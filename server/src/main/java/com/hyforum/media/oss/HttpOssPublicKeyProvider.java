package com.hyforum.media.oss;

import com.hyforum.media.config.OssCallbackProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 公钥来源的默认实现：HTTP(S) 下载 PEM，按地址缓存。
 *
 * <p>官方文档明确建议缓存（"由于公钥地址的内容不变，建议缓存公钥以避免因网络波动影响服务"），
 * 因此这里按<b>地址</b>缓存内容，TTL 见 {@code hy.oss.callback.public-key-cache-seconds}。</p>
 *
 * <p><b>允许前缀校验是安全边界，不是洁癖</b>：{@code x-oss-pub-key-url} 由请求方提供，
 * 若不校验就 {@code GET} 它，任何人都能让我们去请求任意 URL（内网探测 / SSRF），
 * 更致命的是：攻击者可以把自己域名的公钥塞进来，再用自己的私钥签名 ——
 * <b>验签会通过</b>。官方文档原文要求该地址必须以
 * {@code http://gosspublic.alicdn.com/} 或 {@code https://gosspublic.alicdn.com/} 开头。</p>
 *
 * <p>本类刻意<b>不做重定向跟随</b>（{@code HttpClient.Redirect.NEVER}）：
 * 允许前缀校验发生在"我们发出的第一个请求"上，若跟随重定向，
 * 一个允许域名下的 302 就能把请求带到别处，前缀校验被绕过。</p>
 */
@Component
public class HttpOssPublicKeyProvider implements OssPublicKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(HttpOssPublicKeyProvider.class);

    private final OssCallbackProperties properties;
    private final HttpClient httpClient;

    /** 地址 → 缓存项。用 {@link ConcurrentHashMap} 而不是 synchronized：验签在请求线程上执行。 */
    private final Map<String, CachedKey> cache = new ConcurrentHashMap<>();

    public HttpOssPublicKeyProvider(OssCallbackProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.httpTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public String fetchPem(String publicKeyUrl) {
        if (publicKeyUrl == null || publicKeyUrl.isBlank()) {
            throw new IllegalStateException("公钥地址为空");
        }
        String url = publicKeyUrl.trim();
        if (!isAllowed(url)) {
            // 这条日志值得被看见：它意味着有人在尝试 SSRF，或公钥地址换了域名（官方改域名）
            throw new IllegalStateException("公钥地址不在允许范围内：" + url
                    + "（允许前缀：" + properties.allowedPublicKeyUrlPrefixes() + "）");
        }

        CachedKey cached = cache.get(url);
        if (cached != null && !cached.isExpired()) {
            return cached.pem;
        }

        String pem = download(url);
        cache.put(url, new CachedKey(pem, Instant.now().plus(properties.publicKeyCacheTtl())));
        log.info("已下载并缓存 OSS 回调公钥：{}（长度 {}）", url, pem.length());
        return pem;
    }

    /** 前缀白名单校验（见类注释：这是安全边界）。 */
    private boolean isAllowed(String url) {
        return properties.allowedPublicKeyUrlPrefixes().stream().anyMatch(url::startsWith);
    }

    /** 下载 PEM。任何失败都抛异常，由验签器翻译成"验签不通过"。 */
    private String download(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(properties.httpTimeout())
                    .GET()
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("下载公钥失败，HTTP " + response.statusCode());
            }
            return new String(response.body(), StandardCharsets.UTF_8);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("下载公钥被中断", ex);
        } catch (java.io.IOException | RuntimeException ex) {
            throw new IllegalStateException("下载公钥失败：" + ex.getMessage(), ex);
        }
    }

    /** 缓存项：缓存的是<b>不可变</b>的 PEM 文本与到期时刻。 */
    private record CachedKey(String pem, Instant expiresAt) {

        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    /** 仅供测试与排障：当前缓存了几个公钥地址。 */
    public int cachedKeyCount() {
        return cache.size();
    }

    /** 仅供测试与排障：清缓存（例如官方轮换公钥后手动刷新）。 */
    public void clearCache() {
        cache.clear();
    }

    /** 取公钥的超时（供日志说明，避免排障时再翻配置）。 */
    public Duration timeout() {
        return properties.httpTimeout();
    }
}
