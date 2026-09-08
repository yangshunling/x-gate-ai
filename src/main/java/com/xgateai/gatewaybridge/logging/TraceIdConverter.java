package com.xgateai.gatewaybridge.logging;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * <p>
 * TraceIdConverter 自定义 Logback 转换器
 * MDC 中存在 traceId 时输出 "[tid=xxx] " 前缀，否则输出空串，
 * 避免启动日志、定时任务等非请求上下文出现空的方括号。
 * </p>
 *
 * @author xgateai
 * @since 2026/9/8
 */
public class TraceIdConverter extends ClassicConverter {

    @Override
    public String convert(ILoggingEvent event) {
        String traceId = event.getMDCPropertyMap().get(GatewayLog.MDC_TRACE_ID);
        return (traceId == null || traceId.isEmpty()) ? "" : "[tid=" + traceId + "] ";
    }
}
