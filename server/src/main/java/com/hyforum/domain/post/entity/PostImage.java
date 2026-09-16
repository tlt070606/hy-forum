package com.hyforum.domain.post.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 帖子图片实体，对应 {@code post_image} 表（docs/db/schema.sql 表 4）。
 *
 * <p><b>{@code auditStatus} 的语义（CR-006 于 2026-09-15 澄清，最容易搞错的一条）</b>：</p>
 * <pre>
 * 0 = 尚未被人工判定（默认）   1 = 已人工确认通过   2 = 已判定违规
 * 前台可见性规则：隐藏 audit_status = 2 的图片；**不过滤 0**
 * </pre>
 * <p>把"默认 0"理解成"必须人工放行才可见"会让每张图都要人工点一次，
 * 与 §8.6 第 5 条的先发后审直接矛盾，M3 的验收（带图帖正确展示）也无法达成。
 * 图片的审核出口（置 1／2）归 M5/M6，M3 只负责"写入 + 前台不隐藏未判定图片"。</p>
 *
 * <p><b>{@code postId = 0} 的含义</b>：OSS 上传回调（第二交付段）发生在"帖子还不存在"的时刻，
 * 因此回调写下的图片行 {@code post_id} 为 0（占位，列上 NOT NULL、无默认值，0 是合法值）。
 * 用户提交帖子时，{@code PostService} 会按 URL 认领这些行并绑定真实 {@code post_id}；
 * 认领不到（例如第一交付段没有回调）就按提交的 URL 直接落库。见
 * {@code com.hyforum.post.service.PostService} 的注释。</p>
 */
@TableName("post_image")
public class PostImage {

    /** 尚未绑定帖子的图片行的 {@code post_id} 占位值（OSS 回调先落库、发帖时再认领）。 */
    public static final long UNBOUND_POST_ID = 0L;

    /** 审核状态：尚未被人工判定（默认，前台可见）。 */
    public static final int AUDIT_PENDING = 0;

    /** 审核状态：已人工确认通过。 */
    public static final int AUDIT_APPROVED = 1;

    /** 审核状态：已判定违规（前台隐藏）。 */
    public static final int AUDIT_REJECTED = 2;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long postId;

    /** 原图 URL。 */
    private String url;

    /** 九宫格缩略图 URL（OSS 图片处理参数生成）。 */
    private String thumbUrl;

    private Integer width;

    private Integer height;

    /** 同一帖内的展示顺序（0 起）。 */
    private Integer sort;

    /** 0 尚未被人工判定 / 1 已确认 / 2 已判违规（见类注释的 CR-006 口径）。 */
    private Integer auditStatus;

    private LocalDateTime createdAt;

    // ---------- getter / setter ----------

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

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getThumbUrl() {
        return thumbUrl;
    }

    public void setThumbUrl(String thumbUrl) {
        this.thumbUrl = thumbUrl;
    }

    public Integer getWidth() {
        return width;
    }

    public void setWidth(Integer width) {
        this.width = width;
    }

    public Integer getHeight() {
        return height;
    }

    public void setHeight(Integer height) {
        this.height = height;
    }

    public Integer getSort() {
        return sort;
    }

    public void setSort(Integer sort) {
        this.sort = sort;
    }

    public Integer getAuditStatus() {
        return auditStatus;
    }

    public void setAuditStatus(Integer auditStatus) {
        this.auditStatus = auditStatus;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
