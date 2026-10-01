package com.hyforum.admin;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 敏感词管理（M6 批次二；PLAN M6 验收"维护敏感词"）。
 *
 * <h2>本类的核心价值：刷新闭环的<b>端到端</b>证据</h2>
 * <p>"改词库 → 内存快照刷新 → 新词立即参与判定"这条链，只断言"表里有这行词"
 * 是不够的 —— 那恰恰漏掉了"忘刷新"这个最可能的缺陷形态。本用例走真实发帖路径：
 * 加词 → 发含词帖 → 必须<b>立即</b>进待审；删词 → 再发同词帖 → 必须<b>立即</b>正常。
 * 两个方向都必须在无重启的同一个进程里完成。</p>
 *
 * <h2>与 M5 的边界（如实声明）</h2>
 * <p>本类验的是"词库管理 → 判定生效"的机制，<b>不是</b>内容决策：
 * 测试用词是自造的无害字符串，不代表任何真实违规内容（M5 任务书 §0 的同款声明）。</p>
 */
class AdminSensitiveWordTest extends AdminApiTestSupport {

    /** 自造测试词：足以命中机制，不构成真实违规内容。 */
    private static final String WORD = "zzx测试敏感词" + java.util.concurrent.ThreadLocalRandom.current().nextInt(1000);

    @Test
    @DisplayName("M6_sensitive_words：加词立即生效（帖进待审）+ 删词立即失效（帖正常）+ 去重 + 留痕")
    void M6_sensitive_words() {
        TestUser author = createUser("发帖作者");
        stringRedisTemplate.delete("hy:rl:post-new-user-24h:" + author.id());
        stringRedisTemplate.delete("hy:rl:post-hourly:" + author.id());
        long boardId = createBoard();

        // ① 加词 → 列表可见 → 重复添加被拒
        Response add = adminPost("/api/admin/sensitive-words", java.util.Map.of("word", WORD));
        assertThat(add.jsonPath().getInt("code"))
                .as("加词必须成功。响应：%s", add.asString()).isZero();
        long wordId = jdbcTemplate.queryForObject(
                "SELECT id FROM sensitive_word WHERE word = ?", Long.class, WORD);
        assertThat(adminGet("/api/admin/sensitive-words?size=50")
                .jsonPath().<java.util.List<String>>get("data.list.word"))
                .contains(WORD);
        assertThat(adminPost("/api/admin/sensitive-words", java.util.Map.of("word", WORD))
                .jsonPath().getInt("code"))
                .as("重复加词必须 400（uk_word）").isEqualTo(400);

        // ② 端到端（加词方向）：含词帖必须立即进待审（0），前台不可见
        long postId1 = createPostViaApi(author.token(), boardId, "含词帖一", "正文提到 " + WORD);
        assertThat(postColumn(postId1, "status", Integer.class))
                .as("加词后（无需重启）含词帖必须立即进待审")
                .isEqualTo(0);
        assertThat(frontListContains(boardId, postId1))
                .as("待审帖不得出现在前台列表").isFalse();

        // ③ 审核放行该帖（走批次一的审核端点，正常可见）
        assertThat(adminPut("/api/admin/posts/" + postId1 + "/status",
                java.util.Map.of("status", 1)).jsonPath().getInt("code")).isZero();

        // ④ 端到端（删词方向）：删词后同词帖立即正常（1）
        assertThat(adminDelete("/api/admin/sensitive-words/" + wordId).jsonPath().getInt("code")).isZero();
        long postId2 = createPostViaApi(author.token(), boardId, "含词帖二", "正文提到 " + WORD);
        assertThat(postColumn(postId2, "status", Integer.class))
                .as("删词后（无需重启）同词帖必须立即正常")
                .isEqualTo(1);

        // ⑤ 留痕：CREATE / DELETE 各一条，detail 带词本身
        assertThat(adminGet("/api/admin/logs?action=SENSITIVE_WORD_CREATE&size=20")
                .jsonPath().<java.util.List<String>>get("data.list.detail"))
                .contains("word:" + WORD);
        assertThat(adminGet("/api/admin/logs?action=SENSITIVE_WORD_DELETE&size=20")
                .jsonPath().<java.util.List<String>>get("data.list.detail"))
                .contains("word:" + WORD);

        // ⑥ 删不存在的词 → 404；空词 → 400
        assertThat(adminDelete("/api/admin/sensitive-words/99999999").jsonPath().getInt("code"))
                .isEqualTo(404);
        assertThat(adminPost("/api/admin/sensitive-words", java.util.Map.of("word", " "))
                .jsonPath().getInt("code")).isEqualTo(400);
    }

    /** 走真实发帖接口造帖（绕过接口的造数测不出敏感词判定）。 */
    private long createPostViaApi(String token, long boardId, String title, String content) {
        Response response = RestAssured.given()
                .header("Authorization", token)
                .contentType(ContentType.JSON)
                .body(java.util.Map.of("boardId", boardId, "title", title, "content", content))
                .post("/api/posts");
        assertThat(response.jsonPath().getInt("code"))
                .as("发帖必须成功（否则后续断言无意义）。响应：%s", response.asString())
                .isZero();
        return response.jsonPath().getLong("data.id");
    }

    /** 前台版块列表是否包含该帖。 */
    private boolean frontListContains(long boardId, long postId) {
        Response response = RestAssured.given()
                .queryParams(java.util.Map.of("boardId", boardId, "page", 1, "size", 20))
                .get("/api/posts");
        java.util.List<Integer> ids = response.jsonPath().getList("data.list.id");
        return ids != null && ids.contains((int) postId);
    }

    /** 后台 DELETE（support 类只封装了 GET/POST/PUT）。 */
    private Response adminDelete(String path) {
        return RestAssured.given().header("Authorization", adminToken).delete(path);
    }
}
