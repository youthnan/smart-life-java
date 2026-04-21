# 店铺读路径压测（可复核）

## 目的

对比「仅依赖 Redis」与「Caffeine(L1) + Redis(L2)」或调整 Kafka 失效开关前后的差异时，应用**相同 URL、并发、时长**，在简历中只写**可复现**的结论。

## 前置

- 服务已启动，MySQL/Redis 可连；店铺数据中存在用于压测的 `shopId`（如 `1`）。
- 安装 [wrk](https://github.com/wg/wrk) 或使用脚本内的 `curl` 循环（吞吐较低，仅作冒烟）。

## 使用 wrk

```bash
export BASE_URL=http://127.0.0.1:8088
export SHOP_ID=1
./scripts/benchmark-shop-read.sh
```

脚本会请求 `GET /shop/{id}`（该路径在登录拦截器中为白名单）。

## 记录结果

建议在表中记录：JDK 版本、实例数、`hmdp.kafka.cache-invalidation.enabled`、Caffeine 是否命中预热、`wrk` 的 Threads/Connections/Duration、**Requests/sec** 与 **Latency**。未做对比前，**不要将「QPS 提升 50%」写进简历**。
