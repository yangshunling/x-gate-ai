package com.xgateai.gatewaybridge.adapter;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.xgateai.adminbridge.component.EncryptUtil;
import com.xgateai.application.entity.CallLog;
import com.xgateai.application.entity.UpstreamProvider;
import com.xgateai.application.exceptions.CommonException;
import com.xgateai.gatewaybridge.config.GatewayConfig;
import com.xgateai.gatewaybridge.service.CallLogService;
import com.xgateai.gatewaybridge.service.GatewayRouter;
import com.xgateai.gatewaybridge.service.OpenAiClientFactory;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * <p>
 * OpenAiProxyAdapter OpenAI 兼容统一网关适配器
 * 对 Chat / Embeddings 请求做字节级 HTTP 透传：从 GatewayRouter 获取候选上游，
 * 依次尝试调用并在失败时冷却熔断、自动切换到下一个候选（非流式同步等待 + 流式 SSE 原样转发）
 * </p>
 *
 * @author xgateai
 * @since 2026/9/4
 */
@Slf4j
@Component
public class OpenAiProxyAdapter {

    /**
     * Chat 补全上游路径
     */
    private static final String PATH_CHAT_COMPLETIONS = "/chat/completions";

    /**
     * Embeddings 上游路径
     */
    private static final String PATH_EMBEDDINGS = "/embeddings";

    /**
     * 路由调度器
     */
    @Resource
    private GatewayRouter gatewayRouter;

    /**
     * 网关配置（上游超时等）
     */
    @Resource
    private GatewayConfig gatewayConfig;

    /**
     * 透传共用的 WebClient
     */
    @Resource
    private WebClient webClient;

    /**
     * 加密工具（解密上游 apiKey）
     */
    @Resource
    private EncryptUtil encryptUtil;

    /**
     * 调用日志服务
     */
    @Resource
    private CallLogService callLogService;

    /**
     * 上游请求构建工厂
     */
    @Resource
    private OpenAiClientFactory openAiClientFactory;

    /**
     * 非流式对话透传：逐个候选尝试，成功后返回上游原始响应；全部失败抛出 CommonException
     *
     * @param requestBody 客户端原始请求体（OpenAI Chat Completion 格式）
     * @param publicModel 对外模型名
     * @return 上游调用结果（原始响应字符串 + 实际命中的上游 + 耗时）
     */
    public UpstreamCallResult chat(JSONObject requestBody, String publicModel) {
        List<UpstreamProvider> candidates = gatewayRouter.getCandidates(publicModel);
        String lastError = "";
        for (UpstreamProvider provider : candidates) {
            long start = System.currentTimeMillis();
            try {
                // 深拷贝请求体并替换为上游真实模型名，避免污染后续候选
                JSONObject upstreamBody = copyBody(requestBody);
                upstreamBody.put("model", provider.getModelName());
                String rawBody = openAiClientFactory.postJson(webClient, provider, PATH_CHAT_COMPLETIONS, upstreamBody, false)
                        .bodyToMono(String.class)
                        .block(Duration.ofMinutes(gatewayConfig.getTimeOutOfMinutes()));
                long latencyMs = System.currentTimeMillis() - start;
                recordCallLog(publicModel, provider, rawBody, latencyMs, 200);
                log.info("非流式 chat 透传成功，publicModel: {}, 上游: {}, 耗时: {}ms", publicModel, provider.getName(), latencyMs);
                return new UpstreamCallResult(rawBody, provider, latencyMs);
            } catch (Exception e) {
                lastError = resolveMessage(e);
                log.warn("非流式 chat 调用上游失败，上游: {}, model: {}, 原因: {}", provider.getName(), provider.getModelName(), lastError);
                gatewayRouter.markCooldown(provider.getId());
            }
        }
        throw new CommonException("所有上游调用失败: " + lastError);
    }

