package com.hyforum.auth;

import com.hyforum.auth.mode.RegisterMode;
import com.hyforum.auth.support.CaptchaTestSupport;
import com.hyforum.common.api.ErrorCode;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 错误码契约（docs/技术方案.md §6.1）。
 *
 * <p>对应验收项：{@code M1_error_codes_match_contract} —— §6.1 的 12 个错误码逐一命中。</p>
 *
 * <p><b>这个测试要做两件不同的事，缺一不可</b>：</p>
 * <ol>
 *   <li><b>契约完整性</b>：12 个码在 {@link ErrorCode} 里逐个存在、数值与提示语与契约一致。
 *       只测这个是不够的 —— 定义了但没人返回的码等于没有；</li>
 *   <li><b>真实触发</b>：给每个码构造一个能真正打出该码的请求并断言。
 *       只测这个也是不够的 —— 会漏掉"契约里有、代码里没有"的码。</li>
 * </ol>
 *
 * <p><b>关于 12 个码在 M1 的可触发性</b>（如实说明，不含糊）：</p>
 * <ul>
 *   <li>{@code 0 / 400 / 401 / 403 / 404 / 429 / 1001 / 1002 / 1003 / 1004} —— M1 即可真实触发；</li>
 *   <li>{@code 2001 内容包含敏感词} —— M1 的注册接口会写昵称，属内容入口，
 *       可真实触发（完整敏感词能力归 M5）；</li>
 *   <li>{@code 2002 发帖过于频繁} —— <b>其触发点在 M3 的发帖接口</b>。
 *       M1 没有发帖功能，因此对它只做契约完整性断言（值/提示语/来源），
 *       不做"真实触发"。这一点在交付报告里登记，不假装验证过。</li>
 * </ul>
 */
@DisplayName("M1 · 错误码契约")
class M1ErrorCodesTest extends AuthApiTestSupport {

    /** 触发 429 需要把窗口计数打满；测试配置里 ip-per-minute=50。 */
    private static final int RATE_LIMIT_QUOTA = 50;

