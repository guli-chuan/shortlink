package com.github.niu.u.controller;

import com.github.niu.u.common.core.R;
import com.github.niu.u.common.ratelimit.RateLimit;
import com.github.niu.u.config.MvcExceptionHandler;
import com.github.niu.u.service.URLService;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * url控制器
 *
 * @author niuxiangqian
 * @version 1.0
 * @since 2020/12/23 8:48 下午
 **/
@RestController
@RequestMapping("url")
public class URLController {
    private final static Logger logger = LoggerFactory.getLogger(URLController.class);


    @Resource
    private URLService urlService;

    /**
     * 生成短链接。写操作，只允许 POST：避免被浏览器预取、爬虫误触发。
     * <p>
     * 限流挂在这里的理由：生成是唯一会往库里写数据的入口，
     * 而短码是 6 位 Base62（约 568 亿个），一个人疯狂生成就能把库灌满，
     * 还能顺带把自增序列推高、让短码变长。跳转接口不加——
     * 短链天生要被大量点击，按 IP 限流会误伤"同一公司出口 IP"的真实用户。
     */
    @RateLimit(scene = "generate", limit = 20, windowSeconds = 60)
    @PostMapping("/generate")
    public R<Object> generate(String url, Long validTime) {
        logger.info("url:{},validTime:{}", url, validTime);
        if (StringUtils.isBlank(url)) {
            return R.failed("url不能为空");
        }

        return R.ok(urlService.generate(url, validTime));
    }

    /**
     * 删除短链接。
     * <p>
     * 用 DELETE 而不是 POST /delete：语义上删除就该是 DELETE；且 DELETE 是幂等的，
     * 重复调用结果一致（第一次 true，之后 false），不会让调用方误以为多点了一次要报错。
     * <p>
     * 注意返回 false 时用的是 failed：短码不存在/已删过属于「这次没删到东西」，
     * 让前端能明确区分「删成功」和「本来就没有」，而不是都返回 ok 让前端去猜。
     */
    @DeleteMapping("/{shortTarget}")
    public R<Object> delete(@PathVariable String shortTarget) {
        logger.info("delete shortTarget:{}", shortTarget);
        boolean deleted = urlService.delete(shortTarget);
        if (deleted) {
            return R.ok(Boolean.TRUE);
        }
        return R.failed("短链接不存在或已删除");
    }
}
