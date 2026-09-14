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
        // 与 application-test.yml 的 hy.rate-limit.ip-per-minute=50 保持一致。
        // 之所以两处都写：application-test.yml 由本任务独占维护、可能被后来者改动，
        // 而 M1ErrorCodesTest 打满配额的循环次数依赖这个值 —— 在测试类里显式声明一次，
        // 由 @TestPropertySource 覆盖（优先级更高），可以避免"改了 yml 导致限流用例失效"。
        "hy.rate-limit.ip-per-minute=50"
})
public abstract class AuthApiTestSupport extends WebIntegrationTestBase {

    /** 基线管理员账号（仅测试库使用）。 */
    protected static final String ADMIN_USERNAME = "admin_it";

    /** 基线管理员口令：满足"8-32 位且含字母与数字"的自定规则，便于人工排障时复现。 */
    protected static final String ADMIN_PASSWORD = "Admin123456";

    /** 测试用水位密码（满足 8-32 位且含字母与数字）。 */
    protected static final String VALID_PASSWORD = "Passw0rd123";

    /** 用户名的唯一后缀计数器：让用例反复运行时不会撞 uk_username。 */
    private static final java.util.concurrent.atomic.AtomicInteger USERNAME_SEQ =
            new java.util.concurrent.atomic.AtomicInteger();

    /** 用户名后缀的随机源（用 SecureRandom 同源的 Random 即可，仅用于去重）。 */
    private static final java.util.Random USERNAME_RANDOM = new java.util.Random();

    /**
     * 生成一个测试用户名：{@code m1<前缀>_<序号><随机码>}。
     *
     * <p>为什么全项目的 M1 测试都必须用它，而不是直接写字面量：</p>
     * <ul>
     *   <li><b>必须带下划线</b>：{@link #restoreM1Baseline()} 靠
     *       {@code username LIKE '%\_%'} 清掉上一次运行留下的测试用户。
     *       若某个用例写了不带下划线的用户名（例如 {@code flowuser}），
     *       它就不在清理范围内 —— 一旦上次运行中途失败，下一次运行会以
     *       DuplicateKey 报错开头，而那种红最难排查；</li>
     *   <li><b>必须唯一</b>：避免"上一次运行残留"造成唯一键冲突；</li>
     *   <li><b>必须 ≤20 字符</b>：契约 §6.2 规定用户名 4–20 位，
     *       超长会先被 {@code @Size} 拦成 400，用例就测不到想测的那条规则了。
     *       因此前缀要短（≤6 字符），实现里也对超长做了截断保护。</li>
     * </ul>
     *
     * @param prefix 语义前缀（如 {@code dup}），便于人工排障时认出是哪条用例造的数据；
     *               <b>请控制在 6 字符以内</b>
     */
    protected static String uniqueUsername(String prefix) {
        String safePrefix = prefix.length() > 6 ? prefix.substring(0, 6) : prefix;
        int suffix = USERNAME_SEQ.incrementAndGet();
        // 序号 + 4 位随机码：即使上一次运行残留，也有极大概率不撞（撞了也会被清理逻辑删掉）
        int randomPad = 1000 + USERNAME_RANDOM.nextInt(9000);
        String username = "m1" + safePrefix + "_" + suffix + randomPad;
        if (username.length() > 20) {
            // 兜底保护：宁可截断，也不要让"用户名超长"这种无关错误掩盖真正的断言
            username = username.substring(0, 20);
        }
        return username;
    }

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

        // ⑤ 清掉"上一次用例留下的业务数据"。
        //    为什么需要：基类的清表在 @BeforeEach/@AfterEach 各执行一次，
        //    正常失败时 @AfterEach 仍会清表；但若整轮运行被中断（Ctrl+C、超时被杀），
        //    库里会留下已占用的用户名与邀请码，让下一次运行以 DuplicateKey 报错开头。
        //    这类"环境污染导致的红"最难排查，所以基线恢复要做成**绝对幂等**。
        //
        //    匹配口径：本任务造的测试用户名一律带下划线（见 uniqueUsername），
        //    而真实用户名也允许下划线（技术方案 §6.2），因此用
        //    `LIKE '%\_%'`（MySQL 中反斜杠默认就是 LIKE 的转义字符，
        //    所以这里匹配的是**字面下划线**，不需要再写 ESCAPE 子句）。
        //    刻意不用固定前缀：那样每个用例都要改名，收益不抵可读性损失。
        jdbcTemplate.update("DELETE FROM user WHERE username LIKE ?", "%\\_%");
        jdbcTemplate.update("DELETE FROM invite_code");
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
