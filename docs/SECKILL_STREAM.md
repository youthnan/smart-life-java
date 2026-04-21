# 秒杀订单 Redis Stream 消费说明

## 当前语义

- 入队：`seckill.lua` 原子完成库存校验、去重校验与 `XADD stream.orders`。
- 消费：`VoucherOrderServiceImpl` 使用消费组 `g1`，支持配置并发消费者数量。
- ACK 时机：仅在 `handleVoucherOrder` 成功执行后 ACK，保证至少一次消费语义。

## 失败处理

- 普通消费异常：进入 pending list 补偿流程。
- pending 补偿异常：按阈值输出告警日志（`hmdp.seckill.stream.pending-error-alert-threshold`）。
- Redis NOAUTH：消费者主动停机并提示配置 `SPRING_REDIS_PASSWORD`。

## 可观测指标（Micrometer）

- `hmdp.stream.orders.processed`：正常消费并 ACK 的订单数。
- `hmdp.stream.orders.consume_error`：主消费循环异常次数。
- `hmdp.stream.orders.pending_retry`：pending 成功补偿次数。
- `hmdp.stream.orders.pending_error`：pending 处理异常次数。

## 关键配置

见 `application.yaml`:

- `hmdp.seckill.stream.consumer-count`
- `hmdp.seckill.stream.read-batch-size`
- `hmdp.seckill.stream.pending-error-alert-threshold`
