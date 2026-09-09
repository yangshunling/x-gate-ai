package com.xgateai.dto;

import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * LogQueryDTO 调用日志分页查询参数
 * <p>
 * 所有字段均为可选过滤条件，pageNum/pageSize 有下限校验。
 * </p>
 *
 * @author xgateai
 * @since 2026/9/8
 */
@Data
public class LogQueryDTO {

    /**
     * 按客户名/对外模型名过滤（可选）
     */
    private String publicModel;

    /**
     * 按 API Key 过滤（可选）
     */
    private String apiKeyName;

    /**
     * 按上游真实模型名过滤（可选）
     */
    private String model;

    /**
     * 起始日期，格式 yyyy-MM-dd（可选）
     */
    private String dateFrom;

    /**
     * 结束日期，格式 yyyy-MM-dd（可选）
     */
    private String dateTo;

    /**
     * 按 HTTP 状态码过滤（可选）
     */
    private Integer status;

    /**
     * 页码，最小值为 1，默认 1
     */
    @Min(value = 1, message = "页码不能小于 1")
    private Integer pageNum = 1;

    /**
     * 每页条数，最小值为 1，默认 20
     */
    @Min(value = 1, message = "每页条数不能小于 1")
    private Integer pageSize = 20;
}
