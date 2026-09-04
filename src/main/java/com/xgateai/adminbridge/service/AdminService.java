package com.xgateai.adminbridge.service;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xgateai.adminbridge.component.EncryptUtil;
import com.xgateai.application.constant.CommonConstant;
import com.xgateai.application.entity.ApiKey;
import com.xgateai.application.entity.CallLog;
import com.xgateai.application.entity.ChannelUpstream;
import com.xgateai.application.entity.ModelChannel;
import com.xgateai.application.entity.UpstreamProvider;
import com.xgateai.application.entity.User;
import com.xgateai.application.exceptions.CommonException;
import com.xgateai.application.model.dto.ApiKeyCreateDTO;
import com.xgateai.application.model.dto.ChangePasswordDTO;
import com.xgateai.application.model.dto.ChannelDTO;
import com.xgateai.application.model.dto.ProviderDTO;
import com.xgateai.gatewaybridge.adapter.OpenAiProxyAdapter;
import com.xgateai.gatewaybridge.service.CallLogService;
import com.xgateai.gatewaybridge.service.GatewayRouter;
import com.xgateai.mapper.ApiKeyMapper;
import com.xgateai.mapper.ChannelUpstreamMapper;
import com.xgateai.mapper.ModelChannelMapper;
import com.xgateai.mapper.UpstreamProviderMapper;
import com.xgateai.mapper.UserMapper;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * <p>
 * AdminService 管理端服务：登录会话、Provider/通道/API Key 管理、调用日志与看板
 * </p>
 *
 * @author xgateai
 * @since 2026/9/4
 */
@Slf4j
@Service
public class AdminService {

    /**
     * 默认负载策略
     */
    private static final String STRATEGY_ROUND_ROBIN = "ROUND_ROBIN";

    /**
     * 用户 Mapper
     */
    @Resource
    UserMapper userMapper;

    /**
     * API Key Mapper
     */
    @Resource
    ApiKeyMapper apiKeyMapper;

    /**
     * 上游 Provider Mapper
     */
    @Resource
    UpstreamProviderMapper upstreamProviderMapper;

    /**
     * 对外模型通道 Mapper
     */
    @Resource
    ModelChannelMapper modelChannelMapper;

    /**
     * 通道-上游绑定 Mapper
     */
    @Resource
    ChannelUpstreamMapper channelUpstreamMapper;

    /**
     * 上游 API Key 加解密工具
     */
    @Resource
    EncryptUtil encryptUtil;

    /**
     * 网关路由调度器（配置变更后刷新路由缓存）
     */
    @Resource
    GatewayRouter gatewayRouter;

    /**
     * 调用日志服务
     */
    @Resource
    CallLogService callLogService;

    /**
     * 上游连通性测试适配器
     */
    @Resource
    OpenAiProxyAdapter openAiProxyAdapter;

