package com.xgateai.constant;

/**
 * CommonConstant 通用常量定义
 *
 * @author xgateai
 * @since 2026/9/8
 */
public class CommonConstant {

    // ==================== 数字常量 ====================
    public static final int ZERO = 0;
    public static final int ONE = 1;
    public static final int TWO = 2;

    // ==================== 字符串常量 ====================
    public static final String EMPTY_STRING = "";
    public static final String SLASH = "/";
    public static final String UNDERSCORE = "_";

    // ==================== 状态常量 ====================
    /** 禁用 */
    public static final int DISABLED = 0;
    /** 启用 */
    public static final int ENABLED = 1;

    // ==================== HTTP 状态码 ====================
    public static final int HTTP_OK = 200;
    public static final int HTTP_BAD_REQUEST = 400;
    public static final int HTTP_UNAUTHORIZED = 401;
    public static final int HTTP_INTERNAL_SERVER_ERROR = 500;

    // ==================== 文件常量 ====================
    public static final long ONE_KB = 1024L;
    public static final long ONE_MB = 1024L * ONE_KB;

    // ==================== 时间常量（毫秒） ====================
    public static final long ONE_SECOND = 1000L;
    public static final long ONE_MINUTE = 60L * ONE_SECOND;
    public static final long ONE_HOUR = 60L * ONE_MINUTE;
    public static final long ONE_DAY = 24L * ONE_HOUR;

    // ==================== 时间格式 ====================
    public static final String DATE_FORMAT = "yyyy-MM-dd";
    public static final String TIME_FORMAT = "HH:mm:ss";
    public static final String DATETIME_FORMAT = "yyyy-MM-dd HH:mm:ss";

    // ==================== HTTP 头 ====================
    public static final String HEADER_AUTHORIZATION = "Authorization";
    public static final String HEADER_X_API_KEY = "x-api-key";
}
