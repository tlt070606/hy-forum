package com.hyforum.common.security;

import cn.dev33.satoken.stp.StpLogic;

/**
 * 前台用户登录态（独立 StpLogic）。
 *
 * <p><b>为什么必须独立</b>（技术方案 §9「后台隔离」）：{@code admin} 表与 {@code user} 表
 * 完全隔离，两者是两套账号体系。若共用一个 StpLogic，管理员登录后拿到的 token 会
 * 在前台接口上被当成普通用户（或反之），越权面无法收敛。
 * 因此用 {@code loginType="user"} 独立一套，登录态 key 形如 {@code hy:token:user:...}。</p>
 */
public final class StpUserUtil {

    /** 前台用户登录类型标识。 */
    public static final String LOGIN_TYPE = "user";

    /** 前台用户专用逻辑。注意：必须与 {@link StpLogicRegistry} 里登记的是同一个实例。 */
    public static final StpLogic STP = new StpLogic(LOGIN_TYPE);

    private StpUserUtil() {
    }

    /** 登录：写入登录态并返回 token。 */
    public static String login(Long userId) {
        STP.login(userId);
        return STP.getTokenValue();
    }

    /** 注销当前 token。 */
    public static void logout() {
        STP.logout();
    }

    /** 当前请求是否已登录前台账号。 */
    public static boolean isLogin() {
        return STP.isLogin();
    }

    /**
     * 校验登录，未登录抛 {@code NotLoginException}（由全局异常处理器翻译成 401）。
     * 返回值用 Object 是 Sa-Token 的约定（loginId 可能是数字或字符串）。
     */
    public static void checkLogin() {
        STP.checkLogin();
    }

    /** 当前登录用户 id；未登录时返回 null。 */
    public static Long currentUserId() {
        Object loginId = STP.getLoginIdDefaultNull();
        return loginId == null ? null : Long.valueOf(loginId.toString());
    }
}
