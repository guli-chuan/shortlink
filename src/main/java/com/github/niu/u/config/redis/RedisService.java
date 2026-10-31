package com.github.niu.u.config.redis;

import org.springframework.data.redis.core.ZSetOperations;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/***
 * redis操作
 *
 * <p>注意：底层 RedisTemplate 的序列化器是 StringRedisSerializer，
 * 因此所有值都以字符串形式存取。需要存对象时请自行 JSON 序列化
 * （参考 {@link #setList(String, List)}）。</p>
 *
 * @author niuxiangqian
 * @version 1.0
 * @since 2021/11/27 11:22 上午
 **/
public interface RedisService {

    void set(String key, String value);

    void set(String key, String value, long expire, TimeUnit timeUnit);

    String get(String key);

    boolean expire(String key, long expire);

    void del(String key);

    void delBatch(Set<String> keys);

    void delBatch(String keyPrefix);

    <T> void setList(String key, List<T> list);

    <T> void setList(String key, List<T> list, long expire, TimeUnit timeUnit);

    <T> List<T> getList(String key, Class<T> clz);

    boolean hasKey(String key);

    long getExpire(String key);

    Set<String> keySet(String keyPrefix);

    void hashPut(String key, String hashKey, String value);

    String hashGet(String key, String hashKey);

    void zIncrementScore(String key, String value, Double score);

    void zIncrementScore(String key, String value);

    /**
     * 自增并返回自增后的值（对应 Redis INCR），用于生成全局唯一短码序列。
     */
    long incr(String key);

    /**
     * key 不存在时才写入（对应 Redis SETNX）。
     *
     * @return 写入成功返回 true，key 已存在返回 false
     */
    boolean setIfAbsent(String key, String value);

    /**
     * key 不存在时才写入并设置过期时间（对应 Redis SET NX EX）。
     *
     * @return 写入成功返回 true，key 已存在返回 false
     */
    boolean setIfAbsent(String key, String value, long expire, TimeUnit timeUnit);

    /**
     * 按分数从高到低取指定区间（用于访问排行 Top N）。
     */
    Set<ZSetOperations.TypedTuple<String>> zReverseRangeWithScores(String key, long start, long end);

    /**
     * 获取 zset 中某个成员的分数，不存在返回 null。
     */
    Double zScore(String key, String value);

    /**
     * 批量给 zset 累加分数，走 pipeline：无论多少个成员，只付一次网络往返。
     * <p>
     * 用于访问统计的异步刷写——本地攒了 5 秒的计数一次性写进 Redis。
     *
     * @param key    zset 的 key
     * @param deltas member -> 本次要累加的分数
     */
    void zIncrementBatch(String key, Map<String, Long> deltas);

    /**
     * 值相等时才删除（对应 Lua: if GET(key)==ARGV[1] then DEL key end）。
     * <p>
     * 用于安全释放分布式锁：如果锁已经超时自动释放、又被别人抢到，
     * 直接 DEL 会把别人的锁删掉，导致互斥失效。
     *
     * @return 真正删掉了返回 true；key 不存在或值不匹配返回 false
     */
    boolean delIfEquals(String key, String value);
}
