package com.xgateai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * UpstreamProvider 渠道实体（表 x_gate_channel）
 * <p>
 * 代表一个已接入的上游大模型服务账号（如 OpenAI、Anthropic 等兼容接口），
 * 包含 BaseUrl、加密后的 API Key。一个渠道下挂载多个模型，
 * 模型记录在 {@link UpstreamModel}（表 x_gate_model），通过 channelId 挂接。
 * </p>
 *
 * @author xgateai
 * @since 2026/9/4
 */
@Data
@TableName("x_gate_channel")
public class UpstreamProvider {

    /**
     * 主键，自增 ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 渠道显示名称（如 "DeepSeek 官方"、"商汤-主账号"）
     */
    private String name;

    /**
     * 上游 BaseUrl，须包含 /v1 前缀，不含尾部斜杠
     */
    private String baseUrl;

    /**
     * 加密后的上游 API Key（存储密文，读取时解密）
     */
    private String apiKey;

    /**
     * 是否启用：1=启用，0=禁用
     */
    private Integer enabled;

    /**
     * 备注信息
     */
    private String remark;

    /**
     * 创建时间（格式 yyyy-MM-dd HH:mm:ss）
     */
    private String createdAt;
}
