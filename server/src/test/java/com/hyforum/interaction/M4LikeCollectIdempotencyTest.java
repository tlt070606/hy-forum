package com.hyforum.interaction;

import com.hyforum.interaction.service.InteractionService;
import com.hyforum.support.ConcurrencyTestSupport;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M4 帖子互动：点赞 / 收藏的幂等、计数一致性、取消与删帖回退。
 *
 * <p>对应验收项（任务书 §7.1）：{@code M4_like_idempotent_concurrent}、
 * {@code M4_collect_idempotent_concurrent}、{@code M4_uncollect_idempotent}、
 * {@code M4_collect_list_matches_count}、{@code M4_counters_consistent_after_random_ops}、
 * {@code M4_delete_post_decrements_counters}。</p>
 *
 * <h2>并发用例为什么可能"第一次就绿"，以及怎么排除假绿</h2>
 * <p>幂等若有 bug，最典型的表现是"20 个线程都插入成功 → 20 行 + 计数 20"。
 * 但如果线程实际是串行的，唯一索引会让后 19 次自然失败、用例照样绿 ——
 * 那样它证明的是"数据库有唯一索引"，不是"我的代码幂等"。
 * 因此本类沿用幂等样板 {@code IdempotencySampleTest} 的自证手法：
 * 断言 {@code readyThreads() == 20}（真并发）、断言"其余 19 个线程必须拿到
 * {@link DuplicateKeyException}"（而不是"什么都没发生"）、并断言计数恰好 1。</p>
 */
class M4LikeCollectIdempotencyTest extends M4ApiTestSupport {

    /** 并发线程数：任务书 §7 的约定下限（线程太少会掩盖竞态）。 */
    private static final int THREADS = 20;

    /**
     * 直接注入 Service 用于并发用例。
     *
     * <p>为什么并发用例走 Service 而不是 HTTP：20 个线程各自发 HTTP 请求会同时
     * 抢连接池与 Tomcat 线程，失败原因里会混进"连接超时"这类与被测逻辑无关的噪音；
     * 幂等样板（{@code IdempotencySampleTest}）与任务书 §7 的示例也都是直接打底层。
     * 接口层的幂等语义另由本类的 HTTP 用例覆盖（重复点赞两次都返回 code=0）。</p>
     */
    @Autowired
    private InteractionService interactionService;

    /**
     * 发帖 Service（属于 {@code post} 包，W3-M3b 的地盘）。
     *
     * <p><b>测试里注入别人的 Service 是刻意的，且与铁律 3 不冲突</b>：
     * 铁律 3 约束的是<b>主代码</b>的模块依赖（ArchUnit 用 {@code DoNotIncludeTests} 导入，
     * 测试代码不在其范围内）。这里需要它是因为
     * {@code M4_delete_post_decrements_counters} 必须让 {@code user.post_count}
     * 处于"真的发过一篇帖"的状态 —— 见该用例里的注释。</p>
     */
    @Autowired
    private com.hyforum.post.service.PostService postService;

    private TestUser author;
    private TestUser visitor;
    private long boardId;
    private long postId;

    @BeforeEach
    void seedPost() {
        author = createUser("作者");
        visitor = createUser("访客");
        boardId = createBoard();
        postId = createNormalPost(boardId, author.id(), "M4 点赞与收藏用例帖");
    }

    // ==================================================================
    // 点赞
    // ==================================================================

