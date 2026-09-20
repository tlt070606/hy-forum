package com.hyforum.audit;

import com.hyforum.audit.InMemorySensitiveTextChecker;
import com.hyforum.interaction.M4ApiTestSupport;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审核链路的<b>机制</b>用例（任务书 §6 第 8–11 条）。
 *
 * <h2>⚠️ 这批用例验的是「机制」，不是「词库内容」（任务书 §0 硬要求）</h2>
 * <p>触发待审靠的是<b>测试自己注入的词</b>（{@code SENSITIVE_WORD}，直接写
 * {@code sensitive_word} 表并 {@code reload()}），<b>不是</b>真实词库 ——
 * 真实词库是需求方的内容决策，本任务明确不做内容安全。
 * 因此本类证明的是：</p>
 * <ul>
 *   <li><b>命中 → 置待审 → 前台不可见 → 审核出口能放行/屏蔽</b>这条链路是通的；</li>
 *   <li>编辑内容后<b>状态回到待审</b>（防"先发合规内容过审、再改成违规内容"的绕过）。</li>
 * </ul>
 * <p><b>它不证明</b>"敏感词能正确召回、不会误伤" —— 那是 H4（真实词库到位后）的事。
 * 报告里不会把"机制通过"写成"内容安全达标"。</p>
 *
 * <h2>注入的真实实现 vs 接口</h2>
 * <p>这里 autowire 的是 {@code audit} 包的<b>实现类</b>（要用它的 {@code reload()}，
 * 该方法不在 {@code common} 的接口上）—— 与 M3 的 {@code M3ApiTestSupport} 同一惯例。
 * 铁律 3 约束的是<b>主代码</b>（ArchUnit 用 {@code DoNotIncludeTests}），测试不在其内。</p>
 */
class M5PendingVisibilityTest extends M4ApiTestSupport {

    /** 只在本测试类里注入的词 —— 与真实词库无关（见类注释）。 */
    private static final String SENSITIVE_WORD = "M5测试敏感词";

    @Autowired
    private InMemorySensitiveTextChecker sensitiveTextChecker;

    private TestUser author;
    private long boardId;

    @BeforeEach
    void seedSensitiveWord() {
        // 词库是启动时加载进内存的，写完库必须 reload —— 否则用的是上一次加载的旧词库（H3 登记的坑）
        jdbcTemplate.update("DELETE FROM sensitive_word WHERE word = ?", SENSITIVE_WORD);
        jdbcTemplate.update("INSERT INTO sensitive_word (word) VALUES (?)", SENSITIVE_WORD);
        sensitiveTextChecker.reload();

        author = createUser("作者");
        boardId = createBoard();
    }

    @Override
    protected String[] tablesToClean() {
        return new String[]{
                "admin_operation_log", "notification",
                "comment_like", "comment", "post_like", "post_collect", "follow",
                "post_image", "post", "board", "user", "admin"
        };
    }

    // ==================================================================
    // §6 第 8 条：待审帖子前台不可见
    // ==================================================================

