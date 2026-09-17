package com.hyforum.user.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 个人主页（§6.3 {@code GET /api/users/{id}}）。
 *
 * <p>契约原文是"个人主页信息（含是否已关注我/我是否已关注）"，
 * 因此有两个<b>方向相反</b>的标志，命名必须一眼可辨：</p>
 * <ul>
 *   <li>{@code isFollowing} = <b>我</b>关注了<b>他</b>（页面上按钮显示"已关注"）；</li>
 *   <li>{@code isFollowedBy} = <b>他</b>关注了<b>我</b>（页面上显示"他关注了你"）。</li>
 * </ul>
 *
 * <p><b>未登录时两者都是 {@code null}，不是 {@code false}</b>：
 * "未登录"与"登录了但没关注"是两种不同的状态，前端对前者要引导登录、
 * 对后者要显示"关注"按钮。用 {@code false} 冒充会把这两种状态压成一种
 * （这正是 {@code @OptionalLogin} 的类注释里点名的那类静默错法）。
 * 因此两个字段用包装类型 {@code Boolean} 而不是 {@code boolean}。</p>
 *
 * <p>为什么不复用 {@code domain.user.vo.UserVO}：那个还带 {@code username}，
 * 主页不需要暴露登录名（§10 C7 个人信息最小化），而这里要多两个关注标志 ——
 * 两者字段集合不同，硬套会逼出一个"多几个 null 字段"的 VO。</p>
 *
 * @param id                用户 id
 * @param nickname          昵称
 * @param avatarUrl         头像
 * @param bio               个性签名
 * @param gender            0未知 1男 2女
 * @param postCount         发帖数
 * @param followCount       关注数（{@code user.follow_count}）
 * @param fansCount         粉丝数（{@code user.fans_count}）
 * @param likeReceivedCount 被点赞总数
 * @param level             等级（预留字段）
 * @param isFollowing       我是否关注了他；未登录为 null
 * @param isFollowedBy      他是否关注了我；未登录为 null
 * @param createdAt         注册时间
 */
@Schema(name = "UserProfileVO", description = "个人主页信息（含双向关注状态）")
public record UserProfileVO(
        Long id,
        String nickname,
        String avatarUrl,
        String bio,
        Integer gender,
        Integer postCount,
        Integer followCount,
        Integer fansCount,
        Integer likeReceivedCount,
        Integer level,
        Boolean isFollowing,
        Boolean isFollowedBy,
        LocalDateTime createdAt) {
}
