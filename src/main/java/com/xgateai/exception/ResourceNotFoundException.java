package com.xgateai.exception;

/**
 * ResourceNotFoundException 资源不存在异常
 * <p>
 * 用于查找管理员资源（Provider、Channel）时未找到的场景，返回 HTTP 404。
 * </p>
 *
 * @author xgateai
 * @since 2026/9/8
 */
public class ResourceNotFoundException extends GatewayException {

    /**
     * 构造资源不存在异常
     *
     * @param resourceName 资源类型名称（如 "Provider"、"Channel"）
     * @param id           不存在资源的 ID
     */
    public ResourceNotFoundException(String resourceName, Object id) {
        super(String.format("%s not found: %s", resourceName, id), 404, "resource_not_found");
    }
}
