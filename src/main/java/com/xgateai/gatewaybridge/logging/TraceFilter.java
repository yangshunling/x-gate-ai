package com.xgateai.gatewaybridge.logging;

import cn.hutool.core.util.StrUtil;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * <p>
 * TraceFilter 请求链路过滤器
 * 为每个请求生成 traceId 并连同客户端 IP 写入 MDC，使业务日志自动携带请求上下文；
 * 请求结束后清理 MDC，防止线程复用时上一个请求的上下文污染下一个请求。
 * </p>
 *
 * @author xgateai
 * @since 2026/9/8
 */
@Slf4j
@Component
public class TraceFilter implements Filter {

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) servletRequest;
        GatewayLog.putTraceId(GatewayLog.newTraceId());
        GatewayLog.putClientIp(resolveClientIp(request));
        try {
            chain.doFilter(servletRequest, servletResponse);
        } finally {
            GatewayLog.clearMdc();
        }
    }

    /**
     * 客户端 IP：优先取 X-Forwarded-For 首段（网关前存在反代时），否则取 RemoteAddr
     */
    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StrUtil.isNotBlank(forwarded)) {
            int idx = forwarded.indexOf(',');
            return (idx > 0 ? forwarded.substring(0, idx) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }
}
