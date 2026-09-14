package com.hyforum.auth;

import com.hyforum.auth.support.CaptchaTestSupport;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 图形验证码（docs/技术方案.md §6.2 与 §7 的 Redis Key 表）。
 *
 * <p>对应验收项：</p>
 * <ul>
 *   <li>{@code M1_captcha_wrong_returns_1003} —— 错误验证码返回 1003；</li>
 *   <li>{@code M1_captcha_ttl_is_300s} —— Redis 键 {@code hy:captcha:{uuid}} 的 TTL = 300s。</li>
 * </ul>
 *
 * <p>为什么 TTL 这条要单独测：验证码的有效期决定了两件事 —— 太短用户来不及输入，
 * 太长则自动化脚本有充足时间暴力破解（4 位字符集 31 个符号 ≈ 92 万种，配合限流才安全）。
 * 300s 是契约写死的数值，不是"实现里随手写的默认值"，因此必须<b>断言实际写入 Redis 的 TTL</b>，
 * 而不是断言某个配置常量等于 300。</p>
 */
@DisplayName("M1 · 图形验证码")
class M1CaptchaTest extends AuthApiTestSupport {

    @Test
    void M1_captcha_wrong_returns_1003() {
        CaptchaTestSupport.Issued issued = captcha.issue();

        // 构造一个一定错的答案：取正确答案后逐字符替换（保证与正确答案不同）
        String wrong = issued.answer().equals("AAAA") ? "BBBB" : "AAAA";

        Response response = io.restassured.RestAssured.given()
                .contentType(io.restassured.http.ContentType.JSON)
                .body(registerBody("captcha_wrong", VALID_PASSWORD, "验证码错误用户",
                        issued.uuid(), wrong, true))
                .post("/api/auth/register");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getInt("code"))
                .as("错误验证码必须返回 1003，实际响应：%s", response.asString())
                .isEqualTo(1003);

        // 反向断言：不能因为验证码错误就把用户建出来
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user WHERE username = ?", Integer.class, "captcha_wrong");
        assertThat(count).as("验证码不通过时不得落库").isZero();
    }

    @Test
    void M1_captcha_ttl_is_300s() {
        Response response = fetchCaptcha();
        assertThat(response.jsonPath().getInt("code")).isZero();

        String uuid = response.jsonPath().getString("data.uuid");
        String base64Image = response.jsonPath().getString("data.base64Image");
        assertThat(uuid).as("§6.2 要求返回 uuid").isNotBlank();
        assertThat(base64Image).as("§6.2 要求返回 base64Image").isNotBlank();

        // 键确实存在（说明答案写到了契约约定的位置）
        assertThat(captcha.hasCaptchaKey(uuid))
                .as("答案必须写在 Redis 键 hy:captcha:%s 上（技术方案 §7）", uuid)
                .isTrue();

        // 关键断言：TTL = 300s。允许 1 秒的读取延迟误差（Redis TTL 是秒级取整，
        // 生成与读取之间必然消耗零点几秒；写 TTL <= 300 且 > 295 才算真的"300s 级"）
        long ttl = captcha.ttlSeconds(uuid);
        assertThat(ttl)
                .as("hy:captcha:%s 的 TTL 必须为 300s（契约值），实际：%d", uuid, ttl)
                .isBetween(295L, 300L);
    }
}
