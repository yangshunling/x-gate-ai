package com.xgateai.exception;

/**
 * GatewayException 网关业务异常基类
 * <p>
 * 继承自 RuntimeException，用于标识网关层的业务逻辑异常。
 * 所有自定义网关异常应从此类继承，便于统一处理和日志记录。
 * </p>
 *
 * @author xgateai
 * @since 2026/9/8
 */
public class GatewayException extends RuntimeException {

    /**
     * 状态码，对应 HTTP 状态码或 OpenAI 错误码
     */
    private final int statusCode;

    /**
     * 错误类型，用于客户端识别错误类别
     */
    private final String errorCode;

    public GatewayException(String message) {
        super(message);
        this.statusCode = 400;
        this.errorCode = "gateway_error";
    }

    public GatewayException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
        this.errorCode = "gateway_error";
    }

    public GatewayException(String message, String errorCode) {
        super(message);
        this.statusCode = 400;
        this.errorCode = errorCode;
    }

    public GatewayException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 500;
        this.errorCode = "server_error";
    }

    public GatewayException(String message, int statusCode, String errorCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.errorCode = errorCode;
    }

    public GatewayException(String message, int statusCode, String errorCode) {
        super(message);
        this.statusCode = statusCode;
        this.errorCode = errorCode;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
