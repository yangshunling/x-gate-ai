package com.xgateai.dto;

import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * LogQueryDTO 调用日志分页查询参数
 *
 * @author xgateai
 * @since 2026/9/8
 */
@Data
public class LogQueryDTO {

    /** 对外模型名（可选） */
    private String publicModel;

    /** API Key（可选） */
    private String apiKeyName;

    /** 上游真实模型名（可选） */
    private String model;

    /** 起始日期 yyyy-MM-dd（可选） */
    private String dateFrom;

    /** 结束日期 yyyy-MM-dd（可选） */
    private String dateTo;

    /** HTTP 状态码（可选） */
    private Integer status;

    /** 页码，默认 1 */
    @Min(value = 1, message = "页码不能小于 1")
    private Integer pageNum = 1;

    /** 每页条数，默认 20 */
    @Min(value = 1, message = "每页条数不能小于 1")
    private Integer pageSize = 20;
}
