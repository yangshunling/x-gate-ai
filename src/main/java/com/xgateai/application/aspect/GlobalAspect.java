package com.xgateai.application.aspect;

import cn.hutool.core.util.StrUtil;
import com.xgateai.application.constant.CommonConstant;
import com.xgateai.application.exceptions.CommonException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Arrays;

/**
 * <p>
 * GlobalAspect 全局请求日志切面
 * </p>
 *
 * @author xgateai
 * @since 2026/9/4
 */
@Aspect
@Slf4j
@Component
public class GlobalAspect {

    /**
     * 定义切入点
     */
    @Pointcut("execution(* com.xgateai.application.controller.*.*(..))")
    public void log() {
    }

    /**
     * 在切点方法执行前打印请求日志
     *
     * @param joinPoint 切点，包含目标方法签名和参数
     */
    @Before("log()")
    public void doBefore(JoinPoint joinPoint) {
        // 开始打印请求日志
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            throw new CommonException(StrUtil.format("【{}】Attributes cannot be null", CommonConstant.BASE_COMMON_ASPECT));
        }
        HttpServletRequest request = attributes.getRequest();
        // 打印请求相关参数
        log.info("");
        log.info("=========================== Start ===========================");
        // 打印请求 url
        log.info("URL            : {}", request.getRequestURL().toString());
        // 打印 Http method
        log.info("HTTP Method    : {}", request.getMethod());
        // 打印调用 controller 的全路径以及执行方法
        log.info("Class Method   : {}", joinPoint.getSignature().getName());
        // 打印请求的 IP
        log.info("IP             : {}", request.getRemoteAddr());
        // 打印请求入参
        log.info("Request Args   : {}", Arrays.toString(joinPoint.getArgs()));
    }

    /**
     * 在切点方法执行后打印结束标记
     */
    @After("log()")
    public void doAfter() {
        log.info("============================ End ===========================");
    }
}
