package com.xgateai.service.gateway.strategy;

import com.xgateai.entity.ModelChannel;
import com.xgateai.entity.UpstreamProvider;

import java.util.List;

/**
 * UpstreamStrategy 上游路由策略接口
 * <p>
 * 定义选择上游 Provider 的策略契约，支持不同路由算法（轮询、故障转移等）。
 * </p>
 *
 * @author xgateai
 * @since 2026/9/8
 */
public interface UpstreamStrategy {

    /**
     * 根据请求模型选择可用上游候选列表
     *
     * @param channel       对客通道信息
     * @param requestedModel 客户端请求的模型名
     * @return 按优先级排序的上游 Provider 候选列表
     */
    List<UpstreamProvider> selectCandidates(ModelChannel channel, String requestedModel);
}
