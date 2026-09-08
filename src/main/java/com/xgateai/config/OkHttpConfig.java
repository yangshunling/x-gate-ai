package com.xgateai.config;

import jakarta.annotation.Resource;
import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * OkHttpConfig OkHttp 客户端配置
 * <p>
 * 定义两个 OkHttp 客户端实例：
 * 1. mainClient: 主网关请求，复用连接池，超时时间较长
 * 2. testClient: 连通性测试专用，超时时间较短
 * </p>
 *
 * @author xgateai
 * @since 2026/9/8
 */
@Configuration
public class OkHttpConfig {

    @Resource
    private GatewayConfig gatewayConfig;

    @Bean
    public OkHttpClient mainOkHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(gatewayConfig.getTimeOutOfMinutes(), TimeUnit.MINUTES)
                .writeTimeout(1, TimeUnit.MINUTES)
                .build();
    }

    @Bean
    public OkHttpClient testOkHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build();
    }
}
