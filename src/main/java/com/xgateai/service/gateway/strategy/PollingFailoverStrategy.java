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
 *   <li>请求 model=default 时，按全池路由</li>
 *   <li>否则精确匹配模型行 model_name</li>
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
        // 全池路由：请求 model=default 时路由池内所有启用模型
        boolean poolRouting = GatewayConstant.MODEL_POOL.equals(requestedModel);

        String cacheKey = channel.getId() + ":" + requestedModel;
        List<UpstreamRoute> cached = routeCache.getIfPresent(cacheKey);
        if (cached != null) {
            return cached;
        }

        // 查询启用且模型匹配的模型行（单值精确匹配），按失败次数升序、ID 升序
        LambdaQueryWrapper<UpstreamModel> wrapper = new LambdaQueryWrapper<UpstreamModel>()
                .eq(UpstreamModel::getEnabled, CommonConstant.ENABLED)
                .orderByAsc(UpstreamModel::getFailCount)
                .orderByAsc(UpstreamModel::getId);

        if (!poolRouting) {
            wrapper.eq(UpstreamModel::getModelName, requestedModel);
        }

        List<UpstreamModel> models = upstreamModelDao.selectList(wrapper);
        if (models.isEmpty()) {
            String errorMsg = poolRouting
                    ? "池内暂无任何启用的模型，请先在控制台为渠道配置并启用模型"
                    : String.format("池内暂无启用的渠道提供模型: %s", requestedModel);
            throw new BadRequestException(errorMsg);
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
            String errorMsg = poolRouting
                    ? "池内渠道均已停用，无可用模型"
                    : String.format("提供模型 %s 的渠道均已停用", requestedModel);
            throw new BadRequestException(errorMsg);
        }
        routeCache.put(cacheKey, routes);
        return routes;
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
