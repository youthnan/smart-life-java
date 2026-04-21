package com.hmdp.cache;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "hmdp.kafka.cache-invalidation", name = "enabled", havingValue = "false", matchIfMissing = true)
public class NoOpShopCacheInvalidationPublisher implements ShopCacheInvalidationPublisher {

    @Override
    public void publishShopInvalidated(long shopId) {
        // Kafka 未启用：仅依赖本机删 Redis + 清 Caffeine
    }
}
