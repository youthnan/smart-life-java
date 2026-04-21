package com.hmdp.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 36000L;

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";

    public static final String CACHE_SHOP_LIST_KEY="cache:shop-type:list";

    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    public static final String ORDER_STREAM_KEY = "stream.orders";
    public static final String ORDER_CONSUMER_GROUP = "g1";
    public static final String ORDER_CONSUMER_NAME = "c1";
    public static final String LOCK_ORDER_KEY = "lock:order:";
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";

    /** Redisson RBloomFilter 名称：合法店铺 id（用于穿透前置拦截，假阴性需配合库表存在性） */
    public static final String BLOOM_SHOP_IDS = "hmdp:bloom:shop:ids";

    /** LangChain4j 会话记忆（Redis List JSON） */
    public static final String AGENT_MEMORY_KEY = "hmdp:agent:memory:";
    /** 演示：Agent 预约意向单 */
    public static final String AGENT_BOOKING_KEY = "hmdp:agent:booking:";
}
