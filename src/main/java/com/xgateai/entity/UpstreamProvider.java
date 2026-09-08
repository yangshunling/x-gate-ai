package com.xgateai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * UpstreamProvider 上游模型 Provider 实体
 */
@Data
@TableName("upstream_providers")
public class UpstreamProvider {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String baseUrl;

    private String apiKey;

    private String modelName;

    private Integer enabled;

    private Integer failCount;

    private String remark;
}
