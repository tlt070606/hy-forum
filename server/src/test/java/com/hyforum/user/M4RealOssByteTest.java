package com.hyforum.user;

import com.hyforum.common.oss.OssProperties;
import com.hyforum.common.oss.OssThumbnailUrls;
import com.hyforum.common.oss.OssUrls;
import com.hyforum.support.WebIntegrationTestBase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 🔴 <b>缩略图的签名范围必须包含 {@code x-oss-process}</b>（CR-S）——
 * 并用<b>真实请求取回字节</b>来证明。
 *
 * <h2>这条用例为什么必须"真的取字节"</h2>
 * <p>CR-S 的缺陷形态是：URL 里 <b>OSSAccessKeyId / Expires / Signature 三个参数齐全</b>，
 * 但签名算的是"**不含** {@code x-oss-process}"的 CanonicalizedResource，
 * 而请求带上了它 → OSS 侧重算不一致 → <b>403 SignatureDoesNotMatch</b>。</p>
 * <p>于是"断言 URL 里有三个签名参数"这类检查（包括我之前那版护栏）**全部放过去了** ——
 * 它看起来完全正常。**只有真的把字节取回来**才能发现。
 * 这就是 L1 反复强调"验了链路的一半"的那个坑。</p>
 *
 * <h2>为什么用例自己上传对象</h2>
 * <p>签名只有在对象<b>真实存在</b>时才能区分"签名范围错（403）"与"对象不存在（404）"。
 * 本用例用真实凭据签一次 PostObject 表单、上传一个 1×1 PNG，
 * 因此它是**自包含**的（不依赖库里恰好有哪张图）。</p>
 *
 * <h2>凭据缺失时怎么办</h2>
 * <p>真实凭据在<b>用户级环境变量</b>（{@code OSS_ACCESS_KEY_ID} / {@code OSS_ACCESS_KEY_SECRET}）。
 * 若不存在则 {@code assumeTrue} 跳过 —— 但**跳过会打印原因**，
 * 而且校验"签名范围"的**离线**部分（见 {@code M4UnsignedOssUrlGuardTest}）始终会跑。
 * <b>不写成"仅在注释里说应该在真实环境验"</b>：要么真跑，要么显式跳过并说明。</p>
 */
class M4RealOssByteTest extends WebIntegrationTestBase {

