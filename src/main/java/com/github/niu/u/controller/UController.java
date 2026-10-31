package com.github.niu.u.controller;

import com.github.niu.u.common.stats.AccessCounter;
import com.github.niu.u.service.URLService;
import org.apache.commons.lang.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 短链接控制器
 *
 * @author niuxiangqian
 * @version 1.0
 * @since 2020/12/23 11:38 下午
 **/
@RestController
public class UController {

    @Resource
    private URLService urlService;

    @Resource
    private AccessCounter accessCounter;

    @GetMapping("/u/{shortUrl}")
    public void u(HttpServletResponse response, @PathVariable String shortUrl) throws IOException {
        if (StringUtils.isBlank(shortUrl)) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }

        // 缓存优先，未命中先过布隆过滤器，再回源数据库并加锁重建
        // （随机 TTL / 空值缓存 / 命中率统计在 Day 2，布隆与互斥重建在 Day 3）
        String url = urlService.resolve(shortUrl);
        if (StringUtils.isBlank(url)) {
            // 短码不存在或已失效：必须给 404，不能用 200 + 一段文本蒙混过去，
            // 否则监控分不清「正常跳转」和「死链」，爬虫也会把死链当有效页面收录
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("url is lose efficacy");
            return;
        }

        // 访问计数。Day 3 改造：这里只是内存 +1，不再同步写 Redis。
        // 位置也挪到了「确认是有效短码」之后——以前短码不存在时也会写一次脏统计，
        // 死链被爬虫刷几十万次，排行榜上就会出现一堆指向空处的短码。
        // 真正的刷盘由 AccessCounter 每 5 秒批量执行一次（pipeline，一次往返）。
        accessCounter.increment(shortUrl);

        if (!(url.startsWith("http://") || url.startsWith("https://"))) {
            url = "http://" + url;
        }
        response.sendRedirect(url);
    }

}
