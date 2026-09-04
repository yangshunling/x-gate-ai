package com.xgateai.application.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * <p>
 * CallLog 调用日志实体
 * </p>
 *
 * @author xgateai
 * @since 2026/9/4
 */
@Data
@TableName("call_logs")
public class CallLog {

    /**
     * 主键
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 调用方 API Key
     */
    @JsonProperty("api_key")
    private String apiKey;

    /**
     * 对外模型名
     */
    @JsonProperty("public_model")
    private String publicModel;

    /**
     * 上游 URL
     */
    @JsonProperty("upstream_url")
    private String upstreamUrl;

    /**
     * 上游真实模型名
     */
    @JsonProperty("upstream_model")
    private String upstreamModel;

    /**
     * 输入 token 数
     */
    @JsonProperty("input_tokens")
    private Integer inputTokens;

    /**
     * 输出 token 数
     */
    @JsonProperty("output_tokens")
    private Integer outputTokens;

    /**
     * 耗时（毫秒）
     */
    @JsonProperty("latency_ms")
    private Long latencyMs;

    /**
     * HTTP 状态码
     */
    @JsonProperty("http_status")
    private Integer httpStatus;

    /**
     * 创建时间
     */
    @JsonProperty("created_at")
    private String createdAt;
}
