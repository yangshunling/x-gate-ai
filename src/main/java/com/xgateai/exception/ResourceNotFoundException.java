package com.xgateai.exception;

/**
 * ResourceNotFoundException 资源不存在异常
 * <p>
 * 用于查找管理员资源（Provider、Channel）时未找到的场景。
 * </p>
 *
 * @author xgateai
 * @since 2026/9/8
 */
public class ResourceNotFoundException extends GatewayException {

    public ResourceNotFoundException(String resourceName, Object id) {
        super(String.format("%s not found: %s", resourceName, id), 404, "resource_not_found");
    }
}