    @Test
    @DisplayName("M4_like_idempotent_concurrent：20 线程并发点赞同一帖 → post_like 行数=1 且 like_count=1")
    void M4_like_idempotent_concurrent() {
        final long uid = visitor.id();

        ConcurrencyTestSupport.Outcome outcome = ConcurrencyTestSupport.runConcurrently(THREADS,
                index -> interactionService.likePost(uid, postId));
        System.out.println("[M4_like_idempotent_concurrent] " + outcome);

        // 自证点 0：确实并发了（否则下面只是在验证一次串行执行）
        assertThat(outcome.readyThreads())
                .as("20 个线程必须全部就位，否则这条用例不能作为幂等证据。实际：%s", outcome)
                .isEqualTo(THREADS);

        // 自证点 1：唯一索引 uk_post_user 决定行数只能是 1
        assertThat(countRows("post_like", "post_id", postId))
                .as("并发重复点赞不得产生脏数据。实际：%s", outcome)
                .isEqualTo(1);
        assertThat(countRows("post_like", "user_id", visitor.id()))
                .as("该用户的点赞行数也必须是 1")
                .isEqualTo(1);

        // 自证点 2：**所有 20 个线程都返回成功**（幂等语义，任务书 §5.2 的原话：
        // "其余靠唯一索引冲突或影响行数=0 走'已存在'分支，**返回成功而非报错**"）。
        // ⚠️ 这里刻意**不**断言"19 个线程拿到 DuplicateKeyException"：
        //    那是幂等样板（{@code IdempotencySampleTest}）里**裸 JDBC 插入**的行为 ——
        //    它没有 catch，异常当然会冒出来。业务实现按契约把冲突 catch 掉并翻译成成功，
        //    异常根本不会到达调用方。照抄样板的断言会把"正确的幂等实现"判成失败
        //    （本任务真的踩到了这一步，见交付报告「踩坑」一节）。
        //    那"是否真的走了幂等分支"谁来证？由下面那条
        //    M4_like_idempotent_does_not_double_count 来证 —— 它比数异常个数更硬。
        assertThat(outcome.successCount())
                .as("幂等语义要求所有线程都成功。实际：%s", outcome)
                .isEqualTo(THREADS);
        assertThat(outcome.failureCount())
                .as("不应有任何线程失败（唯一键冲突必须被翻译成成功，不能漏给调用方）。实际：%s", outcome)
                .isZero();
        assertThat(outcome.successCount() + outcome.failureCount())
                .as("成功数 + 失败数必须等于线程数，否则有线程既没成功也没报错（断言被吞）")
                .isEqualTo(THREADS);

        // 自证点 3（这条才是 M4 与样板用例的差别）：冗余计数只能 +1 一次
        assertThat(postColumn(postId, "like_count", Integer.class))
                .as("like_count 必须恰好等于关系表行数 1 —— 计数只加一次是幂等的另一半。实际：%s", outcome)
                .isEqualTo(1);
        assertThat(postColumn(postId, "like_count", Integer.class))
                .as("冗余计数必须等于关系表行数")
                .isEqualTo(countRows("post_like", "post_id", postId));
    }

