package com.xgateai.application.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * <p>
 * UpstreamProvider 上游模型 Provider 实体
 * </p>
 *
 * @author xgateai
 * @since 2026/9/4
 */
@Data
@TableName("upstream_providers")
public class UpstreamProvider {

    /**
     * 主键
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 名称
     */
    private String name;

    /**
     * 上游 baseUrl（OpenAI 兼容，含 /v1 前缀）
     */
    private String baseUrl;

    /**
     * 上游 API Key（加密存储）
     */
    private String apiKey;

    /**
     * 上游真实模型名
     */
    private String modelName;

    /**
     * 是否启用
     */
    private Integer enabled;

    /**
     * 备注
     */
    private String remark;
}
