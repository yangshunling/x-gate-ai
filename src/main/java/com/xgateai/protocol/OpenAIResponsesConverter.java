package com.xgateai.protocol;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.xgateai.exception.BadRequestException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * OpenAIResponsesConverter OpenAI Responses 协议 ↔ OpenAI Chat 转换器
 * <p>
 * 实现 {@link ProtocolConverter}：把 OpenAI Responses 请求（POST /v1/responses）
 * 转为 OpenAI Chat 请求体走统一网关链路，再把上游 Chat 响应转回 Responses 格式。
 * Responses 与 Chat 同源（OpenAI 自家设计），转换比 Anthropic 更直接。
 * </p>
 * <p>
 * 支持范围：
 * <ul>
 *   <li>请求：model / input（字符串 / messages 数组 / input items 数组三态）/ instructions / max_output_tokens / temperature / top_p / stream / tools（过滤内置工具）/ tool_choice / reasoning.effort / parallel_tool_calls / text.format</li>
 *   <li>input items：message（input_text/input_image/output_text 块）/ function_call / function_call_output / reasoning（忽略）</li>
 *   <li>响应：output 数组（message item 含 output_text + function_call items）、usage 映射、status 映射</li>
 *   <li>流式：上游 Chat SSE → Responses 事件流（response.created → output_item.added → content_part.added → output_text.delta×N → output_text.done → content_part.done → output_item.done → response.completed，含 function_call_arguments.delta）</li>
 * </ul>
 * 已知限制：previous_response_id / store（服务端会话）忽略；文件输入（input_file）不支持。
 * </p>
 *
 * @author xgateai
 * @since 2026/9/17
 */
@Slf4j
@Component
public class OpenAIResponsesConverter implements ProtocolConverter {

    private static final String EVENT_PREFIX = "event: ";
    private static final String DATA_PREFIX = "data: ";

    /** Responses input items 的类型集合，用于区分 items 数组与 messages 数组 */
    private static final java.util.Set<String> ITEM_TYPES = java.util.Set.of(
            "message", "function_call", "function_call_output", "reasoning");

    /**
     * 该转换器服务的客户端协议类型
     *
     * @return OPENAI_RESPONSES
     */
    @Override
    public ProtocolType clientProtocol() {
        return ProtocolType.OPENAI_RESPONSES;
    }

    // ==================== 入站：OpenAI Responses → OpenAI Chat ====================

    /**
     * 入站转换：OpenAI Responses 请求体 → OpenAI Chat 请求体
     *
     * @param clientRawBody Responses 原始请求体 JSON
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
        Object input = body.get("input");
        if (input == null) {
            throw new BadRequestException("请求体 input 字段不能为空");
        }

        JSONObject chat = new JSONObject();
        chat.put("model", model);
        // max_output_tokens → max_tokens
        if (body.containsKey("max_output_tokens") && body.getInteger("max_output_tokens") != null) {
            chat.put("max_tokens", body.getInteger("max_output_tokens"));
        }
        if (body.containsKey("temperature")) chat.put("temperature", body.getDouble("temperature"));
        if (body.containsKey("top_p")) chat.put("top_p", body.getDouble("top_p"));
        if (body.containsKey("stream")) chat.put("stream", body.getBooleanValue("stream"));
        if (body.containsKey("parallel_tool_calls")) {
            chat.put("parallel_tool_calls", body.getBooleanValue("parallel_tool_calls"));
        }
        if (body.containsKey("tool_choice")) {
            // Responses 的 function 引用是扁平结构 {"type":"function","name":"foo"}，
            // Chat API 需要嵌套在 function 键下，重排为 {"type":"function","function":{"name":"foo"}}
            chat.put("tool_choice", toChatToolChoice(body.get("tool_choice")));
        }
        // reasoning.effort → reasoning_effort（OpenAI o 系列顶层参数）
        JSONObject reasoning = body.getJSONObject("reasoning");
        if (reasoning != null && StrUtil.isNotBlank(reasoning.getString("effort"))) {
            chat.put("reasoning_effort", reasoning.getString("effort"));
        }
        // text.format → response_format
        // 注意：Responses API 的 json_schema 是扁平结构（name/schema/strict 与 type 同级），
        // 而 Chat API 要求嵌套在 json_schema 键下，直接透传会导致上游反序列化报
        // “response_format: missing field json_schema”。
        JSONObject text = body.getJSONObject("text");
        if (text != null) {
            JSONObject format = text.getJSONObject("format");
            if (format != null) {
                JSONObject chatFormat = toChatResponseFormat(format);
                if (chatFormat != null) {
                    chat.put("response_format", chatFormat);
                }
            }
        }
        // tools：仅保留 function 类型，过滤内置工具（file_search / web_search 等）；
        // Responses 的 function tool 是扁平结构（name/description/parameters/strict 与 type 同级），
        // Chat API 要求嵌套在 function 键下，需重排；同时兜底补全缺失的 parameters（无参工具）。
        JSONArray tools = body.getJSONArray("tools");
        if (tools != null && !tools.isEmpty()) {
            JSONArray chatTools = new JSONArray();
            for (int i = 0; i < tools.size(); i++) {
                JSONObject tool = tools.getJSONObject(i);
                if (tool == null || !"function".equals(tool.getString("type"))) continue;
                chatTools.add(toChatFunctionTool(tool));
            }
            if (!chatTools.isEmpty()) {
                chat.put("tools", chatTools);
            }
        }

        // messages 转换：instructions → system，input 三态归一
        JSONArray chatMessages = new JSONArray();
        String instructions = body.getString("instructions");
        if (StrUtil.isNotBlank(instructions)) {
            chatMessages.add(buildSimpleMessage("system", instructions));
        }

        if (input instanceof String s) {
            chatMessages.add(buildSimpleMessage("user", s));
        } else if (input instanceof JSONArray arr) {
            if (isInputItemsArray(arr)) {
                convertInputItems(arr, chatMessages);
            } else {
                convertInputMessages(arr, chatMessages);
            }
        } else {
            throw new BadRequestException("input 字段仅支持字符串或数组");
        }
        chat.put("messages", chatMessages);

        // previous_response_id / store 忽略（Chat 无服务端会话）
        return chat.toJSONString();
    }

    /**
     * 判断 input 数组是新格式（items，元素有 type 字段）还是旧格式（messages，元素有 role 字段）
     */
    private boolean isInputItemsArray(JSONArray arr) {
        for (int i = 0; i < arr.size(); i++) {
            JSONObject item = arr.getJSONObject(i);
            if (item == null) continue;
            String type = item.getString("type");
            if (type != null && ITEM_TYPES.contains(type)) {
                return true;
            }
            // 一旦遇到 role 字段且无 items 类型，判定为 messages 数组
            if (item.containsKey("role")) {
                return false;
            }
        }
        return false;
    }

