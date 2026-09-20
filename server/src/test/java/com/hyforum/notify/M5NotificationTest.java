package com.hyforum.notify;

import com.hyforum.interaction.M4ApiTestSupport;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M5 通知的<b>触发点与查询</b>用例（任务书 §6 第 1–6 条）。
 *
 * <p>继承 {@link M4ApiTestSupport} 而不另建一个基类：M5 的触发点全在
 * {@code interaction} 里（点赞/评论/关注），本类要用的造数与动作辅助方法
 * （{@code likePost/createComment/followUser/...}）在那里已经齐了。
 * 数据源隔离也沿用它的做法（不写 {@code @TestPropertySource} 硬编码，
 * 由 {@code -Dhy.test.db} 或配置决定）。</p>
 *
 * <h2>本类验的是什么、不验什么（任务书 §0 要求如实写明）</h2>
 * <p>本类验的是<b>通知机制</b>：哪些动作会产生通知、通知给谁、什么情况下<b>不</b>产生通知、
 * 未读数与已读标记是否正确。<b>与敏感词词库内容无关</b> —— 本任务不做内容安全
 * （真实词库是需求方的内容决策，挂前置）。涉及敏感词的用例在
 * {@code M5AuditQueueTest} 里，且那里明确标注"验的是机制、不是词库内容"。</p>
 */
class M5NotificationTest extends M4ApiTestSupport {

    /** 通知类型（与 schema 的 notification.type 列注释一致）。 */
    private static final int TYPE_LIKE = 1;
    private static final int TYPE_COMMENT = 2;
    private static final int TYPE_REPLY = 3;
    private static final int TYPE_FOLLOW = 4;

    private TestUser author;
    private TestUser actor;
    private long boardId;
    private long postId;

    @BeforeEach
    void seed() {
        author = createUser("作者");
        actor = createUser("动作发起人");
        boardId = createBoard();
        postId = createNormalPost(boardId, author.id(), "M5 通知用例帖");
        // 发帖配额（§8.7 新用户 3 帖/24h）在本类不涉及（造帖走 SQL），
        // 但点赞/评论走 Service/接口，因此清一下本用户的限流键更稳
        stringRedisTemplate.delete("hy:rl:login-ip-1m:127.0.0.1");
    }

    /**
     * 清表清单 = 父类那份 <b>加上 {@code notification}</b>。
     *
     * <p><b>为什么必须覆盖</b>（这是本类第一条真红的原因）：父类
     * （{@link M4ApiTestSupport}）的清表清单是 M4 时代的，<b>不含 {@code notification}</b> ——
     * 那张表属于 M5。不补的话每条用例都会看到上一条留下的通知，
     * 表现是"刚建的作者已经有 2 条未读"，而报错信息（`Expected size: 1 but was: 2`）
     * 看起来像通知逻辑多写了，实际是**测试自己没清理**。</p>
     *
     * <p>刻意<b>不</b>去改父类的清表清单：那会让 M4 的用例也跟着清通知表，
     * 而 M4 的用例根本不关心它 —— 在子类里加自己需要的那张表是本分。</p>
     */
    @Override
    protected String[] tablesToClean() {
        return new String[]{
                "notification",
                "comment_like", "comment", "post_like", "post_collect", "follow",
                "post_image", "post", "board", "user"
        };
    }

    // ==================================================================
    // 四个触发点
    // ==================================================================

    @Test
    @DisplayName("M5_notification_on_like：A 点赞 B 的帖子 → B 收到一条 type=1 的通知")
    void M5_notification_on_like() {
        assertOk(likePost(actor.token(), postId));

        List<Map<String, Object>> rows = notificationsOf(author.token());
        assertThat(rows).as("点赞成功必须给帖子作者产生一条通知").hasSize(1);
        Map<String, Object> n = rows.get(0);
        assertThat(n.get("type")).as("类型必须是 1（点赞）").isEqualTo(TYPE_LIKE);
        assertThat(((Number) n.get("fromUserId")).longValue())
                .as("发起人必须是点赞的人").isEqualTo(actor.id());
        assertThat(n.get("targetType")).as("目标类型 1=帖子").isEqualTo(1);
        assertThat(((Number) n.get("targetId")).longValue()).isEqualTo(postId);
        // 反向自证：发起人自己**不该**收到通知（他点的是别人的帖子）
        assertThat(notificationsOf(actor.token()))
                .as("点赞的人自己不该收到这条通知").isEmpty();

        // 幂等：重复点赞不再产生第二条通知（第二次走 DuplicateKey 的幂等 return）
        assertOk(likePost(actor.token(), postId));
        assertThat(notificationsOf(author.token()))
                .as("重复点赞不得产生第二条通知（通知挂在'真的插入了关系'那一步之后）")
                .hasSize(1);
    }

