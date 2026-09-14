package com.hyforum.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 图形验证码参数（docs/技术方案.md §6.2 / §7）。
 *
 * <p><b>TTL 是契约参数，不是可调参数</b>：§6.2 与 §7 都写明 300s，且验收项
 * {@code M1_captcha_ttl_is_300s} 直接断言 Redis 键 TTL = 300s。
 * 之所以做成配置项是为了让测试能显式引用同一来源（而不是在测试里写死 300
 * 与实现各说各话）—— {@code application.yml} 里的值必须保持 300。</p>
 *
 * @param ttlSeconds 验证码有效期（秒），契约值 300
 * @param width      图片宽
 * @param height     图片高
 * @param length     验证码字符数
 */
@ConfigurationProperties(prefix = "hy.captcha")
public record CaptchaProperties(int ttlSeconds, int width, int height, int length) {

    /** 兜底默认值：即使配置缺失也不会退化成 0 秒有效（那会让验证码永远失效）。 */
    public CaptchaProperties {
        if (ttlSeconds <= 0) {
            ttlSeconds = 300;
        }
        if (width <= 0) {
            width = 130;
        }
        if (height <= 0) {
            height = 48;
        }
        if (length <= 0) {
            length = 4;
        }
    }
}
