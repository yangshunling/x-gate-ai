package com.xgateai.adminbridge.service;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xgateai.adminbridge.component.EncryptUtil;
import com.xgateai.application.constant.CommonConstant;
import com.xgateai.application.entity.CallLog;
import com.xgateai.application.entity.ChannelUpstream;
import com.xgateai.application.entity.ModelChannel;
import com.xgateai.application.entity.UpstreamProvider;
import com.xgateai.application.exceptions.CommonException;
import com.xgateai.application.model.dto.ChannelDTO;
import com.xgateai.application.model.dto.ProviderDTO;
import com.xgateai.gatewaybridge.adapter.ProxyAdapter;
import com.xgateai.gatewaybridge.service.CallLogService;
import com.xgateai.mapper.ChannelUpstreamMapper;
import com.xgateai.mapper.ModelChannelMapper;
import com.xgateai.mapper.UpstreamProviderMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * <p>
 * AdminService 管理端服务：Provider/通道/API Key 管理、调用日志与看板
 * </p>
 *
 * @author xgateai
 * @since 2026/9/4
 */
@Slf4j
@Service
public class AdminService {

    private static final String STRATEGY_ROUND_ROBIN = "ROUND_ROBIN";

    @Resource
    UpstreamProviderMapper upstreamProviderMapper;

    @Resource
    ModelChannelMapper modelChannelMapper;

    @Resource
    ChannelUpstreamMapper channelUpstreamMapper;

    @Resource
    EncryptUtil encryptUtil;

    @Resource
    CallLogService callLogService;

    @Resource
    ProxyAdapter proxyAdapter;

    /**
     * 查询全部上游 Provider 列表，apiKey 脱敏为 null 不外泄
     */
    public List<UpstreamProvider> listProviders() {
        List<UpstreamProvider> providers = upstreamProviderMapper.selectList(
                new LambdaQueryWrapper<UpstreamProvider>().orderByAsc(UpstreamProvider::getId));
        for (UpstreamProvider p : providers) p.setApiKey(null);
        return providers;
    }

    /**
     * 新增或更新上游 Provider：新增时 apiKey 必填并加密入库；编辑时 apiKey 为空保留原值
     */
    public void saveProvider(ProviderDTO dto) {
        boolean isNew = dto.getId() == null;
        UpstreamProvider provider = isNew ? new UpstreamProvider()
                : upstreamProviderMapper.selectById(dto.getId());
        if (!isNew && provider == null) throw new CommonException("Provider 不存在");
        if (isNew && StrUtil.isBlank(dto.getApiKey())) throw new CommonException("apiKey 不能为空");

        provider.setName(StrUtil.trim(dto.getName()));
        String baseUrl = StrUtil.trim(dto.getBaseUrl());
        while (baseUrl.length() > 1 && baseUrl.endsWith(CommonConstant.SLASH))
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        provider.setBaseUrl(baseUrl);
        provider.setModelName(StrUtil.trim(dto.getModelName()));
        provider.setEnabled(dto.getEnabled() == null ? CommonConstant.ENABLED : dto.getEnabled());
        provider.setRemark(dto.getRemark());
        if (StrUtil.isNotBlank(dto.getApiKey())) provider.setApiKey(encryptUtil.encrypt(dto.getApiKey()));

        if (isNew) upstreamProviderMapper.insert(provider);
        else upstreamProviderMapper.updateById(provider);
        log.info("{}上游 Provider: {} ({})", isNew ? "新增" : "更新", provider.getName(), provider.getId());
    }

    /**
     * 删除上游 Provider，同时删除其在各通道的绑定
     */
    public void deleteProvider(Long id) {
        channelUpstreamMapper.delete(new LambdaQueryWrapper<ChannelUpstream>().eq(ChannelUpstream::getProviderId, id));
        upstreamProviderMapper.deleteById(id);
    }

