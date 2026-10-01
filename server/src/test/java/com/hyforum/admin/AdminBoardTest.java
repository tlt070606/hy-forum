package com.hyforum.admin;

import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 版块管理（M6 批次二；PLAN M6 验收"维护版块"）。
 *
 * <p>核心验收点：slug 唯一且不可改；停用（status=0）后前台版块列表立即隐藏、
 * 后台仍可见（后台的职责是看见前台看不见的东西）。</p>
 */
class AdminBoardTest extends AdminApiTestSupport {

    @Test
    @DisplayName("M6_board_crud：新建/更新/停用 + slug 规则 + 前台即时可见性 + 留痕")
    void M6_board_crud() {
        String slug = "m6board-" + java.util.concurrent.ThreadLocalRandom.current().nextInt(100000);

        // ① 新建：后台列表与前台列表同时可见
        Response create = adminPost("/api/admin/boards", java.util.Map.of(
                "name", "测试版块甲", "slug", slug, "description", "M6 用例版块",
                "sort", 99, "isResource", 1));
        assertThat(create.jsonPath().getInt("code"))
                .as("新建版块必须成功。响应：%s", create.asString()).isZero();
        long boardId = jdbcTemplate.queryForObject(
                "SELECT id FROM board WHERE slug = ?", Long.class, slug);
        assertThat(adminSlugList()).contains(slug);
        assertThat(frontSlugList())
                .as("新建的启用版块必须立刻出现在前台").contains(slug);

        // ② slug 冲突 / 非法 slug 必须被拒（可读 400 而不是 500）
        assertThat(adminPost("/api/admin/boards", java.util.Map.of(
                "name", "重复版块", "slug", slug)).jsonPath().getInt("code")).isEqualTo(400);
        assertThat(adminPost("/api/admin/boards", java.util.Map.of(
                "name", "非法版块", "slug", "Bad_Slug!")).jsonPath().getInt("code")).isEqualTo(400);

        // ③ 更新名称与排序（slug 字段即使传了也不生效）
        Response update = adminPut("/api/admin/boards/" + boardId, java.util.Map.of(
                "name", "测试版块甲改名", "slug", "hacked-slug", "sort", 1));
        assertThat(update.jsonPath().getInt("code")).isZero();
        assertThat(boardColumn(boardId, "slug", String.class))
                .as("slug 创建后不可改").isEqualTo(slug);
        assertThat(boardColumn(boardId, "name", String.class)).isEqualTo("测试版块甲改名");

        // ④ 停用：前台立刻隐藏，后台仍可见（status=0）
        assertThat(adminPut("/api/admin/boards/" + boardId,
                java.util.Map.of("status", 0)).jsonPath().getInt("code")).isZero();
        assertThat(frontSlugList())
                .as("停用版块必须从前台消失").doesNotContain(slug);
        assertThat(adminSlugList()).as("后台必须仍能看到停用版块").contains(slug);

        // ⑤ 留痕：CREATE + UPDATE（含 no-op 的确认型更新）
        Response createLogs = adminGet("/api/admin/logs?action=BOARD_CREATE&size=20");
        assertThat(createLogs.jsonPath().<java.util.List<String>>get("data.list.detail"))
                .as("创建留痕带 slug").contains("board:" + slug + " name:测试版块甲");
        Response updateLogs = adminGet("/api/admin/logs?action=BOARD_UPDATE&size=20");
        assertThat(updateLogs.jsonPath().<java.util.List<String>>get("data.list.detail"))
                .as("更新留痕记录状态迁移").anySatisfy(
                        d -> assertThat(d).contains("status:1->0"));

        // ⑥ 不存在的版块 → 404
        assertThat(adminPut("/api/admin/boards/99999999",
                java.util.Map.of("name", "不存在")).jsonPath().getInt("code")).isEqualTo(404);
    }

    /** 后台版块 slug 列表。 */
    private java.util.List<String> adminSlugList() {
        Response response = adminGet("/api/admin/boards");
        assertThat(response.jsonPath().getInt("code")).isZero();
        java.util.List<String> slugs = response.jsonPath().getList("data.slug");
        return slugs == null ? java.util.List.of() : slugs;
    }

    /** 前台版块 slug 列表（只含启用版块）。 */
    private java.util.List<String> frontSlugList() {
        Response response = io.restassured.RestAssured.get("/api/boards");
        assertThat(response.jsonPath().getInt("code")).isZero();
        java.util.List<String> slugs = response.jsonPath().getList("data.slug");
        return slugs == null ? java.util.List.of() : slugs;
    }

    private <T> T boardColumn(long boardId, String column, Class<T> type) {
        return jdbcTemplate.queryForObject(
                "SELECT `" + column + "` FROM board WHERE id = ?", type, boardId);
    }
}
