package com.hyforum.media;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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

        // ---------- ⑦ 回调体模板必须能拼出**合法 JSON**（真实回调踩过的坑）----------
        // OSS 替换 ${...} 时：字符串变量**自带 JSON 引号**，数值变量不带。
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
