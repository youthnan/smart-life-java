package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
public class RedissonConfig {

    /**
     * 与 {@code spring.redis.*}（含 {@code SPRING_REDIS_PASSWORD}）保持一致，避免 Redisson 写死密码而 Lettuce 未认证。
     */
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(RedisProperties redisProperties) {
        Config config = new Config();
        String host = redisProperties.getHost() != null ? redisProperties.getHost() : "127.0.0.1";
        int port = redisProperties.getPort();
        String address = "redis://" + host + ":" + port;
        SingleServerConfig server = config.useSingleServer().setAddress(address);
        if (StringUtils.hasText(redisProperties.getPassword())) {
            server.setPassword(redisProperties.getPassword());
        }
        server.setDatabase(redisProperties.getDatabase());
        return Redisson.create(config);
    }
}
