package com.xgateai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * ModelChannel 客户实体（表 customer，即 API KEY）
 * <p>
 * 每个客户对应一个面向调用方的 API Key，可限定具体模型名（为 null 时表示不限定），
 * 配合渠道模型池实现路由转发与鉴权。
 * </p>
 *
 * @author xgateai
 * @since 2026/9/4
 */
@Data
@TableName("customer")
public class ModelChannel {

    /**
     * 主键，自增 ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 对外展示的模型名/客户名（如 "my-chatbot"），作为公共标识
     */
    @JsonProperty("public_model_name")
    private String publicModelName;

    /**
     * 上游真实模型名；为 null 或空字符串时表示不限定模型（全池路由）
     */
    @JsonProperty("model_name")
    private String modelName;

    /**
     * 该通道专属的 API Key，对外暴露给调用方使用
     */
    @JsonProperty("api_key")
    private String apiKey;

    /**
     * 是否启用：1=启用，0=禁用
     */
    private Integer enabled;

    /**
     * 路由策略名称（当前固定为 ROUND_ROBIN）
     */
    private String strategy;

    /**
     * 备注信息
     */
    private String remark;

    /**
     * 创建时间（格式 yyyy-MM-dd HH:mm:ss）
     */
    private String createdAt;
}
