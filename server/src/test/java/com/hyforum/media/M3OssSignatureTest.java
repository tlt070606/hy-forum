package com.hyforum.media;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OSS 直传签名（docs/技术方案.md §6.8、§8.4；ADR-0007）。
 *
 * <p>对应验收项 {@code M3_oss_signature_requires_login}（名字逐字取自任务书 §6.2）。</p>
 *
 * <h2>为什么"未登录 401"这一条必须带反证</h2>
 * <p>只断言 401 是<b>弱断言</b>：一个根本没实现的端点会返回 404，而一个"把 {@code /api/oss/**}
 * 整段拦掉"的写法也能让它变成 401。所以用例必须再断言：<b>同一个请求带上有效 token → 200
 * 且字段齐全</b>。有了这一步，"401"才真的说明"这个接口存在且要求登录"。</p>
 *
 * <h2>为什么还要验证签名本身</h2>
 * <p>"字段非空"同样不能说明签名是对的 —— 一个把 {@code signature} 写成随机串的实现也能过。
 * 因此这里<b>用测试侧独立重算一遍</b> {@code base64(HMAC-SHA1(secret, policy))} 并与响应比对：
 * 这是唯一能证明"后端真的用密钥签了这份 policy"的断言，
 * 而它同时也是前端能不能传上去的前提（签名错了，OSS 会直接拒收）。</p>
 */
@DisplayName("M3b · OSS 直传签名")
class M3OssSignatureTest extends M3OssApiTestSupport {

