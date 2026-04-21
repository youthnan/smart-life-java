package com.hmdp.ratelimit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Redis ZSET 滑动窗口限流；可与 IP/登录用户维度组合。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    int windowSeconds();

    int maxRequests();

    RateDimension[] dimensions() default {RateDimension.IP, RateDimension.USER};

    /** Redis key 片段，默认使用方法名 */
    String key() default "";
}
