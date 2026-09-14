package com.hyforum.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 幂等样板：<b>并发写入时靠唯一索引保证幂等</b>。
 *
 * <h2>这是本任务最重要的产出</h2>
 * M4（点赞/收藏/关注）、M5（通知去重）、M6（邀请码单次使用）都会照抄这个结构，
 * 所以它必须满足三个条件：
 * <ol>
 *   <li><b>不依赖业务代码</b>：直接用 {@code JdbcTemplate} 操作 schema 里已有的 {@code post_like}
 *       （表 6，唯一键 {@code uk_post_user(post_id, user_id)}）—— 业务还没写就能跑；</li>
 *   <li><b>真并发</b>：20 个线程经闩锁同时开跑（见 {@link ConcurrencyTestSupport}），
 *       并断言 {@code outcome.readyThreads()} == 20 来自证并发，而不是"串行跑了 20 次"；</li>
 *   <li><b>双向断言</b>：既断言「只落 1 行」，也断言「另外 19 个线程**必须**拿到
 *       {@link DuplicateKeyException}」。只断言行数的话，一个把所有插入都吞掉的实现也能通过。</li>
 * </ol>
 *
 * <h2>为什么不能靠 @Transactional 清理</h2>
 * 20 个线程各有自己的事务与连接，测试方法上的事务只覆盖测试线程自己，
 * 因此必须在 {@code @BeforeEach}/{@code @AfterEach} 显式清表（本基类已做）。
 *
 * <h2>M4–M6 照抄时要改的三处</h2>
 * <ol>
 *   <li>业务表名与唯一键（{@code post_collect}/{@code follow}/{@code invite_code}）；</li>
 *   <li>「成功者顺带做了什么」——例如点赞成功后同事务维护 {@code post.like_count}，
 *       那时断言要加上「计数恰好 +1」（参见 {@code M4_like_idempotent_concurrent} 的断言要点）；</li>
 *   <li>清表清单 {@link #tablesToClean()}。</li>
 * </ol>
 */
class IdempotencySampleTest extends IntegrationTestBase {

    /** 并发线程数：任务书 §7 —— 线程太少会掩盖竞态，20 是约定下限 */
    private static final int THREADS = 20;

    private TestFixtures.SeedPost seed;

    @Override
    protected String[] tablesToClean() {
        // 顺序无关（基线无外键）；把测试用到的表都列上，避免残留影响后续测试
        return new String[]{"post_like", "post", "board", "user"};
    }

    @BeforeEach
    void seedOnePost() {
        seed = fixtures.seedPost();
    }

    @Test
    @DisplayName("并发点赞同一帖子：唯一索引保证只有 1 行，其余 19 个线程必须撞唯一键")
    void SAMPLE_idempotency_concurrent_unique_index() {
        final long postId = seed.postId();
        final long userId = seed.userId();

        // —— 20 个线程同时执行同一条 INSERT（真并发：闩锁同时放行）——
        ConcurrencyTestSupport.Outcome outcome = ConcurrencyTestSupport.runConcurrently(THREADS, index ->
                jdbcTemplate.update("INSERT INTO `post_like` (post_id, user_id) VALUES (?, ?)", postId, userId));

        // 把并发结果的真实数字打出来，便于"第一次就绿"时判断是否真的竞争了
        System.out.println("[SAMPLE_idempotency_concurrent_unique_index] " + outcome);

        // 自证点 0：20 个线程确实都就位了（否则下面的断言只是在验证一次串行执行）
        assertThat(outcome.readyThreads())
                .as("20 个线程必须全部在闩锁前就位，否则这条测试没有真的并发，不能作为幂等证据")
                .isEqualTo(THREADS);

        // 自证点 1：唯一索引 uk_post_user 保证同一 (post_id,user_id) 只能有一行
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM `post_like` WHERE post_id = ? AND user_id = ?",
                Integer.class, postId, userId);
        assertThat(rows)
                .as("并发重复点赞不得产生脏数据：(post_id=%s, user_id=%s) 的行数必须是 1", postId, userId)
                .isEqualTo(1);

        // 自证点 2：另外 19 个线程必须**明确失败**在唯一键冲突上
        // （幂等 = 冲突可预期、可识别；而不是"什么都没发生"或"报了个别的原因"）
        assertThat(outcome.countOf(DuplicateKeyException.class))
                .as("必须有 %d 个线程拿到 DuplicateKeyException；实际并发结果：%s", THREADS - 1, outcome)
                .isEqualTo(THREADS - 1);

        // 自证点 3：成功者恰好 1 个，且没有任何线程"凭空消失"
        assertThat(outcome.successCount())
                .as("只能有 1 个线程插入成功；实际并发结果：%s", outcome)
                .isEqualTo(1);
        assertThat(outcome.successCount() + outcome.failureCount())
                .as("成功数 + 失败数必须等于线程数，否则有线程既没成功也没报错（断言被吞）")
                .isEqualTo(THREADS);

        // 最后再核一遍失败清单的条数：上面 countOf 只统计"含 DuplicateKeyException"的那些，
        // 若有线程因为别的原因（例如锁等待超时 1213）失败，这里会看出来
        List<Throwable> failures = outcome.failures();
        assertThat(failures).hasSize(THREADS - 1);
    }
}