    /**
     * 管理员登录：校验用户名密码，成功后将用户 ID 写入会话并返回脱敏用户
     *
     * @param username 用户名
     * @param password 明文密码
     * @param session  会话
     * @return 脱敏后的用户（不包含密码哈希）
     */
    public User login(String username, String password, HttpSession session) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username)
                .last("LIMIT 1"));
        if (user == null || !BCrypt.checkpw(password, user.getPasswordHash())) {
            throw new CommonException("用户名或密码错误");
        }
        session.setAttribute(CommonConstant.SESSION_ADMIN_USER, user.getId());
        // 脱敏：不向前端暴露密码哈希
        user.setPasswordHash(null);
        log.info("管理员登录成功: {}", username);
        return user;
    }

    /**
     * 管理员登出：销毁当前会话
     *
     * @param session 会话
     */
    public void logout(HttpSession session) {
        if (session != null) {
            session.invalidate();
        }
    }

    /**
     * 获取当前登录用户：会话无登录标记或用户不存在时抛出未登录异常
     *
     * @param session 会话
     * @return 当前登录用户
     */
    public User currentUser(HttpSession session) {
        Object userId = session == null ? null : session.getAttribute(CommonConstant.SESSION_ADMIN_USER);
        if (userId == null) {
            throw new CommonException("未登录");
        }
        User user = userMapper.selectById(Long.valueOf(userId.toString()));
        if (user == null) {
            throw new CommonException("未登录");
        }
        return user;
    }

    /**
     * 修改当前登录用户密码：校验原密码后写入新密码哈希
     *
     * @param dto     修改密码参数
     * @param session 会话
     */
    public void changePassword(ChangePasswordDTO dto, HttpSession session) {
        User user = currentUser(session);
        if (!BCrypt.checkpw(dto.getOldPassword(), user.getPasswordHash())) {
            throw new CommonException("原密码错误");
        }
        user.setPasswordHash(BCrypt.hashpw(dto.getNewPassword(), BCrypt.gensalt()));
        userMapper.updateById(user);
        log.info("管理员修改密码成功: {}", user.getUsername());
    }

    /**
     * 查询全部上游 Provider（按 id 升序），apiKey 脱敏为 null 不外泄
     *
     * @return Provider 列表
     */
    public List<UpstreamProvider> listProviders() {
        List<UpstreamProvider> providers = upstreamProviderMapper.selectList(
                new LambdaQueryWrapper<UpstreamProvider>().orderByAsc(UpstreamProvider::getId));
        for (UpstreamProvider provider : providers) {
            provider.setApiKey(null);
        }
        return providers;
    }

    /**
     * 新增或更新上游 Provider：
     * 新增时 apiKey 必填并加密入库；编辑时 apiKey 为空表示保留原值，baseUrl 去除尾部 "/"
     *
     * @param dto Provider 参数
     */
    public void saveProvider(ProviderDTO dto) {
        boolean isNew = dto.getId() == null;
        UpstreamProvider provider;
        if (isNew) {
            if (StrUtil.isBlank(dto.getApiKey())) {
                throw new CommonException("apiKey 不能为空");
            }
            provider = new UpstreamProvider();
        } else {
            provider = upstreamProviderMapper.selectById(dto.getId());
            if (provider == null) {
                throw new CommonException("Provider 不存在");
            }
        }
        provider.setName(StrUtil.trim(dto.getName()));
        // baseUrl 去除尾部 "/"，统一 baseUrl 规范
        String baseUrl = StrUtil.trim(dto.getBaseUrl());
        while (baseUrl.length() > 1 && baseUrl.endsWith(CommonConstant.SLASH)) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        provider.setBaseUrl(baseUrl);
        provider.setModelName(StrUtil.trim(dto.getModelName()));
        provider.setEnabled(dto.getEnabled() == null ? CommonConstant.ENABLED : dto.getEnabled());
        provider.setRemark(dto.getRemark());
        // 新增必填 apiKey；编辑时 apiKey 非空则更新（重新加密），为空保留原值
        if (StrUtil.isNotBlank(dto.getApiKey())) {
            provider.setApiKey(encryptUtil.encrypt(dto.getApiKey()));
        }
        if (isNew) {
            upstreamProviderMapper.insert(provider);
            log.info("新增上游 Provider: {} ({})", provider.getName(), provider.getId());
        } else {
            upstreamProviderMapper.updateById(provider);
            log.info("更新上游 Provider: {} ({})", provider.getName(), provider.getId());
        }
        // 配置变更后刷新路由缓存，实时生效
        gatewayRouter.refresh();
    }

    /**
     * 删除上游 Provider，同时删除其在各通道的绑定，并刷新路由缓存
     *
     * @param id Provider ID
     */
    public void deleteProvider(Long id) {
        channelUpstreamMapper.delete(new LambdaQueryWrapper<ChannelUpstream>()
                .eq(ChannelUpstream::getProviderId, id));
        upstreamProviderMapper.deleteById(id);
        gatewayRouter.refresh();
    }

    /**
     * 查询全部启用状态的上游 Provider（供通道绑定时下拉选择），apiKey 脱敏不外泄
     *
     * @return 启用的 Provider 列表
     */
    public List<UpstreamProvider> listEnabledProviders() {
        List<UpstreamProvider> providers = upstreamProviderMapper.selectList(
                new LambdaQueryWrapper<UpstreamProvider>()
                        .eq(UpstreamProvider::getEnabled, CommonConstant.ENABLED)
                        .orderByAsc(UpstreamProvider::getId));
        for (UpstreamProvider provider : providers) {
            provider.setApiKey(null);
        }
        return providers;
    }

    /**
     * 连通性测试：委托 OpenAiProxyAdapter 探测指定上游 Provider 是否可用
     *
     * @param id Provider ID
     * @return 探测结果 JSON（如是否成功、耗时、返回信息等）
     */
    public JSONObject testProvider(Long id) {
        UpstreamProvider provider = upstreamProviderMapper.selectById(id);
        if (provider == null) {
            throw new CommonException("Provider 不存在");
        }
        return openAiProxyAdapter.testProvider(provider);
    }

    /**
     * 查询全部对外模型通道（按 id 升序），附带绑定上游信息与顺序
     *
     * @return 通道列表，每项含 id/public_model_name/enabled/strategy/remark/provider_ids/providers
     */
    public List<JSONObject> listChannels() {
        List<ModelChannel> channels = modelChannelMapper.selectList(
                new LambdaQueryWrapper<ModelChannel>().orderByAsc(ModelChannel::getId));
        List<JSONObject> list = new ArrayList<>();
        for (ModelChannel channel : channels) {
            List<ChannelUpstream> binds = channelUpstreamMapper.selectList(
                    new LambdaQueryWrapper<ChannelUpstream>()
                            .eq(ChannelUpstream::getChannelId, channel.getId())
                            .orderByAsc(ChannelUpstream::getSort));
            List<Long> providerIds = new ArrayList<>();
            List<JSONObject> providers = new ArrayList<>();
            for (ChannelUpstream bind : binds) {
                UpstreamProvider provider = upstreamProviderMapper.selectById(bind.getProviderId());
                if (provider == null) {
                    continue;
                }
                providerIds.add(provider.getId());
                JSONObject item = new JSONObject();
                item.put("id", provider.getId());
                item.put("name", provider.getName());
                item.put("model_name", provider.getModelName());
                item.put("enabled", provider.getEnabled());
                providers.add(item);
            }
            JSONObject obj = new JSONObject();
            obj.put("id", channel.getId());
            obj.put("public_model_name", channel.getPublicModelName());
            obj.put("enabled", channel.getEnabled());
            obj.put("strategy", channel.getStrategy());
            obj.put("remark", channel.getRemark());
            obj.put("provider_ids", providerIds);
            obj.put("providers", providers);
            list.add(obj);
        }
        return list;
    }

    /**
     * 新增或更新对外模型通道，并按 providerIds 顺序重建通道-上游绑定（可为空通道），随后刷新路由缓存
     *
     * @param dto 通道参数
     */
    public void saveChannel(ChannelDTO dto) {
        boolean isNew = dto.getId() == null;
        ModelChannel channel;
        if (isNew) {
            channel = new ModelChannel();
        } else {
            channel = modelChannelMapper.selectById(dto.getId());
            if (channel == null) {
                throw new CommonException("通道不存在");
            }
        }
        channel.setPublicModelName(StrUtil.trim(dto.getPublicModelName()));
        channel.setEnabled(dto.getEnabled() == null ? CommonConstant.ENABLED : dto.getEnabled());
        channel.setStrategy(StrUtil.isBlank(dto.getStrategy()) ? STRATEGY_ROUND_ROBIN : dto.getStrategy());
        channel.setRemark(dto.getRemark());
        if (isNew) {
            modelChannelMapper.insert(channel);
            log.info("新增对外模型通道: {} ({})", channel.getPublicModelName(), channel.getId());
        } else {
            modelChannelMapper.updateById(channel);
            log.info("更新对外模型通道: {} ({})", channel.getPublicModelName(), channel.getId());
        }
        Long channelId = channel.getId();
        // 删除旧绑定后按 providerIds 顺序重建绑定（weight=1，sort 从 0 起）
        channelUpstreamMapper.delete(new LambdaQueryWrapper<ChannelUpstream>()
                .eq(ChannelUpstream::getChannelId, channelId));
        List<Long> providerIds = dto.getProviderIds();
        if (providerIds != null) {
            int sort = 0;
            for (Long providerId : providerIds) {
                if (providerId == null) {
                    continue;
                }
                ChannelUpstream bind = new ChannelUpstream();
                bind.setChannelId(channelId);
                bind.setProviderId(providerId);
                bind.setWeight(CommonConstant.ONE);
                bind.setSort(sort++);
                channelUpstreamMapper.insert(bind);
            }
        }
        gatewayRouter.refresh();
    }

    /**
     * 删除对外模型通道及其通道-上游绑定，并刷新路由缓存
     *
     * @param id 通道 ID
     */
    public void deleteChannel(Long id) {
        channelUpstreamMapper.delete(new LambdaQueryWrapper<ChannelUpstream>()
                .eq(ChannelUpstream::getChannelId, id));
        modelChannelMapper.deleteById(id);
        gatewayRouter.refresh();
    }

    /**
     * 查询全部对外调用 API Key（按 id 升序），key 明文脱敏展示（前 8 位 + ...）
     *
     * @return API Key 列表
     */
    public List<ApiKey> listApiKeys() {
        List<ApiKey> keys = apiKeyMapper.selectList(
                new LambdaQueryWrapper<ApiKey>().orderByAsc(ApiKey::getId));
        for (ApiKey apiKey : keys) {
            if (StrUtil.isNotBlank(apiKey.getKey()) && apiKey.getKey().length() > 8) {
                apiKey.setKey(StrUtil.sub(apiKey.getKey(), 0, 8) + "...");
            }
        }
        return keys;
    }

    /**
     * 创建对外调用 API Key（明文完整入库，仅创建时返回一次完整明文）
     *
     * @param dto     创建参数
     * @param session 会话（取当前登录用户）
     * @return 完整明文 key（一次性展示，不再提供查询）
     */
    public String createApiKey(ApiKeyCreateDTO dto, HttpSession session) {
        User user = currentUser(session);
        String key = "xgate-" + RandomUtil.randomString(32);
        ApiKey apiKey = new ApiKey();
        apiKey.setUserId(user.getId());
        apiKey.setKey(key);
        apiKey.setName(dto.getName());
        apiKey.setEnabled(CommonConstant.ENABLED);
        apiKey.setCreatedAt(DateUtil.now());
        apiKeyMapper.insert(apiKey);
        log.info("管理员创建 API Key: {} ({})", dto.getName(), apiKey.getId());
        return key;
    }

    /**
     * 启用/停用切换对外调用 API Key
     *
     * @param id API Key ID
     */
    public void toggleApiKey(Long id) {
        ApiKey apiKey = apiKeyMapper.selectById(id);
        if (apiKey == null) {
            throw new CommonException("API Key 不存在");
        }
        apiKey.setEnabled(apiKey.getEnabled() != null && apiKey.getEnabled() == CommonConstant.ENABLED
                ? CommonConstant.DISABLED : CommonConstant.ENABLED);
        apiKeyMapper.updateById(apiKey);
    }

    /**
     * 删除对外调用 API Key
     *
     * @param id API Key ID
     */
    public void deleteApiKey(Long id) {
        apiKeyMapper.deleteById(id);
    }

    /**
     * 分页查询调用日志（透传网关日志服务）
     *
     * @param publicModel 对外模型名（可选）
     * @param apiKeyName  API Key（可选）
     * @param pageNum     页码
     * @param pageSize    每页条数
     * @return 调用日志分页结果
     */
    public Page<CallLog> queryLogs(String publicModel, String apiKeyName, int pageNum, int pageSize) {
        return callLogService.pageQuery(publicModel, apiKeyName, pageNum, pageSize);
    }

    /**
     * 查询控制台看板统计（透传网关日志服务）
     *
     * @return 看板统计 JSON
     */
    public JSONObject dashboard() {
        return callLogService.dashboard();
    }
}
