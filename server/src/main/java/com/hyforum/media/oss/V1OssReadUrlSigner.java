package com.hyforum.media.oss;

import com.hyforum.common.oss.OssProperties;
import com.hyforum.common.oss.OssReadUrlSigner;
import com.hyforum.media.config.OssCredentialProperties;
import com.hyforum.media.config.OssReadUrlProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@link OssReadUrlSigner} 的实现：<b>OSS Signature v1 的"在 URL 中包含签名"</b>。
 *
 * <h2>口径来源（不是我推导的，是官方 SDK 的原文）</h2>
 * <p>取自阿里云 Java SDK {@code com.aliyun.oss.internal.OSSV1Signer} + {@code SignUtils}：</p>
 * <pre>
 * expires               = 过期时刻的 epoch 秒
 * request.Header[Date]  = expires                       ← 预签名场景下 Expires 占"Date"这一行
 * canonicalString       = "GET\n" + "" + "\n" + "" + "\n" + expires + "\n" + CanonicalizedResource
 *                          （两行空的是 Content-MD5 与 Content-Type；没有署名头时为空串）
 * CanonicalizedResource = "/{bucket}/{object}"
 *                         + 对**每个签名参数**（按键名字典序、以 ?/& 连接）追加 "key" 或 "key=value"
 * signature             = base64(hmacSHA1(accessKeySecret, canonicalString))
 * 最终 URL              = 原 URL + OSSAccessKeyId=...&Expires=...&Signature=...
 * </pre>
 *
 * <h2>🔴 本类最容易写错的一处（§12.3 坑 1）</h2>
 * <p>缩略图 URL 是「裸 URL + {@code ?x-oss-process=...}」，而
 * <b>{@code x-oss-process} 是签名参数</b>（SDK 的 {@code SIGNED_PARAMTERS} 里就有它），
 * 因此它<b>必须进 CanonicalizedResource</b> —— 也就是必须签"已经带参数的完整 URL"。
 * 若写成"先签裸 URL、再把参数拼上去"，OSS 会用**不同的 canonical resource** 验签 → 403，
 * 而现象是「<b>详情页大图能看、缩略图 403</b>」，非常容易被误判成前端问题。
 * 本类的做法：解析出 URL 里已有的查询参数 → 把其中的签名参数（含值）按字典序拼进
 * CanonicalizedResource → 签名 → 最后才追加签名参数（签名参数自身不进 canonical resource，
 * 因为 SDK 也是在算完之后才 addParameter 的）。</p>
 *
 * <h2>另外两条纪律</h2>
 * <ul>
 *   <li><b>只签本项目 OSS 目录下的 URL</b>（§12.3 坑 3）：按 {@code OssProperties.imageUrlPrefix()}
 *       判前缀，外部 URL 原样返回 —— 否则等于给任意外链加上我们的签名参数（既无意义，
 *       也可能把"别人的域名"包装成"我们签过的"）。</li>
 *   <li><b>已签名的 URL 不再二次签名</b>：重复追加会让参数越滚越多、Expires 与 Signature 不配对。
 *       库里存的一律是裸 URL，所以这条主要是防御性检查。</li>
 * </ul>
 */
@Component
public class V1OssReadUrlSigner implements OssReadUrlSigner, com.hyforum.common.oss.AvatarUrlSigner {

    /**
     * OSS 的"签名参数"集合（只有这里面的查询参数才进 CanonicalizedResource）。
     *
     * <p>抄自官方 SDK {@code com.aliyun.oss.internal.SignParameters.SIGNED_PARAMTERS}
     * 与 {@code RequestParameters} 的常量值。刻意<b>全量</b>抄下来而不是只放 {@code x-oss-process}：
     * 将来若有人给 URL 加了别的子资源（如 {@code response-content-type}），
     * 也会被正确纳入签名，而不是"悄悄漏掉 → 403"。</p>
     */
    private static final Set<String> SIGNED_PARAMETERS = Set.of(
            "acl", "uploads", "location", "cors", "logging", "website", "referer", "lifecycle",
            "delete", "append", "tagging", "objectMeta", "uploadId", "partNumber", "security-token",
            "position", "response-cache-control", "response-content-disposition",
            "response-content-encoding", "response-content-language", "response-content-type",
            "response-expires", "x-oss-process", "x-oss-process-conf", "x-oss-image",
            "style", "styleName", "replication", "replicationProgress", "replicationLocation",
            "cname", "bucketInfo", "comp", "qos", "live", "status", "vod", "startTime", "endTime",
            "symlink", "stat", "udf", "udfName", "udfImage", "udfImageDesc", "udfApplication",
            "udfLog", "restore", "versions", "versioning", "versionId", "encryption", "policy",
            "requestPayment", "x-oss-traffic-limit", "qosInfo", "asyncFetch", "sequential",
            "x-oss-request-payer", "vpcip", "vip");

    private final OssProperties ossProperties;
    private final OssCredentialProperties credentials;
    private final OssReadUrlProperties readUrlProperties;

