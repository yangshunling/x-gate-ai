package com.xgateai.exception;

import lombok.Getter;

/**
 * GatewayException 网关业务异常基类
 * <p>
 * 继承自 RuntimeException，用于标识网关层的业务逻辑异常。
 * 所有自定义网关异常应从此类继承，便于 {@link GlobalExceptionHandler} 统一处理和日志记录。
 * </p>
 *
 * @author xgateai
 * @since 2026/9/8
 */
@Getter
public class GatewayException extends RuntimeException {

    /**
     * HTTP 状态码或 OpenAI 错误码，对应响应体的 statusCode 字段
     */
    private final int statusCode;

    /**
     * 错误类型标识，用于客户端识别错误类别（如 invalid_api_key、resource_not_found）
     */
    private final String errorCode;

    /**
     * 构造业务异常（默认 400）
     *
     * @param message 异常提示信息
     */
    public GatewayException(String message) {
        super(message);
        this.statusCode = 400;
        this.errorCode = "gateway_error";
    }

    /**
     * 构造业务异常（指定状态码，默认错误码）
     *
     * @param message   异常提示信息
     * @param statusCode HTTP 状态码
     */
    public GatewayException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
        this.errorCode = "gateway_error";
    }

    /**
     * 构造业务异常（指定错误码，默认状态码 400）
     *
     * @param message  异常提示信息
     * @param errorCode 错误类型标识
     */
    public GatewayException(String message, String errorCode) {
        super(message);
        this.statusCode = 400;
        this.errorCode = errorCode;
    }

    /**
     * 构造带原始 cause 的业务异常（默认 500）
     *
     * @param message 异常提示信息
     * @param cause   原始异常根因
     */
    public GatewayException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 500;
        this.errorCode = "server_error";
    }

    /**
     * 构造带原始 cause 的业务异常（完整参数）
     *
     * @param message    异常提示信息
     * @param statusCode HTTP 状态码
     * @param errorCode  错误类型标识
     * @param cause      原始异常根因
     */
    public GatewayException(String message, int statusCode, String errorCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.errorCode = errorCode;
    }

    /**
     * 构造业务异常（指定状态码和错误码，不含 cause）
     *
     * @param message    异常提示信息
     * @param statusCode HTTP 状态码
     * @param errorCode  错误类型标识
     */
    public GatewayException(String message, int statusCode, String errorCode) {
        super(message);
        this.statusCode = statusCode;
        this.errorCode = errorCode;
    }
}
