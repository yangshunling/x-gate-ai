package com.xgateai.protocol;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.xgateai.exception.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AnthropicConverterTest Anthropic Messages ↔ OpenAI Chat 转换单元测试
 * <p>
 * 覆盖：入站请求转换（文本/图片/工具调用）、出站非流式响应转换、流式 SSE 事件状态机。
 * </p>
 */
class AnthropicConverterTest {

    private AnthropicConverter converter;

    @BeforeEach
    void setUp() {
        converter = new AnthropicConverter();
    }

    // ==================== 入站：Anthropic Messages → OpenAI Chat ====================

    @Test
    @DisplayName("入站：system + 文本消息 + 图片块 转 Chat 请求体")
    void toChatRequest_basic() {
        JSONObject body = new JSONObject();
        body.put("model", "claude-3-5-sonnet");
        body.put("max_tokens", 1024);
        body.put("system", "你是助手");
        body.put("temperature", 0.7);
        body.put("stream", false);
        body.put("stop_sequences", JSONArray.of("END"));

        JSONObject user = new JSONObject();
        user.put("role", "user");
        JSONArray content = new JSONArray();
        JSONObject textBlock = new JSONObject();
        textBlock.put("type", "text");
        textBlock.put("text", "你好");
        JSONObject imageBlock = new JSONObject();
        imageBlock.put("type", "image");
        JSONObject source = new JSONObject();
        source.put("type", "base64");
        source.put("media_type", "image/png");
        source.put("data", "aGVsbG8=");
        imageBlock.put("source", source);
        content.add(textBlock);
        content.add(imageBlock);
        user.put("content", content);

        body.put("messages", JSONArray.of(user));

        JSONObject chat = JSON.parseObject(converter.toChatRequest(body.toJSONString()));

        assertEquals("claude-3-5-sonnet", chat.getString("model"));
        assertEquals(1024, chat.getIntValue("max_tokens"));
        assertEquals(0.7, chat.getDoubleValue("temperature"));
        assertEquals("END", chat.getJSONArray("stop").getString(0));

        JSONArray messages = chat.getJSONArray("messages");
        assertEquals(2, messages.size());
        assertEquals("system", messages.getJSONObject(0).getString("role"));
        assertEquals("你是助手", messages.getJSONObject(0).getString("content"));
        JSONObject userMsg = messages.getJSONObject(1);
        assertEquals("user", userMsg.getString("role"));
        JSONArray userContent = userMsg.getJSONArray("content");
        assertEquals("text", userContent.getJSONObject(0).getString("type"));
        assertEquals("你好", userContent.getJSONObject(0).getString("text"));
        assertEquals("image_url", userContent.getJSONObject(1).getString("type"));
        assertEquals("data:image/png;base64,aGVsbG8=",
                userContent.getJSONObject(1).getJSONObject("image_url").getString("url"));
    }

    @Test
    @DisplayName("入站：纯字符串 content 直接映射")
    void toChatRequest_stringContent() {
        JSONObject body = new JSONObject();
        body.put("model", "m");
        body.put("max_tokens", 10);
        body.put("messages", JSONArray.of(buildUserMsg("hi")));

        JSONObject chat = JSON.parseObject(converter.toChatRequest(body.toJSONString()));
        JSONObject m = chat.getJSONArray("messages").getJSONObject(0);
        assertEquals("user", m.getString("role"));
        assertEquals("hi", m.getString("content"));
    }

