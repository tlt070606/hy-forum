package com.hyforum.domain.interaction.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 帖子点赞关系，对应 {@code post_like} 表（docs/db/schema.sql 表 6）。
 *
 * <p><b>幂等完全由唯一索引承担</b>（技术方案 §8.1）：{@code uk_post_user(post_id, user_id)}。
 * 写入路径只允许"直接 INSERT 并捕获 {@code DuplicateKeyException}"，
 * <b>禁止</b>"先 SELECT 判断存在再 INSERT" —— 那在并发下有竞态（P1-4 定案，
 * 见 {@code docs/agents/工作计划.md} §4 的广播）。</p>
 *
 * <p>本表<b>没有</b> {@code is_deleted}：取消点赞是物理删除。
 * 这是刻意的 —— 冗余计数 {@code post.like_count} 必须等于本表行数
 * （M4 的验收判据），留一行"已取消"的墓碑会让这个等式永远不成立。</p>
 */
@TableName("post_like")
public class PostLike {

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
