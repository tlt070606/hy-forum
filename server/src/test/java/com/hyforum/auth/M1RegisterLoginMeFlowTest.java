package com.hyforum.auth;

import com.hyforum.auth.support.CaptchaTestSupport;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M1 核心链路：注册 → 登录 → {@code GET /api/user/me}。
 *
 * <p>对应验收项：{@code M1_register_login_me_flow}（PLAN §4 的 M1 验收标准原文：
 * 「Knife4j 中可完成注册 → 登录 → 获取 /api/user/me」）。
 * 该链路是 M1 的对外承诺，也是前端 M2 唯一依赖的后端能力，因此单独成一个类，
 * 保证它在任何重构后都最先被发现是否被破坏。</p>
 */
@DisplayName("M1 · 注册登录主链路")
class M1RegisterLoginMeFlowTest extends AuthApiTestSupport {

    @Test
    void M1_register_login_me_flow() {
        String username = "flow_user";
        String nickname = "主链路用户";

        // ---------- ① 取验证码（自动化测试无法识别图片，从 Redis 取正确答案） ----------
        CaptchaTestSupport.Issued issued = captcha.issue();

        // ---------- ② 注册 ----------
        Response register = io.restassured.RestAssured.given()
                .contentType(io.restassured.http.ContentType.JSON)
                .body(registerBody(username, VALID_PASSWORD, nickname, issued.uuid(), issued.answer(), true))
                .post("/api/auth/register");

        assertThat(register.statusCode()).as("注册接口 HTTP 状态：%s", register.asString()).isEqualTo(200);
        assertThat(register.jsonPath().getInt("code")).as("注册返回 code：%s", register.asString()).isZero();
        assertThat(register.jsonPath().getString("data.username")).isEqualTo(username);
        assertThat(register.jsonPath().getString("data.nickname")).isEqualTo(nickname);
        Long userId = register.jsonPath().getLong("data.id");
        assertThat(userId).as("注册必须返回用户 id").isNotNull().isPositive();

        // ---------- ③ 登录 ----------
        Response login = io.restassured.RestAssured.given()
                .contentType(io.restassured.http.ContentType.JSON)
                .body(loginBody(username, VALID_PASSWORD))
                .post("/api/auth/login");

        assertThat(login.statusCode()).as("登录接口 HTTP 状态：%s", login.asString()).isEqualTo(200);
        assertThat(login.jsonPath().getInt("code")).as("登录返回 code：%s", login.asString()).isZero();
        String token = login.jsonPath().getString("data.token");
        assertThat(token).as("登录必须下发 token").isNotBlank();
        assertThat(login.jsonPath().getLong("data.user.id")).isEqualTo(userId);

        // ---------- ④ 携带 token 取 /api/user/me ----------
        Response me = io.restassured.RestAssured.given()
                .header("Authorization", token)
                .get("/api/user/me");

        assertThat(me.statusCode()).as("/api/user/me HTTP 状态：%s", me.asString()).isEqualTo(200);
        assertThat(me.jsonPath().getInt("code")).as("/api/user/me 返回 code：%s", me.asString()).isZero();
        assertThat(me.jsonPath().getLong("data.id")).as("/api/user/me 必须是当前登录人").isEqualTo(userId);
        assertThat(me.jsonPath().getString("data.username")).isEqualTo(username);
    }
}
