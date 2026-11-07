package com.github.niu.u.common.bloom;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.niu.u.common.CommonCache;
import com.github.niu.u.mapper.ShortUrlMapper;
import com.github.niu.u.model.entity.ShortUrl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/**
 * 用 Redis BitMap 手写的布隆过滤器，用来在**入口**拦掉"数据库里肯定不存在"的短码。
 * <p>
 * 为什么手写而不用现成组件（Redisson / Guava）？因为面试问的是原理，不是 API 调用。
 * 手写之后这三个问题都能答得具体：
 * <ol>
 *   <li><b>位数组多大？</b> {@code m = n * 10}（n 为预估元素数）。默认 n = 100 万，
 *       所以 m = 1000 万 bit ≈ 1.25 MB，一条 SETBIT 命令就能维护，代价可以忽略。</li>
 *   <li><b>几个哈希函数？</b> {@code k = 3}。误判率 {@code p ≈ (1 - e^(-kn/m))^k}，
 *       代入 k=3、m/n=10 得 p ≈ 0.9%，也就是一千个不存在的短码里大约有 9 个会漏到数据库，
 *       剩下的 991 个在 Redis 里就被挡掉了。</li>
 *   <li><b>为什么不会漏判？</b>布隆过滤器只会"把存在的说成不存在"以外的方向出错——
 *       说"不存在"就一定不存在，说"存在"可能误判。所以拦截是安全的：
 *       被拦下的请求本来也该 404，唯一代价是误判时会多查一次库。</li>
 * </ol>
 * <p>
 * <b>三个必须处理的工程问题</b>（面试官很爱追这些）：
 * <ul>
 *   <li><b>删不掉</b>：布隆过滤器不支持删除（清位会影响别的元素）。本项目短码删除后位仍在，
 *       结果是"删除的短码仍被放行到数据库"，查不到 → 写空值占位 → 60 秒后 404。
 *       这是可接受的代价，比引入计数型布隆过滤器（Counting Bloom Filter）简单得多。</li>
 *   <li><b>位数组丢失</b>：Redis 被 flush 或换库后位数组全 0，此时"所有短码都不存在"，
 *       会让全站 404。所以 {@link #ensureLoaded()} 每 60 秒检查一次 key 是否存在，丢了就重建。</li>
 *   <li><b>Redis 挂了</b>：{@link #mightContain(String)} 直接 fail-open（返回 true），
 *       让请求穿透到后面的缓存 + 数据库。过滤器是"优化"不是"正确性依赖"，
 *       不能因为它把正常跳转全搞成 404。</li>
 * </ul>
 *
 * @author niuxiangqian
 * @version 1.0
 * @since 2026/10/29
 **/
@Component
public class RedisBloomFilter {

    private static final Logger logger = LoggerFactory.getLogger(RedisBloomFilter.class);

    /**
     * 写入：把 k 个位置全部置 1。三条 SETBIT 放在一个 Lua 里，一次网络往返。
     */
    private static final DefaultRedisScript<Long> ADD_SCRIPT = new DefaultRedisScript<>(
            "for i = 1, #ARGV do redis.call('SETBIT', KEYS[1], ARGV[i], 1) end return 1",
            Long.class);

    /**
     * 查询：把 k 个位的值加起来，等于 k 说明"可能存在"，小于 k 说明"一定不存在"。
     * 同样一个 Lua 只走一次往返——这条在跳转热路径上，三次 GETBIT 就是三次 RTT，不能忍。
     */
    private static final DefaultRedisScript<Long> CHECK_SCRIPT = new DefaultRedisScript<>(
            "local c = 0; local n = #ARGV; "
                    + "for i = 1, n do c = c + redis.call('GETBIT', KEYS[1], ARGV[i]) end return c",
            Long.class);

    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    /**
     * 第二个哈希用的偏移基（FNV-1a 换一个初始值即得到一条独立的哈希曲线）。
     */
    private static final long FNV_OFFSET_BASIS_2 = 0x9e3779b97f4a7c15L;

    private static final byte[] RAW_KEY = CommonCache.SHORT_URL_BLOOM.getBytes(StandardCharsets.UTF_8);

    @Value("${short_url.bloom.bit-size:10000000}")
    private long bitSize = 10000000L;

