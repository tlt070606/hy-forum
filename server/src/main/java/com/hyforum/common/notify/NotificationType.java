package com.hyforum.common.notify;

/**
 * 通知类型，取值与 {@code docs/db/schema.sql} 的
 * {@code notification.type} 列注释<b>逐字对应</b>：
 * {@code 1点赞 2评论 3回复 4关注 5系统}。
 *
 * <p><b>为什么用枚举而不是裸 int</b>：四个触发点分散在 {@code interaction} 的多个方法里
 * （点赞、评论、回复、关注），裸 int 会让"3 到底是回复还是关注"这种问题只能靠翻 schema 回答；
 * 而且写错一个数字不会编译失败、只会静默产生一条类型错误的通知。</p>
 *
 * <p><b>为什么放在 {@code common} 而不是 {@code notify}</b>：触发点（{@code interaction}）
 * 需要引用它，而 {@code interaction} 不能依赖 {@code notify}（铁律 3）。
 * 与 {@link NotificationPublisher} 同一个理由。</p>
 *
 * <p>注意 {@link #SYSTEM}（5）本期<b>没有触发点</b> —— 它留给 M6 的管理端群发/系统公告。
 * 提前定义是因为它已经在契约（列注释）里，少一个枚举值会让以后的人以为"契约漏了类型 5"。</p>
 */
public enum NotificationType {

    /** 1 点赞：有人赞了你的帖子。 */
    LIKE(1),

    /** 2 评论：有人评论了你的帖子（主楼评论）。 */
    COMMENT(2),

    /** 3 回复：有人回复了你的评论 / 楼中楼。 */
    REPLY(3),

    /** 4 关注：有人关注了你。 */
    FOLLOW(4),

    /** 5 系统：系统通知（本期无触发点，留给 M6）。 */
    SYSTEM(5);

    private final int code;

    NotificationType(int code) {
        this.code = code;
    }

    /** 落库用的取值（与 schema 列注释一致）。 */
    public int code() {
        return code;
    }

    /**
     * 按落库值反查枚举。
     *
     * <p>查不到抛异常而不是返回 {@code null}：这个值来自我们自己的枚举，
     * 查不到说明有人手工往库里写了非法值 —— 静默返回 null 会让上游把
     * "类型非法"渲染成一个空标签，反而更难查。</p>
     */
    public static NotificationType of(int code) {
        for (NotificationType each : values()) {
            if (each.code == code) {
                return each;
            }
        }
        throw new IllegalArgumentException("未知通知类型：" + code);
    }
}
