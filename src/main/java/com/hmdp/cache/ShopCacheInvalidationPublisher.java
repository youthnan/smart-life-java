package com.hmdp.cache;

public interface ShopCacheInvalidationPublisher {

    void publishShopInvalidated(long shopId);
}
