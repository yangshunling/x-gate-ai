package com.xgateai.exception;

/**
 * BadRequestException 请求参数异常
 * <p>
 * 用于请求参数校验失败或格式错误的场景。
 * </p>
 *
 * @author xgateai
 * @since 2026/9/8
 */
public class BadRequestException extends GatewayException {

    public BadRequestException(String message) {
        super(message, 400, "invalid_request_error");
    }

    public BadRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