    /**
     * 转换旧格式 input（messages 数组）：role + content（字符串或 blocks）
     */
    private void convertInputMessages(JSONArray messages, JSONArray out) {
        for (int i = 0; i < messages.size(); i++) {
            JSONObject msg = messages.getJSONObject(i);
            if (msg == null) continue;
            String role = StrUtil.blankToDefault(msg.getString("role"), "user");
            Object content = msg.get("content");
            if (content instanceof String text) {
                out.add(buildSimpleMessage(role, text));
            } else if (content instanceof JSONArray blocks) {
                out.add(buildMessageFromBlocks(role, blocks));
            } else if (content == null) {
                JSONObject m = new JSONObject();
                m.put("role", role);
                m.put("content", "");
                out.add(m);
            } else {
                throw new BadRequestException("input[" + i + "].content 格式不支持");
            }
        }
    }

    /**
     * 转换新格式 input（items 数组）：message / function_call / function_call_output / reasoning
     * 连续的 assistant message + function_call 聚合为一条 assistant 消息（content + tool_calls）。
     */
    private void convertInputItems(JSONArray items, JSONArray out) {
        AssistantBuffer buffer = new AssistantBuffer();
        for (int i = 0; i < items.size(); i++) {
            JSONObject item = items.getJSONObject(i);
            if (item == null) continue;
            String type = item.getString("type");
            switch (type == null ? "" : type) {
                case "message" -> {
                    String role = StrUtil.blankToDefault(item.getString("role"), "user");
                    if ("assistant".equals(role)) {
                        // 累积到 assistant 缓冲（content + 后续 function_call）
                        Object content = item.get("content");
                        if (content instanceof String text) {
                            buffer.appendText(text);
                        } else if (content instanceof JSONArray blocks) {
                            buffer.appendBlocks(blocks);
                        }
                    } else {
                        // 非 assistant：先 flush 缓冲，再输出该消息
                        buffer.flush(out);
                        Object content = item.get("content");
                        if (content instanceof String text) {
                            out.add(buildSimpleMessage(role, text));
                        } else if (content instanceof JSONArray blocks) {
                            out.add(buildMessageFromBlocks(role, blocks));
                        } else {
                            JSONObject m = new JSONObject();
                            m.put("role", role);
                            m.put("content", "");
                            out.add(m);
                        }
                    }
                }
                case "function_call" -> {
                    // 聚合到 assistant 缓冲的 tool_calls
                    // 注意：Responses 的 function_call 有两个 id 字段：id（item id，如 fc_xxx）与
                    // call_id（调用 id，如 call_xxx），function_call_output 通过 call_id 关联。
                    // Chat 协议要求 assistant.tool_calls[].id 与后续 tool 消息的 tool_call_id 一致，
                    // 因此这里必须用 call_id（缺省兑底 id），否则上游无法匹配工具调用。
                    buffer.appendToolCall(StrUtil.blankToDefault(item.getString("call_id"), item.getString("id")),
                            item.getJSONObject("function") == null ? item.getString("name")
                                    : item.getJSONObject("function").getString("name"),
                            item.getString("arguments"));
                }
                case "function_call_output" -> {
                    // flush assistant 缓冲，输出 role=tool 消息
                    // 上游/Codex 有时不回传 call_id，此时用最近一次 function_call 的 id 兑底，
                    // 保证与 assistant.tool_calls[].id 一致（Chat 协议要求二者匹配）
                    String callId = item.getString("call_id");
                    if (StrUtil.isBlank(callId)) {
                        callId = buffer.lastToolCallId;
                    }
                    buffer.flush(out);
                    JSONObject toolMsg = new JSONObject();
                    toolMsg.put("role", "tool");
                    toolMsg.put("tool_call_id", StrUtil.nullToEmpty(callId));
                    Object output = item.get("output");
                    toolMsg.put("content", output == null ? "" : output.toString());
                    out.add(toolMsg);
                }
                case "reasoning" -> {
                    // 推理过程：Chat 无对应，忽略
                    log.debug("忽略 Responses reasoning input item");
                }
                default -> throw new BadRequestException("不支持的 input item 类型: " + type);
            }
        }
        buffer.flush(out);
    }

