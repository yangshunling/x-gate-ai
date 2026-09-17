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

    /**
     * 主网关调用 OkHttp 客户端
     * <p>连接超时 10 秒，读超时取配置的超时分钟数（默认 3 分钟），写超时 1 分钟。</p>
     *
     * @return 主网关 OkHttpClient 实例
     */
    @Bean
    public OkHttpClient mainOkHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(gatewayConfig.getTimeOutOfMinutes(), TimeUnit.MINUTES)
                .writeTimeout(1, TimeUnit.MINUTES)
                .build();
    }

    /**
     * 连通性测试专用 OkHttp 客户端
     * <p>超时较短（连接 5 秒、读写 15 秒），避免测试接口长时间阻塞。</p>
     *
     * @return 测试专用 OkHttpClient 实例
     */
    @Bean
    public OkHttpClient testOkHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build();
    }
}