    @Test
    void M3_oss_signature_requires_login() throws Exception {
        // ---------- ① 未登录 → 401 ----------
        Response anonymous = getSignatureAnonymously();
        assertThat(anonymous.statusCode())
                .as("§6.8：签名接口需要登录，未登录必须 401。响应：%s", anonymous.asString())
                .isEqualTo(401);
        assertThat(anonymous.jsonPath().getInt("code")).isEqualTo(401);

        // ---------- ② 反证：登录后同一请求必须 200 且字段齐全 ----------
        TestUser user = createFreshUser();
        Response signed = getSignature(user.token());
        assertThat(signed.statusCode())
                .as("登录后必须能拿到签名（否则上面的 401 可能只是'接口不存在/被整体拦掉'）：%s",
                        signed.asString())
                .isEqualTo(200);
        assertThat(signed.jsonPath().getInt("code")).isZero();

        String host = signed.jsonPath().getString("data.host");
        String policy = signed.jsonPath().getString("data.policy");
        String signature = signed.jsonPath().getString("data.signature");
        String dir = signed.jsonPath().getString("data.dir");
        Object expire = signed.jsonPath().get("data.expire");
        String callback = signed.jsonPath().getString("data.callback");

        assertThat(host).as("§6.8 要求返回 host").isNotBlank();
        assertThat(policy).as("§6.8 要求返回 policy").isNotBlank();
        assertThat(signature).as("§6.8 要求返回 signature").isNotBlank();
        assertThat(dir).as("§6.8 要求返回 dir").isNotBlank();
        assertThat(expire).as("§6.8 要求返回 expire").isNotNull();
        assertThat(callback).as("§6.8 要求返回 callback").isNotBlank();

        // ---------- ③ 字段形态（L1 裁决 CR-B） ----------
        assertThat(host)
                .as("host 是 OSS 域名形态（虚拟主机风格），不带结尾斜杠")
                .isEqualTo("https://hy-forum-2026.oss-cn-beijing.aliyuncs.com");
        assertThat(dir)
                .as("dir 必须带尾斜杠，且与 OssProperties.imageDir 同源"
                        + "（否则前端拼出的 key 过不了后端的前缀校验）")
                .isEqualTo("post/");

        long now = Instant.now().getEpochSecond();
        assertThat(((Number) expire).longValue())
                .as("expire 是 epoch 秒（裁决 CR-B），应约等于 现在 + TTL(%d)", SIGNATURE_TTL_SECONDS)
                .isBetween(now + SIGNATURE_TTL_SECONDS - 15, now + SIGNATURE_TTL_SECONDS + 15);

        // ---------- ④ policy 内容：有效期 + key 前缀约束 ----------
        String policyJson = new String(Base64.getDecoder().decode(policy), StandardCharsets.UTF_8);
        assertThat(policyJson)
                .as("policy 必须含 expiration（Base64 解码后可见）")
                .contains("expiration");
        assertThat(policyJson)
                .as("policy 必须把 key 限制在 post/ 目录内，否则用户可以把图传到桶里任意位置")
                .contains("starts-with")
                .contains(dir);

        // ---------- ⑤ 签名必须真的对（重算 HMAC-SHA1） ----------
        assertThat(ossAccessKeySecret)
                .as("测试密钥必须来自 application-test.yml 的测试占位值（没读到说明配置没生效）")
                .isNotBlank();
        assertThat(signature)
                .as("signature 必须是 base64(HMAC-SHA1(secret, policy)) —— 用测试侧独立重算比对")
                .isEqualTo(hmacSha1Base64(ossAccessKeySecret, policy));

        // ---------- ⑥ callback：Base64 的回调配置 JSON（裁决 CR-C） ----------
        String callbackJson = new String(Base64.getDecoder().decode(callback), StandardCharsets.UTF_8);
        assertThat(callbackJson)
                .as("callback 必须是 Base64 的回调配置 JSON（含 callbackUrl / callbackBody / callbackBodyType）")
                .contains("callbackUrl")
                .contains("callbackBody")
                .contains("callbackBodyType");
        assertThat(callbackJson)
                .as("回调地址必须指向本项目的回调端点")
                .contains("/api/oss/callback");

        // ---------- ⑦ CR-F：accessKeyId（PostObject 表单必需）与"不得泄漏 Secret" ----------
        assertThat(ossAccessKeyIdFromConfig)
                .as("测试配置里必须提供 key id（否则下面的断言是空转）")
                .isNotBlank();
        assertThat(signed.jsonPath().getString("data.accessKeyId"))
                .as("CR-F：PostObject 表单必须有 OSSAccessKeyId，否则前端根本无法完成一次直传。响应：%s",
                        signed.asString())
                .isNotBlank()
                .as("它必须是**配置里的那个值**（独立读取路径比对：证明值是从配置流到响应，"
                        + "而不是被测代码里某个常量凑的）")
                .isEqualTo(ossAccessKeyIdFromConfig);
        assertThat(signed.jsonPath().getString("data.accessKeyId"))
                .as("它是标识不是密钥：必须与 Secret 不同（防止有人把 secret 填进这个字段）")
                .isNotEqualTo(ossAccessKeySecret);

        // Secret 绝不能出现在响应里：查 Secret 的所有 6 字符以上连续子串（见 assertNoSecretLeak 的取舍说明）
        assertNoSecretLeak(signed.asString());

        // ---------- ⑧ 回调体模板必须能拼出**合法 JSON**（真实回调踩过的坑）----------        // OSS 替换 ${...} 时：字符串变量**自带 JSON 引号**，数值变量不带。
        // 若模板自己又加了一层引号，真实回调体就会变成 {"object":""...""}，
        // 而 OSS 只会报 `CallbackFailed: Error status : 400` —— 完全看不出是 JSON 拼坏了。
        // 这条断言就是那次故障的回归守卫（证据见交付报告证据 #6 的原文）。
        Map<String, Object> callbackConfig = new ObjectMapper()
                .readValue(callbackJson, new TypeReference<Map<String, Object>>() {
                });
        String template = String.valueOf(callbackConfig.get("callbackBody"));
        String simulated = template
                .replace("${object}", "\"" + "post/2026/09/16/sim.jpg" + "\"")
                .replace("${bucket}", "\"hy-forum-2026\"")
                .replace("${size}", "12345")
                .replace("${mimeType}", "\"image/jpeg\"")
                .replace("${etag}", "\"etag-simulated\"");
        assertThat(simulated)
                .as("按 OSS 的替换规则拼出来的回调体必须恰好是合法 JSON（模板里不得再自带引号）")
                .isEqualTo("{\"object\":\"post/2026/09/16/sim.jpg\",\"bucket\":\"hy-forum-2026\","
                        + "\"size\":12345,\"mimeType\":\"image/jpeg\",\"etag\":\"etag-simulated\"}");
    }

