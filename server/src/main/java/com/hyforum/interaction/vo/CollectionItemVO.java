package com.hyforum.interaction.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 「我的收藏」列表项（§6.3 {@code GET /api/user/collections}）。
 *
 * <p>契约没有细化这个列表的形状，因此按<b>最小可用</b>给：列表页要显示什么就给什么，
 * 不给正文（TEXT 字段，一页 20 条是几十上百 KB 的纯浪费 —— 与
 * {@code PostSummaryVO} 刻意不带 {@code content} 同源）。</p>
 *
 * <p>为什么不直接复用帖子列表项 VO：那一个在 {@code com.hyforum.post.vo}，
 * 而 {@code interaction} 包不得依赖 {@code post} 包（铁律 3 + ArchUnit）。
 * 取舍与 {@code UserBriefVO} 的说明一致，已在交付报告中提 CR 收敛。</p>
 *
 * @param postId       帖子 id
 * @param boardId      版块 id
 * @param title        标题
 * @param coverUrl     封面（首图缩略图）
 * @param imageCount   图片数
 * @param likeCount    点赞数
 * @param commentCount 评论数
 * @param collectCount 收藏数
 * @param createdAt    帖子发布时间
 */
@Schema(name = "CollectionItemVO", description = "我的收藏列表项（不含正文）")
public record CollectionItemVO(
        Long postId,
        Long boardId,
        String title,
        String coverUrl,
        Integer imageCount,
        Integer likeCount,
        Integer commentCount,
        Integer collectCount,
        LocalDateTime createdAt) {
}
