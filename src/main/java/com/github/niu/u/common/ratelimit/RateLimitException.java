package com.github.niu.u.common.ratelimit;

/**
 * 触发限流时抛出。由全局异常处理器转成 HTTP 429。
 *
 * <p>刻意不继承 {@link com.github.niu.u.common.exception.BaseException}：
 * 两者在语义上不是一回事——BaseException 是"这次请求本身有问题"，
 * 限流是"你现在没错，只是来太快了"。后者要带 Retry-After 让调用方知道等多久，
 * 而且 HTTP 状态码必须是 429，不能被通用的 failed(-1) 描述。</p>
 *
 * @author niuxiangqian
 * @since 2026/10/30
 */
public class RateLimitException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * 建议调用方等待多少秒再重试
     */
    private final long retryAfterSeconds;

    /**
     * 当前窗口的配额上限，回给客户端看
     */
    private final int limit;

    public RateLimitException(String message, long retryAfterSeconds, int limit) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
        this.limit = limit;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    public int getLimit() {
        return limit;
    }
}
