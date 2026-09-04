package com.xgateai.application.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * <p>
 * ChannelDTO 对外模型通道新增/编辑请求参数
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
     * 对外模型名（对外稳定暴露）
     */
    @JsonProperty("publicModelName")
    @NotBlank(message = "对外模型名不能为空")
    private String publicModelName;

    /**
     * 是否启用（为空默认 1）
     */
    private Integer enabled;

    /**
     * 负载策略（为空默认 ROUND_ROBIN）
     */
    private String strategy;

    /**
     * 备注
     */
    private String remark;

    /**
     * 绑定的上游 Provider ID 列表（列表顺序即绑定顺序，可为空列表）
     */
    @JsonProperty("providerIds")
    private List<Long> providerIds;
}
