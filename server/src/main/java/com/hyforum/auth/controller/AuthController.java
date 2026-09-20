package com.hyforum.auth.controller;

import com.hyforum.auth.captcha.CaptchaService;
import com.hyforum.auth.dto.LoginRequest;
import com.hyforum.auth.dto.RegisterRequest;
import com.hyforum.auth.mode.RegisterMode;
import com.hyforum.auth.mode.RegisterModeService;
import com.hyforum.auth.service.AuthService;
import com.hyforum.auth.vo.CaptchaVO;
import com.hyforum.auth.vo.LoginVO;
import com.hyforum.auth.vo.RegisterModeVO;
import com.hyforum.common.api.ApiResponse;
import com.hyforum.common.api.ErrorCode;
import com.hyforum.common.config.RateLimitProperties;
import com.hyforum.common.exception.BizException;
import com.hyforum.common.redis.RateLimiter;
import com.hyforum.common.security.AllowAnonymous;
import com.hyforum.domain.user.vo.UserVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * 认证接口（docs/技术方案.md §6.2）。
 *
 * <p>五个端点逐一对应契约表格：</p>
 * <table>
 *   <caption>§6.2 认证模块</caption>
 *   <tr><th>方法</th><th>路径</th><th>鉴权</th></tr>
 *   <tr><td>GET</td><td>/api/auth/captcha</td><td>否</td></tr>
 *   <tr><td>POST</td><td>/api/auth/register</td><td>否</td></tr>
 *   <tr><td>POST</td><td>/api/auth/login</td><td>否</td></tr>
 *   <tr><td>POST</td><td>/api/auth/logout</td><td>是</td></tr>
 *   <tr><td>GET</td><td>/api/auth/register-mode</td><td>否</td></tr>
 * </table>
 *
 * <p>免登录端点用 {@link AllowAnonymous} 标注（不用"路径白名单"），
 * 这样"某个接口是否需要登录"这件事只在该接口自己身上定义一次。</p>
 *
 * <p><b>限流</b>：注册与登录是免登录接口，是刷量首选目标，按 §8.7 的
 * "同一 IP ≤ 10 次/分钟（登录）"精神做 IP 维度限流，超限返回 429 与 {@code Retry-After}
 * （对应跨里程碑验收项 {@code SEC_rate_limit_returns_429_with_retry_after} 的 429 部分）。
 * 此处实现的是 M1 必要的最小防线，完整的多维度限流仍由后续模块补齐。</p>
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "认证", description = "注册 / 登录 / 登出 / 图形验证码 / 注册模式")
public class AuthController {

    /** 限流动作标识：注册（IP 维度，1 分钟窗口）。 */
    private static final String RL_REGISTER = "register-ip-1m";

    /** 限流动作标识：登录（IP 维度，1 分钟窗口）。 */
    private static final String RL_LOGIN = "login-ip-1m";

    private static final Duration ONE_MINUTE = Duration.ofMinutes(1);

    private final AuthService authService;
    private final CaptchaService captchaService;
    private final RegisterModeService registerModeService;
    private final RateLimiter rateLimiter;
    private final RateLimitProperties rateLimitProperties;

    public AuthController(AuthService authService,
                          CaptchaService captchaService,
                          RegisterModeService registerModeService,
                          RateLimiter rateLimiter,
                          RateLimitProperties rateLimitProperties) {
        this.authService = authService;
        this.captchaService = captchaService;
        this.registerModeService = registerModeService;
        this.rateLimiter = rateLimiter;
        this.rateLimitProperties = rateLimitProperties;
    }

    /**
     * 获取图形验证码。返回 {@code {uuid, base64Image}}，答案写 Redis 300s（§6.2 / §7）。
     *
     * <p><b>CR-005（2026-09-15 批准并实施）</b>：返回类型从 {@code Map<String, Object>}
     * 改成具名 {@link CaptchaVO}。此前契约里 {@code data} 是 {@code Record<string, any>}，
     * 字段名没有静态声明，前端被迫加了一层人工窄化 + 运行时校验的临时层。
     * 字段名与顺序保持不变，前端行为零变化，只是契约从此有了静态形状。</p>
     */
    @GetMapping("/captcha")
    @AllowAnonymous
    @Operation(summary = "获取图形验证码", description = "返回 uuid 与 Base64 图片；答案存 Redis hy:captcha:{uuid}，TTL 300s")
    public ApiResponse<CaptchaVO> captcha() {
        CaptchaService.CaptchaChallenge challenge = captchaService.generate();
        return ApiResponse.ok(new CaptchaVO(
                challenge.uuid(), challenge.base64Image(), challenge.ttlSeconds()));
    }

