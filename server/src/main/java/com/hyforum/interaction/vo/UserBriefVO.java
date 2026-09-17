package com.hyforum.interaction.vo;

import com.hyforum.domain.user.entity.User;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 用户摘要（昵称 + 头像），评论作者、关注流作者、粉丝列表都用它。
 *
 * <p><b>为什么不复用 {@code com.hyforum.post.vo.UserBriefVO}</b>：
 * 那一个属于 {@code post} 包，而铁律 3 与 ArchUnit 的
 * {@code ARCH_no_cross_module_dependency} 都禁止 {@code interaction} 依赖 {@code post}
 * （业务包之间只允许依赖 {@code common} 与 {@code domain}）。改名成
 * {@code domain} 下的公共 VO 需要改 {@code domain/**}（L1 地盘），
 * 因此本次在 {@code interaction} 内自带一份，并在交付报告中提 CR 建议收敛为一份 ——
 * 两处 {@code UserBriefVO} 会在 OpenAPI 里生成两个同名 schema，
 * 这是**已知代价**，不是疏忽。</p>
 *
 * <p>为什么不直接返回 {@code User} 实体：实体带 {@code passwordHash}，
 * 序列化默认全字段，任何一处漏判就是一次密码哈希泄露（M1 的
 * {@code M1_password_never_returned} 正是为此守门）。VO 里没有这个字段，
 * 是<b>结构性</b>的防护。</p>
 *
 * @param id        用户 id
 * @param nickname  昵称
 * @param avatarUrl 头像 URL（可空，前端回落默认头像）
 */
@Schema(name = "InteractionUserBriefVO", description = "用户摘要（昵称 + 头像）")
public record UserBriefVO(Long id, String nickname, String avatarUrl) {

    /** 实体 → 摘要；{@code null} 时给占位而不是抛异常（帖子/评论仍应能打开）。 */
    public static UserBriefVO from(User user) {
        if (user == null) {
            return new UserBriefVO(null, "已注销用户", null);
        }
        return new UserBriefVO(user.getId(), user.getNickname(), user.getAvatarUrl());
    }
}
