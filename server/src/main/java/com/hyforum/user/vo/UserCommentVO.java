package com.hyforum.user.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 个人主页的评论条目（§6.3 {@code GET /api/users/{id}/comments}）。
 *
 * <p>与 {@code interaction} 的 {@code CommentItemVO} 差三处，都是刻意的：</p>
 * <ul>
 *   <li>带 {@code postTitle}：在"某人的评论"列表里，不显示"评论了哪篇帖子"
 *       这条列表就没有意义（而在帖子详情的评论列表里帖名是冗余的）；</li>
 *   <li>不带楼中楼预览：这里展示的是"这个人说过什么"，不是"这条评论下的讨论"，
 *       拉预览会让 20 条列表变成 60 次查询；</li>
 *   <li>带 {@code rootId}／{@code parentId}：前端要能点进对应的主楼去看上下文。</li>
 * </ul>
 *
 * @param id         评论 id
 * @param postId     所属帖子 id
 * @param postTitle  所属帖子标题（便于列表展示"评论了《…》"）
 * @param rootId     所属主楼 id（主楼自己为 0）
 * @param parentId   0 = 主楼；否则 = 所属主楼 id（归并语义下恒等于 rootId）
 * @param content    正文
 * @param likeCount  点赞数
 * @param replyCount 楼中楼数（仅主楼非 0）
 * @param createdAt  发布时间
 */
@Schema(name = "UserCommentVO", description = "个人主页的评论条目")
public record UserCommentVO(
        Long id,
        Long postId,
        String postTitle,
        Long rootId,
        Long parentId,
        String content,
        Integer likeCount,
        Integer replyCount,
        LocalDateTime createdAt) {
}
