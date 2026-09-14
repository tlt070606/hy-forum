package com.hyforum.domain.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 管理员实体，对应 {@code admin} 表（docs/db/schema.sql 表 11）。
 *
 * <p><b>与前台 {@code user} 表完全隔离</b>（技术方案 §9「后台隔离」）：
 * 两套账号体系、两套登录态（两个 StpLogic），管理员不占用 user 的主键空间，
 * 也不能用前台 token 访问后台接口。</p>
 *
 * <p>注意本表<b>没有 is_deleted 列</b>（schema 即如此），停用走 {@code status}。</p>
 */
@TableName("admin")
public class Admin {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 管理员登录名，≤30 字符，唯一（uk_admin_username）。 */
    private String username;

    /** BCrypt 哈希（strength=10）。seed.sql 里是占位符，登录必然失败（fail-closed）。 */
    private String passwordHash;

    private String nickname;

    /** SUPER_ADMIN / ADMIN。 */
    private String role;

    /** 1正常 0停用。 */
    private Integer status;

    private LocalDateTime lastLoginAt;

    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public LocalDateTime getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(LocalDateTime lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
