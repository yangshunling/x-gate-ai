package com.xgateai.component;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * InflightRegistry 上游模型在途并发计数器
 * <p>
 * 以模型行 ID 为键维护进程内并发在途数，供路由执行阶段判断「该模型并发是否已满」。
 * 线程安全由 {@link AtomicInteger} 保证；count 的递增/递减必须成对调用，
 * {@link #release(Long)} 放置于 finally 块以防计数泄漏导致永久占用额度。
 * </p>
 *
 * @author xgateai
 * @since 2026/9/9
 */
@Slf4j
@Component
public class InflightRegistry {

    private final ConcurrentHashMap<Long, AtomicInteger> counters = new ConcurrentHashMap<>();

    /**
     * 查询指定模型行当前在途请求数
     *
     * @param modelId 模型行 ID
     * @return 在途数；不存在返回 0
     */
    public int inFlight(Long modelId) {
        if (modelId == null) return 0;
        AtomicInteger counter = counters.get(modelId);
        return counter == null ? 0 : counter.get();
    }

    /**
     * 尝试占用一个并发额度
     * <p>
     * 当 maxConcurrency {@code <= 0} 时视为不限制并发，直接放行（仍计数以便监控）。
     * 当在途数已达上限时返回 false（不占用额度，调用方需自行跳到下一候选）。
     * </p>
     *
     * @param modelId        模型行 ID
     * @param maxConcurrency 并发上限；{@code <= 0} 表示不限制
     * @return true 表示已占用一个额度；false 表示并发已满
     */
    public boolean tryAcquire(Long modelId, int maxConcurrency) {
        if (modelId == null) return true;
        AtomicInteger counter = counters.computeIfAbsent(modelId, k -> new AtomicInteger());
        while (true) {
            int cur = counter.get();
            if (maxConcurrency > 0 && cur >= maxConcurrency) {
                return false;
            }
            if (counter.compareAndSet(cur, cur + 1)) {
                return true;
            }
        }
    }

    /**
     * 强制占用一个额度（用于全部候选并发已满时的「在途最少」兜底转发），
     * 不做上限校验，但仍计入在途，确保计数始终成对。
     *
     * @param modelId 模型行 ID
     */
    public void forceAcquire(Long modelId) {
        if (modelId == null) return;
        counters.computeIfAbsent(modelId, k -> new AtomicInteger()).incrementAndGet();
    }

    /**
     * 释放一个在途额度，必须与 {@link #tryAcquire} / {@link #forceAcquire} 成对调用
     *
     * @param modelId 模型行 ID
     */
    public void release(Long modelId) {
        if (modelId == null) return;
        AtomicInteger counter = counters.get(modelId);
        if (counter == null) {
            return;
        }
        int after = counter.decrementAndGet();
        if (after < 0) {
            // 计数异常：可能存在重复 release，重置为 0 避免负数永久放行
            counter.set(0);
            log.warn("在途并发计数出现负值并已重置, modelId: {}", modelId);
        }
    }
}
