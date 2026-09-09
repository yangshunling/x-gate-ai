package com.xgateai.adapter;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.xgateai.component.EncryptUtil;
import com.xgateai.entity.UpstreamProvider;
import com.xgateai.exception.UpstreamException;
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

    public String chat(UpstreamProvider provider, String requestBody) throws IOException {
        return postJson(mainClient, provider, GatewayConstant.PATH_CHAT_COMPLETIONS, requestBody);
    }

    public boolean streamChat(UpstreamProvider provider, String requestBody,
                              Consumer<byte[]> onChunk) throws IOException {
        StreamResult result = new StreamResult();
        boolean complete = streamOnce(provider, requestBody, onChunk, result);
        setStreamResult(result);
        return complete;
    }

    public boolean streamChat(UpstreamProvider provider, String requestBody,
                              Consumer<byte[]> onChunk, StreamResult result) throws IOException {
        return streamOnce(provider, requestBody, onChunk, result);
    }

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
                        onChunk.accept(chunk);
                        captureUsage(result, chunk);
                    }
                }
                result.complete = true;
            } catch (IOException e) {
                log.warn("流式读取中断(对端断开), 上游: {}", provider.getName(), e);
            }
        }
        return result.complete;
    }

    public String embeddings(UpstreamProvider provider, String requestBody) throws IOException {
        return postJson(mainClient, provider, GatewayConstant.PATH_EMBEDDINGS, requestBody);
    }

    public JSONObject testProvider(UpstreamProvider provider) {
        long start = System.currentTimeMillis();
        try {
            JSONObject msg = new JSONObject();
            msg.put("role", "user");
            msg.put("content", "ping");
            JSONObject body = new JSONObject();
            body.put("model", provider.getModelName());
            body.put("messages", com.alibaba.fastjson2.JSONArray.of(msg));
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
            String message = resolveMessage(e);
            if (StrUtil.containsIgnoreCase(message, "decrypt")
                    || StrUtil.containsIgnoreCase(message, "密钥")
                    || StrUtil.containsIgnoreCase(message, "cipher")) {
                message = "上游 API Key 解密失败，请检查加密配置";
            }
            result.put("message", message);
            return result;
        }
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

    public static class StreamResult {
        boolean complete;
        public String usageChunkJson;
    }

    /** ThreadLocal 持有当前流的 usage 结果 */
    private static final ThreadLocal<StreamResult> STREAM_RESULT_HOLDER = new ThreadLocal<>();

    public static void setStreamResult(StreamResult result) {
        STREAM_RESULT_HOLDER.set(result);
    }

    public static StreamResult getStreamResult() {
        return STREAM_RESULT_HOLDER.get();
    }

    public static void clearStreamResult() {
        STREAM_RESULT_HOLDER.remove();
    }
}
