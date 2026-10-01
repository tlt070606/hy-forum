package com.hyforum.admin.controller;

import com.hyforum.admin.service.AdminBoardService;
import com.hyforum.admin.vo.AdminBoardVO;
import com.hyforum.common.api.ApiResponse;
import com.hyforum.common.security.StpAdminUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理后台-版块管理（M6 批次二；docs/技术方案.md §6.11）。
 */
@RestController
@RequestMapping("/api/admin")
@Tag(name = "管理后台-版块", description = "版块列表 / 新建 / 更新（停用即前台隐藏）")
public class AdminBoardController {

    private final AdminBoardService adminBoardService;

    public AdminBoardController(AdminBoardService adminBoardService) {
        this.adminBoardService = adminBoardService;
    }

    @GetMapping("/boards")
    @Operation(summary = "版块列表（后台）",
            description = "含停用版块；按 sort 升序（与前台同口径）；带 post_count")
    public ApiResponse<List<AdminBoardVO>> listBoards() {
        return ApiResponse.ok(adminBoardService.listBoards());
    }

    @PostMapping("/boards")
    @Operation(summary = "新建版块",
            description = "name 必填（≤30 字）；slug 小写字母/数字/连字符 2–30 且唯一，创建后不可改")
    public ApiResponse<Long> create(@RequestBody CreateRequest request, HttpServletRequest http) {
        return ApiResponse.ok(adminBoardService.createBoard(
                StpAdminUtil.currentAdminId(),
                request == null ? null : request.name(),
                request == null ? null : request.slug(),
                request == null ? null : request.description(),
                request == null ? null : request.sort(),
                request == null ? null : request.isResource(),
                clientIp(http)));
    }

    @PutMapping("/boards/{id}")
    @Operation(summary = "更新版块",
            description = "部分更新（只改传了的字段）；slug 不可改；status=0 停用即前台隐藏")
    public ApiResponse<Long> update(@PathVariable long id,
                                    @RequestBody UpdateRequest request,
                                    HttpServletRequest http) {
        return ApiResponse.ok(adminBoardService.updateBoard(
                StpAdminUtil.currentAdminId(), id,
                request == null ? null : request.name(),
                request == null ? null : request.description(),
                request == null ? null : request.sort(),
                request == null ? null : request.status(),
                request == null ? null : request.isResource(),
                clientIp(http)));
    }

    /** 新建请求体。 */
    public record CreateRequest(String name, String slug, String description,
                                Integer sort, Integer isResource) {
    }

    /** 更新请求体（部分更新；slug 不接受）。 */
    public record UpdateRequest(String name, String description,
                                Integer sort, Integer status, Integer isResource) {
    }

    /**
     * 取操作来源 IP —— 与其他 Admin 控制器同一口径、同一复制理由（铁律 3 按包判依赖）。
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
