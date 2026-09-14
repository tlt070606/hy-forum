package com.hyforum.auth;

import com.hyforum.auth.mode.RegisterMode;
import com.hyforum.auth.mode.RegisterModeService;
import com.hyforum.auth.support.CaptchaTestSupport;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 注册模式与管理员不受影响（docs/技术方案.md §8.8）。
 *
 * <p>对应验收项：</p>
 * <ul>
 *   <li>{@code M1_register_mode_open_allows} —— 开放注册可直接注册；</li>
 *   <li>{@code M1_register_mode_invite_requires_code} —— 无码拒绝、有效码通过；</li>
 *   <li>{@code M1_register_mode_closed_rejects} —— 任何注册都拒绝；</li>
 *   <li>{@code M1_admin_unaffected_by_register_mode} —— {@code closed} 下管理员仍可登录。</li>
 * </ul>
 *
 * <p><b>这些用例真正在验证的是"数据驱动"</b>：模式值只存在
 * {@code sys_config.register_mode} 里，切换由 {@link RegisterModeService#switchMode} 直接改库
 * （绕过尚未实现的 M6 后台接口）。用例中<b>不重启应用、不改配置、不注入任何开关</b>，
 * 却要求行为立即变化 —— 这就是 DoD 第 5 条「切换后行为立即变化，无需重启」的可执行表述。</p>
 *
 * <p>反向意义同样重要：如果谁给注册模式加了缓存（哪怕 30 秒），
 * 这些用例就会红。这正是它们存在的价值。</p>
 */
@DisplayName("M1 · 注册模式")
class M1RegisterModeTest extends AuthApiTestSupport {

    @Test
    void M1_register_mode_open_allows() {
        // 基线就是 open（AuthApiTestSupport 每次用例前恢复），显式切一次让意图明确
        switchRegisterMode(RegisterMode.OPEN);

        Response response = registerWithFreshCaptcha("open_mode_user", "开放注册用户");
        assertThat(response.jsonPath().getInt("code"))
                .as("open 模式下应可直接注册：%s", response.asString()).isZero();
        assertThat(response.jsonPath().getString("data.username")).isEqualTo("open_mode_user");

        // 落库确认：不能只有响应成功而库里没有
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user WHERE username = ?", Integer.class, "open_mode_user");
        assertThat(count).as("注册成功必须落库").isEqualTo(1);
    }

    @Test
    void M1_register_mode_invite_requires_code() {
        switchRegisterMode(RegisterMode.INVITE);

        // ---------- ① 不带邀请码 → 拒绝 ----------
        Response noCode = registerWithFreshCaptcha("invite_no_code", "无码用户");
        assertThat(noCode.jsonPath().getInt("code"))
                .as("invite 模式下不给邀请码必须拒绝：%s", noCode.asString())
                .isEqualTo(403);
        assertThat(countUser("invite_no_code")).as("被拒的注册不得落库").isZero();

        // ---------- ② 带无效邀请码 → 拒绝 ----------
        CaptchaTestSupport.Issued forBadCode = captcha.issue();
        Response badCode = io.restassured.RestAssured.given()
                .contentType(io.restassured.http.ContentType.JSON)
                .body(withInviteCode(registerBody("invite_bad_code", VALID_PASSWORD, "错码用户",
                        forBadCode.uuid(), forBadCode.answer(), true), "NOT-EXIST-CODE"))
                .post("/api/auth/register");
        assertThat(badCode.jsonPath().getInt("code"))
                .as("无效邀请码必须拒绝：%s", badCode.asString()).isEqualTo(403);
        assertThat(countUser("invite_bad_code")).as("无效码的注册不得落库").isZero();

        // ---------- ③ 带有效邀请码 → 通过 ----------
        String code = insertInviteCode("VALID-INVITE-001", 0);
        CaptchaTestSupport.Issued forGoodCode = captcha.issue();
        Response goodCode = io.restassured.RestAssured.given()
                .contentType(io.restassured.http.ContentType.JSON)
                .body(withInviteCode(registerBody("invite_ok_user", VALID_PASSWORD, "有效码用户",
                        forGoodCode.uuid(), forGoodCode.answer(), true), code))
                .post("/api/auth/register");
        assertThat(goodCode.jsonPath().getInt("code"))
                .as("有效邀请码必须注册成功：%s", goodCode.asString()).isZero();
        assertThat(countUser("invite_ok_user")).isEqualTo(1);

        // 邀请码必须被真实消耗（P1-4：条件更新 + 影响行数校验的结果落在库上）
        Integer usedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invite_code WHERE code = ? AND status = 1 AND used_by_user_id IS NOT NULL",
                Integer.class, code);
        assertThat(usedCount)
                .as("邀请码必须被置为已使用并绑定用户（否则这个码可以被无限次使用）")
                .isEqualTo(1);

        // ---------- ④ 同一个码第二次使用 → 拒绝 ----------
        CaptchaTestSupport.Issued forReuse = captcha.issue();
        Response reuse = io.restassured.RestAssured.given()
                .contentType(io.restassured.http.ContentType.JSON)
                .body(withInviteCode(registerBody("invite_reuse_user", VALID_PASSWORD, "复用码用户",
                        forReuse.uuid(), forReuse.answer(), true), code))
                .post("/api/auth/register");
        assertThat(reuse.jsonPath().getInt("code"))
                .as("已使用的邀请码必须拒绝：%s", reuse.asString()).isEqualTo(403);
        assertThat(countUser("invite_reuse_user"))
                .as("复用已用码时不得建号（否则码就不是一次性的了）")
                .isZero();
    }

    @Test
    void M1_register_mode_closed_rejects() {
        switchRegisterMode(RegisterMode.CLOSED);

        // 即使带着一个完全有效的邀请码，closed 也必须拒绝 —— 否则"关闭注册"是假的
        String code = insertInviteCode("CLOSED-BUT-VALID", 0);
        CaptchaTestSupport.Issued issued = captcha.issue();
        Response response = io.restassured.RestAssured.given()
                .contentType(io.restassured.http.ContentType.JSON)
                .body(withInviteCode(registerBody("closed_mode_user", VALID_PASSWORD, "关闭注册用户",
                        issued.uuid(), issued.answer(), true), code))
                .post("/api/auth/register");

        assertThat(response.jsonPath().getInt("code"))
                .as("closed 模式下任何注册都必须拒绝：%s", response.asString())
                .isEqualTo(403);
        assertThat(countUser("closed_mode_user")).as("closed 模式下不得落库").isZero();

        // 邀请码也不能被消耗（拒绝要拒得干净，不能留下副作用）
        Integer consumed = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invite_code WHERE code = ? AND status = 1", Integer.class, code);
        assertThat(consumed).as("注册被拒时不得消耗邀请码").isZero();

        // 已有用户仍可登录（§8.8：closed 只关闭注册，不影响登录）
        insertUser("existing_user", VALID_PASSWORD, 1);
        Response login = io.restassured.RestAssured.given()
                .contentType(io.restassured.http.ContentType.JSON)
                .body(loginBody("existing_user", VALID_PASSWORD))
                .post("/api/auth/login");
        assertThat(login.jsonPath().getInt("code"))
                .as("closed 模式下已注册用户必须仍可登录：%s", login.asString()).isZero();
    }

    @Test
    void M1_admin_unaffected_by_register_mode() {
        // ---------- 前置：把注册彻底关掉 ----------
        switchRegisterMode(RegisterMode.CLOSED);

        // ---------- 反证：此时普通用户注册确实被拒 ----------
        CaptchaTestSupport.Issued issued = captcha.issue();
        Response userRegister = io.restassured.RestAssured.given()
                .contentType(io.restassured.http.ContentType.JSON)
                .body(registerBody("admin_mode_user", VALID_PASSWORD, "注册被拒用户",
                        issued.uuid(), issued.answer(), true))
                .post("/api/auth/register");
        assertThat(userRegister.jsonPath().getInt("code"))
                .as("前置条件：closed 下注册必须被拒（否则本用例没有意义）")
                .isEqualTo(403);

        // ---------- 断言：管理员登录不受影响 ----------
        Response adminLogin = io.restassured.RestAssured.given()
                .contentType(io.restassured.http.ContentType.JSON)
                .body(adminLoginBody(ADMIN_USERNAME, ADMIN_PASSWORD))
                .post("/api/admin/login");

        assertThat(adminLogin.jsonPath().getInt("code"))
                .as("closed 模式下管理员必须仍可登录（§8.8：管理员账号不受注册模式影响）：%s",
                        adminLogin.asString())
                .isZero();
        assertThat(adminLogin.jsonPath().getString("data.token"))
                .as("管理员登录必须下发独立的后台 token").isNotBlank();

        // ---------- 前后台隔离：后台 token 不得能访问前台接口（§9） ----------
        String adminToken = adminLogin.jsonPath().getString("data.token");
        Response meWithAdminToken = io.restassured.RestAssured.given()
                .header("Authorization", adminToken)
                .get("/api/user/me");
        assertThat(meWithAdminToken.statusCode())
                .as("后台 token 访问前台接口必须被拒（两套独立 StpLogic，§9 后台隔离）：%s",
                        meWithAdminToken.asString())
                .isEqualTo(401);

        // ---------- 管理员注销可用（说明后台登录态是真的建立起来了） ----------
        Response adminLogout = io.restassured.RestAssured.given()
                .header("Authorization", adminToken)
                .post("/api/admin/logout");
        assertThat(adminLogout.jsonPath().getInt("code")).isZero();

        // 注销后同一 token 再用必须失效
        Response afterLogout = io.restassured.RestAssured.given()
                .header("Authorization", adminToken)
                .post("/api/admin/logout");
        assertThat(afterLogout.jsonPath().getInt("code"))
                .as("注销后的 token 必须失效：%s", afterLogout.asString())
                .isEqualTo(401);
    }

    // ==================================================================
    // 小工具
    // ==================================================================

    /** 往注册请求体里加邀请码字段（注册请求体本身在 AuthApiTestSupport 里构造）。 */
    private java.util.Map<String, Object> withInviteCode(java.util.Map<String, Object> body, String code) {
        body.put("inviteCode", code);
        return body;
    }

    private int countUser(String username) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user WHERE username = ?", Integer.class, username);
        return count == null ? 0 : count;
    }
}
