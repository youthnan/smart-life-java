package com.hmdp.ratelimit;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.UUID;

/**
 * 滑动窗口限流切面：对每个维度独立计数，任一维度超限即拒绝。
 */
@Aspect
@Component
public class RateLimitAspect {

    private static final Logger LOGGER = LoggerFactory.getLogger(RateLimitAspect.class);

    private static final String SCRIPT_PATH = "ratelimit/sliding_window.lua";

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Value("${hmdp.ratelimit.enabled:true}")
    private boolean enabled;

    @Value("${hmdp.ratelimit.trust-proxy:false}")
    private boolean trustProxy;

    private DefaultRedisScript<Long> slidingScript;

    @PostConstruct
    public void loadScript() {
        slidingScript = new DefaultRedisScript<>();
        slidingScript.setLocation(new ClassPathResource(SCRIPT_PATH));
        slidingScript.setResultType(Long.class);
    }

    @Around("@annotation(com.hmdp.ratelimit.RateLimit)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        if (!enabled) {
            return joinPoint.proceed();
        }

        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        RateLimit rateLimit = method.getAnnotation(RateLimit.class);
        if (rateLimit == null) {
            return joinPoint.proceed();
        }

        String routeKey = StringUtils.hasText(rateLimit.key()) ? rateLimit.key() : method.getName();
        long windowMs = rateLimit.windowSeconds() * 1000L;
        int max = rateLimit.maxRequests();

        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return joinPoint.proceed();
        }
        HttpServletRequest request = attrs.getRequest();
        String ip = resolveClientIp(request);

        long now = System.currentTimeMillis();
        String member = now + ":" + UUID.randomUUID();

        for (RateDimension dimension : rateLimit.dimensions()) {
            String redisKey = null;
            if (dimension == RateDimension.IP) {
                redisKey = "hmdp:rate:ip:" + ip + ":" + routeKey;
            } else if (dimension == RateDimension.USER) {
                UserDTO user = UserHolder.getUser();
                if (user == null || user.getId() == null) {
                    continue;
                }
                redisKey = "hmdp:rate:uid:" + user.getId() + ":" + routeKey;
            }
            if (redisKey == null) {
                continue;
            }
            Long allowed = stringRedisTemplate.execute(
                    slidingScript,
                    Collections.singletonList(redisKey),
                    String.valueOf(windowMs),
                    String.valueOf(max),
                    String.valueOf(now),
                    member
            );
            if (allowed != null && allowed == 0L) {
                LOGGER.warn("rate limit exceeded, dim={}, key={}", dimension, redisKey);
                return Result.fail("请求过于频繁");
            }
        }

        return joinPoint.proceed();
    }

    private String resolveClientIp(HttpServletRequest request) {
        if (trustProxy) {
            String xff = request.getHeader("X-Forwarded-For");
            if (StringUtils.hasText(xff)) {
                int comma = xff.indexOf(',');
                return comma > 0 ? xff.substring(0, comma).trim() : xff.trim();
            }
        }
        return request.getRemoteAddr();
    }
}
