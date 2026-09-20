package com.hyforum.audit.controller;

import com.hyforum.audit.dto.ReportCreateRequest;
import com.hyforum.audit.service.ReportService;
import com.hyforum.common.api.ApiResponse;
import com.hyforum.common.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 举报入口（技术方案 §6.11、§8.7）。
 *
 * <table>
 *   <caption>端点</caption>
 *   <tr><th>方法</th><th>路径</th><th>鉴权</th></tr>
 *   <tr><td>POST</td><td>/api/report</td><td>是</td></tr>
 * </table>
 *
 * <p><b>为什么不标 {@code @AllowAnonymous}</b>：举报必须留下"谁报的"（{@code report.user_id}
 * 是 NOT NULL），而且 §8.7 的限流是<b>按用户</b>算的（10 次/天）——
 * 匿名举报既无法限流也无法追责。走拦截器 → {@code CurrentUser.requireId()}。</p>
 *
 * <p><b>限流超限返回什么</b>：HTTP <b>200</b> + 业务码（业务动作维度，§5 裁决 #6/#8）——
 * 与 M3 发帖的 {@code 2002} 同构，<b>不是</b>入口维度那种 HTTP 429。
 * 选用的具体码与理由写在 {@code ReportService} 的类注释里。</p>
 */
@RestController
@Tag(name = "举报", description = "提交举报（每用户每天有次数上限）")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    /**
     * 提交举报。
     *
     * <p>返回新建举报的 id（前端可据此提示"已收到，我们会尽快处理"）。
     * 幂等性：契约与 schema 都没有"同一人对同一目标只能报一次"的约束，
     * 因此本接口<b>允许重复举报</b>，由 §8.7 的每日上限兜住（理由见 ReportService）。</p>
     */
    @PostMapping("/api/report")
    @Operation(summary = "提交举报",
            description = "targetType：1 帖子 / 2 评论 / 3 用户；reasonType：1 违法违规 / 2 色情低俗 / "
                    + "3 广告垃圾 / 4 侵权 / 5 其他。超每日上限时返回 **HTTP 200 + 业务码**（业务动作维度）")
    public ApiResponse<Long> report(@Valid @RequestBody ReportCreateRequest request) {
        return ApiResponse.ok(reportService.create(CurrentUser.requireId(), request));
    }
}
