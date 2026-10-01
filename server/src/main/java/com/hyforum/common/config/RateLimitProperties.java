package com.hyforum.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 限流参数（docs/技术方案.md §8.7）。
 *
 * <p>M1 阶段只落地「同一 IP 每分钟请求上限」这一条（登录/注册这类免登录接口被刷的直接防线）。
 * §8.7 的其余维度（发帖 3/24h、评论 20/h、举报 10/天、单用户日 1000 次）分别属于
 * M3/M4/M5 的业务限流，由对应模块实现，但都应复用 {@code RateLimiter}。</p>
 *
 * @param ipPerMinute          同一 IP 每分钟允许的请求数（前台登录/注册共用）
 * @param adminLoginPerMinute  管理员登录同一 IP 每分钟允许的次数（2026-10-01 安全整改：
 *                             此前后台登录是全站唯一没有任何限流的认证端点，可无限速爆破。
 *                             前台失败提示统一为 1002 防枚举，后台口令若偏弱，
 *                             在线爆破是现实威胁，因此刻意比前台紧一个量级）
 * @param commentPerHour       同一用户每小时允许发表的评论数（§8.7 原文「评论 20/h」；
 *                             2026-10-01 安全整改补齐 —— 发帖有限流而发评论没有，
 *                             登录用户可脚本刷评论刷爆通知）
 */
@ConfigurationProperties(prefix = "hy.rate-limit")
public record RateLimitProperties(int ipPerMinute, int adminLoginPerMinute, int commentPerHour) {

    /** 兜底：配置缺失时给一个宽松但有意义的默认值（前台 100 次/分钟、后台 10 次/分钟、评论 20 条/小时）。 */
    public RateLimitProperties {
        if (ipPerMinute <= 0) {
            ipPerMinute = 100;
        }
        if (adminLoginPerMinute <= 0) {
            adminLoginPerMinute = 10;
        }
        if (commentPerHour <= 0) {
            commentPerHour = 20;
        }
    }
}
