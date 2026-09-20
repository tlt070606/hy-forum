package com.hyforum.audit;

import com.hyforum.interaction.M4ApiTestSupport;
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
 * 举报入口与限流（任务书 §6 第 13 条：{@code M5_report_rate_limited_10_per_day}；§8.7）。
 *
 * <h2>本类验的核心一件事：举报超限走的是「业务动作维度」</h2>
 * <p>§5 裁决 #6／#8 把限流分成两层，**两者表现不同**：</p>
 * <ul>
 *   <li><b>入口维度</b>（按 IP 的登录/注册）→ HTTP <b>429</b> + {@code Retry-After} 头；
 *       由 {@code M5RetryAfterHeaderTest} 覆盖；</li>
 *   <li><b>业务动作维度</b>（举报 ≤10 次/天）→ <b>HTTP 200</b> + 业务码。
 *       <b>本类覆盖这一层</b>，与 M3 发帖的 {@code 2002} 同构。</li>
 * </ul>
 * <p>这条区分不是形式：若把举报超限也返回 HTTP 429，前端会按"限流"分支处理
 * （弹"操作太快了"），而实际上用户只是**今天的举报次数用完了** ——
 * 两种提示对用户的下一步动作完全不同（等一会儿 vs 明天再来）。
 * 所以用例**必须同时断言 HTTP 200 与业务码**，只断一个都验不出这个分层。</p>
 *
 * <h2>为什么把上限调小</h2>
 * <p>真实上限是 10 次/天。用例里调成 3，是为了"打满"这件事本身可控且读得懂 ——
 * 与 M1 把 {@code hy.rate-limit.ip-per-minute} 显式写死同一个理由：
 * 断言要测的是**行为**，而不是"碰巧配置成 10"。</p>
 */
@TestPropertySource(properties = {
        // ★ 用契约里的真实值（§8.7：举报 ≤ 10 次/天），**不调小** ——
        //   我一开始把它调成 3（想着"打满更快"），结果下一条用例的 3 次非法探测
        //   就把配额吃光了，合法请求直接 429（实测红过）。而且限流在**校验之前**
        //   （刻意的，见 ReportService：否则可以用不存在的 targetId 无限探测而不消耗配额），
        //   所以非法请求也计数。用真实值后这类"配额比预期少"的坑自然消失，
        //   断言也不必凑一个自造的数字 —— 它测的就是契约里的 10。
        "hy.report.daily-limit=10"
})
class M5ReportRateLimitTest extends M4ApiTestSupport {

    /** 与上面的 hy.report.daily-limit 一致（= 契约 §8.7 的真实值）。 */
    private static final int DAILY_LIMIT = 10;

    /** 业务动作维度选用的业务码（技术方案 §6.1 的 429「请求过于频繁」）。 */
    private static final int CODE_TOO_MANY_REQUESTS = 429;

    private TestUser reporter;
    private TestUser author;
    private long postId;

    @BeforeEach
    void seed() {
        reporter = createUser("举报人");
        author = createUser("被举报人");
        postId = createNormalPost(createBoard(), author.id(), "被举报的帖子");
        // 只清本用例这个用户的举报配额（精确键，不通配符 —— 见 M4 那次修复的教训）
        stringRedisTemplate.delete(reportLimitKey());
    }

    /**
     * 用完清掉本用例自己的举报配额。
     *
     * <p><b>为什么必须 @AfterEach 也清</b>（实测踩到）：清表会重置自增 id，
     * 而**限流计数在 Redis 里、不随清表消失** —— 于是下一条用例新建的"举报人"
     * 很可能拿到与上一条相同的 id，一上来就继承上一条用掉的配额，
     * 直接报 {@code 举报过于频繁}。症状是"第一条用例绿、后面全红"，
     * 看起来像限流逻辑坏了，实际是**用例之间通过 Redis 串了状态**。</p>
     *
     * <p>清**本类自己的键**（不是通配符）：通配符会替别的测试类清配额，
     * 让依赖限流的用例变成"靠执行顺序侥幸"（M4 交付里修过的一个真 bug）。</p>
     */
    @org.junit.jupiter.api.AfterEach
    void clearReportQuota() {
        stringRedisTemplate.delete(reportLimitKey());
    }

    /** 本类自己的举报限流键（与 ReportService.ACTION_REPORT_DAILY 一致）。 */
    private String reportLimitKey() {
        return "hy:rl:report-daily:" + reporter.id();
    }

    @Override
    protected String[] tablesToClean() {
        return new String[]{
                "report", "admin_operation_log", "notification",
                "comment_like", "comment", "post_like", "post_collect", "follow",
                "post_image", "post", "board", "user", "admin"
        };
    }

