package com.hyforum.common.redis;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 频率限制器（docs/技术方案.md §8.7）。
 *
 * <p>实现方式：Redis {@code INCR} + 首次计数时设 {@code EXPIRE}，与 §8.7 的
 * 「基于 Redis INCR + EXPIRE 实现」一致。</p>
 *
 * <p><b>为什么 TTL 只在第一次设置</b>：如果每次 INCR 都刷新 EXPIRE，计数器就永不过期，
 * 一个持续打请求的 IP 会被永久锁死 —— 这是限流实现里最常见的错误。</p>
 *
 * <p><b>TTL 返回 -1 的处理</b>：说明 key 存在但没有过期时间（异常状态，例如进程在
 * INCR 与 EXPIRE 之间被杀）。此时补一次 EXPIRE，避免限流窗口永久化。</p>
 *
 * <p>键格式遵循技术方案 §7 的 {@code hy:rl:{action}:{id}} 口径，
 * 其中 action 里已含时间粒度（如 {@code login-ip-1m}），不额外引入日维度键。</p>
 */
@Component
public class RateLimiter {

    /** Redis key 前缀，与技术方案 §7 的 {@code hy:rl:*} 一致。 */
    private static final String KEY_PREFIX = "hy:rl:";

    private final StringRedisTemplate redis;

    public RateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 计数并判断是否超限。
     *
     * @param action    动作标识（如 {@code register-ip-1m}）
     * @param identity  身份标识（IP 或 userId）
     * @param limit     窗口内允许的最大次数
     * @param window    窗口长度
     * @return 判定结果（已计数、当前次数、剩余秒数）
     */
    public Decision check(String action, String identity, int limit, Duration window) {
        String key = KEY_PREFIX + action + ":" + identity;
        Long count = redis.opsForValue().increment(key);
        if (count == null) {
            // Redis 不可用时不拦请求：限流是保护措施，不应成为可用性单点
            return new Decision(true, 0, 0);
        }
        if (count == 1L) {
            redis.expire(key, window);
        } else {
            Long ttl = redis.getExpire(key);
            if (ttl == null || ttl < 0) {
                redis.expire(key, window);
            }
        }
        boolean allowed = count <= limit;
        long retryAfter = allowed ? 0 : Math.max(1, ttlSeconds(key));
        return new Decision(allowed, count, retryAfter);
    }

    /** 清空某个动作的计数（测试与运维排障用，避免"被限流后无法自证"）。 */
    public void reset(String action, String identity) {
        redis.delete(KEY_PREFIX + action + ":" + identity);
    }

    private long ttlSeconds(String key) {
        Long ttl = redis.getExpire(key);
        return ttl == null ? 60 : ttl;
    }

    /**
     * 限流判定结果。
     *
     * @param allowed    是否放行
     * @param count      当前窗口内已计数次数
     * @param retryAfter 被拒时建议的重试等待秒数（用于 {@code Retry-After} 头，§8.7）
     */
    public record Decision(boolean allowed, long count, long retryAfter) {
    }
}
