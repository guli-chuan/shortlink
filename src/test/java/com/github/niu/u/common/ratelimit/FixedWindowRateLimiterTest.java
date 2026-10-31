package com.github.niu.u.common.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 限流器单测。
 *
 * <p>这里 mock 掉 RedisTemplate，测的是「拿到脚本返回值之后怎么判断」；
 * Lua 脚本本身有没有按预期 INCR/EXPIRE，只能连真 Redis 验（端到端那条）。
 * 两者都要有：单测保证分支逻辑对，端到端保证脚本对。</p>
 *
 * @author niuxiangqian
 * @since 2026/10/30
 */
class FixedWindowRateLimiterTest {

    private RedisTemplate<String, String> redisTemplate;
    private FixedWindowRateLimiter limiter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(RedisTemplate.class);
        limiter = new FixedWindowRateLimiter();
        ReflectionTestUtils.setField(limiter, "redisTemplate", redisTemplate);
    }

    @Test
    @DisplayName("窗口内未超限：放行，并带回当前计数")
    void allowsWhenUnderLimit() {
        mockScriptReturn(Arrays.asList(1L, 3L, 57L));

        FixedWindowRateLimiter.Decision decision = limiter.tryAcquire("u:rate:test:1.1.1.1", 20, 60);

        assertTrue(decision.isAllowed());
        assertEquals(3L, decision.getCurrentCount());
        assertEquals(57L, decision.getTtlSeconds());
    }

    @Test
    @DisplayName("刚好第 limit 次仍然放行，第 limit+1 次才拒绝")
    void rejectsOnlyWhenExceedingLimit() {
        mockScriptReturn(Arrays.asList(1L, 20L, 30L));
        assertTrue(limiter.tryAcquire("k", 20, 60).isAllowed());

        mockScriptReturn(Arrays.asList(0L, 21L, 30L));
        FixedWindowRateLimiter.Decision rejected = limiter.tryAcquire("k", 20, 60);
        assertFalse(rejected.isAllowed());
        // 计数含被拒的这一次，方便日志里看清"已经超到第几次"
        assertEquals(21L, rejected.getCurrentCount());
        // 剩余窗口要带回去，才能给出准确的 Retry-After
        assertEquals(30L, rejected.getTtlSeconds());
    }

    @Test
    @DisplayName("配置写错（limit<=0）时放行，且不访问 Redis")
    void allowsWhenLimitMisconfigured() {
        assertTrue(limiter.tryAcquire("k", 0, 60).isAllowed());
        assertTrue(limiter.tryAcquire("k", 20, 0).isAllowed());
        verifyNoInteractions(redisTemplate);
    }

    @Test
    @DisplayName("Redis 异常时 fail-open 放行，不把限流器变成新的故障点")
    void failsOpenWhenRedisDown() {
        doThrow(new RedisConnectionFailureException("redis down"))
                .when(redisTemplate).execute(any(RedisScript.class), anyList(), any(), any());

        FixedWindowRateLimiter.Decision decision = limiter.tryAcquire("k", 20, 60);

        assertTrue(decision.isAllowed());
    }

    @Test
    @DisplayName("脚本返回 null 或元素不足时放行")
    void allowsWhenScriptReturnsUnexpectedResult() {
        doReturn(null).when(redisTemplate).execute(any(RedisScript.class), anyList(), any(), any());
        assertTrue(limiter.tryAcquire("k", 20, 60).isAllowed());

        mockScriptReturn(Collections.singletonList(1L));
        assertTrue(limiter.tryAcquire("k", 20, 60).isAllowed());
    }

    /**
     * 脚本正常返回 {1/0, current, ttl} 三元素。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void mockScriptReturn(List<?> result) {
        doReturn(result).when(redisTemplate).execute(any(RedisScript.class), anyList(), any(), any());
    }
}
