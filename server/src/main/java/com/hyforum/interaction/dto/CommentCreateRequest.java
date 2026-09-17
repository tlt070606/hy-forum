package com.hyforum.interaction.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 发表评论请求（技术方案 §6.6：{@code POST /api/comments}）。
 *
 * <p>契约里的字段是 {@code postId、parentId（0=主楼）、content、replyToUserId（可选）}。</p>
 *
 * <p><b>本实现刻意不从请求里取 {@code replyToUserId}</b> —— 字段保留（契约兼容、
 * 前端可以照传）但服务端<b>按 {@code parentId} 重新推导</b>。理由：被回复者是
 * 从"回复了哪条评论"唯一确定的，让客户端指定等于允许"我的评论显示成回复了别人"，
 * 而通知（M5）正是按这个字段发出去的 —— 一个可伪造的字段会变成可伪造的通知来源。
 * 想回复 B 的正确做法是把 {@code parentId} 设成 B 那条评论的 id。</p>
 *
 * @param postId    帖子 id（必填）
 * @param parentId  0 = 直接评论帖子（主楼）；否则为被回复评论的 id。
 *                  <b>指向楼中楼时会被归并到它所属的主楼</b>（P1-3 归并语义），
 *                  不是报错也不是照抄 —— 见 {@code CommentService#create}
 * @param content   正文，1–1000 字符（与 {@code comment.content} 的 VARCHAR(1000) 一致）
 */
@Schema(name = "CommentCreateRequest", description = "发表评论")
public record CommentCreateRequest(
        @NotNull(message = "不能为空")
        Long postId,

        @Schema(description = "0=主楼；否则为被回复评论 id。指向楼中楼时归并到其主楼")
        Long parentId,

        @NotBlank(message = "不能为空")
        @Size(max = 1000, message = "不能超过 1000 字")
        String content) {
}
