package com.hyforum.admin.controller;

import com.hyforum.admin.service.AdminPostService;
import com.hyforum.admin.vo.AdminPostVO;
import com.hyforum.common.api.ApiResponse;
import com.hyforum.common.api.ErrorCode;
import com.hyforum.common.api.PageResult;
import com.hyforum.common.exception.BizException;
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

import java.util.Map;

/**
 * 管理后台-帖子审核（M6；docs/技术方案.md §6.11）。
 *
 * <p>路径前缀 {@code /api/admin} 由 {@code AuthInterceptor} 识别为"走后台 StpLogic"：
 * 匿名与前台 token 一律 401（§9 后台隔离）。</p>
 */
@RestController
@RequestMapping("/api/admin")
@Tag(name = "管理后台-帖子", description = "帖子审核队列 / 放行 / 屏蔽")
public class AdminPostController {

    private final AdminPostService adminPostService;

    public AdminPostController(AdminPostService adminPostService) {
        this.adminPostService = adminPostService;
    }

    @GetMapping("/posts")
    @Operation(summary = "帖子列表（后台）",
            description = "可按 status 筛选：0 待审核 / 1 正常 / 2 已屏蔽；不传=全部。时间升序（先处理早的）")
    public ApiResponse<PageResult<AdminPostVO>> listPosts(
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        return ApiResponse.ok(adminPostService.listPosts(status, page, size));
    }

    /**
     * 帖子审核：<b>放行（1）/ 屏蔽（2）</b>，同一事务内写留痕。
     *
     * <p>请求体形状与评论审核（{@code PUT /api/admin/comments/{id}/status}）一致：
     * {@code {status, reason?}}，屏蔽时 reason 必填 —— 两个审核端点保持同构，
     * 管理端前端可以复用同一套处置交互。</p>
     */
    @PutMapping("/posts/{id}/status")
    @Operation(summary = "帖子审核（放行/屏蔽）",
            description = "status=1 放行 / 2 屏蔽；屏蔽必须给 reason。"
                    + "业务改动与留痕在同一事务内：留痕失败则状态改动一并回滚（§6.11 留痕红线）")
    public ApiResponse<Long> reviewPost(@PathVariable long id,
                                        @RequestBody ReviewRequest request,
                                        HttpServletRequest http) {
        Integer status = request == null ? null : request.status();
        if (status == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "status 不能为空（1 放行 / 2 屏蔽）");
        }
        return ApiResponse.ok(adminPostService.reviewPost(
                StpAdminUtil.currentAdminId(), id, status, request.reason(), clientIp(http)));
    }

    /** 审核请求体：与评论审核同构。 */
    public record ReviewRequest(Integer status, String reason) {
    }

    /**
     * 取操作来源 IP（留痕要记，45 字符以兼容 IPv6）。
     *
     * <p>与 {@code AdminAuthController}/{@code AdminCommentController} 的 clientIp 同一口径：
     * 只信 Nginx 覆盖写入的 {@code X-Real-IP}，不读可伪造的 {@code X-Forwarded-For}。
     * 刻意复制不抽公共工具 —— 铁律 3 按包判依赖，两行代码不值得建共享件
     * （三处注释互为指认，改一处必须看其余）。</p>
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
