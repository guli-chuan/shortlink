-- 短链接平台数据库
-- 执行：mysql -uroot -p < doc/sql/shortlink.sql

CREATE DATABASE IF NOT EXISTS shortlink DEFAULT CHARSET utf8mb4;
USE shortlink;

CREATE TABLE IF NOT EXISTS `short_url`
(
    `id`          bigint UNSIGNED NOT NULL AUTO_INCREMENT,
    `code`        varchar(10)     DEFAULT NULL COMMENT '短码：插入时先为 NULL 拿自增 id，再回填',
    `origin_url`  varchar(2048)   NOT NULL COMMENT '原始链接',
    `gid`         varchar(32)     DEFAULT NULL COMMENT '分组',
    `expire_time` datetime        DEFAULT NULL COMMENT '过期时间，NULL 表示永久有效',
    `del_flag`    tinyint         NOT NULL DEFAULT 0,
    `create_time` datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code` (`code`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='短链接表';

CREATE TABLE IF NOT EXISTS `visit_stat`
(
    `id`          bigint UNSIGNED NOT NULL AUTO_INCREMENT,
    `code`        varchar(10)     NOT NULL,
    `visit_date`  date            NOT NULL,
    `pv`          int             NOT NULL DEFAULT 0,
    `uv`          int             NOT NULL DEFAULT 0,
    `update_time` datetime        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code_date` (`code`, `visit_date`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='访问统计表';
