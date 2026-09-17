package com.hyforum.media;

import com.hyforum.media.support.OssCallbackTestSupport;
import com.hyforum.post.M3ApiTestSupport;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

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

    /** 读时签名有效期（§12.2；测试显式声明，与断言配套）。 */
    protected static final int READ_URL_TTL_SECONDS = 3600;

    /** 测试用的 bucket 名（与 {@code @TestPropertySource} 里的 aliyun.oss.bucket-name 一致）。 */
    protected static final String TEST_BUCKET = "hy-forum-2026";

    /** 回调允许的图片类型（§8.4 的白名单）。 */
    protected static final String IMAGE_CONTENT_TYPE = "image/jpeg";

    /** 单图大小上限（§8.4：单图 ≤ 5MB）。 */
    protected static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;

    /** 测试用的 OSS 密钥（来自 {@code application-test.yml} 的**测试占位值**，不是真实密钥）。 */
    @Value("${aliyun.oss.access-key-secret:}")
    protected String ossAccessKeySecret;

    /**
     * 测试用的 AccessKey **标识**，走**独立的读取路径**（{@code @Value} 直读属性）而不是复用
     * 被测服务注入的那个属性对象。
     *
     * <p>这样断言才有意义：它证明 {@code accessKeyId} 是<b>从配置流到响应</b>的，
     * 而不是被测代码里某个常量/别的字段凑出来的（后者用同一个对象比较是察觉不到的）。</p>
     */
    @Value("${aliyun.oss.access-key-id:}")
    protected String ossAccessKeyIdFromConfig;

    /** 回调地址解析器（用于断言"环回地址会打 WARN"这条可见性要求，§12.5 任务 C）。 */
    @org.springframework.beans.factory.annotation.Autowired
    protected com.hyforum.media.config.OssCallbackUrlResolver callbackUrlResolver;

    /**
     * 断言响应报文里<b>没有泄漏 Secret</b>（CR-F 要求 1）。
     *
     * <p>口径选择（说明取舍）：要求是"secret 的任何子串"。逐字符长度的子串检查会疯狂误报
     * （1~2 个字符几乎必然出现在 base64 串里），因此这里检查 <b>Secret 的所有 6 字符以上连续子串</b>：
     * 6 字符的密钥片段已经足够构成泄漏，同时误报率可接受。</p>
     *
     * <p><b>失败信息刻意不打印命中的片段</b>：万一有人拿真实密钥跑测试，
     * 把片段打进日志/报告就等于再泄漏一次 —— 只报"命中长度"，值本身由人工去查。</p>
     */
    protected void assertNoSecretLeak(String responseBody) {
        assertThat(ossAccessKeySecret)
                .as("测试配置里必须有 secret 占位值（否则这条断言形同虚设）")
                .isNotBlank();
        assertThat(responseBody)
                .as("响应体不得包含 Secret 本体")
                .doesNotContain(ossAccessKeySecret);

        String secret = ossAccessKeySecret;
        int window = 6;
        for (int i = 0; i + window <= secret.length(); i++) {
            String fragment = secret.substring(i, i + window);
            if (responseBody.contains(fragment)) {
                throw new AssertionError(
                        "响应体里出现了 AccessKey Secret 的 " + window + " 字符连续片段（位置 " + i
                                + "）—— 密钥泄漏，必须立刻查 OssSignatureVO 的字段来源。"
                                + "（此处刻意不回显命中的片段，避免二次泄漏）");
            }
        }
    }

    @DynamicPropertySource
    static void ossTestProperties(DynamicPropertyRegistry registry) {
        registry.add("hy.oss.callback.allowed-public-key-url-prefixes[0]",
                OssCallbackTestSupport::allowedPrefix);
        registry.add("hy.oss.upload.signature-ttl-seconds", () -> SIGNATURE_TTL_SECONDS);
        // 读时签名有效期（§12.2）：显式写死，让断言的是"配置值真的生效"而不是代码兜底默认值
        registry.add("hy.oss.read-url.ttl-seconds", () -> READ_URL_TTL_SECONDS);
    }

    // ==================================================================
    // 读时签名（§12）相关的小工具
    // ==================================================================

    /** 读时签名会追加的三个查询参数名。 */
    protected static final java.util.Set<String> SIGNATURE_PARAM_NAMES =
            java.util.Set.of("OSSAccessKeyId", "Expires", "Signature");

    /**
     * 去掉读时签名参数（{@code OSSAccessKeyId}/{@code Expires}/{@code Signature}），
     * 保留其余查询参数（例如 {@code x-oss-process}）。
     *
     * <p><b>为什么"封面 = 首图缩略图"这类断言要比 base 而不是比整串</b>：签名带
     * {@code Expires}（epoch 秒），两次组装恰好跨过一秒就会得到不同的签名字符串 ——
     * 直接比较整串会让用例随机变红（本项目最忌讳的那种"偶发红"）。
     * 比 base 语义完全等价，且结果确定。</p>
     */
    protected static String bareUrl(String signedUrl) {
        if (signedUrl == null) {
            return null;
        }
        int q = signedUrl.indexOf('?');
        if (q < 0) {
            return signedUrl;
        }
        String base = signedUrl.substring(0, q);
        String kept = java.util.Arrays.stream(signedUrl.substring(q + 1).split("&"))
                .filter(p -> !p.isEmpty())
                .filter(p -> {
                    String name = p.contains("=") ? p.substring(0, p.indexOf('=')) : p;
                    return !SIGNATURE_PARAM_NAMES.contains(name);
                })
                .collect(java.util.stream.Collectors.joining("&"));
        return kept.isEmpty() ? base : base + "?" + kept;
    }

    /** 取某个查询参数的值（自动 URL 解码，因为正是签名参数）；不存在返回 null。 */
    protected static String queryParamOf(String url, String name) {
        if (url == null) {
            return null;
        }
        int q = url.indexOf('?');
        if (q < 0) {
            return null;
        }
        for (String pair : url.substring(q + 1).split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                return java.net.URLDecoder.decode(pair.substring(eq + 1),
                        java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    /** 断言某 URL 是"读时签名过的"（三个签名参数齐全）。 */
    protected void assertSigned(String url, String what) {
        assertThat(queryParamOf(url, "OSSAccessKeyId"))
                .as("%s 必须带 OSSAccessKeyId（桶是私有的，必须读时签名）：%s", what, url)
                .isNotBlank();
        assertThat(queryParamOf(url, "Expires")).as("%s 必须带 Expires：%s", what, url).isNotNull();
        assertThat(queryParamOf(url, "Signature")).as("%s 必须带 Signature：%s", what, url).isNotBlank();
    }

    /**
     * 按官方 SDK（{@code OSSV1Signer} + {@code SignUtils}）的口径**独立重算**一次 V1 签名。
     *
     * <pre>
     * expires               = URL 里的 Expires（epoch 秒）
     * canonicalString       = "GET\n" + "" + "\n" + "" + "\n" + expires + "\n" + CanonicalizedResource
     * CanonicalizedResource = "/{bucket}/{object}" + 排序后的**签名参数**（x-oss-process 在内、带值）
     * signature             = base64(hmacSHA1(secret, canonicalString))
     * </pre>
     *
     * <p><b>刻意在测试里重写一遍算法、而不是调用被测代码</b>：只有这样，
     * "先签名、再拼 x-oss-process"（子资源没进 CanonicalizedResource）这种实现才会被抓住 ——
     * 那种错误在真实 OSS 上的现象是"大图能看、缩略图 403"。</p>
     *
     * @param objectKeyWithParams 对象 key 连同它的子资源，例如 {@code post/x.png?x-oss-process=...}
     */
    protected String expectedV1Signature(String signedUrl, String objectKeyWithParams) {
        String expires = queryParamOf(signedUrl, "Expires");
        String canonicalResource = "/" + TEST_BUCKET + "/" + objectKeyWithParams;
        String canonicalString = "GET\n" + "\n" + "\n" + expires + "\n" + canonicalResource;
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA1");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    ossAccessKeySecret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA1"));
            return java.util.Base64.getEncoder().encodeToString(
                    mac.doFinal(canonicalString.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("重算 V1 签名失败", ex);
        }
    }

    /** 把某个 logger 的日志挂到内存 appender 上（用于断言 WARN 真的打了）。Logback 自带，无需新依赖。 */
    protected static CapturedLogs captureLogs(String loggerName) {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(loggerName);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return new CapturedLogs(logger, appender);
    }

    /** 日志捕获句柄（务必 try-with-resources）。 */
    protected record CapturedLogs(
            ch.qos.logback.classic.Logger logger,
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender)
            implements AutoCloseable {

        /** 是否出现过"级别=WARN 且消息含指定片段"的日志。 */
        boolean hasWarnContaining(String fragment) {
            return appender.list.stream().anyMatch(e ->
                    e.getLevel() == ch.qos.logback.classic.Level.WARN
                            && e.getFormattedMessage().contains(fragment));
        }

        @Override
        public void close() {
            logger.detachAppender(appender);
        }
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
     * 按任务书 §5.6 列出的**真实 OSS 回调请求头**发一次回调（正向路径用它，保真度更高）。
     *
     * <p>带上 {@code Date} 头还有一个副作用是好的：它把"防重放窗口"的<b>通过路径</b>
     * 也纳入了覆盖（此前那半边只剩"实现了但没断言"）。</p>
     *
     * <p>{@code Content-MD5} 只求真实（OSS 确实发它），<b>验签不依赖它</b> ——
     * 官方公式里没有它，加进来正好把"它不参与签名"这件事写在测试里。</p>
     */
    protected Response postOssStyleCallback(byte[] body, String authorization, String pubKeyUrl) {
        return RestAssured.given()
                .contentType(ContentType.JSON)
                .header("Authorization", authorization)
                .header("x-oss-pub-key-url", pubKeyUrl)
                .header("Content-MD5", md5Base64(body))
                .header("x-oss-bucket", "hy-forum-2026")
                .header("x-oss-signature-version", "1.0")
                .header("x-oss-tag", "CALLBACK")
                .header("User-Agent", "aliyun-oss-callback")
                .header("Date", java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
                        .format(java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)))
                .body(body)
                .post("/api/oss/callback");
    }

    /** Content-MD5（Base64 的 MD5），仅用于让测试请求与真实回调同形。 */
    private static String md5Base64(byte[] body) {
        try {
            return java.util.Base64.getEncoder()
                    .encodeToString(java.security.MessageDigest.getInstance("MD5").digest(body));
        } catch (Exception ex) {
            throw new IllegalStateException("计算 Content-MD5 失败", ex);
        }
    }

    /**
     * 带**指定 {@code Date} 头**发一次回调（用于打防重放窗口的拒绝分支）。
     *
     * <p>签名本身仍然正确 —— 这样唯一能让请求被拒的原因就是"请求过旧"，
     * 断言才有指向性。</p>
     *
     * @param dateHeader RFC 1123 格式的时间（如 30 分钟前）
     */
    protected Response postCallbackWithDate(byte[] body, String authorization, String pubKeyUrl,
                                            String dateHeader) {
        return RestAssured.given()
                .contentType(ContentType.JSON)
                .header("Authorization", authorization)
                .header("x-oss-pub-key-url", pubKeyUrl)
                .header("Date", dateHeader)
                .body(body)
                .post("/api/oss/callback");
    }

    /** 生成 RFC 1123 的 Date 头（UTC），偏移量为负数表示"过去"。 */
    protected static String rfc1123Date(java.time.temporal.TemporalAmount offset) {
        java.time.Instant instant = java.time.Instant.now().plus(offset);
        return java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
                .format(instant.atZone(java.time.ZoneOffset.UTC));
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
