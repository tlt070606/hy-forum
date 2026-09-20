package com.hyforum.audit.controller;

import com.hyforum.common.api.ApiResponse;
import com.hyforum.common.api.PageResult;
import com.hyforum.common.security.StpAdminUtil;
import com.hyforum.audit.service.CommentAuditService;
import com.hyforum.audit.vo.AdminCommentVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
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
 * 后台评论审核接口（docs/技术方案.md §6.11）—— <b>审核队列的出口</b>。
 *
 * <table>
 *   <caption>端点</caption>
 *   <tr><th>方法</th><th>路径</th><th>说明</th></tr>
 *   <tr><td>GET</td><td>/api/admin/comments</td><td>评论列表，可按 status 筛选（含待审 0）</td></tr>
 *   <tr><td>PUT</td><td>/api/admin/comments/{id}/status</td><td><b>审核</b>：放行(1) / 屏蔽(2)</td></tr>
 * </table>
 *
 * <h2>为什么要做在这里（而不是等 M6）</h2>
 * <p>§8.6 第 2 条要求命中敏感词的评论进待审队列，而 <b>这个接口是那个队列的唯一出口</b>。
 * 本项目已经记录过一次同类缺陷（图片审核队列只进不出 → CR-006／P1-2），
 * 任务书 §3 第 3 条因此明确"本任务必须给出审核出口"。M6 续做其余后台接口。</p>
 *
 * <h2>鉴权：走后台独立登录态，不接受任何"前台传管理员 id"</h2>
 * <p>本类位于 {@code /api/admin/**}，{@code AuthInterceptor} 会按路径前缀校验<b>后台</b>
 * 登录态（{@code StpAdminUtil}，§9 后台隔离）—— 前台 token 打到这里会被判未登录（401），
 * 反之亦然。{@code adminId} 一律从 {@link StpAdminUtil#currentAdminId()} 取，
 * <b>绝不接受请求体传入</b>：那是"任何人都能伪造留痕里的操作人"的入口。</p>
 *
 * <p>因此本类<b>刻意不标</b> {@code @AllowAnonymous}／{@code @OptionalLogin}：
 * 后台没有"匿名也能看"的语义。</p>
 */
@RestController
@RequestMapping("/api/admin")
@Tag(name = "后台-评论审核", description = "评论列表（可按状态筛选）/ 审核放行与屏蔽（含留痕）")
public class AdminCommentController {

    private final CommentAuditService commentAuditService;

    public AdminCommentController(CommentAuditService commentAuditService) {
        this.commentAuditService = commentAuditService;
    }

    /**
     * 后台评论列表（§6.11）：可按 {@code status} 筛选；不传则<b>全部状态都返回</b>。
     *
     * <p>不传时返回全部是刻意的：后台的职责就是看见前台看不见的东西。
     * 若默认只返回正常评论，审核队列页就永远看不到待审内容。</p>
     */
    @GetMapping("/comments")
    @Operation(summary = "评论列表（后台）",
            description = "可按 status 筛选：0 待审 / 1 正常 / 2 已屏蔽；不传=全部。时间升序（先处理早的）")
    public ApiResponse<PageResult<AdminCommentVO>> listComments(
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        return ApiResponse.ok(commentAuditService.listComments(status, page, size));
    }

    /**
     * 评论审核（§6.11）：<b>放行（1）与屏蔽（2）两个方向</b>，同一事务内写留痕。
     *
     * @param status 1 放行 / 2 屏蔽；不接受 0
     * @param reason 处置理由；<b>屏蔽时必填</b>（合规 C9）
     */
    @PutMapping("/comments/{id}/status")
    @Operation(summary = "评论审核（放行/屏蔽）",
            description = "status=1 放行 / 2 屏蔽；屏蔽必须给 reason。"
                    + "业务改动与留痕在同一事务内：留痕失败则状态改动一并回滚（§6.11 留痕红线）")
    public ApiResponse<Long> reviewComment(@PathVariable long id,
                                           @RequestBody ReviewRequest request,
                                           HttpServletRequest http) {
        Integer status = request == null ? null : request.status();
        if (status == null) {
            throw new com.hyforum.common.exception.BizException(
                    com.hyforum.common.api.ErrorCode.BAD_REQUEST, "status 不能为空（1 放行 / 2 屏蔽）");
        }
        String reason = request.reason();
        long logId = commentAuditService.reviewComment(
                StpAdminUtil.currentAdminId(), id, status, reason, clientIp(http));
        return ApiResponse.ok(logId);
    }

    /** 取操作来源 IP（留痕要记，45 字符以兼容 IPv6）。 */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * 审核请求体。
     *
     * @param status 1 放行 / 2 屏蔽（不接受 0）
     * @param reason 处置理由；屏蔽时必填
     */
    @Schema(name = "AdminCommentReviewRequest", description = "评论审核请求")
    public record ReviewRequest(Integer status, String reason) {
    }
}