    @Test
    @DisplayName("M5_notification_on_comment：A 评论 B 的帖子 → B 收到一条 type=2 的通知")
    void M5_notification_on_comment() {
        createCommentAndGetId(actor.token(), postId, 0, "A 的主楼评论");

        List<Map<String, Object>> rows = notificationsOf(author.token());
        assertThat(rows).as("主楼评论必须给帖子作者产生通知").hasSize(1);
        Map<String, Object> n = rows.get(0);
        assertThat(n.get("type")).as("主楼评论是 2（评论），不是 3（回复）").isEqualTo(TYPE_COMMENT);
        assertThat(((Number) n.get("fromUserId")).longValue()).isEqualTo(actor.id());
        assertThat(n.get("targetType")).as("主楼评论的目标是帖子").isEqualTo(1);
        assertThat(((Number) n.get("targetId")).longValue()).isEqualTo(postId);
    }

    @Test
    @DisplayName("M5_notification_on_reply：A 回复 B 的评论 → B 收到一条 type=3 的通知（且目标是那条主楼）")
    void M5_notification_on_reply() {
        // 先让 actor 发一条主楼，再由 author 回复它 → 接收人应当是 actor
        long rootId = createCommentAndGetId(actor.token(), postId, 0, "A 的主楼");
        // 清掉上一步给 author 的通知，让本用例只看"回复"产生的那条
        jdbcTemplate.update("DELETE FROM notification WHERE user_id = ?", author.id());

        createCommentAndGetId(author.token(), postId, rootId, "B 回复 A 的主楼");

        List<Map<String, Object>> rows = notificationsOf(actor.token());
        assertThat(rows).as("被回复的人（主楼作者 A）必须收到通知").hasSize(1);
        Map<String, Object> n = rows.get(0);
        assertThat(n.get("type")).as("楼中楼是 3（回复），不是 2（评论）").isEqualTo(TYPE_REPLY);
        assertThat(((Number) n.get("fromUserId")).longValue())
                .as("发起人是回复者 B").isEqualTo(author.id());
        assertThat(n.get("targetType")).as("回复的目标是那条主楼评论").isEqualTo(2);
        assertThat(((Number) n.get("targetId")).longValue())
                .as("targetId 指向**主楼评论**（前端据此跳到楼中楼上下文）").isEqualTo(rootId);

        // 反证：帖子作者（B）自己不该收到"回复"通知 —— 那是给被回复者的
        assertThat(notificationsOf(author.token()))
                .as("回复通知只给被回复者，不给帖子作者").isEmpty();
    }

    @Test
    @DisplayName("M5_notification_on_follow：A 关注 B → B 收到一条 type=4 的通知；重复关注不重复通知")
    void M5_notification_on_follow() {
        assertOk(followUser(actor.token(), author.id()));

        List<Map<String, Object>> rows = notificationsOf(author.token());
        assertThat(rows).as("被关注者必须收到通知").hasSize(1);
        Map<String, Object> n = rows.get(0);
        assertThat(n.get("type")).as("类型必须是 4（关注）").isEqualTo(TYPE_FOLLOW);
        assertThat(((Number) n.get("fromUserId")).longValue()).isEqualTo(actor.id());
        assertThat(n.get("targetType")).as("关注的目标是'人'，契约里没有对应取值 → 必须是 null")
                .isNull();

        // 幂等：重复关注走 uk_follow 的幂等 return，不该再产生通知
        assertOk(followUser(actor.token(), author.id()));
        assertThat(notificationsOf(author.token()))
                .as("重复关注不得产生第二条通知").hasSize(1);
    }

    // ==================================================================
    // 不产生通知的场景
    // ==================================================================

    @Test
    @DisplayName("M5_no_self_notification：自己点赞/评论/关注自己 → 一条通知都不产生")
    void M5_no_self_notification() {
        // 三个"自己操作自己"的场景一次覆盖（它们走的是同一个判断点）
        assertOk(likePost(author.token(), postId));
        createCommentAndGetId(author.token(), postId, 0, "作者自己评论自己的帖子");
        // 关注自己在接口层就被 400 拒绝，因此这里直接验 Service 语义之外的两种动作已足以覆盖该判断点；
        // 自我关注由 M4_follow_self_rejected 覆盖（更早、更严）

        assertThat(notificationsOf(author.token()))
                .as("自己点赞自己的帖子、自己评论自己的帖子，都**不得**产生通知。"
                        + "任务书 §5 第 2 条：这条判断由 NotificationPublisher 的实现统一做，"
                        + "因此这里验的是「那个统一判断点真的生效」")
                .isEmpty();

        // 反证：换个人来操作，就必须产生通知 —— 否则上面的"空"可能只是因为通知根本没写进去
        assertOk(likePost(actor.token(), postId));
        assertThat(notificationsOf(author.token()))
                .as("别人来点赞就必须产生通知（反证：证明通知机制本身是通的）")
                .hasSize(1);
    }

