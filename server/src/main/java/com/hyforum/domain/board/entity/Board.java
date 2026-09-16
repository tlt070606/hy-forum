package com.hyforum.domain.board.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 版块实体，对应 {@code board} 表（docs/db/schema.sql 表 2 / 技术方案 §5.3）。
 *
 * <p><b>为什么在 {@code com.hyforum.domain} 下</b>：技术方案 §3.3 的 v1.8 补充把
 * 「实体 / Mapper / DTO / VO / 枚举」统一放在 {@code com.hyforum.domain}，
 * 并且 {@code HyForumApplication} 的 {@code @MapperScan("com.hyforum.domain.**.mapper")}
 * 只扫描这个位置 —— 放在业务包里 Mapper 根本不会被注册。
 * 更重要的原因是铁律 3：{@code post} 包需要读版块，M4 的 {@code interaction} 包也需要，
 * 而业务包之间禁止相互依赖，所以共享的实体必须落在 {@code domain}。</p>
 *
 * <p>本类是 M3 新增（W1-M1 交付时 {@code domain} 下只有 user/admin/config/invite/sensitiveword），
 * 经 L1 在 2026-09-15 的裁决批准新增（只新增文件，不改 M1 任何既有文件）。</p>
 *
 * <p>字段与列一一对应，不得增减：表结构是冻结契约，缺字段说明契约不足 → 走 CR。</p>
 */
@TableName("board")
public class Board {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 版块名，VARCHAR(30)。 */
    private String name;

    /** 唯一短名（uk_slug），VARCHAR(30)。 */
    private String slug;

    private String description;

    private String iconUrl;

    /** 升序排序值。 */
    private Integer sort;

    /** 1 = 资源版块（发帖表单显示网盘字段），0 = 普通版块。 */
    private Integer isResource;

    /** 冗余计数：该版块下的帖子数（§8.2，写操作在同一事务内维护）。 */
    private Integer postCount;

    /** 1 启用 0 停用。 */
    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    // ---------- getter / setter（刻意不引 Lombok：pom 里没有该依赖） ----------

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getIconUrl() {
        return iconUrl;
    }

    public void setIconUrl(String iconUrl) {
        this.iconUrl = iconUrl;
    }

    public Integer getSort() {
        return sort;
    }

    public void setSort(Integer sort) {
        this.sort = sort;
    }

    public Integer getIsResource() {
        return isResource;
    }

    public void setIsResource(Integer isResource) {
        this.isResource = isResource;
    }

    public Integer getPostCount() {
        return postCount;
    }

    public void setPostCount(Integer postCount) {
        this.postCount = postCount;
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
}
