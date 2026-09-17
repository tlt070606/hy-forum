package com.hyforum.common.web;

import cn.dev33.satoken.exception.NotLoginException;
import com.hyforum.common.api.ErrorCode;
import com.hyforum.common.exception.BizException;
import com.hyforum.common.security.AccountStatusChecker;
import com.hyforum.common.security.AllowAnonymous;
import com.hyforum.common.security.CurrentUser;
import com.hyforum.common.security.OptionalLogin;
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
        // 登录可选优先判定：两者同时标注时按"可选"处理（理由见 OptionalLogin 的类注释）
        if (isOptionalLogin(handlerMethod)) {
            resolveOptionalUser();
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

    private boolean isOptionalLogin(HandlerMethod handlerMethod) {
        return AnnotatedElementUtils.hasAnnotation(handlerMethod.getMethod(), OptionalLogin.class)
                || AnnotatedElementUtils.hasAnnotation(handlerMethod.getBeanType(), OptionalLogin.class);
    }

    /**
     * 「登录可选」：尽力解析当前用户，解析不到就<b>确定地什么都不写</b>（= 匿名）。
     *
     * <p>由 M4 收敛出 {@link OptionalLogin}（H13），见任务书 §5.4。三条边界：</p>
     * <ol>
     *   <li><b>没有 token / token 无效</b> → 放行，且<b>不写</b> {@link CurrentUser}，
     *       业务侧 {@code CurrentUser.idOrNull()} 得到 {@code null}
     *       —— 刻意的：绝不能"取不到就当 0"，那会把匿名用户伪装成 id=0 的用户；</li>
     *   <li><b>token 有效但账号被封禁</b> → 仍然按 1004 拒绝（<b>不降级成匿名</b>）。
     *       降级会让"封禁"变成一条可以绕过的软限制：封了号还能继续匿名看内容、
     *       且日志里看不出他来过。封禁即时生效是既有规则（见 {@link #checkUser}），
     *       这里必须保持一致；</li>
     *   <li><b>不碰后台逻辑</b>：{@code /api/admin} 下的接口不可能标 {@code @OptionalLogin}
     *       （后台没有"匿名也能看"的语义），因此本分支不区分前后台路径。</li>
     * </ol>
     *
     * <p>本方法<b>不自己写响应、也不抛业务异常以外的异常</b>：需要拒绝时抛
     * {@link BizException}，由全局异常处理器统一翻译成契约响应体 ——
     * 与 {@link #checkUser} 同源，避免"两处拼装响应体"的漂移。</p>
     */
    private void resolveOptionalUser() {
        Long userId;
        try {
            userId = StpUserUtil.currentUserId();
        } catch (RuntimeException ex) {
            // token 存在但形态非法（例如被截断）：按匿名处理，而不是 500。
            // 这是"登录可选"与"必须登录"的关键差别 —— 前者不该因为一个坏 token 就报错。
            log.debug("可选登录：token 解析失败，按匿名处理（{}）", ex.getMessage());
            return;
        }
        if (userId == null) {
            return;
        }
        if (!accountStatusChecker.isUserActive(userId)) {
            StpUserUtil.logout();
            throw new BizException(ErrorCode.ACCOUNT_DISABLED);
        }
        CurrentUser.set(userId);
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
