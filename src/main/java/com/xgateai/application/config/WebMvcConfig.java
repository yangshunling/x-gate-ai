package com.xgateai.application.config;

import com.xgateai.gatewaybridge.interceptor.AdminAuthInterceptor;
import com.xgateai.gatewaybridge.interceptor.ApiKeyInterceptor;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * <p>
 * WebMvcConfig Web MVC 配置：注册网关 API Key 校验与管理端登录鉴权拦截器
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
     * 管理端登录鉴权拦截器
     */
    @Resource
    AdminAuthInterceptor adminAuthInterceptor;

    /**
     * 注册拦截器：
     * 对外接口 /v1/** 需通过 API Key 校验；管理接口 /admin/** 需已登录（登录接口放行）
     *
     * @param registry 拦截器注册器
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(apiKeyInterceptor).addPathPatterns("/v1/**");
        registry.addInterceptor(adminAuthInterceptor)
                .addPathPatterns("/admin/**")
                .excludePathPatterns("/admin/login");
    }
}
