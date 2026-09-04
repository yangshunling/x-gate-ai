package com.xgateai.gatewaybridge.interceptor;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xgateai.application.constant.CommonConstant;
import com.xgateai.application.entity.ApiKey;
import com.xgateai.gatewaybridge.constant.GatewayConstant;
import com.xgateai.mapper.ApiKeyMapper;
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
 * ApiKeyInterceptor 对外网关 API Key 鉴权拦截器
 * 从 Authorization（Bearer xxxx 或裸 key）或 x-api-key 头解析调用方 key，
 * 与 api_keys 表中启用的 key 匹配，通过后将 ApiKey 实体放入请求属性 gateway_api_key
 * </p>
 *
 * @author xgateai
 * @since 2026/9/4
 */
@Slf4j
@Component
public class ApiKeyInterceptor implements HandlerInterceptor {

    /**
     * 对外调用 API Key Mapper
     */
    @Resource
    ApiKeyMapper apiKeyMapper;

    /**
     * 请求前置处理：校验调用方 API Key，非法则返回 OpenAI 风格 401 错误
     *
     * @param request  HTTP 请求
     * @param response HTTP 响应
     * @param handler  处理器对象
     * @return true 放行；false 拦截
     * @throws IOException 响应写出异常
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        // 静态资源等非 HandlerMethod 直接放行
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }
        // 优先取 Authorization 头（Bearer xxxx 或裸 key），其次取 x-api-key 头
        String key = resolveApiKey(request);
        if (StrUtil.isBlank(key)) {
            writeUnauthorized(response);
            return false;
        }
        // 匹配 api_keys 表中已启用的 key
        ApiKey apiKey = apiKeyMapper.selectOne(new LambdaQueryWrapper<ApiKey>()
                .eq(ApiKey::getKey, key)
                .eq(ApiKey::getEnabled, CommonConstant.ENABLED)
                .last("LIMIT 1"));
        if (apiKey == null) {
            writeUnauthorized(response);
            return false;
        }
        request.setAttribute(GatewayConstant.ATTR_API_KEY, apiKey);
        return true;
    }

    /**
     * 从请求头解析 API Key
     *
     * @param request HTTP 请求
     * @return 解析出的 key，为空表示未携带
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
     *
     * @param response HTTP 响应
     * @throws IOException 响应写出异常
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
