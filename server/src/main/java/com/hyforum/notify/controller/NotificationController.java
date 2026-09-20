package com.hyforum.notify.controller;

import com.hyforum.common.api.ApiResponse;
import com.hyforum.common.api.PageResult;
import com.hyforum.common.security.CurrentUser;
import com.hyforum.notify.service.NotificationService;
import com.hyforum.notify.vo.NotificationVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 消息通知接口（docs/技术方案.md §6.10）。
 *
 * <table>
 *   <caption>端点（全部需要登录）</caption>
 *   <tr><th>方法</th><th>路径</th><th>说明</th></tr>
 *   <tr><td>GET</td><td>/api/notifications</td><td>我的消息列表，参数 type／page／size</td></tr>
 *   <tr><td>GET</td><td>/api/notifications/unread-count</td><td>未读条数（前端红点）</td></tr>
 *   <tr><td>PUT</td><td>/api/notifications/read</td><td>标记已读（id 列表，或 all=true）</td></tr>
 * </table>
 *
 * <p><b>三个端点都不标 {@code @AllowAnonymous}／{@code @OptionalLogin}</b>：
 * 通知天然是"我的"，没有"未登录也能看"的语义，走拦截器 → {@code CurrentUser.requireId()}
 * 是唯一正确的形态。这里刻意不用 {@code idOrNull()} —— 那会让"忘了登录校验"变成可能。</p>
 */
@RestController
@Tag(name = "消息通知", description = "我的消息列表 / 未读数 / 标记已读")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /**
     * 我的消息列表（§6.10）。
     *
     * @param type 可选：1点赞 2评论 3回复 4关注 5系统；不传=全部
     */
    @GetMapping("/api/notifications")
    @Operation(summary = "我的消息列表",
            description = "时间倒序分页（上限 20）；type 可选。目标内容即使已被删除也照常返回，"
                    + "由前端在点开时依据目标接口的 404 显示\"内容已删除\"")
    public ApiResponse<PageResult<NotificationVO>> list(
            @RequestParam(required = false) Integer type,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        return ApiResponse.ok(notificationService.list(CurrentUser.requireId(), type, page, size));
    }

    /** 未读条数（§6.10，前端红点）。 */
    @GetMapping("/api/notifications/unread-count")
    @Operation(summary = "未读条数", description = "当前登录用户的未读通知数，供前端显示红点")
    public ApiResponse<Long> unreadCount() {
        return ApiResponse.ok(notificationService.unreadCount(CurrentUser.requireId()));
    }

    /**
     * 标记已读（§6.10）：传 {@code ids}，或 {@code all=true}。
     *
     * <p>两种方式都支持（任务书 §5 第 2 条要求写明选了哪种）：<b>两个都做</b>。
     * 点开单条用 {@code ids} 精准，进消息页"全部已读"用 {@code all} 一次请求 ——
     * 只做一种会逼前端循环调用 N 次。</p>
     *
     * <p>只允许标记<b>自己的</b>通知（服务层把 {@code user_id = 我} 放进 WHERE）。
     * 两者都不传 → 400，而不是"静默成功"（后者会让前端以为已读生效、而红点还在）。</p>
     */
    @PutMapping("/api/notifications/read")
    @Operation(summary = "标记已读",
            description = "传 ids 列表，或 all=true 全部已读（两者都支持）；只能标记自己的通知，"
                    + "两者都不传返回 400")
    public ApiResponse<Integer> markRead(@RequestBody(required = false) MarkReadRequest request) {
        List<Long> ids = request == null ? null : request.ids();
        boolean all = request != null && Boolean.TRUE.equals(request.all());
        return ApiResponse.ok(notificationService.markRead(CurrentUser.requireId(), ids, all));
    }

    /**
     * 标记已读请求体（§6.10：传 id 列表，或 {@code all=true}）。
     *
     * @param ids 要标记的通知 id 列表
     * @param all true = 我的全部未读标记为已读（此时忽略 ids）
     */
    @io.swagger.v3.oas.annotations.media.Schema(name = "MarkReadRequest", description = "标记已读请求")
    public record MarkReadRequest(List<Long> ids, Boolean all) {
    }
}
