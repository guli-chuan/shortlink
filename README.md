# shortlink

Spring Boot + MySQL + Redis 实现的短链接服务：长链接转 6 位短码，支持自定义有效期、访问统计、缓存防护与接口限流。

## 关于来源

本项目基于开源项目 [NiuXiangQian/u](https://github.com/NiuXiangQian/u) 二次开发。原项目使用 **GPL-3.0** 协议（[LICENSE](LICENSE) 已完整保留，源码中的原作者版权声明未删改）。

原项目的形态是「**纯 Redis 存储 + MD5 截取短码**」：短链写在 Redis 里，Redis 重启没开持久化就全量丢失；短码由 `MD5(url)` 截取 9 位，存在哈希碰撞可能。本项目在其基础上做了 7 项改造，下文用 `【改造】` / `【新增】` 逐条标明，便于区分原有功能与改动范围。

## 项目结构

```
com.github.niu.u
├── controller      URLController（生成/删除）、UController（跳转）
│                   ApiURLController（AK/SK 开放接口）、StatsController（统计）
├── service         URLService（短码生成、三态读链路、互斥重建、删除）
│                   StatsService（访问量 Top N / 明细）
├── common
│   ├── bloom/      RedisBloomFilter    —— Redis BitMap 手写布隆过滤器【新增】
│   ├── cache/      CacheMetrics        —— hit / nullHit / miss / bloomRejected 埋点【新增】
│   ├── ratelimit/  FixedWindowRateLimiter + @RateLimit —— Lua 固定窗口限流【新增】
│   ├── stats/      AccessCounter       —— 本地聚合 + 定时批量刷写【新增】
│   ├── util/       Base62Util（短码编码）、IpUtil（取真实客户端 IP）
│   └── core/       R（统一返回体）、BaseException、ResultCode
├── config          WebConfig（拦截器注册）、MvcExceptionHandler、redis/
├── interceptor     ApiAccessInterceptor（AK/SK 鉴权）、RateLimitInterceptor（限流）
└── mapper          ShortUrlMapper（MyBatis-Plus）
```

## 核心实现

### 1. 短码生成：数据库自增 + Base62 【改造】

**原做法**：Redis `INCR` 取序列 + `SETNX` 占位 + 最多 5 次重试。

**问题**：序列和已落库数据之间多了一份需要对齐的状态。Redis 一旦丢数据（没开持久化、主从切换、误清库），序列会回退，直接产出重复短码；重试逻辑只能掩盖问题，不能消除。

**现在**：唯一性交给数据库自增主键，Base62 是一一映射，不需要重试。

```java
// 1) 先插一条 code 为 NULL 的记录，拿自增 id
shortUrlMapper.insert(new ShortUrl().setOriginUrl(srcUrl).setExpireTime(expireTime).setDelFlag(0));
Long id = row.getId();

// 2) id + 偏移量 做 Base62，回填 code
String code = Base62Util.encode(id + ID_OFFSET);
shortUrlMapper.updateById(new ShortUrl().setId(id).setCode(code));
```

`code` 必须允许 `NULL`：MySQL 唯一索引允许多个 `NULL`，所以并发插入不会互相冲突。如果用空字符串占位，第二个并发插入立刻撞唯一索引。

`ID_OFFSET = 62^5` 只是把短码补成固定 6 位（避免出现 `"1"`、`"2"` 这种一眼看穿的码），**它不改变 id 的连续性，不能当防枚举手段**。真要防枚举得再加一层 id → code 的随机映射，本项目没做——这是明确的取舍。

### 2. 读链路 Cache-Aside 三态 【改造】

原实现用 `StringUtils.isNotBlank` 判断缓存，空值缓存（`""`）会被当成"未命中"，防护形同虚设。现在严格区分三种状态：

```java
String cached = redisService.get(cacheKey);
if (cached != null) {
    if (cached.isEmpty()) { /* 空值占位：库里也没有，直接判失效 */ return null; }
    return cached;                                  // 命中
}
// 未命中 → 过布隆 → 回源
```

TTL 有两层约束：

```java
long ttl = 1800 + ThreadLocalRandom.current().nextInt(300);   // 30min + 随机 0~5min，防雪崩
if (expireTime != null) {
    ttl = Math.min(ttl, remainSeconds);                       // 压到数据剩余有效期以内
}
```

第二层容易被忽略：只加随机抖动的话，会出现「**缓存还在、链接已经过期**」——用户点进去才发现失效，体验比直接 404 更差。永久链接（`expireTime == null`）同样带 TTL，否则冷数据会永久占住内存把热点挤出去。

### 3. 布隆过滤器防穿透 【新增】

用 Redis BitMap 手写，`m = 10n`（默认 1000 万 bit ≈ 1.25MB）、`k = 3`，理论误判率约 0.9%。

k 个独立哈希通常要算 k 次，这里用 **Kirsch-Mitzenmacher** 构造：只算 2 次 FNV-1a，`h_i = h1 + i * h2`，误判率不变但哈希开销减半。

```java
long h1 = fnv1a64(code, FNV_OFFSET_BASIS);
long h2 = fnv1a64(code, FNV_OFFSET_BASIS_2);
for (int i = 0; i < hashCount; i++) {
    offsets[i] = Math.floorMod(h1 + i * h2, bitSize);   // 必须 floorMod：% 出负数下标会让 SETBIT 直接抛异常
}
```

两个工程细节：

- **k 次 GETBIT 用一个 Lua 脚本压成 1 次往返**。跳转是热路径，多 2 次 RTT 不可接受。
- **fail-open**：Redis 异常或位数组尚未预热时返回 `true`（放行）。过滤器是优化手段，不是正确性依赖——真出故障时让请求落到数据库，比让全站返回 404 好得多。

位数组还会被 `FLUSHDB`、换库、误删清空，此时全 0 → 所有短码都被判"不存在"→ 全站 404。所以每 60 秒检查一次 key 是否存在，丢了就重建（`@Scheduled` 加了 `initialDelay`，否则会和启动时的预热各跑一遍）。

### 4. 缓存重建互斥锁 【新增】

**这条是压测压出来的，不是提前设计好的。** 改造前用 20 个线程同时请求一个不存在的短码，产生了 **19 次**回源；串行请求 5 次只要 1 次。原因很简单：20 个线程在同一瞬间都发现"缓存没有"，一起去查库，等第一个把空值写进去时，其余 19 个早就查完了。

空值缓存解决的是「**持续穿透**」（同一个 code 反复打），解决不了「**瞬时并发击穿**」（并发同时打冷 key）。

```java
if (redisService.setIfAbsent(lockKey, token, 3, TimeUnit.SECONDS)) {
    try { return loadFromDbAndFill(code, cacheKey); }
    finally { releaseLock(lockKey, token); }       // Lua: GET == token 才 DEL
}
// 没抢到：等 3 × 100ms 重读缓存
// 等超时：降级自己查库 —— 宁可多一次回源，也不能把有效链接判成 404
```

三个细节：`SET NX EX` 保证"抢锁 + 设超时"原子；锁值存 UUID，释放时用 Lua 比对，避免锁超时后删掉别人的锁；等待有上限，降级优先于正确性事故。

### 5. 访问统计异步化 【改造】

**原做法**：每次跳转同步 `ZINCRBY` 一次。读多写少的场景里，每次读都带一次写，和读请求抢 Redis 资源。

**现在**：本地 `ConcurrentHashMap<String, LongAdder>` 累加，每 5 秒用 pipeline 批量刷写。

```java
// 跳转链路：1 次 GET + 1 次内存自增（原来要 2 次往返）
accessCounter.increment(code);
```

两个容易踩的点：

- 刷写失败必须把 delta **还回**本地（`sumThenReset` 已经清零，不还就真丢了）。
- `/stats/detail` 要叠加未刷写部分，否则用户刚访问完查到的还是旧数字。

代价是宕机最多丢 5 秒计数。统计数据不是钱，用这个损失换跳转 QPS 提升是划算的，前提是明确接受最多丢 5 秒。

### 6. 删除接口与缓存一致性 【新增】

删除走逻辑删除（保留记录才能回答"这个码曾经指向哪里"），缓存清理注册到**事务提交之后**：

```java
TransactionSynchronizationManager.registerSynchronization(
    new TransactionSynchronizationAdapter() {
        @Override public void afterCommit() { evictCache(key, code); }
    });
```

三种写法的问题对比：

| 写法 | 问题 |
|---|---|
| 事务内先删缓存再改库 | 回滚就白删；并发读可能在删缓存与提交之间读到旧值并回填 |
| 事务内改库再删缓存 | 删完事务仍未提交，此时读请求读到旧值并回填，缓存里是脏数据 |
| **提交后再删**（本实现） | 删的时候新数据一定已可见 |

仍有理论窗口（读请求在"提交后、删缓存前"拿到旧值并回填），彻底消掉要延迟双删或订阅 binlog。短链删除本身极少发生，为它引一套延迟任务不划算——这是取舍，不是遗漏。缓存删不掉时只记 error 日志、不改变删除结果，兜底靠 TTL 自然过期。

### 7. Lua 固定窗口限流 【新增】

```lua
local current = redis.call('INCR', KEYS[1])
local ttl = redis.call('TTL', KEYS[1])
if current == 1 or ttl < 0 then
    redis.call('EXPIRE', KEYS[1], ARGV[2])
    ttl = tonumber(ARGV[2])
end
if current > tonumber(ARGV[1]) then return {0, current, ttl} end
return {1, current, ttl}
```

- 用 Lua 而不是两条命令：`INCR` 和 `EXPIRE` 之间进程崩溃，key 就永久没有过期时间，这个 IP 被**静默永久封禁**，只有查 TTL 才能发现。
- `ttl < 0` 这个条件是额外加的：`current == 1` 只覆盖"刚创建"，如果 key 因人为 `SET` / 数据迁移而存在却没有 TTL，同样会一直累加。
- 返回三元组把 TTL 一起带回来，省一次 `TTL` 查询，也让 `Retry-After` 是准确值。
- 超限返回 **HTTP 429 + `Retry-After` + `X-RateLimit-*`**，不用 200 + 业务错误码——否则调用方会当成业务失败去重试，越重试越超限。
- `X-Forwarded-For` 默认不信任（`short_url.rate-limit.trust-proxy=false`）：这个头客户端能随便填，盲信等于让按 IP 限流被一行请求头绕过。
- Redis 异常时 **fail-open** 放行：限流是保护措施，不是业务正确性。

## 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/url/generate` | 生成短链，限流 20 次 / 60 秒 / IP |
| DELETE | `/url/{code}` | 逻辑删除 + 清理缓存，幂等 |
| GET | `/u/{code}` | 跳转：302 / 404 |
| POST | `/api/v1/generate` | 开放接口，需 AK/SK |
| GET | `/api/v1/restore/{target}` | 解析短码 |
| GET | `/api/v1/restoreFullUrl/{url}` | 解析完整短链 |
| GET | `/stats/cache` | 缓存命中率（hit / nullHit / miss / bloomRejected） |
| GET | `/stats/top` | 访问量 Top N |
| GET | `/stats/detail/{code}` | 单个短码访问量（含未刷写部分） |

```bash
# 生成（注意：这个接口限流 20 次/60 秒/IP）
curl -X POST http://127.0.0.1:8080/url/generate -d "url=https://www.baidu.com&validTime=3600"

# 跳转
curl -i http://127.0.0.1:8080/u/100004

# 缓存命中率
curl http://127.0.0.1:8080/stats/cache
```

## 快速开始

需要 JDK 8、Maven、MySQL 8、Redis 5.0+。

**1) 建库建表**

