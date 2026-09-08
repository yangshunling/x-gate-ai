package com.xgateai.logging;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.xgateai.entity.CallLog;
import com.xgateai.entity.ModelChannel;
import com.xgateai.entity.UpstreamProvider;
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
 * 1. 单行日志：写入 gateway.log 文件，便于 grep/tail 排查
 * 2. 框式日志：仅输出到控制台（带 ANSI 颜色），便于实时观察
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

    private static final int SUMMARY_PREVIEW_MAX = 200;
    private static final int URL_DISPLAY_MAX = 42;
    private static final int BOX_WIDTH = 92;
    private static final int BOX_LABEL = 12;
    private static final int BOX_VALUE = BOX_WIDTH - 7 - BOX_LABEL;

    /** ANSI 颜色码 */
    private static final String A_RST = "\u001B[0m";
    private static final String A_BOLD = "\u001B[1m";
    private static final String A_CYAN = "\u001B[36m";
    private static final String A_GREEN = "\u001B[32m";
    private static final String A_RED = "\u001B[31m";

    /**
     * 输出网关调用日志（单行格式）
     *
     * @param type        调用类型：chat / embedding
     * @param channel     对客通道信息
     * @param requestedModel 客户端请求的模型名
     * @param rawBody     原始请求体 JSON 字符串
     * @param stream      是否流式调用
     * @param result      调用结果：SUCCESS / ALL_FAILED / INTERRUPTED
     * @param finalProvider 最终成功的上游 Provider（可能为 null）
     * @param costMs      总耗时（毫秒）
     * @param usage       Token 用量统计（可能为 null）
     * @param chain       故障转移链路记录
     */
    public void logCall(String type, ModelChannel channel, String requestedModel,
                        String rawBody, boolean stream, String result,
                        UpstreamProvider finalProvider, long costMs,
                        JSONObject usage, List<String> chain) {
        String path = "chat".equals(type)
                ? GatewayConstant.PATH_CHAT_COMPLETIONS
                : GatewayConstant.PATH_EMBEDDINGS;

        Integer inputTokens = usage == null ? null : usage.getInteger("prompt_tokens");
        Integer outputTokens = usage == null ? null : usage.getInteger("completion_tokens");

        // 构建单行日志
        StringBuilder sb = new StringBuilder(256);
        sb.append(type).append(' ').append(path)
                .append(" stream=").append(stream)
                .append(" channelId=").append(channel.getId())
                .append(" customer=").append(StrUtil.blankToDefault(channel.getPublicModelName(), "-"))
                .append(" model=").append(requestedModel)
                .append(" key=").append(channel.getApiKey())
                .append(" result=").append(result);

        if (finalProvider != null) {
            sb.append(" upstream=").append(finalProvider.getName()).append("(#")
                    .append(finalProvider.getId()).append(')')
                    .append(" upstreamModel=").append(finalProvider.getModelName())
                    .append(" url=").append(truncateDisplay(
                            finalProvider.getBaseUrl() + path, URL_DISPLAY_MAX));
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

        GATEWAY_LOGGER.info(sb.toString());

        // 输出框式日志到控制台
        printBoxedLog(type, channel, requestedModel, rawBody, stream, result,
                finalProvider, costMs, inputTokens, outputTokens, payloadDesc, chain);
    }

    /**
     * 构建故障转移链路条目
     */
    public String buildChainEntry(UpstreamProvider provider, String status, String error) {
        StringBuilder sb = new StringBuilder();
        sb.append(provider.getName()).append("(#").append(provider.getId())
                .append('/').append(provider.getModelName()).append("):").append(status);
        if ("FAIL".equals(status) && error != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("上游返回\\s*(\\d+)").matcher(error);
            if (m.find()) {
                sb.append('(').append(m.group(1)).append(')');
            } else {
                sb.append("(ERR)");
            }
        }
        return sb.toString();
    }

    /**
     * 构建调用日志实体（用于落库）
     */
    public CallLog buildCallLogEntry(ModelChannel channel, UpstreamProvider provider,
                                     String requestBody, String upstreamResponse,
                                     long latencyMs, int httpStatus) {
        CallLog entry = new CallLog();
        entry.setApiKey(channel.getApiKey());
        entry.setPublicModel(channel.getPublicModelName());
        entry.setUpstreamUrl(provider.getBaseUrl() + GatewayConstant.PATH_CHAT_COMPLETIONS);
        entry.setUpstreamModel(provider.getModelName());

        JSONObject usage = parseUsage(upstreamResponse);
        if (usage != null) {
            entry.setInputTokens(usage.getIntValue("prompt_tokens", 0));
            entry.setOutputTokens(usage.getIntValue("completion_tokens", 0));
        }

        entry.setLatencyMs(latencyMs);
        entry.setHttpStatus(httpStatus);
        entry.setRequestBody(buildChatSummary(requestBody));
        entry.setCustomerName(channel.getPublicModelName());
        entry.setToolCallsCount(countRequestToolCalls(requestBody));
        entry.setCreatedAt(cn.hutool.core.date.DateUtil.now());
        return entry;
    }

    // ==================== 私有辅助方法 ====================

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
            summary.put("preview", truncateDisplay(lastUserText, SUMMARY_PREVIEW_MAX));
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
                summary.put("preview", truncateDisplay(text, SUMMARY_PREVIEW_MAX));
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
                               UpstreamProvider provider, long costMs,
                               Integer inTokens, Integer outTokens,
                               String payload, List<String> chain) {
        boolean ok = "SUCCESS".equals(result);
        String statusWord = ok ? "成功" : ("INTERRUPTED".equals(result) ? "中断" : "失败");
        String statusColor = ok ? A_GREEN : A_RED;
        String time = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String tid = com.xgateai.logging.GatewayLog.getMdc(
                com.xgateai.logging.GatewayLog.MDC_TRACE_ID);
        String customer = StrUtil.blankToDefault(channel.getPublicModelName(), "-");
        String path = "chat".equals(type)
                ? GatewayConstant.PATH_CHAT_COMPLETIONS
                : GatewayConstant.PATH_EMBEDDINGS;
        String mode = "chat".equals(type)
                ? (stream ? "SSE 流式" : "JSON 非流式")
                : "Embedding";

        String costStr = costMs < 1000
                ? costMs + "ms"
                : String.format("%.3fs", costMs / 1000.0);

        String tokensText;
        if (inTokens == null && outTokens == null) {
            tokensText = "—";
        } else {
            int in = inTokens == null ? 0 : inTokens;
            int out = outTokens == null ? 0 : outTokens;
            tokensText = String.format("📥 输入 %s tokens  │  📤 输出 %s tokens",
                    formatThousands(in), formatThousands(out));
        }

        StringBuilder box = new StringBuilder();
        String hr = "═".repeat(BOX_WIDTH - 2);
        box.append(A_CYAN).append("╔").append(hr).append("╗").append(A_RST).append('\n');
        box.append(A_CYAN).append("║").append(A_BOLD)
                .append(center("🚀 GATEWAY 请求日志 · " + ("chat".equals(type) ? "对话" : "向量"), BOX_WIDTH - 2))
                .append(A_RST).append(A_CYAN).append("║").append(A_RST).append('\n');
        box.append(A_CYAN).append("╠").append(hr).append("╣").append(A_RST).append('\n');

        appendRow(box, "⏰ 请求时间", time);
        appendRow(box, "🔗 链路标识", StrUtil.blankToDefault(tid, "-"));
        appendRow(box, "👤 用户名称", customer);
        appendRow(box, "📡 请求接口", "POST " + path + "  ✦  " + mode);
        appendRow(box, "📊 令牌用量", tokensText);
        appendRow(box, "⚡ 请求性能", statusWord + "  ✦  耗时 " + costStr, statusColor);

        box.append(A_CYAN).append("╚").append(hr).append("╝").append(A_RST);
        GATEWAY_CONSOLE.info(box.toString());
    }

    private void appendRow(StringBuilder sb, String label, String value) {
        appendRow(sb, label, value, null);
    }

    private void appendRow(StringBuilder sb, String label, String value, String color) {
        String lab = truncateDisplay(label, BOX_LABEL);
        List<String> lines = wrapDisplay(value, BOX_VALUE);
        int pad = BOX_VALUE - displayWidth(lines.get(0));
        String firstVal = color == null ? lines.get(0) : color + lines.get(0) + A_RST;

        sb.append(A_CYAN).append("║  ").append(A_RST)
                .append(lab).append(padRight(BOX_LABEL - displayWidth(lab)))
                .append(A_CYAN).append("│ ").append(A_RST)
                .append(firstVal)
                .append(" ".repeat(Math.max(0, pad)))
                .append(A_CYAN).append(" ║").append(A_RST).append('\n');

        for (int i = 1; i < lines.size(); i++) {
            String v = lines.get(i);
            int p = BOX_VALUE - displayWidth(v);
            String cv = color == null ? v : color + v + A_RST;
            sb.append(A_CYAN).append("║  ").append(A_RST)
                    .append(padRight(BOX_LABEL))
                    .append(A_CYAN).append("│ ").append(A_RST)
                    .append(cv)
                    .append(" ".repeat(Math.max(0, p)))
                    .append(A_CYAN).append(" ║").append(A_RST).append('\n');
        }
    }

    private List<String> wrapDisplay(String s, int max) {
        if (s == null || s.isEmpty()) return java.util.Collections.singletonList("");
        if (displayWidth(s) <= max) return java.util.Collections.singletonList(s);
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int w = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            int cw = displayWidthChar(cp);
            if (w + cw > max && cur.length() > 0) {
                lines.add(cur.toString());
                cur = new StringBuilder();
                w = 0;
            }
            cur.appendCodePoint(cp);
            w += cw;
            i += Character.charCount(cp);
        }
        lines.add(cur.toString());
        return lines;
    }

    private String center(String text, int width) {
        int pad = Math.max(0, width - displayWidth(text));
        int l = pad / 2;
        return " ".repeat(l) + text + " ".repeat(pad - l);
    }

    private String padRight(int width) {
        return " ".repeat(Math.max(0, width));
    }

    private int displayWidth(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            w += displayWidthChar(cp);
            i += Character.charCount(cp);
        }
        return w;
    }

    private int displayWidthChar(int cp) {
        if (cp > 0x2E7F) return 2;
        if (cp >= 0x1F000 && cp <= 0x1FAFF) return 2;
        if (cp >= 0x2300 && cp <= 0x26FF) return 2;
        if (cp >= 0x2B00 && cp <= 0x2BFF) return 2;
        return 1;
    }

    private String truncateDisplay(String s, int max) {
        if (displayWidth(s) <= max) return s;
        StringBuilder sb = new StringBuilder();
        int w = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            int cw = displayWidthChar(cp);
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
