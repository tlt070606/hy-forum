package com.hyforum.post.controller;

import com.hyforum.common.api.ApiResponse;
import com.hyforum.common.api.PageResult;
import com.hyforum.common.security.CurrentUser;
import com.hyforum.common.security.OptionalLogin;
import com.hyforum.post.dto.PostCreateRequest;
import com.hyforum.post.dto.PostUpdateRequest;
import com.hyforum.post.service.PostService;
import com.hyforum.post.service.PostViewerStateResolver;
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

import java.util.List;

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
 * <h2>「可选鉴权」是怎么实现的（v2：M4 §5.4 的 {@code @OptionalLogin} 收敛之后）</h2>
 * <p>本类原先是"标 {@code @AllowAnonymous} + 方法内自己调 {@code StpUserUtil.currentUserId()}"
 * —— 那是 H13 登记的例外，问题是"取不到就当 0"这类静默错法有了出现的形状。
 * M4 按 §5.4 在 {@code common/web} 收敛出 {@link OptionalLogin} 之后，
 * 本类改用 {@link OptionalLogin} + {@link CurrentUser#idOrNull()}：
 * 拦截器解析一次登录态，"取不到"就<b>确定地</b>是 {@code null}，业务侧不再碰 token。</p>
 *
 * <p><b>三条只读端点（列表／搜索／详情）必须保持"未登录也能访问"</b>：
 * CR-K 的契约要求"未登录时 {@code liked=false} 且 <b>HTTP 200</b>（不是 401）"，
 * 因此它们只能标 {@link OptionalLogin}，<b>不能</b>改成需要登录 ——
 * 否则前端未登录连列表都打不开。这也让搜索可以带上"我是否点过赞"（同一个口径）。</p>
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

    /**
     * 请求者视角字段（{@code liked}/{@code collected}/{@code imageThumbs}）的装配器。
     *
     * <p><b>为什么它住在 {@code post} 包、而不是复用 interaction 的实现</b>：
     * 第一版我注入的是 interaction 的两个 Service，<b>被 ArchUnit 判违规 9 处</b> ——
     * 铁律 3 是"按包判"的，<b>即使依赖发生在 controller 层也一样违规</b>
     * （我一开始以为"接入层调用两个 Service 不算模块依赖"，那是错的）。
     * 正确做法是读 {@code domain} 的实体与 Mapper（共享层，允许读）——
     * 违规原文与取舍见 {@link com.hyforum.post.service.PostViewerStateResolver} 的类注释。</p>
     */
    private final PostViewerStateResolver viewerState;

    public PostController(PostService postService, PostViewerStateResolver viewerState) {
        this.postService = postService;
        this.viewerState = viewerState;
    }

    /**
     * 帖子列表（§6.5）：{@code boardId}、{@code sort=latest|hot|essence}、{@code page}、{@code size}。
     *
     * <p>分页硬上限 20 由 {@code PageResult.normalizeSize} 统一收敛（deployment §5），
     * 并在 MyBatis-Plus 的 {@code maxLimit} 再兜一道 —— 任何一处漏判都不会真的打到数据库。</p>
     */
    @GetMapping
    @OptionalLogin
    @Operation(summary = "帖子列表",
            description = "可按版块筛选；sort 支持 latest/hot/essence；每页上限 20，只返回摘要（不含正文）。"
                    + "可选鉴权：登录后 liked/collected 反映请求者自己的状态，未登录恒为 false 且仍返回 200")
    public ApiResponse<PageResult<PostSummaryVO>> listPosts(
            @RequestParam(required = false) Long boardId,
            @RequestParam(required = false, defaultValue = "latest") String sort,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        return ApiResponse.ok(withViewerState(postService.listPosts(boardId, sort, page, size)));
    }

    /**
     * 搜索标题与正文（§6.5）。
     *
     * <p>路径 {@code /api/posts/search} 与 {@code /api/posts/{id}} 不冲突：
     * Spring 的路径匹配<b>优先精确字面量</b>，{@code search} 不会落到 {@code {id}} 上。</p>
     *
     * <p>搜索结果同样带"请求者视角字段"：搜索与列表在契约里是同一形状，
     * 若只给列表填，前端就得为两个入口写两套渲染 —— 而"搜索结果里的帖子显示未点赞"
     * 又是一种界面在说谎。</p>
     */
    @GetMapping("/search")
    @OptionalLogin
    @Operation(summary = "搜索帖子", description = "参数 keyword，检索标题与正文；只返回正常状态的帖子")
    public ApiResponse<PageResult<PostSummaryVO>> searchPosts(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        return ApiResponse.ok(withViewerState(postService.searchPosts(keyword, page, size)));
    }

    /**
     * 帖子详情（§6.5）：浏览量 +1 走 Redis（§8.3）。
     *
     * <p>待审帖只有作者本人看得到；其他人（含未登录）得到 404 而不是 403，
     * 以免泄露"这个 id 存在"这一事实。</p>
     */
    @GetMapping("/{id}")
    @OptionalLogin
    @Operation(summary = "帖子详情",
            description = "可选鉴权：作者可以看到自己的待审帖；浏览量 +1 走 Redis；"
                    + "liked/collected 反映请求者自己的状态（未登录恒为 false 且仍返回 200）")
    public ApiResponse<PostDetailVO> getPost(@PathVariable long id) {
        Long viewerId = CurrentUser.idOrNull();
        PostDetailVO detail = postService.getDetail(id, viewerId);
        return ApiResponse.ok(withViewerState(detail, viewerId));
    }

    /** 发帖（§6.5）：资源版块需 diskType/diskUrl；命中敏感词 → 待审（先发后审）。 */
    @PostMapping
    @Operation(summary = "发帖", description = "资源版块必须提供 diskType 与 diskUrl；未命中敏感词直接可见，命中则进待审队列")
    public ApiResponse<PostDetailVO> createPost(@Valid @RequestBody PostCreateRequest request) {
        Long userId = CurrentUser.requireId();
        // 刚发的帖子：作者本人当然"未点赞/未收藏"（同一个人不可能在发帖的同一次请求里点过），
        // 因此这里无需查询关系表 —— 直接给 false 是**确定的事实**，不是省事
        return ApiResponse.ok(withViewerState(postService.create(userId, request), userId));
    }

    /** 改帖（§6.5）：仅作者、限发布后 30 分钟内；内容变更则回到待审。 */
    @PutMapping("/{id}")
    @Operation(summary = "改帖", description = "仅作者本人，且限发布后 30 分钟内；任何内容变更都会让帖子回到待审（status=0）")
    public ApiResponse<PostDetailVO> updatePost(@PathVariable long id,
                                               @Valid @RequestBody PostUpdateRequest request) {
        Long userId = CurrentUser.requireId();
        // 注意：这里**必须真的查**关系表 —— 作者完全可能给自己的帖子点过赞/收过藏
        return ApiResponse.ok(withViewerState(postService.update(userId, id, request), userId));
    }

    /** 删帖（§6.5）：仅作者，逻辑删除（管理端删除入口见 §6.11 的 /api/admin/posts）。 */
    @DeleteMapping("/{id}")
    @Operation(summary = "删帖", description = "仅作者本人；逻辑删除（is_deleted=1）")
    public ApiResponse<Void> deletePost(@PathVariable long id) {
        postService.delete(CurrentUser.requireId(), id);
        return ApiResponse.ok();
    }

    // ==================================================================
    // 请求者视角字段（CR-K / CR-L）—— 本类是全项目唯一知道"谁在看"的地方
    //
    // "怎么查、查几次、缩略图怎么签"全部委托给 PostViewerStateResolver（同在 post 包）：
    // 它只读 domain 的实体与 Mapper，因此不违反铁律 3。
    // 三个入口（版块列表 / 首页流 / 个人主页）必须是**同一份口径** ——
    // 各写一遍必然"改了俩漏了一个"，而漏掉的那个入口会静默地永远显示未点赞。
    // ==================================================================

    /** 给一页摘要补上 liked / collected / imageThumbs（查询次数与页大小无关）。 */
    private PageResult<PostSummaryVO> withViewerState(PageResult<PostSummaryVO> page) {
        List<PostSummaryVO> items = page.list();
        if (items == null || items.isEmpty()) {
            return page;
        }
        Long viewerId = CurrentUser.idOrNull();
        return new PageResult<>(
                viewerState.enrich(items, viewerId), page.total(), page.page(), page.size());
    }

    /**
     * 详情：只补 {@code liked} / {@code collected}。
     *
     * <p>详情页<b>不发</b> {@code imageThumbs}：它已经有完整的 {@code images}（含签名后的
     * {@code thumbUrl}），再给一份"前 3 张"是纯冗余。</p>
     */
    private PostDetailVO withViewerState(PostDetailVO detail, Long viewerId) {
        return viewerState.enrich(detail, viewerId);
    }
}
