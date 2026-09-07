package com.xgateai.application.controller;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.xgateai.application.entity.ModelChannel;
import com.xgateai.application.exceptions.CommonException;
import com.xgateai.gatewaybridge.adapter.OpenAiProxyAdapter;
import com.xgateai.gatewaybridge.adapter.UpstreamCallResult;
import com.xgateai.gatewaybridge.constant.GatewayConstant;
import com.xgateai.gatewaybridge.service.GatewayRouter;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * <p>
 * GatewayController OpenAI 兼容对外网关控制器（/v1）
 * 提供 chat/completions（含 SSE 流式）、embeddings、models 三个对外端点，
 * 由 ApiKeyInterceptor 拦截校验后进入，内部将请求透传至动态上游
 * </p>
 *
 * @author xgateai
 * @since 2026/9/4
 */
@Slf4j
@RestController
@CrossOrigin
@RequestMapping("/v1")
public class GatewayController {

    /**
     * OpenAI 兼容透传适配器
     */
    @Resource
    private OpenAiProxyAdapter openAiProxyAdapter;

    /**
     * 网关路由调度器
     */
    @Resource
    private GatewayRouter gatewayRouter;

    /**
     * Chat Completions 端点：按 body.stream 区分非流式（JSON 透传）与 SSE 流式透传
     * 路由模型以鉴权 Key 对应的对客服务为准（body.model 忽略，保持 OpenAI 兼容）
     *
     * @param rawBody 客户端请求体原始 JSON 字符串
     * @return 非流式为 JSON 字符串响应体；流式为 text/event-stream 的字节 Flux
     */
    @PostMapping("/chat/completions")
    public ResponseEntity<?> chatCompletions(@RequestBody String rawBody, HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            JSONObject body = JSON.parseObject(rawBody);
            if (body == null) {
                throw new CommonException("请求体为空或不是合法 JSON");
            }
            ModelChannel channel = resolveChannel(request);
            if (channel == null) {
                log.error("[DEBUG] 无法识别调用方对客服务，request attribute: {}", request.getAttribute(GatewayConstant.ATTR_API_KEY));
                throw new CommonException("无法识别调用方对客服务");
            }
            String publicModel = channel.getPublicModelName();
            String callerKeyName = channel.getPublicModelName();
            boolean stream = body.getBooleanValue("stream");
            log.info("[DEBUG] 开始处理请求, publicModel: {}, stream: {}, apiKey: {}", publicModel, stream, callerKeyName);
            if (stream) {
                log.info("[DEBUG] 进入流式处理分支");
                // SSE 流式：直接写入 HttpServletResponse
                // 不能用 ResponseEntity<StreamingResponseBody> + text/event-stream，Spring MVC 无对应 converter
                response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
                response.setCharacterEncoding("UTF-8");
                response.flushBuffer();
                AtomicBoolean hasError = new AtomicBoolean(false);
                AtomicReference<String> errorMsg = new AtomicReference<>("");
                try {
                    Flux<DataBuffer> flux = openAiProxyAdapter.chatStream(body, publicModel, callerKeyName)
                            .doOnError(throwable -> {
                                hasError.set(true);
                                errorMsg.set(resolveSSEErrorMessage(throwable));
                                log.warn("SSE 流式调用失败，publicModel: {}, 错误: {}", publicModel, throwable.getMessage());
                            });
                    flux.doOnNext(dataBuffer -> {
                        try {
                            int size = dataBuffer.readableByteCount();
                            byte[] bytes = new byte[size];
                            dataBuffer.read(bytes);
                            response.getOutputStream().write(bytes);
                            response.getOutputStream().flush();
                        } catch (IOException e) {
                            log.error("SSE 流式写出失败", e);
                            throw new RuntimeException("SSE 流式写出失败", e);
                        } finally {
                            DataBufferUtils.release(dataBuffer);
                        }
                    }).blockLast();
                } catch (Exception e) {
                    hasError.set(true);
                    errorMsg.set(resolveSSEErrorMessage(e));
                    log.error("SSE 流式处理异常，publicModel: {}", publicModel, e);
                }
                if (hasError.get() && !errorMsg.get().isEmpty()) {
                    String errorEvent = "data: {\"error\":{\"message\":\"" + errorMsg.get().replace("\"", "\\\\\"") + "\",\"type\":\"upstream_error\",\"code\":null}}\n\n";
                    response.getOutputStream().write(errorEvent.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    response.getOutputStream().flush();
                }
                log.info("[DEBUG] SSE 流式响应完毕");
                return null;
            }
            // 非流式：完整 JSON 原样返回
            log.info("[DEBUG] 进入非流式处理分支");
            UpstreamCallResult result = openAiProxyAdapter.chat(body, publicModel, callerKeyName);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(result.getRawBody());
        } catch (CommonException ex) {
            return buildErrorResponse(400, "invalid_request_error", ex.getMessage());
        } catch (Exception ex) {
            log.error("处理 /v1/chat/completions 请求异常", ex);
            return buildErrorResponse(500, "server_error", ex.getMessage());
        }
    }

