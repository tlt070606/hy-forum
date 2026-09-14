package com.hyforum.auth;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 注销后旧 token 必须失效（补充用例，L1 于 2026-09-14 加）。
 *
 * <p><b>为什么单独补这一条</b>：M1 交付时 {@code /api/auth/logout} 只断言了 {@code code=0}
 * ——「注销请求成功返回」与「注销真的让登录态失效」是两回事：一个什么都没做的实现
 * 也能返回 {@code code=0}。安全相关行为<b>没验证过就等于没实现</b>，
 * 所以这条补测是必需的，不是锦上添花。</p>
 *
 * <p>对应交接事项 H7（见 {@code docs/agents/工作计划.md} §6.5）。</p>
 *
 * <p><b>双向断言</b>（这是本用例的重点）：</p>
 * <ol>
 *   <li>注销<b>前</b>同一 token 必须可用 —— 否则后面那次 401 可能只是因为
 *       "这个 token 从来就没生效过"，那样用例会<b>假绿</b>；</li>
 *   <li>注销<b>后</b>同一 token 必须 401 —— 这才是被验证的行为。</li>
 * </ol>
 */
@DisplayName("M1 · 注销使 token 失效（H7 补测）")
class M1LogoutTest extends AuthApiTestSupport {

    @Test
    void M1_logout_invalidates_token() {
        String username = uniqueUsername("lgout");
        String nickname = "注销用例用户";

        // 先注册再登录，拿到一个真实 token
        Response register = registerWithFreshCaptcha(username, nickname);
        assertThat(register.jsonPath().getInt("code"))
                .as("前置：注册应成功：%s", register.asString()).isZero();

        Response login = RestAssured.given()
                .contentType(ContentType.JSON)
                .body(loginBody(username, VALID_PASSWORD))
                .post("/api/auth/login");
        assertThat(login.jsonPath().getInt("code"))
                .as("前置：登录应成功：%s", login.asString()).isZero();
        String token = login.jsonPath().getString("data.token");
        assertThat(token).as("登录必须返回 token").isNotBlank();

        // ---------- ① 注销前：token 必须可用 ----------
        // 这一步是"反证"。少了它，②③ 的 401 无法说明任何问题 ——
        // 一个压根不签发可用 token 的实现也能让它们全绿。
        Response before = RestAssured.given()
                .header("Authorization", token)
                .get("/api/user/me");
        assertThat(before.statusCode())
                .as("注销前 token 必须可用，否则本用例无法证明任何事：%s", before.asString())
                .isEqualTo(200);
        assertThat(before.jsonPath().getInt("code")).isZero();
        assertThat(before.jsonPath().getString("data.username"))
                .as("返回的应是当前登录用户").isEqualTo(username);

        // ---------- ② 注销 ----------
        Response logout = RestAssured.given()
                .header("Authorization", token)
                .post("/api/auth/logout");
        assertThat(logout.jsonPath().getInt("code"))
                .as("注销应返回 code=0：%s", logout.asString()).isZero();

        // ---------- ③ 注销后：同一个 token 必须失效（HTTP 与响应体都要是 401）----------
        // 两者都断言：技术方案 §6.1 规定错误码 401=未登录，
        // 而 REST 语义要求 HTTP 状态码也是 401；只查其中一个会漏掉"另一个写错"的情况。
        Response after = RestAssured.given()
                .header("Authorization", token)
                .get("/api/user/me");
        assertThat(after.statusCode())
                .as("注销后旧 token 必须返回 HTTP 401，实际：%s", after.asString())
                .isEqualTo(401);
        assertThat(after.jsonPath().getInt("code"))
                .as("注销后旧 token 的响应体 code 必须是 401（§6.1）：%s", after.asString())
                .isEqualTo(401);

        // ---------- ④ 再次用旧 token 注销：不得 500 ----------
        // 契约没规定"重复注销"的返回值，因此这里不做契约断言，
        // 只做一个**健壮性下界**：任何情况下都不许崩成 500。
        // 写成"不得 500"而不是"必须 code=0"，是因为后者是在替契约做它没做的承诺。
        Response secondLogout = RestAssured.given()
                .header("Authorization", token)
                .post("/api/auth/logout");
        assertThat(secondLogout.statusCode())
                .as("重复注销不得 500（响应：%s）", secondLogout.asString())
                .isNotEqualTo(500);
    }
}
