package com.xgateai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * ModelChannel 对外模型通道实体
 */
@Data
@TableName("model_channels")
public class ModelChannel {

    @TableId(type = IdType.AUTO)
    private Long id;

    @JsonProperty("public_model_name")
    private String publicModelName;

    @JsonProperty("model_name")
    private String modelName;

    @JsonProperty("api_key")
    private String apiKey;

    private Integer enabled;

    private String strategy;

    private String remark;
}