    /**
     * 把 content blocks（input_text/input_image/output_text）转成 OpenAI Chat content
     *
     * @param role   消息角色
     * @param blocks content blocks 数组
     * @return 消息 JSON 对象
     */
    private JSONObject buildMessageFromBlocks(String role, JSONArray blocks) {
        JSONArray content = new JSONArray();
        for (int i = 0; i < blocks.size(); i++) {
            JSONObject block = blocks.getJSONObject(i);
            if (block == null) continue;
            String type = block.getString("type");
            switch (type == null ? "" : type) {
                case "input_text", "output_text" -> {
                    JSONObject t = new JSONObject();
                    t.put("type", "text");
                    t.put("text", StrUtil.nullToEmpty(block.getString("text")));
                    content.add(t);
                }
                case "input_image" -> content.add(convertInputImage(block));
                case "output_image" -> content.add(convertInputImage(block));
                default -> throw new BadRequestException("不支持的 input content block 类型: " + type);
            }
        }
        JSONObject msg = new JSONObject();
        msg.put("role", role);
        msg.put("content", content);
        return msg;
    }

    /**
     * Responses input_image → OpenAI image_url
     * <pre>
     * Responses: {"type":"input_image","image_url":"data:..."} 或 {"type":"input_image","image_url":{"url":"..."}}
     * OpenAI:    {"type":"image_url","image_url":{"url":"..."}}
     * </pre>
     */
    private JSONObject convertInputImage(JSONObject block) {
        Object imageUrl = block.get("image_url");
        String url;
        if (imageUrl instanceof String s) {
            url = s;
        } else if (imageUrl instanceof JSONObject obj) {
            url = obj.getString("url");
        } else {
            throw new BadRequestException("input_image 缺少 image_url 字段");
        }
        JSONObject out = new JSONObject();
        out.put("type", "image_url");
        JSONObject iu = new JSONObject();
        iu.put("url", StrUtil.nullToEmpty(url));
        out.put("image_url", iu);
        return out;
    }

    /**
     * Responses text.format → Chat response_format
     * <p>
     * json_schema：Responses 为扁平结构（name/schema/strict 与 type 同级），Chat 需要
     * 嵌套在 json_schema 键下；json_object：两协议结构一致，直接透传；text：Chat 默认
     * 即为文本输出，无需携带（部分上游不识别该类型，忽略之）。
     * </p>
     */
    private JSONObject toChatResponseFormat(JSONObject format) {
        String type = format.getString("type");
        if ("json_schema".equals(type) && !format.containsKey("json_schema")) {
            JSONObject chatFormat = new JSONObject();
            chatFormat.put("type", "json_schema");
            JSONObject schema = new JSONObject();
            copyField(format, schema, "name");
            copyField(format, schema, "description");
            copyField(format, schema, "schema");
            copyField(format, schema, "strict");
            chatFormat.put("json_schema", schema);
            return chatFormat;
        }
        if ("text".equals(type)) {
            return null;
        }
        return format;
    }

