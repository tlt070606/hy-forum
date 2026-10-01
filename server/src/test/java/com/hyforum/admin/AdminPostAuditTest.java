package com.hyforum.admin;

import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 帖子审核（M6 批次一；PLAN M6 验收"管理员可审核帖子……留痕可追溯"）。
 *
 * <h2>与 M5 评论审核用例的关系</h2>
 * <p>同一套规矩的两个资源实例（§6.11 留痕红线 / 双向处置 / reason 必填）。
 * 用例结构刻意保持同构：队列可见性 → 放行 → 屏蔽（无理由拒绝 + 有理由成功）→
 * 非法状态拒绝 → 不存在 404 → 前后台隔离。同构不是复制粘贴的懒惰 ——
 * 它让评审者可以逐条对照"规矩在两个资源上是否等价成立"。</p>
 */
class AdminPostAuditTest extends AdminApiTestSupport {

    private long authorId;
    private long boardId;
    private long pendingPostId;
    private long normalPostId;
    private String authorToken;

    @org.junit.jupiter.api.BeforeEach
    void seed() {
        TestUser author = createUser("帖子作者");
        authorId = author.id();
        authorToken = author.token();
        boardId = createBoard();
        pendingPostId = createPost(boardId, authorId, "待审帖子（测试造数）", 0);
        normalPostId = createPost(boardId, authorId, "正常帖子（测试造数）", 1);
    }

    @Test
    @DisplayName("M6_post_audit_release_and_block：待审帖可见于队列，放行/屏蔽双向生效且留痕")
    void M6_post_audit_release_and_block() {
        // ① 队列能看到待审帖；看不到正常帖（status=0 筛选）
        Response queue = adminGet("/api/admin/posts?status=0&size=20");
        assertThat(queue.jsonPath().getInt("code")).isZero();
        assertThat(queue.jsonPath().<java.util.List<Integer>>get("data.list.id"))
                .as("待审队列必须包含待审帖。响应：%s", queue.asString())
                .contains((int) pendingPostId)
                .doesNotContain((int) normalPostId);
        // 队列项带作者昵称（前端展示需要，不必二次查询）
        assertThat(queue.jsonPath().getString("data.list[0].authorNickname"))
                .as("队列项必须带作者昵称（批量装配而非 N+1）")
                .isNotBlank();

        // ② 放行（0 → 1）：前台列表立刻可见
        Response release = adminPut("/api/admin/posts/" + pendingPostId + "/status",
                java.util.Map.of("status", 1));
        assertThat(release.jsonPath().getInt("code"))
                .as("放行必须成功。响应：%s", release.asString()).isZero();
        assertThat(frontPostIds())
                .as("放行后帖子必须出现在前台版块列表").contains((int) pendingPostId);

        // ③ 屏蔽：不填 reason 必须被拒（合规 C9）
        Response noReason = adminPut("/api/admin/posts/" + pendingPostId + "/status",
                java.util.Map.of("status", 2));
        assertThat(noReason.jsonPath().getInt("code"))
                .as("屏蔽不填 reason 必须被拒。响应：%s", noReason.asString()).isEqualTo(400);

        // ④ 屏蔽（1 → 2）填 reason：前台立刻不可见 + 留痕
        Response block = adminPut("/api/admin/posts/" + pendingPostId + "/status",
                java.util.Map.of("status", 2, "reason", "广告帖（审核用例）"));
        assertThat(block.jsonPath().getInt("code")).isZero();
        assertThat(frontPostIds())
                .as("屏蔽后帖子必须从前台列表消失").doesNotContain((int) pendingPostId);
        assertThat(postColumn(pendingPostId, "status", Integer.class)).isEqualTo(2);

        // ⑤ 留痕：两条（放行 + 屏蔽），detail 记录状态迁移
        Response logs = adminGet("/api/admin/logs?action=POST_STATUS&size=20");
        assertThat(logs.jsonPath().<java.util.List<Integer>>get("data.list.targetId"))
                .as("POST_STATUS 留痕必须指向本帖").contains((int) pendingPostId);
        assertThat(logs.jsonPath().<java.util.List<String>>get("data.list.detail"))
                .as("留痕必须记录状态迁移。响应：%s", logs.asString())
                .contains("status:0->1", "status:1->2");

        // ⑥ 非法目标状态：0（塞回队列）与缺失都必须 400
        assertThat(adminPut("/api/admin/posts/" + pendingPostId + "/status",
                java.util.Map.of("status", 0)).jsonPath().getInt("code")).isEqualTo(400);
        assertThat(adminPut("/api/admin/posts/" + pendingPostId + "/status",
                java.util.Map.of()).jsonPath().getInt("code")).isEqualTo(400);

        // ⑦ 不存在的帖子 → 404
        assertThat(adminPut("/api/admin/posts/99999999/status",
                java.util.Map.of("status", 1)).jsonPath().getInt("code")).isEqualTo(404);
    }

    @Test
    @DisplayName("M6_post_audit_isolation：匿名/前台 token 打后台 401；admin token 打前台也 401")
    void M6_post_audit_isolation() {
        // 匿名 → 401
        assertThat(RestAssured.get("/api/admin/posts").statusCode())
                .as("匿名访问后台帖子队列必须 401").isEqualTo(401);

        // 前台 token → 401（后台隔离：两套 StpLogic，token 不通用）
        Response asUser = RestAssured.given()
                .header("Authorization", authorToken)
                .get("/api/admin/posts");
        assertThat(asUser.statusCode())
                .as("前台 token 打后台必须 401。响应：%s", asUser.asString()).isEqualTo(401);

        // 反向：admin token 打前台用户接口也必须 401（§9）
        Response adminOnFront = RestAssured.given()
                .header("Authorization", adminToken)
                .get("/api/user/me");
        assertThat(adminOnFront.statusCode())
                .as("admin token 打前台必须 401（双向隔离）。响应：%s", adminOnFront.asString())
                .isEqualTo(401);
    }

    /** 前台版块帖子列表里的 id 集合（可见口径：status=1 且未删除）。 */
    private java.util.List<Integer> frontPostIds() {
        Response response = RestAssured.given()
                .queryParams(java.util.Map.of("boardId", boardId, "page", 1, "size", 20))
                .get("/api/posts");
        assertThat(response.jsonPath().getInt("code"))
                .as("前台帖子列表必须可访问。响应：%s", response.asString()).isZero();
        java.util.List<Integer> ids = response.jsonPath().getList("data.list.id");
        return ids == null ? java.util.List.of() : ids;
    }
}