    @Test
    void M1_error_codes_match_contract() {
        // ================= ① 契约完整性：12 个码逐个存在且数值一致 =================
        Set<Integer> contractCodes = Set.of(0, 400, 401, 403, 404, 429,
                1001, 1002, 1003, 1004, 2001, 2002);
        Set<Integer> definedCodes = Arrays.stream(ErrorCode.values())
                .map(ErrorCode::code)
                .filter(code -> contractCodes.contains(code))
                .collect(Collectors.toSet());
        assertThat(definedCodes)
                .as("§6.1 的 12 个错误码必须全部在 ErrorCode 里定义（不多不少）")
                .containsExactlyInAnyOrderElementsOf(contractCodes);

        // 逐一核对数值 → 枚举 的映射（防止"定义成了另一个数字"这种低级但致命的漂移）
        assertThat(ErrorCode.of(0)).isEqualTo(ErrorCode.SUCCESS);
        assertThat(ErrorCode.of(400)).isEqualTo(ErrorCode.BAD_REQUEST);
        assertThat(ErrorCode.of(401)).isEqualTo(ErrorCode.UNAUTHORIZED);
        assertThat(ErrorCode.of(403)).isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(ErrorCode.of(404)).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(ErrorCode.of(429)).isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
        assertThat(ErrorCode.of(1001)).isEqualTo(ErrorCode.USERNAME_EXISTS);
        assertThat(ErrorCode.of(1002)).isEqualTo(ErrorCode.BAD_CREDENTIALS);
        assertThat(ErrorCode.of(1003)).isEqualTo(ErrorCode.CAPTCHA_INVALID);
        assertThat(ErrorCode.of(1004)).isEqualTo(ErrorCode.ACCOUNT_DISABLED);
        assertThat(ErrorCode.of(2001)).isEqualTo(ErrorCode.SENSITIVE_CONTENT);
        assertThat(ErrorCode.of(2002)).isEqualTo(ErrorCode.POST_TOO_FREQUENT);

        // ================= ② 真实触发 =================
        // 下面这些用例刻意放在 429 之前：它们会消耗限流配额（当前 50），
        // 必须在配额被打满之前跑完，否则会互相干扰（这也是"用例顺序敏感"的
        // 反面教材 —— 所以最后会把计数清掉，让后续用例不受影响）。

        // ---- 0 成功 ----
        assertThat(codeOf(fetchCaptcha())).as("GET /api/auth/captcha 成功返回 code=0").isZero();

        // ---- 400 参数错误（弱密码：7 位） ----
        CaptchaTestSupport.Issued forWeak = captcha.issue();
        Response weak = io.restassured.RestAssured.given()
                .contentType(io.restassured.http.ContentType.JSON)
                .body(registerBody("weak_pass_user", "a123456", "弱密码用户",
                        forWeak.uuid(), forWeak.answer(), true))
                .post("/api/auth/register");
        assertThat(weak.jsonPath().getInt("code"))
                .as("弱密码必须是 400：%s", weak.asString()).isEqualTo(400);

        // ---- 1001 用户名已存在 ----
        String dupUsername = "dup_user";
        assertThat(codeOf(registerWithFreshCaptcha(dupUsername, "重复用户")))
                .as("首次注册应成功").isZero();
        Response dup = registerWithFreshCaptcha(dupUsername, "重复用户2");
        assertThat(dup.jsonPath().getInt("code"))
                .as("重复用户名必须是 1001：%s", dup.asString()).isEqualTo(1001);

        // ---- 1002 用户名或密码错误 ----
        Response badPassword = io.restassured.RestAssured.given()
                .contentType(io.restassured.http.ContentType.JSON)
                .body(loginBody(dupUsername, "WrongPass123"))
                .post("/api/auth/login");
        assertThat(badPassword.jsonPath().getInt("code"))
                .as("密码错误必须是 1002：%s", badPassword.asString()).isEqualTo(1002);

        // ---- 1003 验证码错误 ----
        CaptchaTestSupport.Issued forCaptcha = captcha.issue();
        Response wrongCaptcha = io.restassured.RestAssured.given()
                .contentType(io.restassured.http.ContentType.JSON)
                .body(registerBody("captcha_err_user", VALID_PASSWORD, "验证码错误",
                        forCaptcha.uuid(), "ZZZZ", true))
                .post("/api/auth/register");
        assertThat(wrongCaptcha.jsonPath().getInt("code"))
                .as("验证码错误必须是 1003：%s", wrongCaptcha.asString()).isEqualTo(1003);

        // ---- 1004 账号已被封禁 ----
        insertUser("banned_user", VALID_PASSWORD, 0);
        Response banned = io.restassured.RestAssured.given()
                .contentType(io.restassured.http.ContentType.JSON)
                .body(loginBody("banned_user", VALID_PASSWORD))
                .post("/api/auth/login");
        assertThat(banned.jsonPath().getInt("code"))
                .as("封禁账号必须是 1004：%s", banned.asString()).isEqualTo(1004);

        // ---- 2001 内容包含敏感词（昵称命中敏感词表） ----
        CaptchaTestSupport.Issued forSensitive = captcha.issue();
        Response sensitive = io.restassured.RestAssured.given()
                .contentType(io.restassured.http.ContentType.JSON)
                .body(registerBody("sensitive_user", VALID_PASSWORD, "昵称含" + SENSITIVE_WORD,
                        forSensitive.uuid(), forSensitive.answer(), true))
                .post("/api/auth/register");
        assertThat(sensitive.jsonPath().getInt("code"))
                .as("昵称命中敏感词必须是 2001：%s", sensitive.asString()).isEqualTo(2001);

        // ---- 401 未登录 ----
        Response unauthorized = io.restassured.RestAssured.given().get("/api/user/me");
        assertThat(unauthorized.statusCode()).isEqualTo(401);
        assertThat(unauthorized.jsonPath().getInt("code"))
                .as("未登录必须是 401：%s", unauthorized.asString()).isEqualTo(401);

        // ---- 403 无权限（closed 模式下拒绝注册） ----
        switchRegisterMode(RegisterMode.CLOSED);
        CaptchaTestSupport.Issued forClosed = captcha.issue();
        Response closed = io.restassured.RestAssured.given()
                .contentType(io.restassured.http.ContentType.JSON)
                .body(registerBody("closed_mode_user", VALID_PASSWORD, "关闭注册用户",
                        forClosed.uuid(), forClosed.answer(), true))
                .post("/api/auth/register");
        assertThat(closed.jsonPath().getInt("code"))
                .as("closed 模式注册必须是 403：%s", closed.asString()).isEqualTo(403);
        switchRegisterMode(RegisterMode.OPEN);

        // ---- 404 资源不存在 ----
        Response notFound = io.restassured.RestAssured.given().get("/api/no-such-endpoint");
        assertThat(notFound.statusCode())
                .as("不存在的接口必须 404，实际响应：%s", notFound.asString()).isEqualTo(404);
        assertThat(notFound.jsonPath().getInt("code"))
                .as("404 也必须是统一响应体：%s", notFound.asString()).isEqualTo(404);

        // ---- 429 请求过于频繁 ----
        assertThat(triggerTooManyRequests())
                .as("把窗口计数打满后必须返回 429")
                .isEqualTo(429);

        // ================= ③ 2002 的说明（不假装验证） =================
        // 2002（发帖过于频繁）的触发点是 M3 的 POST /api/posts，M1 无发帖功能，
        // 故此处只有上面的契约完整性断言。M3 必须补上真实触发的用例。
        assertThat(ErrorCode.POST_TOO_FREQUENT.message()).isEqualTo("发帖过于频繁");
    }

    /** 取响应体的 code 字段。 */
    private static int codeOf(Response response) {
        return response.jsonPath().getInt("code");
    }

    /**
     * 把当前 IP 的限流窗口打满，返回最后一次响应的 code。
     *
     * <p><b>请求体必须"形状合法"</b>（username/password 都非空）：{@code @Valid} 校验发生在
     * Controller 方法体<b>之前</b>，所以空体 {@code {}}（字段缺失）或弱密码会在限流计数之前
     * 就被 400 拦掉 —— 那样刷多少次都打不满限流窗口（这是实测踩出来的坑，
     * 记录下来防止后来者把它"优化"成空请求体）。</p>
     *
     * <p>这里发的是"用户名不存在"的合法请求 → 正常走到 1002，每个请求都真实消耗一次配额。</p>
     *
     * <p>限流计数在测试结束时由 {@code clearAuthRedisState()} 清掉，
     * 因此不会污染同 JVM 里的其他测试类。</p>
     */
    private int triggerTooManyRequests() {
        int lastCode = -1;
        for (int i = 0; i < RATE_LIMIT_QUOTA + 5; i++) {
            Response response = io.restassured.RestAssured.given()
                    .contentType(io.restassured.http.ContentType.JSON)
                    .body(loginBody("rate_limit_probe_user", VALID_PASSWORD))
                    .post("/api/auth/login");
            lastCode = response.jsonPath().getInt("code");
            if (lastCode == 429) {
                break;
            }
        }
        return lastCode;
    }
}
