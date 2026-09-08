package com.xgateai.gatewaybridge.adapter;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xgateai.adminbridge.component.EncryptUtil;
import com.xgateai.application.entity.ChannelUpstream;
import com.xgateai.application.entity.CallLog;
import com.xgateai.application.entity.ModelChannel;
import com.xgateai.application.entity.UpstreamProvider;
import com.xgateai.gatewaybridge.config.GatewayConfig;
import com.xgateai.gatewaybridge.constant.GatewayConstant;
import com.xgateai.gatewaybridge.service.CallLogService;
import com.xgateai.mapper.ChannelUpstreamMapper;
import com.xgateai.mapper.ModelChannelMapper;
import com.xgateai.mapper.UpstreamProviderMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * <p>
 * ProxyAdapter 统一网关适配器
 * 出站统一走 OkHttp：仅把 body 里的 model 替换为上游真实模型名后原样转发，
 * 上游的 JSON / SSE 响应原样回传给调用方（不做任何中间翻译、不伪造响应 JSON）。
 * 候选上游按故障转移逐个尝试：连接失败或非 2xx 时切换下一个，开始输出后断开则停止（避免混流）。
 * </p>
 *
 * @author xgateai
 * @since 2026/9/7
 */
@Slf4j
@Component
public class ProxyAdapter {

    private static final MediaType JSON_MEDIA = MediaType.get("application/json; charset=utf-8");
    private static final int STREAM_BUFFER = 8192;
    /** 日志请求体摘要：角色统计 + 最后一条用户消息的截断长度 */
    private static final int SUMMARY_PREVIEW_MAX = 200;

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
    private ModelChannelMapper modelChannelMapper;

    @Resource
    private ChannelUpstreamMapper channelUpstreamMapper;

    @Resource
    private UpstreamProviderMapper upstreamProviderMapper;

    /**
     * 非流式对话：逐个候选尝试，成功后原样返回上游 JSON
     */
    public String chat(String rawBody, String publicModel, String callerKeyName) {
        List<UpstreamProvider> candidates = getCandidates(publicModel);
        String lastError = "";
        for (UpstreamProvider provider : candidates) {
            long start = System.currentTimeMillis();
            try {
                String upstreamJson = postJson(provider, GatewayConstant.PATH_CHAT_COMPLETIONS,
                        rebindModel(rawBody, provider.getModelName()));
                long latencyMs = System.currentTimeMillis() - start;
                recordChatLog(callerKeyName, publicModel, provider, rawBody, upstreamJson, latencyMs, 200);
                log.info("chat 透传成功, model: {}, 上游: {}, 耗时: {}ms", publicModel, provider.getName(), latencyMs);
                return upstreamJson;
            } catch (Exception e) {
                lastError = resolveMessage(e);
                log.warn("chat 调用上游失败, 上游: {}, model: {}, 原因: {}", provider.getName(), provider.getModelName(), lastError);
            }
        }
        throw new RuntimeException("所有上游调用失败: " + lastError);
    }

    /**
     * 流式对话：逐个候选发起 SSE 请求，成功后把上游数据块原样逐段透传给调用方。
     */
    public void chatStream(String rawBody, String publicModel, String callerKeyName,
                           Consumer<byte[]> onChunk) {
        List<UpstreamProvider> candidates = getCandidates(publicModel);
        String lastError = "";
        for (UpstreamProvider provider : candidates) {
            long start = System.currentTimeMillis();
            try {
                StreamResult result = streamOnce(provider, rebindModel(rawBody, provider.getModelName()), onChunk);
                long latencyMs = System.currentTimeMillis() - start;
                recordChatStreamLog(callerKeyName, publicModel, provider, rawBody, result.usageChunkJson, latencyMs);
                if (result.complete) {
                    log.info("流式透传完成, model: {}, 上游: {}, 耗时: {}ms", publicModel, provider.getName(), latencyMs);
                } else {
                    log.error("流式输出中断(上游提前断开), model: {}, 上游: {}", publicModel, provider.getName());
                }
                return;
            } catch (Exception e) {
                lastError = resolveMessage(e);
                log.warn("流式连接上游失败, 切换下一个, 上游: {}, 原因: {}", provider.getName(), lastError);
            }
        }
        throw new RuntimeException("所有上游流式调用失败: " + lastError);
    }

