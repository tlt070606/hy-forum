package com.hyforum.domain.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 用户实体，对应 {@code user} 表（docs/db/schema.sql 表 1）。
 *
 * <p><b>字段与列一一对应，不得增减</b>：表结构是冻结契约，
 * 字段缺失说明契约不足 → 走 CR，不得在实体上"先加一个字段"。</p>
 *
 * <p>注意 {@code passwordHash}：本实体<b>只允许在 auth 模块内</b>使用，
 * 任何对外返回都必须是 VO（{@code com.hyforum.domain.user.vo.UserVO}），
 * 由 {@code M1_password_never_returned} 守门。</p>
 */
@TableName("user")
public class User {

    /** 主键，BIGINT UNSIGNED AUTO_INCREMENT（docs/db/README.md 主键类型统一约定）。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 登录名，4–20 位字母数字下划线，唯一（uk_username）。 */
    private String username;

    /** BCrypt 哈希（strength=10）。禁止明文、禁止可逆加密（技术方案 §9）。 */
    private String passwordHash;

    /** 昵称，1–20 字符。 */
    private String nickname;

    private String avatarUrl;

    /** 可选邮箱，本期注册不采集（技术方案 §10 C7 个人信息最小化）。 */
    private String email;

    /** 个性签名，≤200 字符。 */
    private String bio;

    /** 0未知 1男 2女。 */
    private Integer gender;

    private Integer postCount;

    /** 我关注的人数。 */
    private Integer followCount;

    /** 关注我的人数。 */
    private Integer fansCount;

    private Integer likeReceivedCount;

    /** 【预留】积分与等级：schema 里已存在但本期不启用（技术方案 §5.4）。 */
    private Integer points;

    private Integer level;

    /** 1正常 0封禁。 */
    private Integer status;

    private LocalDateTime lastLoginAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /** 逻辑删除标记，由 MyBatis-Plus 的 @TableLogic 自动过滤。 */
    @TableLogic
    @TableField("is_deleted")
    private Integer isDeleted;

    // ---------- getter / setter ----------
    // 刻意不引入 Lombok：pom 里没有该依赖（任务书 §3 禁止引入新的第三方依赖），
    // 手写访问器是这里唯一不越权的选择。

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

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getBio() {
        return bio;
    }

    public void setBio(String bio) {
        this.bio = bio;
    }

    public Integer getGender() {
        return gender;
    }

    public void setGender(Integer gender) {
        this.gender = gender;
    }

    public Integer getPostCount() {
        return postCount;
    }

    public void setPostCount(Integer postCount) {
        this.postCount = postCount;
    }

    public Integer getFollowCount() {
        return followCount;
    }

    public void setFollowCount(Integer followCount) {
        this.followCount = followCount;
    }

    public Integer getFansCount() {
        return fansCount;
    }

    public void setFansCount(Integer fansCount) {
        this.fansCount = fansCount;
    }

    public Integer getLikeReceivedCount() {
        return likeReceivedCount;
    }

    public void setLikeReceivedCount(Integer likeReceivedCount) {
        this.likeReceivedCount = likeReceivedCount;
    }

    public Integer getPoints() {
        return points;
    }

    public void setPoints(Integer points) {
        this.points = points;
    }

    public Integer getLevel() {
        return level;
    }

    public void setLevel(Integer level) {
        this.level = level;
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
