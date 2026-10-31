package com.github.niu.u.interceptor;

import com.github.niu.u.common.CommonCache;
import com.github.niu.u.common.ratelimit.FixedWindowRateLimiter;
import com.github.niu.u.common.ratelimit.RateLimit;
import com.github.niu.u.common.ratelimit.RateLimitException;
import com.github.niu.u.common.util.IpUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 按接口上 {@link RateLimit} 注解执行限流。
 *
 * <p>为什么用拦截器而不是在 Controller 里手写一行调用：
 * 限流是横切关注点，写进业务方法里以后，每加一个要限流的接口就得复制一遍
 * 取 IP、拼 key、判超限的逻辑，而且很容易漏掉其中一个步骤。
 * 注解 + 拦截器把它收敛成一处，加限流只是多一行注解。</p>
 *
 * <p>（也可以在 AOP 里做。选拦截器的原因是这里天然拿得到
 * {@link HttpServletRequest}，不需要额外注入 RequestContextHolder。）</p>
 *
 * @author niuxiangqian
 * @since 2026/10/30
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final Logger logger = LoggerFactory.getLogger(RateLimitInterceptor.class);

    @Resource
    private FixedWindowRateLimiter rateLimiter;

    /**
     * 总开关。放在配置里是因为「出故障时立刻停掉限流」不能等发版。
     */
    @Value("${short_url.rate-limit.enabled:true}")
    private boolean enabled;

    /**
     * 是否信任 X-Forwarded-For / X-Real-IP，默认不信任。
     * 只有在服务部署在可信反向代理后面时才打开，否则按 IP 限流可以被一行请求头绕过。
     */
    @Value("${short_url.rate-limit.trust-proxy:false}")
    private boolean trustProxy;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!enabled || !(handler instanceof HandlerMethod)) {
            return true;
        }
        RateLimit rule = ((HandlerMethod) handler).getMethodAnnotation(RateLimit.class);
        if (rule == null) {
            return true;
        }

        String ip = IpUtil.getClientIp(request, trustProxy);
        String key = CommonCache.RATE_LIMIT + rule.scene() + ":" + ip;

        FixedWindowRateLimiter.Decision decision = rateLimiter.tryAcquire(key, rule.limit(), rule.windowSeconds());
        if (decision.isAllowed()) {
            return true;
        }

        // 只在「本窗口第一次被拦」时打 WARN，后续降 DEBUG。
        // 被刷的时候是每秒几百次拒绝，每个都打 WARN 会把日志写爆，
        // 而且真出问题时反而看不到有用信息——和 Day 2 修的 pending 空转刷屏同一个思路。
        if (decision.getCurrentCount() == rule.limit() + 1L) {
            logger.warn("触发限流 scene={} ip={} {}/{}s，后续拒绝降为 DEBUG",
                    rule.scene(), ip, rule.limit(), rule.windowSeconds());
        } else {
            logger.debug("限流拒绝 scene={} ip={} current={}", rule.scene(), ip, decision.getCurrentCount());
        }

        long retryAfter = decision.getTtlSeconds() > 0 ? decision.getTtlSeconds() : rule.windowSeconds();
        throw new RateLimitException(
                "请求过于频繁，请 " + retryAfter + " 秒后再试", retryAfter, rule.limit());
    }
}
