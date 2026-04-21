package com.hmdp.utils;

import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CacheClientTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private CacheClient cacheClient;

    @BeforeEach
    void setUp() {
        cacheClient = new CacheClient(stringRedisTemplate);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void queryWithPassThrough_shouldReturnCacheValue() {
        Shop shop = new Shop();
        shop.setId(1L);
        shop.setName("coffee");
        when(valueOperations.get("cache:shop:1")).thenReturn(JSONUtil.toJsonStr(shop));

        Shop result = cacheClient.queryWithPassThrough(
                "cache:shop:",
                1L,
                Shop.class,
                id -> null,
                30L,
                TimeUnit.MINUTES
        );

        assertEquals("coffee", result.getName());
    }

    @Test
    void queryWithPassThrough_shouldWriteEmptyMarkerWhenDbMiss() {
        when(valueOperations.get("cache:shop:2")).thenReturn(null);

        Shop result = cacheClient.queryWithPassThrough(
                "cache:shop:",
                2L,
                Shop.class,
                id -> null,
                30L,
                TimeUnit.MINUTES
        );

        assertNull(result);
        verify(valueOperations).set("cache:shop:2", "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
    }

    @Test
    void queryWithLogicalExpire_shouldConvertGenericPayload() {
        RedisData<Object> redisData = new RedisData<>();
        redisData.setExpireTime(LocalDateTime.now().plusMinutes(5));
        Map<String, Object> data = new HashMap<>();
        data.put("id", 3L);
        data.put("name", "bookstore");
        redisData.setData(data);
        when(valueOperations.get("cache:shop:3")).thenReturn(JSONUtil.toJsonStr(redisData));

        Shop result = cacheClient.queryWithLogicalExpire(
                "cache:shop:",
                3L,
                Shop.class,
                id -> null,
                30L,
                TimeUnit.MINUTES
        );

        assertEquals(Long.valueOf(3L), result.getId());
        assertEquals("bookstore", result.getName());
        verify(stringRedisTemplate, never()).delete(eq("lock:shop:3"));
    }
}
