package com.hyforum.media.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * OSS 回调的配置（前缀 {@code hy.oss.callback}）：验签来源、防重放窗口、公钥缓存、
 * 以及回调侧的内容白名单。
 *
 * <p>与 {@link OssUploadProperties} 同样：这些键不在 {@code application.yml} 里
 * （该文件由 L1 维护，本段只读），取值来自下面的代码默认值，可用环境变量覆盖。</p>
 *
 * @param allowedPublicKeyUrlPrefixes 允许的公钥地址前缀。官方文档要求
 *        {@code x-oss-pub-key-url} 解码后必须以 {@code http(s)://gosspublic.alicdn.com/} 开头 ——
 *        这条校验是<b>防 SSRF 的关键</b>：不校验就等于"任何人都能让我们去请求任意 URL"，
 *        而且可以用自己控制的公钥配合自己的私钥伪造签名，验签形同虚设。
 *        测试把它收窄到本地桩地址，于是真实取公钥/验签的代码路径可离线跑通
 * @param maxAgeSeconds               回调请求的 {@code Date} 头允许的最大偏差（秒），默认 900。
 *        验签成功只说明"请求来自持有该私钥的一方"，不说明"是刚刚发的" ——
 *        拦下陈旧请求可以压缩重放窗口。刻意给 15 分钟而不是 5 分钟：
 *        OSS 与开发机之间可能存在时钟偏差，窗口太紧会把正常回调判成过期
 * @param publicKeyCacheSeconds       公钥缓存时长（秒），默认 3600。官方文档明确建议缓存
 *        （"由于公钥地址的内容不变，建议缓存公钥以避免因网络波动影响服务"）
 * @param httpTimeoutSeconds          取公钥的 HTTP 超时（秒），默认 5
 * @param maxImageBytes               单图大小上限（字节），默认 5MB（§8.4）
 * @param allowedContentTypes         允许的图片 Content-Type 白名单（§8.4）
 * @param requiredKeyPrefix           回调里的对象 key 必须以此前缀开头；留空则回落到
 *        {@code common.oss.OssProperties.imageDir()}。<b>不校验它，等于允许把桶里任意对象
 *        注册成"帖子图片"</b>（§8.4 第 4 条的归属校验在发帖侧，而回调是同一件事的另一端）
 */
@ConfigurationProperties(prefix = "hy.oss.callback")
public record OssCallbackProperties(
        List<String> allowedPublicKeyUrlPrefixes,
        int maxAgeSeconds,
        int publicKeyCacheSeconds,
        int httpTimeoutSeconds,
        long maxImageBytes,
        List<String> allowedContentTypes,
        String requiredKeyPrefix) {

    /** 官方公钥地址的允许前缀（文档原文）。 */
    private static final List<String> GOSSPUBLIC_PREFIXES = List.of(
            "http://gosspublic.alicdn.com/",
            "https://gosspublic.alicdn.com/");

    /** 防重放窗口默认值（秒）。 */
    private static final int DEFAULT_MAX_AGE_SECONDS = 900;

    public OssCallbackProperties {
        if (allowedPublicKeyUrlPrefixes == null || allowedPublicKeyUrlPrefixes.isEmpty()) {
            allowedPublicKeyUrlPrefixes = GOSSPUBLIC_PREFIXES;
        }
        if (maxAgeSeconds <= 0) {
            maxAgeSeconds = DEFAULT_MAX_AGE_SECONDS;
        }
        if (publicKeyCacheSeconds <= 0) {
            publicKeyCacheSeconds = 3600;
        }
        if (httpTimeoutSeconds <= 0) {
            httpTimeoutSeconds = 5;
        }
        if (maxImageBytes <= 0) {
            maxImageBytes = 5L * 1024 * 1024;
        }
        if (allowedContentTypes == null || allowedContentTypes.isEmpty()) {
            allowedContentTypes = List.of("image/jpeg", "image/png", "image/webp", "image/gif");
        }
    }

    /** 防重放窗口的 Duration 形态。 */
    public Duration maxAge() {
        return Duration.ofSeconds(maxAgeSeconds);
    }

    /** 公钥缓存时长的 Duration 形态。 */
    public Duration publicKeyCacheTtl() {
        return Duration.ofSeconds(publicKeyCacheSeconds);
    }

    /** 取公钥的 HTTP 超时。 */
    public Duration httpTimeout() {
        return Duration.ofSeconds(httpTimeoutSeconds);
    }
}
