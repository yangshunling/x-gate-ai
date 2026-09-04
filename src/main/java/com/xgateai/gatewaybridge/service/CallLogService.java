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

import java.util.Date;
import java.util.List;

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
     * 分页查询调用日志，可按对外模型名、API Key 精确过滤，并按 id 倒序返回
     *
     * @param publicModel 对外模型名（可选，为空则不过滤）
     * @param apiKeyName  API Key（可选，为空则不过滤）
     * @param pageNum     页码，小于 1 时取 1
     * @param pageSize    每页条数，小于 1 时取 20
     * @return 调用日志分页结果
     */
    public Page<CallLog> pageQuery(String publicModel, String apiKeyName, int pageNum, int pageSize) {
        pageNum = pageNum < 1 ? 1 : pageNum;
        pageSize = pageSize < 1 ? 20 : pageSize;
        LambdaQueryWrapper<CallLog> wrapper = new LambdaQueryWrapper<CallLog>()
                .eq(StrUtil.isNotBlank(publicModel), CallLog::getPublicModel, publicModel)
                .eq(StrUtil.isNotBlank(apiKeyName), CallLog::getApiKey, apiKeyName)
                .orderByDesc(CallLog::getId);
        return callLogMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
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
