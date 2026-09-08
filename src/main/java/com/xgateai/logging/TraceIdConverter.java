package com.xgateai.logging;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * TraceIdConverter Logback 自定义转换器
 * <p>
 * 当 MDC 中存在 traceId 时输出 "[tid=xxx] " 前缀。
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
