package com.hyforum.interaction.controller;

import com.hyforum.common.api.ApiResponse;
import com.hyforum.common.api.ErrorCode;
import com.hyforum.common.api.PageResult;
import com.hyforum.common.config.RateLimitProperties;
import com.hyforum.common.exception.BizException;
import com.hyforum.common.redis.RateLimiter;
import com.hyforum.common.security.AllowAnonymous;
import com.hyforum.common.security.CurrentUser;
import com.hyforum.interaction.dto.CommentCreateRequest;
import com.hyforum.interaction.service.CommentService;
import com.hyforum.interaction.vo.CommentItemVO;
import com.hyforum.interaction.vo.CommentReplyVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * 评论接口（docs/技术方案.md §6.6）。
 *
 * <table>
 *   <caption>端点</caption>
 *   <tr><th>方法</th><th>路径</th><th>鉴权</th></tr>
 *   <tr><td>GET</td><td>/api/posts/{id}/comments</td><td>否（{@code @AllowAnonymous}）</td></tr>
 *   <tr><td>GET</td><td>/api/comments/{rootId}/replies</td><td>否</td></tr>
 *   <tr><td>POST</td><td>/api/comments</td><td>是</td></tr>
 *   <tr><td>DELETE</td><td>/api/comments/{id}</td><td>是</td></tr>
 *   <tr><td>POST</td><td>/api/comments/{id}/like</td><td>是</td></tr>
 *   <tr><td>DELETE</td><td>/api/comments/{id}/like</td><td>是</td></tr>
 * </table>
 *
 * <p><b>读接口为什么用 {@code @AllowAnonymous} 而不是 {@code @OptionalLogin}</b>：
 * 评论列表的形状里没有任何"因人而异"的字段（当前用户是否点过赞不在这里返回），
 * 因此不需要解析登录态。按需才开 —— 多解析一次登录态就多一处"取不到就当 0"
 * 的犯错机会（{@code OptionalLogin} 的类注释讲了这条）。</p>
 *
 * <p><b>写接口一律不标注解</b>：走拦截器 → {@code CurrentUser.requireId()}。
 * 这样"谁能写"由拦截器统一保证，业务代码不可能忘记校验登录态。</p>
 *
 * <p><b>限流（2026-10-01 安全整改）</b>：发表评论按<b>用户</b>维度限流
 * （§8.7 原文「评论 20/h」）。此前的状态是"发帖有限流、发评论没有"——
 * 发帖要过验证码+敏感词+双重限流，评论却能被登录用户脚本无限刷，
 * 刷的直接受害者是通知表（每条评论/回复都写一条通知）。</p>
 */
@RestController
@Tag(name = "评论", description = "主楼列表（含前 2 条楼中楼预览）/ 楼中楼 / 发表 / 删除 / 点赞")
public class CommentController {

    /** 限流动作标识：发表评论（用户维度，1 小时窗口）。 */
    private static final String RL_COMMENT_USER_1H = "comment-user-1h";

    private final CommentService commentService;
    private final RateLimiter rateLimiter;
    private final RateLimitProperties rateLimitProperties;

    public CommentController(CommentService commentService,
                             RateLimiter rateLimiter,
                             RateLimitProperties rateLimitProperties) {
        this.commentService = commentService;
        this.rateLimiter = rateLimiter;
        this.rateLimitProperties = rateLimitProperties;
    }

    /**
     * 主楼分页列表，每条带前 2 条楼中楼预览（§6.6）。
     *
     * <p>路径写在这里而不是 {@code PostController}：那个类归 {@code post} 包，
     * M4 无权改；而契约里的路径是 {@code /api/posts/{id}/comments}，
     * 用类级 {@code @RequestMapping("/api/posts/{id}/comments")} 声明即可，
     * 不产生与 {@code PostController} 的任何耦合。</p>
     */
    @GetMapping("/api/posts/{id}/comments")
    @AllowAnonymous
    @Operation(summary = "主楼评论列表",
            description = "分页；每条主楼带 replyCount（总数）与前 2 条楼中楼预览 replies")
    public ApiResponse<PageResult<CommentItemVO>> listByPost(
            @PathVariable long id,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        return ApiResponse.ok(commentService.listRootComments(id, page, size));
    }

    /**
     * 某主楼下的全部楼中楼，分页（§6.6）。
     *
     * <p>契约特意说明 {@code rootId} 指的是<b>主楼评论 id</b>。传楼中楼的 id 会得到
     * 404 而不是空列表 —— 空列表会让调用方无法区分"没有回复"与"传错了 id"。</p>
     */
    @GetMapping("/api/comments/{rootId}/replies")
    @AllowAnonymous
    @Operation(summary = "楼中楼列表", description = "rootId 为主楼评论 id；按时间升序分页")
    public ApiResponse<PageResult<CommentReplyVO>> listReplies(
            @PathVariable long rootId,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        return ApiResponse.ok(commentService.listReplies(rootId, page, size));
    }

    /**
     * 发表评论或回复（§6.6）。
     *
     * <p>{@code parentId=0} 是主楼；指向楼中楼时服务端<b>归并到它所属的主楼</b>
     * （P1-3 归并语义）—— 这不是容错，而是契约要求的行为。</p>
     */
    @PostMapping("/api/comments")
    @Operation(summary = "发表评论",
            description = "parentId=0 为主楼；回复楼中楼时归并到其主楼（parent_id 恒等于 root_id）；用户限流 20 条/小时")
    public ApiResponse<CommentReplyVO> create(@Valid @RequestBody CommentCreateRequest request) {
        long userId = CurrentUser.requireId();
        RateLimiter.Decision decision = rateLimiter.check(
                RL_COMMENT_USER_1H, String.valueOf(userId),
                rateLimitProperties.commentPerHour(), Duration.ofHours(1));
        if (!decision.allowed()) {
            // 与登录限流同一形态：HTTP 429 + Retry-After 头（GlobalExceptionHandler 统一写头）。
            // 限流放在参数校验之后、业务校验之前：刷子不该消耗"加载被回复评论"那几次查询。
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS,
                    ErrorCode.TOO_MANY_REQUESTS.message() + "，评论太频繁，请 " + decision.retryAfter() + " 秒后再试",
                    decision.retryAfter());
        }
        return ApiResponse.ok(commentService.create(userId, request));
    }

    /** 删除评论（§6.6）：仅作者，逻辑删除；重复删除幂等成功。 */
    @DeleteMapping("/api/comments/{id}")
    @Operation(summary = "删除评论", description = "仅作者本人；逻辑删除；重复删除幂等成功（不重复扣计数）")
    public ApiResponse<Void> delete(@PathVariable long id) {
        commentService.delete(CurrentUser.requireId(), id);
        return ApiResponse.ok();
    }

    /** 点赞评论（§6.6）：幂等。 */
    @PostMapping("/api/comments/{id}/like")
    @Operation(summary = "点赞评论", description = "幂等：重复点赞返回成功且计数不重复增加")
    public ApiResponse<Void> like(@PathVariable long id) {
        commentService.likeComment(CurrentUser.requireId(), id);
        return ApiResponse.ok();
    }

    /** 取消点赞评论（§6.6）：幂等，且计数不会减到负数。 */
    @DeleteMapping("/api/comments/{id}/like")
    @Operation(summary = "取消点赞评论", description = "幂等：重复取消返回成功且计数不为负")
    public ApiResponse<Void> unlike(@PathVariable long id) {
        commentService.unlikeComment(CurrentUser.requireId(), id);
        return ApiResponse.ok();
    }
}
