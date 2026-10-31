package com.github.niu.u.model.vo;

/**
 * 短链访问统计 VO
 *
 * @author niuxiangqian
 * @version 1.0
 * @since 2026/10/17
 **/
public class ShortUrlStatVo {

    /**
     * 短码（短链接标记）
     */
    private String shortTarget;

    /**
     * 完整短链接
     */
    private String shortUrl;

    /**
     * 原始长链接
     */
    private String orgUrl;

    /**
     * 访问次数
     */
    private Long count;

    public String getShortTarget() {
        return shortTarget;
    }

    public ShortUrlStatVo setShortTarget(String shortTarget) {
        this.shortTarget = shortTarget;
        return this;
    }

    public String getShortUrl() {
        return shortUrl;
    }

    public ShortUrlStatVo setShortUrl(String shortUrl) {
        this.shortUrl = shortUrl;
        return this;
    }

    public String getOrgUrl() {
        return orgUrl;
    }

    public ShortUrlStatVo setOrgUrl(String orgUrl) {
        this.orgUrl = orgUrl;
        return this;
    }

    public Long getCount() {
        return count;
    }

    public ShortUrlStatVo setCount(Long count) {
        this.count = count;
        return this;
    }
}
