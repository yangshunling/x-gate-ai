package com.xgateai.adapter;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.xgateai.component.EncryptUtil;
import com.xgateai.entity.UpstreamProvider;
import com.xgateai.exception.UpstreamException;
import com.xgateai.exception.ClientDisconnectedException;
import com.xgateai.logging.GatewayLogger;
import com.xgateai.constant.GatewayConstant;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * ProxyAdapter HTTP 透传适配器
 * <p>
 * 封装对上游服务的 HTTP 请求，支持非流式与流式（SSE）两种模式，
 * 并负责捕获流式响应中的 usage 数据供日志记录使用。
 * </p>
 *
 * @author xgateai
 * @since 2026/9/8
 */
@Slf4j
@Component
public class ProxyAdapter {

    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");
    private static final int STREAM_BUFFER_SIZE = 8192;

    private final OkHttpClient mainClient;
    private final OkHttpClient testClient;
    private final GatewayLogger gatewayLogger;
    private final EncryptUtil encryptUtil;

    public ProxyAdapter(@Qualifier("mainOkHttpClient") OkHttpClient mainClient,
                        @Qualifier("testOkHttpClient") OkHttpClient testClient,
                        GatewayLogger gatewayLogger,
                        EncryptUtil encryptUtil) {
        this.mainClient = mainClient;
        this.testClient = testClient;
        this.gatewayLogger = gatewayLogger;
        this.encryptUtil = encryptUtil;
    }

    // ==================== 公开接口 ====================

    /**
     * 非流式对话请求转发
     *
     * @param provider    目标上游 Provider
     * @param requestBody 原始请求体 JSON
     * @return            上游响应体字符串
     * @throws IOException 网络异常或上游返回非 2xx 状态码时抛出 UpstreamException
     */
    public String chat(UpstreamProvider provider, String requestBody) throws IOException {
        return postJson(mainClient, provider, GatewayConstant.PATH_CHAT_COMPLETIONS, requestBody);
    }

    /**
     * 流式对话请求转发，返回携带 usage 数据的结果对象
     *
     * @param provider    目标上游 Provider
     * @param requestBody 原始请求体 JSON
     * @param onChunk     数据块回调
     * @return 流式结果，包含 complete 标志和 usage chunk JSON
     * @throws IOException 网络异常时抛出
     */
    public StreamResult streamChat(UpstreamProvider provider, String requestBody,
                                    Consumer<byte[]> onChunk) throws IOException {
        StreamResult result = new StreamResult();
        result.complete = streamOnce(provider, requestBody, onChunk, result);
        return result;
    }

    /**
     * 非流式 Embeddings 请求转发
     *
     * @param provider    目标上游 Provider
     * @param requestBody 原始请求体 JSON
     * @return            上游响应体字符串
     *
     */
    public String embeddings(UpstreamProvider provider, String requestBody) throws IOException {
        return postJson(mainClient, provider, GatewayConstant.PATH_EMBEDDINGS, requestBody);
    }

    /**
     * 连通性测试：向指定渠道发送最小 ping 请求并返回测试结果
     *
     * @param provider  待测试的渠道
     * @param modelName 待测试的真实模型名（模型行粒度）
     * @return 测试结果 Map，包含 ok / latencyMs / message 字段
     */
    public JSONObject testProvider(UpstreamProvider provider, String modelName) {
        long start = System.currentTimeMillis();
        try {
            JSONObject msg = new JSONObject();
            msg.put("role", "user");
            msg.put("content", "ping");
            JSONObject body = new JSONObject();
            body.put("model", modelName);
            body.put("messages", JSONArray.of(msg));
            body.put("max_tokens", 5);
            postJson(testClient, provider, GatewayConstant.PATH_CHAT_COMPLETIONS, body.toJSONString());
            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("latencyMs", System.currentTimeMillis() - start);
            result.put("message", "连通正常");
            return result;
        } catch (Exception e) {
            JSONObject result = new JSONObject();
            result.put("ok", false);
            String message;
            if (e instanceof UpstreamException ue) {
                message = "HTTP " + ue.getUpstreamHttpStatus();
            } else {
                message = resolveMessage(e);
                if (message.length() > 60) message = message.substring(0, 60) + "...";
            }
            if (StrUtil.containsIgnoreCase(message, "decrypt")
                    || StrUtil.containsIgnoreCase(message, "密钥")
                    || StrUtil.containsIgnoreCase(message, "cipher")) {
                message = "API Key 解密失败";
            }
            result.put("message", message);
            return result;
        }
    }

