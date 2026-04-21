# 简历与仓库对齐说明

## 策略 2a（秒杀与消息中间件）

- **秒杀异步下单**：实现为 **Redis Stream**（`VoucherOrderServiceImpl` + `seckill.lua`），**未使用 Kafka 承载订单消息**。简历中若写「Kafka 剥离秒杀非核心逻辑」，请改为 **Redis Stream** 或单独说明「订单异步化」。
- **Kafka 在本项目中的用途**：仅用于 **多实例下店铺缓存失效广播**（可选，`hmdp.kafka.cache-invalidation.enabled=true`）。详见 [KAFKA_SHOP_CACHE.md](./KAFKA_SHOP_CACHE.md)。

## LangChain4j 与百炼（通义 DashScope）

- 使用 **DashScope OpenAI 兼容模式** + `langchain4j-open-ai` 的 `OpenAiChatModel`。
- 配置环境变量 **`LLM_API_KEY`**、**`LLM_BASE_URL`**、**`LLM_MODEL`**（与 `seckill-agent/.env` 一致；亦可回退 **`DASHSCOPE_API_KEY`**）。勿将密钥提交到仓库。
- HTTP 演示接口：`POST /agent/chat`（见 `AgentController`）；接口已加 **滑动窗口限流**（`@RateLimit`，与秒杀接口同类组件）。
- 静态页入口（Nginx 根目录 `html/hmdp`）：**`/agent.html`**；底栏 **「助手」**、首页顶栏图标、**`me.html` / `info-edit.html`** 内链入。

## 压测与数字表述

- 吞吐对比脚本与记录方式见 [BENCHMARK.md](./BENCHMARK.md)。简历中的 **「QPS 提升 50%」** 须来自可复现压测，否则建议删除或改为定性描述。
