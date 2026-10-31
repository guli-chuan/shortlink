package com.github.niu.u.service.impl;

import com.github.niu.u.common.CommonCache;
import com.github.niu.u.common.exception.BaseException;
import com.github.niu.u.common.stats.AccessCounter;
import com.github.niu.u.config.redis.RedisService;
import com.github.niu.u.model.vo.ShortUrlStatVo;
import com.github.niu.u.service.StatsService;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 访问统计实现：数据来自 zset {@link CommonCache#ACCESS_COUNT}（member=短码，score=访问次数）。
 * <p>
 * 写入方是 {@link AccessCounter}——它在本地攒 5 秒的计数后 pipeline 批量刷进这个 zset，
 * 而不是每次跳转同步写一次。所以这里的数字有最多 5 秒的延迟，
 * 单个短码的实时数字由 {@link #detail(String)} 补上本地未刷写的部分。
 *
 * @author niuxiangqian
 * @version 1.0
 * @since 2026/10/17
 **/
@Service
public class StatsServiceImpl implements StatsService {

    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 100;

    @Value("${short_url.server:http://127.0.0.1:8080}")
    private String shortUrlServer;

    @Resource
    private RedisService redisService;

    @Resource
    private AccessCounter accessCounter;

    /**
     * Top N 排行榜。
     * <p>
     * 注意这里只能读到已刷写的部分：本地攒着的那几秒还没进 zset，
     * 所以刚被访问的短码在排行榜上可能"慢半拍"。
     * 这是异步刷写的固有延迟，换来的是跳转链路少一次 Redis 往返——
     * 排行榜晚 5 秒更新完全不影响业务，而跳转慢 5 毫秒是实打实的。
     * 单个短码的实时数字走 {@link #detail(String)}，那里会补上本地未刷写的部分。
     */
    @Override
    public List<ShortUrlStatVo> top(int limit) {
        int size = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        Set<ZSetOperations.TypedTuple<String>> tuples =
                redisService.zReverseRangeWithScores(CommonCache.ACCESS_COUNT, 0, size - 1L);

        List<ShortUrlStatVo> result = new ArrayList<>();
        if (tuples == null || tuples.isEmpty()) {
            return result;
        }
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            String target = tuple.getValue();
            if (StringUtils.isBlank(target)) {
                continue;
            }
            result.add(buildStat(target, tuple.getScore()));
        }
        return result;
    }

    @Override
    public ShortUrlStatVo detail(String shortTarget) {
        if (StringUtils.isBlank(shortTarget)) {
            throw new BaseException("shortTarget不能为空");
        }
        Double score = redisService.zScore(CommonCache.ACCESS_COUNT, shortTarget);
        long persisted = score == null ? 0L : score.longValue();
        // 加上还在本地没刷写的那部分：用户刚访问完就来查，数字不该是 0
        return buildStat(shortTarget, (double) (persisted + accessCounter.unsaved(shortTarget)));
    }

    /**
     * 组装统计信息：访问次数来自 zset，原始链接再回查一次短链缓存。
     */
    private ShortUrlStatVo buildStat(String target, Double score) {
        ShortUrlStatVo vo = new ShortUrlStatVo();
        vo.setShortTarget(target)
                .setCount(score == null ? 0L : score.longValue())
                .setShortUrl(shortUrlServer + "/u/" + target)
                .setOrgUrl(redisService.get(CommonCache.SHORT_URL + target));
        return vo;
    }
}
