package com.hyforum.audit;

import com.hyforum.interaction.M4ApiTestSupport;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审核状态变化与冗余计数的同步（2026-10-01 口径裁定的验收用例）。
 *
 * <h2>口径本身（需求方 2026-10-01 裁定）</h2>
 * <pre>
 *   post.comment_count == COUNT(comment WHERE post_id = ? AND is_deleted = 0 AND status = 1)
 *   主楼.reply_count   == COUNT(comment WHERE root_id = ? AND is_deleted = 0 AND status = 1)
 * </pre>
 * <p>待审（0）与已屏蔽（2）的评论<b>不进计数</b>：前台可见什么，数字就是什么。
 * 裁定前的实现有三处与它矛盾：发待审评论也 +1、屏蔽可见评论不 -1、
 * 放行待审评论不 +1 —— 用户会看到"3 条评论"点进去只有 1 条。
 * 本类把三个方向都钉死，并钉死删除路径的一个判别性场景（见
 * {@link #delete_root_with_hidden_child_does_not_over_decrement}）。</p>
 *
 * <h2>判别性场景为什么重要</h2>
 * <p>"屏蔽后计数 -1"这类断言，旧实现（屏蔽不减）会红、新实现绿 —— 这只证明新实现做了。
 * 而"删掉带隐藏楼中楼的主楼"是旧实现<b>减过头</b>的场景：旧行为按"删掉的总行数"扣
 * （含本来就不在计数里的隐藏行），扣到下限 0 恰好把<b>别的可见评论的份额</b>也扣没了。
 * 只有用这个场景，"旧错新对"才能表现为"旧红新绿"。</p>
 */
class M5CommentAuditCountSyncTest extends M4ApiTestSupport {

    private TestUser author;
    private TestUser commenter;
    private long postId;
    private String adminToken;

    @BeforeEach
    void seed() {
        author = createUser("帖子作者");
        commenter = createUser("评论者");
        long boardId = createBoard();
        postId = createNormalPost(boardId, author.id(), "审核计数用例帖");
        adminToken = createAdminAndLogin();
    }

    /** 与 M5CommentAuditTest 相同的清表清单：父类 + 通知 + 后台两张表。 */
    @Override
    protected String[] tablesToClean() {
        return new String[]{
                "admin_operation_log", "notification",
                "comment_like", "comment", "post_like", "post_collect", "follow",
                "post_image", "post", "board", "user", "admin"
        };
    }

    @Test
    @DisplayName("M5_audit_block_release_syncs_comment_count：屏蔽可见评论 -1、放行恢复 +1、放行待审 +1")
    void M5_audit_block_release_syncs_comment_count() {
        long visible = createCommentAndGetId(commenter.token(), postId, 0, "可见主楼");
        assertThat(postCommentCount()).as("发一条可见评论 → 计数 1").isEqualTo(1);

        // 屏蔽可见评论（1 → 2）：它对前台不可见了，计数必须跟着 -1
        assertOk(adminPut("/api/admin/comments/" + visible + "/status",
                Map.of("status", 2, "reason", "审核计数用例：屏蔽")));
        assertThat(postCommentCount())
                .as("屏蔽可见评论后 comment_count 必须 -1（裁定前这里是 1：数字比可见的多）")
                .isZero();
        assertThat(visibleRootCount()).as("屏蔽后前台列表应为空").isZero();

        // 放行（2 → 1）：恢复可见，计数必须 +1 回来
        assertOk(adminPut("/api/admin/comments/" + visible + "/status", Map.of("status", 1)));
        assertThat(postCommentCount()).as("放行后 comment_count 必须 +1 恢复").isEqualTo(1);

        // 待审评论不进计数：直接造一条待审（与 M5CommentAuditTest 同口径，验机制不验词库）
        long pending = insertCommentWithStatus("待审评论（测试注入）", 0);
        assertThat(postCommentCount())
                .as("待审评论不得进计数（裁定前这里是 2：待审也算数）").isEqualTo(1);

        // 放行待审（0 → 1）：从不可见变可见 → 计数 +1
        assertOk(adminPut("/api/admin/comments/" + pending + "/status", Map.of("status", 1)));
        assertThat(postCommentCount()).as("放行待审评论后 comment_count 必须 +1").isEqualTo(2);

        // 屏蔽待审（0 → 2）：两边都不可见，计数必须不动
        long pending2 = insertCommentWithStatus("另一条待审", 0);
        assertOk(adminPut("/api/admin/comments/" + pending2 + "/status",
                Map.of("status", 2, "reason", "审核计数用例：直接屏蔽待审")));
        assertThat(postCommentCount())
                .as("屏蔽一条**待审**评论（0→2）计数不得变化：两个状态都不在计数里")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("M5_audit_syncs_reply_count：屏蔽/放行楼中楼时，主楼 reply_count 同步增减")
    void M5_audit_syncs_reply_count() {
        long root = createCommentAndGetId(commenter.token(), postId, 0, "主楼");
        long reply = createCommentAndGetId(commenter.token(), postId, root, "楼中楼");
        assertThat(commentColumn(root, "reply_count", Integer.class))
                .as("发楼中楼后主楼 reply_count = 1").isEqualTo(1);
        assertThat(postCommentCount()).as("主楼 + 楼中楼 = 2 条可见").isEqualTo(2);

        assertOk(adminPut("/api/admin/comments/" + reply + "/status",
                Map.of("status", 2, "reason", "审核计数用例：屏蔽楼中楼")));
        assertThat(commentColumn(root, "reply_count", Integer.class))
                .as("屏蔽楼中楼后主楼 reply_count 必须 -1").isZero();
        assertThat(postCommentCount())
                .as("屏蔽楼中楼后帖子 comment_count 必须 -1").isEqualTo(1);

        assertOk(adminPut("/api/admin/comments/" + reply + "/status", Map.of("status", 1)));
        assertThat(commentColumn(root, "reply_count", Integer.class))
                .as("放行楼中楼后主楼 reply_count 必须 +1 恢复").isEqualTo(1);
        assertThat(postCommentCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("M5_delete_root_with_hidden_child：删带隐藏楼中楼的主楼，不得把别的评论的份额也扣掉")
    void delete_root_with_hidden_child_does_not_over_decrement() {
        long rootA = createCommentAndGetId(commenter.token(), postId, 0, "主楼 A（带隐藏楼中楼）");
        // 直接造一条已屏蔽的楼中楼（它不进计数：status=2）
        insertReplyWithStatus(rootA, "已屏蔽的楼中楼（测试注入）", 2);
        long rootB = createCommentAndGetId(commenter.token(), postId, 0, "主楼 B（无关评论）");
        assertThat(postCommentCount())
                .as("计数只算可见行：A + B = 2，隐藏楼中楼不算").isEqualTo(2);

        // 删掉主楼 A：该帖剩下的可见评论只有 B → 计数必须是 1。
        // 旧实现按"删掉的总行数"（A + 隐藏楼中楼 = 2 行）扣，把 B 的份额也扣没了（错误地变成 0）。
        assertOk(deleteComment(commenter.token(), rootA));
        assertThat(postCommentCount())
                .as("删主楼 A（自带 1 条隐藏楼中楼）后，计数必须只减 A 自己：剩 B 的 1。"
                        + "减成 0 说明把不在计数里的隐藏行也扣了")
                .isEqualTo(1);
        assertThat(visibleRootCount()).as("前台列表应只剩主楼 B").isEqualTo(1);
    }

    // ==================================================================
    // 小工具
    // ==================================================================

    private int postCommentCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT comment_count FROM post WHERE id = ?", Integer.class, postId);
        return count == null ? 0 : count;
    }

    private int visibleRootCount() {
        Response response = listComments(postId, 1, 20);
        assertOk(response);
        Integer size = response.jsonPath().getList("data.list.id").size();
        return size == null ? 0 : size;
    }

    /** 直接写库造一条指定状态的**主楼**评论（与 M5CommentAuditTest 同口径：验机制不验词库）。 */
    private long insertCommentWithStatus(String content, int status) {
        jdbcTemplate.update(
                "INSERT INTO comment (post_id, user_id, parent_id, root_id, content, status) "
                        + "VALUES (?, ?, 0, 0, ?, ?)",
                postId, commenter.id(), content, status);
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM comment WHERE post_id = ? ORDER BY id DESC LIMIT 1", Long.class, postId);
        assertThat(id).as("造评论后必须能查到 id").isNotNull();
        return id;
    }

    /** 直接写库造一条指定状态的**楼中楼**评论。 */
    private void insertReplyWithStatus(long rootId, String content, int status) {
        jdbcTemplate.update(
                "INSERT INTO comment (post_id, user_id, parent_id, root_id, content, status) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                postId, commenter.id(), rootId, rootId, content, status);
    }

    /** 造一个管理员并登录（与 M5CommentAuditTest 同一条真实路径）。 */
    private String createAdminAndLogin() {
        String username = "m5cnt" + java.util.concurrent.ThreadLocalRandom.current().nextInt(100000);
        jdbcTemplate.update(
                "INSERT INTO admin (username, password_hash, nickname, role, status) VALUES (?, ?, ?, 'ADMIN', 1)",
                username, passwordEncoder.encode(TEST_PASSWORD), "审核员");
        Response response = io.restassured.RestAssured.given()
                .contentType(io.restassured.http.ContentType.JSON)
                .body(Map.of("username", username, "password", TEST_PASSWORD))
                .post("/api/admin/login");
        String token = response.jsonPath().getString("data.token");
        assertThat(token)
                .as("后台登录必须成功，否则本类全部无意义。响应：%s", response.asString())
                .isNotBlank();
        return token;
    }

    private Response adminPut(String path, Map<String, Object> body) {
        return io.restassured.RestAssured.given()
                .header("Authorization", adminToken)
                .contentType(io.restassured.http.ContentType.JSON)
                .body(body)
                .put(path);
    }
}
