package com.github.niu.u.common.stats;

import com.github.niu.u.common.CommonCache;
import com.github.niu.u.config.redis.RedisService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 访问统计异步计数器测试。
 *
 * @author niuxiangqian
 * @version 1.0
 * @since 2026/10/29
 **/
@ExtendWith(MockitoExtension.class)
class AccessCounterTest {

    @Mock
    private RedisService redisService;

    private AccessCounter counter;

    @BeforeEach
    void setUp() {
        counter = new AccessCounter();
        // 不调 start()：定时任务依赖真实时钟，测起来又慢又不稳定，
        // 这里直接测 flush()/unsaved() 这两个核心方法
        ReflectionTestUtils.setField(counter, "redisService", redisService);
    }

    @Test
    @DisplayName("本地累加不碰 Redis，unsaved 能读到未刷写的数量")
    void incrementShouldOnlyTouchMemory() {
        for (int i = 0; i < 5; i++) {
            counter.increment("100004");
        }

        assertEquals(5L, counter.unsaved("100004"));
        verifyNoInteractions(redisService);
    }

    @Test
    @DisplayName("flush 把同一短码的计数聚合成一个增量，写完清零")
    void flushShouldAggregateAndReset() {
        counter.increment("100004");
        counter.increment("100004");
        counter.increment("100004");
        counter.increment("100005");

        int flushed = counter.flush();

        assertEquals(2, flushed, "两个短码应该只产生两次 zIncrBy");
        verify(redisService).zIncrementBatch(eq(CommonCache.ACCESS_COUNT), argThatContains("100004", 3L));
        assertEquals(0L, counter.unsaved("100004"), "刷写后本地应清零，否则下轮会重复累加");
    }

    @Test
    @DisplayName("没有计数时 flush 直接返回，不发任何 Redis 命令")
    void flushWithEmptyBufferShouldDoNothing() {
        assertEquals(0, counter.flush());
        verify(redisService, never()).zIncrementBatch(any(), anyMap());
    }

    @Test
    @DisplayName("刷写失败要把计数还回本地，否则这批访问记录凭空消失")
    void flushFailureShouldRequeueCounts() {
        counter.increment("100004");
        counter.increment("100004");
        doThrow(new RuntimeException("redis down"))
                .when(redisService).zIncrementBatch(any(), anyMap());

        assertThrows(RuntimeException.class, () -> counter.flush());

        assertEquals(2L, counter.unsaved("100004"), "失败后计数必须回到本地，等下一轮重试");
    }

    @Test
    @DisplayName("停机时把剩余计数刷掉，缩小丢数据的窗口")
    void shutdownShouldFlushRemaining() {
        ReflectionTestUtils.setField(counter, "flushIntervalSeconds", 5L);
        counter.start();
        counter.increment("100006");

        counter.shutdown();

        assertTrue(counter.unsaved("100006") == 0L, "停机后本地不应再有未刷写的计数");
        verify(redisService).zIncrementBatch(eq(CommonCache.ACCESS_COUNT), argThatContains("100006", 1L));
    }

    @Test
    @DisplayName("空短码直接忽略，不进 map")
    void blankCodeShouldBeIgnored() {
        counter.increment(null);
        counter.increment("");
        assertEquals(0L, counter.unsaved(""));
        assertEquals(0, counter.flush());
    }

    private static Map<String, Long> argThatContains(String code, long expected) {
        return org.mockito.ArgumentMatchers.argThat(map -> map != null
                && Long.valueOf(expected).equals(map.get(code)));
    }
}
