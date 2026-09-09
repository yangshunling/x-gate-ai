package com.xgateai.interceptor;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.xgateai.constant.CommonConstant;
import com.xgateai.entity.ModelChannel;
import com.xgateai.exception.AuthenticationException;
import com.xgateai.constant.GatewayConstant;
import com.xgateai.logging.GatewayLog;
import com.xgateai.mapper.IModelChannelDao;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * ApiKeyInterceptor API Key 鉴权拦截器
 * <p>
 * 拦截 /v1/** 请求，校验客户端提供的 API Key 是否有效。
 * 校验通过后将 ModelChannel 信息写入请求属性，供后续处理使用。
 * </p>
 *
 * @author xgateai
 * @since 2026/9/8
 */
@Slf4j
@Component
public class ApiKeyInterceptor implements HandlerInterceptor {

    private final IModelChannelDao modelChannelDao;
    private final Cache<String, Object> channelCache;

    public ApiKeyInterceptor(IModelChannelDao modelChannelDao,
                             Cache<String, Object> channelCache) {
        this.modelChannelDao = modelChannelDao;
        this.channelCache = channelCache;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws IOException {
        // 非 API 请求直接放行
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }

        String apiKey = resolveApiKey(request);
        if (StrUtil.isBlank(apiKey)) {
            log.warn("API Key 为空, 拒绝访问, ip: {}", request.getRemoteAddr());
            writeUnauthorizedResponse(response);
            return false;
        }

        // 先查缓存，命中则跳过 DB
        ModelChannel channel = (ModelChannel) channelCache.getIfPresent(apiKey);
        if (channel == null) {
            channel = modelChannelDao.selectOne(
                    new LambdaQueryWrapper<ModelChannel>()
                            .eq(ModelChannel::getApiKey, apiKey)
                            .eq(ModelChannel::getEnabled, CommonConstant.ENABLED)
                            .last("LIMIT 1"));
            if (channel != null) {
                channelCache.put(apiKey, channel);
            }
        }

        if (channel == null) {
            log.warn("API Key 校验失败(未匹配启用通道), ip: {}, key: {}",
                    request.getRemoteAddr(), apiKey);
            writeUnauthorizedResponse(response);
            return false;
        }

        // 写入请求属性，供 Controller 使用
        request.setAttribute(GatewayConstant.ATTR_API_KEY, channel);

        // 写入 MDC 上下文，供日志使用
        GatewayLog.putChannelId(String.valueOf(channel.getId()));
        GatewayLog.putChannelName(channel.getPublicModelName());
        GatewayLog.putChannelModel(StrUtil.blankToDefault(channel.getModelName(), "default"));
        GatewayLog.putChannelKey(maskApiKey(channel.getApiKey()));

        return true;
    }

    // ==================== 私有辅助方法 ====================

    private String resolveApiKey(HttpServletRequest request) {
        // 优先从 Authorization: Bearer xxx 中提取
        String authorization = request.getHeader(CommonConstant.HEADER_AUTHORIZATION);
        if (StrUtil.isNotBlank(authorization)) {
            if (authorization.startsWith("Bearer ")) {
                return authorization.substring(7).trim();
            }
            return authorization.trim();
        }

        //  fallback: 从 x-api-key 头中提取
        return request.getHeader(CommonConstant.HEADER_X_API_KEY);
    }

    private void writeUnauthorizedResponse(HttpServletResponse response) throws IOException {
        JSONObject error = new JSONObject();
        error.put("message", "Invalid API key");
        error.put("type", "invalid_request_error");
        error.put("param", null);
        error.put("code", "invalid_api_key");

        JSONObject body = new JSONObject();
        body.put("error", error);

        response.setStatus(CommonConstant.HTTP_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(JSON.toJSONString(body));
    }

    private String maskApiKey(String apiKey) {
        if (StrUtil.isBlank(apiKey)) return "";
        if (apiKey.length() <= 8) return apiKey;
        return apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4);
    }
}
