package com.xgateai.application.constant;

/**
 * <p>
 * CommonConstant 常量类
 * </p>
 *
 * @author xgateai
 * @since 2026/9/4
 */
public class CommonConstant {

    /******************************************************************************/

    // 日志前缀常量
    public static final String BASE_COMMON_ASPECT = "XGateAspect";

    // 数字常量
    public static final int ZERO = 0;
    public static final int ONE = 1;
    public static final int TWO = 2;
    public static final int THREE = 3;
    public static final int FOUR = 4;
    public static final int FIVE = 5;
    public static final int SIX = 6;
    public static final int SEVEN = 7;
    public static final int EIGHT = 8;
    public static final int NINE = 9;
    public static final int TEN = 10;
    public static final int HUNDRED = 100;
    public static final int THOUSAND = 1000;

    // 常用字符串常量
    public static final String EMPTY_STRING = "";
    public static final String SPACE = " ";
    public static final String COMMA = ",";
    public static final String DOT = ".";
    public static final String COLON = ":";
    public static final String SEMICOLON = ";";
    public static final String SLASH = "/";
    public static final String UNDERSCORE = "_";
    public static final String DASH = "-";

    // 常用布尔常量
    public static final int DISABLED = 0;
    public static final int ENABLED = 1;

    // HTTP状态码
    public static final int HTTP_OK = 200;
    public static final int HTTP_CREATED = 201;
    public static final int HTTP_NO_CONTENT = 204;
    public static final int HTTP_BAD_REQUEST = 400;
    public static final int HTTP_UNAUTHORIZED = 401;
    public static final int HTTP_FORBIDDEN = 403;
    public static final int HTTP_NOT_FOUND = 404;
    public static final int HTTP_INTERNAL_SERVER_ERROR = 500;
    public static final int HTTP_BAD_GATEWAY = 502;
    public static final int HTTP_SERVICE_UNAVAILABLE = 503;

    // 文件大小常量
    public static final long ONE_KB = 1024L;
    public static final long ONE_MB = 1024L * ONE_KB;

    // 时间常量，单位为毫秒
    public static final long ONE_SECOND = 1000L;
    public static final long ONE_MINUTE = 60L * ONE_SECOND;
    public static final long ONE_HOUR = 60L * ONE_MINUTE;
    public static final long ONE_DAY = 24L * ONE_HOUR;

    // 常用的时间格式常量
    public static final String DATE_FORMAT = "yyyy-MM-dd";
    public static final String TIME_FORMAT = "HH:mm:ss";
    public static final String DATETIME_FORMAT = "yyyy-MM-dd HH:mm:ss";

    // 常用的HTTP头部常量
    public static final String HEADER_AUTHORIZATION = "Authorization";
    public static final String HEADER_X_API_KEY = "x-api-key";

    // 会话键
    public static final String SESSION_ADMIN_USER = "admin_user";

}