    @Test
    @DisplayName("幂等的硬证据：关系行已存在时 20 线程并发点赞，like_count 必须纹丝不动（哨兵 99）")
    void M4_like_idempotent_does_not_double_count() {
        // 这条用例补上一条的盲区。上一条只能证明"20 个线程都成功、行数=1、计数=1"，
        // 而下面这种错误实现**也能通过上一条**：
        //   "插入冲突时不加计数，但没区分'这次是我插的'与'别人已经插了'" ——
        //   在 20 线程里如果它把 19 次冲突误判成成功，计数会变成 20，上一条其实能抓到；
        //   但如果实现是"先 SELECT 判断存在再决定加不加"（P1-4 明令禁止的两步式），
        //   在并发下多个线程会同时读到"不存在"→ 各自加一次 → 计数虚高，
        //   而上一条仍可能因为时序恰好只看到 1 —— 这是**偶发绿**，最难查。
        //
        // 做法：预先手工落一行 post_like，并把 like_count 设成哨兵值 99，
        // 再让 20 个线程并发调 likePost（全部必然走幂等分支）。
        // 任何"误加"都会让 99 变成 99+ —— 哨兵值让误加**无处隐藏**，
        // 也让这条用例不可能因为时序而碰巧变绿。
        jdbcTemplate.update("INSERT INTO post_like (post_id, user_id) VALUES (?, ?)", postId, visitor.id());
        jdbcTemplate.update("UPDATE post SET like_count = 99 WHERE id = ?", postId);

        final long uid = visitor.id();
        ConcurrencyTestSupport.Outcome outcome = ConcurrencyTestSupport.runConcurrently(THREADS,
                index -> interactionService.likePost(uid, postId));
        System.out.println("[M4_like_idempotent_does_not_double_count] " + outcome);

        assertThat(outcome.readyThreads()).as("必须真并发。实际：%s", outcome).isEqualTo(THREADS);
        assertThat(outcome.failureCount()).as("全部应幂等成功。实际：%s", outcome).isZero();
        assertThat(postColumn(postId, "like_count", Integer.class))
                .as("关系行本来就存在 → 这 20 次调用**一次都不能加计数**。"
                        + "若这里不是 99，说明实现把'已存在'当成了'插入成功'。实际：%s", outcome)
                .isEqualTo(99);
        assertThat(countRows("post_like", "post_id", postId))
                .as("关系行数也必须仍是 1")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("点赞的接口层幂等：连续两次 POST 都返回 code=0，行数与计数都不变")
    void M4_like_idempotent_via_api() {
        Response first = likePost(visitor.token(), postId);
        Response second = likePost(visitor.token(), postId);
        assertOk(first);
        assertOk(second);
        assertThat(countRows("post_like", "post_id", postId)).isEqualTo(1);
        assertThat(postColumn(postId, "like_count", Integer.class)).isEqualTo(1);
    }

    // ==================================================================
    // 收藏
    // ==================================================================

    @Test
    @DisplayName("M4_collect_idempotent_concurrent：20 线程并发收藏 → post_collect 行数=1 且 collect_count=1")
    void M4_collect_idempotent_concurrent() {
        final long uid = visitor.id();

        ConcurrencyTestSupport.Outcome outcome = ConcurrencyTestSupport.runConcurrently(THREADS,
                index -> interactionService.collectPost(uid, postId));
        System.out.println("[M4_collect_idempotent_concurrent] " + outcome);

        assertThat(outcome.readyThreads()).as("必须真并发。实际：%s", outcome).isEqualTo(THREADS);
        assertThat(countRows("post_collect", "post_id", postId))
                .as("并发重复收藏不得产生脏数据。实际：%s", outcome)
                .isEqualTo(1);
        // 同点赞：冲突被实现 catch 并翻译成成功，因此断言"全部成功"而不是"19 个异常"
        // （理由与 M4_like_idempotent_concurrent 的注释同一处，不重复）
        assertThat(outcome.successCount()).as("幂等要求全部成功。实际：%s", outcome).isEqualTo(THREADS);
        assertThat(outcome.failureCount()).as("不应有失败。实际：%s", outcome).isZero();
        assertThat(postColumn(postId, "collect_count", Integer.class))
                .as("collect_count 必须恰好 1（与 like_count 同源机制）")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("收藏的硬证据：关系行已存在时 20 线程并发收藏，collect_count 必须纹丝不动（哨兵 77）")
    void M4_collect_idempotent_does_not_double_count() {
        // 与 M4_like_idempotent_does_not_double_count 同构：点赞与收藏是两套独立计数，
        // 必须各自被证明（一处对、另一处错是最常见的复制粘贴事故）
        jdbcTemplate.update("INSERT INTO post_collect (post_id, user_id) VALUES (?, ?)", postId, visitor.id());
        jdbcTemplate.update("UPDATE post SET collect_count = 77 WHERE id = ?", postId);

        final long uid = visitor.id();
        ConcurrencyTestSupport.Outcome outcome = ConcurrencyTestSupport.runConcurrently(THREADS,
                index -> interactionService.collectPost(uid, postId));
        System.out.println("[M4_collect_idempotent_does_not_double_count] " + outcome);

        assertThat(outcome.readyThreads()).isEqualTo(THREADS);
        assertThat(outcome.failureCount()).as("全部应幂等成功。实际：%s", outcome).isZero();
        assertThat(postColumn(postId, "collect_count", Integer.class))
                .as("关系行已存在 → 20 次调用一次都不能加计数（应为哨兵值 77）。实际：%s", outcome)
                .isEqualTo(77);
        assertThat(countRows("post_collect", "post_id", postId)).isEqualTo(1);
    }

    @Test
    @DisplayName("M4_uncollect_idempotent：重复取消收藏 → 行数=0、计数不为负")
    void M4_uncollect_idempotent() {
        assertOk(collectPost(visitor.token(), postId));
        assertThat(postColumn(postId, "collect_count", Integer.class)).isEqualTo(1);

        // 连续 5 次取消：第 1 次真的删，后 4 次都必须是"幂等成功且不动计数"
        for (int i = 0; i < 5; i++) {
            assertOk(uncollectPost(visitor.token(), postId));
        }

        assertThat(countRows("post_collect", "post_id", postId))
                .as("重复取消后收藏行数必须是 0")
                .isZero();
        assertThat(postColumn(postId, "collect_count", Integer.class))
                .as("collect_count 必须回到 0，且**绝不能为负**（计数列是 UNSIGNED，减到负数会直接报错）")
                .isZero();

        // 反向自证：即使有人在计数已经不匹配的情况下硬取消，也不该把计数减成负数
        jdbcTemplate.update("UPDATE post SET collect_count = 0 WHERE id = ?", postId);
        assertOk(uncollectPost(visitor.token(), postId));
        assertThat(postColumn(postId, "collect_count", Integer.class))
                .as("计数为 0 时再取消，必须仍是 0（GREATEST 兜底）")
                .isZero();
    }

    @Test
    @DisplayName("M4_collect_list_matches_count：随机 100 次收藏/取消后，收藏列表长度 == collect_count")
    void M4_collect_list_matches_count() {
        // 三个用户对同一帖随机收藏/取消；用"随机"是为了覆盖
        // "同一个人反复切换"与"不同人交叉操作"两类交错
        TestUser second = createUser("访客二");
        TestUser third = createUser("访客三");
        List<TestUser> users = List.of(visitor, second, third);

        for (int i = 0; i < 100; i++) {
            TestUser user = users.get(ThreadLocalRandom.current().nextInt(users.size()));
            if (ThreadLocalRandom.current().nextBoolean()) {
                assertOk(collectPost(user.token(), postId));
            } else {
                assertOk(uncollectPost(user.token(), postId));
            }
        }

        // ① 冗余计数 == 关系表行数（§8.2）
        int rows = countRows("post_collect", "post_id", postId);
        Integer collectCount = postColumn(postId, "collect_count", Integer.class);
        assertThat(collectCount)
                .as("随机 100 次操作后，collect_count 必须等于 post_collect 的行数")
                .isEqualTo(rows);

        // ② 每个用户的收藏列表长度 == 该帖的收藏关系（§5.5 第 4 条的判据）
        //    这里的"收藏列表长度"以接口返回的 total 为准：它来自关系表的行数，
        //    是"我收藏了几篇"的权威答案
        int totalOfUsers = 0;
        for (TestUser user : users) {
            Response response = listMyCollections(user.token(), 1, 20);
            assertOk(response);
            int total = response.jsonPath().getInt("data.total");
            int expected = countRows("post_collect", "user_id", user.id());
            assertThat(total)
                    .as("用户 %s 的收藏列表长度必须等于他的收藏关系行数。响应：%s", user.username(), response.asString())
                    .isEqualTo(expected);
            totalOfUsers += expected;
        }
        assertThat(totalOfUsers)
                .as("三个用户的收藏关系行数之和必须等于该帖的收藏行数（没有重复计数、也没有凭空多出的行）")
                .isEqualTo(rows);
    }

    // ==================================================================
    // 随机操作下的计数自洽（任务书 §5.3 的判据）
    // ==================================================================

    @Test
    @DisplayName("M4_counters_consistent_after_random_ops：随机增删后冗余计数 == 关系表行数（无需校准任务）")
    void M4_counters_consistent_after_random_ops() {
        TestUser second = createUser("访客二");
        List<TestUser> users = List.of(visitor, second);

        // ★ 先用**确定性**的操作建立至少一组关系行（点赞 + 关注），再跑随机序列。
        //
        //   为什么必须这样（这是本用例自己的一处缺陷，实测红过一次）：
        //   随机序列是**无种子**的（ThreadLocalRandom），6 种操作里恰好有 3 种是"取消/取关"，
        //   两条帖子 × 两个用户 × 8 轮下来，整段序列把**所有**关系行净消掉是正常概率事件
        //   （约 1/6）。而本用例末尾有一条"反向自证"断言 —— 要求整轮确实落下过关系行，
        //   否则"计数 == 行数"在**全为 0** 时也成立、用例等于什么都没测。
        //   两者一叠加：**约 1/6 的概率让这条用例无辜变红**。
        //   这正是我在本任务里反复强调的那类问题 —— 一条靠运气才能绿的用例不是证据。
        //   建立确定性基线之后：随机部分仍然照跑（覆盖交错），而"测到了东西"变成必然。
        assertOk(likePost(visitor.token(), postId));
        assertOk(followUser(visitor.token(), author.id()));

        // 每个用户对每条帖子随机做 8 轮操作（点赞/取消/收藏/取消 + 关注/取关）
        for (int round = 0; round < 8; round++) {
            for (long pid : List.of(postId)) {
                for (TestUser user : users) {
                    switch (ThreadLocalRandom.current().nextInt(6)) {
                        case 0 -> assertOk(likePost(user.token(), pid));
                        case 1 -> assertOk(unlikePost(user.token(), pid));
                        case 2 -> assertOk(collectPost(user.token(), pid));
                        case 3 -> assertOk(uncollectPost(user.token(), pid));
                        case 4 -> assertOk(followUser(user.token(), author.id()));
                        default -> assertOk(unfollowUser(user.token(), author.id()));
                    }
                }
            }
        }

        // 判据（§5.3 裁剪后的版本）：每个计数字段 == 它对应的关系表行数
        assertThat(postColumn(postId, "like_count", Integer.class))
                .as("post.like_count 必须等于 post_like 行数")
                .isEqualTo(countRows("post_like", "post_id", postId));
        assertThat(postColumn(postId, "collect_count", Integer.class))
                .as("post.collect_count 必须等于 post_collect 行数")
                .isEqualTo(countRows("post_collect", "post_id", postId));

        for (TestUser user : users) {
            assertThat(userColumn(user.id(), "follow_count", Integer.class))
                    .as("user.follow_count 必须等于 follow 表里该用户关注出去的条数")
                    .isEqualTo(countRows("follow", "user_id", user.id()));
            assertThat(userColumn(user.id(), "fans_count", Integer.class))
                    .as("user.fans_count 必须等于 follow 表里指向该用户的条数")
                    .isEqualTo(countRows("follow", "target_user_id", user.id()));
        }
        assertThat(userColumn(author.id(), "fans_count", Integer.class))
                .as("被关注者的 fans_count 也必须等于指向他的关系条数")
                .isEqualTo(countRows("follow", "target_user_id", author.id()));

        // 反向自证：这一轮确实发生了操作（否则上面的等式在"全是 0"时也成立，
        // 用例会在什么都没测到的情况下变绿）
        int totalRelations = countRows("post_like", "post_id", postId)
                + countRows("post_collect", "post_id", postId)
                + countRows("follow", "target_user_id", author.id());
        assertThat(totalRelations)
                .as("随机序列必须真的落下过关系行，否则本用例没有测到任何东西")
                .isGreaterThan(0);
        System.out.println("[M4_counters_consistent_after_random_ops] 结束时关系行总数=" + totalRelations);
    }

    // ==================================================================
    // 删帖后的计数回退
    // ==================================================================

    @Test
    @DisplayName("M4_delete_post_decrements_counters：帖子逻辑删除后相关计数递减且与关系表一致")
    void M4_delete_post_decrements_counters() {
        // ⚠️ 这一步刻意**走发帖 Service 造帖**，而不是像本类其它用例那样直接写 SQL。
        //    原因：删帖会同时递减 user.post_count / board.post_count，而
        //    `post_count` 是 **INT UNSIGNED**；若它当前是 0，M3 的删帖语句
        //    `GREATEST(post_count - 1, 0)` 会先在无符号域里算出 -1 而**直接报错 1690**
        //    （MySQL 实测，见交付报告 CR-M4-3）。发帖接口会把 post_count 正确置为 1，
        //    于是这条用例测的是"删帖的计数回退"，而不是"人工制造出来的不可能状态"。
        //    这样也顺带真实地覆盖了"发帖 → 互动 → 删帖"这条完整链路。
        //
        // ⚠️ 发帖前必须**先清掉本用例自己的发帖配额**：§8.7 对新注册用户限 3 帖/24 小时
        //    （`PostRateLimiter` 的 action 是 `post-new-user-24h`，键含用户 id；`TestFixtures`
        //    造的用户 `created_at = NOW()`，因此每条用例的用户都算"新用户"）。
        //    不清的话，同一个用户 id 在多条用例里累积计数 → 第 4 次发帖就被 429，
        //    报错是 `请求过于频繁，请 86322 秒后再试`（实测就这样红过一次）。
        //    这里只清**本用例这个用户**的两条键 —— 不是通配符，因此不会动别人的配额。
        stringRedisTemplate.delete("hy:rl:post-new-user-24h:" + author.id());
        stringRedisTemplate.delete("hy:rl:post-hourly:" + author.id());

        long apiPostId = postService.create(author.id(),
                new com.hyforum.post.dto.PostCreateRequest(
                        boardId, "M4 删帖用例帖", "正文", null, null, null, null)).id();
        assertThat(userColumn(author.id(), "post_count", Integer.class))
                .as("发帖后作者的 post_count 必须为 1（否则下面的删帖会踩到 M3 的无符号减法缺陷）")
                .isEqualTo(1);

        // 造出"有点赞、有收藏、有评论、有评论点赞"的完整状态
        assertOk(likePost(visitor.token(), apiPostId));
        assertOk(collectPost(visitor.token(), apiPostId));
        long rootId = createCommentAndGetId(visitor.token(), apiPostId, 0, "删帖用例的主楼");
        long replyId = createCommentAndGetId(author.token(), apiPostId, rootId, "删帖用例的楼中楼");
        assertOk(likeComment(visitor.token(), replyId));

        assertThat(postColumn(apiPostId, "like_count", Integer.class)).isEqualTo(1);
        assertThat(postColumn(apiPostId, "collect_count", Integer.class)).isEqualTo(1);
        assertThat(postColumn(apiPostId, "comment_count", Integer.class)).isEqualTo(2);

        // ★ **顺序是硬约束**：互动收尾必须在逻辑删除**之前**做。
        //   原因是 Post 实体上的 @TableLogic 会让 MyBatis-Plus 给每一条 UPDATE
        //   自动附加 `AND is_deleted = 0` —— 帖子一旦被删除，计数 UPDATE 就影响 0 行、
        //   静默失效，而关系行是物理删除的照样会删掉，于是"关系表空了、计数还在"
        //   这个最坏状态就出现了（实测证据见交付报告的"删帖收尾顺序"一节）。
        //   将来把本方法接进 M3 删帖路径时，同样必须放在 postMapper.deleteById 之前
        //   （CR-M4-2）。
        int removed = interactionService.onPostDeleted(apiPostId);
        System.out.println("[M4_delete_post_decrements_counters] 收尾清掉的关系行数=" + removed);

        // 逻辑删除（M3 的 DELETE /api/posts/{id}）
        assertOk(deletePostViaApi(author.token(), apiPostId));

        assertThat(postColumn(apiPostId, "like_count", Integer.class))
                .as("删帖后 like_count 必须归零").isZero();
        assertThat(postColumn(apiPostId, "collect_count", Integer.class))
                .as("删帖后 collect_count 必须归零").isZero();
        assertThat(postColumn(apiPostId, "comment_count", Integer.class))
                .as("删帖后 comment_count 必须归零").isZero();

        // ① 关系表：post_like / post_collect 是**纯关系表**（无 is_deleted），
        //    所以它们必须被物理清空 —— 留一行"已取消"的墓碑会让计数等式永久不成立
        assertThat(countRows("post_like", "post_id", apiPostId)).isZero();
        assertThat(countRows("post_collect", "post_id", apiPostId)).isZero();
        assertThat(countRows("comment_like", "comment_id", replyId))
                .as("评论点赞关系必须随评论一起清掉（否则留下指向已删评论的孤儿行）")
                .isZero();

        // ② 评论行是**逻辑删除**（comment 表有 @TableLogic，DELETE 被翻译成
        //    UPDATE ... SET is_deleted = 1，实测 SQL 见交付报告）。
        //    因此这里断言的是"可见评论数为 0"，而不是"物理行数为 0" ——
        //    后者会是一条与表设计相矛盾的断言（第一版就是这么写错的）。
        assertThat(countVisibleComments(apiPostId))
                .as("删帖后该帖必须不再有可见评论").isZero();
        assertThat(postColumn(apiPostId, "comment_count", Integer.class))
                .as("comment_count 减的是**可见**评论数，必须与之一致")
                .isEqualTo(countVisibleComments(apiPostId));

        // ③ 与关系表一致（不只是"归零"，而是"和关系表对得上"——
        //    若关系行还留着而计数被清零，下一次对账会看到差值）
        assertThat(postColumn(apiPostId, "like_count", Integer.class))
                .isEqualTo(countRows("post_like", "post_id", apiPostId));
        assertThat(postColumn(apiPostId, "collect_count", Integer.class))
                .isEqualTo(countRows("post_collect", "post_id", apiPostId));

        // is_deleted 必须真的是 1（"计数递减"与"帖子真的被删了"是两件事，都要断言）
        assertThat(postColumn(apiPostId, "is_deleted", Integer.class)).isEqualTo(1);

        // 删帖后不得再能点赞/收藏/评论（否则互动会挂到一篇已删的帖子上，
        // 而那些关系行永远上不了任何列表 —— 是纯粹的脏数据来源）
        assertThat(likePost(visitor.token(), apiPostId).jsonPath().getInt("code"))
                .as("已删帖不能再被点赞").isEqualTo(404);
        assertThat(collectPost(visitor.token(), apiPostId).jsonPath().getInt("code"))
                .as("已删帖不能再被收藏").isEqualTo(404);
        assertThat(createComment(visitor.token(), apiPostId, 0, "评论已删帖").jsonPath().getInt("code"))
                .as("已删帖不能再被评论").isEqualTo(404);
    }

    /**
     * 删帖：走 M3 的 {@code DELETE /api/posts/{id}}（M4 不改它，但必须用真实链路复现）。
     *
     * <p>不用静态导入是为了让"这条请求属于 M3 的接口"在阅读时一眼可辨。</p>
     */
    private Response deletePostViaApi(String token, long pid) {
        return io.restassured.RestAssured.given()
                .header("Authorization", token)
                .delete("/api/posts/" + pid);
    }

    /** 该帖<b>可见</b>的评论行数（comment 用逻辑删除，所以必须带 is_deleted = 0）。 */
    private int countVisibleComments(long postId) {
        Integer count = scalar("SELECT COUNT(*) FROM comment WHERE post_id = ? AND is_deleted = 0",
                Integer.class, postId);
        return count == null ? 0 : count;
    }
}
