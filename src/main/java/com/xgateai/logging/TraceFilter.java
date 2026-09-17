package com.xgateai.logging;

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
 * TraceFilter 请求链路过滤器
 * <p>
 * 为每个请求生成 traceId 并写入 MDC，请求结束后清理上下文。
 * </p>
 *
 * @author xgateai
 * @since 2026/9/8
 */
@Slf4j
@Component
public class TraceFilter implements Filter {

    /**
     * 请求链路过滤器：为每个请求生成并写入 traceId / clientIp 到 MDC，
     * 请求结束后清理上下文防止线程复用串扰
     *
     * @param servletRequest  请求
     * @param servletResponse 响应
     * @param chain           过滤器链
     * @throws IOException      过滤链调用时可能抛出
     * @throws ServletException 过滤链调用时可能抛出
     */
    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse,
                         FilterChain chain) throws IOException, ServletException {
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
     * 解析客户端 IP，优先取 X-Forwarded-For 首段
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
