package com.xgateai.application.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * <p>
 * ChangePasswordDTO 修改管理员密码请求参数
 * </p>
 *
 * @author xgateai
 * @since 2026/9/4
 */
@Data
public class ChangePasswordDTO {

    /**
     * 原密码
     */
    @JsonProperty("oldPassword")
    @NotBlank(message = "原密码不能为空")
    private String oldPassword;

    /**
     * 新密码（长度不少于 6 位）
     */
    @JsonProperty("newPassword")
    @NotBlank(message = "新密码不能为空")
    @Size(min = 6, message = "新密码长度不能少于 6 位")
    private String newPassword;
}
