package com.xgateai.constant;

/**
 * CommonConstant 通用常量定义
 * <p>
 * 集中管理跨模块复用的数字、字符串、状态、时间、HTTP 头等业务常量，
 * 避免魔法值散落在各业务类中，提升代码可读性与可维护性。
 * </p>
 *
 * @author xgateai
 * @since 2026/9/8
 */
public class CommonConstant {

    // ==================== 数字常量 ====================
    /** 数字 0 */
    public static final int ZERO = 0;
    /** 数字 1 */
    public static final int ONE = 1;
    /** 数字 2 */
    public static final int TWO = 2;

    // ==================== 字符串常量 ====================
    /** 空字符串 */
    public static final String EMPTY_STRING = "";
    /** 路径分隔符 */
    public static final String SLASH = "/";
    /** 下划线 */
    public static final String UNDERSCORE = "_";

    // ==================== 状态常量 ====================
    /** 禁用状态值 */
    public static final int DISABLED = 0;
    /** 启用状态值 */
    public static final int ENABLED = 1;

    // ==================== HTTP 状态码 ====================
    /** HTTP 200 OK */
    public static final int HTTP_OK = 200;
    /** HTTP 400 Bad Request */
    public static final int HTTP_BAD_REQUEST = 400;
    /** HTTP 401 Unauthorized */
    public static final int HTTP_UNAUTHORIZED = 401;
    /** HTTP 500 Internal Server Error */
    public static final int HTTP_INTERNAL_SERVER_ERROR = 500;

    // ==================== 文件容量单位（字节） ====================
    /** 1 KB = 1024 字节 */
    public static final long ONE_KB = 1024L;
    /** 1 MB = 1024 KB */
    public static final long ONE_MB = 1024L * ONE_KB;

    // ==================== 时间单位（毫秒） ====================
    /** 1 秒 = 1000 毫秒 */
    public static final long ONE_SECOND = 1000L;
    /** 1 分钟 = 60 秒 */
    public static final long ONE_MINUTE = 60L * ONE_SECOND;
    /** 1 小时 = 60 分钟 */
    public static final long ONE_HOUR = 60L * ONE_MINUTE;
    /** 1 天 = 24 小时 */
    public static final long ONE_DAY = 24L * ONE_HOUR;

    // ==================== 时间格式 ====================
    /** 日期格式 yyyy-MM-dd */
    public static final String DATE_FORMAT = "yyyy-MM-dd";
    /** 时间格式 HH:mm:ss */
    public static final String TIME_FORMAT = "HH:mm:ss";
    /** 日期时间格式 yyyy-MM-dd HH:mm:ss */
    public static final String DATETIME_FORMAT = "yyyy-MM-dd HH:mm:ss";

    // ==================== HTTP 请求头 ====================
    /** Authorization 请求头名称 */
    public static final String HEADER_AUTHORIZATION = "Authorization";
    /** x-api-key 自定义请求头名称 */
    public static final String HEADER_X_API_KEY = "x-api-key";
}
