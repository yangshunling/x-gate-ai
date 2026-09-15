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
     */
    public void logCall(String type, ModelChannel channel, String requestedModel,
                        String rawBody, boolean stream, String result,
                        UpstreamRoute finalRoute, long costMs,
                        JSONObject usage, List<String> chain) {
        String path = "chat".equals(type)
                ? GatewayConstant.PATH_CHAT_COMPLETIONS
                : GatewayConstant.PATH_EMBEDDINGS;

        Integer inputTokens = usage == null ? null : usage.getInteger("prompt_tokens");
        Integer outputTokens = usage == null ? null : usage.getInteger("completion_tokens");

        StringBuilder sb = buildSingleLineLog(type, path, stream, channel, requestedModel,
                result, finalRoute, costMs, inputTokens, outputTokens, rawBody, chain);

        switch (result) {
            case "SUCCESS" -> GATEWAY_LOGGER.info(sb.toString());
            case "INTERRUPTED" -> GATEWAY_LOGGER.warn(sb.toString());
            default -> GATEWAY_LOGGER.error(sb.toString());
        }

        printBoxedLog(type, channel, requestedModel, rawBody, stream, result,
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
        entry.setRequestBody(buildChatSummary(requestBody));
        entry.setCustomerName(channel.getPublicModelName());
        entry.setToolCallsCount(countRequestToolCalls(requestBody));
        entry.setCreatedAt(DateUtil.now());
        return entry;
    }

    // ==================== 私有辅助方法 ====================

    private StringBuilder buildSingleLineLog(String type, String path, boolean stream,
                                              ModelChannel channel, String requestedModel,
                                              String result, UpstreamRoute finalRoute,
                                              long costMs, Integer inputTokens,
                                              Integer outputTokens, String rawBody,
                                              List<String> chain) {
        StringBuilder sb = new StringBuilder(256);
        sb.append(type).append(' ').append(path)
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
                            provider.getBaseUrl() + path, URL_DISPLAY_MAX));
        }

        sb.append(" cost=").append(costMs).append("ms");
        if (inputTokens != null || outputTokens != null) {
            sb.append(" tokens=in:").append(inputTokens == null ? "?" : inputTokens)
                    .append("/out:").append(outputTokens == null ? "?" : outputTokens);
        }

        String payloadDesc = buildPayloadDescription(type, rawBody);
        if (StrUtil.isNotBlank(payloadDesc)) {
            sb.append(' ').append(payloadDesc);
        }

        if (chain != null && !chain.isEmpty()) {
            sb.append(" chain=").append(String.join(" -> ", chain));
        }
        return sb;
    }

    private String buildPayloadDescription(String type, String rawBody) {
        String summaryJson = "chat".equals(type)
                ? buildChatSummary(rawBody)
                : buildEmbeddingSummary(rawBody);
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

    private String buildChatSummary(String rawBody) {
        try {
            JSONObject summary = new JSONObject();
            summary.put("type", "chat");
            JSONObject body = JSON.parseObject(rawBody);
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

    private String buildEmbeddingSummary(String rawBody) {
        try {
            JSONObject summary = new JSONObject();
            summary.put("type", "embedding");
            JSONObject body = JSON.parseObject(rawBody);
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

    private int countRequestToolCalls(String rawBody) {
        try {
            JSONObject body = JSON.parseObject(rawBody);
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

    private JSONObject parseUsage(String upstreamJson) {
        try {
            JSONObject resp = JSON.parseObject(upstreamJson);
            return resp == null ? null : resp.getJSONObject("usage");
        } catch (Exception e) {
            return null;
        }
    }

    private void printBoxedLog(String type, ModelChannel channel, String requestedModel,
                               String rawBody, boolean stream, String result,
                               UpstreamRoute finalRoute, long costMs,
                               Integer inTokens, Integer outTokens,
                               List<String> chain) {
        boolean ok = "SUCCESS".equals(result);
        String statusWord = ok ? "成功" : ("INTERRUPTED".equals(result) ? "中断" : "失败");
        String statusColor = ok ? A_GREEN : A_RED;
        String time = DateUtil.format(DateUtil.date(), "yyyy-MM-dd HH:mm:ss");
        String tid = GatewayLog.getMdc(GatewayLog.MDC_TRACE_ID);
        String customer = StrUtil.blankToDefault(channel.getPublicModelName(), "-");
        String fullKey = StrUtil.blankToDefault(channel.getApiKey(), "-");
        String path = "chat".equals(type)
                ? GatewayConstant.PATH_CHAT_COMPLETIONS
                : GatewayConstant.PATH_EMBEDDINGS;
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
        rows.add(new Object[]{"请求接口", "POST " + path + "  ✦  " + mode, null});
        rows.add(new Object[]{"请求模型", StrUtil.blankToDefault(requestedModel, "-"), null});
        if (inTokens != null || outTokens != null) {
            int in = inTokens == null ? 0 : inTokens;
            int out = outTokens == null ? 0 : outTokens;
            rows.add(new Object[]{"令牌用量", "输入 " + formatThousands(in) + " tokens"
                    + (out > 0 ? "  │  输出 " + formatThousands(out) + " tokens" : ""), null});
        }
        rows.add(new Object[]{"请求性能", statusWord + "  耗时 " + costStr, statusColor});

        String rolesText = buildRolesDescription(type, rawBody);
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

    private String buildRolesDescription(String type, String rawBody) {
        if (!"chat".equals(type)) return "";
        try {
            JSONObject body = JSON.parseObject(rawBody);
            JSONArray messages = body == null ? null : body.getJSONArray("messages");
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

    private void appendRow(StringBuilder sb, String label, String value, String color) {
        appendRow(sb, label, value, color, BOX_VALUE);
    }

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

    private String center(String text, int width) {
        int pad = Math.max(0, width - displayWidth(text));
        int l = pad / 2;
        return " ".repeat(l) + text + " ".repeat(pad - l);
    }

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

    private String formatThousands(Integer n) {
        if (n == null) return "?";
        return java.text.NumberFormat.getIntegerInstance().format(n);
    }
}
