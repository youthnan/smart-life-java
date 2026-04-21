# 店铺缓存：Kafka 跨节点失效 + 延迟双删

## 何时需要 Kafka

多实例部署时，某实例执行 `ShopServiceImpl.update` 会删除 **本机 Caffeine** 与 **Redis** 中该店铺 key，但 **其他实例的 Caffeine 仍可能残留旧数据**。开启 Kafka 广播后，所有实例消费同一消息并清理本地缓存与 Redis，并做一次 **延迟二次删 Redis**（减轻并发读写下的短暂不一致）。

> 建议：**单机可关闭，双机及以上默认开启** `hmdp.kafka.cache-invalidation.enabled=true`。否则跨节点会出现 L1 本地缓存残留。

## 一键开启步骤

1. 启动 Kafka（可用仓库根目录 `docker-compose.yml` 中的 `zookeeper` + `kafka`）。
2. 设置环境变量（示例）：
   - `SPRING_KAFKA_BOOTSTRAP_SERVERS=127.0.0.1:9092`
   - `HMDP_KAFKA_CACHE_INVALIDATION_ENABLED=true`  
     或在 `application.yaml` 中：`hmdp.kafka.cache-invalidation.enabled: true`
3. 确保 `spring.kafka.bootstrap-servers` 与集群一致（见 `application.yaml`）。

## 行为说明

- **生产消息**：`ShopServiceImpl.update` → `ShopCacheInvalidationPublisher.publishShopInvalidated`；启用 Kafka 时为 `KafkaShopCacheInvalidationPublisher`。
- **消费处理**：[`ShopCacheInvalidationListener`](../src/main/java/com/hmdp/cache/ShopCacheInvalidationListener.java)  
  - 删除 Caffeine 中该 `shopId`  
  - 删除 Redis `cache:shop:{id}`  
  - 调度 **`double-delete-delay-ms`（默认 300ms）** 后再次删除同一 Redis key（延迟双删）

## 一致性说明

- 延迟双删只能降低并发读写下的不一致窗口，不能做到严格线性一致；默认目标是「秒级内最终一致」。
- 若业务要求更严格一致性，可叠加「版本号比较」或「读请求短时绕过 L1」策略。

## 默认关闭

未配 Kafka 或未开启开关时，使用 `NoOpShopCacheInvalidationPublisher`，**仅本机**在 `update` 内删除 Redis + Caffeine，无跨节点广播。
