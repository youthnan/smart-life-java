package com.hmdp.service.impl;

import cn.hutool.core.collection.ListUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_LIST_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryTypeList() {
        String key=CACHE_SHOP_LIST_KEY;
        // 1. 从 Redis 查询商铺类型缓存
        String shopTypeJson = stringRedisTemplate.opsForValue().get(key);
        // 2 如果不为空，直接输出
        if(StrUtil.isNotBlank(shopTypeJson)){
            List<ShopType> typeList = JSONUtil.toList(shopTypeJson,ShopType.class);
            return Result.ok(typeList);
        }
        // 3 如果为空，在服务器找
        List<ShopType> typeList = query().orderByAsc("sort").list();

        //4 如果没找到，输出
        if(typeList==null){
            return Result.fail("没有商店类型");
        }

        // 5 如果找到了，就写在redis里面
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(typeList));
        return Result.ok(typeList);
    }
}


