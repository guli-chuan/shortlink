package com.github.niu.u.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 短链接实体，对应表 short_url。
 * <p>
 * 生成流程：先 insert（code 留空）拿到自增主键 id，再用 Base62(id + ID_OFFSET) 算出 code 回填。
 * 自增主键天然唯一，因此编码结果不会冲突，不需要分布式锁和重试。
 *
 * @author niuxiangqian
 * @since 2026/10/27
 **/
@TableName("short_url")
public class ShortUrl {

    /**
     * 自增主键。它同时是短码的原料，所以必须由数据库生成（IdType.AUTO）。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 短码。插入时为 null（唯一索引允许多个 NULL），回填后才有值。
     */
    private String code;

    /**
     * 原始长链接
     */
    private String originUrl;

    /**
     * 分组，预留字段
     */
    private String gid;

    /**
     * 过期时间，null 表示永久有效。
     * 有效期必须以数据库为准：缓存被淘汰后能从库里恢复出"这条链接本来什么时候到期"。
     */
    private LocalDateTime expireTime;

    /**
     * 逻辑删除标记：0 正常，1 已删除
     */
    private Integer delFlag;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public ShortUrl setId(Long id) {
        this.id = id;
        return this;
    }

    public String getCode() {
        return code;
    }

    public ShortUrl setCode(String code) {
        this.code = code;
        return this;
    }

    public String getOriginUrl() {
        return originUrl;
    }

    public ShortUrl setOriginUrl(String originUrl) {
        this.originUrl = originUrl;
        return this;
    }

    public String getGid() {
        return gid;
    }

    public ShortUrl setGid(String gid) {
        this.gid = gid;
        return this;
    }

    public LocalDateTime getExpireTime() {
        return expireTime;
    }

    public ShortUrl setExpireTime(LocalDateTime expireTime) {
        this.expireTime = expireTime;
        return this;
    }

    public Integer getDelFlag() {
        return delFlag;
    }

    public ShortUrl setDelFlag(Integer delFlag) {
        this.delFlag = delFlag;
        return this;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public ShortUrl setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
        return this;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public ShortUrl setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
        return this;
    }
}
