package com.github.niu.u.common.cache;

import com.github.niu.u.model.vo.CacheMetricsVo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * 缓存命中率计数器测试。
 * <p>
 * 重点不在「加一」本身，而在两个容易写错的地方：
 * 1. 没有请求时（total=0）不能除零；
 * 2. 并发累加不能丢计数——命中率一旦被算便宜了，简历上的数字就站不住。
 *
 * @author niuxiangqian
 * @version 1.0
 * @since 2026/10/28
 **/
class CacheMetricsTest {

    @Test
    @DisplayName("没有任何请求时命中率为 0，不抛除零异常")
    void emptySnapshotShouldReturnZero() {
        CacheMetricsVo vo = new CacheMetrics().snapshot();

        assertEquals(0L, vo.getTotal());
        assertEquals(0D, vo.getHitRate(), 1e-9);
        assertEquals(0D, vo.getNullHitRate(), 1e-9);
        assertEquals(0D, vo.getMissRate(), 1e-9);
    }

    @Test
    @DisplayName("三个比例互斥，加起来等于 1")
    void ratesShouldSumToOne() {
        CacheMetrics metrics = new CacheMetrics();
        for (int i = 0; i < 7; i++) {
            metrics.recordHit();
        }
        metrics.recordNullHit();
        for (int i = 0; i < 2; i++) {
            metrics.recordMiss();
        }

        CacheMetricsVo vo = metrics.snapshot();
        assertEquals(10L, vo.getTotal(), "total = hit + nullHit + miss");
        assertEquals(0.7D, vo.getHitRate(), 1e-9);
        assertEquals(0.1D, vo.getNullHitRate(), 1e-9);
        assertEquals(0.2D, vo.getMissRate(), 1e-9);
        assertEquals(1D, vo.getHitRate() + vo.getNullHitRate() + vo.getMissRate(), 1e-9);
    }

    @Test
    @DisplayName("比例保留 4 位小数，避免 JSON 里出现一长串浮点尾数")
    void rateShouldBeRoundedToFourDecimals() {
        CacheMetrics metrics = new CacheMetrics();
        metrics.recordHit();
        metrics.recordMiss();
        metrics.recordMiss();

        // 1/3 = 0.333333...，四舍五入后是 0.3333
        assertEquals(0.3333D, metrics.snapshot().getHitRate(), 1e-9);
    }

    @Test
    @DisplayName("expiredMiss 是 miss 的子集，不额外抬高分母")
    void expiredMissShouldNotAffectTotal() {
        CacheMetrics metrics = new CacheMetrics();
        metrics.recordMiss();
        metrics.recordExpiredMiss();

        CacheMetricsVo vo = metrics.snapshot();
        assertEquals(1L, vo.getTotal(), "过期回源已经算在 miss 里，不能再算一次");
        assertEquals(1L, vo.getExpiredMiss());
        assertEquals(1D, vo.getMissRate(), 1e-9);
    }

    @Test
    @DisplayName("8 线程各累加 1 万次，计数不丢——这也是用 AtomicLong 而不是 int 的原因")
    void concurrentIncrementShouldNotLoseCount() throws InterruptedException {
        CacheMetrics metrics = new CacheMetrics();
        int threads = 8;
        int perThread = 10000;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int j = 0; j < perThread; j++) {
                            metrics.recordHit();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            done.await(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertEquals((long) threads * perThread, metrics.snapshot().getHit(),
                "并发累加必须精确，普通 int 的 ++ 在这种场景下会丢计数");
    }

    @Test
    @DisplayName("定时汇总日志在无请求时也能安全执行")
    void logSummaryShouldBeSafeWhenIdle() {
        CacheMetrics metrics = new CacheMetrics();

        assertDoesNotThrow(metrics::logSummary);
        metrics.recordHit();
        assertDoesNotThrow(metrics::logSummary);
        assertDoesNotThrow(metrics::logSummary);
    }
}
