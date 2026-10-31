package com.github.niu.u.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.niu.u.common.CommonCache;
import com.github.niu.u.common.bloom.RedisBloomFilter;
import com.github.niu.u.common.cache.CacheMetrics;
import com.github.niu.u.common.exception.BaseException;
import com.github.niu.u.common.util.Base62Util;
import com.github.niu.u.config.redis.RedisService;
import com.github.niu.u.mapper.ShortUrlMapper;
import com.github.niu.u.model.entity.ShortUrl;
import com.github.niu.u.model.vo.ShortUrlVo;
import com.github.niu.u.service.URLService;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * @author niuxiangqian
 * @version 1.0
 * @since 2020/12/23 8:56 下午
 **/
@Service
public class URLServiceImpl implements URLService {

    private static final Logger logger = LoggerFactory.getLogger(URLServiceImpl.class);

    /**
     * 自增序列的起始偏移量（62^5 = 916132832），保证短码至少 6 位，
     * 避免出现 "1"、"2" 这种一眼就能猜到的极短短码。
     */
    private static final long ID_OFFSET = 916132832L;

    /**
     * 缓存基础有效期（秒）：30 分钟。
     */
    private static final long CACHE_TTL_BASE_SECONDS = 1800L;

    /**
     * 缓存 TTL 随机抖动上限（秒）：0~299 秒。
     * 同一时刻批量写入的 key 不会同时到期，避免集中回源打穿数据库（缓存雪崩）。
     */
    private static final int CACHE_TTL_JITTER_SECONDS = 300;

    /**
     * 空值占位的存活时间（秒）。不存在的短码只挡 1 分钟，
     * 防止有人拿它当"负缓存"把合法短码挡住（比如短码刚被创建出来）。
     */
    private static final long EMPTY_CACHE_TTL_SECONDS = 60L;

    /**
     * 缓存重建锁的存活时间（秒）。
     * 取 3 秒是因为「一次主键查询 + 一次 SET」正常情况下只要几毫秒；
     * 给到 3 秒足够宽裕，又能保证持锁线程崩溃后锁很快自动释放，不会让后续请求全部卡住。
     */
    private static final long LOCK_TTL_SECONDS = 3L;

    /**
     * 没抢到锁的线程最多等几轮、每轮多久。
     * 3 × 100ms = 300ms 是刻意选的：重建一次缓存通常是个位数毫秒，
     * 300ms 还等不到说明持锁线程大概率出问题了，再等下去只会拖垮线程池。
     */
    private static final int LOCK_WAIT_TIMES = 3;

    private static final long LOCK_WAIT_MILLIS = 100L;

    @Value("${short_url.server:http://127.0.0.1:8080}")
    private String shortUrlServer;

    /**
     * 默认有效期（秒），默认 3 天，可通过 short_url.default-valid-time 覆盖。
     */
    @Value("${short_url.default-valid-time:259200}")
    private long defaultValidTime;

    @Resource
    private RedisService redisService;

    @Resource
    private ShortUrlMapper shortUrlMapper;

    @Resource
    private CacheMetrics cacheMetrics;

    @Resource
    private RedisBloomFilter bloomFilter;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ShortUrlVo generate(String srcUrl, Long valid) {
        if (StringUtils.isBlank(srcUrl)) {
            throw new BaseException("srcUrl不能为空");
        }
        if (valid == null) {
            valid = defaultValidTime;
        }
        // -1 表示永久有效；其余必须是正整数。注意 0 也不能放过：
        // Redis 的 SET ... EX 0 会直接报 "invalid expire time"。
        if (valid != -1 && valid <= 0) {
            throw new BaseException("有效时间必须大于 0，或传 -1 表示永久有效");
        }
        srcUrl = srcUrl.trim();

        LocalDateTime expireTime = valid == -1 ? null : LocalDateTime.now().plusSeconds(valid);
        String code = createShortTarget(srcUrl, expireTime);

        ShortUrlVo shortUrlVo = new ShortUrlVo();
        shortUrlVo.setOrgUrl(srcUrl)
                .setShortTarget(code)
                .setValidTime(valid)
                .setShortUrl(generateShortUrl(code));

        return shortUrlVo;
    }

