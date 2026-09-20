package com.hyforum.domain.notify.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 通知实体，对应 {@code notification} 表（docs/db/schema.sql 表 10 / 技术方案 §6.10）。
 *
 * <p><b>字段与列一一对应，不得增减</b>：表结构是冻结契约，缺字段说明契约不足 → 走 CR。</p>
 *
 * <p>几条与实现有关的列语义（都取自 schema 的列注释，不是猜的）：</p>
 * <ul>
 *   <li>{@code type}：{@code 1点赞 2评论 3回复 4关注 5系统} —— 与
 *       {@code com.hyforum.common.notify.NotificationType} 逐字对应；</li>
 *   <li>{@code targetType}：{@code 1帖子 2评论}，无具体目标时可为 {@code null}；</li>
 *   <li>{@code isRead}：{@code 0} 未读 / {@code 1} 已读（既有单条标记也有批量标记，
 *       见 {@code NotificationService}）；</li>
 *   <li><b>本表没有 {@code is_deleted}</b>：通知不做逻辑删除。用户的消息记录
 *       不该被"别人把帖子删了"这种动作抹掉（任务书 §5 第 3 条），
 *       因此目标不存在时只是<b>读不到详情</b>，通知本身保留。</li>
 * </ul>
 */
@TableName("notification")
public class Notification {

    /**
     * 目标类型：1 帖子（schema 列注释：{@code 1帖子 2评论}）。
     *
     * <p><b>为什么这个常量放在实体上（{@code domain}）而不是 {@code notify} 包</b>：
     * 触发点在 {@code interaction}，而 {@code interaction} 不得依赖 {@code notify}
     * （铁律 3；ArchUnit 按包判，controller/常量引用一样算）。
     * {@code domain} 是共享层，任何业务包都可以读 ——
     * 与 M4 把 {@code Comment.ROOT_MARKER} 放在实体上是同一个理由。</p>
     */
    public static final int TARGET_POST = 1;

    /** 目标类型：2 评论。 */
    public static final int TARGET_COMMENT = 2;

    /** 已读。 */
    public static final int READ = 1;

    /** 未读。 */
    public static final int UNREAD = 0;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 接收人。 */
    private Long userId;

    /** 1点赞 2评论 3回复 4关注 5系统。 */
    private Integer type;

    /** 动作发起人；系统通知为 null。 */
    private Long fromUserId;

    /** 1帖子 2评论；无具体目标为 null。 */
    private Integer targetType;

    private Long targetId;

    /** 展示用文案，VARCHAR(200)。 */
    private String content;

    /** 0 未读 / 1 已读。 */
    private Integer isRead;

    private LocalDateTime createdAt;

    // ---------- getter / setter（不引 Lombok：pom 里没有该依赖） ----------

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Integer getType() {
        return type;
    }

    public void setType(Integer type) {
        this.type = type;
    }

    public Long getFromUserId() {
        return fromUserId;
    }

    public void setFromUserId(Long fromUserId) {
        this.fromUserId = fromUserId;
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

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Integer getIsRead() {
        return isRead;
    }

    public void setIsRead(Integer isRead) {
        this.isRead = isRead;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
