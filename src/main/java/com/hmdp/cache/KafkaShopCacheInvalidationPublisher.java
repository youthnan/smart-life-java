package com.hmdp.cache;

import cn.hutool.json.JSONUtil;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "hmdp.kafka.cache-invalidation", name = "enabled", havingValue = "true")
public class KafkaShopCacheInvalidationPublisher implements ShopCacheInvalidationPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Counter publishCounter;
    private final Counter publishErrorCounter;

    public KafkaShopCacheInvalidationPublisher(KafkaTemplate<String, String> kafkaTemplate,
                                               ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.kafkaTemplate = kafkaTemplate;
        MeterRegistry meterRegistry = meterRegistryProvider.getIfAvailable();
        this.publishCounter = meterRegistry == null ? null
                : Counter.builder("hmdp.kafka.cache_invalidation.publish").register(meterRegistry);
        this.publishErrorCounter = meterRegistry == null ? null
                : Counter.builder("hmdp.kafka.cache_invalidation.publish_error").register(meterRegistry);
    }

    private void increment(Counter counter) {
        if (counter != null) {
            counter.increment();
        }
    }

    @Value("${hmdp.kafka.cache-invalidation.topic}")
    private String topic;

    @Override
    public void publishShopInvalidated(long shopId) {
        ShopCacheInvalidationMessage msg = new ShopCacheInvalidationMessage(shopId, System.currentTimeMillis());
        String payload = JSONUtil.toJsonStr(msg);
        kafkaTemplate.send(topic, String.valueOf(shopId), payload).completable()
                .whenComplete((r, ex) -> {
                    if (ex != null) {
                        increment(publishErrorCounter);
                        log.warn("shop cache invalidation send failed, shopId={}", shopId, ex);
                    } else {
                        increment(publishCounter);
                        log.debug("shop cache invalidation sent, shopId={}", shopId);
                    }
                });
    }
}
