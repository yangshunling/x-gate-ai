package com.xgateai.gatewaybridge.logging;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import org.slf4j.MDC;

/**
 * <p>
 * GatewayLog 网关日志工具
 * 负责请求级 MDC 上下文（traceId / 客户端IP / 通道身份）与 API Key 脱敏，
 * 为单行化的网关日志提供统一取值入口。
 * </p>
 *
 * @author xgateai
 * @since 2026/9/8
 */
public final class GatewayLog {

    private GatewayLog() {
    }

    // ==================== MDC 键 ====================

    /** 请求追踪号 */
    public static final String MDC_TRACE_ID = "traceId";
    /** 客户端 IP */
    public static final String MDC_CLIENT_IP = "clientIp";
    /** 对客通道 ID */
    public static final String MDC_CHANNEL_ID = "channelId";
    /** 对客通道备注名 */
    public static final String MDC_CHANNEL_NAME = "channelName";
    /** 对客通道对外模型名 */
    public static final String MDC_CHANNEL_MODEL = "channelModel";
    /** 对客通道调用 Key（脱敏） */
    public static final String MDC_CHANNEL_KEY = "channelKey";

    // ==================== MDC 操作 ====================

    /**
     * 生成请求追踪号（32 位十六进制，简洁且碰撞概率低）
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

    public static void putChannelModel(String value) {
        MDC.put(MDC_CHANNEL_MODEL, StrUtil.blankToDefault(value, "-"));
    }

    public static void putChannelKey(String value) {
        MDC.put(MDC_CHANNEL_KEY, StrUtil.blankToDefault(value, "-"));
    }

    public static String getMdc(String key) {
        return MDC.get(key);
    }

    /**
     * 清理 MDC，避免线程复用时上下文串到下一个请求
     */
    public static void clearMdc() {
        MDC.clear();
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
}