```bash
mysql -uroot -p < doc/sql/shortlink.sql
```

**2) 配置连接**

连接信息全部走环境变量，仓库里不存明文密码（`application-dev.yml` 里只有本地兜底默认值）：

| 变量 | 说明 |
|---|---|
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | Redis 连接 |
| `DB_HOST` / `DB_PORT` / `DB_USERNAME` / `DB_PASSWORD` | MySQL 连接 |
| `SHORT_URL_SERVER` | 生成短链时拼在前面的域名，默认 `http://127.0.0.1:8080` |

**3) 运行**

IDEA：打开项目根目录等待 Maven 导入完成，Project SDK 选 JDK 8，主类 `com.github.niu.u.UApplication`，环境变量按上表填好即可直接运行。

命令行方式：

```bash
mvn clean package -DskipTests

# Git Bash / Linux
REDIS_HOST=127.0.0.1 REDIS_PASSWORD=yourpass DB_PASSWORD=yourpass \
  java -jar target/shortlink.jar
```

CMD 用 `set REDIS_PASSWORD=xxx` 逐条设置。启动后访问 `http://127.0.0.1:8080/` 是原有 web 界面。

## 实测数据

环境：JDK 8、本机 MySQL 8、单机 Redis 7，压测客户端为 Python `http.client` 20 线程。

