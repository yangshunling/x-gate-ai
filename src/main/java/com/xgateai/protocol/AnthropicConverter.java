package com.xgateai.protocol;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.xgateai.exception.BadRequestException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AnthropicConverter Anthropic Messages 协议 ↔ OpenAI Chat 转换器
 * <p>
 * 实现 {@link ProtocolConverter}：把 Anthropic Messages 请求（POST /v1/messages）
 * 转为 OpenAI Chat 请求体走统一网关链路，再把上游 Chat 响应转回 Anthropic 格式。
 * </p>
 * <p>
 * 支持范围：
 * <ul>
 *   <li>请求：model / max_tokens（必填）/ system / messages（文本 + 图片 + 工具调用）/ tools / tool_choice / temperature / top_p / stream / stop_sequences</li>
 *   <li>工具映射：tool_use→tool_calls（input 对象 → arguments JSON 字符串）、tool_result→role=tool 消息（拆消息）、input_schema→parameters、tool_choice 枚举映射</li>
 *   <li>响应：文本 + tool_use content blocks、usage 映射（prompt_tokens→input_tokens，completion_tokens→output_tokens）、stop_reason 映射</li>
 *   <li>流式：上游 Chat SSE → Anthropic 事件流（text_delta 与 input_json_delta 混合，content_block 开闭配对）</li>
 * </ul>
 * 已知限制：Claude extended thinking（thinking 块）当前忽略；多 tool_call 分片假设按 index 顺序连续到达。
 * </p>
 *
 * @author xgateai
 * @since 2026/9/17
 */
@Slf4j
@Component
public class AnthropicConverter implements ProtocolConverter {

    /** Anthropic 请求体中的事件分隔前缀 */
    private static final String EVENT_PREFIX = "event: ";
    private static final String DATA_PREFIX = "data: ";

    /**
     * 该转换器服务的客户端协议类型
     *
     * @return ANTHROPIC_MESSAGES
     */
    @Override
    public ProtocolType clientProtocol() {
        return ProtocolType.ANTHROPIC_MESSAGES;
    }

    // ==================== 入站：Anthropic Messages → OpenAI Chat ====================

    /**
     * 入站转换：Anthropic Messages 请求体 → OpenAI Chat 请求体
     *
     * @param clientRawBody Anthropic 原始请求体 JSON
     * @return OpenAI Chat 请求体 JSON
     */
    @Override
    public String toChatRequest(String clientRawBody) {
        JSONObject body = JSON.parseObject(clientRawBody);
        if (body == null) {
            throw new BadRequestException("请求体为空或不是合法 JSON");
        }

        String model = StrUtil.trim(body.getString("model"));
        if (StrUtil.isBlank(model)) {
            throw new BadRequestException("请求体 model 字段不能为空");
        }
        JSONArray messages = body.getJSONArray("messages");
        if (messages == null || messages.isEmpty()) {
            throw new BadRequestException("请求体 messages 字段不能为空");
        }
        // Anthropic 协议要求 max_tokens 必填
        if (!body.containsKey("max_tokens") || body.getInteger("max_tokens") == null) {
            throw new BadRequestException("请求体 max_tokens 字段必填");
        }

        JSONObject chat = new JSONObject();
        chat.put("model", model);
        chat.put("max_tokens", body.getInteger("max_tokens"));
        // 透传通用采样参数（top_k 在 OpenAI 无对应，丢弃；thinking 块暂忽略）
        if (body.containsKey("temperature")) chat.put("temperature", body.getDouble("temperature"));
        if (body.containsKey("top_p")) chat.put("top_p", body.getDouble("top_p"));
        if (body.containsKey("stream")) chat.put("stream", body.getBooleanValue("stream"));
        // stop_sequences → OpenAI stop（两者均为字符串数组）
        JSONArray stopSequences = body.getJSONArray("stop_sequences");
        if (stopSequences != null && !stopSequences.isEmpty()) {
            chat.put("stop", stopSequences);
        }
        if (body.containsKey("metadata")) chat.put("metadata", body.getJSONObject("metadata"));
        // 工具定义：input_schema → parameters
        JSONArray tools = body.getJSONArray("tools");
        if (tools != null && !tools.isEmpty()) {
            chat.put("tools", convertTools(tools));
        }
        // 工具选择策略
        Object toolChoice = body.get("tool_choice");
        if (toolChoice != null) {
            chat.put("tool_choice", convertToolChoice(toolChoice));
        }

        // 消息体转换：顶层 system 插到首位，content blocks 按序映射
        JSONArray chatMessages = new JSONArray();
        String system = extractSystemText(body.get("system"));
        if (StrUtil.isNotBlank(system)) {
            chatMessages.add(buildSimpleMessage("system", system));
        }
        for (int i = 0; i < messages.size(); i++) {
            JSONObject msg = messages.getJSONObject(i);
            if (msg == null) continue;
            String role = StrUtil.blankToDefault(msg.getString("role"), "user");
            Object content = msg.get("content");
            if (content instanceof String text) {
                chatMessages.add(buildSimpleMessage(role, text));
            } else if (content instanceof JSONArray blocks) {
                if ("assistant".equals(role)) {
                    chatMessages.add(convertAssistantMessage(blocks));
                } else {
                    // user 消息可能含 tool_result 块，需拆分为多条消息（tool 消息 + user 消息）
                    chatMessages.addAll(convertUserMessageBlocks(blocks));
                }
            } else {
                throw new BadRequestException("messages[" + i + "].content 格式不支持，仅支持字符串或 content block 数组");
            }
        }
        chat.put("messages", chatMessages);
        return chat.toJSONString();
    }

