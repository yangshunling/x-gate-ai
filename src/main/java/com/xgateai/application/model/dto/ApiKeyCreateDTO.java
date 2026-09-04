package com.xgateai.application.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * <p>
 * ApiKeyCreateDTO 对外调用 API Key 创建请求参数
 * </p>
 *
 * @author xgateai
 * @since 2026/9/4
 */
@Data
public class ApiKeyCreateDTO {

    /**
     * 名称
     */
    @JsonProperty("name")
    @NotBlank(message = "API Key 名称不能为空")
    private String name;
}
