package com.xgateai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * CallLog 调用日志实体（表 call_log）
 * <p>
 * 记录每一次通过网关转发的模型调用请求，包括客户端标识、上游路由信息、
 * Token 用量、延迟及 HTTP 状态码等关键指标，用于日志查询与仪表盘统计。
 * </p>
 *
 * @author xgateai
 * @since 2026/9/4
 */
@Data
@TableName("call_log")
public class CallLog {

    /**
     * 主键，自增 ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 客户端 API Key（来自对应 ModelChannel）
     */
    @JsonProperty("api_key")
    private String apiKey;

    /**
     * 对外展示的模型名（客户侧可见名称）
     */
    @JsonProperty("public_model")
    private String publicModel;

    /**
     * 上游实际调用的完整 URL
     */
    @JsonProperty("upstream_url")
    private String upstreamUrl;

    /**
     * 上游真实模型名
     */
    @JsonProperty("upstream_model")
    private String upstreamModel;

    /**
     * 提示词 Token 消耗量，非负整数；未记录时为 null
     */
    @JsonProperty("input_tokens")
    private Integer inputTokens;

    /**
     * 生成输出 Token 消耗量，非负整数；未记录时为 null
     */
    @JsonProperty("output_tokens")
    private Integer outputTokens;

    /**
     * 单次请求端到端耗时（毫秒）
     */
    @JsonProperty("latency_ms")
    private Long latencyMs;

    /**
     * 上游返回的 HTTP 状态码
     */
    @JsonProperty("http_status")
    private Integer httpStatus;

    /**
     * 请求体摘要 JSON（仅 chat 类型包含 roles/preview 摘要，不含完整消息）
     */
    @JsonProperty("request_body")
    private String requestBody;

    /**
     * 请求时间，格式 yyyy-MM-dd HH:mm:ss
     */
    @JsonProperty("created_at")
    private String createdAt;

    /**
     * 客户/通道名称（同 publicModel，冗余便于排序筛选）
     */
    @JsonProperty("customer_name")
    private String customerName;

    /**
     * 请求体中 assistant 消息的 tool_calls 总数，用于计费统计
     */
    @JsonProperty("tool_calls_count")
    private Integer toolCallsCount;
}
