package com.xgateai.service.admin;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.xgateai.constant.CommonConstant;
import com.xgateai.dto.ChannelDTO;
import com.xgateai.dto.ConcurrencyLimitDTO;
import com.xgateai.dto.FetchModelsDTO;
import com.xgateai.dto.ProviderDTO;
import com.xgateai.dto.ProviderModelDTO;
import com.xgateai.entity.ModelChannel;
import com.xgateai.entity.UpstreamModel;
import com.xgateai.entity.UpstreamProvider;
import com.xgateai.exception.ResourceNotFoundException;
import com.xgateai.adapter.ProxyAdapter;
import com.xgateai.component.EncryptUtil;
import com.xgateai.component.InflightRegistry;
import com.xgateai.mapper.IModelChannelDao;
import com.xgateai.mapper.IUpstreamModelDao;
import com.xgateai.mapper.IUpstreamProviderDao;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.*;

/**
 * AdminManagementServiceImpl 管理端业务服务实现
 * <p>
 * 实现渠道（含其下模型行）与客户（对外 API Key）的增删改查、连通性测试及服务器信息获取。
 * 渠道账号存 upstream_provider，渠道下的模型按「一个模型一行」存 upstream_model。
 * API Key 存储前经 {@link EncryptUtil} 加密，查询结果中自动脱敏。
 * </p>
 *
 * @author xgateai
 * @since 2026/9/8
 */
@Slf4j
@Service
public class AdminManagementServiceImpl implements AdminManagementService {

    private final IUpstreamProviderDao upstreamProviderDao;
    private final IUpstreamModelDao upstreamModelDao;
    private final IModelChannelDao modelChannelDao;
    private final ProxyAdapter proxyAdapter;
    private final EncryptUtil encryptUtil;
    private final InflightRegistry inflightRegistry;
    private final Cache<String, Object> channelCache;
    private final Cache<String, List> routeCache;

    public AdminManagementServiceImpl(IUpstreamProviderDao upstreamProviderDao,
                                       IUpstreamModelDao upstreamModelDao,
                                       IModelChannelDao modelChannelDao,
                                       ProxyAdapter proxyAdapter,
                                       EncryptUtil encryptUtil,
                                       InflightRegistry inflightRegistry,
                                       Cache<String, Object> channelCache,
                                       Cache<String, List> routeCache) {
        this.upstreamProviderDao = upstreamProviderDao;
        this.upstreamModelDao = upstreamModelDao;
        this.modelChannelDao = modelChannelDao;
        this.proxyAdapter = proxyAdapter;
        this.encryptUtil = encryptUtil;
        this.inflightRegistry = inflightRegistry;
        this.channelCache = channelCache;
        this.routeCache = routeCache;
    }

    // ==================== 渠道管理（含模型） ====================

