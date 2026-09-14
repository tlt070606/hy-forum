package com.hyforum.common.web;

import cn.dev33.satoken.exception.NotLoginException;
import com.hyforum.common.api.ErrorCode;
import com.hyforum.common.exception.BizException;
import com.hyforum.common.security.AccountStatusChecker;
import com.hyforum.common.security.AllowAnonymous;
import com.hyforum.common.security.CurrentUser;
import com.hyforum.common.security.StpAdminUtil;
import com.hyforum.common.security.StpUserUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 登录态拦截器：<b>前后台各走各自的一套 StpLogic</b>。
 *
 * <p>规则（与 docs/技术方案.md §6.1「鉴权：请求头 Authorization，Sa-Token」一致）：</p>
 * <ol>
 *   <li>被 {@link AllowAnonymous} 标注的 handler → 直接放行；</li>
 *   <li>路径以 {@code /api/admin} 开头 → 校验<b>后台</b>登录态（StpAdminUtil）；</li>
 *   <li>其余 {@code /api/**} → 校验<b>前台</b>登录态（StpUserUtil）；</li>
 *   <li>非 {@code /api/**}（文档页、actuator、静态资源）→ 不拦截。</li>
 * </ol>
 *
 * <p>关键点：<b>两套逻辑互不通用</b>。后台 token 打到前台接口会因为 loginType 不同而
 * 查不到登录态 → 401，反之亦然。这是技术方案 §9「后台隔离」的技术实现，
 * 也对应验收项 {@code SEC_admin_token_rejected_on_user_api}（该验收项由后续模块补测）。</p>
 *
 * <p>返回值一律抛 {@link BizException}，由全局异常处理器翻译成统一响应体 ——
 * 拦截器不自行写响应，避免出现"两处拼装响应体"的漂移风险。</p>
 */
public class AuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuthInterceptor.class);

    /** 后台接口前缀（技术方案 §6.1：后台统一前缀 /api/admin）。 */
    private static final String ADMIN_PREFIX = "/api/admin";

    private final AccountStatusChecker accountStatusChecker;

    public AuthInterceptor(AccountStatusChecker accountStatusChecker) {
        this.accountStatusChecker = accountStatusChecker;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 静态资源 / 非 HandlerMethod：不涉及业务，直接放行
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        if (isAnonymous(handlerMethod)) {
            return true;
        }

        String path = request.getRequestURI();
        if (path.startsWith(ADMIN_PREFIX)) {
            checkAdmin();
        } else {
            checkUser();
        }
        return true;
    }

    private boolean isAnonymous(HandlerMethod handlerMethod) {
        return AnnotatedElementUtils.hasAnnotation(handlerMethod.getMethod(), AllowAnonymous.class)
                || AnnotatedElementUtils.hasAnnotation(handlerMethod.getBeanType(), AllowAnonymous.class);
    }

    /** 前台鉴权：登录态 + 账号状态（封禁即时生效，不等 token 过期）。 */
    private void checkUser() {
        try {
            StpUserUtil.checkLogin();
        } catch (NotLoginException ex) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        Long userId = StpUserUtil.currentUserId();
        if (userId == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        if (!accountStatusChecker.isUserActive(userId)) {
            // 账号被封禁：按契约返回 1004，并且顺手注销其登录态
            StpUserUtil.logout();
            throw new BizException(ErrorCode.ACCOUNT_DISABLED);
        }
        CurrentUser.set(userId);
    }

    /** 后台鉴权：同样的两步校验，但走 admin 的独立登录态。 */
    private void checkAdmin() {
        try {
            StpAdminUtil.checkLogin();
        } catch (NotLoginException ex) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        Long adminId = StpAdminUtil.currentAdminId();
        if (adminId == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        if (!accountStatusChecker.isAdminActive(adminId)) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // 必须清理：Tomcat 线程复用，残留身份会造成越权
        CurrentUser.clear();
    }
}
