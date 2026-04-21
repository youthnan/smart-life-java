package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor.AbortPolicy;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;

@Slf4j
@Component

public class CacheClient {
    private static final int MAX_COLD_SPIN = 12;
    private static final AtomicInteger CACHE_REBUILD_THREAD_SEQ = new AtomicInteger(0);

    private final StringRedisTemplate stringRedisTemplate;
    private final MeterRegistry meterRegistry;
    private final Counter logicalCacheHitCounter;
    private final Counter logicalCacheMissCounter;
    private final Counter logicalCacheRebuildCounter;
    private final Counter logicalCacheColdRetryCounter;

    /**
     * 逻辑过期时间 + 保护余量（秒） => Redis 物理 TTL，避免「逻辑仍可读但物理 key 提前过期」。
     */
    @Value("${hmdp.cache.logical-expire.physical-ttl-skew-seconds:60}")
    private long physicalTtlSkewSeconds;

    @Value("${hmdp.cache.logical-expire.cold-miss-initial-backoff-ms:30}")
    private long coldMissInitialBackoffMs;

    @Value("${hmdp.cache.logical-expire.cold-miss-max-backoff-ms:320}")
    private long coldMissMaxBackoffMs;

    public CacheClient(StringRedisTemplate stringRedisTemplate, ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.meterRegistry = meterRegistryProvider.getIfAvailable();
        this.logicalCacheHitCounter = registerCounter("hmdp.cache.logical.hit");
        this.logicalCacheMissCounter = registerCounter("hmdp.cache.logical.miss");
        this.logicalCacheRebuildCounter = registerCounter("hmdp.cache.logical.rebuild");
        this.logicalCacheColdRetryCounter = registerCounter("hmdp.cache.logical.cold_retry");
    }

    private Counter registerCounter(String name) {
        if (meterRegistry == null) {
            return null;
        }
        return Counter.builder(name).register(meterRegistry);
    }