    /**
     * 生成短码：数据库自增主键 -> Base62 编码 -> 回填 code -> 写缓存。
     * <p>
     * 与原来的「Redis INCR + SETNX + 5 次重试」相比，这里的唯一性由数据库自增主键保证：
     * <ul>
     *   <li>主键天然唯一且递增，Base62 是一一映射，**编码结果不可能冲突**，不需要重试；</li>
     *   <li>id 落库，重启、Redis 清空、多实例都不会影响短码的唯一性；</li>
     *   <li>代价是短码可枚举（连续 id 对应连续短码）；ID_OFFSET = 62^5 只是把短码补成固定 6 位，
     *       **自身不改变连续性，不能当防枚举/打散手段**——要真防枚举得再加一层映射。</li>
     * </ul>
     *
     * @param srcUrl     原始长链接
     * @param expireTime 过期时间，null 表示永久
     * @return 短码
     */
    private String createShortTarget(String srcUrl, LocalDateTime expireTime) {
        // 1、先插一条 code 为空的记录，拿数据库自增 id
        ShortUrl row = new ShortUrl()
                .setOriginUrl(srcUrl)
                .setExpireTime(expireTime)
                .setDelFlag(0);
        shortUrlMapper.insert(row);
        Long id = row.getId();
        if (id == null) {
            throw new BaseException("短链接生成失败，请稍后重试");
        }

        // 2、id + 偏移量 做 Base62 编码得到短码，再回填
        String code = Base62Util.encode(id + ID_OFFSET);
        ShortUrl update = new ShortUrl().setId(id).setCode(code);
        shortUrlMapper.updateById(update);

        // 3、写缓存，让第一次跳转就能命中
        writeCache(code, srcUrl, expireTime);

        // 4、同步写入布隆过滤器：以后所有跳转都要先过这一层
        bloomFilter.add(code);

        logger.info("生成短链 id={}, code={}, originUrl={}, expireTime={}", id, code, srcUrl, expireTime);
        return code;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean delete(String shortTarget) {
        if (StringUtils.isBlank(shortTarget)) {
            throw new BaseException("shortTarget不能为空");
        }

        ShortUrl row = shortUrlMapper.selectOne(Wrappers.<ShortUrl>lambdaQuery()
                .eq(ShortUrl::getCode, shortTarget)
                .eq(ShortUrl::getDelFlag, 0));
        if (row == null) {
            // 不存在或已删过：返回 false 而不是抛异常。删除是幂等操作，
            // 重复点删除按钮不该给用户报错
            return false;
        }

        // 逻辑删除而不是物理删除：保留记录才能回答「这个短码曾经指向哪里」；
        // 而且物理删掉也省不了空间里的短码——自增 id 不会回退，短码也不会被复用
        shortUrlMapper.updateById(new ShortUrl().setId(row.getId()).setDelFlag(1));

        deleteCacheAfterCommit(shortTarget);
        return true;
    }

    /**
     * 把「删缓存」注册到事务提交之后执行。
     * <p>
     * 这是 Cache-Aside 里最容易写错的地方。三种写法对比：
     * <ul>
     *   <li><b>事务内先删缓存再改库</b>：事务万一回滚，缓存没了、库还在，白删；
     *       并发读还可能在删缓存与提交之间读到旧值并回填，缓存里又变成旧数据。</li>
     *   <li><b>事务内改库再删缓存</b>：比上一种好，但删缓存后事务仍未提交，
     *       此时读请求会读到未提交前的旧值并回填，回填进去的是脏数据。</li>
     *   <li><b>提交后再删缓存（本实现）</b>：删的时候新数据一定已经可见，
     *       下一个读请求要么命中（新值），要么回源拿到新值。</li>
     * </ul>
     * 仍然存在的理论窗口：读请求在「提交后、删缓存前」拿到旧值并回填 → 缓存是旧值，
     * 直到 TTL 到期。要彻底消掉得用延迟双删（提交后再删一次）或订阅 binlog，
     * 本项目没做——短链的「更新」本身极少发生，为它引一套延迟任务不划算，
     * 这是明确的取舍而不是遗漏。
     */
    private void deleteCacheAfterCommit(String code) {
        final String key = CommonCache.SHORT_URL + code;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronizationAdapter() {
                @Override
                public void afterCommit() {
                    evictCache(key, code);
                }
            });
        } else {
            // 没有事务上下文（比如被单测直接调用）时退化为立即删，保证行为可预测
            evictCache(key, code);
        }
    }

    private void evictCache(String key, String code) {
        try {
            redisService.del(key);
            logger.info("删除短链 code={}，已清理缓存", code);
        } catch (Exception e) {
            // 数据库已经删成功了，缓存删不掉不能反过来让接口报失败。
            // 兜底是 TTL：最多 1800+300 秒后 key 自然过期，这段时间内可能仍能跳转
            logger.error("删除短链 code={} 后清理缓存失败，只能等 TTL 自然过期", code, e);
        }
    }

    @Override
    public String resolve(String shortTarget) {
        Objects.requireNonNull(shortTarget);
        String cacheKey = CommonCache.SHORT_URL + shortTarget;

        // 1、缓存优先。这里必须区分三种状态：
        //    null       -> key 不存在，真正的未命中，要回源
        //    ""         -> 空值占位，说明"数据库里也没有"，直接返回失效
        //    其他        -> 命中，直接返回
        //    注意不能用 StringUtils.isNotBlank 判断，它会把 "" 当成未命中，
        //    导致空值缓存形同虚设，请求照样打到数据库。
        //
        // 日志级别用 DEBUG 而不是 INFO：这条路径每秒可能跑几千次，
        // Day 2 压测 2 万次请求刷了 2 万行日志，日志 I/O 直接把 QPS 测量值拉低了。
        // 命中率看 /stats/cache 就够了，高频路径不该打 INFO——这是按调用量决定的，不是个人喜好。
        String cached = redisService.get(cacheKey);
        if (cached != null) {
            if (cached.isEmpty()) {
                cacheMetrics.recordNullHit();
                logger.debug("cache NULL-HIT code={}", shortTarget);
                return null;
            }
            cacheMetrics.recordHit();
            logger.debug("cache HIT code={}", shortTarget);
            return cached;
        }

        // 2、缓存未命中，先过布隆过滤器
        cacheMetrics.recordMiss();
        logger.debug("cache MISS code={}", shortTarget);

        if (!bloomFilter.mightContain(shortTarget)) {
            // 过滤器说"一定不存在"，直接失效，连数据库都不碰。
            // 这是防穿透的第一道闸：攻击者用一个随便编的短码高频请求，
            // 以前要打到数据库才知道不存在，现在在 Redis 里就被拦下。
            cacheMetrics.recordBloomRejected();
            logger.debug("bloom rejected code={}", shortTarget);
            return null;
        }

        // 3、可能存在，回源数据库。同一瞬间可能有 N 个线程一起走到这里，
        //    所以要用一把锁保证只有一个线程真的去查库，其余线程等它回填后直接读缓存
        return rebuildWithLock(shortTarget, cacheKey);
    }

    /**
     * 缓存重建：抢到锁的线程查库并回填，没抢到的等一会儿重读缓存。
     * <p>
     * 为什么光有空值缓存还不够（Day 2 压测实测）：
     * 20 个线程同时打一个不存在的短码，产生了 <b>19 次</b>回源；串行打 5 次只要 1 次。
     * 因为 20 个线程在同一瞬间都发现"缓存没有"，一起去查库，
     * 等第一个线程把空值写进去时，另外 19 个早就查完了。
     * 空值缓存解决的是「持续穿透」（同一个 code 反复打），解决不了「瞬时并发击穿」（并发同时打冷 key）。
     * <p>
     * 锁的三个细节（面试常问）：
     * <ul>
     *   <li><b>SET NX EX</b>：抢锁和设超时必须是一条命令，分两步会留下"抢到了但没设上超时"的死锁；</li>
     *   <li><b>值存 UUID</b>：释放时用它校验，避免锁超时自动释放后把别人刚拿到的锁删掉；</li>
     *   <li><b>等待有上限</b>：等不到就退化为自己查库，宁可多一次回源也不能把有效链接误判成 404。</li>
     * </ul>
     */
    private String rebuildWithLock(String code, String cacheKey) {
        String lockKey = CommonCache.SHORT_URL_LOCK + code;
        String token = UUID.randomUUID().toString();

        if (redisService.setIfAbsent(lockKey, token, LOCK_TTL_SECONDS, TimeUnit.SECONDS)) {
            try {
                return loadFromDbAndFill(code, cacheKey);
            } finally {
                releaseLock(lockKey, token);
            }
        }

        // 没抢到锁：说明已经有人在重建，等它写进缓存后直接读，不必自己再查一次库
        for (int i = 1; i <= LOCK_WAIT_TIMES; i++) {
            if (!sleepQuietly(LOCK_WAIT_MILLIS)) {
                break;
            }
            String retry = redisService.get(cacheKey);
            if (retry != null) {
                logger.debug("lock wait {}ms 后命中缓存 code={}", i * LOCK_WAIT_MILLIS, code);
                // 注意这里不再记一次 hit：本次请求前面已经记过 miss，
                // 再记 hit 会让 total 大于真实请求数，命中率被算偏高
                return retry.isEmpty() ? null : retry;
            }
        }

        // 等待超时（持锁线程可能挂了）：退化为自己查库。
        // 正确性优先——多一次回源只是性能损失，把有效链接判成 404 是业务事故
        logger.debug("lock wait timeout, fallback to db code={}", code);
        return loadFromDbAndFill(code, cacheKey);
    }

    /**
     * 回源数据库并回填缓存。只有持锁线程（或降级线程）会走到这里。
     */
    private String loadFromDbAndFill(String shortTarget, String cacheKey) {
        ShortUrl row = shortUrlMapper.selectOne(Wrappers.<ShortUrl>lambdaQuery()
                .eq(ShortUrl::getCode, shortTarget)
                .eq(ShortUrl::getDelFlag, 0));
        if (row == null) {
            // 数据库里也没有：写 60 秒空值占位。
            // 没有这一层，攻击者拿一个不存在的短码高频请求就能打穿缓存直击数据库。
            redisService.set(cacheKey, "", EMPTY_CACHE_TTL_SECONDS, TimeUnit.SECONDS);
            return null;
        }
        if (row.getExpireTime() != null && row.getExpireTime().isBefore(LocalDateTime.now())) {
            // 数据本身已过期：直接失效，但**不写空值缓存**。
            // 因为过期的记录将来可能被续期，写死 60 秒会把续期后的合法链接误挡在外面。
            logger.debug("cache MISS but expired, code={}", shortTarget);
            cacheMetrics.recordExpiredMiss();
            return null;
        }
        writeCache(shortTarget, row.getOriginUrl(), row.getExpireTime());
        return row.getOriginUrl();
    }

    /**
     * 释放锁：只有值还是自己的 token 才删。
     */
    private void releaseLock(String lockKey, String token) {
        try {
            redisService.delIfEquals(lockKey, token);
        } catch (Exception e) {
            // 释放失败不影响本次请求结果，锁会在 3 秒后自动过期
            logger.warn("释放缓存重建锁失败 key={}, 原因={}", lockKey, e.toString());
        }
    }

    /**
     * 可中断的睡眠。
     *
     * @return true 正常睡完；false 被中断（此时恢复中断标记，让上层知道该收尾了）
     */
    private boolean sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            // 吞掉 InterruptedException 又不恢复中断标记，是并发代码里最常见的一类 bug：
            // 线程池关闭时这个线程会"假装没收到信号"继续跑
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public ShortUrlVo restoreByTarget(String shortTarget) {
        Objects.requireNonNull(shortTarget);
        String srcUrl = resolve(shortTarget);
        if (StringUtils.isBlank(srcUrl)) {
            throw new BaseException("短链接不存在或者已经失效");
        }
        String shortUrl = generateShortUrl(shortTarget);
        ShortUrlVo shortUrlVo = new ShortUrlVo();
        shortUrlVo.setShortUrl(shortUrl)
                .setShortTarget(shortTarget)
                .setValidTime(redisService.getExpire(CommonCache.SHORT_URL + shortTarget))
                .setOrgUrl(srcUrl);

        return shortUrlVo;
    }

    /**
     * 根据完整的短链接解析
     *
     * @param shortUrl:
     * @return com.github.niu.u.model.vo.ShortUrlVo
     * @todo 存在域名变更问题
     * @author nxq
     */
    @Override
    public ShortUrlVo restoreByShortUrl(String shortUrl) {
        Objects.requireNonNull(shortUrl);
        if (!shortUrl.contains("/u/")) {
            throw new BaseException("短链接不合法");
        }
        String target = shortUrl.split("/u/")[1];

        return restoreByTarget(target);
    }

    /**
     * 写短链缓存。
     * <p>
     * TTL 取「基础值 + 随机抖动」与「数据剩余有效期」中较小的那个：
     * <ul>
     *   <li>加随机抖动：防止同一批 key 同时到期、集中回源造成缓存雪崩；</li>
     *   <li>压到剩余有效期以内：否则会出现「缓存还在、数据已过期」，用户点进去才发现失效。</li>
     * </ul>
     * 注意永久有效的链接（expireTime 为 null）同样要带 TTL：
     * 否则冷数据会永久占住 Redis 内存，把热点数据挤出去。
     */
    private void writeCache(String code, String originUrl, LocalDateTime expireTime) {
        String key = CommonCache.SHORT_URL + code;

        long ttl = CACHE_TTL_BASE_SECONDS + ThreadLocalRandom.current().nextInt(CACHE_TTL_JITTER_SECONDS);

        if (expireTime != null) {
            long remainSeconds = Duration.between(LocalDateTime.now(), expireTime).getSeconds();
            if (remainSeconds <= 0) {
                return;
            }
            ttl = Math.min(ttl, remainSeconds);
        }

        redisService.set(key, originUrl, ttl, TimeUnit.SECONDS);
    }

    private String generateShortUrl(String target) {
        return shortUrlServer + "/u/" + target;
    }
}
