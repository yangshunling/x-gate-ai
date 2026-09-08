package com.xgateai.gatewaybridge.adapter;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xgateai.adminbridge.component.EncryptUtil;
import com.xgateai.application.constant.CommonConstant;
import com.xgateai.application.entity.CallLog;
import com.xgateai.application.entity.ModelChannel;
import com.xgateai.application.entity.UpstreamProvider;
import com.xgateai.application.exceptions.CommonException;
import com.xgateai.gatewaybridge.config.GatewayConfig;
import com.xgateai.gatewaybridge.constant.GatewayConstant;
import com.xgateai.gatewaybridge.logging.GatewayLog;
import com.xgateai.gatewaybridge.service.CallLogService;
import com.xgateai.mapper.UpstreamProviderMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * <p>
 * ProxyAdapter 统一网关适配器
 * 出站统一走 OkHttp：仅把 body 里的 model 替换为上游真实模型名后原样转发，
 * 上游的 JSON / SSE 响应原样回传给调用方（不做任何中间翻译、不伪造响应 JSON）。
 * 路由语义：按客户端请求里的 model 精确匹配全池启用的上游渠道（model_name 相同），
 * 在同一模型命中的候选间做轮询与故障转移；Key 若被限定为某模型则只允许该模型，
 * 限定为空(default) 表示可请求池内任意模型（请求字面 default 时按全池路由，自动在
 * 全部启用渠道中轮询并替换为各候选真实模型名）。不同模型的渠道不会参与同模型故障转移。
 * 每个请求输出一段分块美观的网关日志，完整记录：调用方 Key、候选顺序、逐次尝试结果、
 * 故障转移链路与最终落点。
 * </p>
 *
 * @author xgateai
 * @since 2026/9/7
 */
@Slf4j
@Component
public class ProxyAdapter {

    /** 网关业务日志专用 Logger（logback 路由到控制台 + logs/gateway.log） */
    private static final Logger GATEWAY_LOGGER = LoggerFactory.getLogger("GATEWAY");
    /** 网关框式日志专用 Logger（仅控制台，带 ANSI 彩色，便于实时观察） */
    private static final Logger GATEWAY_CONSOLE = LoggerFactory.getLogger("GATEWAY_CONSOLE");

    private static final MediaType JSON_MEDIA = MediaType.get("application/json; charset=utf-8");
    private static final int STREAM_BUFFER = 8192;
    /** 日志请求体摘要：角色统计 + 最后一条用户消息的截断长度 */
    private static final int SUMMARY_PREVIEW_MAX = 200;
    /** URL 日志展示截断长度 */
    private static final int URL_DISPLAY_MAX = 42;

    /**
     * 通道 ID -> 轮询计数器（请求级，不持久化）
     */
    private final ConcurrentHashMap<Long, AtomicLong> roundRobinCounter = new ConcurrentHashMap<>();

    @Resource
    private OkHttpClient okHttpClient;

    @Resource
    private EncryptUtil encryptUtil;

    @Resource
    private CallLogService callLogService;

    @Resource
    private UpstreamProviderMapper upstreamProviderMapper;

    /**
     * 非流式对话：按请求 model 匹配候选逐个尝试，成功后原样返回上游 JSON
     */
    public String chat(ModelChannel channel, String rawBody) {
        String requestedModel = parseRequestedModel(rawBody);
        List<UpstreamProvider> candidates = resolveCandidates(channel, requestedModel);
        String lastError = "";
        List<String> chain = new ArrayList<>();
        long totalStart = System.currentTimeMillis();
        for (int i = 0; i < candidates.size(); i++) {
            UpstreamProvider provider = candidates.get(i);
            long start = System.currentTimeMillis();
            try {
                String upstreamJson = postJson(provider, GatewayConstant.PATH_CHAT_COMPLETIONS,
                        rebindModel(rawBody, provider.getModelName()));
                long latencyMs = System.currentTimeMillis() - start;
                JSONObject usage = recordChatLog(channel, provider, rawBody, upstreamJson, latencyMs, 200);
                chain.add(chainEntry(provider, "OK", null));
                logCall("chat", channel, requestedModel, rawBody, false, "SUCCESS", provider,
                        System.currentTimeMillis() - totalStart, usage, chain);
                return upstreamJson;
            } catch (Exception e) {
                lastError = resolveMessage(e);
                chain.add(chainEntry(provider, "FAIL", lastError));
            }
        }
        logCall("chat", channel, requestedModel, rawBody, false, "ALL_FAILED", null,
                System.currentTimeMillis() - totalStart, null, chain);
        throw new CommonException("所有上游调用失败: " + lastError);
    }

