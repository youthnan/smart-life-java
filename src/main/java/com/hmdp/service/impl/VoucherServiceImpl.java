package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.NearbyVoucherDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.Voucher;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IShopService;
import com.hmdp.service.IVoucherService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IShopService shopService;
    @Override
    public Result queryVoucherOfShop(Long shopId) {
        // 查询优惠券信息
        List<Voucher> vouchers = getBaseMapper().queryVoucherOfShop(shopId);
        // 返回结果
        return Result.ok(vouchers);
    }

    @Override
    @Transactional
    public void addSeckillVoucher(Voucher voucher) {
        // 保存优惠券
        save(voucher);
        // 保存秒杀信息
        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(voucher.getId());
        seckillVoucher.setStock(voucher.getStock());
        seckillVoucher.setBeginTime(voucher.getBeginTime());
        seckillVoucher.setEndTime(voucher.getEndTime());
        seckillVoucherService.save(seckillVoucher);
        //保存秒杀库存到redis
        stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY + voucher.getId(), voucher.getStock().toString());
    }

    @Override
    public Result queryNearbyAvailable(Integer current, Double x, Double y, Double radius, String keyword) {
        if (x == null || y == null) {
            return Result.fail("坐标不能为空");
        }
        List<Shop> shops = shopService.queryNearbyShops(current, x, y, radius, keyword, null);
        if (shops == null || shops.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        LocalDateTime now = LocalDateTime.now();
        List<NearbyVoucherDTO> result = shops.stream()
                .flatMap(shop -> getBaseMapper().queryVoucherOfShop(shop.getId()).stream()
                        .filter(voucher -> isAvailable(voucher, now))
                        .map(voucher -> toNearbyVoucherDTO(shop, voucher)))
                .collect(Collectors.toList());
        return Result.ok(result);
    }

    private boolean isAvailable(Voucher voucher, LocalDateTime now) {
        if (voucher == null) {
            return false;
        }
        if (voucher.getType() == null || voucher.getType() == 0) {
            return true;
        }
        Integer stock = voucher.getStock();
        if (stock == null || stock <= 0) {
            return false;
        }
        LocalDateTime begin = voucher.getBeginTime();
        LocalDateTime end = voucher.getEndTime();
        if (begin == null || end == null) {
            return false;
        }
        return !now.isBefore(begin) && !now.isAfter(end);
    }

    private NearbyVoucherDTO toNearbyVoucherDTO(Shop shop, Voucher voucher) {
        NearbyVoucherDTO dto = new NearbyVoucherDTO();
        dto.setShopId(shop.getId());
        dto.setShopName(shop.getName());
        dto.setDistance(shop.getDistance());
        dto.setVoucherId(voucher.getId());
        dto.setTitle(voucher.getTitle());
        dto.setPayValue(voucher.getPayValue());
        dto.setActualValue(voucher.getActualValue());
        dto.setStock(voucher.getStock());
        dto.setBeginTime(voucher.getBeginTime());
        dto.setEndTime(voucher.getEndTime());
        dto.setAvailable(Boolean.TRUE);
        return dto;
    }
}
