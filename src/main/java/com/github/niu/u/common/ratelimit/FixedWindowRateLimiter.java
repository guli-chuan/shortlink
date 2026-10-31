package com.github.niu.u.common.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 固定窗口限流器：一个窗口内同一个 key 最多放行 limit 次。
 *
 * <p>为什么必须写成 Lua：</p>
 * <pre>
 * 如果 Java 里分两步发命令：
 *   Long current = redis.call INCR key
 *   if (current == 1) redis.call EXPIRE key window
 * 两步之间进程崩了 / 网络断了，key 就永久没有过期时间。
 * 之后这个 key 的计数只增不减，等于把这个 IP 永久拉黑——而且是静默的，
 * 只有看 TTL 才能发现。Lua 在 Redis 服务端顺序执行、不被其他命令打断，
 * INCR + EXPIRE 变成一个原子操作，不存在"只做了一半"。
 * </pre>
 *
 * <p>还有一个隐蔽的兜底：脚本里判断的是 {@code current == 1 || ttl < 0}。
 * 只判断 {@code current == 1} 是不够的——如果这个 key 因为人为 SET、
 * 从别处复制数据、或者上一版本代码的 bug 而存在但没有 TTL，
 * 计数会一直累加下去。多判一个 {@code ttl < 0} 就能自愈。</p>
 *
 * @author niuxiangqian
 * @since 2026/10/30
 */
@Component
public class FixedWindowRateLimiter {

    private final Logger logger = LoggerFactory.getLogger(FixedWindowRateLimiter.class);

    /**
     * KEYS[1] = 计数 key，ARGV[1] = 阈值，ARGV[2] = 窗口秒数。
     * 返回 {是否放行(1/0), 当前计数, 剩余秒数}——把三个值一起带回来，
     * 省掉一次 TTL 查询，也让调用方能给出准确的 Retry-After。
     */
    private static final String SCRIPT =
            "local current = redis.call('INCR', KEYS[1])\n" +
            "local ttl = redis.call('TTL', KEYS[1])\n" +
            "if current == 1 or ttl < 0 then\n" +
            "    redis.call('EXPIRE', KEYS[1], ARGV[2])\n" +
            "    ttl = tonumber(ARGV[2])\n" +
            "end\n" +
            "if current > tonumber(ARGV[1]) then\n" +
            "    return {0, current, ttl}\n" +
            "end\n" +
            "return {1, current, ttl}\n";

    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> SCRIPT_OBJECT = new DefaultRedisScript<>(SCRIPT, List.class);

    /**
     * 上一次调用是否成功。只用来决定"要不要打这条日志"，
     * 不做业务判断。Redis 挂掉时每个请求都打一条 ERROR 会刷爆日志，
     * 和 Day 2 修掉的 pending 空转刷屏是同一类问题。
     */
    private final AtomicBoolean healthy = new AtomicBoolean(true);

    @Resource
    private RedisTemplate<String, String> redisTemplate;

    /**
     * 尝试取一次配额。
     *
     * @param key           限流计数 key（不同场景、不同来源要分开）
     * @param limit         窗口内允许的次数
     * @param windowSeconds 窗口长度（秒）
     */
    public Decision tryAcquire(String key, int limit, long windowSeconds) {
        if (limit <= 0 || windowSeconds <= 0) {
            // 配置写错了不能把业务一并拦死：宁可暂时不限流，也不让全站生成接口 500
            logger.warn("限流参数非法，本次放行。key={}, limit={}, window={}", key, limit, windowSeconds);
            return Decision.allow(0, 0);
        }
        try {
            List<?> result = redisTemplate.execute(SCRIPT_OBJECT, Collections.singletonList(key),
                    String.valueOf(limit), String.valueOf(windowSeconds));
            markHealthy();
            if (result == null || result.size() < 3) {
                logger.warn("限流脚本返回结果异常，本次放行。key={}, result={}", key, result);
                return Decision.allow(0, 0);
            }
            long allowed = toLong(result.get(0));
            long current = toLong(result.get(1));
            long ttl = toLong(result.get(2));
            return Decision.of(allowed == 1L, current, ttl);
        } catch (Exception e) {
            markUnhealthy(e);
            return Decision.allow(0, 0);
        }
    }

    /**
     * fail-open：限流器是"防刷"这一层保护，不是业务正确性的一部分。
     * Redis 抖动时如果选择拒绝，就等于让限流器自己变成新的故障点——
     * 用户看到的报错会指向"请求太频繁"，而真实原因是 Redis 挂了，
     * 这种误导性错误比少拦几个刷子代价大得多。
     */
    private void markUnhealthy(Exception e) {
        if (healthy.compareAndSet(true, false)) {
            logger.error("限流器执行失败，转入 fail-open（后续失败不再重复打印）", e);
        }
    }

    private void markHealthy() {
        if (!healthy.get() && healthy.compareAndSet(false, true)) {
            logger.info("限流器已恢复正常");
        }
    }

    private static long toLong(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    /**
     * 一次限流判定的结果。
     */
    public static final class Decision {
        private final boolean allowed;
        private final long currentCount;
        private final long ttlSeconds;

        private Decision(boolean allowed, long currentCount, long ttlSeconds) {
            this.allowed = allowed;
            this.currentCount = currentCount;
            this.ttlSeconds = ttlSeconds;
        }

        public static Decision of(boolean allowed, long currentCount, long ttlSeconds) {
            return new Decision(allowed, currentCount, ttlSeconds);
        }

        /**
         * 放行。fail-open 路径也用它：此时没有真实计数，current 传 0。
         */
        public static Decision allow(long currentCount, long ttlSeconds) {
            return new Decision(true, currentCount, ttlSeconds);
        }

        public boolean isAllowed() {
            return allowed;
        }

        /**
         * 本次请求计入后的累计次数（含被拒的这次）。
         */
        public long getCurrentCount() {
            return currentCount;
        }

        /**
         * 当前窗口还剩多少秒，用于 Retry-After。
         */
        public long getTtlSeconds() {
            return ttlSeconds;
        }
    }
}
