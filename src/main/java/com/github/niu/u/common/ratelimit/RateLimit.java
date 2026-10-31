package com.github.niu.u.common.ratelimit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 给接口挂限流规则。
 *
 * <p>阈值写在注解上而不是配置文件里，是刻意的取舍：</p>
 * <ul>
 *   <li>好处：看接口就知道它被限成什么样，code review 时不用跳文件对照</li>
 *   <li>代价：改阈值要发版。生产上更常见的做法是放配置中心，
 *       支持不改代码动态调整、出问题能一键关掉</li>
 * </ul>
 * <p>这个项目取了前者，但把"总开关"留给配置（{@code short_url.rate-limit.enabled}），
 * 因为出故障时最需要的是「立刻停掉限流」，而这件事不能等发版。</p>
 *
 * @author niuxiangqian
 * @since 2026/10/30
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimit {

    /**
     * 场景名，参与拼装 Redis key：{@code u:rate:{scene}:{来源}}。
     * 不同接口必须用不同场景名，否则会共用一个计数器、互相挤占配额。
     */
    String scene();

    /**
     * 一个窗口内允许的次数。
     */
    int limit() default 20;

    /**
     * 窗口长度（秒）。
     */
    long windowSeconds() default 60;
}
