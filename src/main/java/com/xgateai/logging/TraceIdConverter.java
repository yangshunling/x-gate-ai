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

    /**
     * 将日志事件转换为带 traceId 前缀的字符串
     *
     * @param event 日志事件
     * @return 存在 traceId 时返回 "[tid=xxx] " 前缀，否则返回空串
     */
    @Override
    public String convert(ILoggingEvent event) {
        String traceId = event.getMDCPropertyMap().get(GatewayLog.MDC_TRACE_ID);
        return (traceId == null || traceId.isEmpty()) ? "" : "[tid=" + traceId + "] ";
    }
}