    /**
     * 8×8 纯色 PNG（129 字节）—— <b>必须是真正合法的 PNG</b>。
     *
     * <p>这里踩过一次：第一版用的是从网上抄来的"1×1 透明 PNG"base64，
     * 结果 OSS 图片处理报 <b>{@code ImageDamage / The image file may be damaged.}</b>（HTTP 400）——
     * 签名完全没问题，是<b>测试 fixture 本身不是合法图片</b>。
     * 现象很容易被误读成"签名又坏了"，所以记在这里：
     * 这一串 base64 是用 System.Drawing 生成真 PNG 后导出的。</p>
     */
    private static final byte[] PNG_FIXTURE = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAgAAAAICAYAAADED76LAAAAAXNSR0IArs4c6QAAAARnQU1BAACxjwv8YQUAAAAJcEhZcwAADsMA"
            +             "AA7DAcdvqGQAAAAWSURBVChTY0iZ+vY/PsyALoCOh4cCAEZ5uUFEHeYSAAAAAElFTkSuQmCC");


    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Autowired
    private OssUrls ossUrls;

    @Autowired
    private OssProperties ossProperties;

    /**
     * 真实凭据的两个来源（按优先级）：
     * <ol>
     *   <li><b>环境变量</b> {@code OSS_ACCESS_KEY_ID} / {@code OSS_ACCESS_KEY_SECRET}
     *       —— CI 走这条（在那里它们是真的环境变量）；</li>
     *   <li><b>系统属性 {@code hy.real.oss.creds} 指向的 .properties 文件</b> —— 本机走这条。</li>
     * </ol>
     *
     * <h2>为什么本机需要第二条（这是"我三次误报本机没凭据"的真正原因）</h2>
     * <p>本机的这两个变量只存在于 <b>User 作用域</b>（注册表
     * {@code HKCU\Environment}），而 User 作用域的值**只对"之后新建的登录会话"生效** ——
     * 我这一轮 shell 的<b>进程级环境里根本没有它们</b>，Maven 起的 fork 自然也继承不到。
     * 所以"进程级查不到"与"本机确实有凭据"<b>同时为真</b>：
     * 我之前只查进程级就下结论"本机无凭据"，那是错的。</p>
     * <p>用<b>文件</b>而不是 {@code -D} 直接传密钥：{@code -D} 会把密钥写进进程命令行，
     * 在同机其它进程的进程表里可见。文件只落在工作区，且用后即删。</p>
     *
     * <p>文件格式（Java properties）：{@code OSS_ACCESS_KEY_ID=...} 与
     * {@code OSS_ACCESS_KEY_SECRET=...} 两行。</p>
     */
    private static String accessKeyId;
    private static String accessKeySecret;

    @BeforeAll
    static void readRealCredentials() {
        accessKeyId = System.getenv("OSS_ACCESS_KEY_ID");
        accessKeySecret = System.getenv("OSS_ACCESS_KEY_SECRET");

        if (isBlank(accessKeyId) || isBlank(accessKeySecret)) {
            String credsPath = System.getProperty("hy.real.oss.creds");
            if (!isBlank(credsPath)) {
                try (java.io.InputStream in = java.nio.file.Files.newInputStream(
                        java.nio.file.Path.of(credsPath))) {
                    java.util.Properties props = new java.util.Properties();
                    props.load(in);
                    accessKeyId = props.getProperty("OSS_ACCESS_KEY_ID");
                    accessKeySecret = props.getProperty("OSS_ACCESS_KEY_SECRET");
                } catch (Exception ex) {
                    System.out.println("[CR-S] 读取凭据文件失败：" + credsPath + " → " + ex);
                }
            }
        }

        if (isBlank(accessKeyId) || isBlank(accessKeySecret)) {
            System.out.println("[CR-S] 未检测到真实 OSS 凭据 → 跳过「真实取字节」验证。"
                    + "注意：本机的 OSS_ACCESS_KEY_ID 只在 **User 作用域**，"
                    + "进程级查不到（`GetEnvironmentVariable(...,'User')` 才读得到），"
                    + "因此本机跑要用 -Dhy.real.oss.creds=<临时 .properties>；"
                    + "只查进程级会误判成「本机无凭据」。");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @Override
    protected String[] tablesToClean() {
        return new String[]{"user"};
    }

    /**
     * 把真实凭据喂给 <b>Spring 上下文里的签名器</b>。
     *
     * <p>为什么必须这么做（实测踩到）：{@code application-test.yml} 里放的是
     * <b>占位凭据</b>（{@code TEST-KEYID-1234567890}），而签名器
     * （{@code OssCredentialProperties}）从 Spring 配置读密钥 ——
     * 于是即使环境里有真凭据，签出来的 URL 也用占位密钥 → <b>403</b>，
     * 看起来像"签名逻辑坏了"，其实是<b>配置来源不对</b>。
     * 实测确认：签出的 URL 里 {@code OSSAccessKeyId=TEST-KEYID-1234567890}。</p>
     *
     * <p>用 {@code @DynamicPropertySource} 而不是 {@code -D}：
     * {@code -D} 会把 <b>AccessKey Secret 写进进程命令行</b>，同机其它进程可见。
     * 这里从文件读、只在内存里传给 Spring，命令行里没有密钥。</p>
     */
    @org.springframework.test.context.DynamicPropertySource
    static void feedRealOssCredentials(
            org.springframework.test.context.DynamicPropertyRegistry registry) {
        readRealCredentials();
        if (accessKeyId != null && !accessKeyId.isBlank()) {
            registry.add("aliyun.oss.access-key-id", () -> accessKeyId);
            registry.add("aliyun.oss.access-key-secret", () -> accessKeySecret);
        }
    }

    // ==================================================================
    // 主线：缩略图签名必须覆盖 x-oss-process，且真的能取回字节
    // ==================================================================

    @Test
    @DisplayName("M4_thumbnails_are_signed_with_process_params：缩略图（带 x-oss-process）签名覆盖处理参数，真实 GET 必须 200 + image/*")
    void M4_thumbnails_are_signed_with_process_params() throws Exception {
        assumeTrue(hasRealCredentials(), "无真实 OSS 凭据，跳过取字节验证");

        String objectKey = uploadRealPng();
        String bareUrl = ossProperties.publicUrlPrefix() + objectKey;

        // ---------- ① 正确形状：derive（拼上 x-oss-process）→ sign ----------
        List<String> thumbs = ossUrls.thumbs(List.of(bareUrl), 1);
        assertThat(thumbs).as("必须产出 1 张缩略图").hasSize(1);
        String correct = thumbs.get(0);

        assertThat(correct)
                .as("缩略图 URL 必须带处理参数").contains("x-oss-process=");
        assertThat(correct)
                .as("签名参数必须齐全").contains("Signature=").contains("Expires=")
                .contains("OSSAccessKeyId=");

        HttpResponse<byte[]> ok = get(correct);
        System.out.println("[CR-S] 缩略图取回字节数=" + ok.body().length
                + "，Content-Type=" + ok.headers().firstValue("Content-Type").orElse("(无)"));
        assertThat(ok.statusCode())
                .as("**正确的缩略图 URL 必须真的能取回字节**（HTTP 200）。"
                        + "若这里是 403 SignatureDoesNotMatch，说明签名**没有覆盖 x-oss-process**"
                        + "（CR-S）。URL：%s", correct)
                .isEqualTo(200);
        assertThat(ok.headers().firstValue("Content-Type").orElse(""))
                .as("必须是图片类型。实际：%s", ok.headers().firstValue("Content-Type").orElse("(无)"))
                .startsWith("image/");
        assertThat(ok.body())
                .as("必须真的取回字节（非空）").isNotEmpty();

        // ---------- ② ★ 反证：把 x-oss-process 从签名范围里去掉 → 必须 403 ----------
        //    构造 CR-S 的**错误形状**：先签裸地址、再把处理参数拼上去。
        //    这证明本用例**真的在验签名范围**，而不是"只要 200 就算过"。
        String signedBare = ossUrls.sign(bareUrl);
        String brokenShape = signedBare + (signedBare.contains("?") ? "&" : "?")
                + OssThumbnailUrls.RESIZE_PARAM;

        HttpResponse<byte[]> broken = get(brokenShape);
        assertThat(broken.statusCode())
                .as("**反证**：签名只覆盖裸地址、却带上 x-oss-process 的 URL 必须被 OSS 拒绝（403）。"
                        + "若这里也是 200，说明本用例区分不出「签名范围对不对」，护栏就白加了。URL：%s",
                        brokenShape)
                .isEqualTo(403);
    }

    /**
     * 附加验证：不带任何处理参数的签名必须能取回字节（证明"签名本身是对的"，
     * 从而把"403"的原因**锁定在签名范围**上，而不是凭据或前缀）。
     */
    @Test
    @DisplayName("对照：同一对象不带 x-oss-process 的签名必须 200（把 403 的原因锁定在签名范围）")
    void bare_object_signed_works() throws Exception {
        assumeTrue(hasRealCredentials(), "无真实 OSS 凭据，跳过取字节验证");

        String objectKey = uploadRealPng();
        String signed = ossUrls.sign(ossProperties.publicUrlPrefix() + objectKey);

        HttpResponse<byte[]> response = get(signed);
        assertThat(response.statusCode())
                .as("裸对象（无处理参数）的签名必须可用 —— 否则 403 的原因就不是'签名范围'，"
                        + "而要先查凭据/前缀。URL：%s", signed)
                .isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .as("必须是图片类型").startsWith("image/");
    }

    // ==================================================================
    // 骨架
    // ==================================================================

    private static boolean hasRealCredentials() {
        return accessKeyId != null && !accessKeyId.isBlank()
                && accessKeySecret != null && !accessKeySecret.isBlank();
    }

    /**
     * 用**真实凭据**签一次 PostObject 表单并把 1×1 PNG 传上去，返回对象 key。
     *
     * <p>与前端直传走同一套 V1 口径（policy + HMAC-SHA1 签名 + 表单字段），
     * 只是不带 callback（头像/缩略图都不需要回调）。</p>
     */
    private static final String UPLOADED_KEYS_PREFIX = "post/";

    private String uploadRealPng() throws Exception {
        // 唯一 key，避免并发/重复运行时互相覆盖
        String key = UPLOADED_KEYS_PREFIX + "cr-s/"
                + java.util.UUID.randomUUID().toString().replace("-", "") + ".png";
        String host = ossProperties.publicUrlPrefix();
        host = host.endsWith("/") ? host.substring(0, host.length() - 1) : host;

        String expiration = java.time.Instant.now().plus(Duration.ofMinutes(10)).toString();
        String policyJson = "{\"expiration\":\"" + expiration + "\",\"conditions\":[[\"starts-with\",\"$key\",\""
                + UPLOADED_KEYS_PREFIX + "\"]]}";
        String policy = Base64.getEncoder().encodeToString(policyJson.getBytes(StandardCharsets.UTF_8));

        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA1");
        mac.init(new javax.crypto.spec.SecretKeySpec(
                accessKeySecret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        String signature = Base64.getEncoder().encodeToString(
                mac.doFinal(policy.getBytes(StandardCharsets.UTF_8)));

        String boundary = "----crS" + System.nanoTime();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeField(body, boundary, "key", key);
        writeField(body, boundary, "policy", policy);
        writeField(body, boundary, "OSSAccessKeyId", accessKeyId);
        writeField(body, boundary, "signature", signature);
        writeField(body, boundary, "success_action_status", "200");
        // 与签名器同口径：请求带 x-oss-process 时，PostObject 表单也必须显式带上该字段，
        // 否则 policy 的签名范围（canonical 含该参数）与实际请求不一致 —— 实测报 400 InvalidPolicyDocument
        writeField(body, boundary, "x-oss-process", "image/resize,m_fill,w_360,h_360/quality,q_80");
        // 文件字段
        body.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(("Content-Disposition: form-data; name=\"file\"; filename=\"cr-s.png\"\r\n")
                .getBytes(StandardCharsets.UTF_8));
        body.write("Content-Type: image/png\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        body.write(PNG_FIXTURE);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder(URI.create(host))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();

        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .as("直传必须成功（这一步用真实凭据；失败说明凭据/桶配置有问题，"
                        + "而不是签名范围问题）。body=%s", response.body())
                .isEqualTo(200);
        return key;
    }

    private static void writeField(ByteArrayOutputStream out, String boundary,
                                   String name, String value) throws Exception {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.write((value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private static HttpResponse<byte[]> get(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }
}
