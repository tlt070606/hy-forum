package com.hyforum.common.security;

/**
 * 当前请求登录上下文。
 *
 * <p>为什么不直接在业务里调 {@link StpUserUtil#currentUserId()}：那会把"当前登录人"这一
 * 横切关注点散落到每个 Service，且难以在中立位置做统一校验（例如"账号是否已被封禁"）。
 * 因此由 {@code AuthInterceptor} 在请求进入时解析一次、放入本 ThreadLocal，
 * 业务代码只从这里取。</p>
 *
 * <p><b>内存纪律</b>：拦截器 {@code afterCompletion} 必须调用 {@link #clear()}。
 * Tomcat 线程是复用的，不清会把上一个请求的用户 id 带到下一个请求 —— 这是
 * 典型的越权漏洞来源。</p>
 */
public final class CurrentUser {

    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();

    private CurrentUser() {
    }

    /** 由拦截器在校验通过后写入。 */
    public static void set(Long userId) {
        USER_ID.set(userId);
    }

    /** 当前登录用户 id；未登录为 null。 */
    public static Long idOrNull() {
        return USER_ID.get();
    }

    /**
     * 当前登录用户 id，未登录直接抛 401。
     * 用于那些"必须登录"但方法签名里不方便接收 id 的场景。
     */
    public static Long requireId() {
        Long id = USER_ID.get();
        if (id == null) {
            throw new com.hyforum.common.exception.BizException(com.hyforum.common.api.ErrorCode.UNAUTHORIZED);
        }
        return id;
    }

    /** 请求结束务必调用，避免线程复用导致的身份串号。 */
    public static void clear() {
        USER_ID.remove();
    }
}
