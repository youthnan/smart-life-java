package com.hmdp.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hmdp.entity.Shop;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class ShopLocalCache {

    private final Cache<Long, Shop> cache;

    public ShopLocalCache(
            @Value("${hmdp.cache.shop-local.maximum-size:10000}") long maximumSize,
            @Value("${hmdp.cache.shop-local.ttl-minutes:10}") long ttlMinutes
    ) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(maximumSize)
                .expireAfterWrite(ttlMinutes, TimeUnit.MINUTES)
                .build();
    }

    public Shop getIfPresent(Long shopId) {
        if (shopId == null) {
            return null;
        }
        return cache.getIfPresent(shopId);
    }

    public void put(Long shopId, Shop shop) {
        if (shopId == null || shop == null) {
            return;
        }
        cache.put(shopId, shop);
    }

    public void invalidate(Long shopId) {
        if (shopId == null) {
            return;
        }
        cache.invalidate(shopId);
    }
}
