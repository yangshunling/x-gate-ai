package com.xgateai.gatewaybridge.service;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xgateai.application.constant.CommonConstant;
import com.xgateai.application.entity.CallLog;
import com.xgateai.application.entity.ModelChannel;
import com.xgateai.application.entity.UpstreamProvider;
import com.xgateai.gatewaybridge.config.GatewayConfig;
import com.xgateai.mapper.CallLogMapper;
import com.xgateai.mapper.ModelChannelMapper;
import com.xgateai.mapper.UpstreamProviderMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * <p>
 * CallLogService 调用日志服务
 * </p>
 *
 * @author xgateai
 * @since 2026/9/4
 */
@Slf4j
@Service
public class CallLogService {

    /**
     * 调用日志 Mapper
     */
    @Resource
    CallLogMapper callLogMapper;

    /**
     * 网关配置
     */
    @Resource
    GatewayConfig gatewayConfig;

    /**
     * 对外模型通道 Mapper
     */
    @Resource
    ModelChannelMapper modelChannelMapper;

    /**
     * 上游 Provider Mapper
     */
    @Resource
    UpstreamProviderMapper upstreamProviderMapper;

    /**
     * 保存一条调用日志，createdAt 为空时默认取当前时间
     *
     * @param callLog 调用日志实体
     */
    public void save(CallLog callLog) {
        if (callLog.getCreatedAt() == null || StrUtil.isBlank(callLog.getCreatedAt())) {
            callLog.setCreatedAt(DateUtil.now());
        }
        callLogMapper.insert(callLog);
    }

    /**
     * 分页查询调用日志，支持多条件过滤，按 id 倒序返回
     *
     * @param publicModel 对外模型名（可选）
     * @param apiKeyName  API Key（可选）
     * @param model       上游真实模型名（可选）
     * @param dateFrom    起始日期 yyyy-MM-dd（可选）
     * @param dateTo      结束日期 yyyy-MM-dd（可选）
     * @param status      HTTP 状态码（可选）
     * @param pageNum     页码
     * @param pageSize    每页条数
     * @return 调用日志分页结果
     */
    public Page<CallLog> pageQuery(String publicModel, String apiKeyName, String model,
                                   String dateFrom, String dateTo, Integer status,
                                   int pageNum, int pageSize) {
        pageNum = pageNum < 1 ? 1 : pageNum;
        pageSize = pageSize < 1 ? 20 : pageSize;
        LambdaQueryWrapper<CallLog> wrapper = new LambdaQueryWrapper<CallLog>()
                .eq(StrUtil.isNotBlank(publicModel), CallLog::getPublicModel, publicModel)
                .eq(StrUtil.isNotBlank(apiKeyName), CallLog::getApiKey, apiKeyName)
                .eq(StrUtil.isNotBlank(model), CallLog::getUpstreamModel, model)
                .ge(StrUtil.isNotBlank(dateFrom), CallLog::getCreatedAt, dateFrom + " 00:00:00")
                .le(StrUtil.isNotBlank(dateTo), CallLog::getCreatedAt, dateTo + " 23:59:59")
                .eq(status != null && status > 0, CallLog::getHttpStatus, status)
                .orderByDesc(CallLog::getId);
        return callLogMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
    }

