package com.hyforum.media.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 读时签名（{@code GET} 对象 URL 的临时凭证）的配置，前缀 {@code hy.oss.read-url}。
 *
 * <p>与 {@code OssUploadProperties} 同样：键不在 {@code application.yml} 里（该文件由 L1 维护），
 * 取值来自下面的代码默认值，需要调整时用环境变量覆盖。</p>
 *
 * @param ttlSeconds 签名有效期（秒），默认 <b>3600</b>（§12.2 的建议值）。
 *                   取舍：太短会让用户翻着帖子图片就 403（详情页停留时间可能超过几分钟）；
 *                   太长则等于"泄露一个 URL 就长期可读"。1 小时对论坛图片足够，
 *                   而列表/详情每次请求都会重新签发，所以用户实际看到的总是一个"新鲜"的 URL。
 */
@ConfigurationProperties(prefix = "hy.oss.read-url")
public record OssReadUrlProperties(int ttlSeconds) {

    /** 默认有效期（秒）：§12.2 建议 3600。 */
    private static final int DEFAULT_TTL_SECONDS = 3600;

    public OssReadUrlProperties {
        if (ttlSeconds <= 0) {
            ttlSeconds = DEFAULT_TTL_SECONDS;
        }
    }

    /** 有效期（Duration 形态）。 */
    public Duration ttl() {
        return Duration.ofSeconds(ttlSeconds);
    }
}
