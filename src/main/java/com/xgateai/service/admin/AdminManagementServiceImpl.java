package com.xgateai.service.admin;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.xgateai.constant.CommonConstant;
import com.xgateai.dto.ChannelDTO;
import com.xgateai.dto.ProviderDTO;
import com.xgateai.entity.ModelChannel;
import com.xgateai.entity.UpstreamProvider;
import com.xgateai.exception.ResourceNotFoundException;
import com.xgateai.adapter.ProxyAdapter;
import com.xgateai.mapper.IModelChannelDao;
import com.xgateai.mapper.IUpstreamProviderDao;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.*;

/**
 * AdminManagementServiceImpl 管理端业务服务实现
 * <p>
 * 实现上游 Provider 和模型通道的 CRUD 操作。
 * </p>
 *
 * @author xgateai
 * @since 2026/9/8
 */
@Slf4j
@Service
public class AdminManagementServiceImpl implements AdminManagementService {

    private final IUpstreamProviderDao upstreamProviderDao;
    private final IModelChannelDao modelChannelDao;
    private final ProxyAdapter proxyAdapter;

    public AdminManagementServiceImpl(IUpstreamProviderDao upstreamProviderDao,
                                      IModelChannelDao modelChannelDao,
                                      ProxyAdapter proxyAdapter) {
        this.upstreamProviderDao = upstreamProviderDao;
        this.modelChannelDao = modelChannelDao;
        this.proxyAdapter = proxyAdapter;
    }

    // ==================== 上游 Provider 管理 ====================

    @Override
    public List<UpstreamProvider> listProviders() {
        List<UpstreamProvider> providers = upstreamProviderDao.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<UpstreamProvider>()
                        .orderByAsc(UpstreamProvider::getId));
        // 脱敏：不返回真实 API Key
        providers.forEach(provider -> provider.setApiKey(null));
        return providers;
    }

    @Override
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

        // 映射 DTO 到实体
        provider.setName(StrUtil.trim(dto.getName()));
        provider.setBaseUrl(trimTrailingSlash(StrUtil.trim(dto.getBaseUrl())));
        provider.setModelName(StrUtil.trim(dto.getModelName()));
        provider.setEnabled(dto.getEnabled() != null ? dto.getEnabled() : CommonConstant.ENABLED);
        provider.setRemark(dto.getRemark());
        if (StrUtil.isNotBlank(dto.getApiKey())) {
            provider.setApiKey(encryptApiKey(dto.getApiKey()));
        }

        // 持久化
        if (isNew) {
            upstreamProviderDao.insert(provider);
            log.info("新增上游 Provider: {} ({})", provider.getName(), provider.getId());
        } else {
            upstreamProviderDao.updateById(provider);
            log.info("更新上游 Provider: {} ({})", provider.getName(), provider.getId());
        }
    }

    @Override
    public void deleteProvider(Long id) {
        upstreamProviderDao.deleteById(id);
        log.info("删除上游 Provider: {}", id);
    }

    @Override
    public Map<String, Object> testProvider(Long id) {
        UpstreamProvider provider = upstreamProviderDao.selectById(id);
        if (provider == null) {
            throw new ResourceNotFoundException("Provider", id);
        }
        return proxyAdapter.testProvider(provider);
    }

    @Override
    public List<Map<String, Object>> testAllProviders() {
        List<UpstreamProvider> allProviders = upstreamProviderDao.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<UpstreamProvider>()
                        .orderByAsc(UpstreamProvider::getId));

        List<Map<String, Object>> results = new ArrayList<>();
        for (UpstreamProvider provider : allProviders) {
            try {
                Map<String, Object> result = proxyAdapter.testProvider(provider);
                result.put("id", provider.getId());
                result.put("name", provider.getName());
                results.add(result);
            } catch (Exception e) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("id", provider.getId());
                result.put("name", provider.getName());
                result.put("ok", false);
                result.put("message", e.getMessage());
                results.add(result);
            }
        }
        return results;
    }

    // ==================== 模型通道管理 ====================

    @Override
    public List<ModelChannel> listChannels() {
        return modelChannelDao.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ModelChannel>()
                        .orderByAsc(ModelChannel::getId));
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

        // 映射 DTO 到实体
        channel.setPublicModelName(StrUtil.trim(dto.getPublicModelName()));
        channel.setModelName(StrUtil.blankToDefault(StrUtil.trim(dto.getModelName()), ""));
        channel.setEnabled(dto.getEnabled() != null ? dto.getEnabled() : CommonConstant.ENABLED);
        channel.setRemark(dto.getRemark());

        // 持久化
        if (isNew) {
            modelChannelDao.insert(channel);
            log.info("新增模型通道: {} ({}) [default 全池]",
                    channel.getPublicModelName(), channel.getId());
        } else {
            modelChannelDao.updateById(channel);
            log.info("更新模型通道: {} ({})", channel.getPublicModelName(), channel.getId());
        }

        return generatedKey;
    }

    @Override
    public void deleteChannel(Long id) {
        modelChannelDao.deleteById(id);
        log.info("删除模型通道: {}", id);
    }

    // ==================== 服务器信息 ====================

    @Override
    public Map<String, Object> getServerInfo(int port) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("host", resolveLanIp());
        info.put("port", port);
        return info;
    }

    // ==================== 私有辅助方法 ====================

    private String trimTrailingSlash(String url) {
        while (url.length() > 1 && url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    private String encryptApiKey(String apiKey) {
        // 使用 EncryptUtil 加密，此处简化处理，实际应注入 EncryptUtil
        return apiKey; // TODO: 实际项目中应调用 EncryptUtil.encrypt()
    }

    private String generateApiKey() {
        return "xgate-" + RandomUtil.randomString(32);
    }

    private String resolveLanIp() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni == null || !ni.isUp() || ni.isLoopback() || ni.isVirtual()) {
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