    /**
     * 按小时聚合 Token 用量趋势（最近 N 小时，含调用次数）
     *
     * @param hours 查询窗口小时数，默认 24
     * @return [{hour, calls, inputTokens, outputTokens}] 按时间升序
     */
    public List<JSONObject> tokenTrend(int hours) {
        if (hours <= 0) {
            hours = 24;
        }
        String start = DateUtil.format(DateUtil.offsetHour(new Date(), -hours), CommonConstant.DATETIME_FORMAT);
        QueryWrapper<CallLog> wrapper = new QueryWrapper<CallLog>()
                .select("substr(created_at, 1, 13) AS hour",
                        "COUNT(*) AS calls",
                        "COALESCE(SUM(input_tokens), 0) AS input_tokens",
                        "COALESCE(SUM(output_tokens), 0) AS output_tokens")
                .ge("created_at", start)
                .groupBy("hour")
                .orderByAsc("hour");
        List<Map<String, Object>> rows = callLogMapper.selectMaps(wrapper);
        List<JSONObject> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            JSONObject item = new JSONObject();
            item.put("hour", String.valueOf(row.get("hour")));
            item.put("calls", toLong(row.get("calls")));
            item.put("inputTokens", toLong(row.get("input_tokens")));
            item.put("outputTokens", toLong(row.get("output_tokens")));
            result.add(item);
        }
        return result;
    }

    /**
     * 按对外模型聚合统计（调用次数 / Token / 成功率 / 平均耗时）
     *
     * @return [{model, calls, successCalls, inputTokens, outputTokens, avgLatencyMs}] 按调用次数倒序
     */
    public List<JSONObject> modelStats() {
        QueryWrapper<CallLog> wrapper = new QueryWrapper<CallLog>()
                .select("public_model AS model",
                        "COUNT(*) AS calls",
                        "SUM(CASE WHEN http_status >= 200 AND http_status < 400 THEN 1 ELSE 0 END) AS success_calls",
                        "COALESCE(SUM(input_tokens), 0) AS input_tokens",
                        "COALESCE(SUM(output_tokens), 0) AS output_tokens",
                        "COALESCE(AVG(latency_ms), 0) AS avg_latency_ms")
                .groupBy("public_model")
                .orderByDesc("calls");
        List<Map<String, Object>> rows = callLogMapper.selectMaps(wrapper);
        List<JSONObject> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            JSONObject item = new JSONObject();
            item.put("model", String.valueOf(row.get("model")));
            item.put("calls", toLong(row.get("calls")));
            item.put("successCalls", toLong(row.get("success_calls")));
            item.put("inputTokens", toLong(row.get("input_tokens")));
            item.put("outputTokens", toLong(row.get("output_tokens")));
            item.put("avgLatencyMs", Math.round(toDouble(row.get("avg_latency_ms"))));
            result.add(item);
        }
        return result;
    }

    private long toLong(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private double toDouble(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.parseDouble(String.valueOf(value));
    }

    /**
     * 统计今日调用与各资源启用数量，用于控制台看板
     *
     * @return 看板统计 JSON：todayCalls/todaySuccess/todayFail/todayInputTokens/todayOutputTokens/
     * enabledChannels/enabledProviders
     */
    public JSONObject dashboard() {
        // 今日零点作为字符串起点（created_at 存储格式为 yyyy-MM-dd HH:mm:ss）
        String todayStart = DateUtil.format(DateUtil.beginOfDay(new Date()), CommonConstant.DATETIME_FORMAT);
        JSONObject result = new JSONObject();

        // 今日调用总数与成功数（httpStatus 落在 [200, 400) 视为成功）
        QueryWrapper<CallLog> todayWrapper = new QueryWrapper<CallLog>().ge("created_at", todayStart);
        Long todayCalls = callLogMapper.selectCount(todayWrapper);
        QueryWrapper<CallLog> successWrapper = new QueryWrapper<CallLog>()
                .ge("created_at", todayStart)
                .ge("http_status", CommonConstant.HTTP_OK)
                .lt("http_status", CommonConstant.HTTP_BAD_REQUEST);
        Long todaySuccess = callLogMapper.selectCount(successWrapper);
        result.put("todayCalls", todayCalls);
        result.put("todaySuccess", todaySuccess);
        result.put("todayFail", todayCalls - todaySuccess);

        // 今日 token 汇总（数据量小，全量查出后内存求和，简单可靠）
        List<CallLog> todayLogs = callLogMapper.selectList(todayWrapper);
        int todayInputTokens = todayLogs.stream()
                .mapToInt(l -> l.getInputTokens() == null ? 0 : l.getInputTokens())
                .sum();
        int todayOutputTokens = todayLogs.stream()
                .mapToInt(l -> l.getOutputTokens() == null ? 0 : l.getOutputTokens())
                .sum();
        result.put("todayInputTokens", todayInputTokens);
        result.put("todayOutputTokens", todayOutputTokens);

        // 各类资源启用数量
        Long enabledChannels = modelChannelMapper.selectCount(
                new QueryWrapper<ModelChannel>().eq("enabled", CommonConstant.ENABLED));
        Long enabledProviders = upstreamProviderMapper.selectCount(
                new QueryWrapper<UpstreamProvider>().eq("enabled", CommonConstant.ENABLED));
        result.put("enabledChannels", enabledChannels);
        result.put("enabledProviders", enabledProviders);

        return result;
    }
}