    /**
     * 提取顶层 system 字段文本。Anthropic 允许 string 或 content block 数组，仅取 text 块。
     */
    private String extractSystemText(Object system) {
        if (system == null) return null;
        if (system instanceof String s) return s;
        if (system instanceof JSONArray arr) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.size(); i++) {
                JSONObject block = arr.getJSONObject(i);
                if (block != null && "text".equals(block.getString("type"))) {
                    sb.append(StrUtil.nullToEmpty(block.getString("text")));
                }
            }
            return sb.toString();
        }
        return null;
    }

    /**
     * 构建单条纯文本消息
     *
     * @param role    消息角色
     * @param content 文本内容
     * @return 消息 JSON 对象
     */
    private JSONObject buildSimpleMessage(String role, String content) {
        JSONObject msg = new JSONObject();
        msg.put("role", role);
        msg.put("content", StrUtil.nullToEmpty(content));
        return msg;
    }

    /**
     * 转换 assistant 消息的 content block 数组。
     * <pre>
     * Anthropic: [{type:text,...}, {type:tool_use, id, name, input}, ...]
     * OpenAI:    {role:assistant, content: 文本或null, tool_calls:[{id, type:function, function:{name, arguments}}]}
     * </pre>
     * 文本块拼接为 content，tool_use 块转 tool_calls（input 对象 → arguments JSON 字符串）；thinking 块忽略。
     */
    private JSONObject convertAssistantMessage(JSONArray blocks) {
        StringBuilder text = new StringBuilder();
        JSONArray toolCalls = new JSONArray();
        for (int i = 0; i < blocks.size(); i++) {
            JSONObject block = blocks.getJSONObject(i);
            if (block == null) continue;
            String type = block.getString("type");
            switch (type == null ? "" : type) {
                case "text" -> text.append(StrUtil.nullToEmpty(block.getString("text")));
                case "tool_use" -> {
                    JSONObject tc = new JSONObject();
                    tc.put("id", block.getString("id"));
                    tc.put("type", "function");
                    JSONObject fn = new JSONObject();
                    fn.put("name", block.getString("name"));
                    Object input = block.get("input");
                    fn.put("arguments", input == null ? "{}" : JSON.toJSONString(input));
                    tc.put("function", fn);
                    toolCalls.add(tc);
                }
                case "thinking", "redacted_thinking" -> {
                    // Claude extended thinking：OpenAI Chat 无对应参数，M2 阶段忽略
                    log.debug("忽略 Anthropic thinking 块（extended thinking 暂不支持）");
                }
                default -> throw new BadRequestException("不支持的 assistant content block 类型: " + type);
            }
        }
        JSONObject msg = new JSONObject();
        msg.put("role", "assistant");
        msg.put("content", text.length() == 0 ? null : text.toString());
        if (!toolCalls.isEmpty()) {
            msg.put("tool_calls", toolCalls);
        }
        return msg;
    }

    /**
     * 转换 user 消息的 content block 数组：tool_result 块拆成独立的 role=tool 消息，
     * 其余 text/image 块合并为一条 role=user 消息，按原顺序输出（可多条）。
     * <pre>
     * Anthropic: [{type:text}, {type:tool_result, tool_use_id, content}, ...]
     * OpenAI:    [{role:user,...}, {role:tool, tool_call_id, content}, ...]
     * </pre>
     */
    private List<JSONObject> convertUserMessageBlocks(JSONArray blocks) {
        List<JSONObject> result = new ArrayList<>();
        JSONArray userContent = new JSONArray();
        for (int i = 0; i < blocks.size(); i++) {
            JSONObject block = blocks.getJSONObject(i);
            if (block == null) continue;
            String type = block.getString("type");
            switch (type == null ? "" : type) {
                case "tool_result" -> {
                    // 先 flush 累积的 user content，保证 tool 消息紧随 assistant tool_calls
                    if (!userContent.isEmpty()) {
                        result.add(buildContentUserMessage(userContent));
                        userContent = new JSONArray();
                    }
                    JSONObject toolMsg = new JSONObject();
                    toolMsg.put("role", "tool");
                    toolMsg.put("tool_call_id", block.getString("tool_use_id"));
                    toolMsg.put("content", extractToolResultContent(block.get("content")));
                    result.add(toolMsg);
                }
                case "text" -> {
                    JSONObject t = new JSONObject();
                    t.put("type", "text");
                    t.put("text", StrUtil.nullToEmpty(block.getString("text")));
                    userContent.add(t);
                }
                case "image" -> userContent.add(convertImageBlock(block));
                default -> throw new BadRequestException("不支持的 user content block 类型: " + type);
            }
        }
        if (!userContent.isEmpty()) {
            result.add(buildContentUserMessage(userContent));
        }
        return result;
    }

    /**
     * 构建 user 角色、content blocks 数组格式的消息
     *
     * @param content content blocks 数组
     * @return 消息 JSON 对象
     */
    private JSONObject buildContentUserMessage(JSONArray content) {
        JSONObject msg = new JSONObject();
        msg.put("role", "user");
        msg.put("content", content);
        return msg;
    }

    /**
     * 提取 tool_result 的 content 文本：支持字符串或 text/image content block 数组（仅取 text）。
     */
    private String extractToolResultContent(Object content) {
        if (content == null) return "";
        if (content instanceof String s) return s;
        if (content instanceof JSONArray arr) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.size(); i++) {
                JSONObject block = arr.getJSONObject(i);
                if (block != null && "text".equals(block.getString("type"))) {
                    sb.append(StrUtil.nullToEmpty(block.getString("text")));
                }
            }
            return sb.toString();
        }
        return content.toString();
    }

    /**
     * Anthropic tools 定义 → OpenAI tools 定义
     * <pre>
     * Anthropic: {"name","description","input_schema":{JSON Schema}}
     * OpenAI:    {"type":"function","function":{"name","description","parameters":{JSON Schema}}}
     * </pre>
     */
    private JSONArray convertTools(JSONArray anthropicTools) {
        JSONArray chatTools = new JSONArray();
        for (int i = 0; i < anthropicTools.size(); i++) {
            JSONObject tool = anthropicTools.getJSONObject(i);
            if (tool == null) continue;
            JSONObject chatTool = new JSONObject();
            chatTool.put("type", "function");
            JSONObject fn = new JSONObject();
            fn.put("name", tool.getString("name"));
            fn.put("description", StrUtil.nullToEmpty(tool.getString("description")));
            JSONObject inputSchema = tool.getJSONObject("input_schema");
            fn.put("parameters", inputSchema == null ? new JSONObject() : inputSchema);
            chatTool.put("function", fn);
            chatTools.add(chatTool);
        }
        return chatTools;
    }

    /**
     * Anthropic tool_choice → OpenAI tool_choice
     * <pre>
     * {"type":"auto"}                          → "auto"
     * {"type":"any"}                           → "required"
     * {"type":"tool","name":"xxx"}             → {"type":"function","function":{"name":"xxx"}}
     * {"type":"none"}                          → "none"
     * </pre>
     */
    private Object convertToolChoice(Object toolChoice) {
        if (toolChoice instanceof String s) return s;
        if (toolChoice instanceof JSONObject obj) {
            String type = obj.getString("type");
            if (type == null) return "auto";
            return switch (type) {
                case "auto" -> "auto";
                case "any" -> "required";
                case "tool" -> {
                    JSONObject r = new JSONObject();
                    r.put("type", "function");
                    JSONObject f = new JSONObject();
                    f.put("name", obj.getString("name"));
                    r.put("function", f);
                    yield r;
                }
                case "none" -> "none";
                default -> "auto";
            };
        }
        return "auto";
    }

    /**
     * Anthropic image block → OpenAI image_url block
     * <pre>
     * Anthropic: {"type":"image","source":{"type":"base64","media_type":"image/png","data":"..."}}
     * OpenAI:    {"type":"image_url","image_url":{"url":"data:image/png;base64,..."}}
     * </pre>
     */
    private JSONObject convertImageBlock(JSONObject block) {
        JSONObject source = block.getJSONObject("source");
        if (source == null) {
            throw new BadRequestException("image content block 缺少 source 字段");
        }
        String sourceType = source.getString("type");
        JSONObject imageUrl = new JSONObject();
        if ("base64".equals(sourceType)) {
            String mediaType = StrUtil.blankToDefault(source.getString("media_type"), "image/png");
            String data = StrUtil.nullToEmpty(source.getString("data"));
            imageUrl.put("url", "data:" + mediaType + ";base64," + data);
        } else if ("url".equals(sourceType)) {
            imageUrl.put("url", StrUtil.nullToEmpty(source.getString("url")));
        } else {
            throw new BadRequestException("不支持的 image source 类型: " + sourceType);
        }
        JSONObject out = new JSONObject();
        out.put("type", "image_url");
        out.put("image_url", imageUrl);
        return out;
    }

    // ==================== 出站（非流式）：OpenAI Chat → Anthropic Messages ====================

    /**
     * 出站转换（非流式）：OpenAI Chat 响应 → Anthropic Messages 响应
     *
     * @param chatJson 上游 OpenAI Chat 响应 JSON
     * @return Anthropic Messages 响应 JSON
     */
    @Override
    public String fromChatResponse(String chatJson) {
        JSONObject resp = JSON.parseObject(chatJson);
        if (resp == null) {
            throw new BadRequestException("上游响应为空或不是合法 JSON");
        }
        JSONArray choices = resp.getJSONArray("choices");
        JSONObject choice = (choices == null || choices.isEmpty()) ? null : choices.getJSONObject(0);
        JSONObject message = choice == null ? null : choice.getJSONObject("message");

        JSONObject anthropic = new JSONObject();
        anthropic.put("id", StrUtil.blankToDefault(resp.getString("id"), genMessageId()));
        anthropic.put("type", "message");
        anthropic.put("role", "assistant");

        // 文本 + tool_use content blocks（text 在前，tool_use 在后，与 Anthropic 官方一致）
        JSONArray content = new JSONArray();
        String text = message == null ? null : message.getString("content");
        if (StrUtil.isNotBlank(text)) {
            JSONObject textBlock = new JSONObject();
            textBlock.put("type", "text");
            textBlock.put("text", text);
            content.add(textBlock);
        }
        JSONArray toolCalls = message == null ? null : message.getJSONArray("tool_calls");
        if (toolCalls != null) {
            for (int i = 0; i < toolCalls.size(); i++) {
                JSONObject tc = toolCalls.getJSONObject(i);
                if (tc == null) continue;
                JSONObject toolUseBlock = new JSONObject();
                toolUseBlock.put("type", "tool_use");
                toolUseBlock.put("id", tc.getString("id"));
                JSONObject fn = tc.getJSONObject("function");
                toolUseBlock.put("name", fn == null ? null : fn.getString("name"));
                // arguments JSON 字符串 → input 对象
                String args = fn == null ? "{}" : StrUtil.nullToEmpty(fn.getString("arguments"));
                Object input;
                try {
                    input = JSON.parse(args);
                } catch (Exception e) {
                    input = null;
                }
                toolUseBlock.put("input", input == null ? new JSONObject() : input);
                content.add(toolUseBlock);
            }
        }
        // 空响应兜底：至少一个空 text 块
        if (content.isEmpty()) {
            JSONObject textBlock = new JSONObject();
            textBlock.put("type", "text");
            textBlock.put("text", "");
            content.add(textBlock);
        }
        anthropic.put("content", content);

        anthropic.put("model", resp.getString("model"));
        anthropic.put("stop_reason", mapFinishReason(choice == null ? null : choice.getString("finish_reason")));
        anthropic.put("stop_sequence", null);

        JSONObject usage = resp.getJSONObject("usage");
        JSONObject anthropicUsage = new JSONObject();
        if (usage != null) {
            anthropicUsage.put("input_tokens", usage.getIntValue("prompt_tokens", 0));
            anthropicUsage.put("output_tokens", usage.getIntValue("completion_tokens", 0));
        } else {
            anthropicUsage.put("input_tokens", 0);
            anthropicUsage.put("output_tokens", 0);
        }
        anthropic.put("usage", anthropicUsage);
        return anthropic.toJSONString();
    }

    /**
     * OpenAI finish_reason → Anthropic stop_reason 枚举映射
     */
    private String mapFinishReason(String finishReason) {
        if (finishReason == null) return "end_turn";
        return switch (finishReason) {
            case "stop" -> "end_turn";
            case "length" -> "max_tokens";
            case "tool_calls", "function_call" -> "tool_use";
            case "content_filter" -> "refusal";
            default -> "end_turn";
        };
    }

    /**
     * 生成 Anthropic 格式的消息 ID
     *
     * @return 形如 msg_xxxxxxxx 的随机 ID
     */
    private String genMessageId() {
        return "msg_" + UUID.randomUUID().toString().replace("-", "");
    }

    // ==================== 出站（流式）：OpenAI Chat SSE → Anthropic 事件流 ====================

    /**
     * 创建 Anthropic 流式事件转换器
     *
     * @param requestedModel 客户端请求的模型名（作为 message_start 的兜底 model 字段）
     * @return Anthropic 流式转换器实例
     */
    @Override
    public StreamTransformer createStreamTransformer(String requestedModel) {
        return new AnthropicStreamTransformer(requestedModel);
    }

    /**
     * Anthropic 流式事件状态机
     * <p>
     * 上游 OpenAI Chat SSE 逐块喂入，映射为 Anthropic 双行事件。支持文本（text_delta）
     * 与工具调用（input_json_delta）两种 content block，并按 Anthropic 规范保证
     * content_block 开闭配对（start → delta×N → stop，同一时刻仅一个 block 打开）。
     * </p>
     * <p>
     * 事件序列：message_start → content_block_start → content_block_delta×N →
     * content_block_stop → message_delta → message_stop。usage 注入策略：
     * message_start 的 input_tokens 取首个携带 usage 的块，否则为 0；message_delta 的
     * output_tokens 取末尾携带 usage 的块（OpenAI 上游通常在最后一个块带 usage）。
     * </p>
     */
    private class AnthropicStreamTransformer implements StreamTransformer {

        private final String fallbackModel;

        /** 是否已发 message_start */
        private boolean messageStarted;
        /** 是否已发过至少一个 content_block_start */
        private boolean anyBlockOpened;
        /** 下一个可分配的 Anthropic content block 序号（全局递增） */
        private int nextBlockIndex;
        /** 当前打开的 content block（null 表示无） */
        private BlockOpen currentBlock;
        /** OpenAI tool_calls 状态，key 为 OpenAI 的 tool_calls[].index */
        private final Map<Integer, ToolCallState> toolCallStates = new HashMap<>();
        /** 上游响应 id（首个块捕获） */
        private String responseId;
        /** 上游返回的模型名（首个块捕获） */
        private String responseModel;
        /** 首个携带 usage 的块（用于 input_tokens） */
        private JSONObject firstUsage;
        /** 末尾携带 usage 的块（用于 output_tokens） */
        private JSONObject lastUsage;
        /** 上游 finish_reason（流中捕获，用于 stop_reason 映射） */
        private String finishReason;
        /** 流结束标记，防止重复补发 */
        private boolean finished;

        /**
         * 构造流式转换器
         *
         * @param fallbackModel 兜底模型名（上游未返回 model 时使用）
         */
        AnthropicStreamTransformer(String fallbackModel) {
            this.fallbackModel = fallbackModel;
        }

        /**
         * 转换一个上游 SSE 数据块为 Anthropic 事件字节
         *
         * @param upstreamChunk 上游原始 SSE 数据块
         * @return Anthropic 事件字节；无可输出内容时返回空数组
         */
        @Override
        public byte[] transform(byte[] upstreamChunk) {
            StringBuilder out = new StringBuilder();
            String text = new String(upstreamChunk, StandardCharsets.UTF_8);
            for (String line : text.split("\r?\n")) {
                String data;
                if (line.startsWith("data:")) {
                    data = line.substring("data:".length()).trim();
                } else {
                    // 兜底：部分兼容上游可能直接吐裸 JSON 行
                    data = line.trim();
                }
                if (data.isEmpty() || "[DONE]".equals(data)) continue;

                JSONObject obj;
                try {
                    obj = JSON.parseObject(data);
                } catch (Exception e) {
                    continue;
                }
                if (obj == null) continue;

                if (responseId == null) responseId = obj.getString("id");
                if (responseModel == null) responseModel = obj.getString("model");

                JSONObject usage = obj.getJSONObject("usage");
                if (usage != null) {
                    if (firstUsage == null) firstUsage = usage;
                    lastUsage = usage;
                }

                JSONArray choices = obj.getJSONArray("choices");
                if (choices == null || choices.isEmpty()) continue;
                JSONObject choice = choices.getJSONObject(0);
                if (choice.getString("finish_reason") != null) {
                    finishReason = choice.getString("finish_reason");
                }
                JSONObject delta = choice.getJSONObject("delta");
                if (delta == null) continue;

                // ---- 文本增量 ----
                String content = delta.getString("content");
                if (content == null) content = delta.getString("text");
                if (StrUtil.isNotBlank(content)) {
                    switchToBlock(out, "text", -1);
                    appendEvent(out, "content_block_delta",
                            buildTextDelta(currentBlock.anthropicIndex, content));
                }

                // ---- 工具调用增量 ----
                JSONArray toolCalls = delta.getJSONArray("tool_calls");
                if (toolCalls != null && !toolCalls.isEmpty()) {
                    for (int i = 0; i < toolCalls.size(); i++) {
                        JSONObject tc = toolCalls.getJSONObject(i);
                        if (tc == null) continue;
                        int openaiIdx = tc.getIntValue("index", 0);
                        ToolCallState state = toolCallStates.computeIfAbsent(openaiIdx, k -> new ToolCallState());
                        // 首片携带 id / name
                        String id = tc.getString("id");
                        if (id != null) state.id = id;
                        JSONObject fn = tc.getJSONObject("function");
                        if (fn != null) {
                            String name = fn.getString("name");
                            if (name != null) state.name = name;
                        }
                        // id + name 齐备且未 start → 打开 tool_use content block
                        if (!state.started && state.id != null && state.name != null) {
                            switchToBlock(out, "tool_use", openaiIdx);
                            state.started = true;
                            state.anthropicIndex = currentBlock.anthropicIndex;
                        }
                        // arguments 分片 → input_json_delta
                        String arguments = fn == null ? null : fn.getString("arguments");
                        if (StrUtil.isNotBlank(arguments) && state.started) {
                            appendEvent(out, "content_block_delta",
                                    buildInputJsonDelta(state.anthropicIndex, arguments));
                        }
                    }
                }
            }
            return out.toString().getBytes(StandardCharsets.UTF_8);
        }

        /**
         * 上游流结束：补发收尾事件（content_block_stop / message_delta / message_stop）
         *
         * @return 收尾事件字节；已结束时返回空数组
         */
        @Override
        public byte[] finish() {
            if (finished) return new byte[0];
            finished = true;
            StringBuilder out = new StringBuilder();
            // 兜底：上游空响应或仅返回 role 时，也要给出完整事件序列
            ensureMessageStarted(out);
            if (currentBlock == null && !anyBlockOpened) {
                switchToBlock(out, "text", -1);
            }
            if (currentBlock != null) {
                appendEvent(out, "content_block_stop",
                        buildContentBlockStop(currentBlock.anthropicIndex));
                currentBlock = null;
            }
            appendEvent(out, "message_delta", buildMessageDelta());
            appendEvent(out, "message_stop", new JSONObject());
            return out.toString().getBytes(StandardCharsets.UTF_8);
        }

        // ---------- 状态机辅助 ----------

        /**
         * 切换到指定类型的 content block：若当前打开的 block 不是目标类型，
         * 先发 content_block_stop 关闭，再发新的 content_block_start。
         *
         * @param targetType          目标 block 类型：text / tool_use
         * @param openaiToolCallIndex 目标 tool_call 的 OpenAI index（type=text 时为 -1）
         */
        private void switchToBlock(StringBuilder out, String targetType, int openaiToolCallIndex) {
            boolean sameBlock;
            if (currentBlock == null) {
                sameBlock = false;
            } else if ("text".equals(targetType)) {
                sameBlock = "text".equals(currentBlock.type);
            } else {
                sameBlock = "tool_use".equals(currentBlock.type)
                        && openaiToolCallIndex == currentBlock.openaiToolCallIndex;
            }
            if (sameBlock) return;

            if (currentBlock != null) {
                appendEvent(out, "content_block_stop",
                        buildContentBlockStop(currentBlock.anthropicIndex));
            }
            ensureMessageStarted(out);
            if ("text".equals(targetType)) {
                openTextBlock(out);
            } else {
                openToolUseBlock(out, openaiToolCallIndex);
            }
        }

        /**
         * 确保已发送 message_start 事件（幂等）
         *
         * @param out 事件输出构建器
         */
        private void ensureMessageStarted(StringBuilder out) {
            if (messageStarted) return;
            messageStarted = true;
            JSONObject message = new JSONObject();
            message.put("id", StrUtil.blankToDefault(responseId, genMessageId()));
            message.put("type", "message");
            message.put("role", "assistant");
            message.put("content", new JSONArray());
            message.put("model", StrUtil.blankToDefault(responseModel, fallbackModel));
            message.put("stop_reason", null);
            message.put("stop_sequence", null);
            JSONObject usage = new JSONObject();
            usage.put("input_tokens", firstUsage == null ? 0 : firstUsage.getIntValue("prompt_tokens", 0));
            usage.put("output_tokens", 0);
            message.put("usage", usage);

            JSONObject eventData = new JSONObject();
            eventData.put("type", "message_start");
            eventData.put("message", message);
            appendEvent(out, "message_start", eventData);
        }

        /**
         * 打开 text 类型 content block 并发送 content_block_start 事件
         *
         * @param out 事件输出构建器
         */
        private void openTextBlock(StringBuilder out) {
            int idx = nextBlockIndex++;
            JSONObject contentBlock = new JSONObject();
            contentBlock.put("type", "text");
            contentBlock.put("text", "");
            JSONObject eventData = new JSONObject();
            eventData.put("type", "content_block_start");
            eventData.put("index", idx);
            eventData.put("content_block", contentBlock);
            appendEvent(out, "content_block_start", eventData);
            currentBlock = new BlockOpen("text", idx);
            anyBlockOpened = true;
        }

        /**
         * 打开 tool_use 类型 content block 并发送 content_block_start 事件
         *
         * @param out            事件输出构建器
         * @param openaiToolIdx  OpenAI tool_calls index
         */
        private void openToolUseBlock(StringBuilder out, int openaiToolIdx) {
            ToolCallState state = toolCallStates.get(openaiToolIdx);
            int idx = nextBlockIndex++;
            JSONObject contentBlock = new JSONObject();
            contentBlock.put("type", "tool_use");
            contentBlock.put("id", state.id);
            contentBlock.put("name", state.name);
            contentBlock.put("input", new JSONObject());
            JSONObject eventData = new JSONObject();
            eventData.put("type", "content_block_start");
            eventData.put("index", idx);
            eventData.put("content_block", contentBlock);
            appendEvent(out, "content_block_start", eventData);
            state.anthropicIndex = idx;
            currentBlock = new BlockOpen("tool_use", idx, openaiToolIdx);
            anyBlockOpened = true;
        }

        /**
         * 构建 content_block_delta 事件（text_delta 类型）
         *
         * @param index content block 序号
         * @param text  文本增量
         * @return 事件数据对象
         */
        private JSONObject buildTextDelta(int index, String text) {
            JSONObject delta = new JSONObject();
            delta.put("type", "text_delta");
            delta.put("text", text);
            JSONObject eventData = new JSONObject();
            eventData.put("type", "content_block_delta");
            eventData.put("index", index);
            eventData.put("delta", delta);
            return eventData;
        }

        /**
         * 构建 content_block_delta 事件（input_json_delta 类型）
         *
         * @param index        content block 序号
         * @param partialJson 工具参数 JSON 分片
         * @return 事件数据对象
         */
        private JSONObject buildInputJsonDelta(int index, String partialJson) {
            JSONObject delta = new JSONObject();
            delta.put("type", "input_json_delta");
            delta.put("partial_json", partialJson);
            JSONObject eventData = new JSONObject();
            eventData.put("type", "content_block_delta");
            eventData.put("index", index);
            eventData.put("delta", delta);
            return eventData;
        }

        /**
         * 构建 content_block_stop 事件
         *
         * @param index content block 序号
         * @return 事件数据对象
         */
        private JSONObject buildContentBlockStop(int index) {
            JSONObject eventData = new JSONObject();
            eventData.put("type", "content_block_stop");
            eventData.put("index", index);
            return eventData;
        }

        /**
         * 构建 message_delta 事件（含 stop_reason 与 output_tokens 用量）
         *
         * @return 事件数据对象
         */
        private JSONObject buildMessageDelta() {
            JSONObject delta = new JSONObject();
            delta.put("stop_reason", mapFinishReason(finishReason));
            delta.put("stop_sequence", null);
            JSONObject usage = new JSONObject();
            usage.put("output_tokens", lastUsage == null ? 0 : lastUsage.getIntValue("completion_tokens", 0));
            JSONObject eventData = new JSONObject();
            eventData.put("type", "message_delta");
            eventData.put("delta", delta);
            eventData.put("usage", usage);
            return eventData;
        }

        /**
         * 输出一个 Anthropic 事件：
         * <pre>
         * event: &lt;name&gt;
         * data: &lt;json&gt;
         *
         * </pre>
         */
        private void appendEvent(StringBuilder out, String eventName, JSONObject data) {
            out.append(EVENT_PREFIX).append(eventName).append('\n');
            out.append(DATA_PREFIX).append(data.toJSONString()).append('\n');
            out.append('\n');
        }
    }

    /** 当前打开的 content block 描述 */
    private static class BlockOpen {
        /** block 类型：text / tool_use */
        final String type;
        /** Anthropic content block 序号 */
        int anthropicIndex;
        /** OpenAI tool_calls index（type=tool_use 时有效，text 为 -1） */
        final int openaiToolCallIndex;

    /**
     * 构造 content block 描述
     *
     * @param type           block 类型：text / tool_use
     * @param anthropicIndex Anthropic content block 序号
     */
    BlockOpen(String type, int anthropicIndex) {
        this(type, anthropicIndex, -1);
    }

    /**
     * 构造 content block 描述
     *
     * @param type                 block 类型：text / tool_use
     * @param anthropicIndex       Anthropic content block 序号
     * @param openaiToolCallIndex  OpenAI tool_calls index（text 类型为 -1）
     */
    BlockOpen(String type, int anthropicIndex, int openaiToolCallIndex) {
        this.type = type;
        this.anthropicIndex = anthropicIndex;
        this.openaiToolCallIndex = openaiToolCallIndex;
    }
    }

    /** OpenAI 单个 tool_call 的流式状态 */
    private static class ToolCallState {
        /** 工具调用 ID */
        String id;
        /** 工具名称 */
        String name;
        /** 是否已发送 content_block_start */
        boolean started;
        /** 分配的 Anthropic content block 序号 */
        int anthropicIndex;
    }
}
