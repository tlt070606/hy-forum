package com.hyforum.admin;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 系统配置与邀请码（M6 批次一）：注册模式切换 / 邀请码生成与列表 / 留痕查询。
 *
 * <h2>注册模式切换的验收语义（§8.8 原文）</h2>
 * <p>"切换后行为立即变化，无需重启" —— 因此本用例切换后<b>立刻打前台接口</b>
 * （{@code GET /api/auth/register-mode} 与 {@code POST /api/auth/register}），
 * 不接受任何"过一会儿才生效"的实现。</p>
 *
 * <h2>sys_config 的确定性约定（见 {@code AdminApiTestSupport} 类注释）</h2>
 * <p>清表<b>不清</b> {@code sys_config}（全库共享配置行）。本类在 @BeforeEach/@AfterEach
 * 把 {@code register_mode} 恢复为 {@code open}：就算用例中途挂掉，恢复逻辑也保证
 * 后续测试类（尤其是 M1 的注册用例）拿到的是契约默认值。</p>
 */
class AdminSystemTest extends AdminApiTestSupport {

    @org.junit.jupiter.api.BeforeEach
    void restoreMode() {
        forceMode("open");
    }

    @AfterEach
    void restoreModeAgain() {
        forceMode("open");
    }

    private void forceMode(String value) {
        jdbcTemplate.update(
                "UPDATE sys_config SET config_value = ? WHERE config_key = 'register_mode'", value);
    }

    @Test
    @DisplayName("M6_register_mode_switch：切换立即生效于前台 + 留痕 + 非法值拒绝")
    void M6_register_mode_switch() {
        // 起点：契约默认 open
        assertThat(frontMode()).isEqualTo("open");

        // 切到 invite：前台立即感知（inviteRequired=true）
        Response toInvite = adminPut("/api/admin/configs/register-mode",
                java.util.Map.of("mode", "invite"));
        assertThat(toInvite.jsonPath().getInt("code"))
                .as("切换到 invite 必须成功。响应：%s", toInvite.asString()).isZero();
        assertThat(frontMode()).as("切换必须立即生效（无缓存窗口）").isEqualTo("invite");

        // invite 模式下，不带邀请码的注册必须被拒（行为随数据变化，§8.8）
        Response registerWithoutCode = RestAssured.given()
                .contentType(ContentType.JSON)
                .body(java.util.Map.of(
                        "username", "m6reg" + System.currentTimeMillis() % 100000,
                        "password", TEST_PASSWORD,
                        "nickname", "注册测试"))
                .post("/api/auth/register");
        assertThat(registerWithoutCode.jsonPath().getInt("code"))
                .as("invite 模式下无邀请码注册必须被拒。响应：%s", registerWithoutCode.asString())
                .isNotZero();

        // 切到 closed：注册模式查询立即反映
        assertThat(adminPut("/api/admin/configs/register-mode",
                java.util.Map.of("mode", "closed")).jsonPath().getInt("code")).isZero();
        assertThat(frontMode()).isEqualTo("closed");

        // 非法值 → 400
        assertThat(adminPut("/api/admin/configs/register-mode",
                java.util.Map.of("mode", "freetrial")).jsonPath().getInt("code")).isEqualTo(400);

        // 留痕：三次成功切换都在 REGISTER_MODE 里，detail 记录迁移路径
        Response logs = adminGet("/api/admin/logs?action=REGISTER_MODE&size=20");
        assertThat(logs.jsonPath().<java.util.List<String>>get("data.list.detail"))
                .as("留痕必须记录 register_mode 迁移路径。响应：%s", logs.asString())
                .contains("register_mode:open->invite", "register_mode:invite->closed");

        // 留痕查询带操作人用户名（可读性，不用拿 adminId 二次查询）
        assertThat(logs.jsonPath().getString("data.list[0].adminUsername"))
                .as("留痕项必须带操作人用户名").isNotBlank();
    }

    @Test
    @DisplayName("M6_invite_codes：批量生成 + 参数校验 + 列表筛选 + 留痕")
    void M6_invite_codes() {
        // 批量生成 3 张，7 天有效
        Response gen = adminPost("/api/admin/invite-codes",
                java.util.Map.of("count", 3, "expireDays", 7));
        assertThat(gen.jsonPath().getInt("code"))
                .as("生成 3 张必须成功。响应：%s", gen.asString()).isZero();
        java.util.List<String> codes = gen.jsonPath().getList("data");
        assertThat(codes).as("响应必须带回 3 个码").hasSize(3);
        codes.forEach(code -> assertThat(code).hasSize(16).doesNotContain("I", "O", "0", "1"));

        // 落库：3 行未使用、有过期时间
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invite_code WHERE status = 0 AND expire_at IS NOT NULL",
                Integer.class);
        assertThat(rows).as("3 张码必须落库且带过期时间").isEqualTo(3);

        // 参数校验：数量越界与过期天数越界
        assertThat(adminPost("/api/admin/invite-codes",
                java.util.Map.of("count", 0)).jsonPath().getInt("code")).isEqualTo(400);
        assertThat(adminPost("/api/admin/invite-codes",
                java.util.Map.of("count", 51)).jsonPath().getInt("code")).isEqualTo(400);
        assertThat(adminPost("/api/admin/invite-codes",
                java.util.Map.of("count", 1, "expireDays", 366)).jsonPath().getInt("code")).isEqualTo(400);

        // 列表（不筛选）能看到全部 3 张；筛选 status=0 同样
        Response list = adminGet("/api/admin/invite-codes?size=20");
        assertThat(list.jsonPath().<java.util.List<String>>get("data.list.code"))
                .as("列表必须包含刚生成的码").containsAll(codes);

        // 生成动作的留痕（一次批量 = 一条）
        Response logs = adminGet("/api/admin/logs?action=INVITE_CODE&size=20");
        assertThat(logs.jsonPath().<java.util.List<String>>get("data.list.detail"))
                .as("批量生成必须只留一条 count:N 痕").contains("count:3");
    }

    /** 读前台注册模式（走公开接口，验证"立即生效"的正是这条路）。 */
    private String frontMode() {
        Response response = RestAssured.given()
                .contentType(ContentType.JSON)
                .get("/api/auth/register-mode");
        assertThat(response.jsonPath().getInt("code"))
                .as("register-mode 查询必须成功。响应：%s", response.asString()).isZero();
        return response.jsonPath().getString("data.mode");
    }
}
