package com.xgateai.protocol;

/**
 * ProtocolType 网关客户端协议类型
 * <p>
 * 网关对外暴露多套协议入口（OpenAI Chat / Anthropic Messages / OpenAI Responses），
 * 内部统一转换为 OpenAI Chat 请求体转发到上游，返回时再转换回客户端协议。
 * </p>
 *
 * @author xgateai
 * @since 2026/9/17
 */
public enum ProtocolType {

    /**
     * OpenAI Chat Completions 协议（内部通用中间表示，identity 转换）
     */
    OPENAI_CHAT,

    /**
     * Anthropic Messages 协议（POST /v1/messages）
     */
    ANTHROPIC_MESSAGES,

    /**
     * OpenAI Responses 协议（POST /v1/responses）
     */
    OPENAI_RESPONSES
}
