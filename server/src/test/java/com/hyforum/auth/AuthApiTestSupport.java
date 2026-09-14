package com.hyforum.auth;

import com.hyforum.auth.mode.RegisterMode;
import com.hyforum.auth.mode.RegisterModeService;
import com.hyforum.auth.support.CaptchaTestSupport;
import com.hyforum.audit.InMemorySensitiveTextChecker;
import com.hyforum.support.WebIntegrationTestBase;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * M1 接口集成测试的公共父类。
 *
 * <p>继承共享的 {@link WebIntegrationTestBase}（真随机端口 + REST Assured + 测试库防线 +
 * 清表机制）—— 接口测试必须走<b>真实 Servlet 容器与完整 filter 链</b>，
 * 否则「鉴权拦截器、统一响应体、错误码」这些跨切面行为根本测不到。</p>
 *
 * <p>本类只做三件 M1 特有的事：</p>
 * <ol>
 *   <li>把测试库恢复到 M1 需要的基线（注册模式 open + 一个可登录的管理员 + 敏感词），
 *       因为清表会把 {@code seed.sql} 的基线一起清掉；</li>
 *   <li>清掉本任务写入 Redis 的键（验证码 / 限流计数），让每个用例的起点确定；</li>
 *   <li>提供注册/登录的请求体构造（契约 §6.2 的字段名写一次，
 *       避免每个用例各拼一份 JSON 而把字段名写错）。</li>
 * </ol>
 *
 * <p><b>管理员的测试口令</b>：{@code seed.sql} 里的 {@code password_hash} 是占位符
 * （登录必然失败，属 fail-closed 设计，见 docs/db/seed.sql 注释）。测试库每次重建后
 * 管理员无法登录，因此这里用真实的 BCrypt 哈希造一个测试管理员 ——
 * 这不修改任何契约，只是把"部署时要替换占位哈希"这件事在测试里做掉。</p>
 */
@TestPropertySource(properties = {
        // 用例数量远小于 50，但同分钟内每个用例都会打若干请求；
        // 取 50 既能真实覆盖"计数会累加"，又不会让无关用例互相踩到限流。
        // 限流本身的断言（429）放在专门的用例里做。
        "hy.rate-limit.ip-per-minute=50",
        // 注册模式读取不缓存（DoD 第 5 条要求"切换后立即生效"）
        "hy.register.register-mode-cache-seconds=0"
})
public abstract class AuthApiTestSupport extends WebIntegrationTestBase {

    /** 基线管理员账号（仅测试库使用）。 */
    protected static final String ADMIN_USERNAME = "admin_it";

    /** 基线管理员口令：满足"8-32 位且含字母与数字"的自定规则，便于人工排障时复现。 */
    protected static final String ADMIN_PASSWORD = "Admin123456";

    /** 测试用水位密码（满足 8-32 位且含字母与数字）。 */
    protected static final String VALID_PASSWORD = "Passw0rd123";

    /** 测试库里的敏感词：用于验证 2001（内容包含敏感词）。 */
    protected static final String SENSITIVE_WORD = "测试违禁词";

    @Autowired
    protected CaptchaTestSupport captcha;

    @Autowired
    protected RegisterModeService registerModeService;

    @Autowired
    protected InMemorySensitiveTextChecker sensitiveTextChecker;

    @Autowired
    protected PasswordEncoder passwordEncoder;

    /**
     * 每个用例前的基线恢复。
     *
     * <p>必须放在父类（{@code AuthApiTestSupport}）而不是各个用例里：清表会把
     * {@code sys_config}/{@code admin}/{@code sensitive_word} 一并清空，
     * 忘记恢复就会让用例因为"注册模式配置缺失"这类环境问题而红，
     * 而不是因为被测行为错了而红 —— 那是测试最大的噪音来源。</p>
     *
     * <p><b>为什么是"先删后插"而不是直接 INSERT</b>：集成测试库里可能已经存在基线
     * （M0 的 {@code seed.sql}、或共享基建的 schema 测试留下的数据），
     * 直接 INSERT 会撞唯一索引（{@code uk_config_key} / {@code uk_admin_username} /
     * {@code uk_word}）报 DuplicateKey。基线恢复必须<b>幂等</b>，
     * 否则用例的成败会取决于"谁先跑过"。</p>
     */
    @BeforeEach
    void restoreM1Baseline() {
        // ① 注册模式：回到契约默认值 open（删了重插，保证值与 remark 都是确定的）
        jdbcTemplate.update("DELETE FROM sys_config WHERE config_key = ?", RegisterModeService.CONFIG_KEY);
        jdbcTemplate.update("INSERT INTO sys_config (config_key, config_value, remark) VALUES (?, ?, ?)",
                RegisterModeService.CONFIG_KEY, RegisterMode.OPEN.value(), "集成测试基线");

        // ② 一个可登录的管理员（真实 BCrypt 哈希，strength 由 PasswordEncoder 决定＝10）
        //    seed.sql 里的占位哈希登录必然失败（fail-closed），所以这里重建一个可用的
        jdbcTemplate.update("DELETE FROM admin WHERE username = ?", ADMIN_USERNAME);
        jdbcTemplate.update("INSERT INTO admin (username, password_hash, nickname, role, status) VALUES (?, ?, ?, ?, ?)",
                ADMIN_USERNAME, passwordEncoder.encode(ADMIN_PASSWORD), "集成测试管理员", "SUPER_ADMIN", 1);

        // ③ 敏感词一条。注意词库是启动时加载进内存的，写完必须 reload，
        //    否则测的是"旧的空词库"（这一点本身就是 M5 验收项要覆盖的行为）
        jdbcTemplate.update("DELETE FROM sensitive_word WHERE word = ?", SENSITIVE_WORD);
        jdbcTemplate.update("INSERT INTO sensitive_word (word) VALUES (?)", SENSITIVE_WORD);
        sensitiveTextChecker.reload();

        // ④ 清掉本任务在 Redis 里的残留（验证码、限流计数）
        captcha.clearAuthRedisState();
    }

