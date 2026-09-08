package com.xgateai.exception;

/**
 * UpstreamException 上游服务异常
 * <p>
 * 用于上游服务调用失败的场景，包含上游响应信息。
 * </p>
 *
 * @author xgateai
 * @since 2026/9/8
 */
public class UpstreamException extends GatewayException {

    private final int upstreamHttpStatus;

    public UpstreamException(String message, int upstreamHttpStatus) {
        super(message, 502, "upstream_error");
        this.upstreamHttpStatus = upstreamHttpStatus;
    }

    public UpstreamException(String message, int upstreamHttpStatus, Throwable cause) {
        super(message, cause);
        this.upstreamHttpStatus = upstreamHttpStatus;
    }

    public int getUpstreamHttpStatus() {
        return upstreamHttpStatus;
    }
}
