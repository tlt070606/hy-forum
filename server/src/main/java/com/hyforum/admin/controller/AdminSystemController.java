package com.hyforum.admin.controller;

import com.hyforum.admin.service.AdminConfigService;
import com.hyforum.admin.service.AdminInviteService;
import com.hyforum.admin.service.AdminLogService;
import com.hyforum.admin.vo.AdminInviteCodeVO;
import com.hyforum.admin.vo.AdminLogVO;
import com.hyforum.common.api.ApiResponse;
import com.hyforum.common.api.PageResult;
import com.hyforum.common.security.StpAdminUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 管理后台-系统（M6；docs/技术方案.md §6.11）：注册模式切换 / 邀请码 / 操作留痕查询。
 *
 * <p>三个低频管理动作合成一个控制器（同属"系统设置"域）；帖子与用户各自成控制器，
 * 因为它们有自己的资源集合。路径前缀 {@code /api/admin} 由 {@code AuthInterceptor}
 * 识别为"走后台 StpLogic"。</p>
 */
@RestController
@RequestMapping("/api/admin")
@Tag(name = "管理后台-系统", description = "注册模式 / 邀请码 / 操作留痕")
public class AdminSystemController {

    private final AdminConfigService adminConfigService;
    private final AdminInviteService adminInviteService;
    private final AdminLogService adminLogService;

    public AdminSystemController(AdminConfigService adminConfigService,
                                 AdminInviteService adminInviteService,
                                 AdminLogService adminLogService) {
        this.adminConfigService = adminConfigService;
        this.adminInviteService = adminInviteService;
        this.adminLogService = adminLogService;
    }

    @PutMapping("/configs/register-mode")
    @Operation(summary = "切换注册模式",
            description = "mode=open/invite/closed；立即生效（读侧无缓存）。留痕 register_mode:A->B")
    public ApiResponse<Long> switchRegisterMode(@RequestBody ModeRequest request,
                                                HttpServletRequest http) {
        String mode = request == null ? null : request.mode();
        return ApiResponse.ok(adminConfigService.switchRegisterMode(
                StpAdminUtil.currentAdminId(), mode, clientIp(http)));
    }

    @PostMapping("/invite-codes")
    @Operation(summary = "生成邀请码",
            description = "count 1–50（默认 1）；expireDays 1–365（缺省=永不过期）。"
                    + "一次生成一条留痕（detail=count:N）")
    public ApiResponse<List<String>> generate(@RequestBody(required = false) GenerateRequest request,
                                              HttpServletRequest http) {
        Integer count = request == null ? null : request.count();
        Integer expireDays = request == null ? null : request.expireDays();
        return ApiResponse.ok(adminInviteService.generate(
                StpAdminUtil.currentAdminId(), count, expireDays, clientIp(http)));
    }

    @GetMapping("/invite-codes")
    @Operation(summary = "邀请码列表（后台）",
            description = "可按 status 筛选：0 未使用 / 1 已使用 / 2 已失效；id 倒序（新生成的在前）")
    public ApiResponse<PageResult<AdminInviteCodeVO>> listInviteCodes(
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        return ApiResponse.ok(adminInviteService.list(status, page, size));
    }

    @GetMapping("/logs")
    @Operation(summary = "操作留痕查询（后台）",
            description = "可按 action 筛选（POST_STATUS/USER_BAN/REGISTER_MODE/...）；时间倒序。"
                    + "PLAN M6 验收：每个管理动作均可在本接口追溯")
    public ApiResponse<PageResult<AdminLogVO>> listLogs(
            @RequestParam(required = false) String action,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        return ApiResponse.ok(adminLogService.list(action, page, size));
    }

    /** 注册模式切换请求体。 */
    public record ModeRequest(String mode) {
    }

    /** 邀请码生成请求体。 */
    public record GenerateRequest(Integer count, Integer expireDays) {
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
