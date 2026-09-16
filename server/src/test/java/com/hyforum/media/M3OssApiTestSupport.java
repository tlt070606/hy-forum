package com.hyforum.media;

import com.hyforum.media.support.OssCallbackTestSupport;
import com.hyforum.post.M3ApiTestSupport;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * M3 第二交付段（media/OSS）接口测试的公共父类。
 *
 * <p>它<b>继承第一交付段的 {@link M3ApiTestSupport}</b>（同一模块、同一套造数与清场），
 * 而不是另写一份：造用户/版块、清 {@code post_image}／{@code post}／{@code board}／{@code user}、
 * 清 Redis 键这些事两边完全一样，抄一份就是第二份会漂移的事实。</p>
 *
 * <h2>两个动态属性（为什么用 {@code @DynamicPropertySource} 而不是写死在注解里）</h2>
 * <ol>
 *   <li>{@code hy.oss.callback.allowed-public-key-url-prefixes[0]} —— 指向**本地公钥桩**。
 *       生产默认是 {@code gosspublic.alicdn.com}（官方要求公钥地址必须来自该域名），
 *       测试把它收窄成 {@code http://127.0.0.1:随机端口/}，于是
 *       <b>真实取公钥 + 真实验签的代码路径被完整跑通，且不依赖外网、不依赖真实 OSS</b>。
 *       随机端口只能在运行时拿到，所以必须动态注册。</li>
 *   <li>{@code hy.oss.upload.signature-ttl-seconds} —— 显式写死成 600，
 *       让断言的是"契约值真的被配置进来了"，而不是"代码里碰巧写了个默认值"。</li>
 * </ol>
 */
public abstract class M3OssApiTestSupport extends M3ApiTestSupport {

    /** 签名有效期（测试显式声明，与断言配套）。 */
    protected static final int SIGNATURE_TTL_SECONDS = 600;

    /** 回调允许的图片类型（§8.4 的白名单）。 */
    protected static final String IMAGE_CONTENT_TYPE = "image/jpeg";

    /** 单图大小上限（§8.4：单图 ≤ 5MB）。 */
    protected static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;

    /** 测试用的 OSS 密钥（来自 {@code application-test.yml} 的**测试占位值**，不是真实密钥）。 */
    @Value("${aliyun.oss.access-key-secret:}")
    protected String ossAccessKeySecret;

    @DynamicPropertySource
    static void ossTestProperties(DynamicPropertyRegistry registry) {
        registry.add("hy.oss.callback.allowed-public-key-url-prefixes[0]",
                OssCallbackTestSupport::allowedPrefix);
        registry.add("hy.oss.upload.signature-ttl-seconds", () -> SIGNATURE_TTL_SECONDS);
    }

    // ==================================================================
    // 请求动作
    // ==================================================================

    /** 取直传签名（带 token）。 */
    protected Response getSignature(String token) {
        return RestAssured.given()
                .header("Authorization", token)
                .get("/api/oss/signature");
    }

    /** 取直传签名（匿名，用于断言 401）。 */
    protected Response getSignatureAnonymously() {
        return RestAssured.given().get("/api/oss/signature");
    }

    /**
     * 模拟 OSS 发起回调。
     *
     * <p>用原始字节体（{@code Content-Type: application/json}）而不是序列化的对象：
     * 验签算的是<b>请求体原文</b>，任何一次"先反序列化再序列化"都会让待签名字符串变化 ——
     * 真实 OSS 回调也必须按原文验签，所以测试必须走同一形态。</p>
     *
     * @param body          回调消息体
     * @param authorization {@code Authorization} 头（Base64 签名）
     * @param pubKeyUrl     {@code x-oss-pub-key-url} 头（Base64 的公钥地址）
     */
    protected Response postRawCallback(byte[] body, String authorization, String pubKeyUrl) {
        return RestAssured.given()
                .contentType(ContentType.JSON)
                .header("Authorization", authorization)
                .header("x-oss-pub-key-url", pubKeyUrl)
                .body(body)
                .post("/api/oss/callback");
    }

    /** 该 URL 对应的 post_image 行（没有则返回 null）。 */
    protected java.util.Map<String, Object> findImageRowByUrl(String url) {
        java.util.List<java.util.Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, post_id, url, thumb_url, sort, audit_status FROM post_image WHERE url = ?", url);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 发一次**签名正确**的回调（内容由参数决定）。
     *
     * <p>用于验证"验签通过但内容不合规"这类分支：签名是对的，所以能走到内容校验；
     * 若这些分支不生效，非法内容就会真的落库 —— 这正是要断言的地方。</p>
     */
    protected Response postSignedCallback(String objectKey, String mimeType, long size) {
        byte[] body = com.hyforum.media.support.OssCallbackTestSupport.callbackBody(
                objectKey, mimeType, size, "etag-content-check");
        return postRawCallback(body,
                com.hyforum.media.support.OssCallbackTestSupport.sign("/api/oss/callback", null, body),
                com.hyforum.media.support.OssCallbackTestSupport.pubKeyUrlHeader());
    }

    /** 库里 post_image 的总行数（验签失败时用它断言"没有多出一行"）。 */
    protected int countAllImages() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM post_image", Integer.class);
        return count == null ? 0 : count;
    }

    /** 把后端下发的 host + object key 拼成完整图片 URL（与后端的拼接口径一致）。 */
    protected String imageUrlOf(String host, String objectKey) {
        return host + "/" + objectKey;
    }
}
