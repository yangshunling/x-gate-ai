package com.xgateai.gatewaybridge.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * <p>
 * GatewayConfig 网关配置
 * </p>
 *
 * @author xgateai
 * @since 2026/9/4
 */
@Data
@Component
@ConfigurationProperties(prefix = "gateway")
public class GatewayConfig {

    /**
     * 上游调用超时时间（分钟）
     */
    private long timeOutOfMinutes = 3;

    /**
     * 失败上游冷却时间（秒）
     */
    private long coolDownSeconds = 30;

    /**
     * 调用日志保留天数
     */
    private int logRetentionDays = 30;

    /**
     * 上游 api_key 加密密钥
     */
    private String encryptKey = "xgate-ai-encrypt-2026";
}
