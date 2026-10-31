package com.github.niu.u.config.redis;

import com.alibaba.fastjson.JSON;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/***
 * Redis 操作实现。
 *
 * <p>注入的 RedisTemplate 泛型与 {@link RedisConfig} 中定义的
 * {@code RedisTemplate<String, String>} 保持一致，避免泛型不一致
 * 导致 zset 元组被迫用 Object 接收。</p>
 *
 * @author niuxiangqian
 * @version 1.0
 * @since 2021/11/27 11:23 上午
 **/
@Component
public class RedisServiceImpl implements RedisService {

    /**
     * 值匹配才删除。写成 Lua 是为了保证「比较 + 删除」的原子性：
     * 如果在 Java 里先 GET 再 DEL，两步之间锁可能刚好过期并被别的线程抢走，
     * 于是删掉的是别人的锁。
     */
    private static final DefaultRedisScript<Long> DEL_IF_EQUALS_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end",
            Long.class);

    @Resource
    private RedisTemplate<String, String> redisTemplate;

    @Override
    public void set(String key, String value) {
        redisTemplate.opsForValue().set(key, value);
    }

    @Override
    public void set(String key, String value, long expire, TimeUnit timeUnit) {
        redisTemplate.opsForValue().set(key, value, expire, timeUnit);
    }

    @Override
    public String get(String key) {
        return redisTemplate.opsForValue().get(key);
    }

    @Override
    public boolean expire(String key, long expire) {
        return redisTemplate.expire(key, expire, TimeUnit.SECONDS);
    }

    @Override
    public void del(String key) {
        redisTemplate.opsForValue().getOperations().delete(key);
    }

    @Override
    public void delBatch(Set<String> keys) {
        redisTemplate.delete(keys);
    }

    @Override
    public void delBatch(String keyPrefix) {
        Set<String> keys = this.keySet(keyPrefix + "*");
        if (!CollectionUtils.isEmpty(keys)) {
            delBatch(keys);
        }
    }

    @Override
    public <T> void setList(String key, List<T> list) {
        String value = JSON.toJSONString(list);
        set(key, value);
    }

    @Override
    public <T> void setList(String key, List<T> list, long expire, TimeUnit timeUnit) {
        String value = JSON.toJSONString(list);
        set(key, value, expire, timeUnit);
    }

    @Override
    public <T> List<T> getList(String key, Class<T> clz) {
        String json = get(key);
        if (json != null) {
            return JSON.parseArray(json, clz);
        }
        return null;
    }

    @Override
    public boolean hasKey(String key) {
        return redisTemplate.hasKey(key);
    }

    @Override
    public long getExpire(String key) {
        return redisTemplate.getExpire(key);
    }

    @Override
    public Set<String> keySet(String keyPrefix) {
        return redisTemplate.keys(keyPrefix + "*");
    }

    @Override
    public void hashPut(String key, String hashKey, String value) {
        redisTemplate.opsForHash().put(key, hashKey, value);
    }

    @Override
    public String hashGet(String key, String hashKey) {
        Object value = redisTemplate.opsForHash().get(key, hashKey);
        return value == null ? null : value.toString();
    }

    @Override
    public void zIncrementScore(String key, String value, Double score) {
        redisTemplate.opsForZSet().incrementScore(key, value, score);
    }

    @Override
    public void zIncrementScore(String key, String value) {
        zIncrementScore(key, value, 1d);
    }

    @Override
    public long incr(String key) {
        Long value = redisTemplate.opsForValue().increment(key);
        return value == null ? 0L : value;
    }

    @Override
    public boolean setIfAbsent(String key, String value) {
        Boolean ok = redisTemplate.opsForValue().setIfAbsent(key, value);
        return Boolean.TRUE.equals(ok);
    }

    @Override
    public boolean setIfAbsent(String key, String value, long expire, TimeUnit timeUnit) {
        Boolean ok = redisTemplate.opsForValue().setIfAbsent(key, value, expire, timeUnit);
        return Boolean.TRUE.equals(ok);
    }

    @Override
    public Set<ZSetOperations.TypedTuple<String>> zReverseRangeWithScores(String key, long start, long end) {
        return redisTemplate.opsForZSet().reverseRangeWithScores(key, start, end);
    }

    @Override
    public Double zScore(String key, String value) {
        return redisTemplate.opsForZSet().score(key, value);
    }

    @Override
    public void zIncrementBatch(String key, Map<String, Long> deltas) {
        if (deltas == null || deltas.isEmpty()) {
            return;
        }
        final byte[] rawKey = key.getBytes(StandardCharsets.UTF_8);
        redisTemplate.executePipelined((RedisCallback<Object>) (RedisConnection connection) -> {
            deltas.forEach((member, delta) -> connection.zIncrBy(rawKey, delta.doubleValue(),
                    member.getBytes(StandardCharsets.UTF_8)));
            return null;
        });
    }

    @Override
    public boolean delIfEquals(String key, String value) {
        Long deleted = redisTemplate.execute(DEL_IF_EQUALS_SCRIPT, Collections.singletonList(key), value);
        return deleted != null && deleted > 0L;
    }
}
