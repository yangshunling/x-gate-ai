package com.xgateai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * ChannelDTO 模型通道新增/编辑请求参数
 *
 * @author xgateai
 * @since 2026/9/8
 */
@Data
public class ChannelDTO {

    /** 主键（新增为空，编辑必填） */
    private Long id;

    /** 客户名（对外展示归属，创建时必填） */
    @NotBlank(message = "客户名不能为空")
    private String publicModelName;

    /** 限定模型名（可空，空表示不限制） */
    private String modelName;

    /** 是否启用（为空默认启用） */
    private Integer enabled;

    /** 备注 */
    private String remark;
}
