package com.hyforum.admin;

import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 帖子管理动作（M6 批次二）：置顶 / 加精 / 管理端删除。
 *
 * <h2>管理端删除与作者删帖同口径（如实登记的边界）</h2>
 * <p>管理端删除 = 逻辑删除 + post_count/board.post_count 回退，与 M3 作者删帖
 * <b>完全一致</b>——包括"互动关系行保留、互动计数不动"这个边界（CR-M4-2 的
 * 收尾接线至今未落，admin → interaction 又是铁律 3 禁区；见
 * {@code AdminPostService#deletePost} 的长注释）。用例把这个边界显式断言出来：
 * 删帖后 like_count 等保持原值 —— 这不是缺陷，是当前登记的口径。</p>
 *
 * <h2>附带回归：CR-M4-3（GREATEST → IF）</h2>
 * <p>M3 作者删帖在 post_count=0 时会因 UNSIGNED 下溢抛 1690 → 500。
 * 本批修复为 IF(col &gt; 0, col-1, 0)，这里用"SQL 造帖（post_count 不自增）→ 作者删帖"
 * 复现当时的触发条件，钉住不回归。</p>
 */
class AdminPostManageTest extends AdminApiTestSupport {

    @org.junit.jupiter.api.BeforeEach
    void seed() {
        // 留空：各用例自造数据（互不依赖）
    }

    @Test
    @DisplayName("M6_post_top_essence：置顶/加精开关生效 + 留痕记录迁移")
    void M6_post_top_essence() {
        TestUser author = createUser("置顶作者");
        long boardId = createBoard();
        long postId = createNormalPost(boardId, author.id(), "置顶加精用例帖");

        // 置顶开 → 关
        assertThat(adminPut("/api/admin/posts/" + postId + "/top",
                java.util.Map.of("top", true)).jsonPath().getInt("code")).isZero();
        assertThat(postColumn(postId, "is_top", Integer.class)).isEqualTo(1);
        assertThat(adminPut("/api/admin/posts/" + postId + "/top",
                java.util.Map.of("top", false)).jsonPath().getInt("code")).isZero();
        assertThat(postColumn(postId, "is_top", Integer.class)).isZero();

        // 加精开 → 关
        assertThat(adminPut("/api/admin/posts/" + postId + "/essence",
                java.util.Map.of("essence", true)).jsonPath().getInt("code")).isZero();
        assertThat(postColumn(postId, "is_essence", Integer.class)).isEqualTo(1);

        // 参数缺失 → 400
        assertThat(adminPut("/api/admin/posts/" + postId + "/top",
                java.util.Map.of()).jsonPath().getInt("code")).isEqualTo(400);

        // 留痕
        assertThat(adminGet("/api/admin/logs?action=POST_TOP&size=20")
                .jsonPath().<java.util.List<String>>get("data.list.detail"))
                .contains("is_top:0->1", "is_top:1->0");
        assertThat(adminGet("/api/admin/logs?action=POST_ESSENCE&size=20")
                .jsonPath().<java.util.List<String>>get("data.list.detail"))
                .contains("is_essence:0->1");
    }

    @Test
    @DisplayName("M6_admin_delete_post：reason 必填 + 逻辑删除 + 计数回退 + 互动计数保持（登记口径）")
    void M6_admin_delete_post() {
        TestUser author = createUser("删帖作者");
        clearPostRateKeys(author.id());
        long boardId = createBoard();
        // 走 API 发帖：user.post_count / board.post_count 会 +1，回退断言才有起点
        long postId = createPostViaApi(author.token(), boardId, "管理端删除用例帖");
        assertThat(userColumn(author.id(), "post_count", Integer.class)).isEqualTo(1);
        int boardCountBefore = boardColumn(boardId, "post_count", Integer.class);
        assertThat(postColumn(postId, "like_count", Integer.class)).isZero();

        // 先造一个点赞（关系行 + 计数），验证删除后互动侧按登记口径保持原样
        TestUser liker = createUser("点赞路人");
        assertThat(RestAssured.given().header("Authorization", liker.token())
                .post("/api/posts/" + postId + "/like").jsonPath().getInt("code")).isZero();
        assertThat(postColumn(postId, "like_count", Integer.class)).isEqualTo(1);

        // reason 缺失 → 400
        assertThat(adminDelete("/api/admin/posts/" + postId,
                java.util.Map.of()).jsonPath().getInt("code")).isEqualTo(400);

        // 删除成功：前台消失 + post_count 双回退 + 留痕
        Response delete = adminDelete("/api/admin/posts/" + postId,
                java.util.Map.of("reason", "违规广告帖（管理端删除用例）"));
        assertThat(delete.jsonPath().getInt("code"))
                .as("管理端删除必须成功。响应：%s", delete.asString()).isZero();
        assertThat(postColumn(postId, "is_deleted", Integer.class)).isEqualTo(1);
        assertThat(userColumn(author.id(), "post_count", Integer.class))
                .as("作者 post_count 必须回退").isZero();
        assertThat(boardColumn(boardId, "post_count", Integer.class))
                .isEqualTo(boardCountBefore - 1);
        assertThat(frontListContains(boardId, postId))
                .as("删除后前台不可见").isFalse();

        // 登记口径：互动关系行保留、like_count 不动（CR-M4-2 未接线，与作者删帖一致）
        assertThat(postColumn(postId, "like_count", Integer.class))
                .as("互动计数按当前登记口径保持原样（对账脚本兜底）").isEqualTo(1);
        assertThat(adminGet("/api/admin/logs?action=POST_DELETE&size=20")
                .jsonPath().<java.util.List<String>>get("data.list.detail"))
                .contains("post:is_deleted=1");

        // 重复删除 / 不存在 → 404（@TableLogic 已删行查不到）
        assertThat(adminDelete("/api/admin/posts/" + postId,
                java.util.Map.of("reason", "再删")).jsonPath().getInt("code")).isEqualTo(404);
    }

    @Test
    @DisplayName("M4_cr_m4_3_regression：post_count=0 时删帖不得 500（GREATEST→IF 回归钉）")
    void M4_cr_m4_3_regression() {
        TestUser author = createUser("回归作者");
        clearPostRateKeys(author.id());
        long boardId = createBoard();
        // SQL 造帖：不走发帖接口，user.post_count 保持 0（复现触发条件）
        long postId = createPost(boardId, author.id(), "计数为零的帖子", 1);
        assertThat(userColumn(author.id(), "post_count", Integer.class)).isZero();

        // 作者走前台删除接口：修复前这里 500（UNSIGNED 下溢 1690），修复后必须成功
        Response delete = RestAssured.given()
                .header("Authorization", author.token())
                .delete("/api/posts/" + postId);
        assertThat(delete.jsonPath().getInt("code"))
                .as("post_count=0 时删帖不得 500（CR-M4-3）。响应：%s", delete.asString())
                .isZero();
        assertThat(userColumn(author.id(), "post_count", Integer.class))
                .as("下限 0：不得为负也不得报错").isZero();
    }

    /** 清本用例作者的发帖限流键（清表重置自增 id 后，新用户会继承同 id 旧配额）。 */
    private void clearPostRateKeys(long userId) {
        stringRedisTemplate.delete("hy:rl:post-new-user-24h:" + userId);
        stringRedisTemplate.delete("hy:rl:post-hourly:" + userId);
    }

    private <T> T boardColumn(long boardId, String column, Class<T> type) {
        return jdbcTemplate.queryForObject(
                "SELECT `" + column + "` FROM board WHERE id = ?", type, boardId);
    }

    private long createPostViaApi(String token, long boardId, String title) {
        Response response = RestAssured.given()
                .header("Authorization", token)
                .contentType(io.restassured.http.ContentType.JSON)
                .body(java.util.Map.of("boardId", boardId, "title", title, "content", "正文"))
                .post("/api/posts");
        assertThat(response.jsonPath().getInt("code"))
                .as("发帖必须成功。响应：%s", response.asString()).isZero();
        return response.jsonPath().getLong("data.id");
    }

    private boolean frontListContains(long boardId, long postId) {
        Response response = RestAssured.given()
                .queryParams(java.util.Map.of("boardId", boardId, "page", 1, "size", 20))
                .get("/api/posts");
        java.util.List<Integer> ids = response.jsonPath().getList("data.list.id");
        return ids != null && ids.contains((int) postId);
    }

    private Response adminDelete(String path, java.util.Map<String, Object> body) {
        return RestAssured.given()
                .header("Authorization", adminToken)
                .contentType(io.restassured.http.ContentType.JSON)
                .body(body)
                .delete(path);
    }
}
