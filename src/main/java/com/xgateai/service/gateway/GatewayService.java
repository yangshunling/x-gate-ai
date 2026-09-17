package com.xgateai.service.gateway;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xgateai.entity.CallLog;
import com.xgateai.entity.ModelChannel;
import com.xgateai.entity.UpstreamModel;
import com.xgateai.entity.UpstreamProvider;
import com.xgateai.entity.UpstreamRoute;
import com.xgateai.constant.CommonConstant;
import com.xgateai.constant.GatewayConstant;
import com.xgateai.exception.BadRequestException;
import com.xgateai.exception.UpstreamException;
import com.xgateai.adapter.ProxyAdapter;
import com.xgateai.component.InflightRegistry;
import com.xgateai.config.GatewayConfig;
import com.xgateai.logging.GatewayLog;
import com.xgateai.logging.GatewayLogger;
import com.xgateai.mapper.ICallLogDao;
import com.xgateai.mapper.IUpstreamModelDao;
import com.xgateai.mapper.IUpstreamProviderDao;
import com.xgateai.service.gateway.strategy.UpstreamStrategy;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

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
    private final IUpstreamProviderDao upstreamProviderDao;
    private final InflightRegistry inflightRegistry;
    private final GatewayConfig gatewayConfig;
    private ExecutorService logExecutor;

    public GatewayService(ProxyAdapter proxyAdapter,
                          UpstreamStrategy upstreamStrategy,
                          GatewayLogger gatewayLogger,
                          ICallLogDao callLogDao,
                          IUpstreamModelDao upstreamModelDao,
                          IUpstreamProviderDao upstreamProviderDao,
                          InflightRegistry inflightRegistry,
                          GatewayConfig gatewayConfig) {
        this.proxyAdapter = proxyAdapter;
        this.upstreamStrategy = upstreamStrategy;
        this.gatewayLogger = gatewayLogger;
        this.callLogDao = callLogDao;
        this.upstreamModelDao = upstreamModelDao;
        this.upstreamProviderDao = upstreamProviderDao;
        this.inflightRegistry = inflightRegistry;
        this.gatewayConfig = gatewayConfig;
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
                (route, body, onChunk) -> {
                    String resp = proxyAdapter.chat(route.getProvider(), body);
                    return new CallResult(resp, true, resp);
                },
                null);
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
        executeWithFailover(channel, requestedModel, rawBody, true, candidates,
                (route, body, chunkConsumer) -> {
                    ProxyAdapter.StreamResult sr = proxyAdapter.streamChat(
                            route.getProvider(), body, chunkConsumer);
                    return new CallResult(null, sr.complete, sr.usageChunkJson);
                },
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
                (route, body, onChunk) -> {
                    String resp = proxyAdapter.embeddings(route.getProvider(), body);
                    return new CallResult(resp, true, resp);
                },
                null);
    }

    // ==================== 模型列表 ====================

    /**
     * 查询池内所有启用的模型列表（OpenAI 兼容格式）
     * <p>
     * 仅返回启用渠道下的启用模型，按模型名去重。
     * {@code owned_by} 统一为网关品牌名，不向上游调用方暴露真实供应商
     * （如商汤科技、DeepSeek、OpenAI）。{@code auto} 是全池路由策略关键字，
     * 非模型，不在列表中返回。
     * </p>
     *
     * @return 模型信息列表，每个元素包含 id/object/created/owned_by
     */
    public List<JSONObject> listAvailableModels() {
        List<UpstreamModel> models = upstreamModelDao.selectList(
                new LambdaQueryWrapper<UpstreamModel>()
                        .eq(UpstreamModel::getEnabled, CommonConstant.ENABLED)
                        .orderByAsc(UpstreamModel::getModelName));
        if (models.isEmpty()) {
            return Collections.emptyList();
        }

        Map<Long, UpstreamProvider> providerMap = loadEnabledProviders();
        if (providerMap.isEmpty()) {
            return Collections.emptyList();
        }

        // 按模型名去重，组装 OpenAI 兼容格式
        LinkedHashMap<String, JSONObject> dedup = new LinkedHashMap<>();
        for (UpstreamModel model : models) {
            UpstreamProvider provider = providerMap.get(model.getChannelId());
            if (provider == null) {
                continue;
            }
            String modelName = model.getModelName();
            if (dedup.containsKey(modelName)) {
                continue;
            }
            JSONObject entry = new JSONObject();
            entry.put("id", modelName);
            entry.put("object", "model");
            entry.put("created", parseCreatedToEpoch(model.getCreatedAt()));
            entry.put("owned_by", gatewayConfig.getBrandName());
            dedup.put(modelName, entry);
        }
        return new ArrayList<>(dedup.values());
    }

    /**
     * 装载所有启用的渠道，以 id 为键
     */
    private Map<Long, UpstreamProvider> loadEnabledProviders() {
        List<UpstreamProvider> providers = upstreamProviderDao.selectList(
                new LambdaQueryWrapper<UpstreamProvider>()
                        .eq(UpstreamProvider::getEnabled, CommonConstant.ENABLED));
        return providers.stream()
                .collect(Collectors.toMap(UpstreamProvider::getId, p -> p, (a, b) -> a, HashMap::new));
    }

    /**
     * 将 createdAt 字符串解析为 epoch 秒，解析失败返回 0
     */
    private long parseCreatedToEpoch(String createdAt) {
        if (StrUtil.isBlank(createdAt)) {
            return 0;
        }
        try {
            return LocalDateTime.parse(createdAt,
                    DateTimeFormatter.ofPattern(CommonConstant.DATETIME_FORMAT))
                    .atZone(ZoneId.systemDefault())
                    .toEpochSecond();
        } catch (Exception e) {
            return 0;
        }
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
     * 统一的故障转移执行框架（流式与非流式共用）
     *
     * @param channel        对客通道信息
     * @param requestedModel 客户端请求的模型名
     * @param rawBody        原始请求体
     * @param stream         是否流式（用于日志标记和结果处理）
     * @param candidates     上游候选列表
     * @param callFn         上游调用函数，返回 CallResult
     * @param onChunk        流式数据块回调（非流式时为 null）
     * @return               非流式返回上游响应 JSON；流式返回 null（数据已通过 onChunk 写出）
     * @throws BadRequestException 当所有上游候选均调用失败时抛出
     */
    private String executeWithFailover(ModelChannel channel, String requestedModel, String rawBody,
                                        boolean stream, List<UpstreamRoute> candidates,
                                        UpstreamCall callFn, Consumer<byte[]> onChunk) {
        List<String> chain = new ArrayList<>();
        long totalStart = System.currentTimeMillis();
        String lastError = "";
        UpstreamRoute lastRoute = null;
        int lastStatus = CommonConstant.HTTP_INTERNAL_SERVER_ERROR;
        boolean anyAttempted = false;

        for (UpstreamRoute route : candidates) {
            UpstreamModel model = route.getModel();
            int limit = maxConcurrencyOf(model);

            if (!inflightRegistry.tryAcquire(model.getId(), limit)) {
                chain.add(gatewayLogger.buildChainEntry(route, "FULL",
                        "已满" + inflightRegistry.inFlight(model.getId()) + "/" + limit));
                continue;
            }
            anyAttempted = true;
            lastRoute = route;
            try {
                return attemptCandidate(route, channel, requestedModel, rawBody,
                        stream, callFn, onChunk, chain, totalStart);
            } catch (Exception e) {
                lastError = resolveMessage(e);
                lastStatus = resolveUpstreamStatus(e);
                chain.add(gatewayLogger.buildChainEntry(route, "FAIL", lastError));
                bumpFailCount(route);
            } finally {
                inflightRegistry.release(model.getId());
            }
        }

        // 全部候选并发已满：退化为「在途最少」候选强制转发一单
        if (!anyAttempted) {
            UpstreamRoute route = candidates.stream()
                    .min(Comparator.comparingInt(r -> inflightRegistry.inFlight(r.getModel().getId())))
                    .orElse(null);
            if (route != null) {
                UpstreamModel model = route.getModel();
                inflightRegistry.forceAcquire(model.getId());
                lastRoute = route;
                try {
                    return attemptCandidate(route, channel, requestedModel, rawBody,
                            stream, callFn, onChunk, chain, totalStart);
                } catch (Exception e) {
                    lastError = resolveMessage(e);
                    lastStatus = resolveUpstreamStatus(e);
                    chain.add(gatewayLogger.buildChainEntry(route, "FAIL", lastError));
                    bumpFailCount(route);
                } finally {
                    inflightRegistry.release(model.getId());
                }
            }
        }

        // 全链路失败：以最后一个候选的上游状态码落库
        if (lastRoute != null) {
            recordCallLog(channel, lastRoute, rawBody, null,
                    System.currentTimeMillis() - totalStart, lastStatus);
        }
        logCall("chat", channel, requestedModel, rawBody, stream,
                "ALL_FAILED", null, System.currentTimeMillis() - totalStart, null, chain);
        throw new BadRequestException("所有上游调用失败: " + lastError);
    }

    /**
     * 尝试单个候选的上游调用
     * <p>
     * 成功或流式中断时返回结果（流式返回 null），失败时抛出异常由调用方处理故障转移。
     * </p>
     *
     * @return 非流式返回响应 JSON；流式返回 null（数据已通过 onChunk 写出）
     * @throws Exception 上游调用失败时抛出
     */
    private String attemptCandidate(UpstreamRoute route, ModelChannel channel, String requestedModel,
                                     String rawBody, boolean stream, UpstreamCall callFn,
                                     Consumer<byte[]> onChunk, List<String> chain, long totalStart) throws IOException {
        long start = System.currentTimeMillis();
        String upstreamBody = rebindUpstreamModel(rawBody, route.getModelName());
        CallResult callResult = callFn.call(route, upstreamBody, onChunk);
        long latencyMs = System.currentTimeMillis() - start;
        JSONObject usage = parseUsageFromResponse(callResult.usageJson);

        boolean ok = !stream || callResult.streamComplete;
        String status = ok ? "OK" : "BROKEN";
        String logResult = ok ? "SUCCESS" : "INTERRUPTED";
        chain.add(gatewayLogger.buildChainEntry(route, status, null));
        logCall("chat", channel, requestedModel, rawBody, stream,
                logResult, route, System.currentTimeMillis() - totalStart, usage, chain);
        recordCallLog(channel, route, upstreamBody, usage, latencyMs, 200);
        return stream ? null : callResult.responseJson;
    }

    /**
     * 记录调用日志到 call_log 表
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

    /**
     * 解析异常链最深处的上游 HTTP 状态码，非上游异常按 500 处理
     */
    private int resolveUpstreamStatus(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        if (cause instanceof UpstreamException upstreamException) {
            return upstreamException.getUpstreamHttpStatus();
        }
        return CommonConstant.HTTP_INTERNAL_SERVER_ERROR;
    }

    /** 上游调用函数式接口（流式与非流式统一） */
    @FunctionalInterface
    private interface UpstreamCall {
        CallResult call(UpstreamRoute route, String body, Consumer<byte[]> onChunk) throws IOException;
    }

    /** 上游调用结果容器 */
    private static class CallResult {
        /** 非流式响应 JSON（流式时为 null） */
        final String responseJson;
        /** 流式是否完整结束（非流式固定为 true） */
        final boolean streamComplete;
        /** 从响应或流中提取的 usage JSON 字符串 */
        final String usageJson;

        CallResult(String responseJson, boolean streamComplete, String usageJson) {
            this.responseJson = responseJson;
            this.streamComplete = streamComplete;
            this.usageJson = usageJson;
        }
    }
}