    /**
     * Responses function tool → Chat function tool
     * <p>
     * Responses 的 function tool 是扁平结构（name/description/parameters/strict 与 type
     * 同级），Chat API 需要嵌套在 function 键下。若客户端已给嵌套格式则直接复用；
     * 无参工具可能缺 parameters，Chat 上游严格要求该字段，兜底补全为
     * {"type":"object","properties":{}}。
     * </p>
     */
    private JSONObject toChatFunctionTool(JSONObject tool) {
        JSONObject chatTool = new JSONObject();
        chatTool.put("type", "function");
        JSONObject fn = tool.getJSONObject("function");
        if (fn == null) {
            fn = new JSONObject();
            copyField(tool, fn, "name");
            copyField(tool, fn, "description");
            copyField(tool, fn, "parameters");
            copyField(tool, fn, "strict");
        }
        if (!fn.containsKey("parameters")) {
            JSONObject emptyParams = new JSONObject();
            emptyParams.put("type", "object");
            emptyParams.put("properties", new JSONObject());
            fn.put("parameters", emptyParams);
        }
        chatTool.put("function", fn);
        return chatTool;
    }

    /**
     * Responses tool_choice → Chat tool_choice
     * <p>
     * 字符串（auto/none/required）两协议一致，原样返回；
     * 对象且为扁平 function 引用（name 与 type 同级）时，重排为 Chat 的
     * {"type":"function","function":{"name":...}} 嵌套结构；已嵌套则原样返回。
     * </p>
     */
    private Object toChatToolChoice(Object toolChoice) {
        if (toolChoice instanceof JSONObject obj
                && "function".equals(obj.getString("type"))
                && obj.containsKey("name")
                && !obj.containsKey("function")) {
            JSONObject chatChoice = new JSONObject();
            chatChoice.put("type", "function");
            JSONObject fn = new JSONObject();
            copyField(obj, fn, "name");
            chatChoice.put("function", fn);
            return chatChoice;
        }
        return toolChoice;
    }

