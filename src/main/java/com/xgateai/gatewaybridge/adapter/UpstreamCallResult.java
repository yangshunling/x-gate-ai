package com.xgateai.gatewaybridge.adapter;

import com.xgateai.application.entity.UpstreamProvider;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * <p>
 * UpstreamCallResult 上游调用结果封装：携带上游原始响应体、实际命中的上游与耗时
 * </p>
 *
 * @author xgateai
 * @since 2026/9/4
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpstreamCallResult {

    /**
     * 上游原始响应体字符串（非流式透传直接以此为响应体）
     */
    private String rawBody;

    /**
     * 实际命中的上游 Provider
     */
    private UpstreamProvider provider;

    /**
     * 本次调用耗时（毫秒）
     */
    private long latencyMs;
}
