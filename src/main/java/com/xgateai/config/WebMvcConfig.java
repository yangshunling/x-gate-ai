package com.xgateai.config;

import com.xgateai.interceptor.ApiKeyInterceptor;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * WebMvcConfig Web MVC 配置
 * <p>
 * 注册 API Key 鉴权拦截器，拦截 /v1/** 路径的请求。
 * </p>
 *
 * @author xgateai
 * @since 2026/9/8
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final ApiKeyInterceptor apiKeyInterceptor;

    /**
     * 构造 Web MVC 配置
     *
     * @param apiKeyInterceptor API Key 鉴权拦截器
     */
    public WebMvcConfig(ApiKeyInterceptor apiKeyInterceptor) {
        this.apiKeyInterceptor = apiKeyInterceptor;
    }

    /**
     * 注册拦截器：将 API Key 鉴权拦截器应用到 /v1/** 路径
     *
     * @param registry 拦截器注册表
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(apiKeyInterceptor)
                .addPathPatterns("/v1/**");
    }
}
