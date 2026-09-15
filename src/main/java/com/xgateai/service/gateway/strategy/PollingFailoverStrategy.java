package com.xgateai.service.gateway.strategy;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.xgateai.constant.CommonConstant;
import com.xgateai.entity.ModelChannel;
import com.xgateai.entity.UpstreamModel;
import com.xgateai.entity.UpstreamProvider;
import com.xgateai.entity.UpstreamRoute;
import com.xgateai.exception.BadRequestException;
import com.xgateai.constant.GatewayConstant;
import com.xgateai.mapper.IUpstreamModelDao;
import com.xgateai.mapper.IUpstreamProviderDao;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * PollingFailoverStrategy 轮询故障转移策略
 * <p>
 * 候选粒度为「渠道下的模型行」（upstream_model），同一渠道下的不同模型彼此独立；
 * 失败次数少的上游优先，失败时自动切换到下一个候选。路由规则：
 * <ol>
 *   <li>请求 model=auto 时，按全池自动路由</li>
 *   <li>指定具体模型名时，同名模型行组成优先段，池内其余模型行组成兜底段；优先段全部失败后自动落到兜底段，保证链路不断</li>
 *   <li>指定的模型名在池内不存在时直接拒绝，不做兜底</li>
 *   <li>候选按 fail_count 升序排列，失败越少越优先（模型行粒度）</li>
 *   <li>渠道被禁用时其下所有模型不参与候选</li>
 * </ol>
 * </p>
 *
 * @author xgateai
 * @since 2026/9/8
 */
@Slf4j
@Component
public class PollingFailoverStrategy implements UpstreamStrategy {

    private final IUpstreamModelDao upstreamModelDao;
    private final IUpstreamProviderDao upstreamProviderDao;
    private final Cache<String, List> routeCache;

    public PollingFailoverStrategy(IUpstreamModelDao upstreamModelDao,
                                   IUpstreamProviderDao upstreamProviderDao,
                                   Cache<String, List> routeCache) {
        this.upstreamModelDao = upstreamModelDao;
        this.upstreamProviderDao = upstreamProviderDao;
        this.routeCache = routeCache;
    }

    @Override
    public List<UpstreamRoute> selectCandidates(ModelChannel channel, String requestedModel) {
        // 自动路由：请求 model=auto 时路由池内所有启用模型
        boolean poolRouting = GatewayConstant.MODEL_POOL.equals(requestedModel);

        String cacheKey = channel.getId() + ":" + requestedModel;
        List<UpstreamRoute> cached = routeCache.getIfPresent(cacheKey);
        if (cached != null) {
            return cached;
        }

        // 查询池内全部启用模型行，按失败次数升序、ID 升序
        List<UpstreamModel> models = upstreamModelDao.selectList(
                new LambdaQueryWrapper<UpstreamModel>()
                        .eq(UpstreamModel::getEnabled, CommonConstant.ENABLED)
                        .orderByAsc(UpstreamModel::getFailCount)
                        .orderByAsc(UpstreamModel::getId));
        if (models.isEmpty()) {
            throw new BadRequestException("池内暂无任何启用的模型，请先在控制台为渠道配置并启用模型");
        }

        // 装载所有启用渠道，过滤禁用/不存在的渠道，组装路由候选
        Map<Long, UpstreamProvider> channelMap = loadEnabledChannels();
        List<UpstreamRoute> routes = new ArrayList<>();
        for (UpstreamModel model : models) {
            UpstreamProvider provider = channelMap.get(model.getChannelId());
            if (provider != null) {
                routes.add(new UpstreamRoute(provider, model));
            }
        }
        if (routes.isEmpty()) {
            throw new BadRequestException("池内渠道均已停用，无可用模型");
        }

        // 全池路由：直接返回全部启用模型行
        if (poolRouting) {
            routeCache.put(cacheKey, routes);
            return routes;
        }

        // 指定具体模型：同名模型行组成优先段，其余模型行组成兜底段；优先段全部失败后落到兜底段
        List<UpstreamRoute> preferred = new ArrayList<>();
        List<UpstreamRoute> fallback = new ArrayList<>();
        for (UpstreamRoute route : routes) {
            if (requestedModel.equals(route.getModelName())) {
                preferred.add(route);
            } else {
                fallback.add(route);
            }
        }
        // 指定的模型名在池内不存在：直接拒绝，避免任意模型名都能兜底命中
        if (preferred.isEmpty()) {
            throw new BadRequestException(String.format("池内暂无启用的渠道提供模型: %s", requestedModel));
        }

        List<UpstreamRoute> candidates = new ArrayList<>(preferred.size() + fallback.size());
        candidates.addAll(preferred);
        candidates.addAll(fallback);
        routeCache.put(cacheKey, candidates);
        return candidates;
    }

    /**
     * 装载所有启用的渠道，以 id 为键
     */
    private Map<Long, UpstreamProvider> loadEnabledChannels() {
        List<UpstreamProvider> providers = upstreamProviderDao.selectList(
                new LambdaQueryWrapper<UpstreamProvider>()
                        .eq(UpstreamProvider::getEnabled, CommonConstant.ENABLED));
        return providers.stream()
                .collect(Collectors.toMap(UpstreamProvider::getId, p -> p, (a, b) -> a, HashMap::new));
    }
}
