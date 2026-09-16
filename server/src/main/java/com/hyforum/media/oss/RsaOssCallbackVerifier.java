package com.hyforum.media.oss;

import com.hyforum.media.config.OssCallbackProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

/**
 * 回调验签的默认实现：<b>RSA + MD5</b>，与阿里云官方文档《callback》「验证请求签名确保安全」
 * 一节逐字对齐（本类注释里的公式就是文档原文）。
 *
 * <pre>
 * 待签名字符串：sign_str   = url_decode(path) + query_string + '\n' + body
 * 签名（OSS 侧）：authorization = base64(rsa_sign(private_key, sign_str, md5))
 * 验签（本类）：  rsa_verify(public_key, md5(sign_str), base64_decode(authorization))
 * 公钥：          x-oss-pub-key-url 头 Base64 解码 → 公钥地址（必须在允许前缀内）→ 下载 PEM
 * </pre>
 *
 * <h2>本类为什么是"最硬的一块"（任务书 §6.2 的 ★）</h2>
 * <p>如果验签器写错方向（例如漏了 MD5、或忘记把查询串的 {@code ?} 拼上），
 * 现象是<b>所有真实回调都被拒</b>；而"伪造签名被拒"那条用例照样绿 ——
 * 于是一整套测试全绿，功能全废。因此：</p>
 * <ul>
 *   <li>算法按官方文档实现，公式与来源写在注释里；</li>
 *   <li>测试用本地 RSA 密钥对 + 本地公钥桩走<b>正向路径</b>（真实验签通过 → 真的落库）；</li>
 *   <li>失败原因进日志（{@link OssVerifyResult#reason()}），不进响应体。</li>
 * </ul>
 *
 * <h2>四个刻意的实现取舍</h2>
 * <ol>
 *   <li><b>算法固定 MD5withRSA，不做"多种算法都试一遍"</b>：验签器接受多种算法等于降低攻击成本
 *       （算法降级）。官方用的就是 MD5+RSA，多试没有收益。</li>
 *   <li><b>公钥只从允许前缀下载</b>：{@code x-oss-pub-key-url} 是请求方给的，
 *       不校验就能被用于 SSRF，更致命的是"用自己的公钥+私钥签名即可通过"。</li>
 *   <li><b>防重放窗口（默认 15 分钟）</b>：验签只证明"签名来自持钥方"，
 *       不证明"是刚发的"。{@code Date} 头解析失败<b>不算失败</b>（只记 WARN）——
 *       因为把"时间格式不认识"当成"签名无效"，会让一次时区/格式差异变成全链路故障。</li>
 *   <li><b>异常一律转成"验签不通过"</b>：验签路径上不允许异常冒到全局处理器变成 500 ——
 *       500 会让 OSS 认为"服务器出错"并重试，而真相是请求不可信。</li>
 * </ol>
 */
@Component
public class RsaOssCallbackVerifier implements OssCallbackVerifier {

    private static final Logger log = LoggerFactory.getLogger(RsaOssCallbackVerifier.class);

    /** 官方文档指定的签名算法：RSA + MD5 哈希。 */
    private static final String SIGNATURE_ALGORITHM = "MD5withRSA";

    private final OssCallbackProperties properties;
    private final OssPublicKeyProvider publicKeyProvider;

    public RsaOssCallbackVerifier(OssCallbackProperties properties, OssPublicKeyProvider publicKeyProvider) {
        this.properties = properties;
        this.publicKeyProvider = publicKeyProvider;
    }

    @Override
    public OssVerifyResult verify(OssCallbackRequest request) {
        // ---------- ① 头是否齐（缺 Authorization 是最常见的"伪造"形态） ----------
        if (request.authorizationHeader() == null || request.authorizationHeader().isBlank()) {
            return OssVerifyResult.fail("缺少 Authorization 头");
        }
        if (request.pubKeyUrlHeader() == null || request.pubKeyUrlHeader().isBlank()) {
            return OssVerifyResult.fail("缺少 x-oss-pub-key-url 头");
        }

        // ---------- ② 公钥地址：Base64 解码 → 允许前缀校验 → 下载（缓存） ----------
        String publicKeyUrl;
        try {
            publicKeyUrl = new String(Base64.getDecoder().decode(request.pubKeyUrlHeader()),
                    StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return OssVerifyResult.fail("x-oss-pub-key-url 不是合法 Base64");
        }
        String pem;
        try {
            pem = publicKeyProvider.fetchPem(publicKeyUrl);
        } catch (RuntimeException ex) {
            // 含"不在允许范围内""下载失败""超时"三类
            return OssVerifyResult.fail("取公钥失败：" + ex.getMessage());
        }

        // ---------- ③ 防重放：Date 头（解析不了只警告，不判失败，见类注释） ----------
        OssVerifyResult freshness = checkFreshness(request.dateHeader());
        if (!freshness.passed()) {
            return freshness;
        }

        // ---------- ④ 验签本体：rsa_verify(public_key, md5(sign_str), signature) ----------
        try {
            PublicKey publicKey = parsePublicKey(pem);
            Signature signature = Signature.getInstance(SIGNATURE_ALGORITHM);
            signature.initVerify(publicKey);
            signature.update(request.stringToSign().getBytes(StandardCharsets.UTF_8));
            boolean matched = signature.verify(Base64.getDecoder().decode(request.authorizationHeader()));
            if (!matched) {
                return OssVerifyResult.fail("签名不匹配（可能被伪造，或待签名字符串的拼接口径与 OSS 不一致）");
            }
            return OssVerifyResult.pass();
        } catch (IllegalArgumentException ex) {
            return OssVerifyResult.fail("Authorization 不是合法 Base64");
        } catch (Exception ex) {
            // NoSuchAlgorithmException / InvalidKeyException / SignatureException 等：
            // 一律视为"验签不通过"，绝不冒成 500（见类注释取舍 4）
            return OssVerifyResult.fail("验签执行失败：" + ex.getClass().getSimpleName() + " " + ex.getMessage());
        }
    }

    /**
     * 解析 PEM 公钥（X.509 SubjectPublicKeyInfo，即 {@code -----BEGIN PUBLIC KEY-----}）。
     *
     * <p>官方公钥文件就是这个格式。若将来官方换成 PKCS#1（{@code BEGIN RSA PUBLIC KEY}），
     * 这里会明确报错而不是静默失败 —— 报错内容会指出"不是 X.509 格式"。</p>
     */
    private PublicKey parsePublicKey(String pem) throws Exception {
        String base64 = pem.replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64);
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    }

    /** {@code Date} 头的时效校验（RFC 1123）。 */
    private OssVerifyResult checkFreshness(String dateHeader) {
        if (dateHeader == null || dateHeader.isBlank()) {
            log.warn("回调缺少 Date 头，跳过防重放校验（签名仍然已验证）");
            return OssVerifyResult.pass();
        }
        try {
            Instant requestTime = ZonedDateTime.parse(dateHeader, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant();
            Duration age = Duration.between(requestTime, Instant.now()).abs();
            if (age.compareTo(properties.maxAge()) > 0) {
                return OssVerifyResult.fail("回调请求过旧（Date 与当前时间相差 " + age.toMinutes()
                        + " 分钟，超过允许的 " + properties.maxAge().toMinutes() + " 分钟）");
            }
            return OssVerifyResult.pass();
        } catch (RuntimeException ex) {
            // 见类注释取舍 3：格式不认识不等于签名无效
            log.warn("回调 Date 头无法解析，跳过防重放校验：{}", dateHeader);
            return OssVerifyResult.pass();
        }
    }
}
