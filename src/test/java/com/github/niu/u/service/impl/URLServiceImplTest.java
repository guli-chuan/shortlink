package com.github.niu.u.service.impl;

import com.github.niu.u.common.CommonCache;
import com.github.niu.u.common.bloom.RedisBloomFilter;
import com.github.niu.u.common.cache.CacheMetrics;
import com.github.niu.u.common.exception.BaseException;
import com.github.niu.u.common.util.Base62Util;
import com.github.niu.u.config.redis.RedisService;
import com.github.niu.u.mapper.ShortUrlMapper;
import com.github.niu.u.model.entity.ShortUrl;
import com.github.niu.u.model.vo.CacheMetricsVo;
import com.github.niu.u.model.vo.ShortUrlVo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 生成与解析链路测试。
 * <p>
 * 用 Mockito 替代 Redis 和 MyBatis-Plus，不依赖真实中间件，可在 CI 中直接跑。
 * 注意：原版本测的是「Redis INCR + SETNX + 重试」，改成数据库自增主键方案后这套断言已失效，
 * 这里按新方案重写——存储策略变了，测试必须跟着变，否则等于给自己留假绿。
 *
 * @author niuxiangqian
 * @version 1.0
 * @since 2026/10/21
 **/
@ExtendWith(MockitoExtension.class)
class URLServiceImplTest {

    private static final long ID_OFFSET = 916132832L;
    private static final long FAKE_ID = 42L;

    @Mock
    private RedisService redisService;

    @Mock
    private ShortUrlMapper shortUrlMapper;

    /**
     * 布隆过滤器默认放行（返回 true）：绝大多数用例测的是"过滤器之后"的链路，
     * 让它们被过滤器拦住会掩盖真正的断言。专门验证拦截行为的用例里单独改成 false。
     */
    @Mock
    private RedisBloomFilter bloomFilter;

    @InjectMocks
    private URLServiceImpl urlService;

    /**
     * 命中率计数器用真实实例（不是 mock）：它的状态就是被测对象，
     * mock 掉之后断言「命中一次 hit 应该 +1」就变成了自欺欺人。
     */
    private final CacheMetrics cacheMetrics = new CacheMetrics();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(urlService, "defaultValidTime", 259200L);
        ReflectionTestUtils.setField(urlService, "shortUrlServer", "http://127.0.0.1:8080");
        ReflectionTestUtils.setField(urlService, "cacheMetrics", cacheMetrics);

