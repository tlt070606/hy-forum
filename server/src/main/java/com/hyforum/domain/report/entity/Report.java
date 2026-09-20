package com.hyforum.domain.report.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 举报实体，对应 {@code report} 表（docs/db/schema.sql 表 9 / 技术方案 §6.11）。
 *
 * <p>字段与列一一对应，不得增减。几条列语义（取自 schema 列注释）：</p>
 * <ul>
 *   <li>{@code targetType}：{@code 1帖子 2评论 3用户}；</li>
 *   <li>{@code reasonType}：{@code 1违法违规 2色情低俗 3广告垃圾 4侵权 5其他}；</li>
 *   <li>{@code status}：{@code 0待处理 1已处理 2已驳回} —— <b>举报也有队列</b>，
 *       而且同样必须有出口（处理与驳回两个方向）。**本任务只做"入口 + 限流"**，
 *       举报的处理出口归 M6（§6.11 的 `PUT /api/admin/reports/{id}`）；
 *       这里如实登记，避免出现第二个"只进不出的队列"而没人发现；</li>
 *   <li>{@code handlerId}/{@code handleNote}/{@code handledAt}：处理信息，M6 填。</li>
 * </ul>
 *
 * <p>本表<b>没有 is_deleted</b>：举报记录是"有人报过"的事实，不该被删除动作抹掉。</p>
 */
@TableName("report")
public class Report {

    /** 目标类型：1 帖子 / 2 评论 / 3 用户（与 schema 列注释一致）。 */
    public static final int TARGET_POST = 1;
    public static final int TARGET_COMMENT = 2;
    public static final int TARGET_USER = 3;

    /** 举报理由类型：1 违法违规 / 2 色情低俗 / 3 广告垃圾 / 4 侵权 / 5 其他。 */
    public static final int REASON_ILLEGAL = 1;
    public static final int REASON_PORN = 2;
    public static final int REASON_AD = 3;
    public static final int REASON_INFRINGE = 4;
    public static final int REASON_OTHER = 5;

    /** 状态：0 待处理 / 1 已处理 / 2 已驳回。 */
    public static final int STATUS_PENDING = 0;
    public static final int STATUS_HANDLED = 1;
    public static final int STATUS_REJECTED = 2;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Integer targetType;

    private Long targetId;

    /** 举报人。 */
    private Long userId;

    private Integer reasonType;

    /** 补充说明，VARCHAR(200)。 */
    private String reasonDetail;

    /** 0待处理 1已处理 2已驳回。 */
    private Integer status;

    private Long handlerId;

    private String handleNote;

    private LocalDateTime handledAt;

    private LocalDateTime createdAt;

    // ---------- getter / setter ----------

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Integer getReasonType() {
        return reasonType;
    }

    public void setReasonType(Integer reasonType) {
        this.reasonType = reasonType;
    }

    public String getReasonDetail() {
        return reasonDetail;
    }

    public void setReasonDetail(String reasonDetail) {
        this.reasonDetail = reasonDetail;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Long getHandlerId() {
        return handlerId;
    }

    public void setHandlerId(Long handlerId) {
        this.handlerId = handlerId;
    }

    public String getHandleNote() {
        return handleNote;
    }

    public void setHandleNote(String handleNote) {
        this.handleNote = handleNote;
    }

    public LocalDateTime getHandledAt() {
        return handledAt;
    }

    public void setHandledAt(LocalDateTime handledAt) {
        this.handledAt = handledAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
