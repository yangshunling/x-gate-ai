package com.xgateai.service.log;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xgateai.constant.CommonConstant;
import com.xgateai.dto.LogQueryDTO;
import com.xgateai.entity.CallLog;
import com.xgateai.entity.ModelChannel;
import com.xgateai.entity.UpstreamModel;
import com.xgateai.entity.UpstreamProvider;
import com.xgateai.mapper.ICallLogDao;
import com.xgateai.mapper.IModelChannelDao;
import com.xgateai.mapper.IUpstreamModelDao;
import com.xgateai.mapper.IUpstreamProviderDao;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * CallLogAnalysisServiceImpl 调用日志统计分析服务实现
 * <p>
 * 负责调用日志的分页查询、聚合统计和仪表盘数据计算。
 * 查询条件均通过 MyBatis-Plus 动态构造，避免字符串拼接 SQL。
 * </p>
 *
 * @author xgateai
 * @since 2026/9/8
 */
@Slf4j
@Service
public class CallLogAnalysisServiceImpl implements CallLogAnalysisService {

    private final ICallLogDao callLogDao;
    private final IModelChannelDao modelChannelDao;
    private final IUpstreamProviderDao upstreamProviderDao;
    private final IUpstreamModelDao upstreamModelDao;

    public CallLogAnalysisServiceImpl(ICallLogDao callLogDao,
                                      IModelChannelDao modelChannelDao,
                                      IUpstreamProviderDao upstreamProviderDao,
                                      IUpstreamModelDao upstreamModelDao) {
        this.callLogDao = callLogDao;
        this.modelChannelDao = modelChannelDao;
        this.upstreamProviderDao = upstreamProviderDao;
        this.upstreamModelDao = upstreamModelDao;
    }

    @Override
    public Page<CallLog> queryLogs(LogQueryDTO query) {
        int pageNum = Math.max(ObjectUtil.defaultIfNull(query.getPageNum(), 1), 1);
        int pageSize = Math.max(ObjectUtil.defaultIfNull(query.getPageSize(), 20), 1);

        LambdaQueryWrapper<CallLog> wrapper = new LambdaQueryWrapper<CallLog>()
                .eq(StrUtil.isNotBlank(query.getPublicModel()), CallLog::getPublicModel, query.getPublicModel())
                .eq(StrUtil.isNotBlank(query.getApiKeyName()), CallLog::getApiKey, query.getApiKeyName())
                .eq(StrUtil.isNotBlank(query.getModel()), CallLog::getUpstreamModel, query.getModel())
                .ge(StrUtil.isNotBlank(query.getDateFrom()), CallLog::getCreatedAt, query.getDateFrom() + " 00:00:00")
                .le(StrUtil.isNotBlank(query.getDateTo()), CallLog::getCreatedAt, query.getDateTo() + " 23:59:59")
                .eq(query.getStatus() != null && query.getStatus() > 0, CallLog::getHttpStatus, query.getStatus())
                .orderByDesc(CallLog::getId);

        return callLogDao.selectPage(new Page<>(pageNum, pageSize), wrapper);
    }

