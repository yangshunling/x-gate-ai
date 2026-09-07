package com.xgateai.gatewaybridge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xgateai.application.entity.ChannelUpstream;
import com.xgateai.application.entity.ModelChannel;
import com.xgateai.application.entity.UpstreamProvider;
import com.xgateai.application.exceptions.CommonException;
import com.xgateai.gatewaybridge.config.GatewayConfig;
import com.xgateai.mapper.ChannelUpstreamMapper;
import com.xgateai.mapper.ModelChannelMapper;
import com.xgateai.mapper.UpstreamProviderMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * <p>
 * GatewayRouter 网关路由调度器
 * 维护对外模型通道 -> 上游 Provider 的内存映射，实现轮询与冷却熔断
 * </p>
 *
 * @author xgateai
 * @since 2026/9/4
 */
@Slf4j
@Service
public class GatewayRouter {

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
     * 上游 Provider Mapper
     */
    @Resource
    UpstreamProviderMapper upstreamProviderMapper;

    /**
     * 网关配置
     */
    @Resource
    GatewayConfig gatewayConfig;

    /**
     * 对外模型名 -> 通道缓存（仅启用）
     */
    private final ConcurrentHashMap<String, ModelChannel> channelCache = new ConcurrentHashMap<>();

    /**
     * 通道 ID -> 启用的上游列表缓存
     */
    private final ConcurrentHashMap<Long, List<UpstreamProvider>> providerCache = new ConcurrentHashMap<>();

    /**
     * 通道 ID -> 轮询计数器
     */
    private final ConcurrentHashMap<Long, AtomicLong> roundRobinCounter = new ConcurrentHashMap<>();

    /**
     * 上游 ID -> 冷却截止时间戳（毫秒）
     */
    private final ConcurrentHashMap<Long, Long> cooldownMap = new ConcurrentHashMap<>();

    /**
     * 初始化：加载路由缓存
     */
    @PostConstruct
    public void init() {
        refresh();
    }

    /**
     * 刷新路由缓存（配置变更后调用，实时生效）
     */
    public synchronized void refresh() {
        channelCache.clear();
        providerCache.clear();
        roundRobinCounter.clear();
        cooldownMap.clear();

        // 加载启用的通道
        List<ModelChannel> channels = modelChannelMapper.selectList(
                new LambdaQueryWrapper<ModelChannel>().eq(ModelChannel::getEnabled, 1));
        log.info("[DEBUG] 从数据库加载到 {} 个启用的通道", channels.size());
        for (ModelChannel channel : channels) {
            channelCache.put(channel.getPublicModelName(), channel);
            log.info("[DEBUG] 通道: {}, id: {}", channel.getPublicModelName(), channel.getId());
            // 加载该通道绑定的启用上游，按 sort 升序
            List<ChannelUpstream> binds = channelUpstreamMapper.selectList(
                    new LambdaQueryWrapper<ChannelUpstream>()
                            .eq(ChannelUpstream::getChannelId, channel.getId())
                            .orderByAsc(ChannelUpstream::getSort));
            log.info("[DEBUG] 通道 {} 绑定了 {} 个上游", channel.getPublicModelName(), binds.size());
            List<UpstreamProvider> providers = new ArrayList<>();
            for (ChannelUpstream bind : binds) {
                UpstreamProvider provider = upstreamProviderMapper.selectById(bind.getProviderId());
                if (provider != null) {
                    log.info("[DEBUG]   上游: {}, enabled: {}, baseUrl: {}, modelName: {}", 
                            provider.getName(), provider.getEnabled(), provider.getBaseUrl(), provider.getModelName());
                    if (provider.getEnabled() == 1) {
                        providers.add(provider);
                    }
                }
            }
            providers.sort(Comparator.comparingInt(p -> {
                // 以绑定 sort 排序，保留绑定顺序
                return 0;
            }));
            if (!providers.isEmpty()) {
                providerCache.put(channel.getId(), providers);
                log.info("[DEBUG] 通道 {} 有 {} 个可用上游", channel.getPublicModelName(), providers.size());
            } else {
                log.warn("[DEBUG] 通道 {} 没有可用上游！", channel.getPublicModelName());
            }
        }
        log.info("网关路由刷新完成，对外模型数量: {}, 可用通道数: {}", channelCache.size(), providerCache.size());
    }

    /**
     * 获取对外模型候选上游列表（已跳过冷却上游，按轮询起点旋转）
     *
     * @param publicModel 对外模型名
     * @return 候选上游列表（按轮询旋转后的顺序）
     */
    public List<UpstreamProvider> getCandidates(String publicModel) {
        log.info("[DEBUG] getCandidates 被调用, publicModel: {}, 缓存中的模型: {}", publicModel, channelCache.keySet());
        ModelChannel channel = channelCache.get(publicModel);
        if (channel == null) {
            log.error("[DEBUG] 找不到通道: {}", publicModel);
            throw new CommonException("对外模型不存在或未启用: " + publicModel);
        }
        List<UpstreamProvider> all = providerCache.get(channel.getId());
        log.info("[DEBUG] 通道 {} 的上游列表: {}", publicModel, all != null ? all.size() : "null");
        if (all == null || all.isEmpty()) {
            log.error("[DEBUG] 通道 {} 没有可用上游", publicModel);
            throw new CommonException("对外模型未绑定可用的上游: " + publicModel);
        }
        long now = System.currentTimeMillis();
        List<UpstreamProvider> available = new ArrayList<>();
        for (UpstreamProvider provider : all) {
            Long coolUntil = cooldownMap.get(provider.getId());
            if (coolUntil == null || coolUntil <= now) {
                available.add(provider);
            }
        }
        if (available.isEmpty()) {
            throw new CommonException("对外模型全部上游处于冷却状态: " + publicModel);
        }
        // 轮询旋转起点
        AtomicLong counter = roundRobinCounter.computeIfAbsent(channel.getId(), k -> new AtomicLong(0));
        int size = available.size();
        int start = (int) (counter.getAndIncrement() % size);
        List<UpstreamProvider> rotated = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            rotated.add(available.get((start + i) % size));
        }
        return rotated;
    }

    /**
     * 标记上游进入冷却状态
     *
     * @param providerId 上游 ID
     */
    public void markCooldown(Long providerId) {
        long until = System.currentTimeMillis() + gatewayConfig.getCoolDownSeconds() * 1000;
        cooldownMap.put(providerId, until);
        log.warn("上游 {} 已进入冷却状态，截止: {}ms", providerId, until);
    }

    /**
     * 获取全部启用的对外模型名列表
     *
     * @return 对外模型名集合
     */
    public List<String> listPublicModels() {
        return new ArrayList<>(channelCache.keySet());
    }
}
