package com.hyforum.post.vo;

import com.hyforum.domain.user.entity.User;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 作者信息摘要（列表与详情共用）。
 *
 * <p><b>为什么不直接返回 {@code UserVO} 或 {@code User} 实体</b>：</p>
 * <ul>
 *   <li>{@code User} 实体带 {@code passwordHash} —— 实体一旦走上 HTTP 响应，
 *       序列化默认全字段，任何一个接口漏加 {@code @JsonIgnore} 就是一次密码哈希泄露
 *       （M1 的 {@code M1_password_never_returned} 正是为此守门）；</li>
 *   <li>{@code UserVO}（§6.3 用户模块）带 bio／counts 等一整套字段，
 *       列表页 20 条 × 十余字段是白花的报文与查询成本，而列表只需要昵称与头像。</li>
 * </ul>
 * <p>这与 M1 的 VO 白名单思路一致：<b>用"没有这个字段"来杜绝泄露</b>。</p>
 *
 * @param id        用户 id
 * @param nickname  昵称
 * @param avatarUrl 头像 URL（可为空，前端回落到默认头像）
 */
@Schema(name = "UserBriefVO", description = "作者信息摘要")
public record UserBriefVO(Long id, String nickname, String avatarUrl) {

    public static UserBriefVO from(User user) {
        if (user == null) {
            // 用户可能已被物理删除（本项目用逻辑删除，理论上不会），
            // 此时给一个占位而不是抛异常：帖子本身仍然应该能打开
            return new UserBriefVO(null, "已注销用户", null);
        }
        return new UserBriefVO(user.getId(), user.getNickname(), user.getAvatarUrl());
    }
}
