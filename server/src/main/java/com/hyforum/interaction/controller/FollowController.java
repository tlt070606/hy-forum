package com.hyforum.interaction.controller;

import com.hyforum.common.api.ApiResponse;
import com.hyforum.common.api.PageResult;
import com.hyforum.common.security.CurrentUser;
import com.hyforum.interaction.service.FollowService;
import com.hyforum.interaction.vo.FollowUserVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 关注接口（docs/技术方案.md §6.7）。
 *
 * <table>
 *   <caption>端点</caption>
 *   <tr><th>方法</th><th>路径</th><th>鉴权</th></tr>
 *   <tr><td>POST</td><td>/api/follow/{userId}</td><td>是</td></tr>
 *   <tr><td>DELETE</td><td>/api/follow/{userId}</td><td>是</td></tr>
 *   <tr><td>GET</td><td>/api/users/{id}/follows</td><td>否</td></tr>
 *   <tr><td>GET</td><td>/api/users/{id}/fans</td><td>否</td></tr>
 * </table>
 *
 * <p>两个列表接口是公开的（§6.7 鉴权列写"否"）：关注关系在论坛里本来就是公开信息，
 * 而"谁关注了我"若是私有，前端就要为登录/未登录写两套渲染。</p>
 */
@RestController
@Tag(name = "关注", description = "关注 / 取消关注 / 关注列表 / 粉丝列表")
public class FollowController {

    private final FollowService followService;

    public FollowController(FollowService followService) {
        this.followService = followService;
    }

    /** 关注（§6.7）：幂等；禁止关注自己（400）。 */
    @PostMapping("/api/follow/{userId}")
    @Operation(summary = "关注用户", description = "幂等：重复关注返回成功，双方计数各只加一次；不能关注自己（400）")
    public ApiResponse<Void> follow(@PathVariable long userId) {
        followService.follow(CurrentUser.requireId(), userId);
        return ApiResponse.ok();
    }

    /** 取消关注（§6.7）：幂等。 */
    @DeleteMapping("/api/follow/{userId}")
    @Operation(summary = "取消关注", description = "幂等：重复取消返回成功，计数不会减到负数")
    public ApiResponse<Void> unfollow(@PathVariable long userId) {
        followService.unfollow(CurrentUser.requireId(), userId);
        return ApiResponse.ok();
    }

    /** 他关注的人（§6.7）。 */
    @GetMapping("/api/users/{id}/follows")
    @com.hyforum.common.security.AllowAnonymous
    @Operation(summary = "某用户关注的人", description = "按关注时间倒序分页")
    public ApiResponse<PageResult<FollowUserVO>> listFollowing(
            @PathVariable long id,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        return ApiResponse.ok(followService.listFollowing(id, page, size));
    }

    /** 关注他的人（§6.7）。 */
    @GetMapping("/api/users/{id}/fans")
    @com.hyforum.common.security.AllowAnonymous
    @Operation(summary = "某用户的粉丝", description = "按关注时间倒序分页")
    public ApiResponse<PageResult<FollowUserVO>> listFans(
            @PathVariable long id,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        return ApiResponse.ok(followService.listFans(id, page, size));
    }
}
