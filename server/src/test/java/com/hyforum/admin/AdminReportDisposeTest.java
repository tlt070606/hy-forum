package com.hyforum.admin;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 举报处置（M6 批次二；PLAN M6 验收"处理举报"）。
 *
 * <h2>本类验的边界：处置登记 ≠ 内容屏蔽</h2>
 * <p>处置端点只登记结论（已处理/已驳回 + 必填说明 + 留痕），<b>不</b>动被举报内容 ——
 * 内容屏蔽走帖子/评论审核端点（单一写入口，口径不漂移）。用例显式断言：
 * 处置之后帖子状态<b>不变</b>，让"这条边界是刻意的"有机器证据。</p>
 */
class AdminReportDisposeTest extends AdminApiTestSupport {

    private TestUser reporter;
    private long postId;
    private long reportId;

    @org.junit.jupiter.api.BeforeEach
    void seed() {
        TestUser author = createUser("被举报作者");
        reporter = createUser("举报人");
        long boardId = createBoard();
        postId = createNormalPost(boardId, author.id(), "被举报的帖子");
        Response report = RestAssured.given()
                .header("Authorization", reporter.token())
                .contentType(ContentType.JSON)
                .body(java.util.Map.of("targetType", 1, "targetId", postId, "reasonType", 3))
                .post("/api/report");
        assertThat(report.jsonPath().getInt("code"))
                .as("造一条举报作为起点。响应：%s", report.asString()).isZero();
        reportId = jdbcTemplate.queryForObject(
                "SELECT id FROM report WHERE user_id = ? AND target_id = ?", Long.class,
                reporter.id(), postId);
    }

    @Test
    @DisplayName("M6_report_dispose：队列可见 + 处置/驳回 + note 必填 + 留痕锚定被举报对象")
    void M6_report_dispose() {
        // ① 待处理队列能看到，且带对象摘要与举报人昵称
        Response queue = adminGet("/api/admin/reports?status=0&size=20");
        assertThat(queue.jsonPath().getInt("code")).isZero();
        assertThat(queue.jsonPath().<java.util.List<Integer>>get("data.list.id"))
                .contains((int) reportId);
        assertThat(queue.jsonPath().getString("data.list[0].targetSummary"))
                .as("对象摘要应是帖子标题（处置时能看见背景）").isEqualTo("被举报的帖子");
        assertThat(queue.jsonPath().getString("data.list[0].reporterNickname")).isNotBlank();

        // ② note 缺失必须被拒（C9）
        assertThat(adminPut("/api/admin/reports/" + reportId + "/dispose",
                java.util.Map.of("outcome", 1)).jsonPath().getInt("code")).isEqualTo(400);

        // ③ 处置（outcome=1）：状态/处置人/时间/说明落库
        Response dispose = adminPut("/api/admin/reports/" + reportId + "/dispose",
                java.util.Map.of("outcome", 1, "note", "已同步屏蔽该帖（审核用例）"));
        assertThat(dispose.jsonPath().getInt("code"))
                .as("处置必须成功。响应：%s", dispose.asString()).isZero();
        assertThat(postColumn(postId, "status", Integer.class))
                .as("处置登记不动被举报内容（单一写入口）——帖子状态必须原样").isEqualTo(1);
        assertThat(reportColumn(reportId, "status", Integer.class)).isEqualTo(1);
        assertThat(reportColumn(reportId, "handler_id", Long.class))
                .as("处置人必须落库").isNotNull();
        assertThat(reportColumn(reportId, "handled_at", java.sql.Timestamp.class))
                .as("处置时间必须落库").isNotNull();

        // ④ 留痕：target 锚定被举报对象（追溯链），举报行 id 在 detail
        Response logs = adminGet("/api/admin/logs?action=REPORT_DISPOSE&size=20");
        assertThat(logs.jsonPath().<java.util.List<Integer>>get("data.list.targetId"))
                .as("留痕 target 必须是被举报帖子").contains((int) postId);
        assertThat(logs.jsonPath().<java.util.List<String>>get("data.list.detail"))
                .as("detail 记举报行与状态迁移").contains("report:" + reportId + ":status:0->1");

        // ⑤ 驳回（outcome=2）：再造一条举报走驳回路径
        long report2 = createAnotherReport();
        assertThat(adminPut("/api/admin/reports/" + report2 + "/dispose",
                java.util.Map.of("outcome", 2, "note", "内容未违规")).jsonPath().getInt("code"))
                .isZero();
        assertThat(reportColumn(report2, "status", Integer.class)).isEqualTo(2);

        // ⑥ 不存在的举报 → 404；非法 outcome → 400
        assertThat(adminPut("/api/admin/reports/99999999/dispose",
                java.util.Map.of("outcome", 1, "note", "x")).jsonPath().getInt("code")).isEqualTo(404);
        assertThat(adminPut("/api/admin/reports/" + reportId + "/dispose",
                java.util.Map.of("outcome", 9, "note", "x")).jsonPath().getInt("code")).isEqualTo(400);
    }

    private long createAnotherReport() {
        long boardId = createBoard();
        long pid = createNormalPost(boardId, reporter.id(), "第二张举报的目标帖");
        TestUser another = createUser("另一位举报人");
        RestAssured.given()
                .header("Authorization", another.token())
                .contentType(ContentType.JSON)
                .body(java.util.Map.of("targetType", 1, "targetId", pid, "reasonType", 5))
                .post("/api/report");
        return jdbcTemplate.queryForObject(
                "SELECT id FROM report WHERE target_id = ?", Long.class, pid);
    }

    private <T> T reportColumn(long reportId, String column, Class<T> type) {
        return jdbcTemplate.queryForObject(
                "SELECT `" + column + "` FROM report WHERE id = ?", type, reportId);
    }
}