    /**
     * 查询全部启用的上游 Provider（供通道绑定时下拉），apiKey 脱敏
     */
    public List<UpstreamProvider> listEnabledProviders() {
        List<UpstreamProvider> providers = upstreamProviderMapper.selectList(
                new LambdaQueryWrapper<UpstreamProvider>()
                        .eq(UpstreamProvider::getEnabled, CommonConstant.ENABLED)
                        .orderByAsc(UpstreamProvider::getId));
        for (UpstreamProvider p : providers) p.setApiKey(null);
        return providers;
    }

    /**
     * 连通性测试
     */
    public JSONObject testProvider(Long id) {
        UpstreamProvider provider = upstreamProviderMapper.selectById(id);
        if (provider == null) throw new CommonException("Provider 不存在");
        return proxyAdapter.testProvider(provider);
    }

    /**
     * 查询全部对外模型通道，附带绑定上游信息
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
                UpstreamProvider p = upstreamProviderMapper.selectById(bind.getProviderId());
                if (p == null) continue;
                providerIds.add(p.getId());
                JSONObject item = new JSONObject();
                item.put("id", p.getId());
                item.put("name", p.getName());
                item.put("model_name", p.getModelName());
                item.put("enabled", p.getEnabled());
                providers.add(item);
            }
            JSONObject obj = new JSONObject();
            obj.put("id", channel.getId());
            obj.put("public_model_name", channel.getPublicModelName());
            obj.put("api_key", channel.getApiKey());
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
     * 新增或更新对外模型通道，按 providerIds 顺序重建绑定
     * 新增时自动生成专属调用 Key
     */
    public String saveChannel(ChannelDTO dto) {
        boolean isNew = dto.getId() == null;
        String generatedKey = null;
        ModelChannel channel = isNew ? new ModelChannel()
                : modelChannelMapper.selectById(dto.getId());
        if (!isNew && channel == null) throw new CommonException("通道不存在");
        if (isNew) {
            generatedKey = "xgate-" + RandomUtil.randomString(32);
            channel.setApiKey(generatedKey);
        }
        channel.setPublicModelName(StrUtil.trim(dto.getPublicModelName()));
        channel.setEnabled(dto.getEnabled() == null ? CommonConstant.ENABLED : dto.getEnabled());
        channel.setStrategy(StrUtil.isBlank(dto.getStrategy()) ? STRATEGY_ROUND_ROBIN : dto.getStrategy());
        channel.setRemark(dto.getRemark());

        if (isNew) modelChannelMapper.insert(channel);
        else modelChannelMapper.updateById(channel);
        log.info("{}对外模型通道: {} ({})", isNew ? "新增" : "更新", channel.getPublicModelName(), channel.getId());

        Long channelId = channel.getId();
        channelUpstreamMapper.delete(new LambdaQueryWrapper<ChannelUpstream>().eq(ChannelUpstream::getChannelId, channelId));
        if (dto.getProviderIds() != null) {
            int sort = 0;
            for (Long providerId : dto.getProviderIds()) {
                if (providerId == null) continue;
                ChannelUpstream bind = new ChannelUpstream();
                bind.setChannelId(channelId);
                bind.setProviderId(providerId);
                bind.setWeight(CommonConstant.ONE);
                bind.setSort(sort++);
                channelUpstreamMapper.insert(bind);
            }
        }
        return generatedKey;
    }

    /**
     * 删除对外模型通道及其绑定
     */
    public void deleteChannel(Long id) {
        channelUpstreamMapper.delete(new LambdaQueryWrapper<ChannelUpstream>().eq(ChannelUpstream::getChannelId, id));
        modelChannelMapper.deleteById(id);
    }

    public Page<CallLog> queryLogs(String publicModel, String apiKeyName, String model,
                                   String dateFrom, String dateTo, Integer status,
                                   int pageNum, int pageSize) {
        return callLogService.pageQuery(publicModel, apiKeyName, model, dateFrom, dateTo, status, pageNum, pageSize);
    }

    public List<JSONObject> tokenTrend(int hours) { return callLogService.tokenTrend(hours); }
    public List<JSONObject> modelStats() { return callLogService.modelStats(); }
    public JSONObject dashboard() { return callLogService.dashboard(); }
}
