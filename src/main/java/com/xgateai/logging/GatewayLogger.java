package com.xgateai.logging;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.xgateai.entity.CallLog;
import com.xgateai.entity.ModelChannel;
import com.xgateai.entity.UpstreamProvider;
import com.xgateai.entity.UpstreamRoute;
import com.xgateai.constant.GatewayConstant;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * GatewayLogger 网关日志组件
 * <p>
 * 负责输出两种格式的网关日志：
 * <ol>
 *   <li>单行日志：写入 gateway.log 文件，便于 grep/tail 排查</li>
 *   <li>框式日志：仅输出到控制台（带 ANSI 颜色），便于实时观察</li>
 * </ol>
 * </p>
 *
 * @author xgateai
 * @since 2026/9/8
 */
@Slf4j
@Component
public class GatewayLogger {

    /** 网关业务日志专用 Logger（logback 路由到控制台 + logs/gateway.log） */
    private static final Logger GATEWAY_LOGGER = LoggerFactory.getLogger("GATEWAY");

    /** 网关框式日志专用 Logger（仅控制台，带 ANSI 彩色） */
    private static final Logger GATEWAY_CONSOLE = LoggerFactory.getLogger("GATEWAY_CONSOLE");

    /** 上游 URL 显示最大字符数 */
    private static final int URL_DISPLAY_MAX = 42;
    /** 框式日志整体宽度 */
    private static final int BOX_WIDTH = 92;
    /** 左侧标签列宽 */
    private static final int BOX_LABEL = 14;
    /** 右侧值列宽 */
    private static final int BOX_VALUE = BOX_WIDTH - 7 - BOX_LABEL;

    /** ANSI 重置 */
    private static final String A_RST = "\u001B[0m";
    /** ANSI 加粗 */
    private static final String A_BOLD = "\u001B[1m";
    /** ANSI 青色 */
    private static final String A_CYAN = "\u001B[36m";
    /** ANSI 绿色 */
    private static final String A_GREEN = "\u001B[32m";
    /** ANSI 红色 */
    private static final String A_RED = "\u001B[31m";

    /** 输入 Token 单价（每百万 token 美元） */
    private static final double PRICE_IN_PER_M = 0.30;
    /** 输出 Token 单价（每百万 token 美元） */
    private static final double PRICE_OUT_PER_M = 1.20;

    // ==================== 公开接口 ====================

    /**
     * 输出网关调用日志（单行格式 + 框式格式）
     *
     * @param type           调用类型：chat / embedding
     * @param channel        对客通道信息
     * @param requestedModel 客户端请求的模型名
     * @param rawBody        原始请求体 JSON 字符串
     * @param stream         是否流式调用
     * @param result         调用结果：SUCCESS / ALL_FAILED / INTERRUPTED
     * @param finalRoute     最终成功的上游路由目标（ALL_FAILED 时为 null）
     * @param costMs         总耗时（毫秒）
     * @param usage          Token 用量统计（可能为 null）
     * @param chain          故障转移链路记录
     * @param clientPath     客户端实际命中的网关入口路径（如 /chat/completions / /messages / /responses），用于日志展示
     */
    public void logCall(String type, ModelChannel channel, String requestedModel,
                        String rawBody, boolean stream, String result,
                        UpstreamRoute finalRoute, long costMs,
                        JSONObject usage, List<String> chain, String clientPath) {
        // 上游实际调用路径：对话走 /chat/completions，向量化走 /embeddings
        String upstreamPath = "chat".equals(type)
                ? GatewayConstant.PATH_CHAT_COMPLETIONS
                : GatewayConstant.PATH_EMBEDDINGS;
        // 客户端入口路径：展示用，缺省时退回上游路径
        String displayPath = StrUtil.isBlank(clientPath) ? upstreamPath : clientPath;

        Integer inputTokens = usage == null ? null : usage.getInteger("prompt_tokens");
        Integer outputTokens = usage == null ? null : usage.getInteger("completion_tokens");

        JSONObject parsedBody = JSON.parseObject(rawBody);
        StringBuilder sb = buildSingleLineLog(type, displayPath, upstreamPath, stream, channel,
                requestedModel, result, finalRoute, costMs, inputTokens, outputTokens,
                parsedBody, chain);

        switch (result) {
            case "SUCCESS" -> GATEWAY_LOGGER.info(sb.toString());
            case "INTERRUPTED" -> GATEWAY_LOGGER.warn(sb.toString());
            default -> GATEWAY_LOGGER.error(sb.toString());
        }

        printBoxedLog(type, displayPath, channel, requestedModel, parsedBody, stream, result,
                finalRoute, costMs, inputTokens, outputTokens, chain);
    }