        // lenient：不是每个用例都会走到布隆过滤器和抢锁，用 strict stub 会因为"多余的 stub"报错
        lenient().when(bloomFilter.mightContain(anyString())).thenReturn(true);
        // 默认抢锁成功，走"自己查库回填"的主路径；测锁竞争时单独改成 false
        lenient().when(redisService.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);
    }

    // ---------------- 参数校验 ----------------

    @Test
    @DisplayName("validTime=0 应被拒绝，且不触碰 DB 与 Redis")
    void zeroValidTimeShouldBeRejected() {
        BaseException ex = assertThrows(BaseException.class,
                () -> urlService.generate("https://www.baidu.com", 0L));

        assertTrue(ex.getMessage().contains("有效时间"), "异常信息应提示有效时间");
        verifyNoInteractions(redisService);
        verifyNoInteractions(shortUrlMapper);
    }

    @Test
    @DisplayName("validTime 为负数（且不是 -1）应被拒绝")
    void negativeValidTimeShouldBeRejected() {
        assertThrows(BaseException.class, () -> urlService.generate("https://www.baidu.com", -2L));
        verifyNoInteractions(redisService);
        verifyNoInteractions(shortUrlMapper);
    }

    @Test
    @DisplayName("url 为空白应被拒绝")
    void blankUrlShouldBeRejected() {
        assertThrows(BaseException.class, () -> urlService.generate("   ", 60L));
        verifyNoInteractions(redisService);
        verifyNoInteractions(shortUrlMapper);
    }

    // ---------------- 生成链路 ----------------

    @Test
    @DisplayName("先 insert 拿自增 id，再用 Base62(id+偏移量) 回填 code")
    void shouldInsertThenBackfillCodeByAutoIncrementId() {
        mockInsertReturningId(FAKE_ID);
        when(shortUrlMapper.updateById(any(ShortUrl.class))).thenReturn(1);

        ShortUrlVo vo = urlService.generate("https://www.baidu.com", null);

        String expectedCode = Base62Util.encode(FAKE_ID + ID_OFFSET);
        assertEquals(expectedCode, vo.getShortTarget(), "短码应等于 Base62(id + 偏移量)");
        assertEquals(6, vo.getShortTarget().length(), "加偏移量后短码至少 6 位");
        assertEquals("http://127.0.0.1:8080/u/" + expectedCode, vo.getShortUrl());
        assertEquals(259200L, vo.getValidTime().longValue());

        ArgumentCaptor<ShortUrl> captor = ArgumentCaptor.forClass(ShortUrl.class);
        verify(shortUrlMapper).updateById(captor.capture());
        assertEquals(FAKE_ID, captor.getValue().getId());
        assertEquals(expectedCode, captor.getValue().getCode());

        // 写缓存时 TTL 取剩余秒数，不会超过有效期
        verify(redisService).set(anyString(), eq("https://www.baidu.com"), anyLong(), eq(TimeUnit.SECONDS));
        // 新增的短码必须同步进布隆过滤器，否则刚生成的链接会被自己的过滤器拦成 404
        verify(bloomFilter).add(expectedCode);
    }

    @Test
    @DisplayName("validTime=-1 时 expireTime 为空，但缓存仍写随机 TTL，不永久驻留")
    void minusOneShouldStillWriteWithTtl() {
        mockInsertReturningId(FAKE_ID);
        when(shortUrlMapper.updateById(any(ShortUrl.class))).thenReturn(1);

        ShortUrlVo vo = urlService.generate("https://www.qq.com", -1L);

        assertEquals(-1L, vo.getValidTime().longValue());
        ArgumentCaptor<ShortUrl> captor = ArgumentCaptor.forClass(ShortUrl.class);
        verify(shortUrlMapper).insert(captor.capture());
        assertNull(captor.getValue().getExpireTime(), "永久有效时不应写过期时间");

        // 数据永久有效 != 缓存永久驻留：仍要带 TTL，否则冷数据会一直占内存
        ArgumentCaptor<Long> ttlCaptor = ArgumentCaptor.forClass(Long.class);
        verify(redisService).set(anyString(), eq("https://www.qq.com"), ttlCaptor.capture(), eq(TimeUnit.SECONDS));
        long ttl = ttlCaptor.getValue();
        assertTrue(ttl >= 1800 && ttl < 2100, "TTL 应落在 [1800, 2100) 的随机区间，实际=" + ttl);
    }

    @Test
    @DisplayName("insert 拿不到自增 id 时应直接报错，不写缓存")
    void shouldFailFastWhenIdNotGenerated() {
        when(shortUrlMapper.insert(any(ShortUrl.class))).thenReturn(1);

        assertThrows(BaseException.class, () -> urlService.generate("https://www.taobao.com", 60L));
        verify(redisService, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    // ---------------- 解析链路 ----------------

    @Test
    @DisplayName("缓存命中时不查库")
    void resolveShouldHitCacheWithoutDb() {
        when(redisService.get(CommonCache.SHORT_URL + "abc123")).thenReturn("https://www.baidu.com");

        assertEquals("https://www.baidu.com", urlService.resolve("abc123"));
        verifyNoInteractions(shortUrlMapper);
        assertEquals(1L, cacheMetrics.snapshot().getHit(), "命中缓存应记一次 hit");
        assertEquals(0L, cacheMetrics.snapshot().getMiss(), "命中时不应产生 miss");
    }

    @Test
    @DisplayName("缓存未命中时回源数据库并回填缓存")
    void resolveShouldFallbackToDbAndBackfill() {
        ShortUrl row = new ShortUrl()
                .setCode("abc123")
                .setOriginUrl("https://www.baidu.com")
                .setExpireTime(LocalDateTime.now().plusDays(1));
        when(redisService.get(CommonCache.SHORT_URL + "abc123")).thenReturn(null);
        when(shortUrlMapper.selectOne(any())).thenReturn(row);

        assertEquals("https://www.baidu.com", urlService.resolve("abc123"));
        verify(redisService).set(eq(CommonCache.SHORT_URL + "abc123"), eq("https://www.baidu.com"),
                anyLong(), eq(TimeUnit.SECONDS));
        assertEquals(1L, cacheMetrics.snapshot().getMiss(), "回源应记一次 miss");
    }

    @Test
    @DisplayName("空值占位命中时直接返回 null，不再查库")
    void resolveShouldHitEmptyCacheWithoutDb() {
        // "" 表示之前已经确认过"库里也没有"，属于命中，不能当成未命中再查一次库
        when(redisService.get(CommonCache.SHORT_URL + "nope")).thenReturn("");

        assertNull(urlService.resolve("nope"));
        verifyNoInteractions(shortUrlMapper);
        // 空值命中要单独计数：它挡掉了回源，但不是业务意义上的「命中」
        assertEquals(1L, cacheMetrics.snapshot().getNullHit());
        assertEquals(0L, cacheMetrics.snapshot().getHit(), "空值不能混进 hit");
    }

    @Test
    @DisplayName("库里也查不到时写入 60 秒空值占位，防穿透")
    void resolveShouldWriteEmptyPlaceholderWhenAbsent() {
        when(redisService.get(CommonCache.SHORT_URL + "nope")).thenReturn(null);
        when(shortUrlMapper.selectOne(any())).thenReturn(null);

        assertNull(urlService.resolve("nope"));
        verify(redisService).set(eq(CommonCache.SHORT_URL + "nope"), eq(""), eq(60L), eq(TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("库里有记录但已过期时返回 null，且不写空值缓存")
    void resolveShouldReturnNullWhenExpired() {
        ShortUrl row = new ShortUrl()
                .setCode("old999")
                .setOriginUrl("https://www.baidu.com")
                .setExpireTime(LocalDateTime.now().minusSeconds(1));
        when(redisService.get(CommonCache.SHORT_URL + "old999")).thenReturn(null);
        when(shortUrlMapper.selectOne(any())).thenReturn(row);

        assertNull(urlService.resolve("old999"));
        // 过期记录将来可能被续期，写死 60 秒空值会把续期后的合法链接挡在外面
        verify(redisService, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        CacheMetricsVo snapshot = cacheMetrics.snapshot();
        assertEquals(1L, snapshot.getExpiredMiss(), "过期回源单独记一笔，便于排查");
        assertEquals(1L, snapshot.getMiss(), "过期回源同时也是一次 miss");
    }

    @Test
    @DisplayName("命中率按真命中口径计算，空值命中不计入 hit")
    void hitRateShouldOnlyCountRealHits() {
        when(redisService.get(CommonCache.SHORT_URL + "a1")).thenReturn("https://www.baidu.com");
        when(redisService.get(CommonCache.SHORT_URL + "a2")).thenReturn("https://www.qq.com");
        when(redisService.get(CommonCache.SHORT_URL + "a3")).thenReturn("");
        when(redisService.get(CommonCache.SHORT_URL + "a4")).thenReturn(null);
        when(shortUrlMapper.selectOne(any())).thenReturn(null);

        urlService.resolve("a1");
        urlService.resolve("a2");
        urlService.resolve("a3");
        urlService.resolve("a4");

        CacheMetricsVo snapshot = cacheMetrics.snapshot();
        assertEquals(4L, snapshot.getTotal());
        assertEquals(2L, snapshot.getHit());
        assertEquals(1L, snapshot.getNullHit());
        assertEquals(1L, snapshot.getMiss());
        assertEquals(0.5D, snapshot.getHitRate(), 1e-9, "真命中率 = 2/4");
        assertEquals(0.25D, snapshot.getNullHitRate(), 1e-9);
        assertEquals(0.25D, snapshot.getMissRate(), 1e-9);
    }

    @Test
    @DisplayName("剩余有效期短于基础 TTL 时，缓存 TTL 取剩余有效期，不会比数据活得久")
    void ttlShouldNotOutliveData() {
        ShortUrl row = new ShortUrl()
                .setCode("soon01")
                .setOriginUrl("https://www.baidu.com")
                .setExpireTime(LocalDateTime.now().plusSeconds(120));
        when(redisService.get(CommonCache.SHORT_URL + "soon01")).thenReturn(null);
        when(shortUrlMapper.selectOne(any())).thenReturn(row);

        urlService.resolve("soon01");

        ArgumentCaptor<Long> ttlCaptor = ArgumentCaptor.forClass(Long.class);
        verify(redisService).set(eq(CommonCache.SHORT_URL + "soon01"), eq("https://www.baidu.com"),
                ttlCaptor.capture(), eq(TimeUnit.SECONDS));
        assertTrue(ttlCaptor.getValue() <= 120,
                "TTL 不能超过数据剩余有效期，实际=" + ttlCaptor.getValue());
    }

    // ---------------- 布隆过滤器与互斥重建 ----------------

    @Test
    @DisplayName("布隆过滤器判定一定不存在：直接返回 null，一次 SQL 都不发")
    void bloomRejectShouldSkipDb() {
        when(redisService.get(CommonCache.SHORT_URL + "fake01")).thenReturn(null);
        when(bloomFilter.mightContain("fake01")).thenReturn(false);

        assertNull(urlService.resolve("fake01"));

        verifyNoInteractions(shortUrlMapper);
        // 也不该去抢锁：确定不存在的东西没什么可重建的
        verify(redisService, never()).setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        CacheMetricsVo snapshot = cacheMetrics.snapshot();
        assertEquals(1L, snapshot.getBloomRejected(), "被过滤器拦下要单独计数");
        assertEquals(1L, snapshot.getMiss(), "缓存没命中，仍然算一次 miss");
    }

    @Test
    @DisplayName("没抢到锁时等待片刻重读缓存，别人已经回填就直接用，不再查库")
    void shouldReadCacheAgainWhenLockNotAcquired() {
        String cacheKey = CommonCache.SHORT_URL + "hot001";
        // 第一次读是未命中（所以在抢锁前就返回 null），等 100ms 后别人已经写好
        when(redisService.get(cacheKey)).thenReturn(null, "https://www.baidu.com");
        when(redisService.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(false);

        assertEquals("https://www.baidu.com", urlService.resolve("hot001"));

        verifyNoInteractions(shortUrlMapper);
        // 等待到的那次不算 hit：本次请求已经记过 miss，再记 hit 会把命中率算高
        assertEquals(0L, cacheMetrics.snapshot().getHit());
        assertEquals(1L, cacheMetrics.snapshot().getMiss());
    }

    @Test
    @DisplayName("锁等待超时后降级为自己查库，不能把有效链接误判成 404")
    void shouldFallbackToDbWhenLockWaitTimeout() {
        String cacheKey = CommonCache.SHORT_URL + "slow01";
        when(redisService.get(cacheKey)).thenReturn(null);
        when(redisService.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(false);
        ShortUrl row = new ShortUrl().setCode("slow01").setOriginUrl("https://www.qq.com")
                .setExpireTime(LocalDateTime.now().plusDays(1));
        when(shortUrlMapper.selectOne(any())).thenReturn(row);

        assertEquals("https://www.qq.com", urlService.resolve("slow01"),
                "持锁线程失联时也要能返回结果，正确性优先于少查一次库");
        verify(shortUrlMapper).selectOne(any());
    }

    @Test
    @DisplayName("抢到锁后即使中途抛异常也要释放锁，否则这个短码会卡 3 秒")
    void shouldReleaseLockEvenWhenDbFails() {
        String cacheKey = CommonCache.SHORT_URL + "err001";
        when(redisService.get(cacheKey)).thenReturn(null);
        when(shortUrlMapper.selectOne(any())).thenThrow(new RuntimeException("db down"));

        assertThrows(RuntimeException.class, () -> urlService.resolve("err001"));

        ArgumentCaptor<String> lockKeyCaptor = ArgumentCaptor.forClass(String.class);
        verify(redisService).delIfEquals(lockKeyCaptor.capture(), anyString());
        assertEquals(CommonCache.SHORT_URL_LOCK + "err001", lockKeyCaptor.getValue());
    }

    // ---------------- 删除链路 ----------------

    @Test
    @DisplayName("删除：逻辑删除置 del_flag=1，并在事务提交后清缓存")
    void deleteShouldMarkDelFlagAndEvictCacheAfterCommit() {
        ShortUrl row = new ShortUrl().setId(7L).setCode("abc123")
                .setOriginUrl("https://www.baidu.com").setDelFlag(0);
        when(shortUrlMapper.selectOne(any())).thenReturn(row);
        when(shortUrlMapper.updateById(any(ShortUrl.class))).thenReturn(1);

        // 手动开启事务同步，模拟 @Transactional 生效时的环境
        TransactionSynchronizationManager.initSynchronization();
        try {
            assertTrue(urlService.delete("abc123"));

            // 逻辑删除而不是物理删除
            ArgumentCaptor<ShortUrl> captor = ArgumentCaptor.forClass(ShortUrl.class);
            verify(shortUrlMapper).updateById(captor.capture());
            assertEquals(7L, captor.getValue().getId());
            assertEquals(1, captor.getValue().getDelFlag(), "应该是逻辑删除 del_flag=1");

            // 关键断言：事务还没提交，缓存一个字节都不能动
            verify(redisService, never()).del(anyString());

            // 模拟事务提交，此时才该删缓存
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
            verify(redisService).del(CommonCache.SHORT_URL + "abc123");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("删除不存在的短码返回 false，不写库也不动缓存")
    void deleteMissingCodeShouldReturnFalse() {
        when(shortUrlMapper.selectOne(any())).thenReturn(null);

        assertFalse(urlService.delete("nope"));
        verify(shortUrlMapper, never()).updateById(any(ShortUrl.class));
        verify(redisService, never()).del(anyString());
    }

    @Test
    @DisplayName("删除空白短码应报错，不触碰 DB 与 Redis")
    void deleteBlankCodeShouldBeRejected() {
        assertThrows(BaseException.class, () -> urlService.delete("  "));
        verifyNoInteractions(shortUrlMapper);
        verifyNoInteractions(redisService);
    }

    @Test
    @DisplayName("缓存删不掉时，删除操作仍然算成功（库里已经删了，不能反过来报失败）")
    void deleteShouldStillSucceedWhenCacheEvictionFails() {
        ShortUrl row = new ShortUrl().setId(7L).setCode("abc123").setDelFlag(0);
        when(shortUrlMapper.selectOne(any())).thenReturn(row);
        when(shortUrlMapper.updateById(any(ShortUrl.class))).thenReturn(1);
        doThrow(new RuntimeException("redis down")).when(redisService).del(anyString());

        // 没有事务上下文，走的是「立即删」分支，异常被吞掉
        assertTrue(urlService.delete("abc123"),
                "缓存删除失败不应让删除接口报错，兜底靠 TTL 自然过期");
    }

    @Test
    @DisplayName("删除后再解析：库里 del_flag=0 查不到，返回 null 并写空值占位")
    void resolveAfterDeleteShouldReturnNull() {
        when(redisService.get(CommonCache.SHORT_URL + "abc123")).thenReturn(null);
        // 逻辑删除后，带 del_flag=0 条件的查询自然查不到
        when(shortUrlMapper.selectOne(any())).thenReturn(null);

        assertNull(urlService.resolve("abc123"));
        verify(redisService).set(eq(CommonCache.SHORT_URL + "abc123"), eq(""), eq(60L), eq(TimeUnit.SECONDS));
    }

    /**
     * 模拟 MyBatis-Plus insert：写入时由数据库回填自增主键。
     */
    private void mockInsertReturningId(long id) {
        doAnswer(invocation -> {
            ShortUrl arg = invocation.getArgument(0);
            arg.setId(id);
            return 1;
        }).when(shortUrlMapper).insert(any(ShortUrl.class));
    }
}