    @Override
    public List<Map<String, Object>> analyzeByModel() {
        QueryWrapper<CallLog> wrapper = new QueryWrapper<CallLog>()
                .select("upstream_model AS model",
                        "COUNT(*) AS calls",
                        "SUM(CASE WHEN http_status >= 200 AND http_status < 400 THEN 1 ELSE 0 END) AS success_calls",
                        "COALESCE(SUM(input_tokens), 0) AS input_tokens",
                        "COALESCE(SUM(output_tokens), 0) AS output_tokens",
                        "COALESCE(AVG(latency_ms), 0) AS avg_latency_ms")
                .groupBy("upstream_model")
                .orderByDesc("calls");

        return callLogDao.selectMaps(wrapper).stream()
                .map(row -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("model", toString(row.get("model")));
                    result.put("calls", toLong(row.get("calls")));
                    result.put("successCalls", toLong(row.get("success_calls")));
                    result.put("inputTokens", toLong(row.get("input_tokens")));
                    result.put("outputTokens", toLong(row.get("output_tokens")));
                    result.put("avgLatencyMs", Math.round(toDouble(row.get("avg_latency_ms"))));
                    return result;
                })
                .collect(Collectors.toList());
    }

    @Override
    public List<Map<String, Object>> analyzeFailuresByModel() {
        // 仅统计启用渠道下启用模型的失败次数
        List<Long> enabledChannelIds = upstreamProviderDao.selectList(
                new LambdaQueryWrapper<UpstreamProvider>()
                        .select(UpstreamProvider::getId)
                        .eq(UpstreamProvider::getEnabled, CommonConstant.ENABLED))
                .stream()
                .map(UpstreamProvider::getId)
                .collect(Collectors.toList());

        if (enabledChannelIds.isEmpty()) {
            return Collections.emptyList();
        }

        QueryWrapper<UpstreamModel> wrapper = new QueryWrapper<UpstreamModel>()
                .select("model_name AS model", "COALESCE(SUM(fail_count), 0) AS failCount")
                .eq("enabled", CommonConstant.ENABLED)
                .in("channel_id", enabledChannelIds)
                .ne("model_name", "")
                .isNotNull("model_name")
                .groupBy("model_name")
                .orderByDesc("failCount")
                .orderByAsc("model_name");

        return upstreamModelDao.selectMaps(wrapper).stream()
                .map(row -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("model", toString(row.get("model")));
                    result.put("failCount", toLong(row.get("failCount")));
                    return result;
                })
                .collect(Collectors.toList());
    }

    @Override
    public List<Map<String, Object>> analyzeWeightByProvider() {
        // 仅展示启用渠道下启用模型的权重信息
        List<UpstreamModel> models = upstreamModelDao.selectList(
                new LambdaQueryWrapper<UpstreamModel>()
                        .eq(UpstreamModel::getEnabled, CommonConstant.ENABLED)
                        .ne(UpstreamModel::getModelName, "")
                        .isNotNull(UpstreamModel::getModelName)
                        .orderByAsc(UpstreamModel::getFailCount)
                        .orderByAsc(UpstreamModel::getId));

        Map<Long, String> channelNames = new HashMap<>();
        for (UpstreamProvider provider : upstreamProviderDao.selectList(
                new LambdaQueryWrapper<UpstreamProvider>()
                        .eq(UpstreamProvider::getEnabled, CommonConstant.ENABLED))) {
            channelNames.put(provider.getId(), provider.getName());
        }

        return models.stream()
                .filter(m -> channelNames.containsKey(m.getChannelId()))
                .map(m -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("name", channelNames.get(m.getChannelId()));
                    result.put("model", m.getModelName());
                    result.put("failCount", defaultIfNull(m.getFailCount(), 0));
                    return result;
                })
                .collect(Collectors.toList());
    }

    @Override
    public Map<String, Object> dashboardStats() {
        Map<String, Object> stats = new LinkedHashMap<>();

        // 历史累计调用统计
        long totalCalls = callLogDao.selectCount(new QueryWrapper<CallLog>());
        long totalSuccess = callLogDao.selectCount(
                new QueryWrapper<CallLog>()
                        .ge("http_status", CommonConstant.HTTP_OK)
                        .lt("http_status", CommonConstant.HTTP_BAD_REQUEST));
        stats.put("totalCalls", totalCalls);
        stats.put("totalSuccess", totalSuccess);
        stats.put("totalFail", totalCalls - totalSuccess);

        // 历史累计 Token 汇总
        List<CallLog> allLogs = callLogDao.selectList(
                new QueryWrapper<CallLog>().select("input_tokens", "output_tokens"));
        int totalInputTokens = allLogs.stream()
                .mapToInt(log -> defaultIfNull(log.getInputTokens(), 0)).sum();
        int totalOutputTokens = allLogs.stream()
                .mapToInt(log -> defaultIfNull(log.getOutputTokens(), 0)).sum();
        stats.put("totalInputTokens", totalInputTokens);
        stats.put("totalOutputTokens", totalOutputTokens);

        // 今日统计
        String todayStart = DateUtil.format(DateUtil.beginOfDay(new Date()), CommonConstant.DATETIME_FORMAT);
        QueryWrapper<CallLog> todayWrapper = new QueryWrapper<CallLog>().ge("created_at", todayStart);
        long todayCalls = callLogDao.selectCount(todayWrapper);
        long todaySuccess = callLogDao.selectCount(
                new QueryWrapper<CallLog>()
                        .ge("created_at", todayStart)
                        .ge("http_status", CommonConstant.HTTP_OK)
                        .lt("http_status", CommonConstant.HTTP_BAD_REQUEST));
        stats.put("todayCalls", todayCalls);
        stats.put("todaySuccess", todaySuccess);
        stats.put("todayFail", todayCalls - todaySuccess);

        List<CallLog> todayLogs = callLogDao.selectList(todayWrapper);
        int todayInputTokens = todayLogs.stream()
                .mapToInt(log -> defaultIfNull(log.getInputTokens(), 0)).sum();
        int todayOutputTokens = todayLogs.stream()
                .mapToInt(log -> defaultIfNull(log.getOutputTokens(), 0)).sum();
        stats.put("todayInputTokens", todayInputTokens);
        stats.put("todayOutputTokens", todayOutputTokens);

        // 客户总数（已接入的对外客户数，取自 model_channels）与启用资源数
        long totalCustomers = modelChannelDao.selectCount(null);
        long enabledChannels = modelChannelDao.selectCount(
                new QueryWrapper<ModelChannel>().eq("enabled", CommonConstant.ENABLED));
        long enabledProviders = upstreamProviderDao.selectCount(
                new QueryWrapper<UpstreamProvider>().eq("enabled", CommonConstant.ENABLED));
        stats.put("totalCustomers", totalCustomers);
        stats.put("enabledChannels", enabledChannels);
        stats.put("enabledProviders", enabledProviders);

        return stats;
    }

    @Override
    public List<Map<String, Object>> topCustomersToday() {
        String todayStart = DateUtil.format(DateUtil.beginOfDay(new Date()), CommonConstant.DATETIME_FORMAT);

        QueryWrapper<CallLog> wrapper = new QueryWrapper<CallLog>()
                .select("customer_name AS customerName",
                        "COUNT(*) AS calls",
                        "COALESCE(SUM(input_tokens), 0) AS inputTokens",
                        "COALESCE(SUM(output_tokens), 0) AS outputTokens")
                .ge("created_at", todayStart)
                .ne("customer_name", "")
                .isNotNull("customer_name")
                .groupBy("customer_name")
                .orderByDesc("calls")
                .last("LIMIT 5");

        return callLogDao.selectMaps(wrapper).stream()
                .map(row -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("customerName", toString(row.get("customerName")));
                    result.put("calls", toLong(row.get("calls")));
                    result.put("inputTokens", toLong(row.get("inputTokens")));
                    result.put("outputTokens", toLong(row.get("outputTokens")));
                    return result;
                })
                .collect(Collectors.toList());
    }

    // ==================== 私有辅助方法 ====================

    private String toString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private long toLong(Object value) {
        if (value == null) return 0;
        if (value instanceof Number num) return num.longValue();
        return Long.parseLong(String.valueOf(value));
    }

    private double toDouble(Object value) {
        if (value == null) return 0.0;
        if (value instanceof Number num) return num.doubleValue();
        return Double.parseDouble(String.valueOf(value));
    }

    /**
     * 若 value 为 null 则返回 defaultValue
     */
    private int defaultIfNull(Integer value, int defaultValue) {
        return value != null ? value : defaultValue;
    }
}
