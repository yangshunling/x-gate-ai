package com.xgateai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * ProviderModelDTO 渠道下挂载模型的单条编辑项
 * <p>
 * 新增/编辑渠道时随 ProviderDTO 一起提交，一个模型对应 upstream_model 一行。
 * </p>
 *
 * @author xgateai
 * @since 2026/9/9
 */
@Data
public class ProviderModelDTO {

    /**
     * 上游真实模型名（单值），必填
     */
    @NotBlank(message = "模型名不能为空")
    private String modelName;

    /**
     * 是否启用；为 null 时默认启用（值 1）
     */
    private Integer enabled;

    /**
     * 该模型的备注
     */
    private String remark;
}