    /**
     * 流式 SSE 对话透传：字节级原样转发（不转 String 二次封装）
     * 单个候选在未输出任何字节前失败时切换下一候选；已在输出中断流则原样把错误抛给客户端
     *
     * @param requestBody 客户端原始请求体
     * @param publicModel 对外模型名
     * @return SSE 流式字节流
     */
    public Flux<DataBuffer> chatStream(JSONObject requestBody, String publicModel) {
        AtomicReference<UpstreamProvider> usedProviderRef = new AtomicReference<>();
        AtomicLong startRef = new AtomicLong(0);
        return Flux.defer(() -> {
            // 候选为空 / 模型不存在时由 getCandidates 抛 CommonException，转为 Flux.error 交还给下游
            List<UpstreamProvider> candidates = gatewayRouter.getCandidates(publicModel);
            startRef.compareAndSet(0, System.currentTimeMillis());
            return streamAttempt(candidates, 0, requestBody, publicModel, usedProviderRef, new AtomicReference<>(""));
        }).doFinally(signal -> {
            // 流结束或出错时统一记录调用日志（需在订阅期间记录最终使用的上游）
            UpstreamProvider provider = usedProviderRef.get();
            if (provider == null) {
                return;
            }
            long latencyMs = System.currentTimeMillis() - startRef.get();
            recordCallLog(publicModel, provider, null, latencyMs, 200);
        });
    }

    /**
     * Embeddings 透传（同非流式 chat 逻辑，仅上游路径不同）
     *
     * @param requestBody 客户端原始请求体（含 model 字段）
     * @param publicModel 对外模型名
     * @return 上游调用结果
     */
    public UpstreamCallResult embeddings(JSONObject requestBody, String publicModel) {
        List<UpstreamProvider> candidates = gatewayRouter.getCandidates(publicModel);
        String lastError = "";
        for (UpstreamProvider provider : candidates) {
            long start = System.currentTimeMillis();
            try {
                JSONObject upstreamBody = copyBody(requestBody);
                upstreamBody.put("model", provider.getModelName());
                String rawBody = openAiClientFactory.postJson(webClient, provider, PATH_EMBEDDINGS, upstreamBody, false)
                        .bodyToMono(String.class)
                        .block(Duration.ofMinutes(gatewayConfig.getTimeOutOfMinutes()));
                long latencyMs = System.currentTimeMillis() - start;
                recordCallLog(publicModel, provider, rawBody, latencyMs, 200);
                log.info("embeddings 透传成功，publicModel: {}, 上游: {}, 耗时: {}ms", publicModel, provider.getName(), latencyMs);
                return new UpstreamCallResult(rawBody, provider, latencyMs);
            } catch (Exception e) {
                lastError = resolveMessage(e);
                log.warn("embeddings 调用上游失败，上游: {}, model: {}, 原因: {}", provider.getName(), provider.getModelName(), lastError);
                gatewayRouter.markCooldown(provider.getId());
            }
        }
        throw new CommonException("所有上游调用失败: " + lastError);
    }

    /**
     * 管理端上游连通性测试：对指定上游直接发送最小 chat 请求并测量耗时
     * 不走路由 / 冷却逻辑，内部吞掉一切异常，保证不向上抛
     *
     * @param provider 待测试的上游 Provider
     * @return {"ok":true,"latencyMs":xx,"message":"连通正常"} 或 {"ok":false,"message":"<错误原因>"}
     */
    public JSONObject testProvider(UpstreamProvider provider) {
        JSONObject result = new JSONObject();
        long start = System.currentTimeMillis();
        try {
            // 先校验 apiKey 能否正常解密，给出更清晰的配置错误提示
            encryptUtil.decrypt(provider.getApiKey());
            // 构造最小 ping 请求体
            JSONObject pingBody = new JSONObject();
            pingBody.put("model", provider.getModelName());
            pingBody.put("max_tokens", 5);
            JSONArray messages = new JSONArray();
            JSONObject userMessage = new JSONObject();
            userMessage.put("role", "user");
            userMessage.put("content", "ping");
            messages.add(userMessage);
            pingBody.put("messages", messages);

            openAiClientFactory.postJson(webClient, provider, PATH_CHAT_COMPLETIONS, pingBody, false)
                    .bodyToMono(String.class)
                    .block(Duration.ofMinutes(gatewayConfig.getTimeOutOfMinutes()));
            long latencyMs = System.currentTimeMillis() - start;
            result.put("ok", true);
            result.put("latencyMs", latencyMs);
            result.put("message", "连通正常");
        } catch (Exception e) {
            result.put("ok", false);
            String message = resolveMessage(e);
            // apiKey 解密失败优先提示配置问题
            if (StrUtil.containsIgnoreCase(message, "decrypt")
                    || StrUtil.containsIgnoreCase(message, "密钥")
                    || StrUtil.containsIgnoreCase(message, "cipher")) {
                message = "上游 API Key 解密失败，请检查加密配置";
            }
            result.put("message", message);
        }
        return result;
    }

