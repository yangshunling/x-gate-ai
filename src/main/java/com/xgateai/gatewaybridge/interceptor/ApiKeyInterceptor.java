package com.xgateai.gatewaybridge.interceptor;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xgateai.application.constant.CommonConstant;
import com.xgateai.application.entity.ModelChannel;
import com.xgateai.gatewaybridge.constant.GatewayConstant;
import com.xgateai.gatewaybridge.logging.GatewayLog;
import com.xgateai.mapper.ModelChannelMapper;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * <p>
 * ApiKeyInterceptor 对外网关鉴权拦截器
 * 每个对客服务拥有专属 Key，调用时用 Authorization（Bearer xxxx 或裸 key）或 x-api-key 携带，
 * 匹配 model_channels 表中启用服务的 api_key，通过后将 ModelChannel 放入请求属性
 * </p>
 *
 * @author xgateai
 * @since 2026/9/4
 */
@Slf4j
@Component
public class ApiKeyInterceptor implements HandlerInterceptor {

    /**
     * 对客服务 Mapper
     */
    @Resource
    ModelChannelMapper modelChannelMapper;

    /**
     * 请求前置处理：校验调用方 Key，非法则返回 OpenAI 风格 401 错误
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        // 静态资源等非 HandlerMethod 直接放行
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }
        String key = resolveApiKey(request);
        if (StrUtil.isBlank(key)) {
            log.warn("API Key 为空, 拒绝访问, ip: {}", request.getRemoteAddr());
            writeUnauthorized(response);
            return false;
        }
        // 匹配 model_channels 表中已启用的服务专属 Key
        ModelChannel channel = modelChannelMapper.selectOne(new LambdaQueryWrapper<ModelChannel>()
                .eq(ModelChannel::getApiKey, key)
                .eq(ModelChannel::getEnabled, CommonConstant.ENABLED)
                .last("LIMIT 1"));
        if (channel == null) {
            log.warn("API Key 校验失败(未匹配启用通道), ip: {}, key: {}",
                    request.getRemoteAddr(), GatewayLog.maskKey(key));
            writeUnauthorized(response);
            return false;
        }
        request.setAttribute(GatewayConstant.ATTR_API_KEY, channel);
        // 通道身份写入 MDC，供本次请求所有日志统一展示（key 脱敏）
        GatewayLog.putChannelId(String.valueOf(channel.getId()));
        GatewayLog.putChannelName(channel.getPublicModelName());
        GatewayLog.putChannelModel(StrUtil.blankToDefault(channel.getModelName(), "default"));
        GatewayLog.putChannelKey(GatewayLog.maskKey(channel.getApiKey()));
        return true;
    }

    /**
     * 从请求头解析 API Key
     */
    private String resolveApiKey(HttpServletRequest request) {
        String authorization = request.getHeader(CommonConstant.HEADER_AUTHORIZATION);
        if (StrUtil.isNotBlank(authorization)) {
            if (authorization.startsWith("Bearer ")) {
                return authorization.substring(7).trim();
            }
            return authorization.trim();
        }
        return request.getHeader(CommonConstant.HEADER_X_API_KEY);
    }

    /**
     * 写出 OpenAI 风格 401 错误响应
     */
    private void writeUnauthorized(HttpServletResponse response) throws IOException {
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
}
