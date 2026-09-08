package com.xgateai.exception;

/**
 * AuthenticationException 认证失败异常
 * <p>
 * 用于 API Key 无效或未提供的场景，返回 401 状态码。
 * </p>
 *
 * @author xgateai
 * @since 2026/9/8
 */
public class AuthenticationException extends GatewayException {

    public AuthenticationException(String message) {
        super(message, 401, "invalid_api_key");
    }

    public AuthenticationException(String message, Throwable cause) {
        super(message, cause);
    }
}