    /**
     * 流式对话：按请求 model 匹配候选逐个发起 SSE 请求，成功后把上游数据块原样逐段透传给调用方。
     */
    public void chatStream(ModelChannel channel, String rawBody,
                           Consumer<byte[]> onChunk) {
        String requestedModel = parseRequestedModel(rawBody);
        List<UpstreamProvider> candidates = resolveCandidates(channel, requestedModel);
        String lastError = "";
        List<String> chain = new ArrayList<>();
        long totalStart = System.currentTimeMillis();
        for (int i = 0; i < candidates.size(); i++) {
            UpstreamProvider provider = candidates.get(i);
            long start = System.currentTimeMillis();
            try {
                StreamResult result = streamOnce(provider, rebindModel(rawBody, provider.getModelName()), onChunk);
                long latencyMs = System.currentTimeMillis() - start;
                JSONObject usage = recordChatStreamLog(channel, provider, rawBody, result.usageChunkJson, latencyMs);
                if (result.complete) {
                    chain.add(chainEntry(provider, "OK", null));
                    logCall("chat", channel, requestedModel, rawBody, true, "SUCCESS", provider,
                            System.currentTimeMillis() - totalStart, usage, chain);
                } else {
                    chain.add(chainEntry(provider, "BROKEN", null));
                    logCall("chat", channel, requestedModel, rawBody, true, "INTERRUPTED", provider,
                            System.currentTimeMillis() - totalStart, usage, chain);
                }
                return;
            } catch (Exception e) {
                lastError = resolveMessage(e);
                chain.add(chainEntry(provider, "FAIL", lastError));
            }
        }
        logCall("chat", channel, requestedModel, rawBody, true, "ALL_FAILED", null,
                System.currentTimeMillis() - totalStart, null, chain);
        throw new CommonException("所有上游流式调用失败: " + lastError);
    }

    /**
     * Embeddings：按请求 model 匹配候选逐个尝试，成功后原样返回上游 JSON
     */
    public String embeddings(ModelChannel channel, String rawBody) {
        String requestedModel = parseRequestedModel(rawBody);
        List<UpstreamProvider> candidates = resolveCandidates(channel, requestedModel);
        String lastError = "";
        List<String> chain = new ArrayList<>();
        long totalStart = System.currentTimeMillis();
        for (int i = 0; i < candidates.size(); i++) {
            UpstreamProvider provider = candidates.get(i);
            long start = System.currentTimeMillis();
            try {
                String upstreamJson = postJson(provider, GatewayConstant.PATH_EMBEDDINGS,
                        rebindModel(rawBody, provider.getModelName()));
                long latencyMs = System.currentTimeMillis() - start;
                JSONObject usage = recordEmbeddingLog(channel, provider, rawBody, latencyMs, 200);
                chain.add(chainEntry(provider, "OK", null));
                logCall("embedding", channel, requestedModel, rawBody, false, "SUCCESS", provider,
                        System.currentTimeMillis() - totalStart, usage, chain);
                return upstreamJson;
            } catch (Exception e) {
                lastError = resolveMessage(e);
                chain.add(chainEntry(provider, "FAIL", lastError));
            }
        }
        logCall("embedding", channel, requestedModel, rawBody, false, "ALL_FAILED", null,
                System.currentTimeMillis() - totalStart, null, chain);
        throw new CommonException("所有上游调用失败: " + lastError);
    }

    /**
     * 连通性测试：向上游发一个最小 chat 请求验证
     */
    public JSONObject testProvider(UpstreamProvider provider) {
        JSONObject result = new JSONObject();
        long start = System.currentTimeMillis();
        try {
            JSONObject msg = new JSONObject();
            msg.put("role", "user");
            msg.put("content", "ping");
            JSONArray messages = new JSONArray();
            messages.add(msg);
            JSONObject body = new JSONObject();
            body.put("model", provider.getModelName());
            body.put("messages", messages);
            body.put("max_tokens", 5);
            postJson(provider, GatewayConstant.PATH_CHAT_COMPLETIONS, body.toJSONString());
            result.put("ok", true);
            result.put("latencyMs", System.currentTimeMillis() - start);
            result.put("message", "连通正常");
        } catch (Exception e) {
            result.put("ok", false);
            String message = resolveMessage(e);
            if (StrUtil.containsIgnoreCase(message, "decrypt") || StrUtil.containsIgnoreCase(message, "密钥")
                    || StrUtil.containsIgnoreCase(message, "cipher")) {
                message = "上游 API Key 解密失败，请检查加密配置";
            }
            result.put("message", message);
        }
        return result;
    }

