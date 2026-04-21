package com.hmdp.bloom;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.utils.RedisConstants;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.List;

/**
 * 基于 Redisson RBloomFilter 的店铺 id 集合；{@code contains == false} 时可拦截必然不存在的 id。
 */
@Service
public class ShopBloomService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ShopBloomService.class);

    @Resource
    private RedissonClient redissonClient;
    @Resource
    private ShopMapper shopMapper;

    @Value("${hmdp.bloom.shop.enabled:true}")
    private boolean enabled;

    @Value("${hmdp.bloom.shop.expected-insertions:100000}")
    private long expectedInsertions;

    @Value("${hmdp.bloom.shop.false-probability:0.01}")
    private double falseProbability;

    @Value("${hmdp.bloom.shop.refresh-enabled:false}")
    private boolean refreshEnabled;

    private RBloomFilter<String> bloomFilter;

    @PostConstruct
    public void initBloomFilter() {
        if (!enabled) {
            LOGGER.info("Shop bloom filter disabled (hmdp.bloom.shop.enabled=false)");
            return;
        }
        bloomFilter = redissonClient.getBloomFilter(RedisConstants.BLOOM_SHOP_IDS);
        bloomFilter.tryInit(expectedInsertions, falseProbability);
        warmUpBloom();
    }

    @Scheduled(
            fixedDelayString = "${hmdp.bloom.shop.refresh-interval-ms:3600000}",
            initialDelayString = "${hmdp.bloom.shop.refresh-initial-delay-ms:300000}"
    )
    public void scheduledRefresh() {
        if (!enabled || !refreshEnabled || bloomFilter == null) {
            return;
        }
        warmUpBloom();
    }

    private void warmUpBloom() {
        List<Object> ids = shopMapper.selectObjs(new QueryWrapper<Shop>().select("id"));
        int n = 0;
        for (Object id : ids) {
            if (id != null) {
                bloomFilter.add(String.valueOf(id));
                n++;
            }
        }
        LOGGER.info("Shop bloom filter warmed up with {} ids (expectedInsertions={}, falseProbability={})",
                n, expectedInsertions, falseProbability);
    }

    /**
     * @return false 表示布隆判定该 id 一定不在集合中，可短路；关闭布隆或未初始化时恒为 true
     */
    public boolean mightContainShop(Long shopId) {
        if (!enabled || shopId == null) {
            return true;
        }
        if (bloomFilter == null) {
            return true;
        }
        return bloomFilter.contains(String.valueOf(shopId));
    }

    public void addShopId(Long shopId) {
        if (!enabled || shopId == null || bloomFilter == null) {
            return;
        }
        bloomFilter.add(String.valueOf(shopId));
    }
}
