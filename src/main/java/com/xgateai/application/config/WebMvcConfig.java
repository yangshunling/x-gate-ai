package com.xgateai.application.config;

import com.xgateai.gatewaybridge.interceptor.ApiKeyInterceptor;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
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

    /**
     * 启用异步请求处理支持，使 Flux<DataBuffer> 能正确写入 SSE 响应
     */
    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        // 设置异步请求超时时间为 5 分钟，适配长耗时的大模型生成任务
        configurer.setDefaultTimeout(5 * 60 * 1000L);
    }
}
