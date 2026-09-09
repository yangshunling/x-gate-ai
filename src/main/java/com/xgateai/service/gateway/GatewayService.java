package com.xgateai.service.gateway;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.xgateai.entity.CallLog;
import com.xgateai.entity.ModelChannel;
import com.xgateai.entity.UpstreamModel;
import com.xgateai.entity.UpstreamRoute;
import com.xgateai.constant.GatewayConstant;
import com.xgateai.exception.BadRequestException;
import com.xgateai.adapter.ProxyAdapter;
import com.xgateai.component.InflightRegistry;
import com.xgateai.logging.GatewayLog;
import com.xgateai.logging.GatewayLogger;
import com.xgateai.mapper.ICallLogDao;
import com.xgateai.mapper.IUpstreamModelDao;
import com.xgateai.service.gateway.strategy.UpstreamStrategy;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * GatewayService 网关核心服务
 * <p>
 * 负责请求的路由决策、上游调用编排和日志记录。
 * 采用策略模式选择候选，候选粒度为「渠道下的模型行」（{@link UpstreamRoute}）。
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
    private final IUpstreamModelDao upstreamModelDao;
    private final InflightRegistry inflightRegistry;
    private ExecutorService logExecutor;

    public GatewayService(ProxyAdapter proxyAdapter,
                          UpstreamStrategy upstreamStrategy,
                          GatewayLogger gatewayLogger,
                          ICallLogDao callLogDao,
                          IUpstreamModelDao upstreamModelDao,
                          InflightRegistry inflightRegistry) {
        this.proxyAdapter = proxyAdapter;
        this.upstreamStrategy = upstreamStrategy;
        this.gatewayLogger = gatewayLogger;
        this.callLogDao = callLogDao;
        this.upstreamModelDao = upstreamModelDao;
        this.inflightRegistry = inflightRegistry;
    }

    /**
     * 初始化日志落库与日志打印专用线程池，避免同步 IO 阻塞请求主流程。
     * 队列满或线程池关闭时按拒绝策略静默丢弃，日志丢失不影响业务。
     */
    @PostConstruct
    void initLogExecutor() {
        int cores = Runtime.getRuntime().availableProcessors();
        AtomicInteger seq = new AtomicInteger(0);
        logExecutor = new ThreadPoolExecutor(
                Math.max(2, cores),
                Math.max(4, cores * 2),
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(2048),
                r -> {
                    Thread t = new Thread(r, "xgate-log-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.DiscardPolicy());
    }

    /**
     * 应用关闭时优雅关闭日志线程池，已提交任务执行完毕后终止
     */
    @PreDestroy
    void shutdownLogExecutor() {
        if (logExecutor != null) {
            logExecutor.shutdown();
        }
    }

    // ==================== 对话接口 ====================

    /**
     * 非流式对话请求处理
     *
     * @param channel   对客通道信息
     * @param rawBody   原始请求体 JSON
     * @return          上游响应 JSON 字符串
     * @throws BadRequestException 当所有上游候选均调用失败时抛出
     */
    public String chat(ModelChannel channel, String rawBody) {
        String requestedModel = parseRequestedModel(rawBody);
        List<UpstreamRoute> candidates = upstreamStrategy.selectCandidates(channel, requestedModel);
        return executeWithFailover(channel, requestedModel, rawBody, false, candidates,
                (route, body) -> proxyAdapter.chat(route.getProvider(), body),
                (route, body, latencyMs, usage) -> recordCallLog(channel, route, body, usage, latencyMs, 200));
    }

    /**
     * 流式对话请求处理
     *
     * @param channel   对客通道信息
     * @param rawBody   原始请求体 JSON
     * @param onChunk   数据块回调函数，逐块转发至响应流
     * @throws BadRequestException 当所有上游候选均调用失败时抛出
     */
    public void chatStream(ModelChannel channel, String rawBody, Consumer<byte[]> onChunk) {
        String requestedModel = parseRequestedModel(rawBody);
        List<UpstreamRoute> candidates = upstreamStrategy.selectCandidates(channel, requestedModel);
        executeWithFailoverStream(channel, requestedModel, rawBody, candidates,
                (route, body, chunkConsumer) -> proxyAdapter.streamChat(route.getProvider(), body, chunkConsumer),
                onChunk);
    }

    // ==================== Embeddings 接口 ====================

    /**
     * Embeddings 请求处理
     *
     * @param channel  对客通道信息
     * @param rawBody  原始请求体 JSON
     * @return         上游响应 JSON 字符串
     * @throws BadRequestException 当所有上游候选均调用失败时抛出
     */
    public String embeddings(ModelChannel channel, String rawBody) {
        String requestedModel = parseRequestedModel(rawBody);
        List<UpstreamRoute> candidates = upstreamStrategy.selectCandidates(channel, requestedModel);
        return executeWithFailover(channel, requestedModel, rawBody, false, candidates,
                (route, body) -> proxyAdapter.embeddings(route.getProvider(), body),
                (route, body, latencyMs, usage) -> recordCallLog(channel, route, body, usage, latencyMs, 200));
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 解析客户端请求中的模型名
     *
     * @param rawBody 原始请求体 JSON
     * @return 模型名字符串
     * @throws BadRequestException 当请求体无效或 model 字段为空时
     */
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

    /**
     * 将请求体中的 model 字段替换为路由目标真实模型名后返回
     *
     * @param rawBody       原始请求体
     * @param upstreamModel 路由目标真实模型名
     * @return 替换后的请求体 JSON 字符串
     */
    private String rebindUpstreamModel(String rawBody, String upstreamModel) {
        JSONObject body = JSON.parseObject(rawBody);
        if (body == null) {
            throw new IllegalArgumentException("请求体为空或不是合法 JSON");
        }
        body.put("model", upstreamModel);
        return body.toJSONString();
    }

    /**
     * 带故障转移的重试执行框架（非流式）
     *
     * @param channel        对客通道信息
     * @param requestedModel 客户端请求的模型名
     * @param rawBody        原始请求体
     * @param stream         是否流式（用于日志标记）
     * @param candidates     上游候选列表
     * @param callFn         上游调用函数
     * @param onSuccessFn    调用成功后的记录函数
     * @return               上游响应 JSON
     */
    private String executeWithFailover(ModelChannel channel, String requestedModel, String rawBody,
                                        boolean stream, List<UpstreamRoute> candidates,
                                        UpstreamCallFn callFn, OnSuccessFn onSuccessFn) {
        List<String> chain = new ArrayList<>();
        long totalStart = System.currentTimeMillis();
        String lastError = "";
        boolean anyAttempted = false;

        for (int i = 0; i < candidates.size(); i++) {
            UpstreamRoute route = candidates.get(i);
            UpstreamModel model = route.getModel();
            int limit = maxConcurrencyOf(model);

            // 并发上限检查：在途已达上限则跳过该候选，故障转移到下一优先级
            if (!inflightRegistry.tryAcquire(model.getId(), limit)) {
                chain.add(gatewayLogger.buildChainEntry(route, "FULL",
                        "已满" + inflightRegistry.inFlight(model.getId()) + "/" + limit));
                continue;
            }
            anyAttempted = true;
            long start = System.currentTimeMillis();

            try {
                // 关键：将客户端请求的 model 替换为该路由目标（渠道下模型行）的真实模型名后再转发
                String upstreamBody = rebindUpstreamModel(rawBody, route.getModelName());
                String result = callFn.call(route, upstreamBody);
                long latencyMs = System.currentTimeMillis() - start;
                JSONObject usage = parseUsageFromResponse(result);
                onSuccessFn.onSuccess(route, upstreamBody, latencyMs, usage);

                chain.add(gatewayLogger.buildChainEntry(route, "OK", null));
                logCall("chat", channel, requestedModel, rawBody, stream,
                        "SUCCESS", route, System.currentTimeMillis() - totalStart, usage, chain);
                return result;
            } catch (Exception e) {
                lastError = resolveMessage(e);
                chain.add(gatewayLogger.buildChainEntry(route, "FAIL", lastError));
                bumpFailCount(route);
            } finally {
                inflightRegistry.release(model.getId());
            }
        }

        // 全部候选并发已满：退化为「在途最少」候选强制转发一单，避免请求被并发上限丢弃
        if (!anyAttempted) {
            UpstreamRoute route = candidates.stream()
                    .min(Comparator.comparingInt(r -> inflightRegistry.inFlight(r.getModel().getId())))
                    .orElse(null);
            if (route != null) {
                UpstreamModel model = route.getModel();
                inflightRegistry.forceAcquire(model.getId());
                long start = System.currentTimeMillis();
                try {
                    String upstreamBody = rebindUpstreamModel(rawBody, route.getModelName());
                    String result = callFn.call(route, upstreamBody);
                    long latencyMs = System.currentTimeMillis() - start;
                    JSONObject usage = parseUsageFromResponse(result);
                    onSuccessFn.onSuccess(route, upstreamBody, latencyMs, usage);

                    chain.add(gatewayLogger.buildChainEntry(route, "OK", null));
                    logCall("chat", channel, requestedModel, rawBody, stream,
                            "SUCCESS", route, System.currentTimeMillis() - totalStart, usage, chain);
                    return result;
                } catch (Exception e) {
                    lastError = resolveMessage(e);
                    chain.add(gatewayLogger.buildChainEntry(route, "FAIL", lastError));
                    bumpFailCount(route);
                } finally {
                    inflightRegistry.release(model.getId());
                }
            }
        }

        logCall("chat", channel, requestedModel, rawBody, stream,
                "ALL_FAILED", null, System.currentTimeMillis() - totalStart, null, chain);
        throw new BadRequestException("所有上游调用失败: " + lastError);
    }

    /**
     * 带故障转移的重试执行框架（流式）
     *
     * @param channel        对客通道信息
     * @param requestedModel 客户端请求的模型名
     * @param rawBody        原始请求体
     * @param candidates     上游候选列表
     * @param streamCallFn   流式上游调用函数
     * @param onChunk        数据块回调
     */
    private void executeWithFailoverStream(ModelChannel channel, String requestedModel, String rawBody,
                                            List<UpstreamRoute> candidates,
                                            StreamCallFn streamCallFn, Consumer<byte[]> onChunk) {
        List<String> chain = new ArrayList<>();
        long totalStart = System.currentTimeMillis();
        String lastError = "";
        boolean anyAttempted = false;

        for (int i = 0; i < candidates.size(); i++) {
            UpstreamRoute route = candidates.get(i);
            UpstreamModel model = route.getModel();
            int limit = maxConcurrencyOf(model);

            // 并发上限检查：在途已达上限则跳过该候选，故障转移到下一优先级
            if (!inflightRegistry.tryAcquire(model.getId(), limit)) {
                chain.add(gatewayLogger.buildChainEntry(route, "FULL",
                        "已满" + inflightRegistry.inFlight(model.getId()) + "/" + limit));
                continue;
            }
            anyAttempted = true;
            long start = System.currentTimeMillis();

            try {
                // 关键：将客户端请求的 model 替换为该路由目标（渠道下模型行）的真实模型名后再转发
                String upstreamBody = rebindUpstreamModel(rawBody, route.getModelName());
                boolean complete = streamCallFn.streamCall(route, upstreamBody, onChunk);
                long latencyMs = System.currentTimeMillis() - start;
                String usageJson = extractUsageFromStream();
                JSONObject usage = parseUsageFromResponse(usageJson);

                String status = complete ? "OK" : "BROKEN";
                String logResult = complete ? "SUCCESS" : "INTERRUPTED";
                chain.add(gatewayLogger.buildChainEntry(route, status, null));
                logCall("chat", channel, requestedModel, rawBody, true,
                        logResult, route, System.currentTimeMillis() - totalStart, usage, chain);
                    recordCallLog(channel, route, upstreamBody, usage, latencyMs, 200);
                return;
            } catch (Exception e) {
                lastError = resolveMessage(e);
                chain.add(gatewayLogger.buildChainEntry(route, "FAIL", lastError));
                bumpFailCount(route);
            } finally {
                inflightRegistry.release(model.getId());
            }
        }

        // 全部候选并发已满：退化为「在途最少」候选强制转发一单，避免请求被并发上限丢弃
        if (!anyAttempted) {
            UpstreamRoute route = candidates.stream()
                    .min(Comparator.comparingInt(r -> inflightRegistry.inFlight(r.getModel().getId())))
                    .orElse(null);
            if (route != null) {
                UpstreamModel model = route.getModel();
                inflightRegistry.forceAcquire(model.getId());
                long start = System.currentTimeMillis();
                try {
                    String upstreamBody = rebindUpstreamModel(rawBody, route.getModelName());
                    boolean complete = streamCallFn.streamCall(route, upstreamBody, onChunk);
                    long latencyMs = System.currentTimeMillis() - start;
                    String usageJson = extractUsageFromStream();
                    JSONObject usage = parseUsageFromResponse(usageJson);

                    String status = complete ? "OK" : "BROKEN";
                    String logResult = complete ? "SUCCESS" : "INTERRUPTED";
                    chain.add(gatewayLogger.buildChainEntry(route, status, null));
                    logCall("chat", channel, requestedModel, rawBody, true,
                            logResult, route, System.currentTimeMillis() - totalStart, usage, chain);
                recordCallLog(channel, route, upstreamBody, usage, latencyMs, 200);
                    return;
                } catch (Exception e) {
                    lastError = resolveMessage(e);
                    chain.add(gatewayLogger.buildChainEntry(route, "FAIL", lastError));
                    bumpFailCount(route);
                } finally {
                    inflightRegistry.release(model.getId());
                }
            }
        }

        logCall("chat", channel, requestedModel, rawBody, true,
                "ALL_FAILED", null, System.currentTimeMillis() - totalStart, null, chain);
        throw new BadRequestException("所有上游流式调用失败: " + lastError);
    }

    /**
     * 记录调用日志到 x_gate_call_log 表
     *
     * @param channel     对客通道信息
     * @param route       最终选中的路由目标（渠道 + 模型行）
     * @param requestBody 原始请求体摘要
     * @param usage       上游返回的 Token 用量（已解析，可能为 null）
     * @param latencyMs   端到端耗时（毫秒）
     * @param httpStatus  HTTP 状态码
     */
    private void recordCallLog(ModelChannel channel, UpstreamRoute route,
                               String requestBody, JSONObject usage, long latencyMs, int httpStatus) {
        submitLog(() -> {
            try {
                CallLog entry = gatewayLogger.buildCallLogEntry(
                        channel, route, requestBody, usage, latencyMs, httpStatus);
                callLogDao.insert(entry);
            } catch (Exception e) {
                log.error("记录调用日志失败, channel: {}, provider: {}",
                        channel.getPublicModelName(), route.getProvider().getName(), e);
            }
        });
    }

    private void logCall(String type, ModelChannel channel, String requestedModel,
                         String rawBody, boolean stream, String result,
                         UpstreamRoute finalRoute, long costMs,
                         JSONObject usage, List<String> chain) {
        submitLog(() -> gatewayLogger.logCall(type, channel, requestedModel, rawBody, stream,
                result, finalRoute, costMs, usage, chain));
    }

    /**
     * 提交日志任务到独立线程池，传递当前线程的 traceId 保证链路标识不丢失；
     * 线程池关闭或队列满时静默丢弃，不影响主流程。
     */
    private void submitLog(Runnable task) {
        final String traceId = GatewayLog.getMdc(GatewayLog.MDC_TRACE_ID);
        try {
            logExecutor.submit(() -> {
                if (traceId != null) {
                    MDC.put(GatewayLog.MDC_TRACE_ID, traceId);
                }
                try {
                    task.run();
                } finally {
                    MDC.remove(GatewayLog.MDC_TRACE_ID);
                }
            });
        } catch (Exception ignored) {
            // 日志线程池已关闭或队列满被拒绝，静默丢弃
        }
    }

    /**
     * 取模型行的并发上限；null 或 {@code <= 0} 视为不限制（返回 0）
     */
    private int maxConcurrencyOf(UpstreamModel model) {
        if (model == null || model.getMaxConcurrency() == null) {
            return 0;
        }
        return Math.max(model.getMaxConcurrency(), 0);
    }

    /**
     * 原子自增指定模型行的 fail_count，失败时静默忽略
     */
    private void bumpFailCount(UpstreamRoute route) {
        try {
            UpstreamModel model = route.getModel();
            if (model == null || model.getId() == null) return;
            upstreamModelDao.incrementFailCount(model.getId());
        } catch (Exception e) {
            log.warn("更新 fail_count 失败, model: {}, channel: {}",
                    route.getModelName(), route.getProvider().getName(), e);
        }
    }

    /**
     * 从上游响应 JSON 中提取 usage 对象
     */
    private JSONObject parseUsageFromResponse(String json) {
        try {
            JSONObject resp = JSON.parseObject(json);
            return resp == null ? null : resp.getJSONObject("usage");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从 ThreadLocal 中取出当前流的 usage chunk，并清理 ThreadLocal
     */
    private String extractUsageFromStream() {
        ProxyAdapter.StreamResult result = ProxyAdapter.getStreamResult();
        if (result == null) return null;
        String usage = result.usageChunkJson;
        ProxyAdapter.clearStreamResult();
        return usage;
    }

    /**
     * 解析异常链最深层的 message
     */
    private String resolveMessage(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return StrUtil.isBlank(message) ? cause.getClass().getSimpleName() : message;
    }

    /** 上游非流式调用函数式接口 */
    @FunctionalInterface
    private interface UpstreamCallFn {
        String call(UpstreamRoute route, String body) throws IOException;
    }

    /** 调用成功后的回调函数式接口 */
    @FunctionalInterface
    private interface OnSuccessFn {
        void onSuccess(UpstreamRoute route, String body, long latencyMs, JSONObject usage);
    }

    /** 流式调用函数式接口 */
    @FunctionalInterface
    private interface StreamCallFn {
        boolean streamCall(UpstreamRoute route, String body, Consumer<byte[]> onChunk) throws IOException;
    }
}
