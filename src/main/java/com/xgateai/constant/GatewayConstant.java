package com.xgateai.constant;

/**
 * GatewayConstant 网关常量定义
 *
 * @author xgateai
 * @since 2026/9/8
 */
public class GatewayConstant {

    /** 负载策略：轮询 */
    public static final String STRATEGY_ROUND_ROBIN = "ROUND_ROBIN";

    /** 请求属性键：存储解析后的 API Key 信息 */
    public static final String ATTR_API_KEY = "gateway_api_key";

    /** 默认页码 */
    public static final int DEFAULT_PAGE_NUM = 1;

    /** 默认每页条数 */
    public static final int DEFAULT_PAGE_SIZE = 20;

    /** 对外 API 端点：对话补全 */
    public static final String PATH_CHAT_COMPLETIONS = "/chat/completions";

    /** 对外 API 端点：向量化 */
    public static final String PATH_EMBEDDINGS = "/embeddings";

    /** 全池路由通配模型名 */
    public static final String MODEL_POOL = "default";
}
