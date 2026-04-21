package com.hmdp.cache;

import cn.hutool.json.JSONUtil;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import java.util.Date;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "hmdp.kafka.cache-invalidation", name = "enabled", havingValue = "true")
public class ShopCacheInvalidationListener {

    private final ShopLocalCache shopLocalCache;
    private final StringRedisTemplate stringRedisTemplate;
    private final ThreadPoolTaskScheduler cacheInvalidationScheduler;
    private final Counter consumeCounter;
    private final Counter payloadErrorCounter;

    @Value("${hmdp.kafka.cache-invalidation.double-delete-delay-ms:300}")
    private long doubleDeleteDelayMs;

    @Autowired
    public ShopCacheInvalidationListener(
            ShopLocalCache shopLocalCache,
            StringRedisTemplate stringRedisTemplate,
            @Qualifier("cacheInvalidationScheduler") ThreadPoolTaskScheduler cacheInvalidationScheduler,
            ObjectProvider<MeterRegistry> meterRegistryProvider
    ) {
        this.shopLocalCache = shopLocalCache;
        this.stringRedisTemplate = stringRedisTemplate;
        this.cacheInvalidationScheduler = cacheInvalidationScheduler;
        MeterRegistry meterRegistry = meterRegistryProvider.getIfAvailable();
        this.consumeCounter = meterRegistry == null ? null
                : Counter.builder("hmdp.kafka.cache_invalidation.consume").register(meterRegistry);
        this.payloadErrorCounter = meterRegistry == null ? null
                : Counter.builder("hmdp.kafka.cache_invalidation.payload_error").register(meterRegistry);
    }

    private void increment(Counter counter) {
        if (counter != null) {
            counter.increment();
        }
    }

    @KafkaListener(
            topics = "${hmdp.kafka.cache-invalidation.topic}",
            groupId = "${hmdp.kafka.cache-invalidation.consumer-group}",
            concurrency = "1"
    )
    public void onShopCacheInvalidation(String payload) {
        ShopCacheInvalidationMessage msg;
        try {
            msg = JSONUtil.toBean(payload, ShopCacheInvalidationMessage.class);
        } catch (Exception e) {
            increment(payloadErrorCounter);
            log.warn("invalid shop cache invalidation payload: {}", payload, e);
            return;
        }
        if (msg.getShopId() == null) {
            increment(payloadErrorCounter);
            return;
        }
        increment(consumeCounter);
        Long shopId = msg.getShopId();
        shopLocalCache.invalidate(shopId);
        String key = CACHE_SHOP_KEY + shopId;
        stringRedisTemplate.delete(key);
        Date when = new Date(System.currentTimeMillis() + doubleDeleteDelayMs);
        cacheInvalidationScheduler.schedule(() -> {
            try {
                stringRedisTemplate.delete(key);
                log.debug("shop cache delayed second delete, shopId={}", shopId);
            } catch (Exception e) {
                log.warn("delayed redis delete failed, shopId={}", shopId, e);
            }
        }, when);
    }
}
