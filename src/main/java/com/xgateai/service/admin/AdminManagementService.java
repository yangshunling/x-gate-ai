package com.xgateai.service.admin;

import com.xgateai.dto.ChannelDTO;
import com.xgateai.dto.ProviderDTO;
import com.xgateai.entity.ModelChannel;

import java.util.List;
import java.util.Map;

/**
 * AdminManagementService 管理端业务服务接口
 * <p>
 * 定义管理员操作的契约，包括渠道（含其下模型）与客户/API KEY 的增删改查。
 * 渠道数据分散在 x_gate_channel（账号）与 x_gate_model（模型行）两张表。
 * </p>
 *
 * @author xgateai
 * @since 2026/9/8
 */
public interface AdminManagementService {

    // ==================== 渠道管理（含模型） ====================

    /**
     * 获取所有渠道列表（含其下模型与明文 API Key，供编辑回显）
     *
     * @return 每个元素为渠道对象，含 models 子列表
     */
    List<Map<String, Object>> listProviders();

    /**
     * 新增或更新渠道（含其下模型的差量同步，事务内完成）
     */
    void saveProvider(ProviderDTO dto);

    /**
     * 删除渠道（级联删除其下全部模型）
     */
    void deleteProvider(Long id);

    /**
     * 测试单个渠道下所有模型的连通性
     *
     * @return 每个模型的测试结果列表
     */
    List<Map<String, Object>> testProvider(Long id);

    /**
     * 测试单个模型行的连通性
     *
     * @return 该模型的测试结果
     */
    Map<String, Object> testModel(Long modelId);

    /**
     * 批量测试所有渠道下所有模型的连通性
     *
     * @return 每个模型的测试结果列表
     */
    List<Map<String, Object>> testAllProviders();

    /**
     * 探测上游渠道可用的模型列表（用于表单一键导入）
     *
     * @return 含 usedStoredKey / models 字段的结果
     */
    Map<String, Object> fetchProviderModels(com.xgateai.dto.FetchModelsDTO dto);

    // ==================== 客户/API KEY 管理 ====================

    /**
     * 获取所有客户列表（API KEY）
     */
    List<ModelChannel> listChannels();

    /**
     * 新增或更新客户，返回生成的 API Key
     */
    String saveChannel(ChannelDTO dto);

    /**
     * 删除客户
     */
    void deleteChannel(Long id);

    // ==================== 服务器信息 ====================

    /**
     * 获取服务器基础信息（IP、端口等）
     */
    Map<String, Object> getServerInfo(int port);

    // ==================== 并发控制 ====================

    /**
     * 列出所有候选模型（启用渠道下启用模型），按优先级由高到低排列，
     * 含失败次数、并发上限、当前在途并发数。
     *
     * @return 每个元素含 channelName / modelName / failCount / maxConcurrency / inFlight / priority 等字段
     */
    List<Map<String, Object>> listConcurrencyModels();

    /**
     * 更新指定模型行的并发上限
     *
     * @param modelId        模型行 ID
     * @param maxConcurrency 并发上限；{@code 0} 表示不限制
     */
    void updateConcurrencyLimit(Long modelId, int maxConcurrency);
}
