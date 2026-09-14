package com.hyforum.domain.invite.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 邀请码实体，对应 {@code invite_code} 表（docs/db/schema.sql 表 14）。
 *
 * <p>用于「注册模式 = invite」时的降级注册（技术方案 §8.8）。</p>
 *
 * <p><b>并发正确性红线（P1-4 定案，2026-09-14）</b>：一次性使用<b>不能靠 {@code uk_code}</b>
 * —— 唯一索引只保证 code 不重复，不保证不被用两次。必须走
 * 「条件 UPDATE + 影响行数校验」，见
 * {@code InviteCodeMapper#markUsedIfAvailable} 与 {@code RegisterModeService} 的调用点。</p>
 */
@TableName("invite_code")
public class InviteCode {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 邀请码字符串，≤32 字符，唯一（uk_code）。 */
    private String code;

    /** 生成者（管理员用户 id，可为空）。 */
    private Long creatorUserId;

    /** 使用者用户 id；未使用时为空。 */
    private Long usedByUserId;

    /** 0未使用 1已使用 2已失效。 */
    private Integer status;

    /** 过期时间；为空表示永不过期。 */
    private LocalDateTime expireAt;

    private LocalDateTime createdAt;

    private LocalDateTime usedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public Long getCreatorUserId() {
        return creatorUserId;
    }

    public void setCreatorUserId(Long creatorUserId) {
        this.creatorUserId = creatorUserId;
    }

    public Long getUsedByUserId() {
        return usedByUserId;
    }

    public void setUsedByUserId(Long usedByUserId) {
        this.usedByUserId = usedByUserId;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public LocalDateTime getExpireAt() {
        return expireAt;
    }

    public void setExpireAt(LocalDateTime expireAt) {
        this.expireAt = expireAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUsedAt() {
        return usedAt;
    }

    public void setUsedAt(LocalDateTime usedAt) {
        this.usedAt = usedAt;
    }
}
