package com.xgateai.application.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * <p>
 * User 管理端用户实体
 * </p>
 *
 * @author xgateai
 * @since 2026/9/4
 */
@Data
@TableName("users")
public class User {

    /**
     * 主键
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 用户名
     */
    @JsonProperty("username")
    private String username;

    /**
     * 密码哈希
     */
    private String passwordHash;

    /**
     * 创建时间
     */
    private String createdAt;
}