    @Test
    @DisplayName("入站：缺少 max_tokens 抛 400")
    void toChatRequest_missingMaxTokens() {
        JSONObject body = new JSONObject();
        body.put("model", "m");
        body.put("messages", JSONArray.of(buildUserMsg("hi")));
        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> converter.toChatRequest(body.toJSONString()));
        assertTrue(ex.getMessage().contains("max_tokens"));
    }

    @Test
    @DisplayName("入站：tools 定义 + tool_choice 转换（input_schema→parameters）")
    void toChatRequest_withTools() {
        JSONObject body = new JSONObject();
        body.put("model", "m");
        body.put("max_tokens", 1024);
        body.put("messages", JSONArray.of(buildUserMsg("读文件")));
        // tools
        JSONObject tool = new JSONObject();
        tool.put("name", "read_file");
        tool.put("description", "读取文件");
        JSONObject inputSchema = new JSONObject();
        inputSchema.put("type", "object");
        JSONObject props = new JSONObject();
        props.put("path", new JSONObject().fluentPut("type", "string"));
        inputSchema.put("properties", props);
        inputSchema.put("required", JSONArray.of("path"));
        tool.put("input_schema", inputSchema);
        body.put("tools", JSONArray.of(tool));
        // tool_choice: auto
        body.put("tool_choice", new JSONObject().fluentPut("type", "auto"));

        JSONObject chat = JSON.parseObject(converter.toChatRequest(body.toJSONString()));

        JSONArray tools = chat.getJSONArray("tools");
        assertEquals(1, tools.size());
        JSONObject t = tools.getJSONObject(0);
        assertEquals("function", t.getString("type"));
        assertEquals("read_file", t.getJSONObject("function").getString("name"));
        assertEquals("读取文件", t.getJSONObject("function").getString("description"));
        assertEquals("object", t.getJSONObject("function").getJSONObject("parameters").getString("type"));
        assertEquals("auto", chat.getString("tool_choice"));
    }

    @Test
    @DisplayName("入站：tool_choice=any→required, tool=→{type:function,...}")
    void toChatRequest_toolChoiceVariants() {
        // any → required
        JSONObject body1 = new JSONObject();
        body1.put("model", "m");
        body1.put("max_tokens", 10);
        body1.put("messages", JSONArray.of(buildUserMsg("hi")));
        body1.put("tool_choice", new JSONObject().fluentPut("type", "any"));
        assertEquals("required",
                JSON.parseObject(converter.toChatRequest(body1.toJSONString())).getString("tool_choice"));

        // tool → {type:function, function:{name}}
        JSONObject body2 = new JSONObject();
        body2.put("model", "m");
        body2.put("max_tokens", 10);
        body2.put("messages", JSONArray.of(buildUserMsg("hi")));
        body2.put("tool_choice", new JSONObject().fluentPut("type", "tool").fluentPut("name", "read_file"));
        JSONObject tc = JSON.parseObject(converter.toChatRequest(body2.toJSONString())).getJSONObject("tool_choice");
        assertEquals("function", tc.getString("type"));
        assertEquals("read_file", tc.getJSONObject("function").getString("name"));
    }

    @Test
    @DisplayName("入站：assistant tool_use → tool_calls；user tool_result → 独立 tool 消息（拆消息）")
    void toChatRequest_withToolUseAndResult() {
        JSONObject body = new JSONObject();
        body.put("model", "m");
        body.put("max_tokens", 1024);

        JSONArray messages = new JSONArray();
        // user: 读文件
        messages.add(buildUserMsg("读一下 /tmp/a.txt"));
        // assistant: tool_use 块
        JSONObject assistant = new JSONObject();
        assistant.put("role", "assistant");
        JSONArray aContent = new JSONArray();
        JSONObject toolUse = new JSONObject();
        toolUse.put("type", "tool_use");
        toolUse.put("id", "toolu_01");
        toolUse.put("name", "read_file");
        JSONObject input = new JSONObject();
        input.put("path", "/tmp/a.txt");
        toolUse.put("input", input);
        aContent.add(toolUse);
        assistant.put("content", aContent);
        messages.add(assistant);
        // user: tool_result + 新指令（混合块，验证拆分）
        JSONObject user2 = new JSONObject();
        user2.put("role", "user");
        JSONArray u2Content = new JSONArray();
        JSONObject toolResult = new JSONObject();
        toolResult.put("type", "tool_result");
        toolResult.put("tool_use_id", "toolu_01");
        toolResult.put("content", "文件内容 abc");
        JSONObject newText = new JSONObject();
        newText.put("type", "text");
        newText.put("text", "继续");
        u2Content.add(toolResult);
        u2Content.add(newText);
        user2.put("content", u2Content);
        messages.add(user2);
        body.put("messages", messages);

        JSONObject chat = JSON.parseObject(converter.toChatRequest(body.toJSONString()));
        JSONArray out = chat.getJSONArray("messages");
        // 预期顺序：user(text) → assistant(tool_calls) → tool(tool_result) → user(text)
        assertEquals(4, out.size());

        assertEquals("user", out.getJSONObject(0).getString("role"));
        assertEquals("assistant", out.getJSONObject(1).getString("role"));
        JSONArray toolCalls = out.getJSONObject(1).getJSONArray("tool_calls");
        assertEquals(1, toolCalls.size());
        JSONObject tc = toolCalls.getJSONObject(0);
        assertEquals("toolu_01", tc.getString("id"));
        assertEquals("function", tc.getString("type"));
        assertEquals("read_file", tc.getJSONObject("function").getString("name"));
        // input 对象 → arguments JSON 字符串
        JSONObject args = JSON.parseObject(tc.getJSONObject("function").getString("arguments"));
        assertEquals("/tmp/a.txt", args.getString("path"));
        assertNull(out.getJSONObject(1).get("content")); // 纯 tool_use 时 content=null

        // tool_result → 独立 tool 消息
        JSONObject toolMsg = out.getJSONObject(2);
        assertEquals("tool", toolMsg.getString("role"));
        assertEquals("toolu_01", toolMsg.getString("tool_call_id"));
        assertEquals("文件内容 abc", toolMsg.getString("content"));
        // 剩余 text → user 消息
        JSONObject userMsg = out.getJSONObject(3);
        assertEquals("user", userMsg.getString("role"));
        assertEquals("继续", userMsg.getJSONArray("content").getJSONObject(0).getString("text"));
    }

    @Test
    @DisplayName("入站：thinking 块被忽略（不报错）")
    void toChatRequest_thinkingIgnored() {
        JSONObject body = new JSONObject();
        body.put("model", "m");
        body.put("max_tokens", 10);
        JSONObject assistant = new JSONObject();
        assistant.put("role", "assistant");
        JSONArray content = new JSONArray();
        JSONObject thinking = new JSONObject();
        thinking.put("type", "thinking");
        thinking.put("thinking", "思考...");
        JSONObject text = new JSONObject();
        text.put("type", "text");
        text.put("text", "答案");
        content.add(thinking);
        content.add(text);
        assistant.put("content", content);
        body.put("messages", JSONArray.of(buildUserMsg("q"), assistant));

        JSONObject chat = JSON.parseObject(converter.toChatRequest(body.toJSONString()));
        JSONObject a = chat.getJSONArray("messages").getJSONObject(1);
        assertEquals("assistant", a.getString("role"));
        assertEquals("答案", a.getString("content"));
        assertNull(a.get("tool_calls"));
    }

    // ==================== 出站（非流式）：OpenAI Chat → Anthropic Messages ====================

    @Test
    @DisplayName("出站：Chat 响应转 Anthropic message（usage/stop_reason 映射）")
    void fromChatResponse_basic() {
        JSONObject resp = new JSONObject();
        resp.put("id", "chatcmpl-abc123");
        resp.put("model", "deepseek-chat");
        JSONObject choice = new JSONObject();
        choice.put("index", 0);
        JSONObject message = new JSONObject();
        message.put("role", "assistant");
        message.put("content", "你好，我是助手");
        choice.put("message", message);
        choice.put("finish_reason", "stop");
        resp.put("choices", JSONArray.of(choice));
        JSONObject usage = new JSONObject();
        usage.put("prompt_tokens", 12);
        usage.put("completion_tokens", 8);
        usage.put("total_tokens", 20);
        resp.put("usage", usage);

        JSONObject anthropic = JSON.parseObject(converter.fromChatResponse(resp.toJSONString()));

        assertEquals("message", anthropic.getString("type"));
        assertEquals("assistant", anthropic.getString("role"));
        assertEquals("chatcmpl-abc123", anthropic.getString("id"));
        assertEquals("deepseek-chat", anthropic.getString("model"));
        assertEquals("end_turn", anthropic.getString("stop_reason"));
        JSONArray content = anthropic.getJSONArray("content");
        assertEquals(1, content.size());
        assertEquals("text", content.getJSONObject(0).getString("type"));
        assertEquals("你好，我是助手", content.getJSONObject(0).getString("text"));
        JSONObject u = anthropic.getJSONObject("usage");
        assertEquals(12, u.getIntValue("input_tokens"));
        assertEquals(8, u.getIntValue("output_tokens"));
    }

    @Test
    @DisplayName("出站：Chat 响应含 tool_calls → tool_use content blocks")
    void fromChatResponse_withToolCalls() {
        JSONObject resp = new JSONObject();
        resp.put("id", "chatcmpl-1");
        resp.put("model", "m");
        JSONObject choice = new JSONObject();
        choice.put("index", 0);
        JSONObject message = new JSONObject();
        message.put("role", "assistant");
        message.put("content", "好的");
        JSONArray toolCalls = new JSONArray();
        JSONObject tc = new JSONObject();
        tc.put("id", "call_01");
        tc.put("type", "function");
        JSONObject fn = new JSONObject();
        fn.put("name", "read_file");
        fn.put("arguments", "{\"path\":\"/tmp/a.txt\"}");
        tc.put("function", fn);
        toolCalls.add(tc);
        message.put("tool_calls", toolCalls);
        choice.put("message", message);
        choice.put("finish_reason", "tool_calls");
        resp.put("choices", JSONArray.of(choice));

        JSONObject anthropic = JSON.parseObject(converter.fromChatResponse(resp.toJSONString()));
        assertEquals("tool_use", anthropic.getString("stop_reason"));
        JSONArray content = anthropic.getJSONArray("content");
        assertEquals(2, content.size());
        // text 在前
        assertEquals("text", content.getJSONObject(0).getString("type"));
        assertEquals("好的", content.getJSONObject(0).getString("text"));
        // tool_use 在后
        JSONObject tu = content.getJSONObject(1);
        assertEquals("tool_use", tu.getString("type"));
        assertEquals("call_01", tu.getString("id"));
        assertEquals("read_file", tu.getString("name"));
        // arguments JSON 字符串 → input 对象
        JSONObject input = tu.getJSONObject("input");
        assertEquals("/tmp/a.txt", input.getString("path"));
    }

    @Test
    @DisplayName("出站：finish_reason=length 映射为 max_tokens")
    void fromChatResponse_lengthMapping() {
        JSONObject resp = new JSONObject();
        resp.put("id", "id1");
        resp.put("model", "m");
        JSONObject choice = new JSONObject();
        choice.put("index", 0);
        choice.put("message", new JSONObject());
        choice.put("finish_reason", "length");
        resp.put("choices", JSONArray.of(choice));

        JSONObject anthropic = JSON.parseObject(converter.fromChatResponse(resp.toJSONString()));
        assertEquals("max_tokens", anthropic.getString("stop_reason"));
    }

    // ==================== 出站（流式）：OpenAI Chat SSE → Anthropic 事件流 ====================

    @Test
    @DisplayName("流式：完整文本事件序列 message_start → delta×N → stop 收尾")
    void stream_basicSequence() {
        StreamTransformer transformer = converter.createStreamTransformer("claude-3-5-sonnet");

        JSONObject chunk1 = new JSONObject();
        chunk1.put("id", "chatcmpl-xyz");
        chunk1.put("model", "deepseek-chat");
        JSONObject delta1 = new JSONObject();
        delta1.put("role", "assistant");
        delta1.put("content", "你好");
        JSONObject choice1 = new JSONObject();
        choice1.put("index", 0);
        choice1.put("delta", delta1);
        chunk1.put("choices", JSONArray.of(choice1));

        JSONObject chunk2 = new JSONObject();
        JSONObject delta2 = new JSONObject();
        delta2.put("content", "世界");
        JSONObject choice2 = new JSONObject();
        choice2.put("index", 0);
        choice2.put("delta", delta2);
        chunk2.put("choices", JSONArray.of(choice2));

        JSONObject chunk3 = new JSONObject();
        JSONObject delta3 = new JSONObject();
        delta3.put("content", "");
        JSONObject choice3 = new JSONObject();
        choice3.put("index", 0);
        choice3.put("delta", delta3);
        choice3.put("finish_reason", "stop");
        chunk3.put("choices", JSONArray.of(choice3));
        JSONObject usage = new JSONObject();
        usage.put("prompt_tokens", 5);
        usage.put("completion_tokens", 6);
        usage.put("total_tokens", 11);
        chunk3.put("usage", usage);

        StringBuilder stream = new StringBuilder();
        stream.append(new String(transformer.transform(
                ("data: " + chunk1.toJSONString() + "\n\n").getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8));
        stream.append(new String(transformer.transform(
                ("data: " + chunk2.toJSONString() + "\n\n").getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8));
        stream.append(new String(transformer.transform(
                ("data: " + chunk3.toJSONString() + "\n\n").getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8));
        stream.append(new String(transformer.finish(), StandardCharsets.UTF_8));

        String out = stream.toString();
        assertTrue(out.contains("event: message_start"));
        assertTrue(out.contains("event: content_block_start"));
        assertTrue(out.contains("event: content_block_delta"));
        assertTrue(out.contains("event: content_block_stop"));
        assertTrue(out.contains("event: message_delta"));
        assertTrue(out.contains("event: message_stop"));

        String startData = extractEventData(out, "message_start");
        JSONObject start = JSON.parseObject(startData);
        assertEquals("chatcmpl-xyz", start.getJSONObject("message").getString("id"));
        assertEquals("deepseek-chat", start.getJSONObject("message").getString("model"));

        String deltas = extractAllDeltaText(out);
        assertTrue(deltas.contains("你好"));
        assertTrue(deltas.contains("世界"));

        String deltaData = extractEventData(out, "message_delta");
        JSONObject md = JSON.parseObject(deltaData);
        assertEquals("end_turn", md.getJSONObject("delta").getString("stop_reason"));
        assertEquals(6, md.getJSONObject("usage").getIntValue("output_tokens"));

        assertEquals(countOccurrences(out, "event: content_block_start"),
                countOccurrences(out, "event: content_block_stop"));
    }

    @Test
    @DisplayName("流式：上游仅返回 role（无文本）时 finish 仍补全完整事件")
    void stream_emptyContent() {
        StreamTransformer transformer = converter.createStreamTransformer("claude-3-5-sonnet");
        JSONObject chunk = new JSONObject();
        chunk.put("id", "id-empty");
        JSONObject delta = new JSONObject();
        delta.put("role", "assistant");
        delta.put("content", "");
        JSONObject choice = new JSONObject();
        choice.put("index", 0);
        choice.put("delta", delta);
        chunk.put("choices", JSONArray.of(choice));

        String out = new String(transformer.transform(
                ("data: " + chunk.toJSONString() + "\n\n").getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8)
                + new String(transformer.finish(), StandardCharsets.UTF_8);

        assertTrue(out.contains("event: message_start"));
        assertTrue(out.contains("event: content_block_start"));
        assertTrue(out.contains("event: content_block_stop"));
        assertTrue(out.contains("event: message_stop"));
        assertEquals(countOccurrences(out, "event: content_block_start"),
                countOccurrences(out, "event: content_block_stop"));
    }

    @Test
    @DisplayName("流式：上游 tool_calls 分片 → Anthropic input_json_delta（含 content_block 开闭）")
    void stream_withToolCalls() {
        StreamTransformer transformer = converter.createStreamTransformer("m");

        // 首片：带 id + name + 空 arguments
        JSONObject chunk1 = new JSONObject();
        JSONObject delta1 = new JSONObject();
        delta1.put("role", "assistant");
        JSONObject tc1 = new JSONObject();
        tc1.put("index", 0);
        tc1.put("id", "call_01");
        tc1.put("type", "function");
        JSONObject fn1 = new JSONObject();
        fn1.put("name", "read_file");
        fn1.put("arguments", "");
        tc1.put("function", fn1);
        delta1.put("tool_calls", JSONArray.of(tc1));
        JSONObject choice1 = new JSONObject();
        choice1.put("index", 0);
        choice1.put("delta", delta1);
        chunk1.put("choices", JSONArray.of(choice1));

        // 后续：arguments 分片
        JSONObject chunk2 = new JSONObject();
        JSONObject delta2 = new JSONObject();
        JSONObject tc2 = new JSONObject();
        tc2.put("index", 0);
        JSONObject fn2 = new JSONObject();
        fn2.put("arguments", "{\"path\":\"/tmp/a.txt\"}");
        tc2.put("function", fn2);
        delta2.put("tool_calls", JSONArray.of(tc2));
        JSONObject choice2 = new JSONObject();
        choice2.put("index", 0);
        choice2.put("delta", delta2);
        chunk2.put("choices", JSONArray.of(choice2));

        // 结束块
        JSONObject chunk3 = new JSONObject();
        JSONObject choice3 = new JSONObject();
        choice3.put("index", 0);
        choice3.put("delta", new JSONObject());
        choice3.put("finish_reason", "tool_calls");
        chunk3.put("choices", JSONArray.of(choice3));
        chunk3.put("usage", new JSONObject().fluentPut("prompt_tokens", 5).fluentPut("completion_tokens", 3));

        StringBuilder stream = new StringBuilder();
        stream.append(new String(transformer.transform(
                ("data: " + chunk1.toJSONString() + "\n\n").getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8));
        stream.append(new String(transformer.transform(
                ("data: " + chunk2.toJSONString() + "\n\n").getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8));
        stream.append(new String(transformer.transform(
                ("data: " + chunk3.toJSONString() + "\n\n").getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8));
        stream.append(new String(transformer.finish(), StandardCharsets.UTF_8));

        String out = stream.toString();

        // content_block_start 带 tool_use 元信息
        String startData = extractEventData(out, "content_block_start");
        JSONObject start = JSON.parseObject(startData);
        assertEquals("tool_use", start.getJSONObject("content_block").getString("type"));
        assertEquals("call_01", start.getJSONObject("content_block").getString("id"));
        assertEquals("read_file", start.getJSONObject("content_block").getString("name"));

        // input_json_delta 携带分片
        String partial = extractAllInputJsonDelta(out);
        assertTrue(partial.contains("{\"path\":\"/tmp/a.txt\"}"));

        // stop_reason = tool_use
        String deltaData = extractEventData(out, "message_delta");
        JSONObject md = JSON.parseObject(deltaData);
        assertEquals("tool_use", md.getJSONObject("delta").getString("stop_reason"));
        assertEquals(3, md.getJSONObject("usage").getIntValue("output_tokens"));

        // 开闭配对
        assertEquals(countOccurrences(out, "event: content_block_start"),
                countOccurrences(out, "event: content_block_stop"));
    }

    @Test
    @DisplayName("流式：先文本后工具调用，验证 content_block 切换（text block 先 stop 再开 tool_use block）")
    void stream_textThenToolCalls() {
        StreamTransformer transformer = converter.createStreamTransformer("m");

        // 文本增量
        JSONObject chunk1 = new JSONObject();
        JSONObject delta1 = new JSONObject();
        delta1.put("content", "开始");
        JSONObject choice1 = new JSONObject();
        choice1.put("index", 0);
        choice1.put("delta", delta1);
        chunk1.put("choices", JSONArray.of(choice1));

        // 工具调用首片
        JSONObject chunk2 = new JSONObject();
        JSONObject delta2 = new JSONObject();
        JSONObject tc2 = new JSONObject();
        tc2.put("index", 0);
        tc2.put("id", "call_1");
        JSONObject fn2 = new JSONObject();
        fn2.put("name", "search");
        fn2.put("arguments", "{\"q\":\"a\"}");
        tc2.put("function", fn2);
        delta2.put("tool_calls", JSONArray.of(tc2));
        JSONObject choice2 = new JSONObject();
        choice2.put("index", 0);
        choice2.put("delta", delta2);
        chunk2.put("choices", JSONArray.of(choice2));
        chunk2.put("usage", new JSONObject().fluentPut("completion_tokens", 4));

        String out = new String(transformer.transform(
                ("data: " + chunk1.toJSONString() + "\n\n").getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8)
                + new String(transformer.transform(
                        ("data: " + chunk2.toJSONString() + "\n\n").getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8)
                + new String(transformer.finish(), StandardCharsets.UTF_8);

        // 两个 content_block_start（text + tool_use），两个 stop
        assertEquals(2, countOccurrences(out, "event: content_block_start"));
        assertEquals(2, countOccurrences(out, "event: content_block_stop"));
        // 顺序：text_start → text_delta → text_stop → tool_use_start → input_json_delta → tool_use_stop
        int textStart = out.indexOf("event: content_block_start");
        int textDelta = out.indexOf("text_delta");
        int textStop = out.indexOf("event: content_block_stop");
        int toolStart = out.indexOf("event: content_block_start", textStop);
        assertTrue(textStart < textDelta);
        assertTrue(textDelta < textStop);
        assertTrue(textStop < toolStart);
        // 第二个 start 是 tool_use
        String toolStartData = extractEventData(out.substring(toolStart), "content_block_start");
        assertEquals("tool_use", JSON.parseObject(toolStartData).getJSONObject("content_block").getString("type"));
    }

    // ==================== 辅助 ====================

    private JSONObject buildUserMsg(String text) {
        JSONObject msg = new JSONObject();
        msg.put("role", "user");
        msg.put("content", text);
        return msg;
    }

    /** 提取指定事件名的 data 行内容（第一个） */
    private String extractEventData(String sse, String eventName) {
        String marker = "event: " + eventName + "\n";
        int idx = sse.indexOf(marker);
        if (idx < 0) return "{}";
        int dataIdx = sse.indexOf("data: ", idx);
        if (dataIdx < 0) return "{}";
        int end = sse.indexOf("\n", dataIdx);
        return end < 0 ? sse.substring(dataIdx + 6) : sse.substring(dataIdx + 6, end);
    }

    /** 提取所有 content_block_delta 的 text 并拼接 */
    private String extractAllDeltaText(String sse) {
        StringBuilder sb = new StringBuilder();
        int idx = 0;
        while ((idx = sse.indexOf("event: content_block_delta", idx)) >= 0) {
            String data = extractEventData(sse.substring(idx), "content_block_delta");
            try {
                JSONObject obj = JSON.parseObject(data);
                if (obj != null && obj.getJSONObject("delta") != null
                        && "text_delta".equals(obj.getJSONObject("delta").getString("type"))) {
                    sb.append(obj.getJSONObject("delta").getString("text"));
                }
            } catch (Exception ignored) {
            }
            idx += "event: content_block_delta".length();
        }
        return sb.toString();
    }

    /** 提取所有 input_json_delta 的 partial_json 并拼接 */
    private String extractAllInputJsonDelta(String sse) {
        StringBuilder sb = new StringBuilder();
        int idx = 0;
        while ((idx = sse.indexOf("event: content_block_delta", idx)) >= 0) {
            String data = extractEventData(sse.substring(idx), "content_block_delta");
            try {
                JSONObject obj = JSON.parseObject(data);
                if (obj != null && obj.getJSONObject("delta") != null
                        && "input_json_delta".equals(obj.getJSONObject("delta").getString("type"))) {
                    sb.append(obj.getJSONObject("delta").getString("partial_json"));
                }
            } catch (Exception ignored) {
            }
            idx += "event: content_block_delta".length();
        }
        return sb.toString();
    }

    private long countOccurrences(String s, String sub) {
        long count = 0;
        int idx = 0;
        while ((idx = s.indexOf(sub, idx)) >= 0) {
            count++;
            idx += sub.length();
        }
        return count;
    }
}
