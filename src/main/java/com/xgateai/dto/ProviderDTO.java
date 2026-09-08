package com.xgateai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * ProviderDTO 上游 Provider 新增/编辑请求参数
 *
 * @author xgateai
 * @since 2026/9/8
 */
@Data
public class ProviderDTO {

    /** 主键（新增为空，编辑必填） */
    private Long id;

    /** 名称 */
    @NotBlank(message = "名称不能为空")
    private String name;

    /** 上游 baseUrl（含 /v1 前缀） */
    @NotBlank(message = "上游地址不能为空")
    private String baseUrl;

    /** 上游 API Key（新增时必填；编辑时为空表示不修改） */
    private String apiKey;

    /** 上游真实模型名 */
    @NotBlank(message = "模型名称不能为空")
    private String modelName;

    /** 是否启用（为空默认启用） */
    private Integer enabled;

    /** 累计失败次数（只读） */
    private Integer failCount;

    /** 备注 */
    private String remark;
}
