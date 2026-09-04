package com.xgateai.gatewaybridge.service;

import com.alibaba.fastjson2.JSONObject;
import com.xgateai.adminbridge.component.EncryptUtil;
import com.xgateai.application.entity.UpstreamProvider;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClient.ResponseSpec;

/**
 * <p>
 * OpenAiClientFactory OpenAI 兼容上游请求构建工厂
 * 负责把目标上游 Provider 的 baseUrl / 解密后的 apiKey / 模型请求体组装成 WebClient 请求，
 * 统一处理 Bearer 认证、Content-Type 与 SSE 流式 Accept 头，供网关透传与连通性测试复用
 * </p>
 *
 * @author xgateai
 * @since 2026/9/4
 */
@Slf4j
@Component
public class OpenAiClientFactory {

    /**
     * 加密工具（用于解密上游 apiKey）
     */
    @Resource
    private EncryptUtil encryptUtil;

    /**
     * 向指定上游发起 JSON POST 请求，返回可继续 retrieve / bodyToMono / bodyToFlux 的 ResponseSpec
     * 注意：请求体由调用方负责深拷贝并替换 model 字段（provider 的真实模型名）
     *
     * @param webClient WebClient 实例（透传共用）
     * @param provider  目标上游 Provider
     * @param path      上游路径（如 /chat/completions、/embeddings），baseUrl 含 /v1 前缀
     * @param body      已替换 model 的请求体（JSONObject，以 fastjson2 字符串形式作为请求体发送）
     * @param stream    是否 SSE 流式（true 时追加 text/event-stream Accept）
     * @return ResponseSpec，由调用方决定同步等待还是订阅流式响应
     */
    public ResponseSpec postJson(WebClient webClient, UpstreamProvider provider, String path, JSONObject body, boolean stream) {
        // 解密上游 apiKey 并组装通用请求头
        String plainKey = encryptUtil.decrypt(provider.getApiKey());
        return webClient.post()
                .uri(provider.getBaseUrl() + path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + plainKey)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                // 流式请求声明接收 SSE，非流式接收 JSON
                .header(HttpHeaders.ACCEPT, stream ? MediaType.TEXT_EVENT_STREAM_VALUE : MediaType.APPLICATION_JSON_VALUE)
                .bodyValue(body.toJSONString())
                .retrieve();
    }
}