    /**
     * 拉取上游渠道的可用模型列表（GET {baseUrl}/models，OpenAI 兼容）
     *
     * @param baseUrl 上游 Base URL（含 /v1）
     * @param apiKey  明文上游 API Key
     * @return 模型 id 列表
     * @throws IOException 网络异常或上游返回非 2xx 时抛出 UpstreamException
     */
    public java.util.List<String> fetchModelNames(String baseUrl, String apiKey) throws IOException {
        String base = StrUtil.isBlank(baseUrl) ? "" : baseUrl.trim();
        while (base.length() > 1 && base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String url = base + GatewayConstant.PATH_MODELS;
        Request request = new Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + (apiKey == null ? "" : apiKey))
                .get()
                .build();
        try (Response resp = testClient.newCall(request).execute()) {
            String respBody = resp.body() == null ? "" : resp.body().string();
            if (!resp.isSuccessful()) {
                throw new UpstreamException(
                        "上游返回 " + resp.code() + ": " + truncateDisplay(respBody, 200), resp.code());
            }
            java.util.List<String> names = new java.util.ArrayList<>();
            try {
                JSONObject obj = JSON.parseObject(respBody);
                JSONArray data = obj == null ? null : obj.getJSONArray("data");
                if (data != null) {
                    for (int i = 0; i < data.size(); i++) {
                        JSONObject item = data.getJSONObject(i);
                        String id = item == null ? null : item.getString("id");
                        if (StrUtil.isNotBlank(id)) {
                            names.add(id.trim());
                        }
                    }
                }
            } catch (Exception ignore) {
                // 上游返回结构不兼容时忽略，返回空列表由上层提示
            }
            return names;
        }
    }

    // ==================== 私有实现 ====================

    private boolean streamOnce(UpstreamProvider provider, String requestBody,
                                Consumer<byte[]> onChunk, StreamResult result) throws IOException {
        try (Response resp = mainClient.newCall(buildRequest(provider,
                GatewayConstant.PATH_CHAT_COMPLETIONS, requestBody, true)).execute()) {
            if (!resp.isSuccessful()) {
                String errBody = resp.body() == null ? "" : resp.body().string();
                throw new UpstreamException(
                    "上游返回 " + resp.code() + ": " + truncateDisplay(errBody, 200),
                    resp.code());
            }
            try (InputStream in = resp.body().byteStream()) {
                byte[] buf = new byte[STREAM_BUFFER_SIZE];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (n > 0) {
                        byte[] chunk = java.util.Arrays.copyOf(buf, n);
                        try {
                            onChunk.accept(chunk);
                            captureUsage(result, chunk);
                        } catch (ClientDisconnectedException e) {
                            log.warn("流式写出中断, 客户端可能已断开连接, 上游: {}", provider.getName());
                            break;
                        }
                    }
                }
                result.complete = true;
            } catch (IOException e) {
                log.warn("流式读取中断(对端断开), 上游: {}", provider.getName(), e);
            }
        }
        return result.complete;
    }

    private String postJson(OkHttpClient client, UpstreamProvider provider,
                            String path, String jsonBody) throws IOException {
        try (Response resp = client.newCall(buildRequest(provider, path, jsonBody, false)).execute()) {
            String respBody = resp.body() == null ? "" : resp.body().string();
            if (!resp.isSuccessful()) {
                throw new UpstreamException(
                    "上游返回 " + resp.code() + ": " + truncateDisplay(respBody, 200),
                    resp.code());
            }
            return respBody;
        }
    }

    private Request buildRequest(UpstreamProvider provider, String path,
                                  String jsonBody, boolean stream) {
        Request.Builder builder = new Request.Builder()
                .url(provider.getBaseUrl() + path)
                .header("Content-Type", JSON_MEDIA_TYPE.toString())
                .post(RequestBody.create(jsonBody, JSON_MEDIA_TYPE));
        String apiKey = encryptUtil.decrypt(provider.getApiKey());
        if (StrUtil.isNotBlank(apiKey)) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        if (stream) {
            builder.header("Accept", "text/event-stream");
        }
        return builder.build();
    }

    /**
     * 从 SSE 数据块中捕获 usage chunk（最后一条 data: {..."usage":...} 行）
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
            // SSE 数据块解析容错，忽略单行异常不影响整体流
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

    private String truncateDisplay(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    // ==================== ThreadLocal 流结果持有 ====================

    /**
     * 流式调用结果容器，携带 usage 数据片段
     */
    public static class StreamResult {
        /** 是否完整结束（true=正常结束，false=被中断） */
        public boolean complete;
        /** usage chunk JSON 片段（最后一条含 usage 的 data 行） */
        public String usageChunkJson;
    }
}
