package com.xgateai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * GatewayConfig 网关配置属性
 * <p>
 * 绑定 application.properties 中以 gateway 为前缀的配置项。
 * </p>
 *
 * @author xgateai
 * @since 2026/9/8
 */
@Data
@Component
@ConfigurationProperties(prefix = "gateway")
public class GatewayConfig {

    /**
     * 上游调用超时时间（分钟），用于耗时较长的生成任务
     */
    private long timeOutOfMinutes = 3;

    /**
     * 调用日志保留天数，按天滚动清理
     */
    private int logRetentionDays = 30;

    /**
     * 上游 api_key 加密密钥，生产环境务必通过环境变量覆盖
     */
    private String encryptKey = "xgate-ai-encrypt-2026";

    /**
     * 对外展示的网关品牌名，用于 /v1/models 的 owned_by 字段，
     * 向客户隐藏上游真实厂商（如商汤科技、DeepSeek、OpenAI）身份。
     * 可通过 gateway.brand-name 覆盖，例如设置为自家平台名。
     */
    private String brandName = "x-gate-ai";
}
