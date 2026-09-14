package com.hyforum.common.redis;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 装配。
 *
 * <p>本项目对 Redis 的使用都是<b>简单值语义</b>（验证码答案、限流计数、浏览量增量），
 * 一律用 {@link StringRedisTemplate}（key/value 都是 String），不做对象序列化 ——
 * 这样可以避免 JDK 序列化带来的"改个类就不能反序列化旧值"的问题，
 * 也便于用 redis-cli 直接查看与排障。</p>
 *
 * <p>Sa-Token 的登录态存储不走这里：它由 {@code sa-token-redis-jackson} 自动装配，
 * 见 docs/技术方案.md §7 的 {@code hy:token:*} 一行。</p>
 */
@Configuration
public class RedisConfig {

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
}
