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
     * 客户名（对外展示归属，创建时必填）
     */
    @JsonProperty("public_model_name")
    private String publicModelName;

    /**
     * 限定模型名（可空）：为空/null 表示 default，该 Key 可调用池内所有模型；
     * 非空则仅允许调用该模型
     */
    @JsonProperty("model_name")
    private String modelName;

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
