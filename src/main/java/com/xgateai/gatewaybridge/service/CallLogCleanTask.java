package com.xgateai.gatewaybridge.service;

import cn.hutool.core.date.DateUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xgateai.application.constant.CommonConstant;
import com.xgateai.application.entity.CallLog;
import com.xgateai.gatewaybridge.config.GatewayConfig;
import com.xgateai.mapper.CallLogMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * <p>
 * CallLogCleanTask 调用日志定时清理任务
 * 每天凌晨 3:30 清理超出保留天数的历史调用日志
 * </p>
 *
 * @author xgateai
 * @since 2026/9/4
 */
@Slf4j
@Component
public class CallLogCleanTask {

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
     * 清理过期调用日志：删除 created_at 早于（当前时间 - 保留天数）的记录
     */
    @Scheduled(cron = "0 30 3 * * ?")
    public void cleanExpired() {
        // 计算保留截止时间并格式化为与 created_at 一致的字符串
        Date deadline = DateUtil.offsetDay(new Date(), -gatewayConfig.getLogRetentionDays());
        String deadlineStr = DateUtil.format(deadline, CommonConstant.DATETIME_FORMAT);
        int deleted = callLogMapper.delete(new QueryWrapper<CallLog>().lt("created_at", deadlineStr));
        log.info("调用日志定时清理完成，清理 {} 条，保留截止: {}", deleted, deadlineStr);
    }
}
