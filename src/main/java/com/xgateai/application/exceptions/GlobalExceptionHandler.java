package com.xgateai.application.exceptions;

import com.xgateai.application.model.response.HttpResponse;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * <p>
 * GlobalExceptionHandler 全局异常
 * </p>
 *
 * @author xgateai
 * @since 2026/9/4
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理参数校验异常，提取所有校验失败信息并返回
     *
     * @param ex 方法参数校验失败时抛出的异常
     * @return 包含错误信息的统一响应对象
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public HttpResponse handleValidationExceptions(MethodArgumentNotValidException ex) {
        // 获取所有的错误信息
        String errorMessages = ex.getBindingResult().getAllErrors().stream()
                .map(DefaultMessageSourceResolvable::getDefaultMessage)
                .collect(Collectors.joining(", "));
        // 返回合适的响应
        return HttpResponse.error(errorMessages);
    }

    /**
     * 处理自定义业务异常并返回错误信息
     *
     * @param ex 自定义业务异常
     * @return 包含异常消息的统一响应对象
     */
    @ExceptionHandler(CommonException.class)
    public HttpResponse handleCommonException(CommonException ex) {
        return HttpResponse.error(ex.getMessage());
    }

    /**
     * 处理未捕获的未知异常，兜底返回错误信息
     *
     * @param ex 未知异常
     * @return 包含异常消息的统一响应对象
     */
    @ExceptionHandler(Exception.class)
    public HttpResponse handleGenericException(Exception ex) {
        return HttpResponse.error(ex.getMessage());
    }
}
