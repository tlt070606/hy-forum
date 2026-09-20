package com.hyforum.audit.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 举报请求（技术方案 §6.11 的举报入口）。
 *
 * <p>字段对齐 {@code report} 表的列语义（schema 表 9）：</p>
 * <ul>
 *   <li>{@code targetType}：{@code 1帖子 2评论 3用户}；</li>
 *   <li>{@code reasonType}：{@code 1违法违规 2色情低俗 3广告垃圾 4侵权 5其他} ——
 *       契约把它做成<b>枚举选择</b>而不是自由文本，因此这里只有 {@code reasonDetail} 是可选补充；</li>
 *   <li>{@code reasonDetail}：≤200 字符（与列的 VARCHAR(200) 一致）。</li>
 * </ul>
 *
 * <p><b>这个 DTO 放在 {@code audit} 包（而不是 {@code interaction}）</b>：
 * 举报的入口与限流都在 {@code audit}（它是审核域的一部分）；
 * 放在 {@code interaction} 会让 {@code audit} 依赖 {@code interaction} ——
 * 铁律 3 禁止业务包互相依赖，ArchUnit 按包判。
 * （我在本任务里已经因为同一条规则被罚过一次，所以这次先定包位置再写代码。）</p>
 *
 * @param targetType   1 帖子 / 2 评论 / 3 用户
 * @param targetId     被举报对象的 id
 * @param reasonType   1 违法违规 / 2 色情低俗 / 3 广告垃圾 / 4 侵权 / 5 其他
 * @param reasonDetail 补充说明，可选
 */
@Schema(name = "ReportCreateRequest", description = "举报请求")
public record ReportCreateRequest(
        @NotNull(message = "不能为空")
        @Schema(description = "1 帖子 / 2 评论 / 3 用户")
        Integer targetType,

        @NotNull(message = "不能为空")
        Long targetId,

        @NotNull(message = "不能为空")
        @Schema(description = "1 违法违规 / 2 色情低俗 / 3 广告垃圾 / 4 侵权 / 5 其他")
        Integer reasonType,

        @Size(max = 200, message = "不能超过 200 字符")
        String reasonDetail) {
}
