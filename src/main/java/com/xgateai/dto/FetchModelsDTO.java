package com.xgateai.dto;

import lombok.Data;

/**
 * FetchModelsDTO 拉取上游渠道可用模型列表请求参数
 * <p>
 * 用于在新增/编辑渠道时，基于表单中的 BaseUrl 与 API Key 探测上游 /models。
 * 新增时 apiKey 必填；编辑时 apiKey 留空表示使用渠道库内已保存的 Key。
 * </p>
 *
 * @author xgateai
 * @since 2026/9/9
 */
@Data
public class FetchModelsDTO {

    /**
     * 渠道 ID；编辑且 apiKey 留空时用于取库内已存 Key，新增时可为空
     */
    private Long id;

    /**
     * 上游 BaseUrl（须含 /v1 前缀）
     */
    private String baseUrl;

    /**
     * 上游 API Key（明文，仅用于本次探测，不落库）
     */
    private String apiKey;
}