    @Value("${short_url.bloom.hash-count:3}")
    private int hashCount = 3;

    @Resource
    private RedisTemplate<String, String> redisTemplate;

    @Resource
    private ShortUrlMapper shortUrlMapper;

    /**
     * 过滤器是否已经建好。启动阶段数据库还在预热时它是 false，
     * 此时 {@link #mightContain(String)} 一律放行，避免应用刚起来就全站 404。
     */
    private volatile boolean initialized = false;

    /**
     * 判断短码"可能存在"。
     *
     * @return false 表示**一定不存在**（调用方可以直接 404，不必查库）；
     * true 表示可能存在（包含误判、过滤器未就绪、Redis 异常三种情况，调用方正常走缓存 + 查库）
     */
    public boolean mightContain(String code) {
        if (code == null || code.isEmpty()) {
            return false;
        }
        if (!initialized) {
            // fail-open：过滤器是优化不是正确性依赖
            return true;
        }
        try {
            Long bits = redisTemplate.execute(CHECK_SCRIPT,
                    Collections.singletonList(CommonCache.SHORT_URL_BLOOM), toArgs(positions(code)));
            return bits != null && bits >= hashCount;
        } catch (Exception e) {
            // Redis 抖一下不该让全站 404，放行让后面的缓存和数据库兜底
            logger.warn("布隆过滤器查询失败，本次放行 code={}, 原因={}", code, e.toString());
            return true;
        }
    }

    /**
     * 把短码写进过滤器。新增短链时调用。
     * <p>
     * 这里的失败只记录日志不抛出：写不进过滤器最坏的结果是"以后这个短码多查一次库"，
     * 而抛异常会让用户明明生成成功了却收到 500。
     */
    public void add(String code) {
        if (code == null || code.isEmpty()) {
            return;
        }
        try {
            redisTemplate.execute(ADD_SCRIPT, Collections.singletonList(CommonCache.SHORT_URL_BLOOM),
                    toArgs(positions(code)));
        } catch (Exception e) {
            logger.warn("布隆过滤器写入失败 code={}, 原因={}（只是多查一次库，不影响正确性）", code, e.toString());
        }
    }

    /**
     * 全量预热：把库里所有未删除的短码灌进过滤器。
     * <p>
     * 用 pipeline 而不是逐条 SETBIT：一万条短码 × 3 个位置 = 三万次往返，
     * 串行跑能把启动拖到几分钟，pipeline 一次往返就够。
     * <p>
     * 监听 {@link ApplicationReadyEvent} 而不是 {@code @PostConstruct}：
     * 后者在 Bean 初始化阶段执行，此时数据源、MyBatis 还没完全就绪，
     * 而且会拖慢启动（要等全量扫完才能对外提供服务）。
     */
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void warmUp() {
        long start = System.currentTimeMillis();
        try {
            List<ShortUrl> rows = shortUrlMapper.selectList(Wrappers.<ShortUrl>lambdaQuery()
                    .select(ShortUrl::getCode)
                    .eq(ShortUrl::getDelFlag, 0));
            if (rows == null || rows.isEmpty()) {
                initialized = true;
                // 空库也必须把 key 建出来：SETBIT 写 0 同样会创建这个 key（值是一个全 0 的串）。
                // 不建的话 ensureLoaded() 每次都判 hasKey=false，每 60 秒误报一次
                // "位数组丢失"再触发一次全量预热，日志会一直刷，也让真正丢失时无感
                redisTemplate.execute((RedisCallback<Object>) (RedisConnection connection) -> {
                    connection.setBit(RAW_KEY, 0, false);
                    return null;
                });
                logger.info("布隆过滤器预热完成: 库里没有短码，位数组 {} bit", bitSize);
                return;
            }
            final int[] count = {0};
            redisTemplate.executePipelined((RedisCallback<Object>) (RedisConnection connection) -> {
                for (ShortUrl row : rows) {
                    String code = row.getCode();
                    if (code == null || code.isEmpty()) {
                        // insert 后还没回填 code 的记录（正常流程下不会留下），跳过
                        continue;
                    }
                    for (long offset : positions(code)) {
                        connection.setBit(RAW_KEY, offset, true);
                    }
                    count[0]++;
                }
                return null;
            });
            initialized = true;
            logger.info("布隆过滤器预热完成: 灌入 {} 个短码, 位数组 {} bit, hash={}, 耗时 {} ms",
                    count[0], bitSize, hashCount, System.currentTimeMillis() - start);
        } catch (Exception e) {
            // 预热失败不阻止启动：initialized 保持 false，mightContain 全部放行，
            // 下一个检查周期会自动重试
            logger.error("布隆过滤器预热失败，本次启动不启用过滤（不影响跳转，只是少了防穿透）", e);
        }
    }

