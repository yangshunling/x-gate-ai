package com.xgateai.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * ConcurrencyLimitDTO 流控参数更新请求
 * <p>
 * 用于「流控管理」页面设置某模型行的并发上限（{@code 0} 表示不限制）与失败次数。
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

    /**
     * 累计失败次数；{@code 0} 表示无失败记录，不允许为负
     */
    @NotNull(message = "失败次数不能为空")
    @Min(value = 0, message = "失败次数不能为负数")
    @JsonProperty("fail_count")
    private Integer failCount;
}
