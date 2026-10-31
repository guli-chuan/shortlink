package com.github.niu.u.init;

import com.github.niu.u.common.CommonCache;
import com.github.niu.u.config.redis.RedisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * @author niuxiangqian
 * @version 1.0
 * @since 2020/12/24 12:17 下午
 **/
@Component
public class BootInit implements InitializingBean {
    private final Logger logger = LoggerFactory.getLogger(BootInit.class);
    @Resource
    private RedisService redisService;

    @Override
    public void afterPropertiesSet() {
        //先放入一个可用的ak sk
        //后期版本进行丰富此功能
        try {
            redisService.hashPut(CommonCache.DEFAULT_AK_SK, "ak_123", "sk_123");
            logger.info("default access api ak and sk is {ak_123},{sk_123}");
        } catch (Exception e) {
            // 写入的只是一条 demo 级 AK/SK，Redis 抖动时不该把整个应用拖死：
            // 跳转链路和读取链路本身都有降级与 fail-open，这里也照同样口径处理。
            logger.warn("初始化默认 AK/SK 失败，开放接口暂不可用（Redis 恢复后重启即可）：{}", e.toString());
        }
    }
}
