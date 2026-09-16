package com.hyforum.post.controller;

import com.hyforum.common.api.ApiResponse;
import com.hyforum.common.api.PageResult;
import com.hyforum.common.security.AllowAnonymous;
import com.hyforum.common.security.CurrentUser;
import com.hyforum.common.security.StpUserUtil;
import com.hyforum.post.dto.PostCreateRequest;
import com.hyforum.post.dto.PostUpdateRequest;
import com.hyforum.post.service.PostService;
import com.hyforum.post.vo.PostDetailVO;
import com.hyforum.post.vo.PostSummaryVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 帖子接口（docs/技术方案.md §6.5）。
 *
 * <table>
 *   <caption>端点</caption>
 *   <tr><th>方法</th><th>路径</th><th>鉴权</th></tr>
 *   <tr><td>GET</td><td>/api/posts</td><td>否</td></tr>
 *   <tr><td>GET</td><td>/api/posts/search</td><td>否</td></tr>
 *   <tr><td>GET</td><td>/api/posts/{id}</td><td><b>可选</b></td></tr>
 *   <tr><td>POST</td><td>/api/posts</td><td>是</td></tr>
 *   <tr><td>PUT</td><td>/api/posts/{id}</td><td>是</td></tr>
 *   <tr><td>DELETE</td><td>/api/posts/{id}</td><td>是</td></tr>
 * </table>
 *
 * <h2>「可选鉴权」是怎么实现的（以及为什么必须这样写）</h2>
 * <p>{@code AuthInterceptor} 只区分两种 handler：被 {@code @AllowAnonymous} 标注的（直接放行，
 * 不解析登录态）与其余的（必须登录，否则 401）。它<b>没有</b>"有 token 就解析、没有就放行"的第三种模式，
 * 而该拦截器位于 {@code com.hyforum.common.web}（M1 交付物，不在 M3 的写权内）。</p>
 * <p>因此"可选鉴权"的做法是：端点标 {@code @AllowAnonymous}（让人人能访问），
 * 需要知道"谁在看"时（例如作者要能看到自己的待审帖）在这里显式调一次
 * {@link StpUserUtil#currentUserId()} —— 未登录/无效 token 返回 {@code null}，不会抛异常。</p>
 * <p><b>这是对 {@code CurrentUser} 那条纪律的一处刻意例外，如实登记</b>：
 * {@code CurrentUser} 的设计意图是"当前登录人只由拦截器写入、业务只从它读"，
 * 以免横切关注点散落。可选鉴权场景下拦截器不会写入它，只能由端点自己解析。
 * 例外范围只有<b>三个只读端点</b>（详情、列表、搜索；列表与搜索当前不需要 viewer，
 * 详情需要），且都收敛在本类的私有方法里，没有扩散到 Service。
 * 若 L1 希望消除这处例外，正确做法是给 {@code AuthInterceptor} 增加"可选登录"注解支持 ——
 * 那要改 {@code common/**}，属于 M3 写权之外。</p>
 *
 * <h2>写接口为什么不标 {@code @AllowAnonymous}</h2>
 * <p>发帖/改帖/删帖都必须登录，走拦截器 → {@code CurrentUser.requireId()} 取当前用户。
 * 这样"谁能写"这件事由拦截器统一保证，业务代码不可能忘记校验登录态。</p>
 */
@RestController
@RequestMapping("/api/posts")
@Tag(name = "帖子", description = "帖子列表 / 详情 / 发帖 / 改帖 / 删帖 / 搜索")
public class PostController {

    private final PostService postService;

    public PostController(PostService postService) {
        this.postService = postService;
    }

    /**
     * 帖子列表（§6.5）：{@code boardId}、{@code sort=latest|hot|essence}、{@code page}、{@code size}。
     *
     * <p>分页硬上限 20 由 {@code PageResult.normalizeSize} 统一收敛（deployment §5），
     * 并在 MyBatis-Plus 的 {@code maxLimit} 再兜一道 —— 任何一处漏判都不会真的打到数据库。</p>
     */
    @GetMapping
    @AllowAnonymous
    @Operation(summary = "帖子列表", description = "可按版块筛选；sort 支持 latest/hot/essence；每页上限 20，只返回摘要（不含正文）")
    public ApiResponse<PageResult<PostSummaryVO>> listPosts(
            @RequestParam(required = false) Long boardId,
            @RequestParam(required = false, defaultValue = "latest") String sort,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        return ApiResponse.ok(postService.listPosts(boardId, sort, page, size));
    }

    /**
     * 搜索标题与正文（§6.5）。
     *
     * <p>路径 {@code /api/posts/search} 与 {@code /api/posts/{id}} 不冲突：
     * Spring 的路径匹配<b>优先精确字面量</b>，{@code search} 不会落到 {@code {id}} 上。</p>
     */
    @GetMapping("/search")
    @AllowAnonymous
    @Operation(summary = "搜索帖子", description = "参数 keyword，检索标题与正文；只返回正常状态的帖子")
    public ApiResponse<PageResult<PostSummaryVO>> searchPosts(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        return ApiResponse.ok(postService.searchPosts(keyword, page, size));
    }

    /**
     * 帖子详情（§6.5）：浏览量 +1 走 Redis（§8.3）。
     *
     * <p>待审帖只有作者本人看得到；其他人（含未登录）得到 404 而不是 403，
     * 以免泄露"这个 id 存在"这一事实。</p>
     */
    @GetMapping("/{id}")
    @AllowAnonymous
    @Operation(summary = "帖子详情", description = "可选鉴权：作者可以看到自己的待审帖；浏览量 +1 走 Redis")
    public ApiResponse<PostDetailVO> getPost(@PathVariable long id) {
        return ApiResponse.ok(postService.getDetail(id, viewerIdOrNull()));
    }

    /** 发帖（§6.5）：资源版块需 diskType/diskUrl；命中敏感词 → 待审（先发后审）。 */
    @PostMapping
    @Operation(summary = "发帖", description = "资源版块必须提供 diskType 与 diskUrl；未命中敏感词直接可见，命中则进待审队列")
    public ApiResponse<PostDetailVO> createPost(@Valid @RequestBody PostCreateRequest request) {
        return ApiResponse.ok(postService.create(CurrentUser.requireId(), request));
    }

    /** 改帖（§6.5）：仅作者、限发布后 30 分钟内；内容变更则回到待审。 */
    @PutMapping("/{id}")
    @Operation(summary = "改帖", description = "仅作者本人，且限发布后 30 分钟内；任何内容变更都会让帖子回到待审（status=0）")
    public ApiResponse<PostDetailVO> updatePost(@PathVariable long id,
                                               @Valid @RequestBody PostUpdateRequest request) {
        return ApiResponse.ok(postService.update(CurrentUser.requireId(), id, request));
    }

    /** 删帖（§6.5）：仅作者，逻辑删除（管理端删除入口见 §6.11 的 /api/admin/posts）。 */
    @DeleteMapping("/{id}")
    @Operation(summary = "删帖", description = "仅作者本人；逻辑删除（is_deleted=1）")
    public ApiResponse<Void> deletePost(@PathVariable long id) {
        postService.delete(CurrentUser.requireId(), id);
        return ApiResponse.ok();
    }

    /**
     * 可选鉴权：解析当前登录用户 id，未登录返回 {@code null}。
     *
     * <p>见类注释"「可选鉴权」是怎么实现的"。刻意收在这一个私有方法里，
     * 让这处例外只有一处、可被搜索到。</p>
     */
    private Long viewerIdOrNull() {
        return StpUserUtil.currentUserId();
    }
}
