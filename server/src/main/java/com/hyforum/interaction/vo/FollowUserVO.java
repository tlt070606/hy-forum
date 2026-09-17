package com.hyforum.interaction.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 关注/粉丝列表项（§6.7 {@code GET /api/users/{id}/follows} 与 {@code /fans}）。
 *
 * <p>{@code followedAt} 是<b>关注关系的建立时间</b>，不是用户的注册时间 ——
 * 列表按它倒序（最近关注的排最前），前端"最近关注"的语义依赖它。</p>
 *
 * @param userId     用户 id
 * @param nickname   昵称
 * @param avatarUrl  头像
 * @param bio        个性签名（关注列表里展示，便于判断"要不要互关"）
 * @param followedAt 关注关系建立时间
 */
@Schema(name = "FollowUserVO", description = "关注/粉丝列表项")
public record FollowUserVO(
        Long userId,
        String nickname,
        String avatarUrl,
        String bio,
        LocalDateTime followedAt) {
}