    /**
     * 验收项（§12.5 任务 C）：{@code M3_oss_callback_url_loopback_warns}
     * —— 回调地址落在**环回地址**时必须打 WARN，把静默失败变成可见。
     *
     * <h2>为什么这条必须存在</h2>
     * <p>本机开发时回调地址从请求推导 → {@code http://127.0.0.1:8080/api/oss/callback}，
     * 而 OSS 在公网、永远够不到环回地址。现象是「<b>上传成功但没有图</b>」，
     * 而且<b>日志里一个错都没有</b>：OSS 那边回调失败、我们这边什么都没发生。
     * 这类静默失败最难查，所以要求"启动 + 首次签名各一条 WARN"。</p>
     *
     * <p>测试同时断言两件事：① WARN 真的打了（捕获 Logback 事件，不靠人眼）；
     * ② 那条环回地址<b>确实出现在下发给前端回调配置里</b> —— 也就是这个陷阱在本环境下真实存在，
     * 而不是一条安慰性的日志。</p>
     */
    @Test
    void M3_oss_callback_url_loopback_warns() {
        TestUser user = createFreshUser();
        // 告警是"只打一次"的（否则每个签名请求一行，等于没有信号）→ 先重置，断言才稳定
        callbackUrlResolver.resetLoopbackWarningState();

        try (CapturedLogs logs = captureLogs("com.hyforum.media")) {
            Response signed = getSignature(user.token());
            assertThat(signed.jsonPath().getInt("code"))
                    .as("签名必须成功（本轮只管告警，不管成功与否）：%s", signed.asString())
                    .isZero();

            assertThat(logs.hasWarnContaining("环回"))
                    .as("回调地址是环回地址时必须打一条 WARN（否则这个陷阱是静默的）：%s",
                            signed.asString())
                    .isTrue();
        }

        assertThat(callbackUrlResolver.loopbackWarningCount())
                .as("告警计数据实递增（只增，供排障观察）")
                .isGreaterThanOrEqualTo(1);

        // 复现陷阱本身：下发的 callback 配置里确实是环回地址（本机测试用 127.0.0.1）
        String callbackConfig = new String(Base64.getDecoder().decode(
                getSignature(user.token()).jsonPath().getString("data.callback")), StandardCharsets.UTF_8);
        assertThat(callbackConfig)
                .as("测试环境里回调地址就是环回地址 —— 这正是 OSS 够不到的那一种情况")
                .contains("127.0.0.1");

        // ---------- 反证：**非**环回地址不得打这条 WARN ----------
        // 缺了它，"永远告警"的实现照样绿 —— 而永远告警等于没有信号（L1 登记这个名字时点名要求）。
        // 做法：用 X-Forwarded-Host 把一个公网域名喂给解析器（它优先于请求自身的 host），
        // 于是推导出的回调地址不是环回地址 → 必须一条 loopback WARN 都不打。
        callbackUrlResolver.resetLoopbackWarningState();
        try (CapturedLogs logs = captureLogs("com.hyforum.media")) {
            Response forwarded = io.restassured.RestAssured.given()
                    .header("Authorization", user.token())
                    .header("X-Forwarded-Host", "forum.example.com")
                    .header("X-Forwarded-Proto", "https")
                    .get("/api/oss/signature");
            assertThat(forwarded.jsonPath().getInt("code")).isZero();

            String publicCallback = new String(Base64.getDecoder().decode(
                    forwarded.jsonPath().getString("data.callback")), StandardCharsets.UTF_8);
            assertThat(publicCallback)
                    .as("反证的前置条件：经过反代时回调地址必须是那个公网域名（否则本反证不成立）")
                    .contains("forum.example.com");

            assertThat(logs.hasWarnContaining("环回"))
                    .as("推导出的是公网地址时**不得**打环回告警（否则这条 WARN 就成了噪音）")
                    .isFalse();
        }
        assertThat(callbackUrlResolver.loopbackWarningCount())
                .as("非环回请求不得把告警计数加一")
                .isZero();
    }

    // ==================================================================
    // §14 头像签名：**不带 callback**（L1 裁决，2026-09-20）
    // ==================================================================

