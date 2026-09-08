package com.xgateai.adminbridge.service;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xgateai.adminbridge.component.EncryptUtil;
import com.xgateai.application.constant.CommonConstant;
import com.xgateai.application.entity.CallLog;
import com.xgateai.application.entity.ModelChannel;
import com.xgateai.application.entity.UpstreamProvider;
import com.xgateai.application.exceptions.CommonException;
import com.xgateai.application.model.dto.ChannelDTO;
import com.xgateai.application.model.dto.ProviderDTO;
import com.xgateai.gatewaybridge.adapter.ProxyAdapter;
import com.xgateai.gatewaybridge.service.CallLogService;
import com.xgateai.mapper.ModelChannelMapper;
import com.xgateai.mapper.UpstreamProviderMapper;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * <p>
 * AdminService 管理端服务：上游渠道/对外客户 API Key 管理、调用日志与看板
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
     * 新增或更新上游 Provider：新增时 apiKey 必填并加密入库；编辑时 apiKey 为空保留原值。
     * 路由池为全量动态的：网关按客户端请求的 model 在全部启用的渠道中精确匹配，
     * 因此新增/修改/启停渠道无需任何手动绑定操作，改动即时生效。
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
     * 删除上游 Provider
     */
    public void deleteProvider(Long id) {
        upstreamProviderMapper.deleteById(id);
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
     * 查询全部对外客户 API Key，附带该 Key 可路由到的渠道（按其限定模型或全池计算）
     */
    public List<JSONObject> listChannels() {
        List<ModelChannel> channels = modelChannelMapper.selectList(
                new LambdaQueryWrapper<ModelChannel>().orderByAsc(ModelChannel::getId));
        List<UpstreamProvider> enabled = upstreamProviderMapper.selectList(
                new LambdaQueryWrapper<UpstreamProvider>().eq(UpstreamProvider::getEnabled, CommonConstant.ENABLED)
                        .orderByAsc(UpstreamProvider::getId));
        List<JSONObject> list = new ArrayList<>();
        for (ModelChannel channel : channels) {
            String pinned = StrUtil.blankToDefault(channel.getModelName(), "");
            List<JSONObject> providers = new ArrayList<>();
            for (UpstreamProvider p : enabled) {
                // default(全池) 或限定模型匹配时才可作为该 Key 的路由候选
                if (StrUtil.isNotBlank(pinned) && !pinned.equals(p.getModelName())) continue;
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
            obj.put("model_name", channel.getModelName());
            obj.put("api_key", channel.getApiKey());
            obj.put("enabled", channel.getEnabled());
            obj.put("strategy", channel.getStrategy());
            obj.put("remark", channel.getRemark());
            obj.put("providers", providers);
            list.add(obj);
        }
        return list;
    }

    /**
     * 新增或更新对外客户 API Key。
     * 新增时自动生成专属 Key；modelName 为空表示 default（可调用池内所有模型），否则仅限该模型。
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
        channel.setModelName(StrUtil.blankToDefault(StrUtil.trim(dto.getModelName()), ""));
        channel.setEnabled(dto.getEnabled() == null ? CommonConstant.ENABLED : dto.getEnabled());
        channel.setStrategy(STRATEGY_ROUND_ROBIN);
        channel.setRemark(dto.getRemark());

        if (isNew) modelChannelMapper.insert(channel);
        else modelChannelMapper.updateById(channel);
        log.info("{}客户 API Key: {} ({}){}", isNew ? "新增" : "更新",
                channel.getPublicModelName(), channel.getId(),
                StrUtil.isBlank(channel.getModelName()) ? " [default 全池]" : " [限定 " + channel.getModelName() + "]");
        return generatedKey;
    }

    /**
     * 删除对外客户 API Key
     */
    public void deleteChannel(Long id) {
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
    public List<JSONObject> customerStats() { return callLogService.customerStats(); }

    /**
     * 当前部署服务信息：返回本机局域网 IP 与端口，供前端展示接入 Base URL
     */
    public JSONObject serverInfo(HttpServletRequest request) {
        JSONObject info = new JSONObject();
        info.put("host", resolveLanIp());
        info.put("port", request.getServerPort());
        return info;
    }

    /**
     * 解析本机第一个非回环的 IPv4 局域网地址（优先 site-local），失败回退 localhost
     */
    private String resolveLanIp() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                NetworkInterface ni = ifaces.nextElement();
                if (ni == null || !ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
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
