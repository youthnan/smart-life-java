package com.hmdp.agent.tools;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.utils.RedisConstants;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Function Calling：查店铺、模糊搜店、演示预约（写 Redis，非真实到店履约）。
 */
@Component
public class ShopAgentTools {

    @Resource
    private ShopMapper shopMapper;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Tool("根据店铺数字ID查询详情，返回 JSON；若不存在则说明未找到")
    public String getShopJsonById(@P("店铺ID") long shopId) {
        Shop s = shopMapper.selectById(shopId);
        return s == null ? "未找到该店铺" : JSONUtil.toJsonStr(s);
    }

    @Tool("按名称关键词模糊查询店铺，最多返回 10 条，JSON 数组")
    public String searchShopsByName(@P("名称关键词") String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return "[]";
        }
        List<Shop> list = shopMapper.selectList(
                new LambdaQueryWrapper<Shop>()
                        .like(Shop::getName, keyword.trim())
                        .last("LIMIT 10"));
        return JSONUtil.toJsonStr(list);
    }

    @Tool("登记到店预约意向（演示）：写入 Redis，返回预约单号；不调用外部履约系统")
    public String bookVisit(@P("店铺ID") long shopId, @P("用户备注") String customerNote) {
        Shop s = shopMapper.selectById(shopId);
        if (s == null) {
            return "店铺不存在，无法预约";
        }
        String bookingId = UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> payload = new HashMap<>(4);
        payload.put("bookingId", bookingId);
        payload.put("shopId", shopId);
        payload.put("shopName", s.getName());
        payload.put("note", customerNote == null ? "" : customerNote);
        payload.put("createdAt", Instant.now().toString());
        String key = RedisConstants.AGENT_BOOKING_KEY + bookingId;
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(payload), 7, TimeUnit.DAYS);
        return "预约已记录（演示），单号=" + bookingId + "，店铺=" + s.getName();
    }

    /**
     * 供系统提示词引用：列出若干店铺名（避免上下文过长）。
     */
    public String sampleShopNames() {
        return shopMapper.selectList(new LambdaQueryWrapper<Shop>().last("LIMIT 5"))
                .stream()
                .map(Shop::getName)
                .collect(Collectors.joining(", "));
    }
}
