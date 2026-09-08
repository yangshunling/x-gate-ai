package com.xgateai.gatewaybridge.constant;

/**
 * <p>
 * GatewayConstant 网关常量类
 * </p>
 *
 * @author xgateai
 * @since 2026/9/4
 */
public class GatewayConstant {

    // 负载策略
    public static final String STRATEGY_ROUND_ROBIN = "ROUND_ROBIN";

    // 请求属性键（ApiKeyInterceptor 放入请求的 ApiKey 记录）
    public static final String ATTR_API_KEY = "gateway_api_key";

    // 默认分页
    public static final int DEFAULT_PAGE_NUM = 1;
    public static final int DEFAULT_PAGE_SIZE = 20;

    // 对外 API 端点路径
    public static final String PATH_CHAT_COMPLETIONS = "/chat/completions";
    public static final String PATH_EMBEDDINGS = "/embeddings";

    // 全池路由通配模型：default 客户请求该 model 时自动在池内所有启用渠道中轮询转发
    public static final String MODEL_POOL = "default";
}