    // ==================================================================
    // 请求体构造：契约 §6.2 的字段名只在这里写一次
    // ==================================================================

    /** 构造注册请求体。{@code captchaUuid}/{@code captchaCode} 由调用方传入。 */
    protected Map<String, Object> registerBody(String username, String password, String nickname,
                                               String captchaUuid, String captchaCode, boolean agreeProtocol) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("password", password);
        body.put("nickname", nickname);
        body.put("captchaUuid", captchaUuid);
        body.put("captchaCode", captchaCode);
        body.put("agreeProtocol", agreeProtocol);
        return body;
    }

    /** 登录请求体。 */
    protected Map<String, Object> loginBody(String username, String password) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("password", password);
        return body;
    }

    /** 管理后台登录请求体（§6.11）。 */
    protected Map<String, Object> adminLoginBody(String username, String password) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("password", password);
        return body;
    }

    /**
     * 一次"取验证码 → 用正确答案注册"的完整动作，返回注册响应。
     *
     * <p>绝大多数用例关心的是注册之后的业务规则，而不是验证码本身，
     * 因此把它收敛成一个动作；验证码本身的正确性由专门的用例覆盖。</p>
     */
    protected io.restassured.response.Response registerWithFreshCaptcha(String username, String nickname) {
        CaptchaTestSupport.Issued issued = captcha.issue();
        return RestAssured.given()
                .contentType(ContentType.JSON)
                .body(registerBody(username, VALID_PASSWORD, nickname, issued.uuid(), issued.answer(), true))
                .post("/api/auth/register");
    }

    /** 取一张验证码，返回响应体（用于断言响应形状与 TTL）。 */
    protected io.restassured.response.Response fetchCaptcha() {
        return RestAssured.given().get("/api/auth/captcha");
    }

    /** 把注册模式切到指定值（直连 Service，绕开尚未实现的 M6 后台接口）。 */
    protected void switchRegisterMode(RegisterMode mode) {
        int affected = registerModeService.switchMode(mode);
        if (affected != 1) {
            throw new IllegalStateException("切换注册模式失败：影响行数=" + affected + "（sys_config 基线是否被清掉了？）");
        }
    }

    /** 造一个已存在用户（用于用户名重复、封禁等场景），返回其 id。 */
    protected long insertUser(String username, String rawPassword, int status) {
        jdbcTemplate.update("INSERT INTO user (username, password_hash, nickname, status) VALUES (?, ?, ?, ?)",
                username, passwordEncoder.encode(rawPassword), "已有用户" + username, status);
        Long id = jdbcTemplate.queryForObject("SELECT id FROM user WHERE username = ?", Long.class, username);
        return id == null ? 0L : id;
    }

    /** 造一个永不过期的邀请码。{@code status}：0未使用 1已使用 2已失效。 */
    protected String insertInviteCode(String code, int status) {
        jdbcTemplate.update("INSERT INTO invite_code (code, status, expire_at) VALUES (?, ?, NULL)", code, status);
        return code;
    }

    /**
     * 造一个已过期的邀请码（{@code expire_at} 在昨天）。
     *
     * <p>用"昨天"而不是"NOW()"：{@code expire_at > NOW()} 的边界在 NOW() 上不稳定，
     * 必须留出确定的时间差，否则用例会随机红。</p>
     */
    protected String insertExpiredInviteCode(String code) {
        jdbcTemplate.update("INSERT INTO invite_code (code, status, expire_at) "
                + "VALUES (?, 0, DATE_SUB(NOW(), INTERVAL 1 DAY))", code);
        return code;
    }
}
