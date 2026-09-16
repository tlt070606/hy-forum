package com.hyforum.domain.post.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 帖子实体，对应 {@code post} 表（docs/db/schema.sql 表 3 / 技术方案 §5.3）。
 *
 * <p>字段与列一一对应，不得增减。其中几处语义容易被想当然，列注释与 §8.6 已定死，
 * 这里再写一次（因为它们直接决定测试断言）：</p>
 * <ul>
 *   <li>{@code status}：{@code 0} 待审核（命中敏感词，前台不可见）／{@code 1} 正常／{@code 2} 已屏蔽；</li>
 *   <li>{@code diskUrl}／{@code diskCode}：网盘链接与提取码<b>分开存储</b>，
 *       {@code diskUrl} 不允许含 {@code pwd} 参数（ADR-0008 的归一化结果）；</li>
 *   <li>{@code coverUrl}：列表页封面，取<b>首图缩略图</b>（不是原图）；</li>
 *   <li>{@code isDeleted}：逻辑删除，由 MyBatis-Plus 的 {@code @TableLogic} 自动过滤查询。</li>
 * </ul>
 */
@TableName("post")
public class Post {

    /** 帖子状态：待审核（命中敏感词后落这个值，前台不对普通用户展示）。 */
    public static final int STATUS_PENDING = 0;

    /** 帖子状态：正常可见。 */
    public static final int STATUS_NORMAL = 1;

    /** 帖子状态：已屏蔽（管理端处置结果）。 */
    public static final int STATUS_BLOCKED = 2;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long boardId;

    private Long userId;

    /** 标题，VARCHAR(100)。 */
    private String title;

    /** 正文，TEXT 可空。 */
    private String content;

    /** 列表页封面 = 首图缩略图 URL。 */
    private String coverUrl;

    /** 图片数（与 {@code post_image} 行数一致，同一事务维护）。 */
    private Integer imageCount;

    /** 1百度 2阿里 3夸克 4天翼 5迅雷 6其他；仅资源版。 */
    private Integer diskType;

    /** 网盘分享链接（不含 pwd）；仅资源版。 */
    private String diskUrl;

    /** 提取码，可空；仅资源版。 */
    private String diskCode;

    private Integer viewCount;

    private Integer likeCount;

    private Integer commentCount;

    private Integer collectCount;

    private Integer reportCount;

    private Integer isTop;

    private Integer isEssence;

    /** 0待审核 1正常 2已屏蔽。 */
    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /** 逻辑删除标记，由 MyBatis-Plus 自动附加 {@code is_deleted = 0} 条件。 */
    @TableLogic
    @TableField("is_deleted")
    private Integer isDeleted;

    // ---------- getter / setter ----------

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getBoardId() {
        return boardId;
    }

    public void setBoardId(Long boardId) {
        this.boardId = boardId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getCoverUrl() {
        return coverUrl;
    }

    public void setCoverUrl(String coverUrl) {
        this.coverUrl = coverUrl;
    }

    public Integer getImageCount() {
        return imageCount;
    }

    public void setImageCount(Integer imageCount) {
        this.imageCount = imageCount;
    }

    public Integer getDiskType() {
        return diskType;
    }

    public void setDiskType(Integer diskType) {
        this.diskType = diskType;
    }

    public String getDiskUrl() {
        return diskUrl;
    }

    public void setDiskUrl(String diskUrl) {
        this.diskUrl = diskUrl;
    }

    public String getDiskCode() {
        return diskCode;
    }

    public void setDiskCode(String diskCode) {
        this.diskCode = diskCode;
    }

    public Integer getViewCount() {
        return viewCount;
    }

    public void setViewCount(Integer viewCount) {
        this.viewCount = viewCount;
    }

    public Integer getLikeCount() {
        return likeCount;
    }

    public void setLikeCount(Integer likeCount) {
        this.likeCount = likeCount;
    }

    public Integer getCommentCount() {
        return commentCount;
    }

    public void setCommentCount(Integer commentCount) {
        this.commentCount = commentCount;
    }

    public Integer getCollectCount() {
        return collectCount;
    }

    public void setCollectCount(Integer collectCount) {
        this.collectCount = collectCount;
    }

    public Integer getReportCount() {
        return reportCount;
    }

    public void setReportCount(Integer reportCount) {
        this.reportCount = reportCount;
    }

    public Integer getIsTop() {
        return isTop;
    }

    public void setIsTop(Integer isTop) {
        this.isTop = isTop;
    }

    public Integer getIsEssence() {
        return isEssence;
    }

    public void setIsEssence(Integer isEssence) {
        this.isEssence = isEssence;
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

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Integer getIsDeleted() {
        return isDeleted;
    }

    public void setIsDeleted(Integer isDeleted) {
        this.isDeleted = isDeleted;
    }
}
