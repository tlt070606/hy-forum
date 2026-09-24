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

import java.util.List;

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

    /**
     * CR-R：操作后要把**当前计数**读回来给前端。
     *
     * <p>直接在接入层读 {@code domain} 的 Mapper 是允许的（铁律 3 只约束业务包之间；
     * {@code domain} 是共享层）。这里刻意<b>不</b>去改 {@code InteractionService} 的职责 ——
     * 它的职责是"改动关系与计数"，"把当前状态读出来"是展示层的事。</p>
     */
    private final com.hyforum.domain.post.mapper.PostMapper postMapper;

    public PostInteractionController(InteractionService interactionService,
                                     com.hyforum.domain.post.mapper.PostMapper postMapper) {
        this.interactionService = interactionService;
        this.postMapper = postMapper;
    }

    /**
     * 读回操作后的互动状态（CR-R）。
     *
     * @param userId 当前登录用户（状态是"相对于请求者"的）
     * @param postId 帖子 id
     */
    private com.hyforum.interaction.vo.InteractionStateVO stateOf(long userId, long postId) {
        com.hyforum.domain.post.entity.Post post = postMapper.selectById(postId);
        boolean liked = interactionService.likedPostIds(userId, List.of(postId)).contains(postId);
        boolean collected = interactionService.collectedPostIds(userId, List.of(postId)).contains(postId);
        return new com.hyforum.interaction.vo.InteractionStateVO(
                liked, collected,
                post == null || post.getLikeCount() == null ? 0 : post.getLikeCount(),
                post == null || post.getCollectCount() == null ? 0 : post.getCollectCount());
    }

    /** 点赞（§6.5）：幂等。 */
    @PostMapping("/api/posts/{id}/like")
    @Operation(summary = "点赞帖子", description = "幂等：重复点赞返回成功，post.like_count 只加一次")
    public ApiResponse<com.hyforum.interaction.vo.InteractionStateVO> like(@PathVariable long id) {
        long userId = CurrentUser.requireId();
        interactionService.likePost(userId, id);
        // CR-R：返回操作后的状态与计数，前端据此直接更新按钮，不必再查一次
        return ApiResponse.ok(stateOf(userId, id));
    }

    /** 取消点赞（§6.5）：幂等，计数不为负。 */
    @DeleteMapping("/api/posts/{id}/like")
    @Operation(summary = "取消点赞", description = "幂等：重复取消返回成功，计数不会减到负数")
    public ApiResponse<com.hyforum.interaction.vo.InteractionStateVO> unlike(@PathVariable long id) {
        long userId = CurrentUser.requireId();
        interactionService.unlikePost(userId, id);
        // CR-R 顺带：取消也返回状态 —— 否则"取消"后前端仍要自己猜按钮状态
        return ApiResponse.ok(stateOf(userId, id));
    }

    /** 收藏（§6.5）：幂等。 */
    @PostMapping("/api/posts/{id}/collect")
    @Operation(summary = "收藏帖子", description = "幂等：重复收藏返回成功，post.collect_count 只加一次")
    public ApiResponse<com.hyforum.interaction.vo.InteractionStateVO> collect(@PathVariable long id) {
        long userId = CurrentUser.requireId();
        interactionService.collectPost(userId, id);
        // CR-R：同上
        return ApiResponse.ok(stateOf(userId, id));
    }

    /** 取消收藏（§6.5）：幂等，计数不为负。 */
    @DeleteMapping("/api/posts/{id}/collect")
    @Operation(summary = "取消收藏", description = "幂等：重复取消返回成功，计数不会减到负数")
    public ApiResponse<com.hyforum.interaction.vo.InteractionStateVO> uncollect(@PathVariable long id) {
        long userId = CurrentUser.requireId();
        interactionService.uncollectPost(userId, id);
        // CR-R 顺带：同上
        return ApiResponse.ok(stateOf(userId, id));
    }
}
