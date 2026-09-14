package com.hyforum.common.security;

/**
 * 账号状态校验点（封禁状态）。
 *
 * <p><b>为什么需要这个接口</b>：登录态只保证"token 有效"，不保证"账号现在仍可用"。
 * 被封禁的用户手里的旧 token 在过期前依然有效，若不查状态就等于封禁无效。
 * 因此每次请求都要校验一次账号状态。</p>
 *
 * <p><b>为什么定义在 common 而不是直接用 auth 的 Service</b>：铁律 3 要求七个业务包
 * 互不依赖，而拦截器属于 common 基础设施。若拦截器直接依赖 {@code com.hyforum.auth}
 * 的实现类，就等于 common → auth 的依赖，把业务包焊死在基础设施上（ArchUnit 规则也会
 * 从反向暴露这个问题）。所以这里只放"一个方法的能力声明"，由 auth 模块实现。</p>
 */
public interface AccountStatusChecker {

    /** 前台用户是否可用（存在、未逻辑删除、未封禁）。 */
    boolean isUserActive(Long userId);

    /** 后台管理员是否可用（存在且未被停用）。 */
    boolean isAdminActive(Long adminId);
}
