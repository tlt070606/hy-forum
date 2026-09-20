package com.hyforum.audit.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 后台评论列表项（§6.11 {@code GET /api/admin/comments}）。
 *
 * <p>后台要看到"前台看不到的那些"，因此本 VO 带 <b>{@code status}</b> 与
 * <b>作者昵称</b>这两样前台评论 VO 没有（或语义不同）的东西：</p>
 * <ul>
 *   <li>{@code status}：审核员需要一眼看出哪条待审（0）、哪条已屏蔽（2）；</li>
 *   <li>{@code authorNickname}：处置前要知道是谁发的（同一个人反复发待审内容是要看出来的）。</li>
 * </ul>
 *
 * @param id              评论 id
 * @param postId          所属帖子 id
 * @param postTitle       所属帖子标题（处置时要知道"这条评论在哪篇帖子下"）
 * @param userId          作者 id
 * @param authorNickname  作者昵称；作者已注销时为 null
 * @param parentId        0 = 主楼；否则 = 所属主楼 id（归并语义下等于 root_id）
 * @param content         正文（<b>审核必须看到原文</b>，因此不像列表那样省略）
 * @param status          0 待审 / 1 正常 / 2 已屏蔽
 * @param createdAt       发布时间
 */
@Schema(name = "AdminCommentVO", description = "后台评论列表项（含审核状态与正文）")
public record AdminCommentVO(
        Long id,
        Long postId,
        String postTitle,
        Long userId,
        String authorNickname,
        Long parentId,
        String content,
        @Schema(description = "0 待审核 / 1 正常 / 2 已屏蔽")
        Integer status,
        LocalDateTime createdAt) {
}
