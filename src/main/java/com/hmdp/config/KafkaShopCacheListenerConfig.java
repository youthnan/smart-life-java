package com.hmdp.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;

@Configuration
@EnableKafka
@ConditionalOnProperty(prefix = "hmdp.kafka.cache-invalidation", name = "enabled", havingValue = "true")
public class KafkaShopCacheListenerConfig {
}