    /**
     * 注册（§6.2 + §8.8）。
     *
     * <p>注册成功只返回用户信息，<b>不下发 token</b>：前端随后走正常登录流程。
     * 这样"鉴权"只有一条路径，不需要额外维护"注册即登录"的第二条路径。</p>
     */
    @PostMapping("/register")
    @AllowAnonymous
    @Operation(summary = "注册", description = "需图形验证码；agreeProtocol 必须为 true；invite 模式下 inviteCode 必填")
    public ApiResponse<UserVO> register(@Valid @RequestBody RegisterRequest request, HttpServletRequest http) {
        checkRateLimit(RL_REGISTER, clientIp(http));
        return ApiResponse.ok(authService.register(request));
    }

    /** 登录（§6.2）：返回 token 与用户信息。 */
    @PostMapping("/login")
    @AllowAnonymous
    @Operation(summary = "登录", description = "成功返回 token 与用户信息；token 通过 Authorization 头携带")
    public ApiResponse<LoginVO> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        checkRateLimit(RL_LOGIN, clientIp(http));
        return ApiResponse.ok(authService.login(request));
    }

    /** 注销当前 token（§6.2，需要登录 —— 因此不加 {@code @AllowAnonymous}）。 */
    @PostMapping("/logout")
    @Operation(summary = "注销登录", description = "注销当前请求携带的 token")
    public ApiResponse<Void> logout() {
        authService.logout();
        return ApiResponse.ok();
    }

    /**
     * 查询当前注册模式（§6.2）。前端据此决定是否渲染邀请码输入框，无需发版即可切换（§8.8）。
     *
     * <p><b>CR-005（2026-09-15 批准并实施）</b>：返回类型从 {@code Map<String, Object>}
     * 改成 {@link RegisterModeVO}，其 {@code mode} 是带取值约束的枚举
     * （契约里导出为 {@code enum: [open, invite, closed]}），前端可以做穷尽分支。
     * 响应字段名不变（{@code mode} / {@code inviteRequired}），前端行为零变化。</p>
     */
    @GetMapping("/register-mode")
    @AllowAnonymous
    @Operation(summary = "查询注册模式", description = "取值 open / invite / closed，来源为 sys_config.register_mode")
    public ApiResponse<RegisterModeVO> registerMode() {
        RegisterMode mode = registerModeService.currentMode();
        return ApiResponse.ok(new RegisterModeVO(
                RegisterModeVO.RegisterModeValue.from(mode),
                // 冗余一个布尔位，前端渲染条件更直观（值本身仍是唯一事实来源）
                mode == RegisterMode.INVITE));
    }

    /**
     * IP 维度限流。
     *
     * @param action 动作标识
     * @param ip     客户端 IP
     * @throws BizException 429 请求过于频繁
     */
    private void checkRateLimit(String action, String ip) {
        RateLimiter.Decision decision = rateLimiter.check(
                action, ip, rateLimitProperties.ipPerMinute(), ONE_MINUTE);
        if (!decision.allowed()) {
            // H2 已落地（M5）：把限流器算出的"还要等几秒"一并带上，
            // 由 GlobalExceptionHandler 写成 `Retry-After` 响应头
            // （§8.7 要求；此前只靠错误信息承载，前端拿不到头就得自己正则解析 message）。
            // 注意：**分层不变** —— 入口维度仍是 HTTP 429 + 业务码 429，
            // 发帖那个"业务动作维度"的 2002 保持 HTTP 200 + 业务码，两者不是同一层。
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS,
                    ErrorCode.TOO_MANY_REQUESTS.message() + "，请 " + decision.retryAfter() + " 秒后重试",
                    decision.retryAfter());
        }
    }

    /**
     * 取客户端 IP。
     *
     * <p>先看 {@code X-Forwarded-For}：生产环境前面有 Nginx（技术方案 §12），
     * 若不取该头，所有限流都会记在 Nginx 的 IP 上，等于全局限流。</p>
     *
     * <p>注意：{@code X-Forwarded-For} 可被伪造，因此它只能用于限流这类"稍宽松也无妨"的场景；
     * 不能用于安全判定（例如"同 IP 才能做的操作"）。M1 只用于限流，符合该约束。</p>
     *
     * <p>取不到时返回字面量 {@code "unknown"} 而不是 null：所有无法解析来源的请求共享一个桶，
     * 这样在测试（MockMvc/REST Assured 无真实 remoteAddr）与探针场景下行为是确定的。</p>
     */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        String remote = request.getRemoteAddr();
        return (remote == null || remote.isBlank()) ? "unknown" : remote;
    }
}
