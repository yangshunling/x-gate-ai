package com.xgateai.controller;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.xgateai.entity.ModelChannel;
import com.xgateai.constant.GatewayConstant;
import com.xgateai.exception.BadRequestException;
import com.xgateai.exception.ClientDisconnectedException;
import com.xgateai.protocol.AnthropicConverter;
import com.xgateai.protocol.OpenAIResponsesConverter;
import com.xgateai.protocol.StreamTransformer;
import com.xgateai.service.gateway.GatewayService;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * GatewayController 对外网关控制器
 * <p>
 * 提供 OpenAI 兼容的 API 接口，包括对话、Embeddings 和模型列表。
 * 请求/响应均原样透传到上游服务。
 * </p>
 *
 * @author xgateai
 * @since 2026/9/8
 */
@Slf4j
@RestController
@RequestMapping("/v1")
public class GatewayController {

    private final GatewayService gatewayService;
    private final AnthropicConverter anthropicConverter;
    private final OpenAIResponsesConverter responsesConverter;

    /**
     * 构造网关控制器
     *
     * @param gatewayService     网关核心服务
     * @param anthropicConverter Anthropic Messages 协议转换器
     * @param responsesConverter OpenAI Responses 协议转换器
     */
    public GatewayController(GatewayService gatewayService,
                             AnthropicConverter anthropicConverter,
                             OpenAIResponsesConverter responsesConverter) {
        this.gatewayService = gatewayService;
        this.anthropicConverter = anthropicConverter;
        this.responsesConverter = responsesConverter;
    }

