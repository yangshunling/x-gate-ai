package com.xgateai.application.config;

import com.xgateai.gatewaybridge.interceptor.ApiKeyInterceptor;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * <p>
 * WebMvcConfig Web MVC 配置：注册对外网关 API Key 鉴权拦截器
 * </p>
 *
 * @author xgateai
 * @since 2026/9/4
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /**
     * 对外网关 API Key 拦截器
     */
    @Resource
    ApiKeyInterceptor apiKeyInterceptor;

    /**
     * 注册拦截器：对外接口 /v1/** 需通过 API Key 校验
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(apiKeyInterceptor).addPathPatterns("/v1/**");
    }
}
