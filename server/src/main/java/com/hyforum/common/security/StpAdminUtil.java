package com.hyforum.common.security;

import cn.dev33.satoken.stp.StpLogic;

/**
 * 后台管理员登录态（独立 StpLogic）。
 *
 * <p>与 {@link StpUserUtil} 完全对称，但 loginType 是 {@code admin}，
 * 因此两套登录态在 Redis 里是不同的 key 前缀，互不通用 —— 这就是技术方案 §9
 * 「后台隔离」在代码上的落地：<b>前台 token 拿不到后台接口，后台 token 也拿不到前台接口</b>。</p>
 *
 * <p>管理员账号<b>不受注册模式影响</b>（技术方案 §8.8）：{@code closed} 模式下前台注册
 * 全部拒绝，但 {@code /api/admin/login} 依然可用 —— 这是运行期可运维性的前提，
 * 否则把注册关掉就再也进不去后台了。</p>
 */
public final class StpAdminUtil {

    /** 后台管理员登录类型标识。 */
    public static final String LOGIN_TYPE = "admin";

    /** 后台管理员专用逻辑，与 {@link StpLogicRegistry} 登记的是同一实例。 */
    public static final StpLogic STP = new StpLogic(LOGIN_TYPE);

    private StpAdminUtil() {
    }

    /** 登录并返回 token。 */
    public static String login(Long adminId) {
        STP.login(adminId);
        return STP.getTokenValue();
    }

    /** 注销当前 token。 */
    public static void logout() {
        STP.logout();
    }

    /** 当前请求是否已登录后台账号。 */
    public static boolean isLogin() {
        return STP.isLogin();
    }

    /** 校验登录，未登录抛 {@code NotLoginException}。 */
    public static void checkLogin() {
        STP.checkLogin();
    }

    /** 当前登录管理员 id；未登录时返回 null。 */
    public static Long currentAdminId() {
        Object loginId = STP.getLoginIdDefaultNull();
        return loginId == null ? null : Long.valueOf(loginId.toString());
    }
}
