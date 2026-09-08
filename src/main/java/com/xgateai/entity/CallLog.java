package com.xgateai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * CallLog 调用日志实体
 */
@Data
@TableName("call_logs")
public class CallLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    @JsonProperty("api_key")
    private String apiKey;

    @JsonProperty("public_model")
    private String publicModel;

    @JsonProperty("upstream_url")
    private String upstreamUrl;

    @JsonProperty("upstream_model")
    private String upstreamModel;

    @JsonProperty("input_tokens")
    private Integer inputTokens;

    @JsonProperty("output_tokens")
    private Integer outputTokens;

    @JsonProperty("latency_ms")
    private Long latencyMs;

    @JsonProperty("http_status")
    private Integer httpStatus;

    @JsonProperty("request_body")
    private String requestBody;

    @JsonProperty("created_at")
    private String createdAt;

    @JsonProperty("customer_name")
    private String customerName;

    @JsonProperty("tool_calls_count")
    private Integer toolCallsCount;
}
