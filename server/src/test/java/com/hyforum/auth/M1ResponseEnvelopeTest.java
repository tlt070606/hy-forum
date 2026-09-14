package com.hyforum.auth;

import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 统一响应体与分页结构（docs/技术方案.md §6.1）。
 *
 * <p>对应验收项：{@code M1_response_envelope_shape}。</p>
 *
 * <p>为什么这个测试值钱：统一响应体是<b>所有</b>接口的公共约定，一旦某个接口漏了包装，
 * 前端就得写两套解析逻辑（先判 HTTP 状态、再判 code、还要 catch 解析异常）。
 * 这类"形状漂移"用代码评审很难发现，用一条断言就容易得多 ——
 * 它同时把错误响应也覆盖了：<b>失败时同样必须是统一响应体</b>，
 * 而不是 Spring 默认的 {@code {"timestamp":...,"status":404,"path":...}}。</p>
 */
@DisplayName("M1 · 统一响应与分页结构")
class M1ResponseEnvelopeTest extends AuthApiTestSupport {

    @Test
    void M1_response_envelope_shape() {
        // ---------- ① 成功响应：{code, message, data} ----------
        Response captcha = fetchCaptcha();
        assertThat(captcha.statusCode()).isEqualTo(200);
        Map<String, Object> successBody = captcha.jsonPath().getMap("$");
        assertThat(successBody)
                .as("成功响应必须且只能有 code/message/data 三个顶层字段，实际：%s", successBody.keySet())
                .containsOnlyKeys("code", "message", "data");
        assertThat(captcha.jsonPath().getInt("code")).isZero();
        assertThat(captcha.jsonPath().getString("message")).isEqualTo("ok");
        assertThat(captcha.jsonPath().getMap("data"))
                .as("§6.2 规定验证码返回 {uuid, base64Image}")
                .containsKeys("uuid", "base64Image");

        // ---------- ② 失败响应：同样是统一结构 ----------
        // 未登录访问需要登录的接口（§6.3 GET /api/user/me）
        Response unauthorized = io.restassured.RestAssured.given().get("/api/user/me");
        assertThat(unauthorized.statusCode()).as("未登录必须 401").isEqualTo(401);
        Map<String, Object> failBody = unauthorized.jsonPath().getMap("$");
        assertThat(failBody)
                .as("失败响应也必须是统一结构（不得漏出 Spring 默认错误体），实际：%s", failBody.keySet())
                .containsKeys("code", "message");
        assertThat(unauthorized.jsonPath().getInt("code")).isEqualTo(401);

        // ---------- ③ 业务失败（HTTP 200 + 非 0 code）同样是统一结构 ----------
        Response businessFail = io.restassured.RestAssured.given()
                .contentType(io.restassured.http.ContentType.JSON)
                .body(loginBody("no_such_user_", VALID_PASSWORD))
                .post("/api/auth/login");
        assertThat(businessFail.statusCode()).as("业务失败按契约仍返回 HTTP 200").isEqualTo(200);
        assertThat(businessFail.jsonPath().getMap("$"))
                .as("业务失败响应结构：%s", businessFail.asString())
                .containsKeys("code", "message");
        assertThat(businessFail.jsonPath().getInt("code")).isEqualTo(1002);
    }
}
