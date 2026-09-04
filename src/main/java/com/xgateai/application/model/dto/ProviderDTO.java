package com.xgateai.application.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * <p>
 * ProviderDTO 上游 Provider 新增/编辑请求参数
 * </p>
 *
 * @author xgateai
 * @since 2026/9/4
 */
@Data
public class ProviderDTO {

    /**
     * 主键（新增为空，编辑必填）
     */
    private Long id;

    /**
     * 名称
     */
    @JsonProperty("name")
    @NotBlank(message = "名称不能为空")
    private String name;

    /**
     * 上游 baseUrl（OpenAI 兼容，含 /v1 前缀）
     */
    @JsonProperty("baseUrl")
    @NotBlank(message = "上游地址不能为空")
    private String baseUrl;

    /**
     * 上游 API Key（新增时必填；编辑时为空表示不修改，保留原值）
     */
    @JsonProperty("apiKey")
    private String apiKey;

    /**
     * 上游真实模型名
     */
    @JsonProperty("modelName")
    @NotBlank(message = "模型名称不能为空")
    private String modelName;

    /**
     * 是否启用（为空默认 1）
     */
    @JsonProperty("enabled")
    private Integer enabled;

    /**
     * 备注
     */
    @JsonProperty("remark")
    private String remark;
}
