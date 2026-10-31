package com.github.niu.u.common.cache;

import com.github.niu.u.model.vo.CacheMetricsVo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 短链缓存的命中率统计。
 * <p>
 * 为什么要在业务代码里手写计数，而不是直接用 Spring Boot Actuator 的
 * {@code cache.gets} 指标？因为项目没有走 Spring 的 {@code CacheManager} 抽象，
 * 而是直接注入 {@code RedisService} 调原生命令，Actuator 的缓存指标是挂在
 * {@code CacheManager} 上的，这里一次都采不到。要采就得自己埋点。
 * <p>
 * 计数口径（三者互斥，加起来等于总请求数）：
 * <ul>
 *   <li>{@code hit}   —— 真命中，直接从 Redis 拿到原始链接；</li>
 *   <li>{@code nullHit} —— 命中空值占位（key 存在但值为 ""），说明这个短码数据库里也没有，
 *       属于「缓存挡掉了一次回源」，但对业务是失败，所以单独计数，不能混进 hit；</li>
 *   <li>{@code miss}  —— 没拿到缓存，需要回源数据库（包含下面 expiredMiss 的情况）。</li>
 * </ul>
 * {@code expiredMiss} 是 {@code miss} 的子集（回源后发现数据本身已过期），只作为排查参考，
 * <b>不参与</b>命中率的分母，否则同一个请求会被算两次。
 * <p>
 * 已知限制（面试常被追问）：计数在 JVM 内存里，进程重启就归零，多实例也无法汇总。
 * 要跨重启就写 Redis（每次请求多一次 HINCRBY），要跨实例就得配 Prometheus 之类的集中采集。
 * 练手项目用本地 AtomicLong 是刻意选的：零额外 I/O，且能讲清楚权衡。
 *
 * @author niuxiangqian
 * @version 1.0
 * @since 2026/10/28
 **/
@Component
public class CacheMetrics {

    private static final Logger logger = LoggerFactory.getLogger(CacheMetrics.class);

    private final AtomicLong hit = new AtomicLong();
    private final AtomicLong nullHit = new AtomicLong();
    private final AtomicLong miss = new AtomicLong();
    private final AtomicLong expiredMiss = new AtomicLong();
    private final AtomicLong bloomRejected = new AtomicLong();

    /**
     * 上一轮采样时的累计值，用来算「本窗口新增」。
     * 只有 {@link #logSummary()} 一个线程读写，不需要并发保护，也就没必要用 volatile。
     */
    private long lastTotal;
    private long lastHit;
    private long lastNullHit;

    public void recordHit() {
        hit.incrementAndGet();
    }

    public void recordNullHit() {
        nullHit.incrementAndGet();
    }

    public void recordMiss() {
        miss.incrementAndGet();
    }

    public void recordExpiredMiss() {
        expiredMiss.incrementAndGet();
    }

    /**
     * 布隆过滤器拦截次数。
     * <p>
     * 它也是 {@code miss} 的子集（缓存没命中才会走到过滤器），但意义完全不同：
     * miss 意味着「要查库」，bloom 拦截意味着「连库都不用查就被挡回去了」。
     * 这两个数放在一起看，就能算出过滤器省掉了多少 SQL——
     * 比如 miss=10000、bloomRejected=9900，说明 99% 的穿透被入口拦下了。
     */
    public void recordBloomRejected() {
        bloomRejected.incrementAndGet();
    }

    /**
     * 生成当前累计快照，供 HTTP 接口输出。
     */
    public CacheMetricsVo snapshot() {
        long h = hit.get();
        long nh = nullHit.get();
        long m = miss.get();
        long total = h + nh + m;

        CacheMetricsVo vo = new CacheMetricsVo();
        vo.setTotal(total)
                .setHit(h)
                .setNullHit(nh)
                .setMiss(m)
                .setExpiredMiss(expiredMiss.get())
                .setBloomRejected(bloomRejected.get())
                .setHitRate(ratio(h, total))
                .setNullHitRate(ratio(nh, total))
                .setMissRate(ratio(m, total));
        return vo;
    }

    /**
     * 每 60 秒把累计命中率打进日志，方便直接 tail 日志而不必调接口。
     * <p>
     * 用 {@code fixedDelay} 而不是 {@code fixedRate}：前者是「上一轮跑完再等 60 秒」，
     * 不会因为某一轮慢就堆起一串任务。
     * <p>
     * 窗口内一次请求都没有就直接返回——空闲时不需要刷日志。
     * （黑马点评那边踩过的坑：定时任务无脑打日志，Redis 断连时能刷满磁盘。）
     */
    @Scheduled(fixedDelayString = "${short_url.cache.metrics-interval-ms:60000}")
    public void logSummary() {
        long h = hit.get();
        long nh = nullHit.get();
        long m = miss.get();
        long total = h + nh + m;

        if (total == lastTotal) {
            return;
        }

        long windowHit = h - lastHit;
        long windowNullHit = nh - lastNullHit;
        long windowTotal = total - lastTotal;

        logger.info("缓存命中率 累计: total={}, hit={}, nullHit={}, miss={}, hitRate={}% | 本窗口: 请求 {} 次, 真命中 {} 次",
                total, h, nh, m, percent(h, total), windowTotal, windowHit);

        lastTotal = total;
        lastHit = h;
        lastNullHit = nh;

        // windowNullHit 当前只用于后续排查，先不占用日志行长
        if (windowNullHit > 0 && logger.isDebugEnabled()) {
            logger.debug("本窗口空值命中 {} 次", windowNullHit);
        }
    }

    /**
     * 比例，保留 4 位小数（0.9834 表示 98.34%）。
     * 分母为 0 时返回 0，避免启动初期没有请求就抛 ArithmeticException。
     */
    private static double ratio(long part, long total) {
        if (total <= 0) {
            return 0D;
        }
        return Math.round(part * 10000D / total) / 10000D;
    }

    /**
     * 百分比，保留 2 位小数，仅用于日志展示。
     */
    private static double percent(long part, long total) {
        if (total <= 0) {
            return 0D;
        }
        return Math.round(part * 10000D / total) / 100D;
    }
}
