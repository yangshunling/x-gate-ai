package com.xgateai.application.controller;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.xgateai.application.entity.ModelChannel;
import com.xgateai.application.exceptions.CommonException;
import com.xgateai.gatewaybridge.adapter.ProxyAdapter;
import com.xgateai.gatewaybridge.constant.GatewayConstant;
import jakarta.annotation.Resource;
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
 * <p>
 * GatewayController OpenAI 兼容对外网关控制器（/v1）
 * 请求/响应均为原样透传：仅 stream=true 时以 SSE 逐段回传上游数据块
 * </p>
 *
 * @author xgateai
 * @since 2026/9/7
 */
@Slf4j
@RestController
@CrossOrigin
@RequestMapping("/v1")
public class GatewayController {

    @Resource
    private ProxyAdapter proxyAdapter;

    /**
     * Chat Completions：stream=true 原样透传上游 SSE，否则原样返回上游 JSON
     */
    @PostMapping("/chat/completions")
    public void chatCompletions(@RequestBody String rawBody,
                                HttpServletRequest request,
                                HttpServletResponse response) throws IOException {
        ServletOutputStream out = response.getOutputStream();
        try {
            JSONObject body = JSON.parseObject(rawBody);
            if (body == null) throw new CommonException("请求体为空或不是合法 JSON");
            ModelChannel channel = resolveChannel(request);
            if (channel == null) throw new CommonException("无法识别调用方对客服务");

            if (body.getBooleanValue("stream")) {
                initSse(response);
                try {
                    proxyAdapter.chatStream(channel, rawBody, bytes -> writeBytes(out, bytes));
                } catch (Exception ex) {
                    // 所有上游均在开始输出前失败，回写 SSE 错误事件（headers 已提交，不能改写状态码）
                    log.error("流式调用上游全部失败, model: {}", channel.getPublicModelName(), ex);
                    writeSseError(out, ex.getMessage());
                }
            } else {
                String upstreamJson = proxyAdapter.chat(channel, rawBody);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                out.write(upstreamJson.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (CommonException ex) {
            log.warn("请求校验失败, error: {}", ex.getMessage());
            writeError(response, out, 400, "invalid_request_error", ex.getMessage());
        } catch (Exception ex) {
            log.error("处理 /v1/chat/completions 请求异常", ex);
            writeError(response, out, 500, "server_error", ex.getMessage());
        }
    }

    /**
     * Embeddings：非流式原样透传
     */
    @PostMapping("/embeddings")
    public ResponseEntity<String> embeddings(@RequestBody String rawBody, HttpServletRequest request) {
        try {
            JSONObject body = JSON.parseObject(rawBody);
            if (body == null) throw new CommonException("请求体为空或不是合法 JSON");
            ModelChannel channel = resolveChannel(request);
            if (channel == null) throw new CommonException("无法识别调用方对客服务");
            String upstreamJson = proxyAdapter.embeddings(channel, rawBody);
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(upstreamJson);
        } catch (CommonException ex) {
            return buildErrorResponse(400, "invalid_request_error", ex.getMessage());
        } catch (Exception ex) {
            log.error("处理 /v1/embeddings 请求异常", ex);
            return buildErrorResponse(500, "server_error", ex.getMessage());
        }
    }

    /**
     * Models：返回当前 Key 可用的模型列表（限定模型的 Key 仅返回该模型，default 返回全池模型）
     */
    @GetMapping("/models")
    public ResponseEntity<String> listModels(HttpServletRequest request) {
        ModelChannel channel = resolveChannel(request);
        JSONObject result = new JSONObject();
        result.put("object", "list");
        if (channel != null) {
            result.put("data", proxyAdapter.listAvailableModels(channel));
        } else {
            result.put("data", new JSONArray());
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(result.toJSONString());
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 初始化 SSE 响应头并提交，确保客户端先收到头再等待首块
     */
    private void initSse(HttpServletResponse response) throws IOException {
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");
        response.flushBuffer();
    }

    /**
     * 以 SSE data 帧形式回写错误，供流式场景下所有上游在输出前失败时使用
     */
    private void writeSseError(ServletOutputStream out, String message) {
        try {
            out.write(("data: " + errorBody("server_error", message) + "\n\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            log.error("写出 SSE 错误事件失败", e);
        }
    }

    private void writeBytes(ServletOutputStream out, byte[] bytes) {
        try {
            out.write(bytes);
            out.flush();
        } catch (IOException e) {
            log.error("SSE 写出失败", e);
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
            out.write(errorBody(type, message).getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            log.error("写出错误响应失败", e);
        }
    }

    private ResponseEntity<String> buildErrorResponse(int status, String type, String message) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(errorBody(type, message));
    }

    /**
     * 构建 OpenAI 风格错误 JSON：{"error":{message,type,code}}
     */
    private String errorBody(String type, String message) {
        JSONObject error = new JSONObject();
        error.put("message", StrUtil.isBlank(message) ? "未知错误" : message);
        error.put("type", type);
        error.put("code", null);
        JSONObject body = new JSONObject();
        body.put("error", error);
        return body.toJSONString();
    }

    private ModelChannel resolveChannel(HttpServletRequest request) {
        Object attr = request.getAttribute(GatewayConstant.ATTR_API_KEY);
        return attr instanceof ModelChannel ch ? ch : null;
    }
}
