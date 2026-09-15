package com.xgateai.logging;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import org.slf4j.MDC;

/**
 * GatewayLog 网关日志 MDC 工具类
 * <p>
 * 提供请求级 MDC 上下文的写入和读取，包括 traceId、客户端 IP、通道身份等。
 * </p>
 *
 * @author xgateai
 * @since 2026/9/8
 */
public final class GatewayLog {

    private GatewayLog() {
    }

    // ==================== MDC 键 ====================
    public static final String MDC_TRACE_ID = "traceId";
    public static final String MDC_CLIENT_IP = "clientIp";
    public static final String MDC_CHANNEL_ID = "channelId";
    public static final String MDC_CHANNEL_NAME = "channelName";
    public static final String MDC_CHANNEL_KEY = "channelKey";

    // ==================== MDC 操作 ====================

    /**
     * 生成请求追踪号（32 位十六进制）
     */
    public static String newTraceId() {
        return IdUtil.fastSimpleUUID();
    }

    public static void putTraceId(String traceId) {
        MDC.put(MDC_TRACE_ID, traceId);
    }

    public static void putClientIp(String ip) {
        MDC.put(MDC_CLIENT_IP, StrUtil.blankToDefault(ip, "-"));
    }

    public static void putChannelId(String value) {
        MDC.put(MDC_CHANNEL_ID, StrUtil.blankToDefault(value, "-"));
    }

    public static void putChannelName(String value) {
        MDC.put(MDC_CHANNEL_NAME, StrUtil.blankToDefault(value, "-"));
    }

    public static void putChannelKey(String value) {
        MDC.put(MDC_CHANNEL_KEY, StrUtil.blankToDefault(value, "-"));
    }

    public static String getMdc(String key) {
        return MDC.get(key);
    }

    // ==================== 脱敏 ====================

    /**
     * API Key 脱敏：仅保留首 3 位与尾 4 位
     */
    public static String maskKey(String key) {
        if (StrUtil.isBlank(key)) {
            return "-";
        }
        if (key.length() <= 8) {
            return "****";
        }
        return key.substring(0, 3) + "****" + key.substring(key.length() - 4);
    }

    /**
     * 清理 MDC，防止线程复用时上下文串扰
     */
    public static void clearMdc() {
        MDC.clear();
    }
}
