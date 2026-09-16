package com.hyforum.common;

import com.hyforum.support.WebIntegrationTestBase;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 路径变量类型不匹配必须返回 <b>400</b>，而不是 500（交接事项 <b>H12</b>）。
 *
 * <p>对应验收项 {@code M1_path_variable_type_mismatch_returns_400}
 * —— 名字里的 {@code M1_} 前缀是刻意的：这是 <b>M1 交付物的既有缺陷</b>
 * （{@code GlobalExceptionHandler} 只做了 {@code Exception} 兜底，
 * 没单独处理 {@code MethodArgumentTypeMismatchException}），不是 M3 引入的。</p>
 *
 * <h2>为什么它值得一条独立用例</h2>
 * <p>{@code GET /api/posts/abc} 是"客户端把路径参数写错了"，却被报成
 * {@code 500 服务器内部错误}：前端会按 5xx 去重试/告警，而 <b>5xx 是监控口径里的异常信号</b> ——
 * 被这类必然发生的误用污染之后，真故障会被淹没在噪声里。
 * 修法是加一条异常分支，风险却在于它动的是<b>全局</b>异常处理，会影响所有端点。</p>
 *
 * <h2>两条反证（缺了它们，这条用例可以用"把什么都变成 400"来作弊）</h2>
 * <ul>
 *   <li>未知路径 {@code /api/nonexistent-xyz} 必须<b>仍是 404</b>（§6.1 的 404 语义）；</li>
 *   <li>类型合法但不存在的资源 {@code /api/posts/999999999} 必须<b>仍是 404</b>
 *       —— 不能把"资源不存在"也顺手变成 400。</li>
 * </ul>
 * <p>这两条基线在修复前就已实测可观察（L1 于 2026-09-16 实测，本用例把它固化下来）。</p>
 */
@DisplayName("M1 · 路径变量类型不匹配 → 400（H12）")
class M1PathVariableTypeMismatchTest extends WebIntegrationTestBase {

    @Test
    void M1_path_variable_type_mismatch_returns_400() {
        // ---------- ① 类型不匹配 → 400（修复前实测是 500） ----------
        Response badType = RestAssured.given().get("/api/posts/abc");
        assertThat(badType.statusCode())
                .as("路径变量类型不匹配是客户端错误，必须 400 而不是 500。响应：%s", badType.asString())
                .isEqualTo(400);
        assertThat(badType.jsonPath().getInt("code")).isEqualTo(400);

        // 响应体仍必须是统一结构（§6.1），而不是 Spring 默认错误体
        Map<String, Object> body = badType.jsonPath().getMap("$");
        assertThat(body)
                .as("400 也必须是统一响应体 {code,message,data}，实际字段：%s", body.keySet())
                .containsKeys("code", "message");

        // ---------- ② 反证：未知路径仍是 404 ----------
        Response unknownPath = RestAssured.given().get("/api/nonexistent-xyz");
        assertThat(unknownPath.statusCode())
                .as("未知路径必须仍是 404（不能因为加了一条 400 分支就把 404 语义改掉）。实际：%s",
                        unknownPath.asString())
                .isEqualTo(404);
        assertThat(unknownPath.jsonPath().getInt("code")).isEqualTo(404);

        // ---------- ③ 反证：类型合法但资源不存在仍是 404 ----------
        Response missingResource = RestAssured.given().get("/api/posts/999999999");
        assertThat(missingResource.statusCode())
                .as("帖子不存在仍是 404，不能被归并成 400。实际：%s", missingResource.asString())
                .isEqualTo(404);
        assertThat(missingResource.jsonPath().getInt("code")).isEqualTo(404);
    }
}
