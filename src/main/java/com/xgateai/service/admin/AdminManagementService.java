package com.xgateai.service.admin;

import com.xgateai.dto.ChannelDTO;
import com.xgateai.dto.ProviderDTO;
import com.xgateai.entity.ModelChannel;
import com.xgateai.entity.UpstreamProvider;

import java.util.List;
import java.util.Map;

/**
 * AdminManagementService 管理端业务服务接口
 * <p>
 * 定义管理员操作的契约，包括上游 Provider 和模型通道的增删改查。
 * </p>
 *
 * @author xgateai
 * @since 2026/9/8
 */
public interface AdminManagementService {

    // ==================== 上游 Provider 管理 ====================

    /**
     * 获取所有上游 Provider 列表（API Key 已脱敏）
     */
    List<UpstreamProvider> listProviders();

    /**
     * 新增或更新上游 Provider
     */
    void saveProvider(ProviderDTO dto);

    /**
     * 删除上游 Provider
     */
    void deleteProvider(Long id);

    /**
     * 测试单个上游 Provider 连通性
     */
    Map<String, Object> testProvider(Long id);

    /**
     * 批量测试所有上游 Provider 连通性
     */
    List<Map<String, Object>> testAllProviders();

    // ==================== 模型通道管理 ====================

    /**
     * 获取所有模型通道列表
     */
    List<ModelChannel> listChannels();

    /**
     * 新增或更新模型通道，返回生成的 API Key
     */
    String saveChannel(ChannelDTO dto);

    /**
     * 删除模型通道
     */
    void deleteChannel(Long id);

    // ==================== 服务器信息 ====================

    /**
     * 获取服务器基础信息（IP、端口等）
     */
    Map<String, Object> getServerInfo(int port);
}
