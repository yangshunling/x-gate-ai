package com.xgateai.exception;

import lombok.Getter;

/**
 * AuthenticationException 认证失败异常
 * <p>
 * 用于 API Key 无效、缺失或过期的场景，统一返回 HTTP 401。
 * </p>
 *
 * @author xgateai
 * @since 2026/9/8
 */
@Getter
public class AuthenticationException extends GatewayException {

    /**
     * 构造认证异常
     *
     * @param message 错误提示信息
     */
    public AuthenticationException(String message) {
        super(message, 401, "invalid_api_key");
    }

    /**
     * 构造带原始 cause 的认证异常
     *
     * @param message 错误提示信息
     * @param cause   原始异常根因
     */
    public AuthenticationException(String message, Throwable cause) {
        super(message, cause);
    }
}
