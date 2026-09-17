package com.hyforum.domain.interaction.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 帖子收藏关系，对应 {@code post_collect} 表（docs/db/schema.sql 表 15）。
 *
 * <p>结构与 {@code post_like} 一致（{@code uk_post_user}），幂等机制同源（§8.1）。
 * <b>但收藏不能只当成一个计数器</b>：{@code GET /api/user/collections}（我的收藏列表）
 * 的数据来源就是本表，{@code post.collect_count} 只是列表页展示用的冗余计数
 * （schema.sql 表 15 上方注释的原话）。因此"收藏列表长度 == {@code collect_count}"
 * 是 M4 的一条验收判据。</p>
 */
@TableName("post_collect")
public class PostCollect {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long postId;

    private Long userId;

    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getPostId() {
        return postId;
    }

    public void setPostId(Long postId) {
        this.postId = postId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
