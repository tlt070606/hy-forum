package com.hyforum.admin.controller;

import com.hyforum.admin.service.AdminReportService;
import com.hyforum.admin.vo.AdminReportVO;
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
 * 管理后台-举报处理（M6 批次二；docs/技术方案.md §6.11）。
 *
 * <p>本控制器只登记处置结论；屏蔽内容走帖子/评论的审核端点（单一写入口原则，
 * 见 {@code AdminReportService} 类注释）。路径前缀 {@code /api/admin} 走后台 StpLogic。</p>
 */
@RestController
@RequestMapping("/api/admin")
@Tag(name = "管理后台-举报", description = "举报列表 / 处置登记")
public class AdminReportController {

    private final AdminReportService adminReportService;

    public AdminReportController(AdminReportService adminReportService) {
        this.adminReportService = adminReportService;
    }

    @GetMapping("/reports")
    @Operation(summary = "举报列表（后台）",
            description = "可按 status 筛选：0 待处理 / 1 已处理 / 2 已驳回；时间升序（先处理早的）；带对象摘要")
    public ApiResponse<PageResult<AdminReportVO>> listReports(
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        return ApiResponse.ok(adminReportService.listReports(status, page, size));
    }

    /**
     * 处置举报：登记结论（1 已处理 / 2 已驳回）+ 必填说明 + 留痕。
     * 被举报内容本身的屏蔽/放行走帖子与评论审核端点。
     */
    @PutMapping("/reports/{id}/dispose")
    @Operation(summary = "处置举报",
            description = "outcome=1 已处理 / 2 已驳回；note 必填（C9）。"
                    + "留痕 target 指向被举报对象（追溯链锚点），举报行 id 记在 detail")
    public ApiResponse<Long> dispose(@PathVariable long id,
                                     @RequestBody DisposeRequest request,
                                     HttpServletRequest http) {
        Integer outcome = request == null ? null : request.outcome();
        String note = request == null ? null : request.note();
        return ApiResponse.ok(adminReportService.dispose(
                StpAdminUtil.currentAdminId(), id,
                outcome == null ? -1 : outcome, note, clientIp(http)));
    }

    /** 处置请求体。 */
    public record DisposeRequest(Integer outcome, String note) {
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
