package com.xgateai.entity;

import lombok.Data;

/**
 * UpstreamRoute 路由目标对象
 * <p>
 * 组合一个模型行（{@link UpstreamModel}）与它所属的渠道（{@link UpstreamProvider}），
 * 作为网关候选与故障转移链路上的最小执行单元：上游地址/密钥来自渠道，
 * 真实模型名与失败计数来自模型行。
 * </p>
 *
 * @author xgateai
 * @since 2026/9/9
 */
@Data
public class UpstreamRoute {

    /**
     * 渠道（含 baseUrl / apiKey / enabled）
     */
    private UpstreamProvider provider;

    /**
     * 模型行（含 modelName / failCount / enabled）
     */
    private UpstreamModel model;

    /**
     * 构造路由目标
     *
     * @param provider 所属渠道
     * @param model    模型行
     */
    public UpstreamRoute(UpstreamProvider provider, UpstreamModel model) {
        this.provider = provider;
        this.model = model;
    }

    /**
     * 该路由目标的真实上游模型名
     */
    public String getModelName() {
        return model == null ? null : model.getModelName();
    }
}
