package com.hyforum.post;

import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 版块列表（docs/技术方案.md §6.4）。
 *
 * <p>对应验收项：{@code M3_board_list_marks_resource_board}（本任务新增，待 L1 登记进映射表）——
 * 版块列表必须带 {@code isResource} 标记。</p>
 *
 * <h2>为什么这条必须测，而且必须断言 JSON 字段名</h2>
 * <p>{@code isResource} 不是一个装饰性字段：<b>发帖表单是否显示网盘字段完全由它决定</b>
 * （§4.3 的注释：「版块是数据库中的数据…{@code is_resource} 单独控制发帖表单的字段可见性」）。
 * 它又是一个极容易静默失真的字段名 —— Java 的 boolean 访问器命名规则（{@code isXxx}）
 * 与 Jackson 的属性推导叠加时，字段名完全可能被序列化成 {@code resource} 而不是
 * {@code isResource}：<b>编译期毫无提示，前端只会拿到 undefined，表现为"资源版不显示网盘输入框"</b>。
 * 因此这里断言的是<b>响应报文里的字面字段名</b>，而不是 Java 对象上的属性名。</p>
 *
 * <p>同时断言"停用版块不出现"：{@code board.status=0} 表示停用（§5.3 表 2），
 * 若列表不过滤它，停用版块在前台依然可见 —— 那就等于没有停用。</p>
 */
@DisplayName("M3 · 版块列表")
class M3BoardApiTest extends M3ApiTestSupport {

    @Test
    void M3_board_list_marks_resource_board() {
        // ---------- 造数：一个资源版块 + 一个普通版块 + 一个停用版块 ----------
        long resourceBoardId = createResourceBoard();
        long normalBoardId = createNormalBoard();
        long disabledBoardId = createBoard("已停用版块", 0, 0);

        Response response = io.restassured.RestAssured.given().get("/api/boards");

        assertThat(response.statusCode()).as("版块列表免登录，必须是 200：%s", response.asString()).isEqualTo(200);
        assertThat(response.jsonPath().getInt("code")).isZero();

        List<Map<String, Object>> boards = response.jsonPath().getList("data");
        assertThat(boards).as("data 必须是数组（§6.4）").isNotNull();

        Map<String, Object> resource = findById(boards, resourceBoardId);
        Map<String, Object> normal = findById(boards, normalBoardId);

        // ---------- 关键断言：JSON 里的字面字段名是 isResource ----------
        assertThat(resource)
                .as("资源版块必须带 isResource 字段（前端据此渲染网盘输入框），实际字段：%s", resource.keySet())
                .containsKey("isResource");
        assertThat(resource.get("isResource"))
                .as("资源版块的 isResource 必须是 true，实际：%s", resource.get("isResource"))
                .isEqualTo(Boolean.TRUE);
        assertThat(normal.get("isResource"))
                .as("普通版块的 isResource 必须是 false")
                .isEqualTo(Boolean.FALSE);

        // ---------- 契约 §6.4 要求的其余字段 ----------
        assertThat(resource).containsKeys("id", "name", "slug");
        assertThat(resource.get("name")).isEqualTo("资源分享");

        // ---------- 反证：停用版块确实存在，但不得出现在列表里 ----------
        Integer disabledRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM board WHERE id = ? AND status = 0", Integer.class, disabledBoardId);
        assertThat(disabledRows)
                .as("前置条件：停用版块必须真实存在于库里（否则下面的断言毫无意义）")
                .isEqualTo(1);
        assertThat(boards.stream().noneMatch(b -> String.valueOf(disabledBoardId).equals(String.valueOf(b.get("id")))))
                .as("停用版块不得出现在前台列表里，实际列表：%s", boards)
                .isTrue();
    }

    /** 从列表里按 id 找一条（id 的 JSON 类型可能是 Integer，故用字符串比较，避免拆箱陷阱）。 */
    private static Map<String, Object> findById(List<Map<String, Object>> boards, long id) {
        return boards.stream()
                .filter(b -> String.valueOf(id).equals(String.valueOf(b.get("id"))))
                .findFirst()
                .orElseThrow(() -> new AssertionError("版块 " + id + " 不在列表里，实际列表：" + boards));
    }
}