    @Test
    @DisplayName("M5_report_rate_limited_10_per_day：超每日上限 → HTTP 200 + 业务码（业务动作维度），且不落库")
    void M5_report_rate_limited_10_per_day() {
        // ① 上限内的每一次都必须成功
        for (int i = 1; i <= DAILY_LIMIT; i++) {
            Response ok = submitReport(Map.of(
                    "targetType", 1, "targetId", postId, "reasonType", 3, "reasonDetail", "第 " + i + " 次"));
            assertHttp200WithBusinessCode(ok, 0,
                    "第 " + i + " 次举报（上限内）必须成功");
        }
        assertThat(reportRows()).as("上限内的 %d 次必须都落库", DAILY_LIMIT).isEqualTo(DAILY_LIMIT);

        // ② 超限：**HTTP 仍必须是 200**，由业务码承载（这一层分层是本用例的主角）
        Response over = submitReport(Map.of(
                "targetType", 1, "targetId", postId, "reasonType", 3, "reasonDetail", "超限"));
        assertThat(over.statusCode())
                .as("举报超限必须仍是 **HTTP 200**（业务动作维度）—— 不是 429。"
                        + "返回 429 会让前端弹'操作太快了'，而用户实际情况是'今天的次数用完了'，"
                        + "两者对用户的下一步动作完全不同。响应：%s", over.asString())
                .isEqualTo(200);
        assertThat(over.jsonPath().getInt("code"))
                .as("业务码必须是 %d（请求过于频繁）。响应：%s", CODE_TOO_MANY_REQUESTS, over.asString())
                .isEqualTo(CODE_TOO_MANY_REQUESTS);
        assertThat(over.jsonPath().getString("message"))
                .as("提示语应说明是**每日**上限（而不是泛泛的'太频繁'）—— 用户需要知道是'明天再来'。"
                        + "响应：%s", over.asString())
                .contains("每天");

        // ③ 关键：**被拒的那次不能落库** ——
        //    只看响应码不够：一个"先落库再限流"的实现照样返回 429 业务码，
        //    而库里会多出一条越权的举报记录
        assertThat(reportRows())
                .as("被限流拒绝的举报**不得落库**（否则限流形同虚设，库里会堆满超限记录）")
                .isEqualTo(DAILY_LIMIT);
    }

    @Test
    @DisplayName("举报入口：参数与目标校验（非法 targetType/reasonType → 400；不存在的帖子 → 404）")
    void report_validates_input() {
        // 非法 targetType
        Response badType = submitReport(Map.of(
                "targetType", 9, "targetId", postId, "reasonType", 1));
        assertThat(badType.jsonPath().getInt("code"))
                .as("targetType 只支持 1/2/3。响应：%s", badType.asString()).isEqualTo(400);

        // 非法 reasonType
        Response badReason = submitReport(Map.of(
                "targetType", 1, "targetId", postId, "reasonType", 9));
        assertThat(badReason.jsonPath().getInt("code"))
                .as("reasonType 只支持 1–5。响应：%s", badReason.asString()).isEqualTo(400);

        // 不存在的帖子
        Response missing = submitReport(Map.of(
                "targetType", 1, "targetId", 99999999L, "reasonType", 1));
        assertThat(missing.jsonPath().getInt("code"))
                .as("举报不存在的帖子必须 404。响应：%s", missing.asString()).isEqualTo(404);

        assertThat(reportRows())
                .as("三次非法请求都不得落库（校验必须发生在落库之前）").isZero();

        // 反向自证：合法请求必须成功 —— 否则上面的 400/404 可能只是"接口整体不可用"
        Response ok = submitReport(Map.of(
                "targetType", 1, "targetId", postId, "reasonType", 1, "reasonDetail", "违法违规"));
        assertHttp200WithBusinessCode(ok, 0, "合法举报必须成功");
        assertThat(reportRows()).isEqualTo(1);
    }

    @Test
    @DisplayName("举报需要登录：匿名提交 → 401")
    void report_requires_login() {
        Response anonymous = RestAssured.given()
                .contentType(ContentType.JSON)
                .body(Map.of("targetType", 1, "targetId", postId, "reasonType", 1))
                .post("/api/report");
        assertThat(anonymous.statusCode())
                .as("匿名举报必须 401 —— report.user_id 是 NOT NULL，且按用户的每日限流也依赖它。"
                        + "响应：%s", anonymous.asString())
                .isEqualTo(401);
        assertThat(reportRows()).isZero();
    }

    // ==================================================================
    // 小工具
    // ==================================================================

    private Response submitReport(Map<String, Object> body) {
        return RestAssured.given()
                .header("Authorization", reporter.token())
                .contentType(ContentType.JSON)
                .body(new LinkedHashMap<>(body))
                .post("/api/report");
    }

    /** 断言"HTTP 200 + 期望业务码"。 */
    private void assertHttp200WithBusinessCode(Response response, int expectedCode, String because) {
        assertThat(response.statusCode())
                .as("%s（HTTP 必须 200）。响应：%s", because, response.asString())
                .isEqualTo(200);
        assertThat(response.jsonPath().getInt("code"))
                .as("%s（业务码应为 %d）。响应：%s", because, expectedCode, response.asString())
                .isEqualTo(expectedCode);
    }

    private int reportRows() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM report WHERE user_id = ?", Integer.class, reporter.id());
        return count == null ? 0 : count;
    }
}
