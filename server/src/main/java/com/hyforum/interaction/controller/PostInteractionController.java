package com.hyforum.interaction.controller;

import com.hyforum.common.api.ApiResponse;
import com.hyforum.common.security.CurrentUser;
import com.hyforum.interaction.service.InteractionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 帖子点赞与收藏接口（docs/技术方案.md §6.5、§8.1）。
 *
 * <table>
 *   <caption>端点（全部需要登录）</caption>
 *   <tr><th>方法</th><th>路径</th></tr>
 *   <tr><td>POST</td><td>/api/posts/{id}/like</td></tr>
 *   <tr><td>DELETE</td><td>/api/posts/{id}/like</td></tr>
 *   <tr><td>POST</td><td>/api/posts/{id}/collect</td></tr>
 *   <tr><td>DELETE</td><td>/api/posts/{id}/collect</td></tr>
 * </table>
 *
 * <p>四个端点<b>全部幂等</b>（§8.1）：重复点赞/收藏返回成功，重复取消同样返回成功。
 * 幂等不等于"什么都不做就走"—— 计数只在关系行真的发生变化时才增减，
 * 这一条由 {@link InteractionService} 用唯一索引与影响行数保证。</p>
 */
@RestController
@Tag(name = "帖子互动", description = "点赞 / 取消点赞 / 收藏 / 取消收藏（均幂等）")
public class PostInteractionController {

    private final InteractionService interactionService;

    public PostInteractionController(InteractionService interactionService) {
        this.interactionService = interactionService;
    }

    /** 点赞（§6.5）：幂等。 */
    @PostMapping("/api/posts/{id}/like")
    @Operation(summary = "点赞帖子", description = "幂等：重复点赞返回成功，post.like_count 只加一次")
    public ApiResponse<Void> like(@PathVariable long id) {
        interactionService.likePost(CurrentUser.requireId(), id);
        return ApiResponse.ok();
    }

    /** 取消点赞（§6.5）：幂等，计数不为负。 */
    @DeleteMapping("/api/posts/{id}/like")
    @Operation(summary = "取消点赞", description = "幂等：重复取消返回成功，计数不会减到负数")
    public ApiResponse<Void> unlike(@PathVariable long id) {
        interactionService.unlikePost(CurrentUser.requireId(), id);
        return ApiResponse.ok();
    }

    /** 收藏（§6.5）：幂等。 */
    @PostMapping("/api/posts/{id}/collect")
    @Operation(summary = "收藏帖子", description = "幂等：重复收藏返回成功，post.collect_count 只加一次")
    public ApiResponse<Void> collect(@PathVariable long id) {
        interactionService.collectPost(CurrentUser.requireId(), id);
        return ApiResponse.ok();
    }

    /** 取消收藏（§6.5）：幂等，计数不为负。 */
    @DeleteMapping("/api/posts/{id}/collect")
    @Operation(summary = "取消收藏", description = "幂等：重复取消返回成功，计数不会减到负数")
    public ApiResponse<Void> uncollect(@PathVariable long id) {
        interactionService.uncollectPost(CurrentUser.requireId(), id);
        return ApiResponse.ok();
    }
}
