package com.hyforum.notify.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hyforum.common.oss.AvatarUrlResolver;
import com.hyforum.common.api.PageResult;
import com.hyforum.common.notify.NotificationPublisher;
import com.hyforum.common.notify.NotificationType;
import com.hyforum.domain.notify.entity.Notification;
import com.hyforum.domain.notify.mapper.NotificationMapper;
import com.hyforum.domain.user.entity.User;
import com.hyforum.domain.user.mapper.UserMapper;
import com.hyforum.notify.vo.NotificationVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 通知服务（docs/技术方案.md §6.10）。
 *
 * <h2>职责划分（本类同时是 {@link NotificationPublisher} 的实现）</h2>
 * <ul>
 *   <li><b>写</b>：实现 {@code common.notify.NotificationPublisher}，供 {@code interaction}
 *       在业务事务内调用（铁律 3 因此不被违反 —— 对方依赖的是 {@code common} 的接口）；</li>
 *   <li><b>读</b>：未读数、分页列表、标记已读（§6.10 的三个端点）。</li>
 * </ul>
 *
 * <h2>「自己操作自己 → 不产生通知」由本类统一判断（任务书 §5 第 2 条）</h2>
 * <p>四个触发点只负责如实描述"谁对谁做了什么"，<b>不各自判断</b>。
 * 理由：四个地方各写一遍必然有一处漏掉，而漏掉的表现是"自己给自己发通知"——
 * 看起来很蠢、却因为不报错而很难发现。<b>判断只写一次</b>。</p>
 *
 * <h2>事务语义（任务书 §5 第 1 条）</h2>
 * <p>{@link #publish} 是 {@code REQUIRED} 传播 —— 它加入调用方（点赞/评论/关注）已有的
 * 事务，因此"业务写入"与"通知写入"要么一起提交、要么一起回滚。
 * <b>不用异步线程、不用 {@code @Async}</b>：本地可用版不引入消息队列（铁律 7），
 * 而"点赞成功但通知丢了"是用户可见的不一致。</p>
 */
@Service
public class NotificationService implements NotificationPublisher {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    /** 文案长度上限，与 {@code notification.content} 的 VARCHAR(200) 一致。 */
    private static final int MAX_CONTENT_LENGTH = 200;

    private final NotificationMapper notificationMapper;
    private final UserMapper userMapper;

    /** 头像 URL 的唯一装配入口（CR-Q）：通知发送者的头像也必须带读时签名。 */
    private final AvatarUrlResolver avatarResolver;

    public NotificationService(NotificationMapper notificationMapper, UserMapper userMapper,
                               AvatarUrlResolver avatarResolver) {
        this.notificationMapper = notificationMapper;
        this.userMapper = userMapper;
        this.avatarResolver = avatarResolver;
    }

    // ==================================================================
    // 写：发布通知（NotificationPublisher 的实现）
    // ==================================================================

    /**
     * {@inheritDoc}
     *
     * <p>三条跳过规则（命中任意一条就<b>不落库并返回 {@code null}</b>）：</p>
     * <ol>
     *   <li><b>自己操作自己</b>（{@code fromUserId == toUserId}）—— §5 第 2 条，本类唯一判断点；</li>
     *   <li>{@code toUserId} 为空 —— 触发点拿不到接收人（例如帖子作者已被物理删除）；</li>
     *   <li>接收人不存在或已注销 —— 落一条<b>永远没人能看到</b>的通知只是垃圾数据。
     *       （用 {@code selectById} 判存在：它受 {@code @TableLogic} 影响，已注销用户查不到。）</li>
     * </ol>
     * <p>注意第 3 条<b>不</b>适用于"发起人不存在"：发起人被注销后，
     * 他历史动作产生的通知对接收人仍然有效（消息记录不该被别人的注销抹掉）。</p>
     */
    @Override
    @Transactional
    public Long publish(NotificationType type,
                        Long toUserId,
                        Long fromUserId,
                        Integer targetType,
                        Long targetId,
                        String content) {
        if (type == null || toUserId == null) {
            log.debug("通知参数不足，跳过：type={} toUserId={}", type, toUserId);
            return null;
        }
        // ★ 唯一的"自己操作自己"判断点（§5 第 2 条）
        if (fromUserId != null && fromUserId.equals(toUserId)) {
            log.debug("自己操作自己，不产生通知：type={} userId={}", type, toUserId);
            return null;
        }
        User receiver = userMapper.selectById(toUserId);
        if (receiver == null) {
            log.debug("通知接收人不存在或已注销，跳过：toUserId={}", toUserId);
            return null;
        }

        Notification notification = new Notification();
        notification.setUserId(toUserId);
        notification.setType(type.code());
        notification.setFromUserId(fromUserId);
        notification.setTargetType(targetType);
        notification.setTargetId(targetId);
        notification.setContent(truncate(content));
        notification.setIsRead(0);
        notification.setCreatedAt(LocalDateTime.now());
        notificationMapper.insert(notification);
        return notification.getId();
    }

    /** 文案超长时截断而不是报错：文案是展示性的，为它拒掉一次点赞是荒谬的取舍。 */
    private static String truncate(String content) {
        if (content == null) {
            return null;
        }
        return content.length() <= MAX_CONTENT_LENGTH
                ? content
                : content.substring(0, MAX_CONTENT_LENGTH);
    }

    // ==================================================================
    // 读：未读数 / 列表 / 标记已读
    // ==================================================================

    /**
     * 当前用户的未读条数（§6.10 {@code GET /api/notifications/unread-count}，前端红点）。
     *
     * <p>用 {@code COUNT} 而不是"查出来再 size()"：红点查询会被前端<b>频繁</b>调用，
     * 把整页通知拉回来只为数一数是不必要的。</p>
     */
    @Transactional(readOnly = true)
    public long unreadCount(long userId) {
        Long count = notificationMapper.selectCount(Wrappers.<Notification>lambdaQuery()
                .eq(Notification::getUserId, userId)
                .eq(Notification::getIsRead, 0));
        return count == null ? 0L : count;
    }

    /**
     * 我的消息列表（§6.10），时间倒序分页；{@code type} 可选（不传=全部）。
     *
     * <p><b>目标可能是已删内容</b>（任务书 §5 第 3 条）：这里<b>不</b>因为目标不存在就报错或过滤，
     * 通知本身照常返回，只把 {@code targetExists} 置为 {@code false}，
     * 由前端显示"内容已删除"。理由：用户的消息记录不该被别人的删除动作抹掉 ——
     * 而且若在这里删掉通知，未读数会无声变化，用户会觉得"红点自己消失了"。</p>
     */
    @Transactional(readOnly = true)
    public PageResult<NotificationVO> list(long userId, Integer type, int page, int size) {
        int pageNo = PageResult.normalizePage(page);
        int pageSize = PageResult.normalizeSize(size);

        Page<Notification> query = new Page<>(pageNo, pageSize);
        IPage<Notification> result = notificationMapper.selectPage(query,
                Wrappers.<Notification>lambdaQuery()
                        .eq(Notification::getUserId, userId)
                        .eq(type != null, Notification::getType, type)
                        .orderByDesc(Notification::getCreatedAt)
                        .orderByDesc(Notification::getId));

        List<Notification> rows = result.getRecords();
        Map<Long, User> senders = loadSenders(rows);
        List<NotificationVO> items = new ArrayList<>(rows.size());
        for (Notification row : rows) {
            items.add(NotificationVO.from(row, senders.get(row.getFromUserId()), avatarResolver));
        }
        return PageResult.of(items, result.getTotal(), pageNo, pageSize);
    }

    private Map<Long, User> loadSenders(List<Notification> rows) {
        List<Long> ids = rows.stream()
                .map(Notification::getFromUserId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, User> senders = new HashMap<>();
        if (ids.isEmpty()) {
            return senders;
        }
        for (User user : userMapper.selectBatchIds(ids)) {
            senders.put(user.getId(), user);
        }
        return senders;
    }

    /**
     * 标记已读（§6.10 {@code PUT /api/notifications/read}）：传 id 列表，或 {@code all=true} 全部已读。
     *
     * <p><b>选的是"二选一"里的哪一种、为什么</b>（任务书 §5 第 2 条要求写明）：
     * <b>两者都实现</b>，因为它们服务于不同场景且代价都很小 ——
     * 点开单条用 id 列表（精准），前端进消息页时"全部已读"用 {@code all=true}（一次请求）。
     * 只做其中一种会逼前端循环调 N 次（N 次往返 + N 次 UPDATE）。</p>
     *
     * <p><b>两个方向都必须限死在"我自己的"通知上</b>（{@code user_id = 我} 进 WHERE）：
     * 否则任何人都能把别人的消息标记成已读 —— 那是一个静默的越权写入
     * （不报错、不返回数据，但对方红点会消失）。这也是本方法把 {@code userId}
     * 放进 {@code eq} 而不是在方法体里先查一遍的原因。</p>
     *
     * @param ids 要标记的通知 id 列表；{@code all=true} 时忽略
     * @param all true = 把我的全部未读标记为已读
     * @return 实际影响的行数（幂等：已经是已读的不会重复计数）
     */
    @Transactional
    public int markRead(long userId, List<Long> ids, boolean all) {
        if (!all && (ids == null || ids.isEmpty())) {
            // 两个参数都没给：明确拒绝而不是"什么都不做返回成功" ——
            // 后者会让前端以为"已读"生效了，而红点还在
            throw new com.hyforum.common.exception.BizException(
                    com.hyforum.common.api.ErrorCode.BAD_REQUEST,
                    "必须提供 ids，或显式传 all=true");
        }
        LambdaUpdateWrapper<Notification> update = Wrappers.<Notification>lambdaUpdate()
                .eq(Notification::getUserId, userId)   // ← 越权防线：只能动自己的
                .eq(Notification::getIsRead, 0)        // ← 幂等：已读的不再重复计入影响行数
                .set(Notification::getIsRead, Notification.READ);
        if (!all) {
            update.in(Notification::getId, ids);
        }
        return notificationMapper.update(null, update);
    }
}
