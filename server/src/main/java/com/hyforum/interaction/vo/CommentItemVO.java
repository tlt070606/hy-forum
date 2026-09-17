package com.hyforum.interaction.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 主楼评论条目（{@code GET /api/posts/{id}/comments} 的元素）。
 *
 * <p>契约 §6.6 的原话是"主楼分页列表（含每条主楼的<b>前 2 条</b>楼中楼预览）"。
 * 因此这里有两个不同的数字，<b>不要混用</b>：</p>
 * <ul>
 *   <li>{@code replyCount} = 该主楼下楼中楼的<b>总数</b>（来自 {@code comment.reply_count}
 *       冗余计数）—— 前端用它显示"查看全部 N 条回复"；</li>
 *   <li>{@code replies} = 预览条目，<b>最多 2 条</b>（任务书 §5.5 第 5 条：
 *       "reply_count 是总数，不是预览数"）。</li>
 * </ul>
 *
 * @param id         主楼评论 id（也就是 {@code GET /api/comments/{rootId}/replies} 的 rootId）
 * @param postId     所属帖子 id
 * @param content    正文
 * @param likeCount  点赞数
 * @param replyCount 楼中楼总数
 * @param replies    前 2 条楼中楼预览（按时间升序；最多 2 条）
 * @param author     作者摘要
 * @param createdAt  发布时间
 */
@Schema(name = "CommentItemVO", description = "主楼评论（含前 2 条楼中楼预览）")
public record CommentItemVO(
        Long id,
        Long postId,
        String content,
        Integer likeCount,
        Integer replyCount,
        List<CommentReplyVO> replies,
        UserBriefVO author,
        LocalDateTime createdAt) {
}