| 场景 | 结果 |
|---|---|
| 热点短码 10000 次跳转 | QPS **6958**（统计异步化之前 4813，+44%），100% 302 |
| 缓存命中率 | **99.98%**（10004 次请求仅 1 次回源） |
| 20 线程并发打「缓存刚失效」的短码 | 数据库 `Com_select` 增量 **2**（改造前 19） |
| 编造的短码 10000 次 | 全部 404，`bloomRejected=10000`，**SQL 执行 0 条** |
| 限流 20 次 / 60 秒 | 第 21 次起 429，`Retry-After` 准确；50 并发同时起跑正好 20 放行 / 30 拒绝，计数不丢 |
| 缓存 TTL | 实测 2085 秒，落在 1800~2099 区间 |
| 单元测试 | **55 个**（Mockito），不依赖 Redis / MySQL，clone 后 `mvn test` 直接可跑 |

客户端是 Python，受 GIL 限制，这个 QPS 是**下限**，服务端真实能力更高。JMeter 阶梯压测（含 P99）待补。

## 已知限制

这些是明确的取舍，不是遗漏——每一条都写清了原因和代价。

1. **固定窗口有 2 倍突发**。实测：窗口剩最后 3 秒时用满 20 次配额，跨过边界立刻再打满 20 次，**不到 5 秒通过了 21 次**。彻底解决要用滑动窗口（ZSET 存时间戳）或令牌桶（按时间补令牌），代价是内存和复杂度。
2. **统计最多丢 5 秒计数**（进程被 `kill -9` 时来不及 flush）。
3. **布隆过滤器不能删元素**。短链删除后位数组里仍有记录，代价是多查一次库，可以接受。
4. **误判率 0.9%** 意味着少部分不存在的码仍会穿透到数据库一次，靠空值缓存兜底。
5. **短码可枚举**。连续 id 对应连续短码，`ID_OFFSET` 不解决这个问题。
6. **Redis 挂掉时布隆 fail-open**，防穿透退化为只有空值缓存——有兜底，但没有第二道闸。
7. `visit_stat` 表是预留的日维度统计表，当前统计走 Redis ZSet，这张表还没接入。

## 踩过的坑

写下来是为了不再踩第二次。

- **`redis-cli info stats` 的 `keyspace_hits` 不是你的接口命中率**。它把空值占位算成成功、混了所有客户端和所有 db、重启清零、没有时间窗口。实测同一时刻应用内 `hit=3 / miss=2`，Redis 侧 `hits=7 / misses=3`。所以命中率必须自己埋点。
- **`@Scheduled` 默认启动后就执行第一次**。自愈检查和 `ApplicationReadyEvent` 的预热撞车，日志里出现两条"预热完成"，加 `initialDelay` 修掉。
- **`code` 必须允许 NULL**。用空字符串占位，第二个并发插入就撞唯一索引。
- **命中日志不能打 INFO**。2 万次请求刷了 2 万行日志，日志 I/O 直接把 QPS 测量值拉低。高频路径按调用量决定日志级别，热度看 `/stats/cache` 就够了。

## License

[GPL-3.0](LICENSE)，继承自原项目。
