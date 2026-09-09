package com.xgateai.exception;

import lombok.Getter;

/**
 * UpstreamException 上游服务异常
 * <p>
 * 用于上游服务调用失败的场景，包含上游返回的 HTTP 状态码供排查使用。
 * </p>
 *
 * @author xgateai
 * @since 2026/9/8
 */
@Getter
public class UpstreamException extends GatewayException {

    /**
     * 上游返回的原始 HTTP 状态码
     */
    private final int upstreamHttpStatus;

    /**
     * 构造上游异常（含上游状态码）
     *
     * @param message            错误提示信息
     * @param upstreamHttpStatus 上游返回的 HTTP 状态码
     */
    public UpstreamException(String message, int upstreamHttpStatus) {
        super(message, 502, "upstream_error");
        this.upstreamHttpStatus = upstreamHttpStatus;
    }

    /**
     * 构造上游异常（含原始 cause，上游状态码取 502）
     *
     * @param message 错误提示信息
     * @param cause   原始异常根因
     */
    public UpstreamException(String message, int upstreamHttpStatus, Throwable cause) {
        super(message, cause);
        this.upstreamHttpStatus = upstreamHttpStatus;
    }
}