    /**
     * 头像签名里 <b>不得</b>出现 {@code callback}；帖子图签名里 <b>必须</b>有。
     *
     * <h2>这条用例在防什么（需求方实测撞到的缺陷）</h2>
     * <p>回调的唯一目的是给帖子图写 {@code post_image} 行并按 URL 认领；
     * 头像的 URL 是通过 {@code PUT /api/user/profile} 提交的，**没有待认领的行**。
     * 而 {@code OssCallbackService} 的目录校验只认 {@code post/}（放宽它是禁止的），
     * 于是带 callback 的头像上传会走成：</p>
     * <pre>
     *   对象已进桶 → OSS 发回调 → 回调服务判"不属于本站帖子图片目录" → 400
     *   → OSS 报 CallbackFailed → 前端显示【上传失败】，而对象其实已经传上去了
     * </pre>
     * <p>也就是「<b>上传成功、界面说失败</b>」。因此"给头像发 callback"不是多余，是<b>主动有害</b>。</p>
     *
     * <h2>为什么前半的"反证"不可省</h2>
     * <p>只断言"头像没有 callback"的话，<b>一个"两处都不给 callback"的实现也能通过</b> ——
     * 而那会把帖子图彻底打坏（没有回调就没有 {@code post_image} 行，
     * 发帖时图片会全部认领失败）。所以必须同时断言"帖子图仍然有"。</p>
     */
    @Test
    @DisplayName("M4_avatar_signature_has_no_callback：头像签名不带 callback；反证：帖子图签名必须有")
    void M4_avatar_signature_has_no_callback() {
        // 本类其它用例都是各自 createFreshUser（没有共享字段），这里沿用同一写法
        TestUser user = createFreshUser();

        // ---------- 头像签名：callback 必须缺失/为空 ----------
        Response avatar = RestAssured.given()
                .header("Authorization", user.token())
                .get("/api/oss/signature?target=avatar");
        assertThat(avatar.jsonPath().getInt("code"))
                .as("取头像签名必须成功。响应：%s", avatar.asString())
                .isZero();
        assertThat((Object) avatar.jsonPath().get("data.callback"))
                .as("**头像签名不得带 callback** —— 带了就会走成"
                        + "「对象已进桶 → 回调被判不属于帖子目录 → 400 → OSS 报 CallbackFailed "
                        + "→ 前端说上传失败，而对象其实已经传上去了」。"
                        + "响应：%s", avatar.asString())
                .isNull();
        // dir 必须是 avatar/{自己的 id}/ —— 不依赖 TestUser 的访问器，
        // 直接用接口返回的 dir 形状断言，并额外证明它**含自己的 id**
        String avatarDir = avatar.jsonPath().getString("data.dir");
        assertThat(avatarDir)
                .as("头像签名下发的 dir 必须是 avatar/ 目录（§14.2 ①）。实际：%s", avatarDir)
                .startsWith("avatar/")
                .endsWith("/");
        assertThat(avatarDir)
                .as("dir 必须**按当前用户分目录**（§14.2 ①：扁平 avatar/ 下 A 能用 B 上传的对象）。"
                        + "实际：%s", avatarDir)
                .matches("avatar/\\d+/");

        // ---------- ★ 反证：帖子图签名必须有 callback ----------
        // 缺了它，"两处都不给 callback"的实现照样绿 —— 而那会打坏发帖的图片认领
        Response post = getSignature(user.token());
        assertThat(post.jsonPath().getInt("code")).isZero();
        assertThat(post.jsonPath().getString("data.callback"))
                .as("**帖子图签名必须仍然带 callback**（反证）—— 否则本用例前半的'缺失'"
                        + "可能只是因为整个接口都不给 callback 了，而那样发帖的 post_image 行就没人写。"
                        + "响应：%s", post.asString())
                .isNotBlank();
        assertThat(post.jsonPath().getString("data.dir"))
                .as("帖子图签名下发的 dir 仍是 post/（本次改动不得影响它）")
                .isEqualTo("post/");

        // ---------- 非法 target → 400（不静默回落到 post）----------
        Response invalid = RestAssured.given()
                .header("Authorization", user.token())
                .get("/api/oss/signature?target=banner");
        assertThat(invalid.jsonPath().getInt("code"))
                .as("非法 target 必须 400 而不是静默回落到 post —— "
                        + "回落会让'前端拼错参数'表现为'头像传进了帖子目录'，"
                        + "而那个对象永远过不了头像归属校验（上传成功但头像设不上、日志无错）。"
                        + "响应：%s", invalid.asString())
                .isEqualTo(400);
    }

    /** HMAC-SHA1 → Base64（OSS PostObject 的 policy 签名算法）。 */
    private static String hmacSha1Base64(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("重算签名失败", ex);
        }
    }
}