    public V1OssReadUrlSigner(OssProperties ossProperties,
                              OssCredentialProperties credentials,
                              OssReadUrlProperties readUrlProperties) {
        this.ossProperties = ossProperties;
        this.credentials = credentials;
        this.readUrlProperties = readUrlProperties;
    }

    @Override
    public String sign(String url) {
        return signWithin(ossProperties.imageUrlPrefix(), url);
    }

    /**
     * 头像的读时签名（CR-Q）：允许前缀换成 {@code avatar/{userId}/}。
     *
     * <p><b>为什么复用同一个私有方法，而不是抄一份签名算法</b>：
     * 签名串的构造（canonical resource、子资源排序、HMAC、参数拼接）是本项目已知的
     * 高坑区（§12.3 记了三个坑：{@code x-oss-process} 子资源、裸 URL vs 已签 URL、外部 URL）。
     * 抄一份等于把这三个坑复制一遍 —— 而踩中任何一个的表现都是
     * "URL 看起来带签名、但 GET 回 403"，**极难定位**（L1 自己的探针就误判过一次）。
     * 因此两个入口<b>只允许共享同一段实现</b>，差别仅在"允许前缀"。</p>
     */
    @Override
    public String sign(long userId, String url) {
        return signWithin(ossProperties.userAvatarUrlPrefix(userId), url);
    }

    /**
     * 在给定"允许前缀"内签名 —— <b>全项目读时签名的唯一实现</b>。
     *
     * @param allowedPrefix 只签落在这个前缀内的 URL；空串（OSS 未配置）时一律原样返回
     * @param url           裸对象 URL
     */
    private String signWithin(String allowedPrefix, String url) {
        if (url == null || url.isBlank() || isAlreadySigned(url)) {
            return url;
        }
        if (allowedPrefix.isEmpty() || !url.startsWith(allowedPrefix)) {
            // 见类注释纪律 1：外部 URL（或 OSS 未配置）原样返回
            return url;
        }
        String publicPrefix = ossProperties.publicUrlPrefix();
        // 对象 key = URL 去掉 "https://{bucket}.{endpoint}/" 前缀，并在 '?' 处截断
        String objectAndParams = url.substring(publicPrefix.length());
        int queryIndex = objectAndParams.indexOf('?');
        String objectKey = queryIndex < 0 ? objectAndParams : objectAndParams.substring(0, queryIndex);
        String query = queryIndex < 0 ? "" : objectAndParams.substring(queryIndex + 1);

        String expires = String.valueOf(Instant.now().plus(readUrlProperties.ttl()).getEpochSecond());
        String canonicalResource = "/" + ossProperties.bucketName() + "/" + objectKey
                + canonicalSubResources(query);
        // 与 SDK 的 buildCanonicalString 逐字对齐：GET + 空 Content-MD5 + 空 Content-Type + Expires
        String canonicalString = "GET\n\n\n" + expires + "\n" + canonicalResource;
        String signature = hmacSha1Base64(credentials.accessKeySecret(), canonicalString);

        String separator = query.isEmpty() ? "?" : "&";
        return url + separator
                + "OSSAccessKeyId=" + urlEncode(credentials.accessKeyId())
                + "&Expires=" + expires
                + "&Signature=" + urlEncode(signature);
    }

    /**
     * 把 URL 里的**签名参数**（按键名字典序、带值）拼进 CanonicalizedResource。
     *
     * <p>这就是 §12.3 坑 1 的正面实现：{@code ?x-oss-process=image/...} 会变成
     * {@code ?x-oss-process=image/...} 出现在 canonical resource 里，与 OSS 侧一致。</p>
     *
     * <p>值与 URL 里的原文保持一致（不做二次编码）：官方 SDK 也是拿参数的原值去拼，
     * 而 OSS 在验签时用的是解码后的值。</p>
     */
    private static String canonicalSubResources(String query) {
        if (query == null || query.isEmpty()) {
            return "";
        }
        List<String> signed = new ArrayList<>();
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String name = eq < 0 ? pair : pair.substring(0, eq);
            if (!SIGNED_PARAMETERS.contains(name)) {
                continue;
            }
            signed.add(eq < 0 ? name : pair);
        }
        if (signed.isEmpty()) {
            return "";
        }
        // 字典序（SDK 用 Arrays.sort 对参数名排序；这里按 "name=value" 排，name 唯一时结果一致）
        TreeSet<String> sorted = new TreeSet<>(signed);
        return "?" + String.join("&", sorted);
    }

    /** 是否已经带签名参数（避免二次签名）。 */
    private static boolean isAlreadySigned(String url) {
        int q = url.indexOf('?');
        if (q < 0) {
            return false;
        }
        return Arrays.stream(url.substring(q + 1).split("&"))
                .map(p -> p.contains("=") ? p.substring(0, p.indexOf('=')) : p)
                .anyMatch(name -> "Signature".equals(name) || "OSSAccessKeyId".equals(name));
    }

    private static String hmacSha1Base64(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return Base64.getEncoder().encodeToString(
                    mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("计算读时签名失败：" + ex.getMessage(), ex);
        }
    }

    private static String urlEncode(String value) {
        // base64 里含 + / =，必须编码后再放进查询串（OSS 会先解码再比对）
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
