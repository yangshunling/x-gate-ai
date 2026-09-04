package com.xgateai.application.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * <p>
 * ChannelUpstream 通道-上游绑定实体
 * </p>
 *
 * @author xgateai
 * @since 2026/9/4
 */
@Data
@TableName("channel_upstreams")
public class ChannelUpstream {

    /**
     * 主键
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 通道 ID
     */
    @JsonProperty("channel_id")
    private Long channelId;

    /**
     * 上游 Provider ID
     */
    @JsonProperty("provider_id")
    private Long providerId;

    /**
     * 权重
     */
    private Integer weight;

    /**
     * 排序号
     */
    private Integer sort;
}