    @Test
    @DisplayName("M5_post_pending_hidden：含测试注入词的帖子 → status=0、前台列表/详情对他人不可见、作者自己能看")
    void M5_post_pending_hidden() {
        long postId = publishPostAndGetId(
                Map.of("boardId", boardId, "title", "正常标题", "content", "正文里有" + SENSITIVE_WORD));

        // 命中词 → 待审（先发后审：**收下**而不是拒收，用户刚写的草稿不该被丢掉）
        assertThat(postColumn(postId, "status", Integer.class))
                .as("命中测试注入词的帖子必须落 status=0（待审）").isZero();

        // 前台列表对任何人都看不到它
        assertThat(listPostIds(null))
                .as("待审帖不得出现在全部流/列表里（含作者自己的列表 —— 待审=前台不可见）")
                .doesNotContain((int) postId);

        // 详情：非作者 404（**不是 403** —— 403 等于承认"这个 id 存在但你不能看"，会泄露存在性）
        Response byStranger = getPostDetail(null, postId);
        assertThat(byStranger.jsonPath().getInt("code"))
                .as("他人看待审帖必须 404（不泄露存在性）。响应：%s", byStranger.asString())
                .isEqualTo(404);

        // 详情：作者自己能看到（否则作者只会看到"发出去了但找不到"这种无从解释的现象）
        Response byAuthor = getPostDetail(author.token(), postId);
        assertOk(byAuthor);
        assertThat(byAuthor.jsonPath().getInt("data.status"))
                .as("作者必须能看到自己待审帖的 status=0（前端据此提示'审核中'）").isZero();

        // 反证：把状态改回正常后，前台必须立刻可见 ——
        // 否则上面的"不可见"可能只是因为帖子根本没落库/查询坏了
        jdbcTemplate.update("UPDATE post SET status = 1 WHERE id = ?", postId);
        assertThat(listPostIds(null))
                .as("改动状态为正常后必须立刻可见（反证：证明前面的'不可见'确实来自 status）")
                .contains((int) postId);
    }

    // ==================================================================
    // §6 第 9 条：待审评论前台不可见
    // ==================================================================

    @Test
    @DisplayName("M5_comment_pending_hidden：含测试注入词的评论 → status=0、前台不可见；审核放行后可见")
    void M5_comment_pending_hidden() {
        long postId = createNormalPost(boardId, author.id(), "评论待审用例帖");
        long commentId = createCommentAndGetId(author.token(), postId, 0, "评论里有" + SENSITIVE_WORD);

        assertThat(commentStatus(commentId))
                .as("命中测试注入词的评论必须落 status=0（待审）").isZero();
        assertThat(visibleCommentIds(postId))
                .as("待审评论不得出现在前台评论列表里").doesNotContain((int) commentId);

        // 反证（也是"出入口成对"的证明）：审核放行后立刻可见
        jdbcTemplate.update("UPDATE comment SET status = 1 WHERE id = ?", commentId);
        assertThat(visibleCommentIds(postId))
                .as("放行后必须立刻可见 —— 反证前面的'不可见'确实来自 status").contains((int) commentId);

        // 反向自证：换一条**不含**词的评论必须是正常状态（证明"命中才置 0"，不是"一律置 0"）
        long clean = createCommentAndGetId(author.token(), postId, 0, "这条完全正常");
        assertThat(commentStatus(clean))
                .as("不含词的评论必须是 status=1（否则等于所有评论都进待审队列）").isEqualTo(1);
    }

    // ==================================================================
    // §6 第 10/11 条：编辑重审（状态机闭环）
    // ==================================================================

    @Test
    @DisplayName("M5_edit_post_resets_to_pending：正常帖改内容后 status 一律回到 0（即使新内容不含词）")
    void M5_edit_post_resets_to_pending() {
        long postId = createNormalPost(boardId, author.id(), "编辑重审用例帖");
        assertThat(postColumn(postId, "status", Integer.class)).as("起点必须是正常(1)").isEqualTo(1);

        // 改标题（新内容**不含**词）→ 状态仍必须回 0
        assertOk(updatePost(author.token(), postId, Map.of("title", "改过的标题", "content", "改过的正文")));

        assertThat(postColumn(postId, "status", Integer.class))
                .as("任何内容变更后 status 必须回到 0 重审 —— **不是**'命中敏感词才回 0'。"
                        + "后者是一条真实的审核绕过路径：先发合规内容过审，再改成违规内容")
                .isZero();
        // 反证：内容**没变**时不该把已放行的帖子打回队列（否则作者点一次保存就进待审）
        jdbcTemplate.update("UPDATE post SET status = 1 WHERE id = ?", postId);
        assertOk(updatePost(author.token(), postId, Map.of("title", "改过的标题", "content", "改过的正文")));
        assertThat(postColumn(postId, "status", Integer.class))
                .as("内容没变时不得把帖子打回待审").isEqualTo(1);
    }

