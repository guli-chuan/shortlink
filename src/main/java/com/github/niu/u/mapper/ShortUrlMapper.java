package com.github.niu.u.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.github.niu.u.model.entity.ShortUrl;
import org.apache.ibatis.annotations.Mapper;

/**
 * 短链接 Mapper。单表 CRUD 用 BaseMapper 就够，不需要手写 XML。
 *
 * @author niuxiangqian
 * @since 2026/10/27
 **/
@Mapper
public interface ShortUrlMapper extends BaseMapper<ShortUrl> {
}
