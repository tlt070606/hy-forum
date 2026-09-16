package com.hyforum.post.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 改帖请求体（docs/技术方案.md §6.5 {@code PUT /api/posts/{id}}）。
 *
 * <h2>为什么没有 {@code boardId}</h2>
 * <p>§6.5 把"内容变更"列举为「标题／正文／图片／网盘字段」，<b>不含版块</b>。
 * 允许改版块会让"某篇帖子出现在哪个版块"变成可以事后搬运的东西，
 * 而版块的 {@code post_count}、精华与置顶都是按版块维护的 ——
 * 这不是本任务能顺手定的事。契约里没有它，实现里就不加（要加请走 CR）。</p>
 *
 * <h2>字段全部可选 ≠ 可以不传</h2>
 * <p>PUT 的语义是"用请求体覆盖内容"，因此 <b>省略某个字段等于把它清空</b>：
 * 不传 {@code images} 就是"这张帖子不再有图"，不传 {@code diskUrl} 就是"去掉网盘信息"。
 * 这一条直接决定了 §8.6 第 6 条的编辑重审判定（内容是否变更）——
 * 若把"没传"理解成"保持不变"，作者就能用"只传标题"的方式悄悄改掉正文而不触发重审。</p>
 *
 * @param title    标题（必填，≤100 字）
 * @param content  正文（可选，≤10000 字）
 * @param images   图片 URL 列表（可空 = 清空图片）
 * @param diskType 网盘类型（仅资源版块）
 * @param diskUrl  网盘分享链接（仅资源版块）
 * @param diskCode 提取码（可选）
 */
@Schema(name = "PostUpdateRequest", description = "改帖请求体（仅作者、仅发布后 30 分钟内；任何内容变更都会让帖子回到待审）")
public record PostUpdateRequest(
        @Schema(description = "标题", example = "分享一套 Java 学习资料（已更新）")
        @NotBlank(message = "不能为空")
        @Size(max = 100, message = "不能超过 100 字")
        String title,

        @Schema(description = "正文")
        @Size(max = 10000, message = "不能超过 10000 字")
        String content,

        @Schema(description = "图片 URL 列表（不传 = 清空图片）")
        List<String> images,

        @Schema(description = "网盘类型：1 百度 / 2 阿里 / 3 夸克 / 4 天翼 / 5 迅雷 / 6 其他")
        Integer diskType,

        @Schema(description = "网盘分享链接")
        @Size(max = 500, message = "不能超过 500 字符")
        String diskUrl,

        @Schema(description = "提取码，可选")
        @Size(max = 20, message = "不能超过 20 字符")
        String diskCode) {
}