    private void increment(Counter counter) {
        if (counter != null) {
            counter.increment();
        }
    }

    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public <T> void setWithLogicalExpire(String key, T value, Long time, TimeUnit unit) {
        if (value == null) {
            setNullLogicalMarker(key);
            return;
        }
        long logicalSeconds = Math.max(1L, unit.toSeconds(time));
        long physicalSeconds = logicalSeconds + Math.max(1L, physicalTtlSkewSeconds);
        RedisData<T> redisData = new RedisData<>();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(logicalSeconds));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData), physicalSeconds, TimeUnit.SECONDS);
    }

    /**
     * 逻辑过期下的「空对象」：短 TTL + 逻辑过期时间内直接返回 null，减轻不存在店铺对 DB 的反复查询。
     */
    private void setNullLogicalMarker(String key) {
        RedisData<Object> redisData = new RedisData<>();
        redisData.setData(null);
        redisData.setExpireTime(LocalDateTime.now().plusMinutes(CACHE_NULL_TTL));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData), CACHE_NULL_TTL, TimeUnit.MINUTES);
    }

    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        if (json != null) {
            return null;
        }

        R r = dbFallback.apply(id);
        if (r == null) {
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        this.set(key, r, time, unit);
        return r;

    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = new ThreadPoolExecutor(
            2,
            8,
            60L,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(200),
            r -> {
                Thread t = new Thread(r, "shop-cache-rebuild-" + CACHE_REBUILD_THREAD_SEQ.incrementAndGet());
                t.setDaemon(true);
                return t;
            },
            new AbortPolicy()
    );

    public <R, ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(shopJson)) {
            increment(logicalCacheMissCounter);
            return loadLogicalColdMiss(keyPrefix, id, type, dbFallback, time, unit, 0);
        }
        increment(logicalCacheHitCounter);
        return handleNonBlankLogical(key, shopJson, id, type, dbFallback, time, unit);
    }

    private static final class LogicalParseResult<R> {
        final R value;
        final LocalDateTime expireTime;

        LogicalParseResult(R value, LocalDateTime expireTime) {
            this.value = value;
            this.expireTime = expireTime;
        }
    }

    private <R> LogicalParseResult<R> parseLogicalJson(String shopJson, Class<R> type) {
        RedisData<?> redisData = JSONUtil.toBean(shopJson, RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        Object rawData = redisData.getData();
        R r;
        if (rawData == null) {
            r = null;
        } else if (rawData instanceof JSONObject) {
            r = JSONUtil.toBean((JSONObject) rawData, type);
        } else {
            r = JSONUtil.toBean(JSONUtil.toJsonStr(rawData), type);
        }
        return new LogicalParseResult<>(r, expireTime);
    }

    private <R, ID> R handleNonBlankLogical(String key, String shopJson, ID id, Class<R> type,
            Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        LogicalParseResult<R> parsed = parseLogicalJson(shopJson, type);
        if (parsed.expireTime == null) {
            return null;
        }
        if (parsed.expireTime.isAfter(LocalDateTime.now())) {
            return parsed.value;
        }
        String lockKey = LOCK_SHOP_KEY + id;
        Boolean isLock = trylock(lockKey);
        if (Boolean.TRUE.equals(isLock)) {
            try {
                CACHE_REBUILD_EXECUTOR.submit(() -> {
                    try {
                        String json = stringRedisTemplate.opsForValue().get(key);
                        if (StrUtil.isBlank(json)) {
                            return;
                        }
                        LogicalParseResult<R> p = parseLogicalJson(json, type);
                        if (p.expireTime != null && p.expireTime.isAfter(LocalDateTime.now())) {
                            return;
                        }
                        R r1 = dbFallback.apply(id);
                        if (r1 == null) {
                            setNullLogicalMarker(key);
                        } else {
                            setWithLogicalExpire(key, r1, time, unit);
                        }
                        increment(logicalCacheRebuildCounter);
                        log.debug("Cache rebuild finished for key={}", key);
                    } catch (Exception e) {
                        log.error("Cache rebuild failed for key={}", key, e);
                    } finally {
                        unlock(lockKey);
                    }

                });
            } catch (RuntimeException ex) {
                unlock(lockKey);
                log.warn("cache rebuild task rejected, key={}", key, ex);
            }
        }
        return parsed.value;
    }

    private <R, ID> R loadLogicalColdMiss(String keyPrefix, ID id, Class<R> type,
            Function<ID, R> dbFallback, Long time, TimeUnit unit, int spin) {
        if (spin >= MAX_COLD_SPIN) {
            log.warn("shop logical cache cold load spin limit, prefix={} id={}", keyPrefix, id);
            return null;
        }
        String key = keyPrefix + id;
        String lockKey = LOCK_SHOP_KEY + id;
        boolean locked = trylock(lockKey);
        if (!locked) {
            increment(logicalCacheColdRetryCounter);
            try {
                Thread.sleep(computeBackoffMillis(spin));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            String shopJson = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isBlank(shopJson)) {
                return loadLogicalColdMiss(keyPrefix, id, type, dbFallback, time, unit, spin + 1);
            }
            return handleNonBlankLogical(key, shopJson, id, type, dbFallback, time, unit);
        }
        try {
            String json2 = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(json2)) {
                return handleNonBlankLogical(key, json2, id, type, dbFallback, time, unit);
            }
            R r = dbFallback.apply(id);
            if (r == null) {
                setNullLogicalMarker(key);
            } else {
                setWithLogicalExpire(key, r, time, unit);
            }
            return r;
        } finally {
            unlock(lockKey);
        }
    }

    private long computeBackoffMillis(int spin) {
        long init = Math.max(1L, coldMissInitialBackoffMs);
        long max = Math.max(init, coldMissMaxBackoffMs);
        long candidate = init << Math.min(10, Math.max(0, spin));
        return Math.min(candidate, max);
    }

    private boolean trylock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}
