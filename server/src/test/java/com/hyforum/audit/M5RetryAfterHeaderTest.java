package com.hyforum.audit;

import com.hyforum.support.WebIntegrationTestBase;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H2：限流在 HTTP 429 时必须带 <b>{@code Retry-After}</b> 响应头
 * （映射表条目 {@code SEC_rate_limit_returns_429_with_retry_after}；技术方案 §8.7）。
 *
 * <h2>这条为什么归 M5 而不是 M1</h2>
 * <p>M1 已经能真实触发 429（{@code M1ErrorCodesTest} 把 IP 限流窗口打满），
 * 但它<b>只把"还要等几秒"写进了 message</b>，头没设 ——
 * 于是前端要拿这个信息只能去正则解析中文文案（文案一改就崩）。
 * 本任务按 §5 裁决 #6 把 {@code RateLimiter.Decision.retryAfter()} 一路带到响应头。</p>
 *
 * <h2>⚠️ 分层保持不变（§5 裁决 #6，不要"顺手统一"）</h2>
 * <p>本类只验<b>入口维度</b>：HTTP 429 + 业务码 429 + {@code Retry-After} 头。
 * 发帖那个<b>业务动作维度</b>的 {@code 2002} <b>保持 HTTP 200 + 业务码</b>，
 * <b>没有</b>被改成 429 —— 两者不是同一层，改成同一层要动 M1 冻结的 {@code ErrorCode}，
 * 且会让前端按错的分支处理。</p>
 *
 * <h2>为什么要独立一个类（不塞进 M1ErrorCodesTest）</h2>
 * <p>那个类是 <b>W1-M1 任务独占</b>的文件（看板 §2）。本任务只<b>新增</b>文件，
 * 不动别人的测试 —— 与 M3 当年"不硬复用 M1 的 AuthApiTestSupport"是同一条纪律。</p>
 */
@TestPropertySource(properties = {
        // 与 M1 的用例保持同一配额口径：需要把窗口打满才能触发 429。
        // 显式写死而不是依赖默认值 —— 否则断言测的是"碰巧配置成 50"，而不是这个行为本身。
        "hy.rate-limit.ip-per-minute=50"
})
class M5RetryAfterHeaderTest extends WebIntegrationTestBase {

    /** 触发 429 需要打满的配额（与上面的属性一致）。 */
    private static final int RATE_LIMIT_QUOTA = 50;

    @BeforeEach
    void clearRateLimitState() {
        // 只清**本类自己**会用到的限流键：登录入口的 action 是 login-ip-1m，键含本机 IP。
        // 用精确键而不是 hy:rl:* 通配符 —— 通配符会替别的测试类清配额，
        // 让依赖限流的用例变成"靠执行顺序侥幸"（这是我在 M4 交付里修过的一个真 bug）。
        stringRedisTemplate.delete("hy:rl:login-ip-1m:127.0.0.1");
    }

    /**
     * 清表清单。父类还会用它自己的 {@code TestTableCleaner}（期望库名取自系统属性
     * {@code hy.test.db}，缺省 {@code hy_forum_test}）。
     *
     * <p><b>⚠️ 因此本类必须与"其它测试类"跑在同一个库上</b>：
     * 它直接继承 {@code WebIntegrationTestBase}（不走 M4 那套 {@code @DynamicPropertySource}
     * 数据源覆盖），数据源完全由配置决定。若给本类单独传 {@code -Dhy.test.db=<别的库>}，
     * 就会出现"连接的是 hy_forum_test、清表工具却期望别的库"→ 报
     * {@code 拒绝执行：当前连接的是库 [...]}，而这条错与被测行为（Retry-After 头）毫无关系。
     * 实测踩过一次，记在这里：**本类要随全量一起跑，不要单独指定库。**</p>
     */
    @Override
    protected String[] tablesToClean() {
        // 本类只打登录接口、不落业务数据；但仍然清 user，避免与同 JVM 的其它类互相影响
        return new String[]{"user"};
    }

    @Test
    @DisplayName("SEC_rate_limit_returns_429_with_retry_after：打满入口配额 → HTTP 429 + Retry-After 头")
    void SEC_rate_limit_returns_429_with_retry_after() {
        Response rejected = null;
        for (int i = 0; i < RATE_LIMIT_QUOTA + 5; i++) {
            Response response = RestAssured.given()
                    .contentType(ContentType.JSON)
                    .body(loginBody("rate_limit_probe_user"))
                    .post("/api/auth/login");
            if (response.statusCode() == 429) {
                rejected = response;
                break;
            }
        }
        assertThat(rejected)
                .as("把入口配额（%d）打满后必须出现 429；否则本用例没有测到任何东西"
                        + "（注意：这本身也是一条自证 —— 若限流失效，这里会明确说'没触发'，"
                        + "而不是让下面的断言在一个 null 上抛 NPE）", RATE_LIMIT_QUOTA)
                .isNotNull();

        // ① HTTP 状态码
        assertThat(rejected.statusCode())
                .as("限流必须是 HTTP 429。响应：%s", rejected.asString())
                .isEqualTo(429);
        // ② 业务码（契约 §6.1）
        assertThat(rejected.jsonPath().getInt("code"))
                .as("响应体业务码也必须是 429").isEqualTo(429);

        // ③ ★ 本用例的主角：Retry-After 头
        String retryAfter = rejected.header("Retry-After");
        assertThat(retryAfter)
                .as("429 必须带 Retry-After 头（§8.7 / H2）—— 没有它前端只能去正则解析中文文案，"
                        + "文案一改就崩。响应头：%s", rejected.headers().toString())
                .isNotNull()
                .isNotBlank();
        assertThat(retryAfter)
                .as("Retry-After 必须是**正整数秒数**（HTTP 规范允许两种形态：秒数或 HTTP-date；"
                        + "本项目用秒数，因为限流窗口是相对时间，客户端不需要解析日期）。实际：%s", retryAfter)
                .matches("\\d+");
        int seconds = Integer.parseInt(retryAfter);
        assertThat(seconds)
                .as("Retry-After 必须 > 0 —— 设 0 会让客户端立刻重试，等于把一次拒绝放大成一串重试，"
                        + "比不设头更糟。实际：%d", seconds)
                .isPositive();
        assertThat(seconds)
                .as("限流窗口是 1 分钟（RateLimiter 的 ONE_MINUTE），因此建议等待秒数不该超过窗口。实际：%d",
                        seconds)
                .isLessThanOrEqualTo(60);

        // ④ 反向自证：**同一个接口在未超限时不得带头** ——
        //    否则"总是设一个 Retry-After"的实现也能通过上面所有断言（那种头是噪音，
        //    会让客户端在成功响应上看到"稍后重试"）。
        stringRedisTemplate.delete("hy:rl:login-ip-1m:127.0.0.1");
        Response normal = RestAssured.given()
                .contentType(ContentType.JSON)
                .body(loginBody("rate_limit_probe_user"))
                .post("/api/auth/login");
        assertThat(normal.statusCode())
                .as("清掉配额后必须恢复正常（不是 429）。响应：%s", normal.asString())
                .isNotEqualTo(429);
        assertThat(normal.header("Retry-After"))
                .as("**非 429 响应不得带 Retry-After 头** —— 反证："
                        + "证明这个头是限流时按需加的，而不是全局无脑加上的（那是噪音）")
                .isNull();
    }

    private static Map<String, Object> loginBody(String username) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        // 口令随便给：本用例要的是**限流计数**，不关心认证是否成功 ——
        // 用户名不存在同样会消耗一次配额（AuthController 的限流在业务之前）
        body.put("password", "WrongPass123");
        return body;
    }
}