    @Test
    @DisplayName("M5_edit_cannot_bypass_audit：编辑**不能**绕过审核 —— 改完立刻对他人不可见")
    void M5_edit_cannot_bypass_audit() {
        long postId = createNormalPost(boardId, author.id(), "绕过审核用例帖");
        assertThat(listPostIds(null)).as("前提：改动前它对所有人可见").contains((int) postId);

        // 作者先发合规内容拿到可见状态，再改成违规内容 —— 这是契约 §8.6 第 6 条点名的真实绕过路径
        assertOk(updatePost(author.token(), postId,
                Map.of("title", "违规标题", "content", "正文里有" + SENSITIVE_WORD)));

        assertThat(postColumn(postId, "status", Integer.class))
                .as("编辑后必须回到待审").isZero();
        assertThat(listPostIds(null))
                .as("**编辑后必须立刻从所有前台列表消失** —— 状态回到 0 却仍可见，等于绕过审核")
                .doesNotContain((int) postId);
        Response stranger = getPostDetail(null, postId);
        assertThat(stranger.jsonPath().getInt("code"))
                .as("他人看编辑后的待审帖必须 404。响应：%s", stranger.asString())
                .isEqualTo(404);
    }

    // ==================================================================
    // 小工具
    // ==================================================================

    /** 走发帖接口发帖并取 id（这样才真的经过敏感词判定）。 */
    private long publishPostAndGetId(Map<String, Object> body) {
        // ⚠️ 必须先清本用户的发帖配额：§8.7 对新注册用户限 3 帖/24 小时
        //    （PostRateLimiter 的 action 是 `post-new-user-24h`，键含用户 id；
        //     TestFixtures 造的用户 created_at = NOW()，因此每个用例的用户都算"新用户"）。
        //     不清的话第 4 次走接口发帖就被 2002 拒 —— 实测就这样红过一次
        //     （`请求过于频繁，请 86295 秒后再试`），而它看起来像"发帖功能坏了"。
        //     只清**本用例这个用户**的两条键，不是通配符，因此不会动别人的配额。
        stringRedisTemplate.delete("hy:rl:post-new-user-24h:" + author.id());
        stringRedisTemplate.delete("hy:rl:post-hourly:" + author.id());

        Response response = RestAssured.given()
                .header("Authorization", author.token())
                .contentType(ContentType.JSON)
                .body(body)
                .post("/api/posts");
        assertThat(response.jsonPath().getInt("code"))
                .as("发帖必须成功（命中敏感词是'待审'不是'拒收'）。响应：%s", response.asString())
                .isZero();
        Object id = response.jsonPath().get("data.id");
        assertThat(id).as("发帖响应必须带 id。响应：%s", response.asString()).isNotNull();
        return Long.parseLong(String.valueOf(id));
    }

    private Response updatePost(String token, long postId, Map<String, Object> body) {
        return RestAssured.given()
                .header("Authorization", token)
                .contentType(ContentType.JSON)
                .body(body)
                .put("/api/posts/" + postId);
    }

    /** 全部流里可见的帖子 id。 */
    private List<Integer> listPostIds(String token) {
        Response response = getFeed(token, "all");
        assertOk(response);
        List<Integer> ids = response.jsonPath().getList("data.list.id");
        return ids == null ? List.of() : ids;
    }

    private int commentStatus(long commentId) {
        Integer status = jdbcTemplate.queryForObject(
                "SELECT status FROM comment WHERE id = ?", Integer.class, commentId);
        return status == null ? -1 : status;
    }

    private List<Integer> visibleCommentIds(long postId) {
        Response response = listComments(postId, 1, 20);
        assertOk(response);
        List<Integer> ids = response.jsonPath().getList("data.list.id");
        return ids == null ? List.of() : ids;
    }
}
