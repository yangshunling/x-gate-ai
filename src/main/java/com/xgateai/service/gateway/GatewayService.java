package com.xgateai.service.gateway;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.xgateai.entity.CallLog;
import com.xgateai.entity.ModelChannel;
import com.xgateai.entity.UpstreamProvider;
import com.xgateai.constant.GatewayConstant;
import com.xgateai.exception.BadRequestException;
import com.xgateai.adapter.ProxyAdapter;
import com.xgateai.logging.GatewayLogger;
import com.xgateai.mapper.ICallLogDao;
import com.xgateai.mapper.IUpstreamProviderDao;
import com.xgateai.service.gateway.strategy.UpstreamStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * GatewayService 网关核心服务
 * <p>
 * 负责请求的路由决策、上游调用编排和日志记录。
 * 采用策略模式支持不同的上游选择算法。
 * </p>
 *
 * @author xgateai
 * @since 2026/9/8
 */
@Slf4j
@Service
public class GatewayService {

    private final ProxyAdapter proxyAdapter;
    private final UpstreamStrategy upstreamStrategy;
    private final GatewayLogger gatewayLogger;
    private final ICallLogDao callLogDao;
    private final IUpstreamProviderDao upstreamProviderDao;

    public GatewayService(ProxyAdapter proxyAdapter,
                          UpstreamStrategy upstreamStrategy,
                          GatewayLogger gatewayLogger,
                          ICallLogDao callLogDao,
                          IUpstreamProviderDao upstreamProviderDao) {
        this.proxyAdapter = proxyAdapter;
        this.upstreamStrategy = upstreamStrategy;
        this.gatewayLogger = gatewayLogger;
        this.callLogDao = callLogDao;
        this.upstreamProviderDao = upstreamProviderDao;
    }

    /**
     * 非流式对话请求处理
     *
     * @param channel      对客通道信息
     * @param rawBody      原始请求体 JSON
     * @return             上游响应 JSON
     */
    public String chat(ModelChannel channel, String rawBody) {
        String requestedModel = parseRequestedModel(rawBody);
        List<UpstreamProvider> candidates = upstreamStrategy.selectCandidates(channel, requestedModel);

        List<String> chain = new ArrayList<>();
        long totalStart = System.currentTimeMillis();
        String lastError = "";

        for (int i = 0; i < candidates.size(); i++) {
            UpstreamProvider provider = candidates.get(i);
            long start = System.currentTimeMillis();

            try {
                String upstreamJson = proxyAdapter.chat(
                        provider,
                        rebindUpstreamModel(rawBody, provider.getModelName()));

                long latencyMs = System.currentTimeMillis() - start;
                JSONObject usage = recordCallLog(channel, provider, rawBody, upstreamJson,
                        latencyMs, 200, "chat");

                chain.add(gatewayLogger.buildChainEntry(provider, "OK", null));
                logCall("chat", channel, requestedModel, rawBody, false,
                        "SUCCESS", provider, System.currentTimeMillis() - totalStart, usage, chain);

                return upstreamJson;
            } catch (Exception e) {
                lastError = resolveMessage(e);
                chain.add(gatewayLogger.buildChainEntry(provider, "FAIL", lastError));
                bumpFailCount(provider);
            }
        }

        // 全链路失败
        candidates.forEach(this::bumpFailCount);
        logCall("chat", channel, requestedModel, rawBody, false,
                "ALL_FAILED", null, System.currentTimeMillis() - totalStart, null, chain);

        throw new BadRequestException("所有上游调用失败: " + lastError);
    }

    /**
     * 流式对话请求处理
     *
     * @param channel     对客通道信息
     * @param rawBody     原始请求体 JSON
     * @param onChunk     数据块回调函数
     */
    public void chatStream(ModelChannel channel, String rawBody, Consumer<byte[]> onChunk) {
        String requestedModel = parseRequestedModel(rawBody);
        List<UpstreamProvider> candidates = upstreamStrategy.selectCandidates(channel, requestedModel);

        List<String> chain = new ArrayList<>();
        long totalStart = System.currentTimeMillis();
        String lastError = "";

        for (int i = 0; i < candidates.size(); i++) {
            UpstreamProvider provider = candidates.get(i);
            long start = System.currentTimeMillis();

            try {
                boolean complete = proxyAdapter.streamChat(
                        provider,
                        rebindUpstreamModel(rawBody, provider.getModelName()),
                        onChunk);

                long latencyMs = System.currentTimeMillis() - start;
                String usageJson = extractUsageFromStream();
                JSONObject usage = parseUsage(usageJson);

                if (complete) {
                    chain.add(gatewayLogger.buildChainEntry(provider, "OK", null));
                    logCall("chat", channel, requestedModel, rawBody, true,
                            "SUCCESS", provider, System.currentTimeMillis() - totalStart, usage, chain);
                } else {
                    chain.add(gatewayLogger.buildChainEntry(provider, "BROKEN", null));
                    logCall("chat", channel, requestedModel, rawBody, true,
                            "INTERRUPTED", provider, System.currentTimeMillis() - totalStart, usage, chain);
                }

                recordCallLog(channel, provider, rawBody, usageJson, latencyMs, 200, "chat");
                return;
            } catch (Exception e) {
                lastError = resolveMessage(e);
                chain.add(gatewayLogger.buildChainEntry(provider, "FAIL", lastError));
                bumpFailCount(provider);
            }
        }

        // 全链路失败
        candidates.forEach(this::bumpFailCount);
        logCall("chat", channel, requestedModel, rawBody, true,
                "ALL_FAILED", null, System.currentTimeMillis() - totalStart, null, chain);

        throw new BadRequestException("所有上游流式调用失败: " + lastError);
    }

