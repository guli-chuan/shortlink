package com.github.niu.u.interceptor;

import com.github.niu.u.common.ratelimit.FixedWindowRateLimiter;
import com.github.niu.u.common.ratelimit.RateLimit;
import com.github.niu.u.common.ratelimit.RateLimitException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.method.HandlerMethod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 限流拦截器单测：注解识别、IP 取值、key 拼装、超限转异常。
 *
 * @author niuxiangqian
 * @since 2026/10/30
 */
class RateLimitInterceptorTest {

    private FixedWindowRateLimiter rateLimiter;
    private RateLimitInterceptor interceptor;

    @BeforeEach
    void setUp() {
        rateLimiter = mock(FixedWindowRateLimiter.class);
        interceptor = new RateLimitInterceptor();
        ReflectionTestUtils.setField(interceptor, "rateLimiter", rateLimiter);
        ReflectionTestUtils.setField(interceptor, "enabled", true);
        ReflectionTestUtils.setField(interceptor, "trustProxy", false);
    }

    @Test
    @DisplayName("handler 不是 Controller 方法时直接放行")
    void passesNonHandlerMethod() throws Exception {
        assertTrue(interceptor.preHandle(request(), response(), new Object()));
        verifyNoInteractions(rateLimiter);
    }

    @Test
    @DisplayName("方法上没有 @RateLimit 时不限流")
    void passesMethodWithoutAnnotation() throws Exception {
        when(rateLimiter.tryAcquire(anyString(), anyInt(), anyLong()))
                .thenReturn(FixedWindowRateLimiter.Decision.allow(1, 60));

        assertTrue(interceptor.preHandle(request(), response(), handler("open")));
        verifyNoInteractions(rateLimiter);
    }

    @Test
    @DisplayName("未超限时放行，key 按 scene + 客户端 IP 拼装")
    void passesWhenAllowedAndBuildsKey() throws Exception {
        when(rateLimiter.tryAcquire(anyString(), anyInt(), anyLong()))
                .thenReturn(FixedWindowRateLimiter.Decision.allow(1, 60));

        MockHttpServletRequest request = request();
        request.setRemoteAddr("10.0.0.8");

        assertTrue(interceptor.preHandle(request, response(), handler("limited")));

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(rateLimiter).tryAcquire(key.capture(), eq(2), eq(60L));
        assertEquals("u:rate:generate:10.0.0.8", key.getValue());
    }

    @Test
    @DisplayName("超限时抛出限流异常，并带回剩余秒数")
    void throwsWhenExceeded() throws Exception {
        when(rateLimiter.tryAcquire(anyString(), anyInt(), anyLong()))
                .thenReturn(FixedWindowRateLimiter.Decision.of(false, 3, 42));

        RateLimitException e = assertThrows(RateLimitException.class,
                () -> interceptor.preHandle(request(), response(), handler("limited")));

        assertEquals(42L, e.getRetryAfterSeconds());
        assertEquals(2, e.getLimit());
    }

    @Test
    @DisplayName("剩余秒数为 0 时用整个窗口长度兜底，不能回 0")
    void fallsBackToWindowWhenTtlIsZero() throws Exception {
        when(rateLimiter.tryAcquire(anyString(), anyInt(), anyLong()))
                .thenReturn(FixedWindowRateLimiter.Decision.of(false, 3, 0));

        RateLimitException e = assertThrows(RateLimitException.class,
                () -> interceptor.preHandle(request(), response(), handler("limited")));

        // Retry-After: 0 会让客户端立刻重试，等于火上浇油
        assertEquals(60L, e.getRetryAfterSeconds());
    }

    @Test
    @DisplayName("总开关关闭时完全放行，不碰 Redis")
    void passesWhenDisabled() throws Exception {
        ReflectionTestUtils.setField(interceptor, "enabled", false);

        assertTrue(interceptor.preHandle(request(), response(), handler("limited")));
        verifyNoInteractions(rateLimiter);
    }

    @Test
    @DisplayName("默认不信任 X-Forwarded-For：伪造请求头绕不过限流")
    void ignoresForwardedHeaderByDefault() throws Exception {
        when(rateLimiter.tryAcquire(anyString(), anyInt(), anyLong()))
                .thenReturn(FixedWindowRateLimiter.Decision.allow(1, 60));

        MockHttpServletRequest request = request();
        request.setRemoteAddr("10.0.0.8");
        request.addHeader("X-Forwarded-For", "1.2.3.4");

        interceptor.preHandle(request, response(), handler("limited"));

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(rateLimiter).tryAcquire(key.capture(), anyInt(), anyLong());
        assertEquals("u:rate:generate:10.0.0.8", key.getValue());
    }

    @Test
    @DisplayName("显式开启 trustProxy 后取 XFF 链的第一个地址")
    void trustsForwardedHeaderWhenEnabled() throws Exception {
        ReflectionTestUtils.setField(interceptor, "trustProxy", true);
        when(rateLimiter.tryAcquire(anyString(), anyInt(), anyLong()))
                .thenReturn(FixedWindowRateLimiter.Decision.allow(1, 60));

        MockHttpServletRequest request = request();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "1.2.3.4, 5.6.7.8");

        interceptor.preHandle(request, response(), handler("limited"));

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(rateLimiter).tryAcquire(key.capture(), anyInt(), anyLong());
        assertEquals("u:rate:generate:1.2.3.4", key.getValue());
    }

    private static MockHttpServletRequest request() {
        return new MockHttpServletRequest("POST", "/url/generate");
    }

    private static MockHttpServletResponse response() {
        return new MockHttpServletResponse();
    }

    private static HandlerMethod handler(String methodName) throws NoSuchMethodException {
        return new HandlerMethod(new DemoController(), DemoController.class.getMethod(methodName));
    }

    /**
     * 只为拿注解用的假 Controller。
     */
    static class DemoController {

        @RateLimit(scene = "generate", limit = 2, windowSeconds = 60)
        public void limited() {
        }

        public void open() {
        }
    }
}
