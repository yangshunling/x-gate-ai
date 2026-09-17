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
    /** MDC 键：请求追踪号 */
    public static final String MDC_TRACE_ID = "traceId";
    /** MDC 键：客户端 IP */
    public static final String MDC_CLIENT_IP = "clientIp";
    /** MDC 键：通道 ID */
    public static final String MDC_CHANNEL_ID = "channelId";
    /** MDC 键：通道名称 */
    public static final String MDC_CHANNEL_NAME = "channelName";
    /** MDC 键：通道 API Key（脱敏） */
    public static final String MDC_CHANNEL_KEY = "channelKey";

    // ==================== MDC 操作 ====================

    /**
     * 生成请求追踪号（32 位十六进制）
     */
    public static String newTraceId() {
        return IdUtil.fastSimpleUUID();
    }

    /**
     * 写入 traceId 到 MDC
     *
     * @param traceId 请求追踪号
     */
    public static void putTraceId(String traceId) {
        MDC.put(MDC_TRACE_ID, traceId);
    }

    /**
     * 写入客户端 IP 到 MDC（空值回退为 -）
     *
     * @param ip 客户端 IP
     */
    public static void putClientIp(String ip) {
        MDC.put(MDC_CLIENT_IP, StrUtil.blankToDefault(ip, "-"));
    }

    /**
     * 写入通道 ID 到 MDC（空值回退为 -）
     *
     * @param value 通道 ID
     */
    public static void putChannelId(String value) {
        MDC.put(MDC_CHANNEL_ID, StrUtil.blankToDefault(value, "-"));
    }

    /**
     * 写入通道名称到 MDC（空值回退为 -）
     *
     * @param value 通道名称
     */
    public static void putChannelName(String value) {
        MDC.put(MDC_CHANNEL_NAME, StrUtil.blankToDefault(value, "-"));
    }

    /**
     * 写入通道 API Key（脱敏）到 MDC（空值回退为 -）
     *
     * @param value 脱敏后的 API Key
     */
    public static void putChannelKey(String value) {
        MDC.put(MDC_CHANNEL_KEY, StrUtil.blankToDefault(value, "-"));
    }

    /**
     * 读取 MDC 指定键的值
     *
     * @param key MDC 键
     * @return 键对应的值；不存在时返回 null
     */
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
