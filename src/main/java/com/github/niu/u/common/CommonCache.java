package com.github.niu.u.common;

/**
 * 缓存
 *
 * @author niuxiangqian
 * @version 1.0
 * @since 2020/12/23 9:05 下午
 **/
public interface CommonCache {

    /**
     * 短网址缓存前缀
     */
    String SHORT_URL = "u:short_url:";

    /**
     * Default
     * api访问ak_sk前缀key
     */
    String DEFAULT_AK_SK = "u:ak_sk:";

    /**
     * 访问统计
     */
    String ACCESS_COUNT = "u:access_count:zset";

    /**
     * 布隆过滤器位数组（Redis BitMap，一个 key 存 1000 万个 bit）
     */
    String SHORT_URL_BLOOM = "u:bloom:short_url";

    /**
     * 缓存重建互斥锁前缀，完整 key = u:short_url:lock:{code}
     */
    String SHORT_URL_LOCK = "u:short_url:lock:";

    /**
     * 限流计数前缀，完整 key = u:rate:{scene}:{ip}
     */
    String RATE_LIMIT = "u:rate:";

}
