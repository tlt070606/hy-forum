package com.hyforum.domain.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 管理操作留痕实体，对应 {@code admin_operation_log} 表（docs/db/schema.sql 表 16）。
 *
 * <p><b>合规 C9</b>（技术方案 §6.11 留痕红线）：管理员、时间、对象、动作、理由保留 ≥ 6 个月。
 * 因此这张表的语义与业务表相反 ——</p>
 * <ul>
 *   <li><b>只增不改不删</b>：本实体没有 {@code isDeleted}，本任务也不提供任何更新/删除路径。
 *       清理只能按 {@code created_at} 滚动淘汰 ≥ 6 个月之前的数据（运维动作）；</li>
 *   <li><b>写它必须与业务改动同一事务</b>：留痕失败要回滚业务改动，
 *       不允许出现"已处置但无记录"（§6.11 原文）。</li>
 * </ul>
 *
 * <p>字段与列一一对应，不得增减。</p>
 */
@TableName("admin_operation_log")
public class AdminOperationLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 操作人，对应 {@code admin.id}（管理员与前台用户隔离，见技术方案 §9）。 */
    private Long adminId;

    /** 动作编码，例如 COMMENT_STATUS / POST_STATUS；见 schema 的列注释枚举。 */
    private String action;

    /** 1帖子 2评论 3用户 4系统配置 5邀请码 6敏感词 7版块；无具体对象时为空。 */
    private Integer targetType;

    private Long targetId;

    /** 处置理由；<b>屏蔽/封禁/删除类动作必填</b>（合规要求可追溯处置依据）。 */
    private String reason;

    /** 变更摘要，如 {@code status:0->1}。 */
    private String detail;

    /** 操作来源 IP，45 字符以兼容 IPv6。 */
    private String ip;

    private LocalDateTime createdAt;

    // ---------- 目标类型常量（与 schema 列注释一致） ----------

    /** 1 帖子。 */
    public static final int TARGET_POST = 1;

    /** 2 评论。 */
    public static final int TARGET_COMMENT = 2;

    // ---------- getter / setter ----------

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getAdminId() {
        return adminId;
    }

    public void setAdminId(Long adminId) {
        this.adminId = adminId;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public Integer getTargetType() {
        return targetType;
    }

    public void setTargetType(Integer targetType) {
        this.targetType = targetType;
    }

    public Long getTargetId() {
        return targetId;
    }

    public void setTargetId(Long targetId) {
        this.targetId = targetId;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
