package com.hyforum.audit;

import com.hyforum.interaction.M4ApiTestSupport;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审核队列<b>出口</b>的用例（任务书 §6 第 7 条；§8.6 第 2 条）。
 *
 * <h2>这个类要证明的核心一件事：队列不是"只进不出"</h2>
 * <p>§8.6 要求命中敏感词的评论置 {@code status=0} 进待审队列、前台不可见。
 * 而"进得去"从来不是问题 —— 问题一直是"<b>出得来吗</b>"。
 * 本项目已经记录过一次同类缺陷（图片审核队列只进不出 → CR-006／P1-2），
 * 所以本类把<b>两个方向</b>都跑通并断言，而不是只验"能屏蔽"。</p>
 *
 * <h2>⚠️ 本类验的是"机制"，不是"词库内容"（任务书 §0 要求如实写明）</h2>
 * <p>待审状态在这里是<b>直接写库造出来的</b>（{@code status=0}），<b>不是</b>由敏感词命中产生的 ——
 * 因为真实词库是需求方的内容决策，本任务不做内容安全。
 * 因此本类证明的是：<b>给定一条待审评论，审核员能把它放行或屏蔽，且两个方向都留痕</b>。
 * <b>它不证明"敏感词能正确命中"</b>，那是 H4（词库到位后）的事。</p>
 */
class M5CommentAuditTest extends M4ApiTestSupport {

    /** 审核动作编码（与 CommentAuditService.ACTION_COMMENT_STATUS 一致）。 */
    private static final String ACTION_COMMENT_STATUS = "COMMENT_STATUS";

    private TestUser author;
    private TestUser commenter;
    private long postId;
    private String adminToken;

    @BeforeEach
    void seed() {
        author = createUser("帖子作者");
        commenter = createUser("评论者");
        long boardId = createBoard();
        postId = createNormalPost(boardId, author.id(), "审核用例帖");
        adminToken = createAdminAndLogin();
    }

    /**
     * 本类的清表清单 = 父类那份 + {@code notification} + {@code admin} + {@code admin_operation_log}。
     *
     * <p>父类清单是 M4 时代的（不含通知，也不含后台两张表）。不补的话：
     * ① 上一条用例留下的通知/评论会污染下一条；
     * ② {@code admin.username} 有唯一索引，残留的 admin 会让下一条用例造号失败。</p>
     */
    @Override
    protected String[] tablesToClean() {
        return new String[]{
                "admin_operation_log", "notification",
                "comment_like", "comment", "post_like", "post_collect", "follow",
                "post_image", "post", "board", "user", "admin"
        };
    }

    // ==================================================================
    // 出口的两个方向
    // ==================================================================