    /**
     * 查询当前 Key 可用的对外模型列表（OpenAI /v1/models 语义）：
     * 限定模型的 Key 只返回该模型；default 返回全池启用的去重模型集合
     */
    public JSONArray listAvailableModels(ModelChannel channel) {
        JSONArray data = new JSONArray();
        String pinned = StrUtil.blankToDefault(channel.getModelName(), "");
        if (StrUtil.isNotBlank(pinned)) {
            data.add(modelItem(pinned));
            return data;
        }
        List<UpstreamProvider> providers = upstreamProviderMapper.selectList(
                new LambdaQueryWrapper<UpstreamProvider>()
                        .eq(UpstreamProvider::getEnabled, CommonConstant.ENABLED)
                        .orderByAsc(UpstreamProvider::getId));
        Set<String> models = new LinkedHashSet<>();
        for (UpstreamProvider p : providers) {
            if (StrUtil.isNotBlank(p.getModelName())) {
                models.add(p.getModelName());
            }
        }
        for (String model : models) {
            data.add(modelItem(model));
        }
        return data;
    }

    private JSONObject modelItem(String model) {
        JSONObject item = new JSONObject();
        item.put("id", model);
        item.put("object", "model");
        item.put("created", 1686935002);
        item.put("owned_by", "x-gate-ai");
        return item;
    }

    // ==================== 网关单行日志 ====================

    /**
     * 请求结束输出唯一一行网关日志，完整携带：调用方 Key、请求模型、上游尝试链（含故障转移）、
     * 最终落点、耗时、Token 与请求摘要，便于 grep/tail 排查。
     */
    private void logCall(String type, ModelChannel channel, String requestedModel, String rawBody, boolean stream,
                          String result, UpstreamProvider finalProvider, long costMs,
                          JSONObject usage, List<String> chain) {
        // 1. 原有单行格式，保留给 gateway.log 文件查阅
        String path = "chat".equals(type) ? GatewayConstant.PATH_CHAT_COMPLETIONS : GatewayConstant.PATH_EMBEDDINGS;
        Integer in = usage == null ? null : usage.getInteger("prompt_tokens");
        Integer out = usage == null ? null : usage.getInteger("completion_tokens");
        StringBuilder sb = new StringBuilder(256);
        sb.append(type).append(' ').append(path)
                .append(" stream=").append(stream)
                .append(" channelId=").append(channel.getId())
                .append(" customer=").append(StrUtil.blankToDefault(channel.getPublicModelName(), "-"))
                .append(" model=").append(requestedModel)
                .append(" key=").append(GatewayLog.maskKey(channel.getApiKey()))
                .append(" ip=").append(GatewayLog.getMdc(GatewayLog.MDC_CLIENT_IP))
                .append(" result=").append(result);
        if (finalProvider != null) {
            sb.append(" upstream=").append(finalProvider.getName()).append("(#").append(finalProvider.getId()).append(')')
                    .append(" upstreamModel=").append(finalProvider.getModelName())
                    .append(" url=").append(StrUtil.maxLength(finalProvider.getBaseUrl() + path, URL_DISPLAY_MAX));
        }
        sb.append(" cost=").append(costMs).append("ms");
        if (in != null || out != null) {
            sb.append(" tokens=in:").append(in == null ? "?" : in)
                    .append("/out:").append(out == null ? "?" : out);
        }
        String payload = payloadDesc(type, rawBody);
        if (StrUtil.isNotBlank(payload)) {
            sb.append(' ').append(payload);
        }
        if (chain != null && !chain.isEmpty()) {
            sb.append(" chain=").append(String.join(" -> ", chain));
        }
        switch (result) {
            case "SUCCESS" -> GATEWAY_LOGGER.info(sb.toString());
            case "INTERRUPTED" -> GATEWAY_LOGGER.warn(sb.toString());
            default -> GATEWAY_LOGGER.error(sb.toString());
        }
        // 2. 控制台专用框式日志（带 ANSI 颜色，仅打到终端，不写文件）
        printBoxedLog(type, channel, requestedModel, rawBody, stream, result,
                finalProvider, costMs, in, out, payload, chain);
    }

    /**
     * 一次上游尝试的链节描述：名称(#id/模型):状态(失败原因)
     */
    private String chainEntry(UpstreamProvider provider, String status, String error) {
        StringBuilder sb = new StringBuilder();
        sb.append(provider.getName()).append("(#").append(provider.getId())
                .append('/').append(provider.getModelName()).append("):").append(status);
        if (error != null) {
            sb.append('(').append(StrUtil.maxLength(error, 60)).append(')');
        }
        return sb.toString();
    }