    /**
     * 构建故障转移链路条目
     *
     * @param route  上游路由目标（渠道 + 模型行）
     * @param status 状态："OK" / "FAIL" / "BROKEN"
     * @param error  失败原因（status=FAIL 时有效）
     * @return 格式化后的链路条目字符串
     */
    public String buildChainEntry(UpstreamRoute route, String status, String error) {
        StringBuilder sb = new StringBuilder();
        UpstreamProvider provider = route.getProvider();
        sb.append(provider.getName()).append("(#").append(provider.getId())
                .append('/').append(route.getModelName()).append("):").append(status);
        if ("FAIL".equals(status) && error != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("上游返回\\s*(\\d+)").matcher(error);
            if (m.find()) {
                sb.append('(').append(m.group(1)).append(')');
            } else {
                sb.append("(ERR)");
            }
        } else if ("FULL".equals(status) && error != null) {
            sb.append('(').append(error).append(')');
        }
        return sb.toString();
    }

    /**
     * 构建调用日志实体（用于落库）
     *
     * @param channel          对客通道信息
     * @param route            最终选中的上游路由目标（渠道 + 模型行）
     * @param requestBody      原始请求体摘要
     * @param usage            上游返回的 Token 用量（已解析，可能为 null）
     * @param latencyMs        端到端耗时（毫秒）
     * @param httpStatus       HTTP 状态码
     * @return 已填充字段的 CallLog 实体
     */
    public CallLog buildCallLogEntry(ModelChannel channel, UpstreamRoute route,
                                      String requestBody, JSONObject usage,
                                      long latencyMs, int httpStatus) {
        CallLog entry = new CallLog();
        entry.setApiKey(channel.getApiKey());
        entry.setPublicModel(channel.getPublicModelName());
        entry.setUpstreamUrl(route.getProvider().getBaseUrl() + GatewayConstant.PATH_CHAT_COMPLETIONS);
        entry.setUpstreamModel(route.getModelName());

        if (usage != null) {
            entry.setInputTokens(usage.getIntValue("prompt_tokens", 0));
            entry.setOutputTokens(usage.getIntValue("completion_tokens", 0));
        }

        entry.setLatencyMs(latencyMs);
        entry.setHttpStatus(httpStatus);
        JSONObject parsedBody = JSON.parseObject(requestBody);
        entry.setRequestBody(buildChatSummary(parsedBody));
        entry.setCustomerName(channel.getPublicModelName());
        entry.setToolCallsCount(countRequestToolCalls(parsedBody));
        entry.setCreatedAt(DateUtil.now());
        return entry;
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 构建单行日志字符串（写入 gateway.log）
     *
     * @return 拼装好的单行日志
     */
    private StringBuilder buildSingleLineLog(String type, String displayPath, String upstreamPath,
                                              boolean stream, ModelChannel channel, String requestedModel,
                                              String result, UpstreamRoute finalRoute,
                                              long costMs, Integer inputTokens,
                                              Integer outputTokens, JSONObject parsedBody,
                                              List<String> chain) {
        StringBuilder sb = new StringBuilder(256);
        sb.append(type).append(' ').append(displayPath)
                .append(" stream=").append(stream)
                .append(" channelId=").append(channel.getId())
                .append(" customer=").append(StrUtil.blankToDefault(channel.getPublicModelName(), "-"))
                .append(" model=").append(requestedModel)
                .append(" key=").append(channel.getApiKey())
                .append(" result=").append(result);

        if (finalRoute != null) {
            UpstreamProvider provider = finalRoute.getProvider();
            sb.append(" upstream=").append(provider.getName()).append("(#")
                    .append(provider.getId()).append(')')
                    .append(" upstreamModel=").append(finalRoute.getModelName())
                    .append(" url=").append(truncateDisplay(
                            provider.getBaseUrl() + upstreamPath, URL_DISPLAY_MAX));
        }

        sb.append(" cost=").append(costMs).append("ms");
        if (inputTokens != null || outputTokens != null) {
            sb.append(" tokens=in:").append(inputTokens == null ? "?" : inputTokens)
                    .append("/out:").append(outputTokens == null ? "?" : outputTokens);
        }

        String payloadDesc = buildPayloadDescription(type, parsedBody);
        if (StrUtil.isNotBlank(payloadDesc)) {
            sb.append(' ').append(payloadDesc);
        }

        if (chain != null && !chain.isEmpty()) {
            sb.append(" chain=").append(String.join(" -> ", chain));
        }
        return sb;
    }

    /**
     * 从请求体摘要 JSON 中构建面向单行日志的 payload 描述（roles/preview/inputCount）
     *
     * @param type       调用类型
     * @param parsedBody 已解析的请求体
     * @return payload 描述片段；无内容时返回空串
     */
    private String buildPayloadDescription(String type, JSONObject parsedBody) {
        String summaryJson = "chat".equals(type)
                ? buildChatSummary(parsedBody)
                : buildEmbeddingSummary(parsedBody);
        if (summaryJson == null) {
            return "";
        }
        JSONObject summary = JSON.parseObject(summaryJson);
        StringBuilder sb = new StringBuilder();

        JSONObject roles = summary.getJSONObject("roles");
        if (roles != null && !roles.isEmpty()) {
            List<String> parts = new ArrayList<>();
            for (String role : roles.keySet()) {
                parts.add(role + ":" + roles.getIntValue(role));
            }
            sb.append("roles=").append(String.join(",", parts));
        }

        String preview = summary.getString("preview");
        if (StrUtil.isNotBlank(preview)) {
            if (sb.length() > 0) sb.append(' ');
            sb.append("preview=\"").append(truncateDisplay(preview, 60)).append('"');
        }

        Integer inputCount = summary.getInteger("inputCount");
        if (inputCount != null) {
            if (sb.length() > 0) sb.append(' ');
            sb.append("input=").append(inputCount);
        }

        return sb.toString();
    }

    /**
     * 构建 chat 请求体摘要（type/roles/preview）
     *
     * @param body 请求体
     * @return 摘要 JSON 字符串；解析失败返回 null
     */
    private String buildChatSummary(JSONObject body) {
        try {
            JSONObject summary = new JSONObject();
            summary.put("type", "chat");
            JSONArray messages = body == null ? null : body.getJSONArray("messages");
            if (messages == null || messages.isEmpty()) {
                return summary.toJSONString();
            }

            JSONObject roles = new JSONObject();
            String lastUserText = "";
            for (int i = 0; i < messages.size(); i++) {
                JSONObject msg = messages.getJSONObject(i);
                if (msg == null) continue;
                String role = StrUtil.nullToEmpty(msg.getString("role"));
                roles.merge(role, 1, (oldVal, addVal) -> ((Number) oldVal).intValue() + ((Number) addVal).intValue());
                if ("user".equals(role)) {
                    lastUserText = extractMessageText(msg);
                }
            }
            summary.put("roles", roles);
            summary.put("preview", lastUserText);
            return summary.toJSONString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 构建 embedding 请求体摘要（type/inputCount/preview）
     *
     * @param body 请求体
     * @return 摘要 JSON 字符串；解析失败返回 null
     */
    private String buildEmbeddingSummary(JSONObject body) {
        try {
            JSONObject summary = new JSONObject();
            summary.put("type", "embedding");
            Object input = body == null ? null : body.get("input");
            if (input instanceof JSONArray arr) {
                summary.put("inputCount", arr.size());
            } else if (input instanceof String text) {
                summary.put("inputCount", 1);
                summary.put("preview", text);
            } else {
                summary.put("inputCount", 0);
            }
            return summary.toJSONString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 提取 message 的文本内容：字符串原样返回，content blocks 数组拼接 text 块
     *
     * @param message 消息对象
     * @return 文本内容；无文本时返回空串
     */
    private String extractMessageText(JSONObject message) {
        Object content = message.get("content");
        if (content == null) return "";
        if (content instanceof String text) return text;
        if (content instanceof JSONArray parts) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.size(); i++) {
                JSONObject part = parts.getJSONObject(i);
                if (part != null && "text".equals(part.getString("type"))) {
                    sb.append(StrUtil.nullToEmpty(part.getString("text")));
                }
            }
            return sb.toString();
        }
        return content.toString();
    }

    /**
     * 统计请求体中 assistant 消息的 tool_calls 总数
     *
     * @param body 请求体
     * @return tool_calls 总数；解析失败返回 0
     */
    private int countRequestToolCalls(JSONObject body) {
        try {
            JSONArray messages = body == null ? null : body.getJSONArray("messages");
            if (messages == null || messages.isEmpty()) return 0;
            int total = 0;
            for (int i = 0; i < messages.size(); i++) {
                JSONObject msg = messages.getJSONObject(i);
                if (msg == null) continue;
                if (!"assistant".equals(msg.getString("role"))) continue;
                JSONArray tools = msg.getJSONArray("tool_calls");
                if (tools != null) total += tools.size();
            }
            return total;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 从上游响应 JSON 中提取 usage 对象
     *
     * @param upstreamJson 上游响应 JSON
     * @return usage 对象；解析失败返回 null
     */
    private JSONObject parseUsage(String upstreamJson) {
        try {
            JSONObject resp = JSON.parseObject(upstreamJson);
            return resp == null ? null : resp.getJSONObject("usage");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 输出框式日志到控制台（带 ANSI 颜色）
     *
     * @param type           调用类型
     * @param displayPath    展示路径
     * @param channel        对客通道
     * @param requestedModel 请求模型
     * @param parsedBody     已解析的请求体
     * @param stream         是否流式
     * @param result         调用结果
     * @param finalRoute     最终路由目标
     * @param costMs         总耗时
     * @param inTokens       输入 Token
     * @param outTokens      输出 Token
     * @param chain          故障转移链路
     */
    private void printBoxedLog(String type, String displayPath, ModelChannel channel,
                               String requestedModel, JSONObject parsedBody, boolean stream,
                               String result, UpstreamRoute finalRoute, long costMs,
                               Integer inTokens, Integer outTokens, List<String> chain) {
        boolean ok = "SUCCESS".equals(result);
        String statusWord = ok ? "成功" : ("INTERRUPTED".equals(result) ? "中断" : "失败");
        String statusColor = ok ? A_GREEN : A_RED;
        String time = DateUtil.format(DateUtil.date(), "yyyy-MM-dd HH:mm:ss");
        String tid = GatewayLog.getMdc(GatewayLog.MDC_TRACE_ID);
        String customer = StrUtil.blankToDefault(channel.getPublicModelName(), "-");
        String fullKey = StrUtil.blankToDefault(channel.getApiKey(), "-");
        String mode = "chat".equals(type)
                ? (stream ? "SSE 流式" : "JSON 非流式")
                : "Embedding";

        String costStr = costMs < 1000
                ? costMs + "ms"
                : String.format("%.3fs", costMs / 1000.0);

        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{"请求时间", time, null});
        rows.add(new Object[]{"链路标识", StrUtil.blankToDefault(tid, "-"), null});
        rows.add(new Object[]{"用户名称", customer + " (" + fullKey + ")", null});
        rows.add(new Object[]{"请求接口", "POST " + displayPath + "  ✦  " + mode, null});
        rows.add(new Object[]{"请求模型", StrUtil.blankToDefault(requestedModel, "-"), null});
        if (inTokens != null || outTokens != null) {
            int in = inTokens == null ? 0 : inTokens;
            int out = outTokens == null ? 0 : outTokens;
            rows.add(new Object[]{"令牌用量", "输入 " + formatThousands(in) + " tokens"
                    + (out > 0 ? "  │  输出 " + formatThousands(out) + " tokens" : ""), null});
        }
        rows.add(new Object[]{"请求性能", statusWord + "  耗时 " + costStr, statusColor});

        String rolesText = buildRolesDescription(parsedBody);
        if (StrUtil.isNotBlank(rolesText)) {
            rows.add(new Object[]{"角色分布", rolesText, null});
        }

        if (chain != null && !chain.isEmpty()) {
            rows.add(new Object[]{"故障转移", String.join("  →  ", chain), null});
        }

        int maxValWidth = 0;
        for (Object[] row : rows) {
            maxValWidth = Math.max(maxValWidth, displayWidth((String) row[1]));
        }
        int boxValue = Math.max(BOX_VALUE, maxValWidth);
        int boxWidth = boxValue + 7 + BOX_LABEL;

        StringBuilder box = new StringBuilder();
        String hr = "═".repeat(boxWidth - 2);
        box.append(A_CYAN).append("╔").append(hr).append("╗").append(A_RST).append('\n');
        box.append(A_CYAN).append("║").append(A_BOLD)
                .append(center("GATEWAY 请求日志 · " + ("chat".equals(type) ? "对话" : "向量"), boxWidth - 2))
                .append(A_RST).append(A_CYAN).append("║").append(A_RST).append('\n');
        box.append(A_CYAN).append("╠").append(hr).append("╣").append(A_RST).append('\n');

        for (Object[] row : rows) {
            appendRow(box, (String) row[0], (String) row[1], (String) row[2], boxValue);
        }

        box.append(A_CYAN).append("╚").append(hr).append("╝").append(A_RST);
        GATEWAY_CONSOLE.info(box.toString());
    }

    /**
     * 构建请求体角色分布描述（如 system:1 user:2 assistant:1）
     *
     * @param body 请求体
     * @return 角色分布描述；无内容返回空串
     */
    private String buildRolesDescription(JSONObject body) {
        if (body == null) return "";
        try {
            JSONArray messages = body.getJSONArray("messages");
            if (messages == null || messages.isEmpty()) return "";

            JSONObject roles = new JSONObject();
            for (int i = 0; i < messages.size(); i++) {
                JSONObject msg = messages.getJSONObject(i);
                if (msg == null) continue;
                String role = StrUtil.nullToEmpty(msg.getString("role"));
                if (StrUtil.isBlank(role)) continue;
                roles.merge(role, 1, (oldVal, addVal) -> ((Number) oldVal).intValue() + ((Number) addVal).intValue());
            }
            if (roles.isEmpty()) return "";
            List<String> parts = new ArrayList<>();
            for (String role : roles.keySet()) {
                parts.add(role + ":" + roles.getIntValue(role));
            }
            return String.join(" ", parts);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 追加一行到框式日志（默认值列宽）
     *
     * @param sb    日志字符串构建器
     * @param label 行标签
     * @param value 行值
     * @param color ANSI 颜色（可空）
     */
    private void appendRow(StringBuilder sb, String label, String value, String color) {
        appendRow(sb, label, value, color, BOX_VALUE);
    }

    /**
     * 追加一行到框式日志（自定义值列宽）
     *
     * @param sb       日志字符串构建器
     * @param label    行标签
     * @param value    行值
     * @param color    ANSI 颜色（可空）
     * @param boxValue 值列宽
     */
    private void appendRow(StringBuilder sb, String label, String value, String color, int boxValue) {
        String lab = truncateDisplay(label, BOX_LABEL);
        String val = truncateDisplay(value, boxValue);
        int pad = boxValue - displayWidth(val);
        String coloredVal = color == null ? val : color + val + A_RST;

        sb.append(A_CYAN).append("║  ").append(A_RST)
                .append(lab).append(padRight(BOX_LABEL - displayWidth(lab)))
                .append(A_CYAN).append("│ ").append(A_RST)
                .append(coloredVal)
                .append(" ".repeat(Math.max(0, pad)))
                .append(A_CYAN).append(" ║").append(A_RST).append('\n');
    }

    /**
     * 将文本在指定宽度内居中填充
     *
     * @param text  文本
     * @param width 目标宽度
     * @return 居中后的字符串
     */
    private String center(String text, int width) {
        int pad = Math.max(0, width - displayWidth(text));
        int l = pad / 2;
        return " ".repeat(l) + text + " ".repeat(pad - l);
    }

    /**
     * 生成指定宽度的空格串
     *
     * @param width 宽度
     * @return 空格字符串
     */
    private String padRight(int width) {
        return " ".repeat(Math.max(0, width));
    }

    /** 估算显示宽度：ASCII 占 1 列，CJK/emoji 等按 2 列 */
    private int displayWidth(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            w += (cp > 0x2E7F) ? 2 : 1;
            i += Character.charCount(cp);
        }
        return w;
    }

    /** 截断到指定显示宽度（超长补 …） */
    private String truncateDisplay(String s, int max) {
        if (displayWidth(s) <= max) return s;
        StringBuilder sb = new StringBuilder();
        int w = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            int cw = (cp > 0x2E7F) ? 2 : 1;
            if (w + cw > max - 1) break;
            sb.appendCodePoint(cp);
            w += cw;
            i += Character.charCount(cp);
        }
        return sb + "…";
    }

    /**
     * 将 Integer 格式化为千分位分隔字符串
     *
     * @param n 数字
     * @return 千分位字符串；null 返回 "?"
     */
    private String formatThousands(Integer n) {
        if (n == null) return "?";
        return java.text.NumberFormat.getIntegerInstance().format(n);
    }
}
