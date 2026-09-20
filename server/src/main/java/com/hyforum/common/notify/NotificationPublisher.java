package com.hyforum.common.notify;

/**
 * 通知发布的<b>跨模块契约</b>（接口在 {@code common}，实现在 {@code notify}）。
 *
 * <h2>为什么必须有这个接口（而不是让 {@code interaction} 直接调 {@code notify}）</h2>
 * <p>通知的触发点在 {@code interaction}（点赞/评论/回复/关注成功后要写通知），
 * 而写入实现在 {@code notify}。如果 {@code interaction} 直接注入 {@code NotificationService}，
 * 就构成**业务包互相依赖** —— 铁律 3 禁止，且 ArchUnit 的
 * {@code ARCH_no_cross_module_dependency} <b>按包判</b>，会当场判违规：</p>
 * <pre>
 *   Rule '七个业务包之间禁止相互依赖（只允许依赖 common 与 domain）' was violated:
 *     Field &lt;InteractionService...&gt; has type &lt;com.hyforum.notify...&gt;
 * </pre>
 * <p>（这条规则我在 M4 的 §13 里亲身踩过一次：当年以为是"接入层调用两个 Service 不算依赖"，
 * 结果被 ArchUnit 判了 9 处。所以这次先看规则再动手。）</p>
 *
 * <h2>这是项目已有的做法，不是新发明</h2>
 * <p>同一模式在 M1 就有先例：{@code com.hyforum.common.audit.SensitiveTextChecker} 是
 * <b>{@code common} 里的接口</b>，实现在 {@code audit} 包的
 * {@code InMemorySensitiveTextChecker}；M3 的 {@code PostService} 与 M5 的评论都要用敏感词，
 * 但它们依赖的是那个接口，而不是 {@code audit} 包。
 * <b>本接口沿用同一形状</b>：{@code interaction} 依赖 {@code common.notify}（允许），
 * {@code notify} 提供实现（允许）。</p>
 *
 * <h2>事务语义（任务书 §5 第 1 条：通知写入必须在业务事务内）</h2>
 * <p>实现方必须是 {@code @Transactional(propagation = REQUIRED)} 的<b>同事务</b>写入 ——
 * 点赞成功与写通知要么一起提交、要么一起回滚。
 * <b>禁止</b>用异步线程或 {@code @Async}（本地可用版不引入消息队列，铁律 7）。</p>
 *
 * <h2>「自己操作自己 → 不通知」的归属</h2>
 * <p>这条规则由<b>实现方</b>（{@code notify}）统一判断，而不是靠每个触发点自己记得传对参数 ——
 * 触发点只负责如实描述"谁对谁做了什么"。理由：四个触发点各写一遍判断，
 * 必然有一处漏掉，而漏掉的表现是"自己给自己发通知"这种看起来很蠢但很难发现的现象。</p>
 */
public interface NotificationPublisher {

    /**
     * 发布一条通知（在调用方的业务事务内写入）。
     *
     * <p><b>调用方只描述事实，不做判断</b>：传进来的 {@code fromUserId} 与
     * {@code toUserId} 可能是同一个人（那说明"自己操作自己"），
     * <b>由实现方负责不落库</b>，调用方不需要自己忘了判断而背锅。</p>
     *
     * @param type       通知类型（见 {@link NotificationType}）
     * @param toUserId   接收人（被点赞的帖子作者 / 被评论的帖子作者 / 被回复的人 / 被关注的人）
     * @param fromUserId 动作发起人；为 {@code null} 时表示系统通知（M6 用）
     * @param targetType 目标类型（1 帖子 / 2 评论）；无具体目标时可为 {@code null}
     * @param targetId   目标 id；无具体目标时可为 {@code null}
     * @param content    展示用文案（≤200 字符，与列的 VARCHAR(200) 一致）；可为 {@code null}
     * @return 实际写入的通知 id；<b>未写入时返回 {@code null}</b>
     *         （自己操作自己、或接收人不存在/已注销）
     */
    Long publish(NotificationType type,
                 Long toUserId,
                 Long fromUserId,
                 Integer targetType,
                 Long targetId,
                 String content);
}
