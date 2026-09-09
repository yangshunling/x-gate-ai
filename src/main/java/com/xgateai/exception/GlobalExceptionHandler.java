package com.xgateai.exception;

import com.xgateai.response.HttpResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * GlobalExceptionHandler 全局异常处理器
 * <p>
 * 统一处理网关层所有异常，返回 OpenAI 兼容格式的错误响应。
 * </p>
 *
 * @author xgateai
 * @since 2026/9/8
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理参数校验异常
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public HttpResponse handleValidationException(MethodArgumentNotValidException ex) {
        String errors = ex.getBindingResult().getAllErrors().stream()
                .map(error -> error.getDefaultMessage())
                .collect(Collectors.joining(", "));
        log.warn("参数校验失败: {}", errors);
        return HttpResponse.error(errors);
    }

    /**
     * 处理业务参数/状态校验异常（400）
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<HttpResponse> handleIllegalArgumentException(IllegalArgumentException ex) {
        log.warn("参数校验失败: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(HttpResponse.error(ex.getMessage()));
    }

    /**
     * 处理认证异常（401）
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<HttpResponse> handleAuthenticationException(AuthenticationException ex) {
        log.warn("认证失败: {}", ex.getMessage());
        return ResponseEntity
                .status(ex.getStatusCode())
                .body(HttpResponse.error(ex.getMessage()));
    }

    /**
     * 处理业务异常
     */
    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<HttpResponse> handleGatewayException(GatewayException ex) {
        log.warn("网关业务异常 [{}]: {}", ex.getErrorCode(), ex.getMessage());
        return ResponseEntity
                .status(ex.getStatusCode())
                .body(HttpResponse.error(ex.getMessage()));
    }

    /**
     * 处理静态资源不存在（如 favicon.ico）：静默返回 404，不打印错误日志
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Void> handleNoResourceFoundException(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    /**
     * 处理未知异常（500）
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<HttpResponse> handleUnknownException(Exception ex) {
        log.error("系统未知异常", ex);
        return ResponseEntity
                .status(500)
                .body(HttpResponse.error("Internal server error"));
    }
}
