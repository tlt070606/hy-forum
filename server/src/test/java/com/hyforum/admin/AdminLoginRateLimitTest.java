package com.hyforum.admin;

import com.hyforum.interaction.M4ApiTestSupport;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 管理员登录的 IP 限流（2026-10-01 安全整改的验收用例）。
 *
 * <h2>这条用例存在的理由</h2>
 * <p>整改前，{@code /api/admin/login} 是全站<b>唯一没有任何限流的认证端点</b>：
 * 前台登录/注册有 IP 限流，后台为 0。后台登录不设验证码（M6 做后台界面时一并补），
 * 若口令强度不足，在线爆破是现实威胁 —— 限流是那条端点唯一的自动防线。</p>
 *
 * <h2>口径：入口维度 → HTTP 429 + Retry-After</h2>
 * <p>与前台登录同一分层（§5 裁决 #6/#8）：IP 限流超限返回 <b>HTTP 429</b> 并带
 * {@code Retry-After} 头，而不是 HTTP 200 + 业务码 —— 那是"业务动作维度"（举报、发帖）
 * 的形态。用例同时断言状态码与头，只断一个都验不出分层。</p>
 *
 * <h2>为什么把限额调小到 3</h2>
 * <p>生产值是 10 次/分钟（{@code hy.rate-limit.admin-login-per-minute}）。
 * 本类用 {@code @TestPropertySource} 显式压到 3：断言测的是<b>行为</b>
 * （"超限返回 429 + 头"），不是"碰巧配置成 10"。压小让打满这件事本身可控，
 * 也避免本类给共享的 {@code admin-login-ip-1m:127.0.0.1} 桶灌 10 次失败登录 ——
 * 与 M5ReportRateLimitTest 压 daily-limit 是同一做法。</p>
 */
@TestPropertySource(properties = {
        "hy.rate-limit.admin-login-per-minute=3"
})
class AdminLoginRateLimitTest extends M4ApiTestSupport {

    /** 本类的限流键（action 与 AdminAuthController.RL_ADMIN_LOGIN 一致；IP 是本机回环）。 */
    private static final String RATE_LIMIT_KEY = "hy:rl:admin-login-ip-1m:127.0.0.1";

    /** 造一个管理员（只插库，不登录 —— 登录次数本身是被测对象）。 */
    private String adminUsername;

    @Override
    protected String[] tablesToClean() {
        return new String[]{
                "admin_operation_log", "notification",
                "comment_like", "comment", "post_like", "post_collect", "follow",
                "post_image", "post", "board", "user", "admin"
        };
    }

    @BeforeEach
    void seedAdmin() {
        adminUsername = "admrl" + java.util.concurrent.ThreadLocalRandom.current().nextInt(100000);
        jdbcTemplate.update(
                "INSERT INTO admin (username, password_hash, nickname, role, status) VALUES (?, ?, ?, 'ADMIN', 1)",
                adminUsername, passwordEncoder.encode(TEST_PASSWORD), "限流用例管理员");
        // 起点确定性：清掉本类自己的桶（进程内其他用例可能留下过计数）
        stringRedisTemplate.delete(RATE_LIMIT_KEY);
    }

    @AfterEach
    void clearOwnBucket() {
        // 键是本类自己的（action + 127.0.0.1），清它是正当的测试自清理；
        // 不清的话，后续测试类的管理员登录会继承本类用掉的配额（M5 举报限流同类教训）
        stringRedisTemplate.delete(RATE_LIMIT_KEY);
    }

    @Test
    @DisplayName("M1_admin_login_rate_limited：超限 → HTTP 429 + Retry-After；且正确口令同样被挡")
    void M1_admin_login_rate_limited() {
        // 前 3 次（限额内）：无论口令对错都放行（HTTP 不得是 429）
        // —— 故意把 1 次正确登录夹在错误尝试里：限流看的是次数，不是成败
        Response ok = login(adminUsername, TEST_PASSWORD);
        assertThat(ok.statusCode())
                .as("限额内的正确登录必须放行。响应：%s", ok.asString())
                .isEqualTo(200);
        for (int i = 1; i <= 2; i++) {
            Response wrong = login(adminUsername, "WrongPass" + i);
            assertThat(wrong.statusCode())
                    .as("限额内的第 %d 次错误尝试必须放行到业务校验（不得被 429 挡住）。响应：%s",
                            i, wrong.asString())
                    .isNotEqualTo(429);
        }

        // 第 4 次（超限）：HTTP 429 + Retry-After，且**正确口令也一样被挡** ——
        // 限流在口令校验之前，这正是它防爆破的意义
        Response blocked = login(adminUsername, TEST_PASSWORD);
        assertThat(blocked.statusCode())
                .as("超限后必须 HTTP 429（入口维度分层）。响应：%s", blocked.asString())
                .isEqualTo(429);
        assertThat(blocked.getHeader("Retry-After"))
                .as("429 必须带 Retry-After 头（§8.7）。响应：%s", blocked.asString())
                .isNotNull();
        // 反向自证：清掉自己的桶之后，同一口令必须能登录成功
        // —— 否则上面的 429 可能只是"接口整体坏了"
        stringRedisTemplate.delete(RATE_LIMIT_KEY);
        Response recovered = login(adminUsername, TEST_PASSWORD);
        assertThat(recovered.statusCode())
                .as("清理限流桶后正确口令必须恢复可登录。响应：%s", recovered.asString())
                .isEqualTo(200);
    }

    private Response login(String username, String password) {
        return RestAssured.given()
                .contentType(ContentType.JSON)
                .body(Map.of("username", username, "password", password))
                .post("/api/admin/login");
    }
}
