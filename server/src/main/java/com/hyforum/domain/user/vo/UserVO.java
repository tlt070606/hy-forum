package com.hyforum.domain.user.vo;

import com.hyforum.common.oss.AvatarUrlResolver;
import com.hyforum.domain.user.entity.User;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 用户对外视图对象。
 *
 * <p><b>为什么不直接返回实体</b>：{@code user} 表里有 {@code password_hash}。
 * 实体一旦走上 HTTP 响应，序列化是"默认全部字段"，任何一个接口漏加
 * {@code @JsonIgnore} 就是一次密码哈希泄露。用 VO 白名单是<b>结构性</b>的防护：
 * VO 里根本没有这个字段，写不出泄露代码。对应验收项 {@code M1_password_never_returned}。</p>
 *
 * <p>字段口径来自契约里与用户相关的响应（§6.2 登录返回用户信息、§6.3 用户模块）：
 * 只暴露展示与统计信息，不含邮箱（本期不采集，技术方案 §10 C7 个人信息最小化）。</p>
 *
 * @param id                用户 id
 * @param username          登录名
 * @param nickname          昵称
 * @param avatarUrl         头像
 * @param bio               个性签名
 * @param gender            0未知 1男 2女
 * @param postCount         发帖数
 * @param followCount       关注数
 * @param fansCount         粉丝数
 * @param likeReceivedCount 被点赞总数
 * @param level             等级（预留字段）
 * @param createdAt         注册时间
 */
public record UserVO(
        Long id,
        String username,
        String nickname,
        String avatarUrl,
        String bio,
        @Schema(description = "性别：0 = 未知 / 1 = 男 / 2 = 女。"
                + "取值语义与 docs/db/schema.sql 的 user.gender 列注释一致（CR-M）")
        Integer gender,
        Integer postCount,
        Integer followCount,
        Integer fansCount,
        Integer likeReceivedCount,
        @Schema(description = "【reserved 预留字段】当前无任何写入口径：注册时被固定写成 1"
                + "（见 AuthService.register），schema 默认值也是 1 —— 即**恒为 1、不随任何行为变化**。"
                + "含义待定，**前端不得展示为'等级'**（把恒为 1 的数字渲染成'等级 1'属于界面在说谎）。"
                + "本注解由 M4（CR-M）补：该字段此前在契约里没有任何取值语义说明。")
        Integer level,
        LocalDateTime createdAt) {

    /**
     * 实体 → VO。
     *
     * <p>转换方法刻意放在 VO 上（而不是"给实体加 toVo()"）：这样依赖方向是
     * {@code domain.vo → domain.entity}，实体保持零依赖。若反过来，实体就得知道
     * 表现层的形状，后续每加一个视图都要改实体。</p>
     */
    public static UserVO from(User user, com.hyforum.common.oss.AvatarUrlResolver avatarResolver) {
        if (user == null) {
            return null;
        }
        return new UserVO(
                user.getId(),
                user.getUsername(),
                user.getNickname(),
                avatarResolver.resolve(user),
                user.getBio(),
                user.getGender(),
                user.getPostCount(),
                user.getFollowCount(),
                user.getFansCount(),
                user.getLikeReceivedCount(),
                user.getLevel(),
                user.getCreatedAt());
    }
}
