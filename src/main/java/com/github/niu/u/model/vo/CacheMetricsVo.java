package com.github.niu.u.model.vo;

/**
 * 缓存命中率统计 VO。
 * <p>
 * 比例字段都是小数（0.9834 表示 98.34%），保留 4 位小数；
 * 三个比例互斥，相加等于 1（除非总请求数为 0，此时全为 0）。
 *
 * @author niuxiangqian
 * @version 1.0
 * @since 2026/10/28
 **/
public class CacheMetricsVo {

    /**
     * 总请求数 = hit + nullHit + miss
     */
    private Long total;

    /**
     * 真命中次数：直接从缓存拿到原始链接
     */
    private Long hit;

    /**
     * 空值占位命中次数：短码确实不存在，靠空值缓存挡掉了回源
     */
    private Long nullHit;

    /**
     * 未命中次数：需要回源数据库
     */
    private Long miss;

    /**
     * 回源后发现数据已过期的次数，是 miss 的子集，不参与命中率计算
     */
    private Long expiredMiss;

    /**
     * 被布隆过滤器拦下的次数，是 miss 的子集（缓存没命中才走到过滤器），不参与命中率计算。
     * miss - bloomRejected 才是真正打到数据库的查询次数。
     */
    private Long bloomRejected;

    /**
     * 真命中率 = hit / total
     */
    private Double hitRate;

    /**
     * 空值命中率 = nullHit / total
     */
    private Double nullHitRate;

    /**
     * 未命中率 = miss / total
     */
    private Double missRate;

    public Long getTotal() {
        return total;
    }

    public CacheMetricsVo setTotal(Long total) {
        this.total = total;
        return this;
    }

    public Long getHit() {
        return hit;
    }

    public CacheMetricsVo setHit(Long hit) {
        this.hit = hit;
        return this;
    }

    public Long getNullHit() {
        return nullHit;
    }

    public CacheMetricsVo setNullHit(Long nullHit) {
        this.nullHit = nullHit;
        return this;
    }

    public Long getMiss() {
        return miss;
    }

    public CacheMetricsVo setMiss(Long miss) {
        this.miss = miss;
        return this;
    }

    public Long getExpiredMiss() {
        return expiredMiss;
    }

    public CacheMetricsVo setExpiredMiss(Long expiredMiss) {
        this.expiredMiss = expiredMiss;
        return this;
    }

    public Long getBloomRejected() {
        return bloomRejected;
    }

    public CacheMetricsVo setBloomRejected(Long bloomRejected) {
        this.bloomRejected = bloomRejected;
        return this;
    }

    public Double getHitRate() {
        return hitRate;
    }

    public CacheMetricsVo setHitRate(Double hitRate) {
        this.hitRate = hitRate;
        return this;
    }

    public Double getNullHitRate() {
        return nullHitRate;
    }

    public CacheMetricsVo setNullHitRate(Double nullHitRate) {
        this.nullHitRate = nullHitRate;
        return this;
    }

    public Double getMissRate() {
        return missRate;
    }

    public CacheMetricsVo setMissRate(Double missRate) {
        this.missRate = missRate;
        return this;
    }
}
