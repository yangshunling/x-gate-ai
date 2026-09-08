package com.xgateai.gatewaybridge.config;

import jakarta.annotation.Resource;
import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * <p>
 * OkHttpConfig 出站 HTTP 客户端配置
 * 网关所有上游请求（普通 JSON 与 SSE 流式）统一复用该连接池
 * </p>
 *
 * @author xgateai
 * @since 2026/9/7
 */
@Configuration
public class OkHttpConfig {

    @Resource
    private GatewayConfig gatewayConfig;

    @Bean
    public OkHttpClient okHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                // 读取超时即上游两次返回数据间的最大间隔，SSE 场景按网关配置放宽
                .readTimeout(gatewayConfig.getTimeOutOfMinutes(), TimeUnit.MINUTES)
                .writeTimeout(1, TimeUnit.MINUTES)
                .build();
    }
}
