package com.xgateai.protocol;

/**
 * StreamTransformer 上游 SSE → 客户端协议 SSE 事件流转换器
 * <p>
 * 有状态组件：每个流式请求必须创建独立实例。上游 OpenAI Chat SSE 块
 * （{@code data: {...}} 单行）按块喂入 {@link #transform(byte[])}，
 * 输出为客户端协议的事件序列字节（如 Anthropic 的
 * {@code event: xxx\ndata: xxx\n\n} 双行格式）；上游流结束时调用
 * {@link #finish()} 补发收尾事件（如 content_block_stop / message_delta /
 * message_stop），保证事件开闭配对完整。
 * </p>
 *
 * @author xgateai
 * @since 2026/9/17
 */
public interface StreamTransformer {

    /**
     * 转换一个上游 SSE 数据块，返回要写给客户端的字节
     *
     * @param upstreamChunk 上游原始 SSE 数据块（可能跨行，含多个 data: 行）
     * @return 客户端协议事件字节；无可输出内容时返回空数组
     */
    byte[] transform(byte[] upstreamChunk);

    /**
     * 上游流结束后调用，补发收尾事件
     *
     * @return 收尾事件字节；无需补发时返回空数组
     */
    byte[] finish();
}