    /**
     * Embeddings 请求处理
     */
    public String embeddings(ModelChannel channel, String rawBody) {
        String requestedModel = parseRequestedModel(rawBody);
        List<UpstreamProvider> candidates = upstreamStrategy.selectCandidates(channel, requestedModel);

        List<String> chain = new ArrayList<>();
        long totalStart = System.currentTimeMillis();
        String lastError = "";

        for (int i = 0; i < candidates.size(); i++) {
            UpstreamProvider provider = candidates.get(i);
            long start = System.currentTimeMillis();

            try {
                String upstreamJson = proxyAdapter.embeddings(
                        provider,
                        rebindUpstreamModel(rawBody, provider.getModelName()));

                long latencyMs = System.currentTimeMillis() - start;
                JSONObject usage = recordCallLog(channel, provider, rawBody, upstreamJson,
                        latencyMs, 200, "embedding");

                chain.add(gatewayLogger.buildChainEntry(provider, "OK", null));
                logCall("embedding", channel, requestedModel, rawBody, false,
                        "SUCCESS", provider, System.currentTimeMillis() - totalStart, usage, chain);

                return upstreamJson;
            } catch (Exception e) {
                lastError = resolveMessage(e);
                chain.add(gatewayLogger.buildChainEntry(provider, "FAIL", lastError));
                bumpFailCount(provider);
            }
        }

        // 全链路失败
        candidates.forEach(this::bumpFailCount);
        logCall("embedding", channel, requestedModel, rawBody, false,
                "ALL_FAILED", null, System.currentTimeMillis() - totalStart, null, chain);

        throw new BadRequestException("所有上游调用失败: " + lastError);
    }

    // ==================== 私有辅助方法 ====================

    private String parseRequestedModel(String rawBody) {
        JSONObject body = JSON.parseObject(rawBody);
        if (body == null) {
            throw new BadRequestException("请求体为空或不是合法 JSON");
        }
        String model = StrUtil.trim(body.getString("model"));
        if (StrUtil.isBlank(model)) {
            throw new BadRequestException("请求体 model 字段不能为空");
        }
        return model;
    }

    private String rebindUpstreamModel(String rawBody, String upstreamModel) {
        JSONObject body = JSON.parseObject(rawBody);
        if (body == null) {
            throw new IllegalArgumentException("请求体为空或不是合法 JSON");
        }
        body.put("model", upstreamModel);
        return body.toJSONString();
    }

    private JSONObject recordCallLog(ModelChannel channel, UpstreamProvider provider,
                                     String requestBody, String upstreamResponse,
                                     long latencyMs, int httpStatus, String type) {
        try {
            CallLog entry = gatewayLogger.buildCallLogEntry(
                    channel, provider, requestBody, upstreamResponse, latencyMs, httpStatus);
            callLogDao.insert(entry);
            return parseUsage(upstreamResponse);
        } catch (Exception e) {
            log.error("记录调用日志失败, channel: {}, provider: {}",
                    channel.getPublicModelName(), provider.getName(), e);
            return null;
        }
    }

    private void logCall(String type, ModelChannel channel, String requestedModel,
                         String rawBody, boolean stream, String result,
                         UpstreamProvider finalProvider, long costMs,
                         JSONObject usage, List<String> chain) {
        gatewayLogger.logCall(type, channel, requestedModel, rawBody, stream,
                result, finalProvider, costMs, usage, chain);
    }

    private void bumpFailCount(UpstreamProvider provider) {
        try {
            if (provider.getId() == null) return;
            UpstreamProvider update = new UpstreamProvider();
            update.setId(provider.getId());
            update.setFailCount((provider.getFailCount() == null ? 0 : provider.getFailCount()) + 1);
            upstreamProviderDao.updateById(update);
        } catch (Exception e) {
            log.warn("更新 fail_count 失败, provider: {}", provider.getName(), e);
        }
    }

    private JSONObject parseUsage(String json) {
        try {
            JSONObject resp = JSON.parseObject(json);
            return resp == null ? null : resp.getJSONObject("usage");
        } catch (Exception e) {
            return null;
        }
    }

    private String extractUsageFromStream() {
        // TODO: 需要从流式结果中提取 usage，这里简化处理
        return null;
    }

    private String resolveMessage(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return StrUtil.isBlank(message) ? cause.getClass().getSimpleName() : message;
    }
}
