package com.hyforum.admin.controller;

import com.hyforum.admin.service.AdminSensitiveWordService;
import com.hyforum.admin.vo.AdminSensitiveWordVO;
import com.hyforum.common.api.ApiResponse;
import com.hyforum.common.api.PageResult;
import com.hyforum.common.security.StpAdminUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 管理后台-敏感词管理（M6 批次二；docs/技术方案.md §6.11）。
 *
 * <p>写操作（增删）在事务提交后刷新检查器内存快照 —— 新词立即参与判定，
 * 不需要重启（见 {@code AdminSensitiveWordService} 类注释）。</p>
 */
@RestController
@RequestMapping("/api/admin")
@Tag(name = "管理后台-敏感词", description = "敏感词列表 / 新增 / 删除（立即生效）")
public class AdminSensitiveWordController {

    private final AdminSensitiveWordService adminSensitiveWordService;

    public AdminSensitiveWordController(AdminSensitiveWordService adminSensitiveWordService) {
        this.adminSensitiveWordService = adminSensitiveWordService;
    }

    @GetMapping("/sensitive-words")
    @Operation(summary = "敏感词列表（后台）", description = "id 倒序（新增在前）")
    public ApiResponse<PageResult<AdminSensitiveWordVO>> list(
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        return ApiResponse.ok(adminSensitiveWordService.list(page, size));
    }

    @PostMapping("/sensitive-words")
    @Operation(summary = "新增敏感词",
            description = "≤100 字，uk_word 唯一；事务提交后刷新内存快照，立即生效")
    public ApiResponse<Long> add(@RequestBody WordRequest request, HttpServletRequest http) {
        String word = request == null ? null : request.word();
        return ApiResponse.ok(adminSensitiveWordService.add(
                StpAdminUtil.currentAdminId(), word, clientIp(http)));
    }

    @DeleteMapping("/sensitive-words/{id}")
    @Operation(summary = "删除敏感词",
            description = "物理删除（表无 is_deleted）；事务提交后刷新内存快照，立即生效")
    public ApiResponse<Long> remove(@PathVariable long id, HttpServletRequest http) {
        return ApiResponse.ok(adminSensitiveWordService.remove(
                StpAdminUtil.currentAdminId(), id, clientIp(http)));
    }

    /** 新增请求体。 */
    public record WordRequest(String word) {
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
