package com.xgateai.service.gateway.strategy;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xgateai.constant.CommonConstant;
import com.xgateai.entity.ModelChannel;
import com.xgateai.entity.UpstreamProvider;
import com.xgateai.exception.BadRequestException;
import com.xgateai.constant.GatewayConstant;
import com.xgateai.mapper.IUpstreamProviderDao;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * PollingFailoverStrategy 轮询故障转移策略
 * <p>
 * 优先选择失败次数少的上游，失败时自动切换到下一个候选。
 * 路由规则：
 * 1. Key 限定模型时只允许调用该模型
 * 2. 请求 model=default 且未限定模型时，按全池路由
 * 3. 否则精确匹配 model_name
 * 4. 候选按 fail_count 升序排列，失败越少越优先
 * </p>
 *
 * @author xgateai
 * @since 2026/9/8
 */
@Slf4j
@Component
public class PollingFailoverStrategy implements UpstreamStrategy {

    private final IUpstreamProviderDao upstreamProviderDao;

    public PollingFailoverStrategy(IUpstreamProviderDao upstreamProviderDao) {
        this.upstreamProviderDao = upstreamProviderDao;
    }

    @Override
    public List<UpstreamProvider> selectCandidates(ModelChannel channel, String requestedModel) {
        String pinnedModel = normalizeBlank(channel.getModelName());

        // 规则1: Key 限定模型时校验请求模型是否匹配
        if (isNotBlank(pinnedModel) && !pinnedModel.equals(requestedModel)) {
            throw new BadRequestException(
                String.format("该 Key 已限定仅可调用模型: %s，当前请求: %s", pinnedModel, requestedModel));
        }

        // 规则2: 全池路由
        boolean poolRouting = isBlank(pinnedModel) && GatewayConstant.MODEL_POOL.equals(requestedModel);

        // 构建查询：启用 + 模型匹配 + 按失败次数升序
        LambdaQueryWrapper<UpstreamProvider> wrapper = new LambdaQueryWrapper<UpstreamProvider>()
                .eq(UpstreamProvider::getEnabled, CommonConstant.ENABLED)
                .orderByAsc(UpstreamProvider::getFailCount)
                .orderByAsc(UpstreamProvider::getId);

        if (!poolRouting) {
            wrapper.eq(UpstreamProvider::getModelName, requestedModel);
        }

        List<UpstreamProvider> candidates = upstreamProviderDao.selectList(wrapper);

        // 检查是否有可用候选
        if (candidates.isEmpty()) {
            String errorMsg = poolRouting
                ? "池内暂无任何启用的渠道，请先在控制台配置并启用上游服务"
                : String.format("池内暂无启用的渠道提供模型: %s", requestedModel);
            throw new BadRequestException(errorMsg);
        }

        return candidates;
    }

    private String normalizeBlank(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean isNotBlank(String str) {
        return str != null && !str.isEmpty();
    }

    private boolean isBlank(String str) {
        return str == null || str.isEmpty();
    }
}