    @Test
    @DisplayName("M5_comment_admin_can_release_and_block：待审评论既能放行(1)也能屏蔽(2)，两个方向都留痕")
    void M5_comment_admin_can_release_and_block() {
        long pending = insertPendingComment("命中敏感词（测试注入）的待审评论");

        // 前提自证：队里真的有一条待审评论，且前台看不见它
        assertThat(commentStatus(pending)).as("起点必须是待审(0)").isZero();
        assertThat(visibleCommentIds()).as("待审评论不得出现在前台评论列表里")
                .doesNotContain((int) pending);

        // ① 后台能看到它（队列的入口可见 —— 否则审核员无从处置）
        Response list = adminGet("/api/admin/comments?status=0&size=20");
        assertOk(list);
        assertThat(list.jsonPath().getList("data.list.id"))
                .as("后台按 status=0 必须能看到待审评论。响应：%s", list.asString())
                .contains((int) pending);

        // ② 放行（方向一）→ status=1，前台立刻可见，且留了痕
        Response release = adminPut("/api/admin/comments/" + pending + "/status",
                Map.of("status", 1));
        assertOk(release);
        assertThat(commentStatus(pending))
                .as("放行后评论必须变正常(1)").isEqualTo(1);
        assertThat(visibleCommentIds())
                .as("放行后前台必须立刻能看到它（否则'放行'等于没生效）")
                .contains((int) pending);
        assertThat(auditLogCount(pending)).as("放行也必须留痕（合规 C9）").isEqualTo(1);
        assertThat(lastAuditDetail(pending)).as("留痕要记下状态变化").isEqualTo("status:0->1");

        // ③ 屏蔽（方向二）→ status=2，前台立刻不可见，且再留一条痕
        Response block = adminPut("/api/admin/comments/" + pending + "/status",
                Map.of("status", 2, "reason", "广告垃圾（审核用例）"));
        assertOk(block);
        assertThat(commentStatus(pending))
                .as("屏蔽后评论必须变已屏蔽(2)").isEqualTo(2);
        assertThat(visibleCommentIds())
                .as("屏蔽后前台必须立刻看不到它").doesNotContain((int) pending);
        assertThat(auditLogCount(pending))
                .as("屏蔽同样要留痕 —— 两个方向都要有记录").isEqualTo(2);

        // ④ 反向自证：屏蔽一条**不存在的**评论必须 404，而不是"静默成功"
        Response missing = adminPut("/api/admin/comments/99999999/status",
                Map.of("status", 2, "reason", "不存在"));
        assertThat(missing.jsonPath().getInt("code"))
                .as("对不存在的评论审核必须 404。响应：%s", missing.asString())
                .isEqualTo(404);

        // ⑤ 不接受 status=0：那会把已处置的内容重新塞回队列（无业务含义）
        Response backToPending = adminPut("/api/admin/comments/" + pending + "/status",
                Map.of("status", 0));
        assertThat(backToPending.jsonPath().getInt("code"))
                .as("status=0 必须被拒（不能把已处置的评论重新塞回队列）。响应：%s", backToPending.asString())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("屏蔽必须给 reason（合规 C9：处置依据可追溯）；不给 → 400 且状态不变")
    void blocking_requires_reason() {
        long pending = insertPendingComment("待审评论-理由缺失场景");

        Response noReason = adminPut("/api/admin/comments/" + pending + "/status",
                Map.of("status", 2));
        assertThat(noReason.jsonPath().getInt("code"))
                .as("屏蔽不给 reason 必须 400（合规 C9）。响应：%s", noReason.asString())
                .isEqualTo(400);
        // 关键：必须**没有**被屏蔽（只看状态码不够 —— 一个"先改后拒"的实现也能返回 400）
        assertThat(commentStatus(pending))
                .as("被拒的审核请求不得改变数据").isZero();
        assertThat(auditLogCount(pending)).as("被拒的请求也不该留痕").isZero();
    }

    // ==================================================================
    // 鉴权（§9 后台隔离）
    // ==================================================================

    @Test
    @DisplayName("审核接口必须走后台登录态：前台 token 与匿名都进不来")
    void audit_endpoints_require_admin_token() {
        long pending = insertPendingComment("待审评论-鉴权场景");

        // 匿名
        Response anonymous = RestAssured.given()
                .contentType(ContentType.JSON)
                .body(Map.of("status", 1))
                .put("/api/admin/comments/" + pending + "/status");
        assertThat(anonymous.statusCode())
                .as("匿名调用后台审核必须 401。响应：%s", anonymous.asString())
                .isEqualTo(401);

        // 前台用户 token（前后台两套独立 StpLogic，§9）
        Response asUser = RestAssured.given()
                .header("Authorization", commenter.token())
                .contentType(ContentType.JSON)
                .body(Map.of("status", 1))
                .put("/api/admin/comments/" + pending + "/status");
        assertThat(asUser.statusCode())
                .as("**前台 token 绝不能通过后台审核**（后台隔离）。响应：%s", asUser.asString())
                .isEqualTo(401);

        assertThat(commentStatus(pending))
                .as("两次被拒的请求都不得改变数据").isZero();
    }

    // ==================================================================
    // 小工具
    // ==================================================================

    /** 造一条**待审**评论（status=0）。<b>直接写库</b>：本类验的是出口机制，不是敏感词命中。 */
    private long insertPendingComment(String content) {
        jdbcTemplate.update(
                "INSERT INTO comment (post_id, user_id, parent_id, root_id, content, status) "
                        + "VALUES (?, ?, 0, 0, ?, 0)",
                postId, commenter.id(), content);
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM comment WHERE post_id = ? ORDER BY id DESC LIMIT 1", Long.class, postId);
        assertThat(id).as("造待审评论后必须能查到 id").isNotNull();
        return id;
    }

    private int commentStatus(long commentId) {
        Integer status = jdbcTemplate.queryForObject(
                "SELECT status FROM comment WHERE id = ?", Integer.class, commentId);
        return status == null ? -1 : status;
    }

    /** 前台评论列表里可见的主楼 id（用于证明"放行后可见 / 屏蔽后不可见"）。 */
    private java.util.List<Integer> visibleCommentIds() {
        Response response = listComments(postId, 1, 20);
        assertOk(response);
        java.util.List<Integer> ids = response.jsonPath().getList("data.list.id");
        return ids == null ? java.util.List.of() : ids;
    }

    private int auditLogCount(long commentId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM admin_operation_log WHERE action = ? AND target_type = 2 AND target_id = ?",
                Integer.class, ACTION_COMMENT_STATUS, commentId);
        return count == null ? 0 : count;
    }

    private String lastAuditDetail(long commentId) {
        return jdbcTemplate.queryForObject(
                "SELECT detail FROM admin_operation_log WHERE action = ? AND target_id = ? "
                        + "ORDER BY id DESC LIMIT 1",
                String.class, ACTION_COMMENT_STATUS, commentId);
    }

    /** 造一个管理员并登录（走真实后台登录接口，与运维同一条路径）。 */
    private String createAdminAndLogin() {
        String username = "m5admin" + java.util.concurrent.ThreadLocalRandom.current().nextInt(100000);
        jdbcTemplate.update(
                "INSERT INTO admin (username, password_hash, nickname, role, status) VALUES (?, ?, ?, 'ADMIN', 1)",
                username, passwordEncoder.encode(TEST_PASSWORD), "审核员");
        Response response = RestAssured.given()
                .contentType(ContentType.JSON)
                .body(Map.of("username", username, "password", TEST_PASSWORD))
                .post("/api/admin/login");
        String token = response.jsonPath().getString("data.token");
        assertThat(token)
                .as("后台登录必须成功，否则本类全部无意义。响应：%s", response.asString())
                .isNotBlank();
        return token;
    }

    private Response adminGet(String path) {
        return RestAssured.given().header("Authorization", adminToken).get(path);
    }

    private Response adminPut(String path, Map<String, Object> body) {
        return RestAssured.given()
                .header("Authorization", adminToken)
                .contentType(ContentType.JSON)
                .body(body)
                .put(path);
    }
}
