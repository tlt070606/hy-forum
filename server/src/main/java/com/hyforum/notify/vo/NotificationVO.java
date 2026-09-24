package com.hyforum.notify.vo;

import com.hyforum.common.oss.AvatarUrlResolver;
import com.hyforum.domain.notify.entity.Notification;
import com.hyforum.domain.user.entity.User;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 通知条目（§6.10 {@code GET /api/notifications}）。
 *
 * <p><b>关于"目标可能已删"（任务书 §5 第 3 条）</b>：本 VO <b>不</b>带
 * {@code targetExists} 之类的字段，也不在查询时去校验目标是否存在。理由有两条：</p>
 * <ol>
 *   <li><b>正确性</b>：通知是"当时发生过这件事"的记录，它的存在性与目标当前是否可读<b>无关</b>。
 *       为它加一个"目标还在吗"的字段，等于把"通知的有效性"绑在另一个对象上 ——
 *       而那个对象可能只是暂时被审核屏蔽（{@code status=2}），之后又放行；</li>
 *   <li><b>成本</b>：一页 20 条通知要多查 20 次（或按类型分两次批量查），
 *       而前端点进目标时<b>本来就会</b>发一次请求，那时自然会拿到 404 ——
 *       由那一次请求告诉用户"内容已删除"才是最准确的，且不多花一次查询。</li>
 * </ol>
 * <p>因此契约里只给 {@code targetType} + {@code targetId}，由前端拼跳转链接；
 * 目标不存在时详情接口会返回 404，前端据此显示"内容已删除"。</p>
 *
 * @param id          通知 id
 * @param type        1点赞 2评论 3回复 4关注 5系统（与 schema 列注释一致）
 * @param fromUserId  动作发起人 id；系统通知为 null
 * @param fromNickname 发起人昵称（发起人已注销时为占位文案，见 {@link #DELETED_SENDER}）
 * @param fromAvatarUrl 发起人头像，可为 null
 * @param targetType  1帖子 2评论；无具体目标为 null
 * @param targetId    目标 id，可为 null
 * @param content     展示文案，可为 null
 * @param isRead      是否已读
 * @param createdAt   发生时间
 */
@Schema(name = "NotificationVO", description = "消息通知条目")
public record NotificationVO(
        Long id,
        @Schema(description = "1 点赞 / 2 评论 / 3 回复 / 4 关注 / 5 系统")
        Integer type,
        Long fromUserId,
        String fromNickname,
        String fromAvatarUrl,
        @Schema(description = "1 帖子 / 2 评论；无具体目标时为 null")
        Integer targetType,
        Long targetId,
        String content,
        Boolean isRead,
        LocalDateTime createdAt,
        @Schema(description = "CR-N：关联的帖子 id（评论/回复类由评论反查得到；无关联为 null）")
        Long postId,
        @Schema(description = "CR-N：关联的评论 id（仅评论/回复类有；点赞/关注类为 null）")
        Long commentId) {

    /**
     * 发起人已注销时的占位昵称。
     *
     * <p>不用 {@code null}：前端拿到 null 就得自己决定怎么显示，而四种写法（"未知"/空/隐藏整行/崩）
     * 里大概率会选一种看起来像 bug 的。<b>给一个明确的文案</b>比留空更省事，也更诚实 ——
     * 用户看到"已注销用户"能理解发生了什么。</p>
     */
    public static final String DELETED_SENDER = "已注销用户";

    /**
     * 实体 → VO。
     *
     * <p><b>CR-N</b>：新增 {@code postId} / {@code commentId}，让前端能<b>直接跳转</b>到
     * "被点赞的帖子 / 被回复的评论"。这两个值由调用方算好传进来 ——
     * 本类只做形状转换，**不查库**（VO 工厂不该有 IO，否则每页 20 条就是 20 次额外查询）。</p>
     *
     * @param sender 发起人；可能为 {@code null}（已注销，或系统通知）
     * @param postId 关联帖子 id；可为 {@code null}
     * @param commentId 关联评论 id；仅评论/回复类有，点赞/关注类为 {@code null}
     */
    public static NotificationVO from(Notification notification, User sender,
                                      com.hyforum.common.oss.AvatarUrlResolver avatarResolver,
                                      Long postId, Long commentId) {
        if (notification == null) {
            return null;
        }
        return new NotificationVO(
                notification.getId(),
                notification.getType(),
                notification.getFromUserId(),
                sender == null ? DELETED_SENDER : sender.getNickname(),
                sender == null ? null : avatarResolver.resolve(sender),
                notification.getTargetType(),
                notification.getTargetId(),
                notification.getContent(),
                notification.getIsRead() != null && notification.getIsRead() == 1,
                notification.getCreatedAt(),
                // CR-N：两个"可跳转 id"由调用方算好传进来（本类只做形状转换，不查库 ——
                // 否则每页 20 条就是 20 次额外查询，而 VO 工厂不该有 IO）
                postId,
                commentId);
    }
}