    /**
     * Embeddings：逐个候选尝试，成功后原样返回上游 JSON
     */
    public String embeddings(String rawBody, String publicModel, String callerKeyName) {
        List<UpstreamProvider> candidates = getCandidates(publicModel);
        String lastError = "";
        for (UpstreamProvider provider : candidates) {
            long start = System.currentTimeMillis();
            try {
                String upstreamJson = postJson(provider, GatewayConstant.PATH_EMBEDDINGS,
                        rebindModel(rawBody, provider.getModelName()));
                long latencyMs = System.currentTimeMillis() - start;
                recordEmbeddingLog(callerKeyName, publicModel, provider, rawBody, latencyMs, 200);
                log.info("embeddings 透传成功, model: {}, 上游: {}, 耗时: {}ms", publicModel, provider.getName(), latencyMs);
                return upstreamJson;
            } catch (Exception e) {
                lastError = resolveMessage(e);
                log.warn("embeddings 调用上游失败, 上游: {}, model: {}, 原因: {}", provider.getName(), provider.getModelName(), lastError);
            }
        }
        throw new RuntimeException("所有上游调用失败: " + lastError);
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
            JSONObject body = new JSONObject();
            body.put("model", provider.getModelName());
            body.put("messages", new JSONArray().add(msg));
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
     * 从 DB 实时加载启用的候选上游，按 sort 排序后轮询旋转，返回尝试顺序（含全部候选，供失败切换）
     */
    private List<UpstreamProvider> getCandidates(String publicModel) {
        ModelChannel channel = modelChannelMapper.selectOne(new LambdaQueryWrapper<ModelChannel>()
                .eq(ModelChannel::getPublicModelName, publicModel)
                .eq(ModelChannel::getEnabled, 1)
                .last("LIMIT 1"));
        if (channel == null) {
            throw new RuntimeException("对外模型不存在或未启用: " + publicModel);
        }
        List<ChannelUpstream> binds = channelUpstreamMapper.selectList(new LambdaQueryWrapper<ChannelUpstream>()
                .eq(ChannelUpstream::getChannelId, channel.getId())
                .orderByAsc(ChannelUpstream::getSort));
        List<UpstreamProvider> providers = new ArrayList<>();
        for (ChannelUpstream bind : binds) {
            UpstreamProvider p = upstreamProviderMapper.selectById(bind.getProviderId());
            if (p != null && p.getEnabled() == 1) {
                providers.add(p);
            }
        }
        if (providers.isEmpty()) {
            throw new RuntimeException("对外模型未绑定启用的上游: " + publicModel);
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

    private void recordChatLog(String callerKeyName, String publicModel, UpstreamProvider provider,
                               String requestBody, String upstreamJson, long latencyMs, int httpStatus) {
        try {
            CallLog entry = new CallLog();
            entry.setApiKey(callerKeyName);
            entry.setPublicModel(publicModel);
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
            entry.setCreatedAt(DateUtil.now());
            callLogService.save(entry);
        } catch (Exception e) {
            log.error("记录调用日志失败, publicModel: {}, provider: {}", publicModel, provider.getName(), e);
        }
    }

    private void recordChatStreamLog(String callerKeyName, String publicModel, UpstreamProvider provider,
                                     String requestBody, String usageChunkJson, long latencyMs) {
        try {
            CallLog entry = new CallLog();
            entry.setApiKey(callerKeyName);
            entry.setPublicModel(publicModel);
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
            entry.setCreatedAt(DateUtil.now());
            callLogService.save(entry);
        } catch (Exception e) {
            log.error("记录流式调用日志失败, publicModel: {}, provider: {}", publicModel, provider.getName(), e);
        }
    }

    private void recordEmbeddingLog(String callerKeyName, String publicModel, UpstreamProvider provider,
                                    String requestBody, long latencyMs, int httpStatus) {
        try {
            CallLog entry = new CallLog();
            entry.setApiKey(callerKeyName);
            entry.setPublicModel(publicModel);
            entry.setUpstreamUrl(provider.getBaseUrl() + GatewayConstant.PATH_EMBEDDINGS);
            entry.setUpstreamModel(provider.getModelName());
            entry.setInputTokens(countEmbeddingInputs(requestBody));
            entry.setOutputTokens(0);
            entry.setLatencyMs(latencyMs);
            entry.setHttpStatus(httpStatus);
            entry.setRequestBody(buildEmbeddingSummary(requestBody));
            entry.setCreatedAt(DateUtil.now());
            callLogService.save(entry);
        } catch (Exception e) {
            log.error("记录调用日志失败, publicModel: {}, provider: {}", publicModel, provider.getName(), e);
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
