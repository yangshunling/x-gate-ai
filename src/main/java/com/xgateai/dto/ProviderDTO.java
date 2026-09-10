package com.xgateai.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * ProviderDTO 渠道新增/编辑请求参数
 * <p>
 * 一个渠道携带其下挂载的模型列表（{@link ProviderModelDTO}），
 * 后端保存时按渠道与模型分别落库（upstream_provider / upstream_model）。
 * 新增时 apiKey 必填；编辑时 apiKey 为空表示不修改原有密钥。
 * </p>
 *
 * @author xgateai
 * @since 2026/9/8
 */
@Data
public class ProviderDTO {

    /**
     * 主键 ID；新增时为空，更新时必须传入对应 ID
     */
    private Long id;

    /**
     * 渠道名称，必填
     */
    @NotBlank(message = "渠道名称不能为空")
    private String name;

    /**
     * 上游 BaseUrl（须含 /v1 前缀），必填
     */
    @NotBlank(message = "上游地址不能为空")
    private String baseUrl;

    /**
     * 上游 API Key；新增时必填，编辑时为空表示保留原密钥不变
     */
    private String apiKey;

    /**
     * 是否启用；为 null 时默认启用（值 1）
     */
    private Integer enabled;

    /**
     * 备注信息
     */
    private String remark;

    /**
     * 该渠道下挂载的模型列表；编辑时为空数组表示清空其下所有模型
     */
    @Valid
    @NotNull(message = "模型列表不能为空")
    private List<ProviderModelDTO> models = new ArrayList<>();
}
