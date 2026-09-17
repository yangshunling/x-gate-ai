package com.xgateai.protocol;

/**
 * ProtocolConverter 客户端协议 ↔ OpenAI Chat 转换器
 * <p>
 * 网关对外协议（Anthropic Messages / OpenAI Responses）与上游 OpenAI Chat
 * 之间的双向转换契约。转换是纯格式变换：信息模型等价，仅做 JSON 结构重排、
 * 字段重命名与枚举映射，转换过程不产生或丢失语义。
 * </p>
 *
 * @author xgateai
 * @since 2026/9/17
 */
public interface ProtocolConverter {

    /**
     * 该转换器服务的客户端协议类型
     */
    ProtocolType clientProtocol();

    /**
     * 入站转换：把客户端协议的原始请求体转为 OpenAI Chat 请求体
     *
     * @param clientRawBody 客户端协议原始请求体 JSON
     * @return OpenAI Chat 请求体 JSON
     */
    String toChatRequest(String clientRawBody);

    /**
     * 出站转换（非流式）：把上游 OpenAI Chat 响应转为客户端协议响应
     *
     * @param chatJson 上游 OpenAI Chat 响应 JSON
     * @return 客户端协议响应 JSON
     */
    String fromChatResponse(String chatJson);

    /**
     * 出站转换（流式）：为一次流式请求创建有状态的 SSE 事件转换器。
     * 每次流式请求必须新建实例（内部持有消息状态机）。
     *
     * @param requestedModel 客户端请求的模型名（作为 message_start 的兜底 model 字段）
     * @return 流式转换器实例
     */
    StreamTransformer createStreamTransformer(String requestedModel);
}
