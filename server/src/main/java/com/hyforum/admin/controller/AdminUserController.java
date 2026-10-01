package com.hyforum.admin.controller;

import com.hyforum.admin.service.AdminUserService;
import com.hyforum.admin.vo.AdminUserVO;
import com.hyforum.common.api.ApiResponse;
import com.hyforum.common.api.PageResult;
import com.hyforum.common.security.StpAdminUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理后台-用户管理（M6；docs/技术方案.md §6.11）：搜索 / 封禁 / 解封。
 *
 * <p>路径前缀 {@code /api/admin} 由 {@code AuthInterceptor} 识别为"走后台 StpLogic"。</p>
 */
@RestController
@RequestMapping("/api/admin")
@Tag(name = "管理后台-用户", description = "用户搜索 / 封禁 / 解封")
public class AdminUserController {

    private final AdminUserService adminUserService;

    public AdminUserController(AdminUserService adminUserService) {
        this.adminUserService = adminUserService;
    }

    @GetMapping("/users")
    @Operation(summary = "用户列表（后台）",
            description = "username / nickname 模糊搜索（keyword 可空 = 全量分页）；id 倒序")
    public ApiResponse<PageResult<AdminUserVO>> listUsers(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        return ApiResponse.ok(adminUserService.listUsers(keyword, page, size));
    }

    /**
     * 封禁用户：reason <b>必填</b>（合规 C9）；封禁即时生效（状态检查 + 踢下线双保险，见服务注释）。
     */
    @PutMapping("/users/{id}/ban")
    @Operation(summary = "封禁用户",
            description = "status→0 并踢掉全部登录态；必须给 reason。"
                    + "封禁后该用户下一次请求即被拒（1004），不等 token 过期")
    public ApiResponse<Long> ban(@PathVariable long id,
                                 @RequestBody BanRequest request,
                                 HttpServletRequest http) {
        String reason = request == null ? null : request.reason();
        return ApiResponse.ok(adminUserService.ban(
                StpAdminUtil.currentAdminId(), id, reason, clientIp(http)));
    }

    /** 解封用户：reason 可选（恢复性动作）。 */
    @PutMapping("/users/{id}/unban")
    @Operation(summary = "解封用户", description = "status→1；reason 可选")
    public ApiResponse<Long> unban(@PathVariable long id,
                                   @RequestBody BanRequest request,
                                   HttpServletRequest http) {
        String reason = request == null ? null : request.reason();
        return ApiResponse.ok(adminUserService.unban(
                StpAdminUtil.currentAdminId(), id, reason, clientIp(http)));
    }

    /** 封禁/解封请求体。 */
    public record BanRequest(String reason) {
    }

    /**
     * 取操作来源 IP —— 与 {@code AdminPostController#clientIp} 同一口径、同一复制理由
     * （铁律 3 按包判依赖；三处注释互为指认）。
     */
    private static String clientIp(HttpServletRequest request) {
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        String remote = request.getRemoteAddr();
        return (remote == null || remote.isBlank()) ? "unknown" : remote;
    }
}
