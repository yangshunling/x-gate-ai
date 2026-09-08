package com.xgateai.application.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * <p>
 * ChannelDTO 对外客户 API Key 新增/编辑请求参数
 * </p>
 *
 * @author xgateai
 * @since 2026/9/4
 */
@Data
public class ChannelDTO {

    /**
     * 主键（新增为空，编辑必填）
     */
    private Long id;

    /**
     * 客户名（对外展示归属）
     */
    @JsonProperty("publicModelName")
    @NotBlank(message = "客户名不能为空")
    private String publicModelName;

    /**
     * 限定模型名（可空）：空/default 表示不限制，可调用池内所有模型
     */
    @JsonProperty("modelName")
    private String modelName;

    /**
     * 是否启用（为空默认 1）
     */
    private Integer enabled;

    /**
     * 备注
     */
    private String remark;
}
