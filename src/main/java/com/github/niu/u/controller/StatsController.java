package com.github.niu.u.controller;

import com.github.niu.u.common.cache.CacheMetrics;
import com.github.niu.u.common.core.R;
import com.github.niu.u.model.vo.CacheMetricsVo;
import com.github.niu.u.model.vo.ShortUrlStatVo;
import com.github.niu.u.service.StatsService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.List;

/**
 * 访问统计接口（Web 控制台使用，不走 AK/SK 鉴权）
 *
 * @author niuxiangqian
 * @version 1.0
 * @since 2026/10/17
 **/
@RestController
@RequestMapping("/stats")
public class StatsController {

    @Resource
    private StatsService statsService;

    @Resource
    private CacheMetrics cacheMetrics;

    /**
     * 访问次数 Top N
     *
     * @param limit 取前多少条，默认 10，最大 100
     */
    @GetMapping("/top")
    public R<List<ShortUrlStatVo>> top(@RequestParam(value = "limit", defaultValue = "10") int limit) {
        return R.ok(statsService.top(limit));
    }

    /**
     * 单条短链的访问统计
     *
     * @param target 短码
     */
    @GetMapping("/detail/{target}")
    public R<ShortUrlStatVo> detail(@PathVariable String target) {
        return R.ok(statsService.detail(target));
    }

    /**
     * 缓存命中率（JVM 内累计值，进程重启清零；多实例各自独立，不汇总）
     * <p>
     * 注意和上面两个接口的区别：上面统计的是「短链被访问了多少次」（业务量），
     * 这个统计的是「访问时有多少比例没打到数据库」（缓存效果），两者互不相干。
     */
    @GetMapping("/cache")
    public R<CacheMetricsVo> cache() {
        return R.ok(cacheMetrics.snapshot());
    }
}