    // ==================================================================
    // 未读数与已读
    // ==================================================================

    @Test
    @DisplayName("M5_unread_count_correct：未读数随动作递增，标记已读（单条/全部）后正确回落")
    void M5_unread_count_correct() {
        assertThat(unreadCount(author.token())).as("起点必须是 0").isZero();

        // 造 3 条通知：点赞 + 评论 + 关注（来自 actor）
        assertOk(likePost(actor.token(), postId));
        createCommentAndGetId(actor.token(), postId, 0, "评论一条");
        assertOk(followUser(actor.token(), author.id()));
        assertThat(unreadCount(author.token())).as("三个动作 → 3 条未读").isEqualTo(3);

        // ★ 未读数是"当前用户"的：actor 自己那边不该有（他从没被通知过）
        assertThat(unreadCount(actor.token()))
                .as("未读数必须是当前用户自己的 —— actor 没被任何人操作过，应当是 0").isZero();

        // 单条已读：只减 1
        List<Map<String, Object>> rows = notificationsOf(author.token());
        long firstId = ((Number) rows.get(0).get("id")).longValue();
        assertThat(markRead(author.token(), List.of(firstId))).isEqualTo(1);
        assertThat(unreadCount(author.token())).as("标记 1 条已读 → 2 条未读").isEqualTo(2);

        // 幂等：同一条再标记一次不再计入影响行数（WHERE 里带了 is_read = 0）
        assertThat(markRead(author.token(), List.of(firstId)))
                .as("重复标记同一条 → 影响 0 行（否则前端的重试会把计数打乱）").isZero();
        assertThat(unreadCount(author.token())).isEqualTo(2);

        // 全部已读
        assertThat(markReadAll(author.token())).isEqualTo(2);
        assertThat(unreadCount(author.token())).as("全部已读后未读数归零").isZero();

        // 越权防线：actor 不能把 author 的通知标记成已读
        // （先把 author 的某条改回未读，再让 actor 去标记它）
        jdbcTemplate.update("UPDATE notification SET is_read = 0 WHERE user_id = ?", author.id());
        assertThat(markRead(actor.token(), List.of(firstId)))
                .as("别人不能标记我的通知 —— 否则对方红点会无声消失（静默越权写入）")
                .isZero();
        assertThat(unreadCount(author.token()))
                .as("越权尝试之后，我的未读数必须原样保留").isEqualTo(3);

        // 两个参数都不给 → 400（而不是静默成功，让前端以为已读生效）
        Response bad = RestAssured.given()
                .header("Authorization", author.token())
                .contentType(io.restassured.http.ContentType.JSON)
                .body(Map.of())
                .put("/api/notifications/read");
        assertThat(bad.jsonPath().getInt("code"))
                .as("ids 与 all 都不给必须 400，而不是静默返回成功。响应：%s", bad.asString())
                .isEqualTo(400);
    }

    // ==================================================================
    // 小工具
    // ==================================================================

    /** 我的通知列表（只取 list 部分）。 */
    private List<Map<String, Object>> notificationsOf(String token) {
        Response response = RestAssured.given()
                .header("Authorization", token)
                .get("/api/notifications");
        assertOk(response);
        List<Map<String, Object>> list = response.jsonPath().getList("data.list");
        return list == null ? List.of() : list;
    }

    private long unreadCount(String token) {
        Response response = RestAssured.given()
                .header("Authorization", token)
                .get("/api/notifications/unread-count");
        assertOk(response);
        return response.jsonPath().getLong("data");
    }

    private int markRead(String token, List<Long> ids) {
        Response response = RestAssured.given()
                .header("Authorization", token)
                .contentType(io.restassured.http.ContentType.JSON)
                .body(Map.of("ids", ids))
                .put("/api/notifications/read");
        assertOk(response);
        Integer affected = response.jsonPath().get("data");
        return affected == null ? 0 : affected;
    }

    private int markReadAll(String token) {
        Response response = RestAssured.given()
                .header("Authorization", token)
                .contentType(io.restassured.http.ContentType.JSON)
                .body(Map.of("all", true))
                .put("/api/notifications/read");
        assertOk(response);
        Integer affected = response.jsonPath().get("data");
        return affected == null ? 0 : affected;
    }
}