    @Override
    public List<Map<String, Object>> listProviders() {
        List<UpstreamProvider> providers = upstreamProviderDao.selectList(
                new LambdaQueryWrapper<UpstreamProvider>()
                        .orderByDesc(UpstreamProvider::getEnabled)
                        .orderByAsc(UpstreamProvider::getId));

        List<UpstreamModel> models = upstreamModelDao.selectList(
                new LambdaQueryWrapper<UpstreamModel>().orderByAsc(UpstreamModel::getChannelId)
                        .orderByAsc(UpstreamModel::getId));
        Map<Long, List<Map<String, Object>>> modelGroup = new LinkedHashMap<>();
        for (UpstreamModel model : models) {
            modelGroup.computeIfAbsent(model.getChannelId(), k -> new ArrayList<>())
                    .add(toModelMap(model));
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (UpstreamProvider provider : providers) {
            Map<String, Object> channelMap = new LinkedHashMap<>();
            channelMap.put("id", provider.getId());
            channelMap.put("name", provider.getName());
            channelMap.put("baseUrl", provider.getBaseUrl());
            // 明文回显：供编辑弹窗直接展示，不参与对外鉴权
            channelMap.put("apiKey", decryptQuietly(provider.getApiKey()));
            channelMap.put("enabled", provider.getEnabled());
            channelMap.put("remark", provider.getRemark());
            channelMap.put("createdAt", provider.getCreatedAt() == null ? "-" : provider.getCreatedAt());
            channelMap.put("models", modelGroup.getOrDefault(provider.getId(), Collections.emptyList()));
            result.add(channelMap);
        }
        return result;
    }

    /**
     * 静默解密密钥，解密失败返回 null
     */
    private String decryptQuietly(String encrypted) {
        if (StrUtil.isBlank(encrypted)) {
            return null;
        }
        try {
            return encryptUtil.decrypt(encrypted);
        } catch (Exception e) {
            log.warn("API Key 解密失败, 返回空值");
            return null;
        }
    }

    @Override
    @Transactional
    public void saveProvider(ProviderDTO dto) {
        boolean isNew = dto.getId() == null;
        UpstreamProvider provider = isNew
                ? new UpstreamProvider()
                : upstreamProviderDao.selectById(dto.getId());

        if (!isNew && provider == null) {
            throw new ResourceNotFoundException("Provider", dto.getId());
        }
        if (isNew && StrUtil.isBlank(dto.getApiKey())) {
            throw new IllegalArgumentException("apiKey 不能为空");
        }

        List<ProviderModelDTO> modelItems = dto.getModels() == null
                ? Collections.emptyList() : dto.getModels();

        // 校验模型项：名称去空、去重、禁止逗号
        Map<String, ProviderModelDTO> nameMap = new LinkedHashMap<>();
        for (ProviderModelDTO item : modelItems) {
            String modelName = StrUtil.trim(item.getModelName());
            if (StrUtil.isBlank(modelName)) {
                continue;
            }
            if (modelName.contains(",")) {
                throw new IllegalArgumentException("模型名不能包含英文逗号，一个模型请单独录入一行: " + modelName);
            }
            if (nameMap.containsKey(modelName)) {
                throw new IllegalArgumentException("同一渠道下模型重复: " + modelName);
            }
            item.setModelName(modelName);
            nameMap.put(modelName, item);
        }

        provider.setName(StrUtil.trim(dto.getName()));
        provider.setBaseUrl(trimTrailingSlash(StrUtil.trim(dto.getBaseUrl())));
        provider.setEnabled(defaultIfNull(dto.getEnabled(), CommonConstant.ENABLED));
        provider.setRemark(dto.getRemark());
        if (isNew && provider.getCreatedAt() == null) {
            provider.setCreatedAt(DateUtil.format(DateUtil.date(), CommonConstant.DATETIME_FORMAT));
        }
        if (StrUtil.isNotBlank(dto.getApiKey())) {
            provider.setApiKey(encryptUtil.encrypt(dto.getApiKey()));
        }

        if (isNew) {
            upstreamProviderDao.insert(provider);
            log.info("新增渠道: {} ({})", provider.getName(), provider.getId());
        } else {
            upstreamProviderDao.updateById(provider);
            log.info("更新渠道: {} ({})", provider.getName(), provider.getId());
        }

        syncModels(provider.getId(), nameMap);
        routeCache.invalidateAll();
    }

    /**
     * 对渠道下的模型做差量同步：新增缺失行、删除多余行、更新保留行
     */
    private void syncModels(Long channelId, Map<String, ProviderModelDTO> targetMap) {
        List<UpstreamModel> existing = upstreamModelDao.selectList(
                new LambdaQueryWrapper<UpstreamModel>().eq(UpstreamModel::getChannelId, channelId));
        Map<String, UpstreamModel> existingByName = new HashMap<>();
        for (UpstreamModel model : existing) {
            existingByName.put(model.getModelName(), model);
        }

        // 1. 处理目标中的模型
        for (Map.Entry<String, ProviderModelDTO> entry : targetMap.entrySet()) {
            String modelName = entry.getKey();
            ProviderModelDTO item = entry.getValue();
            UpstreamModel existed = existingByName.remove(modelName);
            if (existed == null) {
                UpstreamModel model = new UpstreamModel();
                model.setChannelId(channelId);
                model.setModelName(modelName);
                model.setEnabled(defaultIfNull(item.getEnabled(), CommonConstant.ENABLED));
                model.setRemark(item.getRemark());
                model.setFailCount(0);
                model.setCreatedAt(DateUtil.format(DateUtil.date(), CommonConstant.DATETIME_FORMAT));
                upstreamModelDao.insert(model);
                log.info("新增模型: 渠道({}) - {}", channelId, modelName);
            } else {
                existed.setEnabled(defaultIfNull(item.getEnabled(), CommonConstant.ENABLED));
                existed.setRemark(item.getRemark());
                upstreamModelDao.updateById(existed);
            }
        }

        // 2. 删除目标中已移除的模型
        for (UpstreamModel removed : existingByName.values()) {
            upstreamModelDao.deleteById(removed.getId());
            log.info("删除模型: 渠道({}) - {}", channelId, removed.getModelName());
        }
    }

    @Override
    @Transactional
    public void deleteProvider(Long id) {
        upstreamProviderDao.deleteById(id);
        upstreamModelDao.delete(
                new LambdaQueryWrapper<UpstreamModel>().eq(UpstreamModel::getChannelId, id));
        log.info("删除渠道及其模型: {}", id);
        routeCache.invalidateAll();
    }

    @Override
    public List<Map<String, Object>> testProvider(Long id) {
        UpstreamProvider provider = upstreamProviderDao.selectById(id);
        if (provider == null) {
            throw new ResourceNotFoundException("Provider", id);
        }
        List<UpstreamModel> models = upstreamModelDao.selectList(
                new LambdaQueryWrapper<UpstreamModel>()
                        .eq(UpstreamModel::getChannelId, id)
                        .orderByAsc(UpstreamModel::getId));
        if (models.isEmpty()) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> results = new ArrayList<>();
        for (UpstreamModel model : models) {
            results.add(testOne(provider, model));
        }
        return results;
    }

    @Override
    public Map<String, Object> testModel(Long modelId) {
        UpstreamModel model = upstreamModelDao.selectById(modelId);
        if (model == null) {
            throw new ResourceNotFoundException("Model", modelId);
        }
        UpstreamProvider provider = upstreamProviderDao.selectById(model.getChannelId());
        if (provider == null) {
            throw new ResourceNotFoundException("Provider", model.getChannelId());
        }
        return testOne(provider, model);
    }

    @Override
    public List<Map<String, Object>> testAllProviders() {
        List<UpstreamProvider> providers = upstreamProviderDao.selectList(
                new LambdaQueryWrapper<UpstreamProvider>().orderByAsc(UpstreamProvider::getId));
        List<Map<String, Object>> results = new ArrayList<>();
        for (UpstreamProvider provider : providers) {
            List<UpstreamModel> models = upstreamModelDao.selectList(
                    new LambdaQueryWrapper<UpstreamModel>()
                            .eq(UpstreamModel::getChannelId, provider.getId())
                            .orderByAsc(UpstreamModel::getId));
            for (UpstreamModel model : models) {
                Map<String, Object> item = testOne(provider, model);
                item.put("channelId", provider.getId());
                item.put("channelName", provider.getName());
                results.add(item);
            }
        }
        return results;
    }

    @Override
    public Map<String, Object> fetchProviderModels(FetchModelsDTO dto) {
        if (dto == null || StrUtil.isBlank(dto.getBaseUrl())) {
            throw new IllegalArgumentException("Base URL 不能为空");
        }
        boolean usedStoredKey = false;
        String apiKey = StrUtil.trim(dto.getApiKey());
        if (StrUtil.isBlank(apiKey)) {
            if (dto.getId() == null) {
                throw new IllegalArgumentException("API Key 不能为空（编辑时可留空复用已保存 Key）");
            }
            UpstreamProvider provider = upstreamProviderDao.selectById(dto.getId());
            if (provider == null) {
                throw new ResourceNotFoundException("Provider", dto.getId());
            }
            apiKey = encryptUtil.decrypt(provider.getApiKey());
            usedStoredKey = true;
        }
        if (StrUtil.isBlank(apiKey)) {
            throw new IllegalArgumentException("API Key 不能为空");
        }

        String baseUrl = trimTrailingSlash(StrUtil.trim(dto.getBaseUrl()));
        List<String> models;
        try {
            models = proxyAdapter.fetchModelNames(baseUrl, apiKey);
        } catch (Exception e) {
            throw new IllegalArgumentException("拉取模型列表失败: " + resolveMessage(e));
        }
        if (models == null) {
            models = new ArrayList<>();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("usedStoredKey", usedStoredKey);
        result.put("models", models);
        return result;
    }

    private String resolveMessage(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return StrUtil.isBlank(message) ? cause.getClass().getSimpleName() : message;
    }

    private Map<String, Object> testOne(UpstreamProvider provider, UpstreamModel model) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("modelId", model.getId());
        result.put("modelName", model.getModelName());
        try {
            var json = proxyAdapter.testProvider(provider, model.getModelName());
            result.put("ok", json.getBooleanValue("ok"));
            result.put("latencyMs", json.get("latencyMs"));
            result.put("message", json.getString("message"));
        } catch (Exception e) {
            result.put("ok", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    // ==================== 客户/API KEY 管理 ====================

    @Override
    public List<ModelChannel> listChannels() {
        return modelChannelDao.selectList(
                new LambdaQueryWrapper<ModelChannel>().orderByAsc(ModelChannel::getId));
    }

    @Override
    public String saveChannel(ChannelDTO dto) {
        boolean isNew = dto.getId() == null;
        ModelChannel channel = isNew
                ? new ModelChannel()
                : modelChannelDao.selectById(dto.getId());

        if (!isNew && channel == null) {
            throw new ResourceNotFoundException("Channel", dto.getId());
        }

        String generatedKey = null;
        if (isNew) {
            generatedKey = generateApiKey();
            channel.setApiKey(generatedKey);
        }

        channel.setPublicModelName(StrUtil.trim(dto.getPublicModelName()));
        channel.setModelName(StrUtil.blankToDefault(StrUtil.trim(dto.getModelName()), ""));
        channel.setEnabled(defaultIfNull(dto.getEnabled(), CommonConstant.ENABLED));
        channel.setRemark(dto.getRemark());

        if (isNew) {
            channel.setCreatedAt(DateUtil.format(DateUtil.date(), CommonConstant.DATETIME_FORMAT));
            modelChannelDao.insert(channel);
            log.info("新增客户: {} ({}) [default 全池]",
                    channel.getPublicModelName(), channel.getId());
        } else {
            modelChannelDao.updateById(channel);
            log.info("更新客户: {} ({})", channel.getPublicModelName(), channel.getId());
        }

        channelCache.invalidateAll();
        return generatedKey;
    }

    @Override
    public void deleteChannel(Long id) {
        modelChannelDao.deleteById(id);
        log.info("删除客户: {}", id);
        channelCache.invalidateAll();
    }

    // ==================== 服务器信息 ====================

    @Override
    public Map<String, Object> getServerInfo(int port) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("host", resolveLanIp());
        info.put("port", port);
        return info;
    }

    // ==================== 并发控制 ====================

    @Override
    public List<Map<String, Object>> listConcurrencyModels() {
        // 仅启用渠道下的启用模型参与路由，按优先级（fail_count 升序，id 升序）排列
        List<UpstreamModel> models = upstreamModelDao.selectList(
                new LambdaQueryWrapper<UpstreamModel>()
                        .eq(UpstreamModel::getEnabled, CommonConstant.ENABLED)
                        .orderByAsc(UpstreamModel::getFailCount)
                        .orderByAsc(UpstreamModel::getId));

        Map<Long, String> channelNames = new HashMap<>();
        for (UpstreamProvider provider : upstreamProviderDao.selectList(
                new LambdaQueryWrapper<UpstreamProvider>()
                        .eq(UpstreamProvider::getEnabled, CommonConstant.ENABLED))) {
            channelNames.put(provider.getId(), provider.getName());
        }

        List<Map<String, Object>> result = new ArrayList<>();
        int priority = 1;
        for (UpstreamModel model : models) {
            if (!channelNames.containsKey(model.getChannelId())) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("modelId", model.getId());
            row.put("channelName", channelNames.get(model.getChannelId()));
            row.put("modelName", model.getModelName());
            row.put("failCount", defaultIfNull(model.getFailCount(), 0));
            row.put("maxConcurrency", defaultIfNull(model.getMaxConcurrency(), 0));
            row.put("inFlight", inflightRegistry.inFlight(model.getId()));
            row.put("priority", priority++);
            result.add(row);
        }
        return result;
    }

    @Override
    public void updateConcurrencyLimit(Long modelId, int maxConcurrency) {
        UpstreamModel model = upstreamModelDao.selectById(modelId);
        if (model == null) {
            throw new ResourceNotFoundException("Model", modelId);
        }
        UpstreamModel update = new UpstreamModel();
        update.setId(modelId);
        update.setMaxConcurrency(maxConcurrency);
        upstreamModelDao.updateById(update);
        log.info("更新并发上限: model({}) {} -> {}", modelId, model.getModelName(), maxConcurrency);
        routeCache.invalidateAll();
    }

    // ==================== 私有辅助方法 ====================

    private Map<String, Object> toModelMap(UpstreamModel model) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", model.getId());
        map.put("channelId", model.getChannelId());
        map.put("modelName", model.getModelName());
        map.put("enabled", model.getEnabled());
        map.put("failCount", model.getFailCount() == null ? 0 : model.getFailCount());
        map.put("remark", model.getRemark());
        map.put("createdAt", model.getCreatedAt() == null ? "-" : model.getCreatedAt());
        return map;
    }

    private String trimTrailingSlash(String url) {
        while (url.length() > 1 && url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    private int defaultIfNull(Integer value, int defaultValue) {
        return value != null ? value : defaultValue;
    }

    private String generateApiKey() {
        return "xgate-" + RandomUtil.randomString(32);
    }

    /**
     * 解析本机局域网 IPv4 地址，用于生成服务接入地址提示。
     * 跳过 docker/veth 等虚拟网卡，优先返回真实网卡 IP。
     */
    private String resolveLanIp() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni == null || !ni.isUp() || ni.isLoopback() || ni.isVirtual()
                        || ni.getName().startsWith("veth") || ni.getName().startsWith("docker")) {
                    continue;
                }
                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address inet4
                            && !inet4.isLoopbackAddress() && inet4.isSiteLocalAddress()) {
                        return inet4.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("解析本机 IP 失败", e);
        }
        return "localhost";
    }
}
