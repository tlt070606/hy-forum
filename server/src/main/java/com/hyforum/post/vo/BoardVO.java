package com.hyforum.post.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.hyforum.domain.board.entity.Board;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 版块列表项（docs/技术方案.md §6.4 {@code GET /api/boards}）。
 *
 * <p><b>为什么 {@code isResource} 要显式写 {@link JsonProperty}</b>：
 * Java 的 boolean 访问器命名规则（{@code isXxx}）与 Jackson 的属性推导叠加时，
 * 字段完全可能被序列化成 {@code resource} 而不是 {@code isResource}。
 * 这个错误<b>编译期毫无提示</b>，前端只会拿到 {@code undefined}，
 * 表现为"资源版块不显示网盘输入框"——一个功能静默失效。
 * 显式声明字段名之后，契约（由注解导出的 openapi.json）与运行时行为不可能分叉。</p>
 *
 * @param id         版块 id
 * @param name       版块名
 * @param slug       唯一短名
 * @param description 简介
 * @param iconUrl    图标
 * @param sort       排序值（升序）
 * @param isResource 1 = 资源版块（发帖表单显示网盘字段）
 * @param postCount  帖子数（冗余计数，用于列表展示）
 */
@Schema(name = "BoardVO", description = "版块列表项")
public record BoardVO(
        Long id,
        String name,
        String slug,
        String description,
        String iconUrl,
        Integer sort,
        @JsonProperty("isResource")
        @Schema(description = "是否资源版块（发帖表单据此显示网盘字段）")
        Boolean isResource,
        Integer postCount) {

    /**
     * 实体 → VO。
     *
     * <p>转换方法放在 VO 上（而不是给实体加 {@code toVo()}）：依赖方向保持
     * {@code vo → entity}，实体零依赖，后续每加一个视图都不必改实体。</p>
     */
    public static BoardVO from(Board board) {
        if (board == null) {
            return null;
        }
        return new BoardVO(
                board.getId(),
                board.getName(),
                board.getSlug(),
                board.getDescription(),
                board.getIconUrl(),
                board.getSort(),
                // 库里是 TINYINT（1/0），对外统一成布尔语义，避免前端写 ==1 这种脆判断
                board.getIsResource() != null && board.getIsResource() == 1,
                board.getPostCount());
    }
}
