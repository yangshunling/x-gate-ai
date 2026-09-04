package com.xgateai.application.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * <p>
 * LoginDTO 管理员登录请求参数
 * </p>
 *
 * @author xgateai
 * @since 2026/9/4
 */
@Data
public class LoginDTO {

    /**
     * 用户名
     */
    @JsonProperty("username")
    @NotBlank(message = "用户名不能为空")
    private String username;

    /**
     * 密码
     */
    @JsonProperty("password")
    @NotBlank(message = "密码不能为空")
    private String password;
}
