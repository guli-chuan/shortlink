package com.github.niu.u.config;

import com.github.niu.u.interceptor.ApiAccessInterceptor;
import com.github.niu.u.interceptor.RateLimitInterceptor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

/**
 * 
 * @author niuxiangqian
 * @version 1.0
 * @since 2020/12/24 3:42 下午
 **/
@Component
public class WebConfig implements WebMvcConfigurer {
    @Resource
    private ApiAccessInterceptor apiAccessInterceptor;

    @Resource
    private RateLimitInterceptor rateLimitInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        //添加api权限拦截器拦截所有api
        registry.addInterceptor(apiAccessInterceptor).addPathPatterns("/api/v1/**");

        // 限流拦截器拦全部请求：它自己判断方法上有没有 @RateLimit，
        // 没注解的直接放行（多一层判断，换来"新增接口挂注解即生效"）。
        // 顺序放在鉴权之后：先确认"你能不能调"，再判断"你调得太快没"。
        registry.addInterceptor(rateLimitInterceptor).addPathPatterns("/**");
    }
}