    /**
     * Embeddings 端点：非流式透传，返回上游原始 JSON
     * 路由模型以鉴权 Key 对应的对客服务为准
     *
     * @param rawBody 客户端请求体原始 JSON 字符串
     * @return 上游原始 JSON 响应体
     */
    @PostMapping("/embeddings")
    public ResponseEntity<?> embeddings(@RequestBody String rawBody, HttpServletRequest request) {
        try {
            JSONObject body = JSON.parseObject(rawBody);
            if (body == null) {
                throw new CommonException("请求体为空或不是合法 JSON");
            }
            ModelChannel channel = resolveChannel(request);
            if (channel == null) {
                throw new CommonException("无法识别调用方对客服务");
            }
            String publicModel = channel.getPublicModelName();
            String callerKeyName = channel.getPublicModelName();
            UpstreamCallResult result = openAiProxyAdapter.embeddings(body, publicModel, callerKeyName);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(result.getRawBody());
        } catch (CommonException ex) {
            return buildErrorResponse(400, "invalid_request_error", ex.getMessage());
        } catch (Exception ex) {
            log.error("处理 /v1/embeddings 请求异常", ex);
            return buildErrorResponse(500, "server_error", ex.getMessage());
        }
    }

    /**
     * Models 端点：返回当前 Key 对应的对客服务（OpenAI 列表格式）
     *
     * @return OpenAI 风格模型列表 JSON
     */
    @GetMapping("/models")
    public ResponseEntity<String> listModels(HttpServletRequest request) {
        ModelChannel channel = resolveChannel(request);
        JSONArray data = new JSONArray();
        if (channel != null) {
            JSONObject item = new JSONObject();
            item.put("id", channel.getPublicModelName());
            item.put("object", "model");
            item.put("created", 1686935002);
            item.put("owned_by", "x-gate-ai");
            data.add(item);
        }
        JSONObject result = new JSONObject();
        result.put("object", "list");
        result.put("data", data);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(result.toJSONString());
    }

    /**
     * 从请求属性中取出拦截器放入的 ModelChannel 记录
     */
    private ModelChannel resolveChannel(HttpServletRequest request) {
        Object attribute = request.getAttribute(GatewayConstant.ATTR_API_KEY);
        if (attribute instanceof ModelChannel channel) {
            return channel;
        }
        return null;
    }

    /**
     * 构造 OpenAI 风格错误响应体：{"error":{"message":...,"type":...,"code":null}}
     *
     * @param httpStatus HTTP 状态码
     * @param errorType  错误类型
     * @param message    错误消息
     * @return 携带错误 JSON 的响应
     */
    private ResponseEntity<String> buildErrorResponse(int httpStatus, String errorType, String message) {
        JSONObject error = new JSONObject();
        error.put("message", StrUtil.isBlank(message) ? "未知错误" : message);
        error.put("type", errorType);
        error.put("code", null);
        JSONObject body = new JSONObject();
        body.put("error", error);
        return ResponseEntity.status(httpStatus)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body.toJSONString());
    }

    /**
     * 从异常中提取可读的错误消息（用于 SSE 错误事件）
     *
     * @param throwable 异常
     * @return 错误消息
     */
    private String resolveSSEErrorMessage(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        if (StrUtil.isBlank(message)) {
            message = cause.getClass().getSimpleName();
        }
        // 截取前 200 字符避免过长
        if (message.length() > 200) {
            message = message.substring(0, 200) + "...";
        }
        return message;
    }
}
