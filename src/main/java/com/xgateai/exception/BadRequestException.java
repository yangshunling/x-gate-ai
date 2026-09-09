package com.xgateai.exception;

/**
 * BadRequestException 请求参数异常
 * <p>
 * 用于请求参数校验失败或格式错误的场景，返回 HTTP 400。
 * </p>
 *
 * @author xgateai
 * @since 2026/9/8
 */
public class BadRequestException extends GatewayException {

    /**
     * 构造参数异常
     *
     * @param message 错误提示信息
     */
    public BadRequestException(String message) {
        super(message, 400, "invalid_request_error");
    }

    /**
     * 构造带原始 cause 的参数异常
     *
     * @param message 错误提示信息
     * @param cause   原始异常根因
     */
    public BadRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
