package com.xgateai.application.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * <p>
 * ModelChannel 对外模型通道实体
 * </p>
 *
 * @author xgateai
 * @since 2026/9/4
 */
@Data
@TableName("model_channels")
public class ModelChannel {

    /**
     * 主键
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 对外模型名（对外稳定暴露）
     */
    @JsonProperty("public_model_name")
    private String publicModelName;

    /**
     * 该对客服务专属的调用 Key（创建时自动生成）
     */
    @JsonProperty("api_key")
    private String apiKey;

    /**
     * 是否启用
     */
    private Integer enabled;

    /**
     * 负载策略（ROUND_ROBIN）
     */
    private String strategy;

    /**
     * 备注
     */
    private String remark;
}
