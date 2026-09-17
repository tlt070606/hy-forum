package com.hyforum.interaction.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 楼中楼条目（{@code GET /api/comments/{rootId}/replies} 的元素，
 * 以及主楼列表里的前 2 条预览）。
 *
 * <p>{@code parentId} 与 {@code rootId} 在这里都返回，且<b>恒等</b>（归并语义的值）。
 * 不隐藏其中任何一个：前端"回复某人"要显示 {@code replyToNickname}，
 * 而 {@code rootId} 是继续拉取该主楼楼中楼的入参 ——
 * 藏掉它前端就只能靠猜（把 {@code parentId} 当 {@code rootId} 用，
 * 一旦哪天语义变了就会静默错）。</p>
 *
 * @param id               评论 id
 * @param postId           所属帖子 id
 * @param rootId           所属主楼评论 id（= 主楼 id）
 * @param parentId         <b>恒等于 {@code rootId}</b>（P1-3 归并语义，不指向楼中楼）
 * @param replyToUserId   被回复用户 id（回复楼中楼时记录在这里）
 * @param replyToNickname 被回复用户昵称（前端渲染"回复 @某人"）
 * @param content         正文
 * @param likeCount       点赞数
 * @param author          作者摘要
 * @param createdAt       发布时间
 */
@Schema(name = "CommentReplyVO", description = "楼中楼条目（parentId 恒等于 rootId）")
public record CommentReplyVO(
        Long id,
        Long postId,
        Long rootId,
        Long parentId,
        Long replyToUserId,
        String replyToNickname,
        String content,
        Integer likeCount,
        UserBriefVO author,
        LocalDateTime createdAt) {
}
