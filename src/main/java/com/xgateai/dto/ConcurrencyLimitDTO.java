package com.xgateai.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * ConcurrencyLimitDTO 并发上限更新请求参数
 * <p>
 * 用于「并发控制」页面单独设置某模型行的并发上限（{@code 0} 表示不限制）。
 * </p>
 *
 * @author xgateai
 * @since 2026/9/9
 */
@Data
public class ConcurrencyLimitDTO {

    /**
     * 并发上限；{@code 0} 表示不限制，不允许为负
     */
    @NotNull(message = "并发上限不能为空")
    @Min(value = 0, message = "并发上限不能为负数")
    private Integer maxConcurrency;
}
