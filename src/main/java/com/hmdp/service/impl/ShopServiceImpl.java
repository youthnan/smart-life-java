package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.bloom.ShopBloomService;
import com.hmdp.cache.ShopCacheInvalidationPublisher;
import com.hmdp.cache.ShopLocalCache;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.SystemConstants;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {



    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CacheClient cacheClient;
    @Resource
    private ShopLocalCache shopLocalCache;
    @Resource
    private ShopCacheInvalidationPublisher shopCacheInvalidationPublisher;
    @Resource
    private ShopBloomService shopBloomService;

    private final Counter localHitCounter;
    private final Counter localMissCounter;

    public ShopServiceImpl(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        MeterRegistry meterRegistry = meterRegistryProvider.getIfAvailable();
        this.localHitCounter = meterRegistry == null ? null
                : Counter.builder("hmdp.cache.shop.local.hit").register(meterRegistry);
        this.localMissCounter = meterRegistry == null ? null
                : Counter.builder("hmdp.cache.shop.local.miss").register(meterRegistry);
    }

    private void increment(Counter counter) {
        if (counter != null) {
            counter.increment();
        }
    }

    @Override
    public boolean save(Shop entity) {
        boolean ok = super.save(entity);
        if (ok && entity.getId() != null) {
            shopBloomService.addShopId(entity.getId());
        }
        return ok;
    }

    @Override
    public Result queryById(Long id){
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        if (!shopBloomService.mightContainShop(id)) {
            return Result.fail("店铺不存在");
        }

        Shop cached = shopLocalCache.getIfPresent(id);
        if (cached != null) {
            increment(localHitCounter);
            return Result.ok(cached);
        }
        increment(localMissCounter);
        Shop shop=cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY,id,Shop.class,this::getById,CACHE_SHOP_TTL,TimeUnit.MINUTES);

        //如果有,判断是否为空值
        if(shop==null){
            return Result.fail("店铺不存在");
        }
        shopLocalCache.put(id, shop);
        //不是空值，直接输出
        return Result.ok(shop);

    }

    @Override
    public Result update(Shop shop) {

        Long id = shop.getId();
        String key = CACHE_SHOP_KEY+id;
        if(id==null){
            return Result.fail("店铺id不能为空");

        }
        updateById(shop);

        shopLocalCache.invalidate(id);
        stringRedisTemplate.delete(key);
        shopCacheInvalidationPublisher.publishShopInvalidated(id);
        return Result.ok();

    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 1.判断是否需要根据坐标查询
        if (x == null || y == null) {
            // 不需要坐标查询，按数据库查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }

        // 2.计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        // 3.查询redis、按照距离排序、分页。结果：shopId、distance
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo() // GEOSEARCH key BYLONLAT x y BYRADIUS 10 WITHDISTANCE
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );
        // 4.解析出id
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {
            // 没有下一页了，结束
            return Result.ok(Collections.emptyList());
        }
        // 4.1.截取 from ~ end的部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            // 4.2.获取店铺id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            // 4.3.获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        // 5.根据id查询Shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        // 6.返回
        return Result.ok(shops);
    }

    @Override
    public Result queryNearbyRecommend(Integer current, Double x, Double y, Double radius, String keyword, Integer typeId) {
        if (x == null || y == null) {
            return Result.fail("坐标不能为空");
        }
        Integer pageNo = current == null || current < 1 ? 1 : current;
        List<Shop> shops = queryNearbyShops(pageNo, x, y, radius, keyword, typeId);
        return Result.ok(shops);
    }

    @Override
    public List<Shop> queryNearbyShops(Integer current, Double x, Double y, Double radius, String keyword, Integer typeId) {
        if (x == null || y == null) {
            return Collections.emptyList();
        }
        int pageNo = current == null || current < 1 ? 1 : current;
        double radiusMeters = (radius == null || radius <= 0) ? 5000D : radius;
        int from = (pageNo - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = pageNo * SystemConstants.DEFAULT_PAGE_SIZE;

        // 1) 确定需要查询的 GEO key（按 typeId 或遍历所有类型）
        List<Integer> typeIds;
        if (typeId != null) {
            typeIds = Collections.singletonList(typeId);
        } else {
            // DISTINCT type_id FROM tb_shop
            List<Object> objs = getBaseMapper().selectObjs(new QueryWrapper<Shop>().select("DISTINCT type_id"));
            typeIds = objs == null ? Collections.emptyList() : objs.stream()
                    .filter(Objects::nonNull)
                    .map(o -> Integer.valueOf(o.toString()))
                    .collect(Collectors.toList());
        }
        if (typeIds.isEmpty()) {
            return Collections.emptyList();
        }

        // 2) GEOSEARCH 合并结果（取最小距离）
        Map<Long, Double> distanceMap = new HashMap<>();
        for (Integer tid : typeIds) {
            String key = SHOP_GEO_KEY + tid;
            GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                    .search(
                            key,
                            GeoReference.fromCoordinate(x, y),
                            new Distance(radiusMeters),
                            RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                    );
            if (results == null) {
                continue;
            }
            for (GeoResult<RedisGeoCommands.GeoLocation<String>> r : results.getContent()) {
                String idStr = r.getContent().getName();
                if (idStr == null) {
                    continue;
                }
                Long id = Long.valueOf(idStr);
                Distance d = r.getDistance();
                double meters = d == null ? Double.MAX_VALUE : d.getValue();
                distanceMap.merge(id, meters, Math::min);
            }
        }
        if (distanceMap.isEmpty()) {
            return Collections.emptyList();
        }

        // 3) 按距离排序后分页截取
        List<Long> sortedIds = distanceMap.entrySet().stream()
                .sorted(Comparator.comparingDouble(Map.Entry::getValue))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        if (sortedIds.size() <= from) {
            return Collections.emptyList();
        }
        List<Long> pageIds = sortedIds.subList(from, Math.min(end, sortedIds.size()));

        // 4) 回表查询商户信息（可选 keyword 过滤），保持距离顺序
        String idStr = StrUtil.join(",", pageIds);
        List<Shop> shops = query()
                .in("id", pageIds)
                .like(StrUtil.isNotBlank(keyword), "name", keyword)
                .last("ORDER BY FIELD(id," + idStr + ")")
                .list();
        if (shops == null || shops.isEmpty()) {
            return Collections.emptyList();
        }
        for (Shop shop : shops) {
            Double meters = distanceMap.get(shop.getId());
            if (meters != null) {
                shop.setDistance(meters);
            }
        }
        return shops;
    }

}
