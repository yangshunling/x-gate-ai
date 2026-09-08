package com.xgateai.service.log;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xgateai.entity.CallLog;
import com.xgateai.dto.LogQueryDTO;

import java.util.List;
import java.util.Map;

/**
 * CallLogAnalysisService 调用日志统计分析服务接口
 * <p>
 * 定义调用日志的查询与统计分析契约。
 * </p>
 *
 * @author xgateai
 * @since 2026/9/8
 */
public interface CallLogAnalysisService {

    /**
     * 分页查询调用日志
     */
    Page<CallLog> queryLogs(LogQueryDTO query);

    /**
     * 按上游模型聚合统计（调用次数、Token、成功率、平均耗时）
     */
    List<Map<String, Object>> analyzeByModel();

    /**
     * 按上游模型聚合失败次数统计
     */
    List<Map<String, Object>> analyzeFailuresByModel();

    /**
     * 按上游渠道聚合调用权重统计
     */
    List<Map<String, Object>> analyzeWeightByProvider();

    /**
     * 仪表盘汇总统计
     */
    Map<String, Object> dashboardStats();

    /**
     * 今日客户调用 Top5 统计
     */
    List<Map<String, Object>> topCustomersToday();
}
