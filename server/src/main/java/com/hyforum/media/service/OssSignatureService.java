package com.hyforum.media.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyforum.common.oss.OssProperties;
import com.hyforum.media.config.OssCallbackUrlResolver;
import com.hyforum.media.config.OssCredentialProperties;
import com.hyforum.media.config.OssUploadProperties;
import com.hyforum.media.vo.OssSignatureVO;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 直传签名服务（docs/技术方案.md §6.8／§8.4；ADR-0007 的落地）。
 *
 * <h2>为什么是 PostObject（表单直传）而不是预签名 URL</h2>
 * <p>{@code OSS配置清单.md} §2 已写明：服务端签名直传用的是 <b>PostObject</b>。
 * 两者的区别不是口味问题 —— PostObject 的 policy 可以在<b>服务端</b>把
 * "只能传到 {@code post/} 目录、单文件 ≤ 5MB、只允许 image/*"这些约束写死，
 * OSS 会替我们执行；预签名 URL 只能约束"某一个对象路径"。</p>
 *
 * <h2>policy 里三条约束都是必须的（不是可选的加固）</h2>
 * <ol>
 *   <li>{@code starts-with $key post/}：没有它，拿到签名的用户可以往桶里任意位置写对象；</li>
 *   <li>{@code content-length-range 0..5MB}：没有它，一个 1GB 的文件也能用这份签名上传（§8.4 单图 ≤ 5MB）；</li>
 *   <li>{@code starts-with $Content-Type image/}：挡掉把桶当网盘用（上传 exe/zip）。</li>
 * </ol>
 * <p>这三条是"OSS 侧"的防线，与回调侧的二次校验（精确白名单 jpeg/png/webp/gif）
 * 构成纵深 —— 两侧都不依赖对方的正确性。</p>
 *
 * <h2>签名算法</h2>
 * <p>{@code signature = Base64(HMAC-SHA1(accessKeySecret, policy))}。
 * <b>Secret 只在服务端出现</b>：前端拿到的 policy + signature 无法反推密钥，
 * 也无法在有效期之外或目录之外使用（post 也读不到本类注入的密钥，
 * 见 {@code OssCredentialProperties} 的类注释）。</p>
 */
@Service
public class OssSignatureService {

    /** policy 到期时刻的格式：OSS 要求 ISO8601 UTC（带毫秒与 Z）。 */
    private static final DateTimeFormatter EXPIRATION_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    /** 单图大小上限（§8.4：5MB）。 */
    private static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;

    /** 回调端点路径（与 Controller 的映射一致；只在这里写一次）。 */
    private static final String CALLBACK_PATH = "/api/oss/callback";

    private final OssProperties ossProperties;
    private final OssCredentialProperties credentials;
    private final OssUploadProperties uploadProperties;
    private final OssCallbackUrlResolver callbackUrlResolver;
    private final ObjectMapper objectMapper;

    public OssSignatureService(OssProperties ossProperties,
                               OssCredentialProperties credentials,
                               OssUploadProperties uploadProperties,
                               OssCallbackUrlResolver callbackUrlResolver,
                               ObjectMapper objectMapper) {
        this.ossProperties = ossProperties;
        this.credentials = credentials;
        this.uploadProperties = uploadProperties;
        this.callbackUrlResolver = callbackUrlResolver;
        this.objectMapper = objectMapper;
    }

    /**
     * 签发一次直传签名（<b>帖子图片</b>，即历史上的默认行为）。
     *
     * @param request 当前 HTTP 请求（仅用于在未显式配置回调地址时推导公网回调地址）
     * @throws IllegalStateException OSS 未配置（前缀为空）—— fail-closed，不给出一份"看起来能用"的签名
     */
    public OssSignatureVO issueSignature(HttpServletRequest request) {
        return issueSignature(request, SignatureTarget.POST_IMAGE, null);
    }

