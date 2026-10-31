package com.github.niu.u.service;

import com.github.niu.u.common.exception.BaseException;
import com.github.niu.u.model.vo.ShortUrlVo;

/**
 * url的服务
 *
 * @author niuxiangqian
 * @version 1.0
 * @since 2020/12/23 8:52 下午
 **/
public interface URLService {

    /**
     * 生成短网址
     *
     * @param srcUrl :  原来的url
     * @param valid  : 有效时间 -1为永久有效
     * @return java.lang.String
     * @author nxq
     */
    ShortUrlVo generate(String srcUrl, Long valid);

    /**
     * 解析短码对应的原始链接，查不到或已过期返回 null（不抛异常，供跳转链路使用）。
     *
     * @param shortTarget 短码
     * @return 原始链接，或 null
     */
    String resolve(String shortTarget);

    /**
     * 根据短网址target标记解析原网址
     *
     * @param shortTarget: 短网址的标记
     * @return com.github.niu.u.model.vo.ShortUrlVo
     * @author nxq
     */
    ShortUrlVo restoreByTarget(String shortTarget);

    /**
     * 根据短网址target标记解析原网址
     *
     * @param shortUrl: 完整的短网址
     * @return com.github.niu.u.model.vo.ShortUrlVo
     * @author nxq
     */
    ShortUrlVo restoreByShortUrl(String shortUrl);

    /**
     * 删除短链接（逻辑删除 del_flag=1），并在**事务提交后**清理缓存。
     * <p>
     * 为什么是提交后删缓存而不是事务内删：事务内删掉缓存后，若事务最终回滚，
     * 缓存已经没了，数据库里数据还在，属于「删了个寂寞」；更糟的是并发读可能在
     * 「删缓存之后、事务提交之前」读到旧数据并回填，缓存里又变成旧值。
     * 所以顺序必须是「先落库，提交成功，再删缓存」。
     *
     * @param shortTarget 短码
     * @return true 删除成功；false 短码不存在或已被删除
     */
    boolean delete(String shortTarget);
}
