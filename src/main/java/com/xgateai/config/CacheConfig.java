package com.xgateai.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * CacheConfig 本地缓存配置
 * <p>
 * 基于 Caffeine 定义两类进程内缓存：
 * <ul>
 *   <li>channelCache：按 API Key 缓存已鉴权的对客通道，避免每次请求查询数据库</li>
 *   <li>routeCache：按「通道 + 请求模型」缓存路由候选列表，降低路由决策开销</li>
 * </ul>
 * </p>
 *
 * @author xgateai
 * @since 2026/9/8
 */
@Configuration
public class CacheConfig {

    /**
     * 对客通道缓存 Bean
     * <p>最大容量 1000 条，写入 5 分钟后过期失效。</p>
     *
     * @return 键为 API Key、值为通道对象的本地缓存
     */
    @Bean
    public Cache<String, Object> channelCache() {
        return Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .build();
    }

    /**
     * 路由候选缓存 Bean
     * <p>最大容量 500 条，写入 2 分钟后过期失效。</p>
     *
     * @return 键为「通道ID:请求模型」、值为候选列表的本地缓存
     */
    @Bean
    public Cache<String, List> routeCache() {
        return Caffeine.newBuilder()
                .maximumSize(500)
                .expireAfterWrite(2, TimeUnit.MINUTES)
                .build();
    }
}
