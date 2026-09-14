package com.hyforum.admin.controller;

import com.hyforum.admin.service.AdminAuthService;
import com.hyforum.admin.vo.AdminLoginVO;
import com.hyforum.common.api.ApiResponse;
import com.hyforum.common.security.AllowAnonymous;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台认证接口（docs/技术方案.md §6.11 前两行的一部分）。
 *
 * <p>路径前缀 {@code /api/admin} 会被 {@code AuthInterceptor} 识别为"走后台 StpLogic"，
 * 因此 {@code /logout} 自动要求<b>后台</b>登录态 —— 前台 token 打到这里会得到 401，
 * 反之亦然（技术方案 §9「后台隔离」）。</p>
 */
@RestController
@RequestMapping("/api/admin")
@Tag(name = "管理后台-认证", description = "管理员登录 / 注销")
public class AdminAuthController {

    private final AdminAuthService adminAuthService;

    public AdminAuthController(AdminAuthService adminAuthService) {
        this.adminAuthService = adminAuthService;
    }

    /**
     * 管理员登录（§6.11）。
     *
     * <p><b>不受注册模式影响</b>：{@code register_mode=closed} 时本接口依然可用
     * （验收项 {@code M1_admin_unaffected_by_register_mode}）。</p>
     */
    @PostMapping("/login")
    @AllowAnonymous
    @Operation(summary = "管理员登录", description = "与前台账号完全隔离；不受注册模式（open/invite/closed）影响")
    public ApiResponse<AdminLoginVO> login(@Valid @RequestBody AdminLoginRequest request) {
        return ApiResponse.ok(adminAuthService.login(request.username(), request.password()));
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
