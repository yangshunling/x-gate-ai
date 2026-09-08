package com.xgateai.controller;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.xgateai.entity.ModelChannel;
import com.xgateai.constant.GatewayConstant;
import com.xgateai.exception.BadRequestException;
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

    public GatewayController(GatewayService gatewayService) {
        this.gatewayService = gatewayService;
    }

    /**
     * Chat Completions：对话补全接口
     * <p>
     * 支持流式和非流式两种模式。
     * 流式模式原样透传上游 SSE 数据块。
     * </p>
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
                    gatewayService.chatStream(channel, rawBody, chunk -> writeBytes(out, chunk));
                } catch (Exception ex) {
                    log.error("流式调用上游全部失败, model: {}", channel.getPublicModelName(), ex);
                    writeSseError(out, ex.getMessage());
                }
            } else {
                String upstreamJson = gatewayService.chat(channel, rawBody);
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
     * Embeddings：向量化接口
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

            String upstreamJson = gatewayService.embeddings(channel, rawBody);
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
     */
    @GetMapping("/models")
    public ResponseEntity<String> listModels(HttpServletRequest request) {
        ModelChannel channel = resolveChannel(request);
        JSONObject result = new JSONObject();
        result.put("object", "list");
        result.put("data", new JSONArray());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(result.toJSONString());
    }

    // ==================== 私有辅助方法 ====================

    private ModelChannel resolveChannel(HttpServletRequest request) {
        Object attr = request.getAttribute(GatewayConstant.ATTR_API_KEY);
        return attr instanceof ModelChannel ch ? ch : null;
    }

    private void initSseResponse(HttpServletResponse response) throws IOException {
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");
        response.flushBuffer();
    }

    private void writeBytes(ServletOutputStream out, byte[] bytes) {
        try {
            out.write(bytes);
            out.flush();
        } catch (IOException e) {
            log.error("SSE 写出失败", e);
        }
    }

    private void writeSseError(ServletOutputStream out, String message) {
        try {
            out.write(("data: " + buildErrorJson("server_error", message) + "\n\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            log.error("写出 SSE 错误事件失败", e);
        }
    }

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
        } catch (IOException e) {
            log.error("写出错误响应失败", e);
        }
    }

    private ResponseEntity<String> buildErrorResponse(int status, String type, String message) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(buildErrorJson(type, message));
    }

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
