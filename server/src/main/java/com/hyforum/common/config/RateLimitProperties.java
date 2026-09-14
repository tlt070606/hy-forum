package com.hyforum.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 限流参数（docs/技术方案.md §8.7）。
 *
 * <p>M1 阶段只落地「同一 IP 每分钟请求上限」这一条（登录/注册这类免登录接口被刷的直接防线）。
 * §8.7 的其余维度（发帖 3/24h、评论 20/h、举报 10/天、单用户日 1000 次）分别属于
 * M3/M4/M5 的业务限流，由对应模块实现，但都应复用 {@code RateLimiter}。</p>
 *
 * @param ipPerMinute 同一 IP 每分钟允许的请求数
 */
@ConfigurationProperties(prefix = "hy.rate-limit")
public record RateLimitProperties(int ipPerMinute) {

    /** 兜底：配置缺失时给一个宽松但有意义的默认值（100 次/分钟）。 */
    public RateLimitProperties {
        if (ipPerMinute <= 0) {
            ipPerMinute = 100;
        }
    }
}
