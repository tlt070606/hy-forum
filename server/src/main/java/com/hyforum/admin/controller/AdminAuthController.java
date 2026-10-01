package com.hyforum.admin.controller;

import com.hyforum.admin.service.AdminAuthService;
import com.hyforum.admin.vo.AdminLoginVO;
import com.hyforum.common.api.ApiResponse;
import com.hyforum.common.api.ErrorCode;
import com.hyforum.common.config.RateLimitProperties;
import com.hyforum.common.exception.BizException;
import com.hyforum.common.redis.RateLimiter;
import com.hyforum.common.security.AllowAnonymous;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * 后台认证接口（docs/技术方案.md §6.11 前两行的一部分）。
 *
 * <p>路径前缀 {@code /api/admin} 会被 {@code AuthInterceptor} 识别为"走后台 StpLogic"，
 * 因此 {@code /logout} 自动要求<b>后台</b>登录态 —— 前台 token 打到这里会得到 401，
 * 反之亦然（技术方案 §9「后台隔离」）。</p>
 *
 * <p><b>限流（2026-10-01 安全整改）</b>：本控制器此前是全站唯一没有任何限流的认证端点 ——
 * 前台登录/注册有 IP 限流，后台登录为 0。后台口令不设验证码（M6 做后台界面时一并补），
 * 若口令强度不足，在线爆破是现实威胁，因此登录端点按 IP 限流，
 * 限额刻意比前台紧一个量级（{@code hy.rate-limit.admin-login-per-minute}，默认 10）。</p>
 */
@RestController
@RequestMapping("/api/admin")
@Tag(name = "管理后台-认证", description = "管理员登录 / 注销")
public class AdminAuthController {

    /** 限流动作标识：管理员登录（IP 维度，1 分钟窗口）。 */
    private static final String RL_ADMIN_LOGIN = "admin-login-ip-1m";

    private final AdminAuthService adminAuthService;
    private final RateLimiter rateLimiter;
    private final RateLimitProperties rateLimitProperties;

    public AdminAuthController(AdminAuthService adminAuthService,
                               RateLimiter rateLimiter,
                               RateLimitProperties rateLimitProperties) {
        this.adminAuthService = adminAuthService;
        this.rateLimiter = rateLimiter;
        this.rateLimitProperties = rateLimitProperties;
    }

    /**
     * 管理员登录（§6.11）。
     *
     * <p><b>不受注册模式影响</b>：{@code register_mode=closed} 时本接口依然可用
     * （验收项 {@code M1_admin_unaffected_by_register_mode}）。</p>
     */
    @PostMapping("/login")
    @AllowAnonymous
    @Operation(summary = "管理员登录", description = "与前台账号完全隔离；不受注册模式（open/invite/closed）影响；IP 限流 10 次/分钟")
    public ApiResponse<AdminLoginVO> login(@Valid @RequestBody AdminLoginRequest request, HttpServletRequest http) {
        String ip = clientIp(http);
        RateLimiter.Decision decision = rateLimiter.check(
                RL_ADMIN_LOGIN, ip, rateLimitProperties.adminLoginPerMinute(), Duration.ofMinutes(1));
        if (!decision.allowed()) {
            // 与前台登录同一形态：HTTP 429 + Retry-After 头（GlobalExceptionHandler 统一写头）
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS,
                    ErrorCode.TOO_MANY_REQUESTS.message() + "，请 " + decision.retryAfter() + " 秒后重试",
                    decision.retryAfter());
        }
        return ApiResponse.ok(adminAuthService.login(request.username(), request.password()));
    }

    /**
     * 取客户端 IP：与 {@code AuthController#clientIp} 同一口径 —— 只信 Nginx 覆盖写入的
     * {@code X-Real-IP}，其次直连对端地址，<b>不读可伪造的 {@code X-Forwarded-For}</b>。
     *
     * <p>刻意复制而不是抽公共工具：这两个方法分别属于前台与后台两个业务包，
     * 抽到 {@code common} 就成了"为两行代码建立共享依赖"，而铁律 3 的守卫
     * （ArchitectureRulesTest）按包判依赖 —— 复制带来的漂移风险由两边的
     * 注释互相指认来兜住（改一处必须看另一处）。</p>
     */
    private String clientIp(HttpServletRequest request) {
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        String remote = request.getRemoteAddr();
        return (remote == null || remote.isBlank()) ? "unknown" : remote;
    }

    /** 注销后台登录态（需要后台登录态）。 */
    @PostMapping("/logout")
    @Operation(summary = "管理员注销", description = "注销当前后台 token")
    public ApiResponse<Void> logout() {
        adminAuthService.logout();
        return ApiResponse.ok();
    }

    /**
     * 管理员登录请求体（§6.11 未展开参数，按前台登录的字段形状对齐）。
     *
     * @param username 管理员登录名
     * @param password 明文密码（WRITE_ONLY，不参与序列化）
     */
    public record AdminLoginRequest(
            @NotBlank(message = "用户名不能为空") String username,
            @NotBlank(message = "密码不能为空")
            @com.fasterxml.jackson.annotation.JsonProperty(
                    access = com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY)
            String password) {
    }
}