    /**
     * 签发一次直传签名（§6.8 + §14 头像）。
     *
     * <p><b>{@code dir} 全部从 {@link OssProperties} 取，本方法里没有任何目录字面量</b>：
     * 帖子图走 {@code imageUrlPrefix()}、头像走 {@code userAvatarUrlPrefix(userId)}，
     * 两者都是"公网前缀 + 目录"的同一形状，因此 {@code dir = 前缀去头} 这一步可以共用。
     * 这正是 §14.2 ② 要的"同一事实一处映射"：签名下发的 {@code dir}
     * 与后端校验用的前缀**在构造上不可能不一致**。</p>
     *
     * @param target 签名用途（决定目录）
     * @param userId 头像签名时的用户 id；帖子图为 {@code null}
     */
    public OssSignatureVO issueSignature(HttpServletRequest request,
                                         SignatureTarget target,
                                         Long userId) {
        String publicPrefix = ossProperties.publicUrlPrefix();
        if (publicPrefix.isEmpty()) {
            // 与 post 侧的 fail-closed 同一口径：配置不全时不发签名，
            // 否则前端会拿到一份指向错误地址（或空域名）的签名，上传失败却看不出原因
            throw new IllegalStateException(
                    "OSS 未配置（endpoint / bucket 为空），无法签发直传签名；请检查 OSS_ENDPOINT 与 OSS_BUCKET");
        }
        String host = stripTrailingSlash(publicPrefix);
        // dir 由"允许前缀"反推，**不重复实现一遍归一化**：
        // 这样"签名的目录"与"后端校验的前缀"在构造上就不可能不一致（裁决 ① 的用意）
        String allowedPrefix = target == SignatureTarget.AVATAR
                ? ossProperties.userAvatarUrlPrefix(requireUserId(target, userId))
                : ossProperties.imageUrlPrefix();
        if (allowedPrefix.isEmpty() || !allowedPrefix.startsWith(publicPrefix)) {
            // 前缀异常（理论上不会发生：两个方法都从同一个 publicUrlPrefix 拼）——
            // 宁可拒绝发签名，也不要发一份目录与校验前缀不一致的签名
            throw new IllegalStateException("OSS 允许前缀异常，拒绝签发签名：" + allowedPrefix);
        }
        String dir = allowedPrefix.substring(publicPrefix.length());

        Instant expiration = Instant.now().plus(uploadProperties.signatureTtl());
        String policy = encodeBase64(policyJson(expiration, dir));
        String signature = encodeBase64(hmacSha1(credentials.accessKeySecret(), policy));

        return new OssSignatureVO(
                host,
                policy,
                signature,
                dir,
                expiration.getEpochSecond(),
                encodeBase64(callbackConfigJson(request)),
                // CR-F：PostObject 表单必须有 OSSAccessKeyId，否则前端无法完成直传。
                // 取值**只**来自配置（而配置只来自环境变量 OSS_ACCESS_KEY_ID）——
                // 这里刻意不写任何兜底/默认值：缺失时应用根本起不来（fail-fast），
                // 而不是发一份"看起来能用、传上去必失败"的签名。
                credentials.accessKeyId());
    }

    /**
     * 签名用途：决定目录（§14.2 ② 的"一处映射"）。
     *
     * <p>用枚举而不是裸字符串：{@code type=avatar} 这种字符串会在两个地方各拼一次
     * （controller 判一次、service 再判一次），而枚举把取值收在一处。</p>
     */
    public enum SignatureTarget {

        /** 帖子图片：目录 {@code post/}（{@code OssProperties.imageDir()}）。 */
        POST_IMAGE,

        /** 用户头像：目录 {@code avatar/{userId}/}（§14.2 ①）。 */
        AVATAR
    }

    private static long requireUserId(SignatureTarget target, Long userId) {
        if (target == SignatureTarget.AVATAR && (userId == null || userId <= 0)) {
            // 头像是**按用户分目录**的，没有 userId 就不知道签哪个目录 ——
            // 退化成扁平 avatar/ 会让"你只能用自己目录下的对象"这条校验失去依据
            throw new IllegalStateException("签头像目录必须知道用户 id，收到：" + userId);
        }
        return userId == null ? 0L : userId;
    }

    /** policy JSON：有效期 + 三条约束（见类注释）。 */
    private String policyJson(Instant expiration, String dir) {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("expiration", EXPIRATION_FORMAT.format(expiration));
        policy.put("conditions", List.of(
                // 只能写到本项目帖子图片目录下
                List.of("starts-with", "$key", dir),
                // 单文件大小上限（OSS 侧执行）
                List.of("content-length-range", 0, MAX_IMAGE_BYTES),
                // 只允许图片类型（§8.4 的 Content-Type 白名单在 OSS 侧先挡一层，
                // 精确白名单在回调侧再挡一次）
                List.of("starts-with", "$Content-Type", "image/"),
                // 只能写入本 bucket
                Map.of("bucket", ossProperties.bucketName())));
        return toJson(policy);
    }

    /** 回调配置 JSON：回调地址 + 回调体模板 + 回调体类型（前端原样作为 callback 表单字段）。 */
    private String callbackConfigJson(HttpServletRequest request) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("callbackUrl", callbackUrlResolver.resolve(request));
        config.put("callbackBody", uploadProperties.callbackBody());
        config.put("callbackBodyType", uploadProperties.callbackBodyType());
        return toJson(config);
    }

    // 回调地址的解析（含"环回地址打 WARN"的可见性要求）已抽到 OssCallbackUrlResolver：
    // 它同时被启动期告警使用，放在这里会让签名服务承担两件事（§12.5 任务 C）。

    /** Base64（标准字母表，带 padding）——policy 与 signature 都用它。 */
    private static String encodeBase64(byte[] raw) {
        return Base64.getEncoder().encodeToString(raw);
    }

    private static String encodeBase64(String text) {
        return encodeBase64(text.getBytes(StandardCharsets.UTF_8));
    }

    /** HMAC-SHA1（OSS PostObject 签名算法）。 */
    private static byte[] hmacSha1(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            // 算法缺失/密钥非法属于环境异常，必须显式暴露（静默返回空签名会让上传在 OSS 侧失败）
            throw new IllegalStateException("计算 OSS 签名失败：" + ex.getMessage(), ex);
        }
    }

    private String toJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("生成 policy/callback JSON 失败", ex);
        }
    }

    private static String stripTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
