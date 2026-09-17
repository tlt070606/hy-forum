package com.hyforum.interaction.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 首页流条目（§6.5 {@code GET /api/feed} 的 {@code type=follow|all}）。
 *
 * <p>不带 {@code content}：与 {@code PostSummaryVO} 同一个理由（正文是 TEXT，
 * 列表页一个字都不显示它）。字段是"列表页真的会画出来"的那一组 ——
 * 首页要显示版块名与作者，因此带上 {@code boardName} 与 {@code author}。</p>
 *
 * <p><b>与 {@code PostSummaryVO} 的关系</b>：两者形状高度相近，但那个类在
 * {@code com.hyforum.post.vo}，而 {@code interaction} 包不得依赖 {@code post} 包
 * （铁律 3 + ArchUnit）。已在交付报告中提 CR，建议把这类"跨模块共用的视图形状"
 * 收敛到 {@code domain} 下的公共 VO。</p>
 *
 * @param id           帖子 id
 * @param boardId      版块 id
 * @param boardName    版块名
 * @param title        标题
 * @param coverUrl     封面（首图缩略图）
 * @param imageCount   图片数
 * @param isTop        是否置顶
 * @param isEssence    是否加精
 * @param viewCount    浏览量
 * @param likeCount    点赞数
 * @param commentCount 评论数
 * @param collectCount 收藏数
 * @param author       作者摘要
 * @param createdAt    发布时间
 */
@Schema(name = "FeedItemVO", description = "首页流条目（不含正文）")
public record FeedItemVO(
        Long id,
        Long boardId,
        String boardName,
        String title,
        String coverUrl,
        Integer imageCount,
        Boolean isTop,
        Boolean isEssence,
        Integer viewCount,
        Integer likeCount,
        Integer commentCount,
        Integer collectCount,
        UserBriefVO author,
        LocalDateTime createdAt) {
}
