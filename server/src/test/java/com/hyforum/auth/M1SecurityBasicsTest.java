package com.hyforum.auth;

import com.hyforum.auth.support.CaptchaTestSupport;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 密码哈希不外泄 + 注册必须勾选协议（docs/技术方案.md §9 / §6.2，合规 C1）。
 *
 * <p>对应验收项：</p>
 * <ul>
 *   <li>{@code M1_password_never_returned} —— 响应中不出现密码/哈希字段；</li>
 *   <li>{@code M1_agree_protocol_required} —— {@code agreeProtocol=false} → 拒绝。</li>
 * </ul>
 *
 * <p>两条都是"安全/合规红线"，比功能正确性更不容妥协，因此断言写成
 * <b>全响应体递归扫描</b>：只要任意层级出现 {@code password} / {@code hash} 之类的键就失败。
 * 逐字段白名单断言会漏掉"新加了一个字段顺手带出哈希"的情况，
 * 而递归扫描能兜住任何形状的响应。</p>
 */
@DisplayName("M1 · 密码不外泄 / 协议勾选")
class M1SecurityBasicsTest extends AuthApiTestSupport {

    /** 触发"泄露"的键名特征（不区分大小写、按子串匹配）。 */
    private static final List<String> FORBIDDEN_KEY_FRAGMENTS = List.of("password", "passwd", "hash", "secret", "salt");

    @Test
    void M1_password_never_returned() {
        String username = "pwd_leak_user";

        // ---------- ① 注册响应 ----------
        CaptchaTestSupport.Issued issued = captcha.issue();
        Response register = io.restassured.RestAssured.given()
                .contentType(io.restassured.http.ContentType.JSON)
                .body(registerBody(username, VALID_PASSWORD, "密码不外泄用户",
                        issued.uuid(), issued.answer(), true))
                .post("/api/auth/register");
        assertThat(register.jsonPath().getInt("code")).isZero();
        assertNoSecretKeys("注册响应", register);

        // ---------- ② 登录响应 ----------
        Response login = io.restassured.RestAssured.given()
                .contentType(io.restassured.http.ContentType.JSON)
                .body(loginBody(username, VALID_PASSWORD))
                .post("/api/auth/login");
        assertThat(login.jsonPath().getInt("code")).isZero();
        assertNoSecretKeys("登录响应", login);
        String token = login.jsonPath().getString("data.token");

        // ---------- ③ /api/user/me 响应 ----------
        Response me = io.restassured.RestAssured.given()
                .header("Authorization", token)
                .get("/api/user/me");
        assertThat(me.jsonPath().getInt("code")).isZero();
        assertNoSecretKeys("/api/user/me 响应", me);

        // ---------- ④ 明文密码也不得回显 ----------
        assertThat(me.asString())
                .as("响应里不得出现明文密码")
                .doesNotContain(VALID_PASSWORD);
        assertThat(register.asString()).doesNotContain(VALID_PASSWORD);

        // ---------- ⑤ 库里的哈希必须是 BCrypt 形态（防"存了明文"） ----------
        String storedHash = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM user WHERE username = ?", String.class, username);
        assertThat(storedHash)
                .as("密码必须以 BCrypt 哈希落库（技术方案 §9），实际：%s", storedHash)
                .isNotNull()
                .startsWith("$2")
                .isNotEqualTo(VALID_PASSWORD);
    }

    @Test
    void M1_agree_protocol_required() {
        // ---------- agreeProtocol=false → 拒绝 ----------
        CaptchaTestSupport.Issued issuedFalse = captcha.issue();
        Response disagree = io.restassured.RestAssured.given()
                .contentType(io.restassured.http.ContentType.JSON)
                .body(registerBody("no_agree_user", VALID_PASSWORD, "未同意协议用户",
                        issuedFalse.uuid(), issuedFalse.answer(), false))
                .post("/api/auth/register");
        assertThat(disagree.jsonPath().getInt("code"))
                .as("agreeProtocol=false 必须被拒（合规 C1）：%s", disagree.asString())
                .isEqualTo(400);
        assertThat(countUser("no_agree_user"))
                .as("未同意协议不得落库（否则后端校验形同虚设）").isZero();

        // ---------- agreeProtocol 缺失 → 同样拒绝 ----------
        // 这一点很重要：若实现写成 "不能为 false"，字段缺失就会变成"默认放行"，
        // 而"默认放行"在合规红线上的错误方向。
        CaptchaTestSupport.Issued issuedMissing = captcha.issue();
        Map<String, Object> body = registerBody("missing_agree_user", VALID_PASSWORD, "缺协议字段用户",
                issuedMissing.uuid(), issuedMissing.answer(), true);
        body.remove("agreeProtocol");
        Response missing = io.restassured.RestAssured.given()
                .contentType(io.restassured.http.ContentType.JSON)
                .body(body)
                .post("/api/auth/register");
        assertThat(missing.jsonPath().getInt("code"))
                .as("agreeProtocol 字段缺失必须被拒（不得默认放行）：%s", missing.asString())
                .isEqualTo(400);
        assertThat(countUser("missing_agree_user")).isZero();

        // ---------- agreeProtocol=true → 通过（反证校验没写死拒绝） ----------
        Response agree = registerWithFreshCaptcha("agree_user", "同意协议用户");
        assertThat(agree.jsonPath().getInt("code"))
                .as("同意协议应注册成功：%s", agree.asString()).isZero();
    }

    /** 递归扫描 JSON 响应体的所有层级，发现疑似密码/哈希/密钥的键就失败。 */
    private void assertNoSecretKeys(String where, Response response) {
        Map<String, Object> body = response.jsonPath().getMap("$");
        assertThat(findSecretKeys(body))
                .as("%s 中出现了疑似密码/哈希字段，违反技术方案 §9「日志脱敏」与访问器白名单设计：%s",
                        where, response.asString())
                .isEmpty();
    }

    /** 深度遍历，收集所有命中 {@link #FORBIDDEN_KEY_FRAGMENTS} 的键路径。 */
    private List<String> findSecretKeys(Object node) {
        List<String> hits = new java.util.ArrayList<>();
        collectSecretKeys(node, "$", hits);
        return hits;
    }

    @SuppressWarnings("unchecked")
    private void collectSecretKeys(Object node, String path, List<String> hits) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : ((Map<Object, Object>) map).entrySet()) {
                String key = String.valueOf(entry.getKey());
                String lower = key.toLowerCase(java.util.Locale.ROOT);
                for (String fragment : FORBIDDEN_KEY_FRAGMENTS) {
                    if (lower.contains(fragment)) {
                        hits.add(path + "." + key);
                    }
                }
                collectSecretKeys(entry.getValue(), path + "." + key, hits);
            }
        } else if (node instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                collectSecretKeys(list.get(i), path + "[" + i + "]", hits);
            }
        }
    }

    private int countUser(String username) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user WHERE username = ?", Integer.class, username);
        return count == null ? 0 : count;
    }
}
