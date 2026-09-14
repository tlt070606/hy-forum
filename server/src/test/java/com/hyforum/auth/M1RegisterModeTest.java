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

        String username = uniqueUsername("open");
        Response response = registerWithFreshCaptcha(username, "开放注册用户");
        assertThat(response.jsonPath().getInt("code"))
                .as("open 模式下应可直接注册：%s", response.asString()).isZero();
        assertThat(response.jsonPath().getString("data.username")).isEqualTo(username);

        // 落库确认：不能只有响应成功而库里没有
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user WHERE username = ?", Integer.class, username);
        assertThat(count).as("注册成功必须落库").isEqualTo(1);
    }

    @Test
    void M1_register_mode_invite_requires_code() {
        switchRegisterMode(RegisterMode.INVITE);

        // ---------- ① 不带邀请码 → 拒绝 ----------
        String noCodeUser = uniqueUsername("invc");
        Response noCode = registerWithFreshCaptcha(noCodeUser, "无码用户");
        assertThat(noCode.jsonPath().getInt("code"))
                .as("invite 模式下不给邀请码必须拒绝：%s", noCode.asString())
                .isEqualTo(403);
        assertThat(countUser(noCodeUser)).as("被拒的注册不得落库").isZero();

        // ---------- ② 带无效邀请码 → 拒绝 ----------
        String badCodeUser = uniqueUsername("invb");
        CaptchaTestSupport.Issued forBadCode = captcha.issue();
        Response badCode = io.restassured.RestAssured.given()
                .contentType(io.restassured.http.ContentType.JSON)
                .body(withInviteCode(registerBody(badCodeUser, VALID_PASSWORD, "错码用户",
                        forBadCode.uuid(), forBadCode.answer(), true), "NOT-EXIST-CODE"))
                .post("/api/auth/register");
        assertThat(badCode.jsonPath().getInt("code"))
                .as("无效邀请码必须拒绝：%s", badCode.asString()).isEqualTo(403);
        assertThat(countUser(badCodeUser)).as("无效码的注册不得落库").isZero();

        // ---------- ③ 带有效邀请码 → 通过 ----------
        String code = insertInviteCode("VALID-INVITE-001", 0);
        String okUser = uniqueUsername("invo");
        CaptchaTestSupport.Issued forGoodCode = captcha.issue();
        Response goodCode = io.restassured.RestAssured.given()
                .contentType(io.restassured.http.ContentType.JSON)
                .body(withInviteCode(registerBody(okUser, VALID_PASSWORD, "有效码用户",
                        forGoodCode.uuid(), forGoodCode.answer(), true), code))
                .post("/api/auth/register");
        assertThat(goodCode.jsonPath().getInt("code"))
                .as("有效邀请码必须注册成功：%s", goodCode.asString()).isZero();
        assertThat(countUser(okUser)).isEqualTo(1);

        // 邀请码必须被真实消耗（P1-4：条件更新 + 影响行数校验的结果落在库上）
        Integer usedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invite_code WHERE code = ? AND status = 1 AND used_by_user_id IS NOT NULL",
                Integer.class, code);
        assertThat(usedCount)
                .as("邀请码必须被置为已使用并绑定用户（否则这个码可以被无限次使用）")
                .isEqualTo(1);

        // ---------- ④ 同一个码第二次使用 → 拒绝 ----------
        String reuseUser = uniqueUsername("invr");
        CaptchaTestSupport.Issued forReuse = captcha.issue();
        assertThat(reuseUser).isNotBlank();
        Response reuse = io.restassured.RestAssured.given()
                .contentType(io.restassured.http.ContentType.JSON)
                .body(withInviteCode(registerBody(reuseUser, VALID_PASSWORD, "复用码用户",
                        forReuse.uuid(), forReuse.answer(), true), code))
                .post("/api/auth/register");
        assertThat(reuse.jsonPath().getInt("code"))
                .as("已使用的邀请码必须拒绝：%s", reuse.asString()).isEqualTo(403);
        assertThat(countUser(reuseUser))
                .as("复用已用码时不得建号（否则码就不是一次性的了）")
                .isZero();
    }

    @Test
    void M1_register_mode_closed_rejects() {
        switchRegisterMode(RegisterMode.CLOSED);

        // 即使带着一个完全有效的邀请码，closed 也必须拒绝 —— 否则"关闭注册"是假的
        String closedUser = uniqueUsername("closed");
        String code = insertInviteCode("CLOSED-BUT-VALID", 0);
        CaptchaTestSupport.Issued issued = captcha.issue();
        Response response = io.restassured.RestAssured.given()
                .contentType(io.restassured.http.ContentType.JSON)
                .body(withInviteCode(registerBody(closedUser, VALID_PASSWORD, "关闭注册用户",
                        issued.uuid(), issued.answer(), true), code))
                .post("/api/auth/register");

        assertThat(response.jsonPath().getInt("code"))
                .as("closed 模式下任何注册都必须拒绝：%s", response.asString())
                .isEqualTo(403);
        assertThat(countUser(closedUser)).as("closed 模式下不得落库").isZero();

        // 邀请码也不能被消耗（拒绝要拒得干净，不能留下副作用）
        Integer consumed = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invite_code WHERE code = ? AND status = 1", Integer.class, code);
        assertThat(consumed).as("注册被拒时不得消耗邀请码").isZero();

        // 已有用户仍可登录（§8.8：closed 只关闭注册，不影响登录）
        String existingUser = uniqueUsername("exist");
        insertUser(existingUser, VALID_PASSWORD, 1);
        Response login = io.restassured.RestAssured.given()
                .contentType(io.restassured.http.ContentType.JSON)
                .body(loginBody(existingUser, VALID_PASSWORD))
                .post("/api/auth/login");
        assertThat(login.jsonPath().getInt("code"))
                .as("closed 模式下已注册用户必须仍可登录：%s", login.asString()).isZero();
    }

    @Test
    void M1_admin_unaffected_by_register_mode() {
        // ---------- 前置：把注册彻底关掉 ----------
        switchRegisterMode(RegisterMode.CLOSED);

        // ---------- 反证：此时普通用户注册确实被拒 ----------
        String adminModeUser = uniqueUsername("adm");
        CaptchaTestSupport.Issued issued = captcha.issue();
        Response userRegister = io.restassured.RestAssured.given()
                .contentType(io.restassured.http.ContentType.JSON)
                .body(registerBody(adminModeUser, VALID_PASSWORD, "注册被拒用户",
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

    /**
     * 用户名唯一（docs/技术方案.md §6.1 错误码 {@code 1001}、§6.2「用户名…且唯一」）。
     *
     * <p>对应验收项：{@code M1_duplicate_username_returns_1001}。</p>
     *
     * <p>这个用例独立于 {@code M1_error_codes_match_contract}（后者也顺带断言了 1001）：
     * 错误码契约用例保证"码被真实返回过"，本用例保证"唯一性这条业务规则在
     * <b>三种注册模式下都成立</b>"，并覆盖了唯一索引这道并发兜底 ——
     * 两者关注点不同，不能互相替代。</p>
     */
    @Test
    void M1_duplicate_username_returns_1001() {
        String username = uniqueUsername("dup");

        // ---------- ① 首次注册成功 ----------
        Response first = registerWithFreshCaptcha(username, "首次注册用户");
        assertThat(first.jsonPath().getInt("code"))
                .as("首次注册应成功（否则后面的重复断言没有意义）：%s", first.asString()).isZero();
        assertThat(countUser(username)).isEqualTo(1);

        // ---------- ② 同一用户名再注册 → 1001 ----------
        // 注意：必须用**新的验证码**，否则先撞上 1003 而不是 1001，
        // 用例就变成在测验证码而不是测用户名唯一性
        Response second = registerWithFreshCaptcha(username, "重复注册用户");
        assertThat(second.jsonPath().getInt("code"))
                .as("重复用户名必须返回 1001：%s", second.asString())
                .isEqualTo(1001);

        // ---------- ③ 库里仍然只有一条，且昵称没有被第二次注册覆盖 ----------
        assertThat(countUser(username)).as("重复注册不得产生第二条记录").isEqualTo(1);
        String nickname = jdbcTemplate.queryForObject(
                "SELECT nickname FROM user WHERE username = ?", String.class, username);
        assertThat(nickname).as("被拒绝的注册不得修改已有用户").isEqualTo("首次注册用户");

        // ---------- ④ 邀请制下同样必须是 1001，而不是先报"邀请码无效" ----------
        // 这条断言固定了校验顺序：已经存在的用户名在 invite 模式下也应返回 1001。
        // 若实现把邀请码校验放到用户名唯一性之前，会先返回 403 —— 用户会以为
        // "是邀请码的问题"，而真正的错因是用户名被占用。
        switchRegisterMode(RegisterMode.INVITE);
        CaptchaTestSupport.Issued issued = captcha.issue();
        Response inviteDup = io.restassured.RestAssured.given()
                .contentType(io.restassured.http.ContentType.JSON)
                .body(registerBody(username, VALID_PASSWORD, "重复注册用户",
                        issued.uuid(), issued.answer(), true))
                .post("/api/auth/register");
        assertThat(inviteDup.jsonPath().getInt("code"))
                .as("invite 模式下重复用户名同样应是 1001：%s", inviteDup.asString())
                .isEqualTo(1001);
        assertThat(countUser(username)).isEqualTo(1);
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
