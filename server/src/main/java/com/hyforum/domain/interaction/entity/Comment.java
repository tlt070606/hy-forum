package com.hyforum.domain.interaction.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 评论实体，对应 {@code comment} 表（docs/db/schema.sql 表 5 / 技术方案 §6.6）。
 *
 * <p><b>字段与列一一对应，不得增减</b>：表结构是冻结契约，缺字段说明契约不足 → 走 CR。</p>
 *
 * <h2>两层结构的不变量（P1-3 定案，本任务最容易写错的一处）</h2>
 * <p>楼中楼的 {@code parent_id} 与 {@code root_id} <b>恒等于其所属主楼 id</b>。
 * 也就是说这张表里<b>不存在</b>"指向一条楼中楼"的行 —— 那不是靠自觉，而是靠两道防线：</p>
 * <ol>
 *   <li>数据库的 {@code chk_comment_two_levels}：只保证<b>同一行内</b>两列的关系
 *       （主楼 {@code (0,0)}、楼中楼 {@code (x,x)}）；</li>
 *   <li>{@code CommentService} 的<b>唯一写入口</b>：跨行校验在此完成 ——
 *       传入的 {@code parentId} 指向楼中楼时，实现要把它<b>归并</b>到主楼，
 *       而不是照抄传入值。本项目禁外键，{@code CHECK} 无法跨行引用，所以这一条
 *       只能在应用层保证（§6.6 明确说这是架构要求，不是编码习惯）。</li>
 * </ol>
 *
 * <p>{@code reply_count} <b>仅主楼维护</b>（列注释的原话）：楼中楼回复别人时，
 * 计数加在它所属的<b>主楼</b>上 —— 因为契约 §6.6 里前端只能拿到主楼的预览与计数
 * （{@code GET /api/comments/{rootId}/replies} 的 {@code rootId} 就是主楼 id）。</p>
 */
@TableName("comment")
public class Comment {

    /** 主楼标记：{@code parent_id}/{@code root_id} 为 0 表示"直接评论帖子"。 */
    public static final long ROOT_MARKER = 0L;

    /** 状态：待审核（命中敏感词后落这个值，前台不对普通用户展示，§8.6 第 2 条）。 */
    public static final int STATUS_PENDING = 0;

    /** 状态：正常可见。 */
    public static final int STATUS_NORMAL = 1;

    /** 状态：已屏蔽（管理端处置结果）。 */
    public static final int STATUS_BLOCKED = 2;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long postId;

    private Long userId;

    /** 0 = 主楼；否则为主楼评论 id（**不得指向楼中楼**）。 */
    private Long parentId;

    /** 主楼为 0；楼中楼存所属主楼 id。 */
    private Long rootId;

    /** 楼中楼回复的目标用户（"回复楼中楼"时被回复者记录在这里，而不是靠 parent_id）。 */
    private Long replyToUserId;

    /** 正文，VARCHAR(1000)。 */
    private String content;

    private Integer likeCount;

    /** 仅主楼维护：其下楼中楼的数量（不是预览数）。 */
    private Integer replyCount;

    /** 0待审核 1正常 2已屏蔽。 */
    private Integer status;

    private LocalDateTime createdAt;

    /** 逻辑删除标记，由 MyBatis-Plus 自动附加 {@code is_deleted = 0} 条件。 */
    @TableLogic
    @TableField("is_deleted")
    private Integer isDeleted;

    // ---------- getter / setter（不引 Lombok：pom 里没有该依赖） ----------

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

    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }

    public Long getRootId() {
        return rootId;
    }

    public void setRootId(Long rootId) {
        this.rootId = rootId;
    }

    public Long getReplyToUserId() {
        return replyToUserId;
    }

    public void setReplyToUserId(Long replyToUserId) {
        this.replyToUserId = replyToUserId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Integer getLikeCount() {
        return likeCount;
    }

    public void setLikeCount(Integer likeCount) {
        this.likeCount = likeCount;
    }

    public Integer getReplyCount() {
        return replyCount;
    }

    public void setReplyCount(Integer replyCount) {
        this.replyCount = replyCount;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public Integer getIsDeleted() {
        return isDeleted;
    }

    public void setIsDeleted(Integer isDeleted) {
        this.isDeleted = isDeleted;
    }
}