    /**
     * 字段拷贝：源对象存在该键时复制到目标对象
     *
     * @param from 源对象
     * @param to   目标对象
     * @param key  字段名
     */
    private void copyField(JSONObject from, JSONObject to, String key) {
        if (from.containsKey(key)) {
            to.put(key, from.get(key));
        }
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

    /** assistant 消息聚合缓冲（一个回合的 message + function_call 合并为一条 assistant 消息） */
    private static class AssistantBuffer {
        final StringBuilder text = new StringBuilder();
        final JSONArray toolCalls = new JSONArray();
        boolean hasText;
        boolean hasToolCalls;
        /** 最近一次 function_call 的 id（用于 function_call_output 兑底链接） */
        String lastToolCallId;

        /**
         * 追加文本片段到缓冲
         *
         * @param t 文本片段
         */
        void appendText(String t) {
            text.append(t);
            hasText = true;
        }

        /**
         * 追加 content blocks 中的文本部分到缓冲
         *
         * @param blocks content blocks 数组
         */
        void appendBlocks(JSONArray blocks) {
            for (int i = 0; i < blocks.size(); i++) {
                JSONObject b = blocks.getJSONObject(i);
                if (b != null && ("input_text".equals(b.getString("type"))
                        || "output_text".equals(b.getString("type")))) {
                    text.append(StrUtil.nullToEmpty(b.getString("text")));
                    hasText = true;
                }
            }
        }

        /**
         * 追加一个工具调用到缓冲
         *
         * @param id        工具调用 ID
         * @param name      工具名称
         * @param arguments 参数 JSON 字符串
         */
        void appendToolCall(String id, String name, String arguments) {
            JSONObject tc = new JSONObject();
            tc.put("id", id);
            tc.put("type", "function");
            JSONObject fn = new JSONObject();
            fn.put("name", name);
            fn.put("arguments", StrUtil.nullToEmpty(arguments));
            tc.put("function", fn);
            toolCalls.add(tc);
            hasToolCalls = true;
            lastToolCallId = id;
        }

        /**
         * 将缓冲内容 flush 为一条 assistant 消息输出；无内容时跳过
         *
         * @param out 目标消息数组
         */
        void flush(JSONArray out) {
            if (!hasText && !hasToolCalls) return;
            JSONObject msg = new JSONObject();
            msg.put("role", "assistant");
            msg.put("content", hasText ? text.toString() : null);
            if (hasToolCalls) {
                // 拷贝副本：msg 持有引用，后续 clear 不能影响已输出的数组
                msg.put("tool_calls", new JSONArray(toolCalls));
            }
            out.add(msg);
            text.setLength(0);
            toolCalls.clear();
            hasText = false;
            hasToolCalls = false;
        }
    }

    // ==================== 出站（非流式）：OpenAI Chat → OpenAI Responses ====================

    /**
     * 出站转换（非流式）：OpenAI Chat 响应 → OpenAI Responses 响应
     *
     * @param chatJson 上游 OpenAI Chat 响应 JSON
     * @return OpenAI Responses 响应 JSON
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

        JSONObject responses = new JSONObject();
        responses.put("id", "resp_" + UUID.randomUUID().toString().replace("-", ""));
        responses.put("object", "response");
        responses.put("created_at", resp.getLongValue("created", Instant.now().getEpochSecond()));
        responses.put("model", resp.getString("model"));
        responses.put("status", mapFinishReasonToStatus(choice == null ? null : choice.getString("finish_reason")));

        // output 数组：message item（output_text）+ function_call items
        JSONArray output = new JSONArray();
        String text = message == null ? null : message.getString("content");
        if (StrUtil.isNotBlank(text)) {
            JSONObject msgItem = new JSONObject();
            msgItem.put("type", "message");
            msgItem.put("id", "msg_" + UUID.randomUUID().toString().replace("-", ""));
            msgItem.put("status", "completed");
            msgItem.put("role", "assistant");
            JSONArray content = new JSONArray();
            JSONObject textPart = new JSONObject();
            textPart.put("type", "output_text");
            textPart.put("text", text);
            textPart.put("annotations", new JSONArray());
            content.add(textPart);
            msgItem.put("content", content);
            output.add(msgItem);
        }
        JSONArray toolCalls = message == null ? null : message.getJSONArray("tool_calls");
        if (toolCalls != null) {
            for (int i = 0; i < toolCalls.size(); i++) {
                JSONObject tc = toolCalls.getJSONObject(i);
                if (tc == null) continue;
                JSONObject fc = new JSONObject();
                fc.put("type", "function_call");
                fc.put("id", "fc_" + UUID.randomUUID().toString().replace("-", ""));
                fc.put("call_id", tc.getString("id"));
                JSONObject fn = tc.getJSONObject("function");
                fc.put("name", fn == null ? null : fn.getString("name"));
                fc.put("arguments", fn == null ? "" : StrUtil.nullToEmpty(fn.getString("arguments")));
                fc.put("status", "completed");
                output.add(fc);
            }
        }
        responses.put("output", output);

        // usage：prompt_tokens → input_tokens，completion_tokens → output_tokens
        JSONObject usage = resp.getJSONObject("usage");
        JSONObject rUsage = new JSONObject();
        if (usage != null) {
            int in = usage.getIntValue("prompt_tokens", 0);
            int out = usage.getIntValue("completion_tokens", 0);
            rUsage.put("input_tokens", in);
            rUsage.put("output_tokens", out);
            rUsage.put("total_tokens", usage.getIntValue("total_tokens", in + out));
        } else {
            rUsage.put("input_tokens", 0);
            rUsage.put("output_tokens", 0);
            rUsage.put("total_tokens", 0);
        }
        responses.put("usage", rUsage);
        return responses.toJSONString();
    }

    /**
     * OpenAI finish_reason → Responses status
     */
    private String mapFinishReasonToStatus(String finishReason) {
        if (finishReason == null) return "completed";
        return switch (finishReason) {
            case "stop", "tool_calls", "function_call" -> "completed";
            case "length" -> "incomplete";
            case "content_filter" -> "incomplete";
            default -> "completed";
        };
    }

    // ==================== 出站（流式）：OpenAI Chat SSE → OpenAI Responses 事件流 ====================

    /**
     * 创建 Responses 流式事件转换器
     *
     * @param requestedModel 客户端请求的模型名（作响应 created 的兜底 model 字段）
     * @return Responses 流式转换器实例
     */
    @Override
    public StreamTransformer createStreamTransformer(String requestedModel) {
        return new ResponsesStreamTransformer(requestedModel);
    }

    /**
     * Responses 流式事件状态机
     * <p>
     * 上游 OpenAI Chat SSE 逐块喂入，映射为 Responses 事件流。事件序列：
     * response.created → response.output_item.added(message) → response.content_part.added →
     * response.output_text.delta×N → response.output_text.done → response.content_part.done →
     * response.output_item.done → ...（工具调用：response.output_item.added(function_call) →
     * response.function_call_arguments.delta×N → response.function_call_arguments.done →
     * response.output_item.done）→ response.completed。
     * </p>
     * <p>
     * output_index 为每个 output item 的序号（全局递增），content_index 为 message item 内
     * content part 序号（通常 0）。response.created 与 response.completed 的 response.id 一致。
     * usage 注入：response.completed 的 response.usage 取末尾携带 usage 的块。
     * </p>
     */
    private class ResponsesStreamTransformer implements StreamTransformer {

        private final String fallbackModel;

        /** 是否已发 response.created */
        private boolean responseStarted;
        /** 自生成的 response id（created 与 completed 复用） */
        private String responseId;
        /** 上游返回的模型名（首个块捕获） */
        private String responseModel;
        /** 上游 created 时间戳 */
        private long createdAt;
        /** 首个携带 usage 的块 */
        private JSONObject firstUsage;
        /** 末尾携带 usage 的块 */
        private JSONObject lastUsage;
        /** 上游 finish_reason（流中捕获） */
        private String finishReason;
        /** 下一个可分配的 output item 序号 */
        private int nextOutputIndex;
        /** 当前打开的 output item（null 表示无） */
        private CurrentItem currentItem;
        /** OpenAI tool_calls 状态，key 为 OpenAI 的 tool_calls[].index */
        private final Map<Integer, ToolCallState> toolCallStates = new LinkedHashMap<>();
        /** 流结束标记 */
        private boolean finished;

        /**
         * 构造流式转换器
         *
         * @param fallbackModel 兜底模型名
         */
        ResponsesStreamTransformer(String fallbackModel) {
            this.fallbackModel = fallbackModel;
        }

        /**
         * 转换一个上游 SSE 数据块为 Responses 事件字节
         *
         * @param upstreamChunk 上游原始 SSE 数据块
         * @return Responses 事件字节；无可输出内容时返回空数组
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

                if (responseModel == null) responseModel = obj.getString("model");
                if (createdAt == 0) createdAt = obj.getLongValue("created", 0);
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
                if (StrUtil.isNotBlank(content)) {
                    ensureResponseStarted(out);
                    switchToMessageItem(out);
                    appendEvent(out, "response.output_text.delta",
                            buildOutputTextDelta(currentItem.outputIndex, content));
                    currentItem.accumulatedText.append(content);
                }

                // ---- 工具调用增量 ----
                JSONArray toolCalls = delta.getJSONArray("tool_calls");
                if (toolCalls != null && !toolCalls.isEmpty()) {
                    for (int i = 0; i < toolCalls.size(); i++) {
                        JSONObject tc = toolCalls.getJSONObject(i);
                        if (tc == null) continue;
                        int openaiIdx = tc.getIntValue("index", 0);
                        ToolCallState state = toolCallStates.computeIfAbsent(openaiIdx, k -> new ToolCallState());
                        // 注意：部分上游（如商汤 DeepSeek 兼容）在后续 arguments 增量块里会把
                        // id/name 发成空串 ""，不能用空串覆盖已在首块捕获的正确值，故用 isNotBlank 守卫
                        String id = tc.getString("id");
                        if (StrUtil.isNotBlank(id)) state.callId = id;
                        JSONObject fn = tc.getJSONObject("function");
                        if (fn != null) {
                            String name = fn.getString("name");
                            if (StrUtil.isNotBlank(name)) state.name = name;
                        }
                        if (!state.started && state.callId != null && state.name != null) {
                            ensureResponseStarted(out);
                            switchToFunctionCallItem(out, openaiIdx);
                            state.started = true;
                            state.outputIndex = currentItem.outputIndex;
                        }
                        String arguments = fn == null ? null : fn.getString("arguments");
                        if (StrUtil.isNotBlank(arguments) && state.started) {
                            appendEvent(out, "response.function_call_arguments.delta",
                                    buildFunctionCallArgumentsDelta(state.outputIndex, arguments));
                            state.accumulatedArguments.append(arguments);
                        }
                    }
                }
            }
            return out.toString().getBytes(StandardCharsets.UTF_8);
        }

        /**
         * 上游流结束：补发收尾事件（response.completed）
         *
         * @return 收尾事件字节；已结束时返回空数组
         */
        @Override
        public byte[] finish() {
            if (finished) return new byte[0];
            finished = true;
            StringBuilder out = new StringBuilder();
            ensureResponseStarted(out);
            closeCurrentItem(out);
            appendEvent(out, "response.completed", buildCompletedResponse());
            return out.toString().getBytes(StandardCharsets.UTF_8);
        }

        // ---------- 状态机辅助 ----------

        /**
         * 确保已发送 response.created 事件（幂等）
         *
         * @param out 事件输出构建器
         */
        private void ensureResponseStarted(StringBuilder out) {
            if (responseStarted) return;
            responseStarted = true;
            if (StrUtil.isBlank(responseId)) {
                responseId = "resp_" + UUID.randomUUID().toString().replace("-", "");
            }
            long created = createdAt == 0 ? Instant.now().getEpochSecond() : createdAt;
            JSONObject response = buildBaseResponse("in_progress", new JSONArray());
            JSONObject eventData = new JSONObject();
            eventData.put("type", "response.created");
            eventData.put("response", response);
            appendEvent(out, "response.created", eventData);
        }

        /**
         * 切换到 message output item：若当前不是 message item，先关闭当前 item。
         */
        private void switchToMessageItem(StringBuilder out) {
            if (currentItem != null && "message".equals(currentItem.type)) return;
            closeCurrentItem(out);
            int idx = nextOutputIndex++;
            // output_item.added
            JSONObject item = new JSONObject();
            item.put("type", "message");
            item.put("id", "msg_" + UUID.randomUUID().toString().replace("-", ""));
            item.put("status", "in_progress");
            item.put("role", "assistant");
            item.put("content", new JSONArray());
            JSONObject itemEventData = new JSONObject();
            itemEventData.put("type", "response.output_item.added");
            itemEventData.put("output_index", idx);
            itemEventData.put("item", item);
            appendEvent(out, "response.output_item.added", itemEventData);

            // content_part.added
            JSONObject part = new JSONObject();
            part.put("type", "output_text");
            part.put("text", "");
            part.put("annotations", new JSONArray());
            JSONObject partEventData = new JSONObject();
            partEventData.put("type", "response.content_part.added");
            partEventData.put("output_index", idx);
            partEventData.put("content_index", 0);
            partEventData.put("part", part);
            appendEvent(out, "response.content_part.added", partEventData);

            currentItem = new CurrentItem("message", idx);
        }

        /**
         * 切换到 function_call output item：先关闭当前 item。
         */
        private void switchToFunctionCallItem(StringBuilder out, int openaiIdx) {
            closeCurrentItem(out);
            int idx = nextOutputIndex++;
            ToolCallState state = toolCallStates.get(openaiIdx);
            JSONObject item = new JSONObject();
            item.put("type", "function_call");
            item.put("id", "fc_" + UUID.randomUUID().toString().replace("-", ""));
            item.put("call_id", state.callId);
            item.put("name", state.name);
            item.put("arguments", "");
            item.put("status", "in_progress");
            JSONObject itemEventData = new JSONObject();
            itemEventData.put("type", "response.output_item.added");
            itemEventData.put("output_index", idx);
            itemEventData.put("item", item);
            appendEvent(out, "response.output_item.added", itemEventData);
            state.itemId = item.getString("id");
            currentItem = new CurrentItem("function_call", idx);
            currentItem.openaiToolCallIndex = openaiIdx;
        }

        /**
         * 关闭当前 output item：补发 done 事件
         */
        private void closeCurrentItem(StringBuilder out) {
            if (currentItem == null) return;
            int idx = currentItem.outputIndex;
            if ("message".equals(currentItem.type)) {
                // output_text.done
                JSONObject textDone = new JSONObject();
                textDone.put("type", "response.output_text.done");
                textDone.put("output_index", idx);
                textDone.put("content_index", 0);
                textDone.put("text", currentItem.accumulatedText.toString());
                appendEvent(out, "response.output_text.done", textDone);
                // content_part.done
                JSONObject partDone = new JSONObject();
                partDone.put("type", "response.content_part.done");
                partDone.put("output_index", idx);
                partDone.put("content_index", 0);
                partDone.put("part", new JSONObject().fluentPut("type", "output_text")
                        .fluentPut("text", currentItem.accumulatedText.toString())
                        .fluentPut("annotations", new JSONArray()));
                appendEvent(out, "response.content_part.done", partDone);
                // output_item.done
                JSONObject item = new JSONObject();
                item.put("type", "message");
                item.put("id", "msg_" + currentItem.itemSuffix);
                item.put("status", "completed");
                item.put("role", "assistant");
                JSONArray content = new JSONArray();
                JSONObject textPart = new JSONObject();
                textPart.put("type", "output_text");
                textPart.put("text", currentItem.accumulatedText.toString());
                textPart.put("annotations", new JSONArray());
                content.add(textPart);
                item.put("content", content);
                appendEvent(out, "response.output_item.done",
                        buildOutputItemDone(idx, item));
            } else if ("function_call".equals(currentItem.type)) {
                ToolCallState state = toolCallStates.get(currentItem.openaiToolCallIndex);
                String args = state == null ? "" : state.accumulatedArguments.toString();
                // function_call_arguments.done
                JSONObject argsDone = new JSONObject();
                argsDone.put("type", "response.function_call_arguments.done");
                argsDone.put("output_index", idx);
                argsDone.put("arguments", args);
                appendEvent(out, "response.function_call_arguments.done", argsDone);
                // output_item.done
                JSONObject item = new JSONObject();
                item.put("type", "function_call");
                item.put("id", state == null ? "" : state.itemId);
                item.put("call_id", state == null ? "" : state.callId);
                item.put("name", state == null ? "" : state.name);
                item.put("arguments", args);
                item.put("status", "completed");
                appendEvent(out, "response.output_item.done",
                        buildOutputItemDone(idx, item));
            }
            currentItem = null;
        }

        /**
         * 构建 response.output_item.done 事件
         *
         * @param idx  output item 序号
         * @param item 完成的 item 对象
         * @return 事件数据对象
         */
        private JSONObject buildOutputItemDone(int idx, JSONObject item) {
            JSONObject eventData = new JSONObject();
            eventData.put("type", "response.output_item.done");
            eventData.put("output_index", idx);
            eventData.put("item", item);
            return eventData;
        }

        /**
         * 构建 response.output_text.delta 事件
         *
         * @param outputIndex output item 序号
         * @param delta       文本增量
         * @return 事件数据对象
         */
        private JSONObject buildOutputTextDelta(int outputIndex, String delta) {
            JSONObject eventData = new JSONObject();
            eventData.put("type", "response.output_text.delta");
            eventData.put("output_index", outputIndex);
            eventData.put("content_index", 0);
            eventData.put("delta", delta);
            return eventData;
        }

        /**
         * 构建 response.function_call_arguments.delta 事件
         *
         * @param outputIndex output item 序号
         * @param delta       参数 JSON 增量
         * @return 事件数据对象
         */
        private JSONObject buildFunctionCallArgumentsDelta(int outputIndex, String delta) {
            JSONObject eventData = new JSONObject();
            eventData.put("type", "response.function_call_arguments.delta");
            eventData.put("output_index", outputIndex);
            eventData.put("delta", delta);
            return eventData;
        }

        /**
         * 构建 response.completed 事件数据对象
         *
         * @return 事件数据对象
         */
        private JSONObject buildCompletedResponse() {
            JSONArray output = new JSONArray();
            // 简化：response.completed 的 output 为空数组，客户端用 delta 累积文本 + completed 拿 usage
            JSONObject response = buildBaseResponse(mapFinishReasonToStatus(finishReason), output);
            JSONObject usage = new JSONObject();
            if (lastUsage != null) {
                int in = lastUsage.getIntValue("prompt_tokens", 0);
                int out = lastUsage.getIntValue("completion_tokens", 0);
                usage.put("input_tokens", in);
                usage.put("output_tokens", out);
                usage.put("total_tokens", lastUsage.getIntValue("total_tokens", in + out));
            } else {
                usage.put("input_tokens", 0);
                usage.put("output_tokens", 0);
                usage.put("total_tokens", 0);
            }
            response.put("usage", usage);
            JSONObject eventData = new JSONObject();
            eventData.put("type", "response.completed");
            eventData.put("response", response);
            return eventData;
        }

        /**
         * 构建 Responses response 基础对象
         *
         * @param status 状态：in_progress / completed / incomplete
         * @param output output 数组
         * @return response 对象
         */
        private JSONObject buildBaseResponse(String status, JSONArray output) {
            JSONObject response = new JSONObject();
            response.put("id", responseId);
            response.put("object", "response");
            response.put("created_at", createdAt == 0 ? Instant.now().getEpochSecond() : createdAt);
            response.put("model", StrUtil.blankToDefault(responseModel, fallbackModel));
            response.put("status", status);
            response.put("output", output);
            return response;
        }

        /**
         * 输出一个 Responses 事件（event: <name>\ndata: <json>\n\n）
         *
         * @param out       事件输出构建器
         * @param eventName 事件名
         * @param data      事件数据对象
         */
        private void appendEvent(StringBuilder out, String eventName, JSONObject data) {
            out.append(EVENT_PREFIX).append(eventName).append('\n');
            out.append(DATA_PREFIX).append(data.toJSONString()).append('\n');
            out.append('\n');
        }
    }

    /** 当前打开的 output item */
    private static class CurrentItem {
        /** message / function_call */
        final String type;
        /** output item 序号 */
        final int outputIndex;
        /** 文本累积（message 类型） */
        final StringBuilder accumulatedText = new StringBuilder();
        /** item id 后缀（用于 done 时复用，message 类型占位） */
        String itemSuffix;
        /** OpenAI tool_calls index（function_call 类型） */
        int openaiToolCallIndex = -1;

        CurrentItem(String type, int outputIndex) {
            this.type = type;
            this.outputIndex = outputIndex;
            this.itemSuffix = UUID.randomUUID().toString().replace("-", "");
        }
    }

    /** OpenAI 单个 tool_call 的流式状态 */
    private static class ToolCallState {
        /** 调用 ID */
        String callId;
        /** 工具名称 */
        String name;
        /** 是否已发送 output_item.added */
        boolean started;
        /** 分配的 output item 序号 */
        int outputIndex;
        /** 生成的 fc_ 前缀 item id */
        String itemId;
        /** 参数 JSON 累积 */
        final StringBuilder accumulatedArguments = new StringBuilder();
    }
}
