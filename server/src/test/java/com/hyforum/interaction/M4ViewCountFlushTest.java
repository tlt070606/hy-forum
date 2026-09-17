package com.hyforum.interaction;

import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * M4 浏览量的<b>定时回写</b>：{@code M4_view_count_flushed_after_interval}。
 *
 * <h2>这条用例与 M3 那条的分工（验收项里写得很明确）</h2>
 * <ul>
 *   <li>M3 的 {@code M3_view_count_increments_in_redis} 证明的是：详情接口把计数写进了
 *       Redis，且<b>不会</b>直接 {@code UPDATE} 数据库 —— 它验证的是"存在一条回写路径"
 *       （必要时手动调 {@code flushPendingViews()}）；</li>
 *   <li><b>本用例证明的是"那条路径自己会跑"</b>：不调用任何方法，只等一个真实的
 *       {@code @Scheduled} 周期，再看数据库里的数对不对。</li>
 * </ul>
 * <p>两者的差别不是形式：一个"注册了 {@code @Scheduled} 但 {@code @EnableScheduling}
 * 没生效"或者"cron 表达式写错"的实现，前者的用例<b>照样绿</b>，
 * 而后者的用例会红。任务书 §7.1 第 14 条要的正是后者。</p>
 *
 * <h2>怎么"等一个真实周期"而不用 sleep 死等</h2>
 * <p>回写周期由 {@code hy.post.view-flush-interval-ms} 控制，本类的
 * {@code @TestPropertySource} 把它压到 <b>1000ms</b>（生产值 300000ms 不动）。
 * 然后用 Awaitility 轮询数据库，最多等 20 秒 —— 轮询而不是 {@code Thread.sleep}，
 * 是为了：① 机器慢时不会假红；② 正常情况下 1–2 秒就返回，不会白等。</p>
 * <p>断言里同时检查"轮询确实等到了变化"（{@code wasTrue}），
 * 否则一个"永远不满足"的条件在超时后抛出的异常信息会很难读。</p>
 */
class M4ViewCountFlushTest extends M4ApiTestSupport {

    /** 与 @TestPropertySource 里的 hy.post.view-flush-interval-ms 对应。 */
    private static final long FLUSH_INTERVAL_MS = 1000L;

    /** 最多等多久（周期 + 余量）。 */
    private static final long WAIT_SECONDS = 20L;

    private TestUser author;
    private long postId;

    @BeforeEach
    void seed() {
        author = createUser("作者");
        long boardId = createBoard();
        postId = createNormalPost(boardId, author.id(), "M4 浏览量回写用例帖");
    }

    @Test
    @DisplayName("M4_view_count_flushed_after_interval：等一个真实回写周期后，DB 的 view_count 正确")
    void M4_view_count_flushed_after_interval() throws InterruptedException {
        assertThat(postColumn(postId, "view_count", Integer.class))
                .as("起点必须是 0（否则下面的断言分不清'回写生效'与'本来就有值'）")
                .isZero();

        // —— 阶段 1：详情接口只动 Redis，不动数据库 ——
        final int visits = 5;
        for (int i = 0; i < visits; i++) {
            Response detail = io.restassured.RestAssured.given().get("/api/posts/" + postId);
            assertOk(detail);
        }
        assertThat(stringRedisTemplate.opsForValue().get("hy:post:view:" + postId))
                .as("详情接口必须把浏览量累加进 Redis（§8.3）")
                .isEqualTo(String.valueOf(visits));
        assertThat(postColumn(postId, "view_count", Integer.class))
                .as("详情接口**不得**直接 UPDATE 数据库（那是 M3 已经验过的另一半）")
                .isZero();

        // —— 阶段 2：不调用任何方法，只等定时任务自己跑 ——
        await().atMost(Duration.ofSeconds(WAIT_SECONDS))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(postColumn(postId, "view_count", Integer.class))
                        .as("等一个真实回写周期（%d ms）后，post.view_count 必须等于 Redis 里累计的 %d 次",
                                FLUSH_INTERVAL_MS, visits)
                        .isEqualTo(visits));

        // —— 阶段 3：回写必须"取走"而不是"只读"，否则下一周期会重复累加 ——
        assertThat(stringRedisTemplate.opsForValue().get("hy:post:view:" + postId))
                .as("回写必须用 GETDEL 取走增量（否则每个周期都会把同一批浏览量再加一遍）")
                .isNull();

        // 再等两个周期，确认计数**不再增长**：这条是"重复累加"这个 bug 的唯一探针。
        // 刻意不用 wait().during(...)（那样在 Awaitility 4.x 里语义容易读错，
        // 而且"期间一直为真"在计数只涨一次时本来就满足得含混）——
        // 直接睡两个周期再读一次，最直白：若回写没取走增量，这里会是 15 而不是 5。
        Thread.sleep(FLUSH_INTERVAL_MS * 2 + 500);
        assertThat(postColumn(postId, "view_count", Integer.class))
                .as("又过两个周期后，view_count 绝不能再次增长（重复累加的直接证据："
                        + "没取走增量的话这里会是 %d）", visits * 3)
                .isEqualTo(visits);

        // 阶段 4：再浏览一次，下一周期必须把这 1 次也加上（证明周期性回写是持续生效的，
        // 不是"只跑了一次"）
        assertOk(io.restassured.RestAssured.given().get("/api/posts/" + postId));
        await().atMost(Duration.ofSeconds(WAIT_SECONDS))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(postColumn(postId, "view_count", Integer.class))
                        .as("新增的 1 次浏览必须在下一个周期被回写")
                        .isEqualTo(visits + 1));
    }
}
