package com.xgateai.service.log;

import cn.hutool.core.date.DateUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xgateai.constant.CommonConstant;
import com.xgateai.entity.CallLog;
import com.xgateai.entity.ModelChannel;
import com.xgateai.entity.UpstreamProvider;
import com.xgateai.dto.LogQueryDTO;
import com.xgateai.mapper.ICallLogDao;
import com.xgateai.mapper.IModelChannelDao;
import com.xgateai.mapper.IUpstreamProviderDao;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * CallLogAnalysisServiceImpl 调用日志统计分析服务实现
 * <p>
 * 负责调用日志的分页查询、聚合统计和仪表盘数据计算。
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

    public CallLogAnalysisServiceImpl(ICallLogDao callLogDao,
                                      IModelChannelDao modelChannelDao,
                                      IUpstreamProviderDao upstreamProviderDao) {
        this.callLogDao = callLogDao;
        this.modelChannelDao = modelChannelDao;
        this.upstreamProviderDao = upstreamProviderDao;
    }

    @Override
    public Page<CallLog> queryLogs(LogQueryDTO query) {
        int pageNum = Math.max(query.getPageNum() != null ? query.getPageNum() : 1, 1);
        int pageSize = Math.max(query.getPageSize() != null ? query.getPageSize() : 20, 1);

        LambdaQueryWrapper<CallLog> wrapper = new LambdaQueryWrapper<CallLog>()
                .eq(isNotBlank(query.getPublicModel()), CallLog::getPublicModel, query.getPublicModel())
                .eq(isNotBlank(query.getApiKeyName()), CallLog::getApiKey, query.getApiKeyName())
                .eq(isNotBlank(query.getModel()), CallLog::getUpstreamModel, query.getModel())
                .ge(isNotBlank(query.getDateFrom()), CallLog::getCreatedAt, query.getDateFrom() + " 00:00:00")
                .le(isNotBlank(query.getDateTo()), CallLog::getCreatedAt, query.getDateTo() + " 23:59:59")
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

        List<Map<String, Object>> rows = callLogDao.selectMaps(wrapper);
        return rows.stream().map(row -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("model", toString(row.get("model")));
            result.put("calls", toLong(row.get("calls")));
            result.put("successCalls", toLong(row.get("success_calls")));
            result.put("inputTokens", toLong(row.get("input_tokens")));
            result.put("outputTokens", toLong(row.get("output_tokens")));
            result.put("avgLatencyMs", Math.round(toDouble(row.get("avg_latency_ms"))));
            return result;
        }).collect(Collectors.toList());
    }

    @Override
    public List<Map<String, Object>> analyzeFailuresByModel() {
        QueryWrapper<UpstreamProvider> wrapper = new QueryWrapper<UpstreamProvider>()
                .select("model_name AS model", "fail_count AS failCount")
                .isNotNull("model_name")
                .ne("model_name", "")
                .orderByDesc("fail_count")
                .orderByAsc("model_name");

        return upstreamProviderDao.selectList(wrapper).stream()
                .map(p -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("model", p.getModelName());
                    result.put("failCount", p.getFailCount() == null ? 0 : p.getFailCount());
                    return result;
                })
                .collect(Collectors.toList());
    }

    @Override
    public List<Map<String, Object>> analyzeWeightByProvider() {
        QueryWrapper<UpstreamProvider> wrapper = new QueryWrapper<UpstreamProvider>()
                .select("name", "model_name", "fail_count")
                .ne("model_name", "")
                .isNotNull("model_name")
                .orderByAsc("fail_count")
                .orderByAsc("name");

        return upstreamProviderDao.selectList(wrapper).stream()
                .map(p -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("name", p.getName());
                    result.put("model", p.getModelName());
                    result.put("failCount", p.getFailCount() == null ? 0 : p.getFailCount());
                    return result;
                })
                .collect(Collectors.toList());
    }

    @Override
    public Map<String, Object> dashboardStats() {
        Map<String, Object> stats = new LinkedHashMap<>();

        // 历史累计统计
        long totalCalls = callLogDao.selectCount(new QueryWrapper<CallLog>());
        long totalSuccess = callLogDao.selectCount(
                new QueryWrapper<CallLog>()
                        .ge("http_status", CommonConstant.HTTP_OK)
                        .lt("http_status", CommonConstant.HTTP_BAD_REQUEST));
        stats.put("totalCalls", totalCalls);
        stats.put("totalSuccess", totalSuccess);
        stats.put("totalFail", totalCalls - totalSuccess);

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

        // 今日 Token 汇总
        List<CallLog> todayLogs = callLogDao.selectList(todayWrapper);
        int todayInputTokens = todayLogs.stream()
                .mapToInt(log -> log.getInputTokens() == null ? 0 : log.getInputTokens())
                .sum();
        int todayOutputTokens = todayLogs.stream()
                .mapToInt(log -> log.getOutputTokens() == null ? 0 : log.getOutputTokens())
                .sum();
        stats.put("todayInputTokens", todayInputTokens);
        stats.put("todayOutputTokens", todayOutputTokens);

        // 资源启用数量
        long enabledChannels = modelChannelDao.selectCount(
                new QueryWrapper<ModelChannel>().eq("enabled", CommonConstant.ENABLED));
        long enabledProviders = upstreamProviderDao.selectCount(
                new QueryWrapper<UpstreamProvider>().eq("enabled", CommonConstant.ENABLED));
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

        List<Map<String, Object>> rows = callLogDao.selectMaps(wrapper);
        return rows.stream().map(row -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("customerName", toString(row.get("customerName")));
            result.put("calls", toLong(row.get("calls")));
            result.put("inputTokens", toLong(row.get("inputTokens")));
            result.put("outputTokens", toLong(row.get("outputTokens")));
            return result;
        }).collect(Collectors.toList());
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

    private boolean isNotBlank(String str) {
        return str != null && !str.isBlank();
    }
}