    /**
     * 递归构建流式透传链：当前候选失败且尚未输出时切换到下一个候选
     *
     * @param candidates      候选上游列表
     * @param index           当前候选下标
     * @param requestBody     客户端原始请求体
     * @param publicModel     对外模型名
     * @param usedProviderRef 记录实际已开始输出（最终使用）的上游
     * @param lastErrorRef    记录最后一次失败原因
     * @return 流式字节 Flux
     */
    private Flux<DataBuffer> streamAttempt(List<UpstreamProvider> candidates, int index, JSONObject requestBody,
                                           String publicModel, AtomicReference<UpstreamProvider> usedProviderRef,
                                           AtomicReference<String> lastErrorRef) {
        if (index >= candidates.size()) {
            // 全部候选均未开始输出即失败
            return Flux.error(new CommonException("所有上游流式调用失败: " + lastErrorRef.get()));
        }
        UpstreamProvider provider = candidates.get(index);
        JSONObject upstreamBody = copyBody(requestBody);
        upstreamBody.put("model", provider.getModelName());
        // 标记当前候选是否已经开始向客户端输出字节
        AtomicBoolean hasOutput = new AtomicBoolean(false);
        return openAiClientFactory.postJson(webClient, provider, PATH_CHAT_COMPLETIONS, upstreamBody, true)
                .bodyToFlux(DataBuffer.class)
                .doOnNext(dataBuffer -> {
                    // 一旦有字节流出即记录最终使用的上游，供 doFinally 记账
                    hasOutput.set(true);
                    usedProviderRef.compareAndSet(null, provider);
                })
                .onErrorResume(throwable -> {
                    // 已在输出中断流：原样抛给客户端，不再切换
                    if (hasOutput.get()) {
                        return Flux.error(throwable);
                    }
                    String error = resolveMessage(throwable);
                    lastErrorRef.set(error);
                    log.warn("流式 chat 调用上游失败，上游: {}, 原因: {}", provider.getName(), error);
                    gatewayRouter.markCooldown(provider.getId());
                    // 尚未输出任何字节：冷却当前上游并尝试下一个候选
                    return streamAttempt(candidates, index + 1, requestBody, publicModel, usedProviderRef, lastErrorRef);
                });
    }

    /**
     * 记录一次成功/已接入上游的调用日志（token 从 usage 中尽量解析，缺省为 0）
     *
     * @param publicModel 对外模型名
     * @param provider    实际命中的上游
     * @param rawBody     上游原始响应（流式时为 null）
     * @param latencyMs   耗时（毫秒）
     * @param httpStatus  HTTP 状态码
     */
    private void recordCallLog(String publicModel, UpstreamProvider provider, String rawBody,
                               long latencyMs, int httpStatus) {
        try {
            CallLog callLog = new CallLog();
            callLog.setApiKey(null);
            callLog.setPublicModel(publicModel);
            callLog.setUpstreamUrl(provider.getBaseUrl() + PATH_CHAT_COMPLETIONS);
            callLog.setUpstreamModel(provider.getModelName());
            callLog.setInputTokens(0);
            callLog.setOutputTokens(0);
            // 尽量从响应 usage 解析 token 统计
            if (StrUtil.isNotBlank(rawBody)) {
                JSONObject usage = JSONObject.parseObject(rawBody).getJSONObject("usage");
                if (usage != null) {
                    callLog.setInputTokens(usage.getIntValue("prompt_tokens", 0));
                    callLog.setOutputTokens(usage.getIntValue("completion_tokens", 0));
                }
            }
            callLog.setLatencyMs(latencyMs);
            callLog.setHttpStatus(httpStatus);
            callLog.setCreatedAt(DateUtil.formatDateTime(new Date()));
            callLogService.save(callLog);
        } catch (Exception e) {
            // 日志记录失败不影响主链路
            log.error("记录调用日志失败, publicModel: {}, provider: {}", publicModel, provider.getName(), e);
        }
    }

    /**
     * 深拷贝请求体（每次候选尝试独立副本，避免相互污染）
     *
     * @param requestBody 原始请求体
     * @return 深拷贝后的请求体
     */
    private JSONObject copyBody(JSONObject requestBody) {
        return JSONObject.parseObject(requestBody.toJSONString());
    }

    /**
     * 提取异常链中最底层可读的错误原因
     *
     * @param throwable 异常
     * @return 可读错误描述
     */
    private String resolveMessage(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        if (StrUtil.isBlank(message)) {
            message = cause.getClass().getSimpleName();
        }
        return message;
    }
}
