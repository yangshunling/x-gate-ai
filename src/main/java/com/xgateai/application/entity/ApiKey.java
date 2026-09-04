package com.xgateai.application.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * <p>
 * ApiKey 对外调用 API Key 实体
 * </p>
 *
 * @author xgateai
 * @since 2026/9/4
 */
@Data
@TableName("api_keys")
public class ApiKey {

    /**
     * 主键
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 所属用户 ID
     */
    private Long userId;

    /**
     * API Key
     */
    @JsonProperty("key")
    private String key;

    /**
     * 名称
     */
    private String name;

    /**
     * 是否启用
     */
    private Integer enabled;

    /**
     * 创建时间
     */
    private String createdAt;

    /**
     * 最后使用时间
     */
    private String lastUsedAt;
}
