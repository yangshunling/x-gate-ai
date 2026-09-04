package com.xgateai.gatewaybridge.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;

/**
 * <p>
 * WebClientConfig WebClient 配置（SSE 流式转发使用）
 * </p>
 *
 * @author xgateai
 * @since 2026/9/4
 */
@Configuration
public class WebClientConfig {

    /**
     * 构建带超时配置的 WebClient
     *
     * @return WebClient 实例
     */
    @Bean
    public WebClient webClient() {
        // 连接池：保持长连接，减少握手开销
        ConnectionProvider connectionProvider = ConnectionProvider.builder("xgate-ai")
                .maxConnections(200)
                .maxIdleTime(Duration.ofSeconds(30))
                .build();
        HttpClient httpClient = HttpClient.create(connectionProvider)
                .responseTimeout(Duration.ofSeconds(120));
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