    /**
     * 每 60 秒确认位数组还在。
     * <p>
     * 这不是"多此一举"：Redis 被 {@code FLUSHDB}、换库、或者误删 key 之后，
     * 位数组全 0 → 所有短码都被判成不存在 → 全站 404。这种故障一旦发生，
     * 排查起来会非常费时间（因为应用日志里只有一片 "bloom rejected"）。
     * <p>
     * {@code initialDelay} 必须和间隔一样大：{@code @Scheduled} 的 {@code fixedDelay}
     * 默认在容器启动后<b>立即</b>执行第一次，而此时 {@link #warmUp()}（监听
     * ApplicationReadyEvent）可能还没跑完，两边会各预热一遍（实测日志里出现两条"预热完成"）。
     * 代价不大，但日志会误导人以为预热执行了两次业务逻辑。
     */
    @Scheduled(initialDelayString = "${short_url.bloom.check-interval-ms:60000}",
            fixedDelayString = "${short_url.bloom.check-interval-ms:60000}")
    public void ensureLoaded() {
        if (!initialized) {
            warmUp();
            return;
        }
        try {
            if (!Boolean.TRUE.equals(redisTemplate.hasKey(CommonCache.SHORT_URL_BLOOM))) {
                logger.warn("布隆过滤器位数组丢失（Redis 被清空或 key 被删？），立即重建");
                warmUp();
            }
        } catch (Exception e) {
            logger.warn("布隆过滤器健康检查失败: {}", e.toString());
        }
    }

    public boolean isInitialized() {
        return initialized;
    }

    public long getBitSize() {
        return bitSize;
    }

    public int getHashCount() {
        return hashCount;
    }

    /**
     * 计算短码在位数组里的 k 个下标。
     * <p>
     * 真正"k 个独立哈希函数"要算 k 次哈希，代价偏高。这里用 Kirsch-Mitzenmacher 的经典技巧：
     * 用两条独立的 64 位哈希 h1、h2，令 {@code h_i = h1 + i * h2}。
     * 数学上可以证明这样构造的 k 个位置与原方案误判率同阶，但只需要算 2 次哈希。
     * <p>
     * {@code Math.floorMod} 而不是 {@code %}：Java 的取余对负数返回负数，
     * 直接拿来当位下标会抛异常（负数下标），floorMod 保证结果一定落在 [0, bitSize)。
     */
    static long[] positions(String code, long bitSize, int hashCount) {
        long h1 = fnv1a64(code, FNV_OFFSET_BASIS);
        long h2 = fnv1a64(code, FNV_OFFSET_BASIS_2);
        long[] offsets = new long[hashCount];
        for (int i = 0; i < hashCount; i++) {
            offsets[i] = Math.floorMod(h1 + i * h2, bitSize);
        }
        return offsets;
    }

    private long[] positions(String code) {
        return positions(code, bitSize, hashCount);
    }

    /**
     * FNV-1a 64 位哈希：实现只有几行，不依赖 Guava/Murmur，跑得比 MD5 快一个量级。
     * 布隆过滤器对哈希的要求是「分布均匀 + 快」，不需要抗碰撞的密码学哈希。
     */
    private static long fnv1a64(String value, long basis) {
        long hash = basis;
        for (int i = 0; i < value.length(); i++) {
            hash ^= value.charAt(i);
            hash *= FNV_PRIME;
        }
        return hash;
    }

    private static Object[] toArgs(long[] offsets) {
        Object[] args = new Object[offsets.length];
        for (int i = 0; i < offsets.length; i++) {
            args[i] = String.valueOf(offsets[i]);
        }
        return args;
    }
}
