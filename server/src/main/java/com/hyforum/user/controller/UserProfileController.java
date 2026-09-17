package com.hyforum.user.controller;

import com.hyforum.common.api.ApiResponse;
import com.hyforum.common.api.PageResult;
import com.hyforum.common.security.AllowAnonymous;
import com.hyforum.common.security.CurrentUser;
import com.hyforum.common.security.OptionalLogin;
import com.hyforum.interaction.service.InteractionService;
import com.hyforum.interaction.vo.CollectionItemVO;
import com.hyforum.interaction.vo.FeedItemVO;
import com.hyforum.user.service.UserService;
import com.hyforum.user.vo.UserCommentVO;
import com.hyforum.user.vo.UserProfileVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户主页接口（docs/技术方案.md §6.3）。
 *
 * <table>
 *   <caption>端点</caption>
 *   <tr><th>方法</th><th>路径</th><th>鉴权</th></tr>
 *   <tr><td>GET</td><td>/api/users/{id}</td><td><b>可选</b>（{@code @OptionalLogin}）</td></tr>
 *   <tr><td>GET</td><td>/api/users/{id}/posts</td><td>否</td></tr>
 *   <tr><td>GET</td><td>/api/users/{id}/comments</td><td>否</td></tr>
 *   <tr><td>GET</td><td>/api/user/collections</td><td>是</td></tr>
 * </table>
 *
 * <h2>⚠️ 为什么不叫 {@code UserController}（实测踩到的坑，后来者必读）</h2>
 * <p>本类第二版曾命名为 {@code UserController}，结果<b>整个 Spring 上下文起不来</b>：</p>
 * <pre>
 * ConflictingBeanDefinitionException: Annotation-specified bean name 'userController'
 * for bean class [com.hyforum.user.controller.UserController] conflicts with existing,
 * non-compatible bean definition of same name and class
 * [com.hyforum.auth.controller.UserController]
 * </pre>
 * <p>原因是 Spring 的默认 bean 名取自<b>类的简单名</b>，与包名无关 ——
 * 而 M1 已有一个 {@code com.hyforum.auth.controller.UserController}（{@code /api/user/me}）。
 * 两个同简单名的类在同一个组件扫描下必然撞名，<b>与被测代码毫无关系</b>，
 * 但表现是"23 个用例全部 IllegalStateException: Failed to load ApplicationContext"，
 * 看上去像基建坏了。因此本类改名为 {@code UserProfileController}
 * （语义也更准：它管的是"别人的主页"，{@code /api/user/me} 才是认证域）。</p>
 *
 * <p>{@code /api/user/collections} 的实现在 {@code interaction} 包（收藏关系归它），
 * 这里只是把契约 §6.3 的路径拼进来 —— 而它与 {@code auth.UserController} 的
 * 类级前缀 {@code /api/user} 并不冲突：两个 {@code @GetMapping} 的<b>完整路径</b>
 * 不同（{@code /me} vs {@code /collections}）。</p>
 *
 * <p><b>为什么 {@code /api/users/{id}} 用 {@code @OptionalLogin} 而不是
 * {@code @AllowAnonymous}</b>：契约要求返回"我是否已关注 / 他是否已关注我"，
 * 这两个答案<b>因人而异</b>。用匿名放行 + 方法内自己解析 token 会让
 * "取不到就当 false"成为一个必然出现的分支（H13 记录的正是这个形状），
 * 于是"未登录"与"没关注"被压成同一个响应，前端再也分不清该弹登录框还是显示关注按钮。</p>
 */
@RestController
@Tag(name = "用户主页", description = "个人主页 / 他的帖子 / 他的评论 / 我的收藏")
public class UserProfileController {

    private final UserService userService;
    private final InteractionService interactionService;

    public UserProfileController(UserService userService, InteractionService interactionService) {
        this.userService = userService;
        this.interactionService = interactionService;
    }

    /**
     * 个人主页（§6.3）：含双向关注状态（未登录时两个标志为 {@code null}）。
     */
    @GetMapping("/api/users/{id}")
    @OptionalLogin
    @Operation(summary = "个人主页",
            description = "可选鉴权：登录后返回 isFollowing / isFollowedBy，未登录时两者为 null（不是 false）")
    public ApiResponse<UserProfileVO> getUser(@PathVariable long id) {
        return ApiResponse.ok(userService.getProfile(id, CurrentUser.idOrNull()));
    }

    /** 该用户的帖子列表（§6.3）：只含正常可见的帖子，时间倒序。 */
    @GetMapping("/api/users/{id}/posts")
    @AllowAnonymous
    @Operation(summary = "某用户的帖子", description = "只返回 status=1（正常）的帖子，时间倒序分页")
    public ApiResponse<PageResult<FeedItemVO>> listPosts(
            @PathVariable long id,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        return ApiResponse.ok(userService.listUserPosts(id, page, size));
    }

    /** 该用户的评论列表（§6.3）：只含正常状态的评论，带所属帖子标题。 */
    @GetMapping("/api/users/{id}/comments")
    @AllowAnonymous
    @Operation(summary = "某用户的评论", description = "只返回正常状态的评论，带 postTitle；时间倒序分页")
    public ApiResponse<PageResult<UserCommentVO>> listComments(
            @PathVariable long id,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        return ApiResponse.ok(userService.listUserComments(id, page, size));
    }

    /**
     * 我的收藏列表（§6.3）：按收藏时间倒序。
     *
     * <p>注意路径是 {@code /api/user/collections}（单数 user），与上面的
     * {@code /api/users/**} 不是同一个前缀 —— 前者是"我的"，后者是"某人的"。
     * 这是契约的写法，不要"顺手统一"。</p>
     */
    @GetMapping("/api/user/collections")
    @Operation(summary = "我的收藏", description = "需登录；按收藏时间倒序分页")
    public ApiResponse<PageResult<CollectionItemVO>> listMyCollections(
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        return ApiResponse.ok(interactionService.listMyCollections(CurrentUser.requireId(), page, size));
    }
}
