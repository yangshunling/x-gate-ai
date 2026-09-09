package com.xgateai.constant;

/**
 * GatewayConstant 网关层专用常量定义
 * <p>
 * 包含网关路由策略名、请求属性键、默认分页参数、OpenAI 兼容端点路径及全池路由标记。
 * </p>
 *
 * @author xgateai
 * @since 2026/9/8
 */
public class GatewayConstant {

    /**
     * 轮询故障转移负载策略名称
     */
    public static final String STRATEGY_ROUND_ROBIN = "ROUND_ROBIN";

    /**
     * 请求属性键：存储经鉴权拦截器解析后的 ModelChannel 对象
     */
    public static final String ATTR_API_KEY = "gateway_api_key";

    /**
     * 默认页码（第一页）
     */
    public static final int DEFAULT_PAGE_NUM = 1;

    /**
     * 默认每页条数
     */
    public static final int DEFAULT_PAGE_SIZE = 20;

    /**
     * OpenAI 兼容对话补全接口路径
     */
    public static final String PATH_CHAT_COMPLETIONS = "/chat/completions";

    /**
     * OpenAI 兼容向量化接口路径
     */
    public static final String PATH_EMBEDDINGS = "/embeddings";

    /**
     * OpenAI 兼容模型列表接口路径（探测上游可用模型）
     */
    public static final String PATH_MODELS = "/models";

    /**
     * 全池路由通配模型名：当客户端请求 model=default 时走全池匹配逻辑
     */
    public static final String MODEL_POOL = "default";
}
