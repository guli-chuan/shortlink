package com.github.niu.u.service;

import com.github.niu.u.model.vo.ShortUrlStatVo;

import java.util.List;

/**
 * 访问统计服务
 *
 * @author niuxiangqian
 * @version 1.0
 * @since 2026/10/17
 **/
public interface StatsService {

    /**
     * 访问次数 Top N
     *
     * @param limit 取前多少条
     * @return 按访问次数倒序的统计列表
     */
    List<ShortUrlStatVo> top(int limit);

    /**
     * 单条短链的访问统计
     *
     * @param shortTarget 短码
     * @return 统计详情
     */
    ShortUrlStatVo detail(String shortTarget);
}
