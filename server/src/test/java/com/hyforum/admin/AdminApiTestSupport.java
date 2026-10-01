package com.hyforum.admin;

import com.hyforum.interaction.M4ApiTestSupport;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M6（管理后台）接口集成测试的公共父类。
 *
 * <h2>与 {@code M5CommentAuditTest} 的关系</h2>
 * <p>M5 时只有"评论审核"一个后台能力，管理员造号 + adminPut 直接写在那个类里。
 * M6 有三个测试类都要做同样的事，于是提升为本支持类 —— 与 M3/M4 各自一份
 * {@code *ApiTestSupport} 的既有格局一致（基线不同的测试类不硬共享，
 * 但同一个里程碑内的测试类共享一份基线）。</p>
 *
 * <h2>清表清单</h2>
 * <p>父类（M4）清单 + 后台三张表：{@code admin_operation_log}（留痕断言要求从 0 开始）、
 * {@code admin}（{@code uk_admin_username} 唯一，残留会让造号失败）、
 * {@code invite_code}（M6 新增资源）。{@code sys_config} <b>刻意不清</b>：
 * 它是全库共享的配置行（TRUNCATE 会把 seed 的其他配置也带掉），
 * 注册模式的确定性由 {@code AdminSystemTest} 自己在前后恢复 {@code open} 来保证。</p>
 *
 * <h2>管理员登录与限流</h2>
 * <p>管理员登录 2026-10-01 起有 IP 限流（{@code admin-login-ip-1m}）。本类每个用例
 * 造一个管理员 = 一次登录，共享基线把它压到 100/分钟（见 {@code M4ApiTestSupport}
 * 的属性注释）；限流行为本身由 {@code AdminLoginRateLimitTest} 专门钉死。</p>
 */
public abstract class AdminApiTestSupport extends M4ApiTestSupport {

    /** 当前用例的管理员后台 token（{@code @BeforeEach} 造好，子类直接用）。 */
    protected String adminToken;

    @BeforeEach
    void seedAdmin() {
        adminToken = createAdminAndLogin();
    }

    @Override
    protected String[] tablesToClean() {
        return new String[]{
                "admin_operation_log", "notification", "invite_code", "report",
                "comment_like", "comment", "post_like", "post_collect", "follow",
                "post_image", "post", "board", "user", "admin"
        };
    }

    // ==================================================================
    // 管理端请求动作
    // ==================================================================

    /** 造一个管理员并登录（走真实后台登录接口，与运维同一条路径）。 */
    protected String createAdminAndLogin() {
        String username = "m6admin" + java.util.concurrent.ThreadLocalRandom.current().nextInt(100000);
        jdbcTemplate.update(
                "INSERT INTO admin (username, password_hash, nickname, role, status) VALUES (?, ?, ?, 'ADMIN', 1)",
                username, passwordEncoder.encode(TEST_PASSWORD), "管理员");
        Response response = RestAssured.given()
                .contentType(ContentType.JSON)
                .body(Map.of("username", username, "password", TEST_PASSWORD))
                .post("/api/admin/login");
        String token = response.jsonPath().getString("data.token");
        assertThat(token)
                .as("后台登录必须成功，否则本类全部无意义。响应：%s", response.asString())
                .isNotBlank();
        return token;
    }

    /** 后台 GET（带 admin token）。 */
    protected Response adminGet(String path) {
        return RestAssured.given().header("Authorization", adminToken).get(path);
    }

    /** 后台 PUT（带 admin token，JSON body）。 */
    protected Response adminPut(String path, Object body) {
        return RestAssured.given()
                .header("Authorization", adminToken)
                .contentType(ContentType.JSON)
                .body(body)
                .put(path);
    }

    /** 后台 POST（带 admin token，JSON body）。 */
    protected Response adminPost(String path, Object body) {
        return RestAssured.given()
                .header("Authorization", adminToken)
                .contentType(ContentType.JSON)
                .body(body)
                .post(path);
    }
}