    /**
     * Chat Completions：对话补全接口
     * <p>
     * 支持流式和非流式两种模式。
     * 流式模式原样透传上游 SSE 数据块。
     * </p>
     *
     * @param rawBody 原始请求体 JSON
     * @param request HTTP 请求（用于取出鉴权后的通道）
     * @param response HTTP 响应
     * @throws IOException 写出响应时可能抛出
     */
    @PostMapping("/chat/completions")
    public void chatCompletions(@RequestBody String rawBody,
                                HttpServletRequest request,
                                HttpServletResponse response) throws IOException {
        ServletOutputStream out = response.getOutputStream();
        ModelChannel channel = resolveChannel(request);

        if (channel == null) {
            writeError(response, out, 400, "invalid_request_error", "无法识别调用方对客服务");
            return;
        }

        try {
            JSONObject body = JSON.parseObject(rawBody);
            if (body == null) {
                writeError(response, out, 400, "invalid_request_error", "请求体为空或不是合法 JSON");
                return;
            }

            if (body.getBooleanValue("stream")) {
                initSseResponse(response);
                try {
                    gatewayService.chatStream(channel, rawBody, GatewayConstant.PATH_CHAT_COMPLETIONS,
                            chunk -> writeBytes(out, chunk));
                } catch (ClientDisconnectedException e) {
                    log.warn("SSE 流式写出中断, 客户端已断开连接, model: {}", channel.getPublicModelName());
                } catch (Exception ex) {
                    log.error("流式调用上游全部失败, model: {}", channel.getPublicModelName(), ex);
                    writeSseError(out, ex.getMessage());
                }
            } else {
                String upstreamJson = gatewayService.chat(channel, rawBody, GatewayConstant.PATH_CHAT_COMPLETIONS);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                out.write(upstreamJson.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (BadRequestException ex) {
            log.warn("请求校验失败, error: {}", ex.getMessage());
            writeError(response, out, ex.getStatusCode(), ex.getErrorCode(), ex.getMessage());
        } catch (Exception ex) {
            log.error("处理 /v1/chat/completions 请求异常", ex);
            writeError(response, out, 500, "server_error", ex.getMessage());
        }
    }

    /**
     * OpenAI Responses：对话补全接口（兼容 OpenAI Responses SDK）
     * <p>
     * 请求转换为 OpenAI Chat 格式后走统一网关链路，响应/流式事件再转回
     * Responses 格式。支持流式和非流式两种模式。
     * </p>
     *
     * @param rawBody 原始请求体 JSON
     * @param request HTTP 请求（用于取出鉴权后的通道）
     * @param response HTTP 响应
     * @throws IOException 写出响应时可能抛出
     */
    @PostMapping("/responses")
    public void openaiResponses(@RequestBody String rawBody,
                                HttpServletRequest request,
                                HttpServletResponse response) throws IOException {
        ServletOutputStream out = response.getOutputStream();
        ModelChannel channel = resolveChannel(request);

        if (channel == null) {
            writeError(response, out, 400, "invalid_request_error", "无法识别调用方对客服务");
            return;
        }

        try {
            JSONObject body = JSON.parseObject(rawBody);
            if (body == null) {
                writeError(response, out, 400, "invalid_request_error", "请求体为空或不是合法 JSON");
                return;
            }

            String chatBody = responsesConverter.toChatRequest(rawBody);
            if (body.getBooleanValue("stream")) {
                initSseResponse(response);
                try {
                    StreamTransformer transformer =
                            responsesConverter.createStreamTransformer(body.getString("model"));
                    gatewayService.chatStream(channel, chatBody, GatewayConstant.PATH_RESPONSES, chunk -> {
                        byte[] bytes = transformer.transform(chunk);
                        if (bytes.length > 0) {
                            writeBytes(out, bytes);
                        }
                    });
                    byte[] tail = transformer.finish();
                    if (tail.length > 0) {
                        writeBytes(out, tail);
                    }
                } catch (ClientDisconnectedException e) {
                    log.warn("Responses 流式写出中断, 客户端已断开连接, model: {}", channel.getPublicModelName());
                } catch (Exception ex) {
                    log.error("Responses 流式调用上游全部失败, model: {}", channel.getPublicModelName(), ex);
                    writeSseError(out, ex.getMessage());
                }
            } else {
                String upstreamJson = gatewayService.chat(channel, chatBody, GatewayConstant.PATH_RESPONSES);
                String responsesJson = responsesConverter.fromChatResponse(upstreamJson);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                out.write(responsesJson.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (BadRequestException ex) {
            log.warn("Responses 请求校验失败, error: {}", ex.getMessage());
            writeError(response, out, ex.getStatusCode(), ex.getErrorCode(), ex.getMessage());
        } catch (Exception ex) {
            log.error("处理 /v1/responses 请求异常", ex);
            writeError(response, out, 500, "server_error", ex.getMessage());
        }
    }

    /**
     * Anthropic Messages：对话补全接口（兼容 Anthropic SDK）
     * <p>
     * 请求转换为 OpenAI Chat 格式后走统一网关链路，响应/流式事件再转回
     * Anthropic Messages 格式。支持流式和非流式两种模式。
     * </p>
     *
     * @param rawBody 原始请求体 JSON
     * @param request HTTP 请求（用于取出鉴权后的通道）
     * @param response HTTP 响应
     * @throws IOException 写出响应时可能抛出
     */
    @PostMapping("/messages")
    public void anthropicMessages(@RequestBody String rawBody,
                                  HttpServletRequest request,
                                  HttpServletResponse response) throws IOException {
        ServletOutputStream out = response.getOutputStream();
        ModelChannel channel = resolveChannel(request);

        if (channel == null) {
            writeAnthropicError(response, out, 400, "invalid_request_error", "无法识别调用方对客服务");
            return;
        }

        try {
            JSONObject body = JSON.parseObject(rawBody);
            if (body == null) {
                writeAnthropicError(response, out, 400, "invalid_request_error", "请求体为空或不是合法 JSON");
                return;
            }

            String chatBody = anthropicConverter.toChatRequest(rawBody);
            if (body.getBooleanValue("stream")) {
                initSseResponse(response);
                try {
                    StreamTransformer transformer =
                            anthropicConverter.createStreamTransformer(body.getString("model"));
                    gatewayService.chatStream(channel, chatBody, GatewayConstant.PATH_MESSAGES, chunk -> {
                        byte[] bytes = transformer.transform(chunk);
                        if (bytes.length > 0) {
                            writeBytes(out, bytes);
                        }
                    });
                    byte[] tail = transformer.finish();
                    if (tail.length > 0) {
                        writeBytes(out, tail);
                    }
                } catch (ClientDisconnectedException e) {
                    log.warn("Anthropic 流式写出中断, 客户端已断开连接, model: {}", channel.getPublicModelName());
                } catch (Exception ex) {
                    log.error("Anthropic 流式调用上游全部失败, model: {}", channel.getPublicModelName(), ex);
                    writeAnthropicSseError(out, ex.getMessage());
                }
            } else {
                String upstreamJson = gatewayService.chat(channel, chatBody, GatewayConstant.PATH_MESSAGES);
                String anthropicJson = anthropicConverter.fromChatResponse(upstreamJson);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                out.write(anthropicJson.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (BadRequestException ex) {
            log.warn("Anthropic 请求校验失败, error: {}", ex.getMessage());
            writeAnthropicError(response, out, ex.getStatusCode(), ex.getErrorCode(), ex.getMessage());
        } catch (Exception ex) {
            log.error("处理 /v1/messages 请求异常", ex);
            writeAnthropicError(response, out, 500, "api_error", ex.getMessage());
        }
    }

    /**
     * Embeddings：向量化接口
     * <p>非流式透传上游响应。</p>
     *
     * @param rawBody 原始请求体 JSON
     * @param request HTTP 请求（用于取出鉴权后的通道）
     * @return 上游响应 JSON
     */
    @PostMapping("/embeddings")
    public ResponseEntity<String> embeddings(@RequestBody String rawBody, HttpServletRequest request) {
        ModelChannel channel = resolveChannel(request);
        if (channel == null) {
            return buildErrorResponse(400, "invalid_request_error", "无法识别调用方对客服务");
        }

        try {
            JSONObject body = JSON.parseObject(rawBody);
            if (body == null) {
                return buildErrorResponse(400, "invalid_request_error", "请求体为空或不是合法 JSON");
            }

            String upstreamJson = gatewayService.embeddings(channel, rawBody, GatewayConstant.PATH_EMBEDDINGS);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(upstreamJson);
        } catch (BadRequestException ex) {
            return buildErrorResponse(ex.getStatusCode(), ex.getErrorCode(), ex.getMessage());
        } catch (Exception ex) {
            log.error("处理 /v1/embeddings 请求异常", ex);
            return buildErrorResponse(500, "server_error", ex.getMessage());
        }
    }

    /**
     * Models：返回当前 Key 可用的模型列表
     * <p>OpenAI 兼容的 list 格式，owned_by 统一为网关品牌名。</p>
     *
     * @param request HTTP 请求（用于取出鉴权后的通道）
     * @return 模型列表 JSON
     */
    @GetMapping("/models")
    public ResponseEntity<String> listModels(HttpServletRequest request) {
        ModelChannel channel = resolveChannel(request);
        if (channel == null) {
            return buildErrorResponse(400, "invalid_request_error", "无法识别调用方对客服务");
        }

        JSONArray data = new JSONArray();
        data.addAll(gatewayService.listAvailableModels());

        JSONObject result = new JSONObject();
        result.put("object", "list");
        result.put("data", data);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(result.toJSONString());
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 从请求属性中取出鉴权拦截器写入的对客通道
     *
     * @param request HTTP 请求
     * @return 对客通道；未鉴权时返回 null
     */
    private ModelChannel resolveChannel(HttpServletRequest request) {
        Object attr = request.getAttribute(GatewayConstant.ATTR_API_KEY);
        return attr instanceof ModelChannel ch ? ch : null;
    }

    /**
     * 初始化 SSE 流式响应头
     *
     * @param response HTTP 响应
     * @throws IOException 刷新缓冲区时可能抛出
     */
    private void initSseResponse(HttpServletResponse response) throws IOException {
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");
        response.flushBuffer();
    }

    /**
     * 向输出流写字节并刷新；写出失败抛 ClientDisconnectedException 中断上游读取
     *
     * @param out   Servlet 输出流
     * @param bytes 待写出的字节
     */
    private void writeBytes(ServletOutputStream out, byte[] bytes) {
        try {
            out.write(bytes);
            out.flush();
        } catch (Exception e) {
            throw new ClientDisconnectedException("SSE 写出失败, 客户端可能已断开连接", e);
        }
    }

    /**
     * 向 SSE 流写出 OpenAI 兼容的错误事件（data: {error}\n\n）
     *
     * @param out     Servlet 输出流
     * @param message 错误提示信息
     */
    private void writeSseError(ServletOutputStream out, String message) {
        try {
            out.write(("data: " + buildErrorJson("server_error", message) + "\n\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Exception e) {
            log.warn("写出 SSE 错误事件失败, 客户端可能已断开连接", e);
        }
    }

    /**
     * 写出 OpenAI 兼容格式的错误响应
     *
     * @param response HTTP 响应
     * @param out      Servlet 输出流
     * @param status   HTTP 状态码
     * @param type     错误类型标识
     * @param message  错误提示信息
     */
    private void writeError(HttpServletResponse response, ServletOutputStream out,
                            int status, String type, String message) {
        try {
            if (!response.isCommitted()) {
                response.setStatus(status);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            }
            out.write(buildErrorJson(type, message).getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Exception e) {
            log.error("写出错误响应失败", e);
        }
    }

    /**
     * 向 SSE 流写出 Anthropic 格式的 error 事件（event: error\ndata: {...}\n\n）
     *
     * @param out     Servlet 输出流
     * @param message 错误提示信息
     */
    private void writeAnthropicSseError(ServletOutputStream out, String message) {
        try {
            JSONObject error = new JSONObject();
            error.put("type", "api_error");
            error.put("message", message);
            JSONObject body = new JSONObject();
            body.put("type", "error");
            body.put("error", error);
            out.write(("event: error\ndata: " + body.toJSONString() + "\n\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Exception e) {
            log.warn("写出 Anthropic SSE 错误事件失败, 客户端可能已断开连接", e);
        }
    }

    /**
     * 写出 Anthropic Messages 格式的错误响应
     * {@code {"type":"error","error":{"type":...,"message":...}}}
     */
    private void writeAnthropicError(HttpServletResponse response, ServletOutputStream out,
                                     int status, String type, String message) {
        try {
            if (!response.isCommitted()) {
                response.setStatus(status);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            }
            JSONObject error = new JSONObject();
            error.put("type", type);
            error.put("message", StrUtil.isBlank(message) ? "未知错误" : message);
            JSONObject body = new JSONObject();
            body.put("type", "error");
            body.put("error", error);
            out.write(body.toJSONString().getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Exception e) {
            log.error("写出 Anthropic 错误响应失败", e);
        }
    }

    /**
     * 构建错误响应体（含 HTTP 状态码）
     *
     * @param status  HTTP 状态码
     * @param type    错误类型标识
     * @param message 错误提示信息
     * @return ResponseEntity 包装的错误响应
     */
    private ResponseEntity<String> buildErrorResponse(int status, String type, String message) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(buildErrorJson(type, message));
    }

    /**
     * 构建错误响应 JSON 字符串（OpenAI 兼容的 {"error":{message,type,code}} 结构）
     *
     * @param type    错误类型标识
     * @param message 错误提示信息
     * @return 错误体 JSON 字符串
     */
    private String buildErrorJson(String type, String message) {
        JSONObject error = new JSONObject();
        error.put("message", StrUtil.isBlank(message) ? "未知错误" : message);
        error.put("type", type);
        error.put("code", null);
        JSONObject body = new JSONObject();
        body.put("error", error);
        return body.toJSONString();
    }
}
