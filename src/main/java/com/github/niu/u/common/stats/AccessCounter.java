package com.github.niu.u.common.stats;

import com.github.niu.u.common.CommonCache;
import com.github.niu.u.config.redis.RedisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * 访问量（PV）异步计数器：本地聚合 + 定时批量刷写。
 * <p>
 * <b>为什么要把这次写操作搬走？</b>
 * 改造前 {@code UController.u()} 每次跳转都先 {@code ZINCRBY} 再 {@code GET}，
 * 也就是每条跳转请求有 <b>2 次 Redis 往返</b>，而且这一次写是同步的——
 * 用户必须等统计写完才能拿到 302。跳转是读多写少里最"读"的那种场景，
 * 为一个排行榜把每次点击都变成一次写，是典型的写放大。
 * <p>
 * 现在改成：内存里 {@code +1}（纳秒级，无网络），后台每 5 秒把攒下来的计数
 * 用 pipeline 一次性写回 Redis。跳转链路因此只剩 1 次 Redis 读，
 * 而写操作的数量从"每次点击一次"降到"每 5 秒每个短码一次"。
 * <p>
 * <b>代价（面试一定会问"宕机怎么办"）</b>：进程崩溃时最多丢一个刷写周期（5 秒）的计数。
 * 这是一个明确的取舍：PV 统计不是钱，丢 5 秒对业务毫无影响；
 * 换来的是跳转链路少一次网络往返。如果这是"下单量"而不是"访问量"，就必须同步写。
 * <p>
 * <b>为什么用 {@link LongAdder} 而不是 {@code AtomicLong}？</b>
 * 热点短码的计数会被所有 Tomcat 线程抢同一个 Cell；AtomicLong 的 CAS 在
 * 高并发下会大量自旋失败，而 LongAdder 内部按线程分散到多个 Cell，
 * 只在 {@code sum()} 时才汇总，这是典型的"空间换竞争"。
 *
 * @author niuxiangqian
 * @version 1.0
 * @since 2026/10/29
 **/
@Component
public class AccessCounter {

    private static final Logger logger = LoggerFactory.getLogger(AccessCounter.class);

    /**
     * 短码 -> 尚未刷写到 Redis 的访问次数。
     * 只增不减的 key 会被 {@link #flush()} 顺手清掉，避免长期运行后无限膨胀。
     */
    private final ConcurrentHashMap<String, LongAdder> pending = new ConcurrentHashMap<>();

    @Value("${short_url.stats.flush-interval-seconds:5}")
    private long flushIntervalSeconds = 5L;

    @Resource
    private RedisService redisService;

    /**
     * 注意这是<b>实例字段</b>而不是 static（Day 1 踩过的坑：static 线程池在容器关闭后
     * 还在跑，日志里一片 "LettuceConnectionFactory was destroyed"）。
     */
    private ScheduledExecutorService flusher;

    @PostConstruct
    public void start() {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "pv-flusher");
            // daemon 只影响 JVM 退出：即使这里没来得及 shutdown，也不会把进程吊住。
            // 真正的优雅停机靠下面的 @PreDestroy。
            thread.setDaemon(true);
            return thread;
        };
        flusher = Executors.newSingleThreadScheduledExecutor(factory);
        flusher.scheduleAtFixedRate(this::flushQuietly,
                flushIntervalSeconds, flushIntervalSeconds, TimeUnit.SECONDS);
        logger.info("访问统计异步刷写已启动，间隔 {} 秒", flushIntervalSeconds);
    }

    /**
     * 记录一次访问。跳转链路调用，必须是纯内存操作。
     */
    public void increment(String code) {
        if (code == null || code.isEmpty()) {
            return;
        }
        pending.computeIfAbsent(code, key -> new LongAdder()).increment();
    }

    /**
     * 还没刷写到 Redis 的本地计数。
     * <p>
     * 统计接口要把它加上，否则用户刚访问 100 次就去看排行榜，会发现数字是 0——
     * 这不是 bug，是异步刷写的固有延迟，但对使用者来说就是"统计不准"。
     */
    public long unsaved(String code) {
        if (code == null || code.isEmpty()) {
            return 0L;
        }
        LongAdder adder = pending.get(code);
        return adder == null ? 0L : adder.sum();
    }

    /**
     * 刷写一轮。定时任务和 {@link #shutdown()} 都会调用。
     *
     * @return 本次写进 Redis 的短码数量
     */
    public int flush() {
        if (pending.isEmpty()) {
            return 0;
        }

        Map<String, Long> deltas = new HashMap<>();
        for (Map.Entry<String, LongAdder> entry : pending.entrySet()) {
            long delta = entry.getValue().sumThenReset();
            if (delta > 0) {
                deltas.put(entry.getKey(), delta);
            } else {
                // 上轮刚刷完、这 5 秒内没被访问的短码：把条目摘掉，避免 map 无限增长。
                // 这里的并发窗口可以忽略——摘掉的瞬间如果刚好又来一次访问，
                // 最坏结果是丢 1 次计数，和"丢 5 秒"是同一个量级的取舍。
                pending.remove(entry.getKey(), entry.getValue());
            }
        }
        if (deltas.isEmpty()) {
            return 0;
        }

        try {
            redisService.zIncrementBatch(CommonCache.ACCESS_COUNT, deltas);
            if (logger.isDebugEnabled()) {
                long total = deltas.values().stream().mapToLong(Long::longValue).sum();
                logger.debug("刷写访问统计: {} 个短码, {} 次访问", deltas.size(), total);
            }
            return deltas.size();
        } catch (Exception e) {
            // 刷写失败必须把这些计数还回本地，否则这批数据凭空消失——
            // 数据已经 sumThenReset 清零了，不还回去就真的丢了
            deltas.forEach((code, delta) -> pending.computeIfAbsent(code, k -> new LongAdder()).add(delta));
            throw e;
        }
    }

    private void flushQuietly() {
        try {
            flush();
        } catch (Exception e) {
            // 定时任务里抛出的异常会被 ScheduledExecutorService 静默吞掉，
            // 而且一旦抛出，后续调度会整个停掉，所以必须自己接住
            logger.warn("访问统计刷写失败，本批计数已回填本地，下个周期重试: {}", e.toString());
        }
    }

    /**
     * 停机前把剩余的计数刷掉，把"最多丢 5 秒"缩小到"最多丢一次刷写失败的量"。
     * <p>
     * 顺序很重要：先 shutdown 再 flush，否则可能在 flush 的同时后台线程也在 flush，
     * 两边抢同一批计数。
     */
    @PreDestroy
    public void shutdown() {
        if (flusher != null) {
            flusher.shutdown();
            try {
                if (!flusher.awaitTermination(2, TimeUnit.SECONDS)) {
                    flusher.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                flusher.shutdownNow();
            }
        }
        try {
            int flushed = flush();
            if (flushed > 0) {
                logger.info("停机前刷写访问统计: {} 个短码", flushed);
            }
        } catch (Exception e) {
            logger.warn("停机前刷写访问统计失败，本次丢失 {} 个短码的计数: {}", pending.size(), e.toString());
        }
    }
}
