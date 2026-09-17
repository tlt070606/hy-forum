package com.hyforum.domain.interaction.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 关注关系，对应 {@code follow} 表（docs/db/schema.sql 表 8 / 技术方案 §6.7）。
 *
 * <p>{@code user_id} 是<b>关注者</b>，{@code target_user_id} 是<b>被关注者</b>。
 * 幂等由唯一索引 {@code uk_follow(user_id, target_user_id)} 承担（§8.1 同源做法）。</p>
 *
 * <p><b>列名口径提醒</b>：契约 §6.7 与任务书里说的 {@code following_count}／{@code follower_count}
 * 在 {@code schema.sql} 里对应的是 {@code user.follow_count}（我关注的人数）与
 * {@code user.fans_count}（关注我的人数）。本任务的实现一律以 schema 为准
 * （契约不可改），已在交付报告中登记这处命名差异。</p>
 */
@TableName("follow")
public class Follow {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关注者（动作发起人）。 */
    private Long userId;

    /** 被关注者。 */
    private Long targetUserId;

    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getTargetUserId() {
        return targetUserId;
    }

    public void setTargetUserId(Long targetUserId) {
        this.targetUserId = targetUserId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