    /**
     * 请求体轻量摘要（仅用于日志单行展示）：角色分布 + 截断的用户消息预览
     */
    private String payloadDesc(String type, String rawBody) {
        String summaryJson = "chat".equals(type) ? buildChatSummary(rawBody) : buildEmbeddingSummary(rawBody);
        if (summaryJson == null) {
            return "";
        }
        JSONObject summary = JSON.parseObject(summaryJson);
        StringBuilder sb = new StringBuilder();
        JSONObject roles = summary.getJSONObject("roles");
        if (roles != null && !roles.isEmpty()) {
            List<String> parts = new ArrayList<>();
            for (String role : roles.keySet()) {
                parts.add(role + ":" + roles.getIntValue(role));
            }
            sb.append("roles=").append(String.join(",", parts));
        }
        String preview = summary.getString("preview");
        if (StrUtil.isNotBlank(preview)) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append("preview=\"").append(StrUtil.maxLength(preview, 60)).append('"');
        }
        Integer inputCount = summary.getInteger("inputCount");
        if (inputCount != null) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append("input=").append(inputCount);
        }
        return sb.toString();
    }

    // ==================== 出站 HTTP（OkHttp） ====================

    /**
     * POST JSON 并返回完整响应体；非 2xx 抛异常交由上层切换候选
     */
    private String postJson(UpstreamProvider provider, String path, String jsonBody) throws IOException {
        try (Response resp = okHttpClient.newCall(buildRequest(provider, path, jsonBody, false)).execute()) {
            String respBody = resp.body() == null ? "" : resp.body().string();
            if (!resp.isSuccessful()) {
                throw new IOException("上游返回 " + resp.code() + ": " + StrUtil.maxLength(respBody, 200));
            }
            return respBody;
        }
    }

    /**
     * 向单个上游发起流式请求并逐块透传
     *
     * @return complete=false 表示开始输出后对端中断（此时不可切换候选，避免混流）
     */
    private StreamResult streamOnce(UpstreamProvider provider, String upstreamBody,
                                    Consumer<byte[]> onChunk) throws IOException {
        StreamResult result = new StreamResult();
        try (Response resp = okHttpClient.newCall(buildRequest(provider, GatewayConstant.PATH_CHAT_COMPLETIONS, upstreamBody, true)).execute()) {
            if (!resp.isSuccessful()) {
                String errBody = resp.body() == null ? "" : resp.body().string();
                throw new IOException("上游返回 " + resp.code() + ": " + StrUtil.maxLength(errBody, 200));
            }
            try (InputStream in = resp.body().byteStream()) {
                byte[] buf = new byte[STREAM_BUFFER];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (n > 0) {
                        byte[] chunk = Arrays.copyOf(buf, n);
                        onChunk.accept(chunk);
                        captureUsage(result, chunk);
                    }
                }
                result.complete = true;
            } catch (IOException e) {
                log.warn("流式读取中断(对端断开), 上游: {}", provider.getName(), e);
            }
        }
        return result;
    }

    private Request buildRequest(UpstreamProvider provider, String path, String jsonBody, boolean stream) {
        Request.Builder builder = new Request.Builder()
                .url(provider.getBaseUrl() + path)
                .header("Authorization", "Bearer " + encryptUtil.decrypt(provider.getApiKey()))
                .header("Content-Type", JSON_MEDIA.toString())
                .post(RequestBody.create(jsonBody, JSON_MEDIA));
        if (stream) {
            builder.header("Accept", "text/event-stream");
        }
        return builder.build();
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 从请求体解析客户端请求的 model（OpenAI 协议必传字段）
     */
    private String parseRequestedModel(String rawBody) {
        JSONObject body = JSON.parseObject(rawBody);
        if (body == null) {
            throw new CommonException("请求体为空或不是合法 JSON");
        }
        String model = StrUtil.trim(body.getString("model"));
        if (StrUtil.isBlank(model)) {
            throw new CommonException("请求体 model 字段不能为空");
        }
        return model;
    }

    /**
     * 解析该 Key 可路由的候选上游：
     * 1) Key 限定模型时只允许请求该模型（model 不一致直接拒绝）；
     * 2) 请求 model 为字面 default 且 Key 未限定模型时，按全池路由：候选为全部启用的渠道，
     *    实际转发时替换为该候选的真实模型名；
     * 3) 否则在全池启用的渠道中精确匹配 model_name 与请求 model 相同的渠道；
     * 4) 命中候选按轮询旋转起点，实现请求级负载均衡与故障转移。
     */
    private List<UpstreamProvider> resolveCandidates(ModelChannel channel, String requestedModel) {
        String pinned = StrUtil.blankToDefault(channel.getModelName(), "");
        if (StrUtil.isNotBlank(pinned) && !pinned.equals(requestedModel)) {
            throw new CommonException("该 Key 已限定仅可调用模型: " + pinned + "，当前请求: " + requestedModel);
        }
        boolean poolRouting = StrUtil.isBlank(pinned) && GatewayConstant.MODEL_POOL.equals(requestedModel);
        LambdaQueryWrapper<UpstreamProvider> wrapper = new LambdaQueryWrapper<UpstreamProvider>()
                .eq(UpstreamProvider::getEnabled, CommonConstant.ENABLED)
                .orderByAsc(UpstreamProvider::getId);
        if (!poolRouting) {
            wrapper.eq(UpstreamProvider::getModelName, requestedModel);
        }
        List<UpstreamProvider> providers = upstreamProviderMapper.selectList(wrapper);
        if (providers.isEmpty()) {
            if (poolRouting) {
                throw new CommonException("池内暂无任何启用的渠道，请先在控制台配置并启用上游服务");
            }
            throw new CommonException("池内暂无启用的渠道提供模型: " + requestedModel);
        }
        // 轮询旋转起点，实现请求级负载均衡
        AtomicLong counter = roundRobinCounter.computeIfAbsent(channel.getId(), k -> new AtomicLong(0));
        int size = providers.size();
        int start = (int) (counter.getAndIncrement() % size);
        List<UpstreamProvider> rotated = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            rotated.add(providers.get((start + i) % size));
        }
        return rotated;
    }

    /**
     * 把请求体里的 model 替换为上游真实模型名后重新序列化（其余字段原样保留）
     */
    private String rebindModel(String rawBody, String upstreamModel) {
        JSONObject body = JSON.parseObject(rawBody);
        if (body == null) {
            throw new IllegalArgumentException("请求体为空或不是合法 JSON");
        }
        body.put("model", upstreamModel);
        return body.toJSONString();
    }

    // ==================== 调用日志 ====================

    private JSONObject recordChatLog(ModelChannel channel, UpstreamProvider provider,
                                     String requestBody, String upstreamJson, long latencyMs, int httpStatus) {
        try {
            CallLog entry = new CallLog();
            entry.setApiKey(channel.getApiKey());
            entry.setPublicModel(channel.getPublicModelName());
            entry.setUpstreamUrl(provider.getBaseUrl() + GatewayConstant.PATH_CHAT_COMPLETIONS);
            entry.setUpstreamModel(provider.getModelName());
            JSONObject usage = parseUsage(upstreamJson);
            if (usage != null) {
                entry.setInputTokens(usage.getIntValue("prompt_tokens", 0));
                entry.setOutputTokens(usage.getIntValue("completion_tokens", 0));
            }
            entry.setLatencyMs(latencyMs);
            entry.setHttpStatus(httpStatus);
            entry.setRequestBody(buildChatSummary(requestBody));
            entry.setCustomerName(channel.getPublicModelName());
            entry.setCreatedAt(DateUtil.now());
            callLogService.save(entry);
            return usage;
        } catch (Exception e) {
            log.error("记录调用日志失败, publicModel: {}, provider: {}", channel.getPublicModelName(), provider.getName(), e);
            return null;
        }
    }

    private JSONObject recordChatStreamLog(ModelChannel channel, UpstreamProvider provider,
                                           String requestBody, String usageChunkJson, long latencyMs) {
        try {
            CallLog entry = new CallLog();
            entry.setApiKey(channel.getApiKey());
            entry.setPublicModel(channel.getPublicModelName());
            entry.setUpstreamUrl(provider.getBaseUrl() + GatewayConstant.PATH_CHAT_COMPLETIONS);
            entry.setUpstreamModel(provider.getModelName());
            JSONObject usage = StrUtil.isBlank(usageChunkJson) ? null : parseUsage(usageChunkJson);
            if (usage != null) {
                entry.setInputTokens(usage.getIntValue("prompt_tokens", 0));
                entry.setOutputTokens(usage.getIntValue("completion_tokens", 0));
            }
            entry.setLatencyMs(latencyMs);
            entry.setHttpStatus(200);
            entry.setRequestBody(buildChatSummary(requestBody));
            entry.setCustomerName(channel.getPublicModelName());
            entry.setCreatedAt(DateUtil.now());
            callLogService.save(entry);
            return usage;
        } catch (Exception e) {
            log.error("记录流式调用日志失败, publicModel: {}, provider: {}", channel.getPublicModelName(), provider.getName(), e);
            return null;
        }
    }

    private JSONObject recordEmbeddingLog(ModelChannel channel, UpstreamProvider provider,
                                          String requestBody, long latencyMs, int httpStatus) {
        try {
            CallLog entry = new CallLog();
            entry.setApiKey(channel.getApiKey());
            entry.setPublicModel(channel.getPublicModelName());
            entry.setUpstreamUrl(provider.getBaseUrl() + GatewayConstant.PATH_EMBEDDINGS);
            entry.setUpstreamModel(provider.getModelName());
            int inputCount = countEmbeddingInputs(requestBody);
            entry.setInputTokens(inputCount);
            entry.setOutputTokens(0);
            entry.setLatencyMs(latencyMs);
            entry.setHttpStatus(httpStatus);
            entry.setRequestBody(buildEmbeddingSummary(requestBody));
            entry.setCustomerName(channel.getPublicModelName());
            entry.setCreatedAt(DateUtil.now());
            callLogService.save(entry);
            JSONObject usage = new JSONObject();
            usage.put("prompt_tokens", inputCount);
            return usage;
        } catch (Exception e) {
            log.error("记录调用日志失败, publicModel: {}, provider: {}", channel.getPublicModelName(), provider.getName(), e);
            return null;
        }
    }

    /**
     * 对话类请求体轻量摘要：只记角色分布 + 最后一条用户消息截断，
     * 不落库完整 system 提示词与多轮对话全文
     */
    private String buildChatSummary(String rawBody) {
        try {
            JSONObject summary = new JSONObject();
            summary.put("type", "chat");
            JSONObject body = JSON.parseObject(rawBody);
            JSONArray messages = body == null ? null : body.getJSONArray("messages");
            if (messages == null || messages.isEmpty()) {
                return summary.toJSONString();
            }
            JSONObject roles = new JSONObject();
            String lastUserText = "";
            for (int i = 0; i < messages.size(); i++) {
                JSONObject msg = messages.getJSONObject(i);
                if (msg == null) continue;
                String role = StrUtil.nullToEmpty(msg.getString("role"));
                roles.merge(role, 1, (oldVal, addVal) -> ((Number) oldVal).intValue() + ((Number) addVal).intValue());
                if ("user".equals(role)) {
                    lastUserText = extractMessageText(msg);
                }
            }
            summary.put("roles", roles);
            summary.put("preview", StrUtil.maxLength(lastUserText, SUMMARY_PREVIEW_MAX));
            return summary.toJSONString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Embeddings 请求体轻量摘要：只记输入条数 + 文本输入截断预览
     */
    private String buildEmbeddingSummary(String rawBody) {
        try {
            JSONObject summary = new JSONObject();
            summary.put("type", "embedding");
            JSONObject body = JSON.parseObject(rawBody);
            Object input = body == null ? null : body.get("input");
            if (input instanceof JSONArray arr) {
                summary.put("inputCount", arr.size());
            } else if (input instanceof String text) {
                summary.put("inputCount", 1);
                summary.put("preview", StrUtil.maxLength(text, SUMMARY_PREVIEW_MAX));
            } else {
                summary.put("inputCount", 0);
            }
            return summary.toJSONString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 提取单条消息的文本内容：兼容字符串 content 与多模态分段数组（[{type:text}]）
     */
    private String extractMessageText(JSONObject message) {
        Object content = message.get("content");
        if (content == null) return "";
        if (content instanceof String text) return text;
        if (content instanceof JSONArray parts) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.size(); i++) {
                JSONObject part = parts.getJSONObject(i);
                if (part != null && "text".equals(part.getString("type"))) {
                    sb.append(StrUtil.nullToEmpty(part.getString("text")));
                }
            }
            return sb.toString();
        }
        return content.toString();
    }

    // ==================== 控制台框式日志（仅终端展示） ====================

    /** ANSI 颜色码 */
    private static final String A_RST   = "\u001B[0m";
    private static final String A_BOLD  = "\u001B[1m";
    private static final String A_CYAN  = "\u001B[36m";
    private static final String A_GREEN = "\u001B[32m";
    private static final String A_RED   = "\u001B[31m";

    /** 费用粗略单价（USD / 百万 token），仅用于日志内成本估算 */
    private static final double PRICE_IN_PER_M = 0.30;
    private static final double PRICE_OUT_PER_M = 1.20;

    /** 框式渲染总宽度（终端字符列） */
    private static final int BOX_WIDTH = 92;
    /** 标签区宽度 */
    private static final int BOX_LABEL = 14;
    /** 值区宽度 */
    private static final int BOX_VALUE = BOX_WIDTH - 7 - BOX_LABEL;

    /**
     * 输出控制台框式日志，便于实时观察单次调用全貌。
     * 仅打到 GATEWAY_CONSOLE（logback 独立控制台 appender，不落文件），
     * 文件侧保留原单行日志便于 grep。
     */
    private void printBoxedLog(String type, ModelChannel channel, String requestedModel, String rawBody,
                               boolean stream, String result, UpstreamProvider provider, long costMs,
                               Integer inTokens, Integer outTokens, String payload, List<String> chain) {
        boolean ok = "SUCCESS".equals(result);
        String statusWord = ok ? "成功" : ("INTERRUPTED".equals(result) ? "中断" : "失败");
        String statusColor = ok ? A_GREEN : A_RED;
        String time = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"));
        String tid = StrUtil.blankToDefault(GatewayLog.getMdc(GatewayLog.MDC_TRACE_ID), "-");
        String customer = StrUtil.blankToDefault(channel.getPublicModelName(), "-");
        String path = "chat".equals(type) ? GatewayConstant.PATH_CHAT_COMPLETIONS : GatewayConstant.PATH_EMBEDDINGS;
        String mode = "chat".equals(type) ? (stream ? "SSE 流式" : "JSON 非流式") : "Embedding";
        String keyMask = GatewayLog.maskKey(channel.getApiKey());
        String ip = GatewayLog.getMdc(GatewayLog.MDC_CLIENT_IP);

        String costStr = costMs < 1000 ? costMs + "ms" : String.format("%.3fs", costMs / 1000.0);
        String modelText = provider == null ? "-"
                : provider.getName() + " #" + provider.getId() + " → " + provider.getModelName();

        String tokensText;
        if (inTokens == null && outTokens == null) {
            tokensText = "—";
        } else {
            int in = inTokens == null ? 0 : inTokens;
            int out = outTokens == null ? 0 : outTokens;
            String cost = String.format("%.4f", estimateCost(in, out));
            tokensText = "📥 输入 " + fmtThousands(in) + " tokens  │  📤 输出 " + fmtThousands(out) + " tokens  │  💰 ~$" + cost;
        }
        String tps = (outTokens != null && outTokens > 0 && costMs > 0)
                ? String.format("%.1f tokens/s", outTokens * 1000.0 / costMs) : "—";
        String perfText = statusWord + "  ✦  耗时 " + costStr + "  ✦  " + tps;

        StringBuilder box = new StringBuilder();
        String hr = "═".repeat(BOX_WIDTH - 2);
        box.append(A_CYAN).append("╔").append(hr).append("╗").append(A_RST).append('\n');
        box.append(A_CYAN).append("║").append(A_BOLD)
                .append(A_CYAN).append(center("🚀 GATEWAY 请求日志 · " + ("chat".equals(type) ? "对话" : "向量"), BOX_WIDTH - 2))
                .append(A_RST).append(A_CYAN).append("║").append(A_RST).append('\n');
        box.append(A_CYAN).append("╠").append(hr).append("╣").append(A_RST).append('\n');

        appendRow(box, "⏰ 时间", time + "   (响应完成)");
        appendRow(box, "🔗 链路 ID", tid);
        appendRow(box, "👤 用户", customer + "  (" + keyMask + ")");
        appendRow(box, "📡 接口", "POST " + path + "  ✦  " + mode + "  ✦  channelId=" + channel.getId()
                + "  ✦  ip=" + ip);
        appendRow(box, "🤖 上游模型", modelText + "  (上游代理)");
        appendRow(box, "📊 Token 用量", tokensText);
        appendRow(box, "⚡ 性能", perfText, statusColor);
        String roles = rolesText(rawBody, type);
        if (StrUtil.isNotBlank(roles)) {
            appendRow(box, "📋 上下文", roles);
        }
        if (chain != null && chain.size() > 1) {
            appendRow(box, "🔁 故障转移", String.join("  →  ", chain));
        }
        box.append(A_CYAN).append("╚").append(hr).append("╝").append(A_RST);
        GATEWAY_CONSOLE.info(box.toString());
    }

    /** 追加一行：边框 + label + 竖线 + value(可选着色)，不足补空格以对齐右边框 */
    private void appendRow(StringBuilder sb, String label, String value) {
        appendRow(sb, label, value, null);
    }

    private void appendRow(StringBuilder sb, String label, String value, String color) {
        String lab = truncDisp(label, BOX_LABEL);
        String val = truncDisp(value, BOX_VALUE);
        int pad = BOX_VALUE - dispWidth(val);
        String coloredVal = color == null ? val : color + val + A_RST;
        sb.append(A_CYAN).append("║  ").append(A_RST)
                .append(lab).append(padRight(BOX_LABEL - dispWidth(lab)))
                .append(A_CYAN).append("│ ").append(A_RST)
                .append(coloredVal)
                .append(" ".repeat(Math.max(0, pad)))
                .append(A_CYAN).append(" ║").append(A_RST).append('\n');
    }

    private String center(String text, int width) {
        int pad = Math.max(0, width - dispWidth(text));
        int l = pad / 2;
        return " ".repeat(l) + text + " ".repeat(pad - l);
    }

    private String padRight(int width) {
        return " ".repeat(Math.max(0, width));
    }

    /** 估算显示宽度：ASCII 占 1 列，CJK/emoji 等按 2 列 */
    private int dispWidth(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            w += (cp > 0x2E7F) ? 2 : 1;
        }
        return w;
    }

    /** 截断到指定显示宽度（超长补 …） */
    private String truncDisp(String s, int max) {
        if (dispWidth(s) <= max) return s;
        StringBuilder sb = new StringBuilder();
        int w = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            int cw = (cp > 0x2E7F) ? 2 : 1;
            if (w + cw > max - 1) break;
            sb.appendCodePoint(cp);
            w += cw;
            i += Character.charCount(cp);
        }
        return sb + "…";
    }

    private String fmtThousands(Integer n) {
        if (n == null) return "?";
        return java.text.NumberFormat.getIntegerInstance().format(n);
    }

    private double estimateCost(int in, int out) {
        return in * PRICE_IN_PER_M / 1_000_000 + out * PRICE_OUT_PER_M / 1_000_000;
    }

    /** 从请求体提取消息角色计数：system:2 ✦ user:10 … */
    private String rolesText(String rawBody, String type) {
        if (!"chat".equals(type)) return "";
        try {
            JSONObject body = JSON.parseObject(rawBody);
            JSONArray msgs = body == null ? null : body.getJSONArray("messages");
            if (msgs == null || msgs.isEmpty()) return "";
            java.util.Map<String, Integer> counter = new java.util.LinkedHashMap<>();
            for (int i = 0; i < msgs.size(); i++) {
                JSONObject msg = msgs.getJSONObject(i);
                if (msg == null) continue;
                String role = StrUtil.nullToEmpty(msg.getString("role"));
                counter.merge(role, 1, Integer::sum);
            }
            if (counter.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            for (java.util.Map.Entry<String, Integer> e : counter.entrySet()) {
                if (sb.length() > 0) sb.append("  ✦  ");
                sb.append(e.getKey()).append(':').append(e.getValue());
            }
            return sb.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private JSONObject parseUsage(String upstreamJson) {
        try {
            JSONObject resp = JSON.parseObject(upstreamJson);
            return resp == null ? null : resp.getJSONObject("usage");
        } catch (Exception e) {
            return null;
        }
    }

    private int countEmbeddingInputs(String requestBody) {
        try {
            JSONObject body = JSON.parseObject(requestBody);
            Object input = body == null ? null : body.get("input");
            if (input instanceof JSONArray arr) {
                return arr.size();
            }
            return input instanceof String ? 1 : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private String resolveMessage(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return StrUtil.isBlank(message) ? cause.getClass().getSimpleName() : message;
    }

    /**
     * 从流式数据块中尽力捕获携带 usage 的 SSE 行（剥离 data: 前缀），供流结束后记账
     */
    private void captureUsage(StreamResult result, byte[] chunk) {
        try {
            String text = new String(chunk, StandardCharsets.UTF_8);
            for (String line : text.split("\r?\n")) {
                if (line.startsWith("data:")) {
                    String data = line.substring("data:".length()).trim();
                    if (data.contains("\"usage\"")) {
                        result.usageChunkJson = data;
                    }
                }
            }
        } catch (Exception ignored) {
            // 透传通道不因记账失败而中断
        }
    }

    /**
     * 一次流式透传的结果
     */
    private static class StreamResult {
        /** 是否完整读到流结束；false 表示开始输出后对端中断 */
        boolean complete;
        /** 最后一个携带 usage 的 SSE 数据行（JSON，已去掉 data: 前缀），可能为 null */
        String usageChunkJson;
    }
}
