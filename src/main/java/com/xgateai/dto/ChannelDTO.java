package com.xgateai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * ChannelDTO 模型通道新增/编辑请求参数
 * <p>
 * 对应 {@link com.xgateai.entity.ModelChannel} 的写入视图，
 * 含可选的 ID 字段以区分新增与更新操作。
 * </p>
 *
 * @author xgateai
 * @since 2026/9/8
 */
@Data
public class ChannelDTO {

    /**
     * 主键 ID；新增时为空，更新时必须传入对应 ID
     */
    private Long id;

    /**
     * 客户名/对外展示名，必填，创建时不可为空
     */
    @NotBlank(message = "客户名不能为空")
    private String publicModelName;

    /**
     * 限定模型名；为空时表示不限定模型（走全池路由）
     */
    private String modelName;

    /**
     * 是否启用；为 null 时默认启用（值 1）
     */
    private Integer enabled;

    /**
     * 备注信息
     */
    private String remark;
}
