package com.xgateai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * UpstreamModel 渠道下挂载的模型实体（表 x_gate_model）
 * <p>
 * 一个渠道（{@link UpstreamProvider}）可挂载多个模型，每条记录对应一个模型。
 * 路由候选、故障计数（failCount）、故障转移均以模型行为粒度。
 * </p>
 *
 * @author xgateai
 * @since 2026/9/9
 */
@Data
@TableName("x_gate_model")
public class UpstreamModel {

    /**
     * 主键，自增 ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 所属渠道 ID（对应 x_gate_channel.id）
     */
    @JsonProperty("channel_id")
    private Long channelId;

    /**
     * 上游真实模型名（单值，如 "gpt-4o"）
     */
    @JsonProperty("model_name")
    private String modelName;

    /**
     * 是否启用：1=启用，0=禁用
     */
    private Integer enabled;

    /**
     * 累计失败次数，由网关在每次调用失败时递增，用于候选排序（升序优先）
     */
    @JsonProperty("fail_count")
    private Integer failCount;

    /**
     * 并发上限：0 表示不限制。在途请求达到该值时跳过该模型，故障转移到下一候选。
     */
    @JsonProperty("max_concurrency")
    private Integer maxConcurrency;

    /**
     * 备注信息
     */
    private String remark;

    /**
     * 创建时间（格式 yyyy-MM-dd HH:mm:ss）
     */
    @JsonProperty("created_at")
    private String createdAt;
}
