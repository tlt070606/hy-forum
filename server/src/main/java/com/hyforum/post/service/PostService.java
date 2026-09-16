package com.hyforum.post.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hyforum.common.api.ErrorCode;
import com.hyforum.common.api.PageResult;
import com.hyforum.common.audit.SensitiveTextChecker;
import com.hyforum.common.exception.BizException;
import com.hyforum.domain.board.entity.Board;
import com.hyforum.domain.board.mapper.BoardMapper;
import com.hyforum.domain.post.entity.Post;
import com.hyforum.domain.post.entity.PostImage;
import com.hyforum.domain.post.mapper.PostImageMapper;
import com.hyforum.domain.post.mapper.PostMapper;
import com.hyforum.domain.user.entity.User;
import com.hyforum.domain.user.mapper.UserMapper;
import com.hyforum.post.config.PostProperties;
import com.hyforum.post.config.OssProperties;
import com.hyforum.post.disk.DiskFieldNormalizer;
import com.hyforum.post.disk.DiskFields;
import com.hyforum.post.disk.DiskType;
import com.hyforum.post.dto.PostCreateRequest;
import com.hyforum.post.dto.PostUpdateRequest;
import com.hyforum.post.image.ThumbnailUrls;
import com.hyforum.post.vo.PostDetailVO;
import com.hyforum.post.vo.PostImageVO;
import com.hyforum.post.vo.PostSummaryVO;
import com.hyforum.post.vo.UserBriefVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 帖子服务：列表 / 详情 / 发帖 / 改帖 / 删帖 / 搜索（docs/技术方案.md §6.5、§8.2、§8.3、§8.4、§8.6、§5.5）。
 *
 * <h2>本类里几条"看起来可选、其实被契约钉死"的规则</h2>
 * <ol>
 *   <li><b>发帖后的 {@code status}</b>（L1 裁决第 1 条 / §8.6 第 5 条）：
 *       未命中敏感词 → {@code 1}（<b>直接可见</b>）；命中 → {@code 0}（待审、前台不可见）。
 *       写成"一律待审"会让 M3 的验收标准（详情页能看到）永远达不成，
 *       而接口响应照样是 {@code code=0}，从响应体上完全看不出问题。</li>
 *   <li><b>命中敏感词不是拒收</b>：不返回 {@code 2001}，而是收下并置 {@code 0}。
 *       {@code 2001} 是"直接拒收"的语义（M1 用在注册昵称上），
 *       帖子走的是"先发后审"的队列 —— 返回 2001 会把用户刚写的草稿直接丢掉。</li>
 *   <li><b>编辑重审</b>（L1 裁决第 3 条 / §8.6 第 6 条）：只要<b>内容有变更</b>
 *       （标题／正文／图片／网盘字段任一项），{@code status} <b>一律回到 0</b>；
 *       内容没变则保持原状态。前者的反面是"命中敏感词才回 0"，
 *       那是一条真实的审核绕过路径：先发合规内容过审，再改成违规内容。</li>
 *   <li><b>浏览量走 Redis</b>（L1 裁决第 4 条 / §8.3）：见 {@link PostViewCounter}。</li>
 *   <li><b>图片可见性</b>（CR-006）：详情返回图片时<b>只隐藏 {@code audit_status=2}</b>，
 *       不过滤 {@code 0}。若把"默认 0"当成"必须人工放行"，所有带图帖在前台都看不到图。</li>
 * </ol>
 *
 * <h2>关于跨模块依赖</h2>
 * <p>本类用到 {@code BoardMapper}／{@code UserMapper}（都在 {@code com.hyforum.domain}，
 * 铁律 3 明确允许）与 {@code common.audit.SensitiveTextChecker}（接口在 {@code common}）。
 * 刻意<b>不</b>直接依赖 {@code com.hyforum.audit} 的实现类：
 * 虽然 M1 的实现恰好是 {@code InMemorySensitiveTextChecker}，
 * 但按包依赖规则那是另一个业务包，ArchUnit 会把它判成跨模块调用。</p>
 */
@Service
public class PostService {

    private static final Logger log = LoggerFactory.getLogger(PostService.class);

    /** 正文长度上限（与 {@code PostCreateRequest} 的 {@code @Size} 保持一致，防御绕过 DTO 的调用方）。 */
    private static final int MAX_CONTENT_LENGTH = 10000;

    /** 搜索关键词长度上限（防止把整段正文当关键词打满 LIKE）。 */
    private static final int MAX_KEYWORD_LENGTH = 50;

    /** 合法的排序取值（§6.5：{@code sort=latest|hot|essence}）。 */
    private static final String SORT_LATEST = "latest";
    private static final String SORT_HOT = "hot";
    private static final String SORT_ESSENCE = "essence";

    private final PostMapper postMapper;
    private final PostImageMapper postImageMapper;
    private final BoardMapper boardMapper;
    private final UserMapper userMapper;
    private final SensitiveTextChecker sensitiveTextChecker;
    private final PostViewCounter viewCounter;
    private final PostRateLimiter rateLimiter;
    private final PostProperties postProperties;
    private final OssProperties ossProperties;

    public PostService(PostMapper postMapper,
                       PostImageMapper postImageMapper,
                       BoardMapper boardMapper,
                       UserMapper userMapper,
                       SensitiveTextChecker sensitiveTextChecker,
                       PostViewCounter viewCounter,
                       PostRateLimiter rateLimiter,
                       PostProperties postProperties,
                       OssProperties ossProperties) {
        this.postMapper = postMapper;
        this.postImageMapper = postImageMapper;
        this.boardMapper = boardMapper;
        this.userMapper = userMapper;
        this.sensitiveTextChecker = sensitiveTextChecker;
        this.viewCounter = viewCounter;
        this.rateLimiter = rateLimiter;
        this.postProperties = postProperties;
        this.ossProperties = ossProperties;
    }

    // ==================================================================
    // 列表与搜索
    // ==================================================================

    /**
     * 帖子列表（§6.5）：可按版块筛选，支持 {@code latest / hot / essence} 三种排序。
     *
     * <p>只返回 {@code status=1}（正常）的帖子：待审与已屏蔽的帖子不得出现在任何前台列表里，
     * 包括作者自己 —— 作者要看自己的待审帖走详情接口（那里有归属判定）。
     * 把待审帖混进列表会让"待审 = 前台不可见"这条规则无法成立。</p>
     */
    @Transactional(readOnly = true)
    public PageResult<PostSummaryVO> listPosts(Long boardId, String sort, int page, int size) {
        String sortMode = normalizeSort(sort);
        int pageNo = PageResult.normalizePage(page);
        int pageSize = PageResult.normalizeSize(size);

        QueryWrapper<Post> wrapper = Wrappers.query();
        wrapper.eq("status", Post.STATUS_NORMAL);
        if (boardId != null) {
            wrapper.eq("board_id", boardId);
        }
        applySort(wrapper, sortMode);

        Page<Post> pageQuery = new Page<>(pageNo, pageSize);
        IPage<Post> result = postMapper.selectPage(pageQuery, wrapper);
        return PageResult.of(toSummaries(result.getRecords()), result.getTotal(), pageNo, pageSize);
    }

    /**
     * 搜索标题与正文（§6.5：参数 {@code keyword}，检索标题与正文）。
     *
     * <p>检索口径按 §6.5 的说明用 {@code LIKE '%keyword%'}（1000–10000 条规模足够），
     * 该节同时保留了 {@code MATCH ... AGAINST} 的升级路径（表上已有 ngram 全文索引）。</p>
     *
     * <p><b>为什么要转义 LIKE 通配符</b>：用户输入的 {@code %} 或 {@code _} 会被 MySQL 当成通配符，
     * 于是搜一个 {@code %} 就返回全站帖子。这不是"体验问题"——
     * 它让"只搜得到正常帖"这条规则之外又多了一条可以绕开版块/状态筛选的路径。</p>
     */
    @Transactional(readOnly = true)
    public PageResult<PostSummaryVO> searchPosts(String keyword, int page, int size) {
        if (keyword == null || keyword.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "keyword 不能为空");
        }
        String trimmed = keyword.trim();
        if (trimmed.length() > MAX_KEYWORD_LENGTH) {
            throw new BizException(ErrorCode.BAD_REQUEST, "keyword 不能超过 " + MAX_KEYWORD_LENGTH + " 个字符");
        }
        int pageNo = PageResult.normalizePage(page);
        int pageSize = PageResult.normalizeSize(size);

        String pattern = escapeLike(trimmed);
        QueryWrapper<Post> wrapper = Wrappers.query();
        wrapper.eq("status", Post.STATUS_NORMAL);
        wrapper.and(nested -> nested.like("title", pattern).or().like("content", pattern));
        wrapper.orderByDesc("created_at");

        Page<Post> pageQuery = new Page<>(pageNo, pageSize);
        IPage<Post> result = postMapper.selectPage(pageQuery, wrapper);
        return PageResult.of(toSummaries(result.getRecords()), result.getTotal(), pageNo, pageSize);
    }

    /** 排序：{@code latest}（置顶优先 + 时间倒序）/ {@code hot}（互动热度）/ {@code essence}（精华）。 */
    private void applySort(QueryWrapper<Post> wrapper, String sortMode) {
        switch (sortMode) {
            case SORT_HOT ->
                // §8.5 的热门公式。这里的列名是**代码里的常量**，不含任何用户输入，
                // 因此不违反"禁止字符串拼接 SQL"（§9 禁的是把参数拼进 SQL）
                wrapper.orderByDesc("(like_count * 3 + comment_count * 5 + view_count)")
                        .orderByDesc("created_at");
            case SORT_ESSENCE ->
                // 精华：先筛出加精帖，再按时间倒序（§6.5 把它列为一种排序入口，
                // 语义上"精华"必然是"只看精华"，否则它和 latest 的区别只是顺序差异）
                wrapper.eq("is_essence", 1).orderByDesc("created_at");
            default -> wrapper.orderByDesc("is_top").orderByDesc("created_at");
        }
    }

    /** 校验并归一排序参数；非法取值直接 400（静默回落默认值会让前端的"热门"按钮不生效却不报错）。 */
    private String normalizeSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return SORT_LATEST;
        }
        String value = sort.trim().toLowerCase(java.util.Locale.ROOT);
        if (SORT_LATEST.equals(value) || SORT_HOT.equals(value) || SORT_ESSENCE.equals(value)) {
            return value;
        }
        throw new BizException(ErrorCode.BAD_REQUEST, "sort 只支持 latest / hot / essence，收到：" + sort);
    }

    // ==================================================================
    // 详情
    // ==================================================================

    /**
     * 帖子详情（§6.5）：浏览量 +1 走 Redis（§8.3）。
     *
     * <p>可见性规则（契约里的"可选鉴权"落到代码上就是这三行）：</p>
     * <ul>
     *   <li>{@code status=1} → 任何人（含未登录）可见；</li>
     *   <li>{@code status=0} → 只有作者可见，其他一律 404（§8.6 第 2 条：不对普通用户展示）；</li>
     *   <li>{@code status=2}（已屏蔽）→ 谁都看不到，管理端查看走 §6.11 的后台接口（M6）。</li>
     * </ul>
     * <p>待审时对外返回 <b>404 而不是 403</b>：403 等于承认"这个 id 存在但你不能看"，
     * 会把待审内容的存在性泄露出去。</p>
     *
     * @param postId   帖子 id
     * @param viewerId 当前登录用户 id；未登录为 {@code null}（§6.5 该接口鉴权为"可选"）
     */
    @Transactional
    public PostDetailVO getDetail(long postId, Long viewerId) {
        Post post = postMapper.selectById(postId);
        if (post == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "帖子不存在");
        }
        assertVisible(post, viewerId);

        Board board = boardMapper.selectById(post.getBoardId());
        User author = userMapper.selectById(post.getUserId());
        List<PostImage> images = listVisibleImages(postId);
        // 浏览量 +1（走 Redis），并把未回写的增量算进返回值 —— 用户看到的是实时数字
        long pendingDelta = viewCounter.recordAndGetPendingDelta(postId);
        int viewCount = nullToZero(post.getViewCount()) + (int) Math.max(0L, pendingDelta);
        return toDetail(post, board, author, images, viewCount);
    }

    /** 可见性判定（见 {@link #getDetail}）。 */
    private void assertVisible(Post post, Long viewerId) {
        Integer status = post.getStatus();
        if (status != null && status == Post.STATUS_NORMAL) {
            return;
        }
        if (status != null && status == Post.STATUS_PENDING
                && viewerId != null && viewerId.equals(post.getUserId())) {
            return;
        }
        // 待审（非作者）与已屏蔽（所有人）统一按"不存在"处理
        throw new BizException(ErrorCode.NOT_FOUND, "帖子不存在");
    }

    // ==================================================================
    // 发帖
    // ==================================================================

    /**
     * 发帖（§6.5）。
     *
     * <p>顺序：限流 → 版块校验 → 字段归一化与校验 → 敏感词判定 → 落库（同一事务内写
     * {@code post} / {@code post_image} / 冗余计数）。</p>
     *
     * <p><b>限流放在最前面</b>：见 {@link PostRateLimiter} 关于"先限流还是先校验"的取舍说明 ——
     * 校验失败也消耗配额，是为了不给"用必然失败的请求刷接口"留路。</p>
     */
    @Transactional
    public PostDetailVO create(Long userId, PostCreateRequest request) {
        User author = userMapper.selectById(userId);
        if (author == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "登录用户不存在，请重新登录");
        }
        rateLimiter.checkAndCount(author);

        Board board = requireUsableBoard(request.boardId());
        String title = request.title().trim();
        String content = blankToNull(request.content());
        if (content != null && content.length() > MAX_CONTENT_LENGTH) {
            throw new BizException(ErrorCode.BAD_REQUEST, "正文不能超过 " + MAX_CONTENT_LENGTH + " 字");
        }
        DiskFields disk = resolveDiskFields(board, request.diskType(), request.diskUrl(), request.diskCode());
        List<String> imageUrls = resolveImageUrls(request.images());

        boolean sensitive = isSensitive(title, content);
        LocalDateTime now = LocalDateTime.now();

        Post post = new Post();
        post.setBoardId(board.getId());
        post.setUserId(userId);
        post.setTitle(title);
        post.setContent(content);
        post.setImageCount(imageUrls.size());
        post.setCoverUrl(imageUrls.isEmpty() ? null : ThumbnailUrls.derive(imageUrls.get(0)));
        post.setDiskType(disk.diskUrl() == null ? null : request.diskType());
        post.setDiskUrl(disk.diskUrl());
        post.setDiskCode(disk.diskCode());
        post.setViewCount(0);
        post.setLikeCount(0);
        post.setCommentCount(0);
        post.setCollectCount(0);
        post.setReportCount(0);
        post.setIsTop(0);
        post.setIsEssence(0);
        // L1 裁决第 1 条：未命中敏感词 → 直接可见（先发后审）；命中 → 待审
        post.setStatus(sensitive ? Post.STATUS_PENDING : Post.STATUS_NORMAL);
        post.setCreatedAt(now);
        post.setUpdatedAt(now);
        postMapper.insert(post);

        bindImages(post.getId(), imageUrls);

        // 冗余计数在同一事务内维护（§8.2）。用列级增减而不是 SELECT 后回写：
        // 后者在并发下会互相覆盖（两个人同时发帖，计数可能只 +1）
        userMapper.update(null, Wrappers.<User>lambdaUpdate()
                .setSql("post_count = post_count + 1")
                .eq(User::getId, userId));
        boardMapper.update(null, Wrappers.<Board>lambdaUpdate()
                .setSql("post_count = post_count + 1")
                .eq(Board::getId, board.getId()));

        if (sensitive) {
            log.info("帖子命中敏感词，已置为待审：postId={} userId={}（§8.6 第 2 条）", post.getId(), userId);
        }
        return toDetail(post, board, author, listVisibleImages(post.getId()), 0);
    }

    // ==================================================================
    // 改帖
    // ==================================================================

    /**
     * 改帖（§6.5）：仅作者、仅发布后 30 分钟内；任何内容变更都会让 {@code status} 回到 0。
     *
     * <p>三个判定各堵一类问题：</p>
     * <ol>
     *   <li><b>归属</b>：非作者一律 403（越权改别人的帖子是最典型的水平越权）；</li>
     *   <li><b>时间窗</b>：超时 403 —— 帖子已被点赞/评论之后再改内容会让讨论记录失真；</li>
     *   <li><b>内容是否变更</b>：变了才回 0。既堵住"先发合规再改违规"的绕过，
     *       也避免"点一次保存就把自己已放行的帖子打回待审队列"。</li>
     * </ol>
     */
    @Transactional
    public PostDetailVO update(Long userId, long postId, PostUpdateRequest request) {
        Post post = postMapper.selectById(postId);
        if (post == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "帖子不存在");
        }
        if (!Objects.equals(post.getUserId(), userId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "只有作者本人可以编辑自己的帖子");
        }
        if (isEditWindowClosed(post)) {
            throw new BizException(ErrorCode.FORBIDDEN,
                    "帖子发布超过 " + postProperties.editWindowMinutes() + " 分钟，不能再编辑");
        }

        Board board = boardMapper.selectById(post.getBoardId());
        String title = request.title().trim();
        String content = blankToNull(request.content());
        if (content != null && content.length() > MAX_CONTENT_LENGTH) {
            throw new BizException(ErrorCode.BAD_REQUEST, "正文不能超过 " + MAX_CONTENT_LENGTH + " 字");
        }
        DiskFields disk = resolveDiskFields(board, request.diskType(), request.diskUrl(), request.diskCode());
        List<String> newImageUrls = resolveImageUrls(request.images());
        Integer newDiskType = disk.diskUrl() == null ? null : request.diskType();

        List<String> oldImageUrls = listAllImageUrls(postId);
        boolean changed = !Objects.equals(post.getTitle(), title)
                || !Objects.equals(post.getContent(), content)
                || !Objects.equals(post.getDiskUrl(), disk.diskUrl())
                || !Objects.equals(post.getDiskCode(), disk.diskCode())
                || !Objects.equals(post.getDiskType(), newDiskType)
                || !oldImageUrls.equals(newImageUrls);

        // 用 LambdaUpdateWrapper + 显式 set 更新：MyBatis-Plus 的 updateById 默认**跳过 null 字段**，
        // 于是"把正文清空"和"去掉网盘信息"这两类操作会静默失效（用户点了保存，内容还在）。
        // 显式 set 会把 null 真正写进库，这才是 PUT 的覆盖语义。
        LambdaUpdateWrapper<Post> updateWrapper = Wrappers.<Post>lambdaUpdate()
                .eq(Post::getId, postId)
                .set(Post::getTitle, title)
                .set(Post::getContent, content)
                .set(Post::getDiskType, newDiskType)
                .set(Post::getDiskUrl, disk.diskUrl())
                .set(Post::getDiskCode, disk.diskCode())
                .set(Post::getImageCount, newImageUrls.size())
                .set(Post::getCoverUrl, newImageUrls.isEmpty() ? null : ThumbnailUrls.derive(newImageUrls.get(0)))
                .set(Post::getUpdatedAt, LocalDateTime.now());
        if (changed) {
            // L1 裁决第 3 条 / §8.6 第 6 条：内容变更后一律回 0 重审（**不**重新判定敏感词 ——
            // 判定的目标是"编辑后的内容未经审核"这件事本身，而不是"新内容是否命中词库"）
            updateWrapper.set(Post::getStatus, Post.STATUS_PENDING);
        }
        postMapper.update(null, updateWrapper);

        if (!oldImageUrls.equals(newImageUrls)) {
            rebindImages(postId, newImageUrls);
        }

        // 重新读一次：updated_at 由数据库的 ON UPDATE CURRENT_TIMESTAMP 维护，
        // 用内存里的对象拼响应会把旧时间返回给前端
        Post latest = postMapper.selectById(postId);
        User author = userMapper.selectById(latest.getUserId());
        return toDetail(latest, board, author, listVisibleImages(postId),
                nullToZero(latest.getViewCount()) + (int) Math.max(0L, pendingDeltaOf(postId)));
    }

    /** 编辑窗口是否已关闭（发布后 30 分钟）。{@code created_at} 缺失时按"可编辑"处理（宁松不误封）。 */
    private boolean isEditWindowClosed(Post post) {
        LocalDateTime createdAt = post.getCreatedAt();
        if (createdAt == null) {
            return false;
        }
        return createdAt.plusMinutes(postProperties.editWindowMinutes()).isBefore(LocalDateTime.now());
    }

    private long pendingDeltaOf(long postId) {
        // 只读不改：详情接口负责 +1，编辑接口不该把浏览量加一次
        try {
            String value = viewCounter.pendingDelta(postId);
            return value == null ? 0L : Long.parseLong(value);
        } catch (RuntimeException ex) {
            return 0L;
        }
    }

    // ==================================================================
    // 删帖
    // ==================================================================

    /**
     * 删帖（§6.5）：仅作者或管理员，<b>逻辑删除</b>。
     *
     * <p><b>关于"或管理员"</b>：前台接口拿不到管理员身份 ——
     * 前后台是两套独立的登录态（§9 后台隔离），后台 token 打到 {@code /api/posts/{id}}
     * 会被拦截器判成未登录（401）。管理员的删除入口是 §6.11 的
     * {@code DELETE /api/admin/posts/{id}}（M6）。这里的"仅作者"不是简化，
     * 而是"这一层不可能有管理员身份"的必然结果，因此把入口写在错误信息里，避免误解。</p>
     *
     * <p>逻辑删除的理由：M4 的评论/点赞/收藏都以帖子 id 为外键，物理删除会让它们变成悬空数据。</p>
     */
    @Transactional
    public void delete(Long userId, long postId) {
        Post post = postMapper.selectById(postId);
        if (post == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "帖子不存在");
        }
        if (!Objects.equals(post.getUserId(), userId)) {
            throw new BizException(ErrorCode.FORBIDDEN,
                    "只有作者本人可以删除自己的帖子（管理端删除入口为 /api/admin/posts，见技术方案 §6.11）");
        }
        // 逻辑删除：MyBatis-Plus 的 @TableLogic 把它翻译成 UPDATE ... SET is_deleted = 1
        postMapper.deleteById(postId);

        // 冗余计数回退，GREATEST(...,0) 兜底：post_count 是 UNSIGNED，
        // 一旦减到负数 MySQL 会报错，而"计数偏小"远比"删帖报 500"轻
        // （§8.2 留了管理员手动校准任务处理计数漂移）
        userMapper.update(null, Wrappers.<User>lambdaUpdate()
                .setSql("post_count = GREATEST(post_count - 1, 0)")
                .eq(User::getId, post.getUserId()));
        boardMapper.update(null, Wrappers.<Board>lambdaUpdate()
                .setSql("post_count = GREATEST(post_count - 1, 0)")
                .eq(Board::getId, post.getBoardId()));

        // 已逻辑删除的帖子不再展示，浏览量增量无意义；但**不主动删 Redis 键** ——
        // 回写任务会对这个 id 做一次 UPDATE（行仍在，只是 is_deleted=1），不会丢数据也不会报错
        log.info("帖子已逻辑删除：postId={} userId={}", postId, userId);
    }

    // ==================================================================
    // 字段归一化与校验
    // ==================================================================

    /** 取版块并校验可用性：必须存在且启用。 */
    private Board requireUsableBoard(Long boardId) {
        Board board = boardId == null ? null : boardMapper.selectById(boardId);
        if (board == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "版块不存在：" + boardId);
        }
        if (board.getStatus() == null || board.getStatus() != 1) {
            throw new BizException(ErrorCode.BAD_REQUEST, "版块已停用，不能发帖：" + board.getName());
        }
        return board;
    }

    /**
     * 归一化并校验网盘字段（§5.5 / ADR-0008）。
     *
     * <p>三类规则：资源版块必须给 {@code diskType} + {@code diskUrl}；
     * 非资源版块<b>不接受</b>网盘字段（不是"忽略"——静默忽略会让用户以为自己填的链接生效了）；
     * {@code diskCode} 一律可选（阿里云盘/夸克没有提取码机制）。</p>
     */
    private DiskFields resolveDiskFields(Board board, Integer diskType, String diskUrl, String diskCode) {
        boolean resourceBoard = board != null && board.getIsResource() != null && board.getIsResource() == 1;
        boolean anyDiskFieldPresent = diskType != null
                || (diskUrl != null && !diskUrl.isBlank())
                || (diskCode != null && !diskCode.isBlank());

        if (!resourceBoard) {
            if (anyDiskFieldPresent) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "非资源版块不接受网盘字段（该版块 isResource=0）：" + board.getName());
            }
            return new DiskFields(null, null);
        }

        if (diskType == null) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "资源版块发帖必须提供 diskType（" + DiskType.legalValues() + "）");
        }
        if (DiskType.of(diskType) == null) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "diskType 取值非法，合法取值：" + DiskType.legalValues());
        }
        if (diskUrl == null || diskUrl.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "资源版块发帖必须提供 diskUrl");
        }

        DiskFields normalized = DiskFieldNormalizer.normalize(diskUrl, diskCode);
        String url = normalized.diskUrl();
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            throw new BizException(ErrorCode.BAD_REQUEST, "diskUrl 必须是 http/https 链接");
        }
        if (url.length() > 500) {
            throw new BizException(ErrorCode.BAD_REQUEST, "diskUrl 不能超过 500 字符");
        }
        if (normalized.diskCode() != null && normalized.diskCode().length() > 20) {
            throw new BizException(ErrorCode.BAD_REQUEST, "提取码不能超过 20 个字符");
        }
        return normalized;
    }

    /**
     * 校验图片 URL 列表（§8.4 第 4 条 + §11 的九宫格上限）。
     *
     * <p>两条规则合在一起才是完整的防线：<b>数量上限</b>（≤9）与<b>归属校验</b>
     * （必须位于本项目 OSS 的帖子图片目录下）。归属校验若缺失，
     * 任何人都能把外站图片塞进帖子 —— 既是被盗链的跳板，
     * 也让"图片审核"这个面失控（审的是自己 OSS 里的东西，前台渲染的却是别处的图）。</p>
     *
     * <p>任一元素不合法即整条请求被拒（不落库）：只存下"合法的那几张"会留下一个
     * 与用户提交内容不一致的帖子，而用户看到的响应还是成功的。</p>
     */
    private List<String> resolveImageUrls(List<String> rawImages) {
        if (rawImages == null || rawImages.isEmpty()) {
            return List.of();
        }
        if (rawImages.size() > postProperties.maxImages()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "单帖最多 " + postProperties.maxImages() + " 张图片，收到 " + rawImages.size() + " 张");
        }
        String allowedPrefix = ossProperties.imageUrlPrefix();
        List<String> result = new ArrayList<>(rawImages.size());
        for (String raw : rawImages) {
            String url = raw == null ? null : raw.trim();
            if (url == null || url.isEmpty()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "图片地址不能为空");
            }
            if (allowedPrefix.isEmpty()) {
                // fail-closed：前缀未知时拒绝一切图片 URL，见 OssProperties 的类注释
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "本站尚未配置 OSS 图片前缀，暂不接受图片；请先配置环境变量 OSS_ENDPOINT / OSS_BUCKET");
            }
            if (!url.startsWith(allowedPrefix)) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "图片地址必须位于本站 OSS 目录（" + allowedPrefix + "）内，收到：" + url);
            }
            if (url.length() > 500) {
                throw new BizException(ErrorCode.BAD_REQUEST, "图片地址不能超过 500 字符");
            }
            result.add(url);
        }
        return result;
    }

    /** 敏感词判定：标题与正文分别检查（拼起来检查会让跨越边界的伪命中出现）。 */
    private boolean isSensitive(String title, String content) {
        return sensitiveTextChecker.containsSensitive(title)
                || sensitiveTextChecker.containsSensitive(content);
    }

    // ==================================================================
    // 图片行维护
    // ==================================================================

    /**
     * 绑定帖子的图片行。
     *
     * <p><b>为什么要"认领"而不是"直接插入"</b>：OSS 直传回调（第二交付段）发生在
     * "帖子还不存在"的时刻，因此回调写下的图片行 {@code post_id} 是占位值 0
     * （见 {@link PostImage#UNBOUND_POST_ID}）。发帖时按 URL 把这些行认领过来，
     * 可以保留回调写入的 {@code width/height}，也避免同一张图出现两行。
     * 认领不到（例如第一交付段没有回调）就按提交的 URL 直接落库。</p>
     */
    private void bindImages(long postId, List<String> urls) {
        if (urls.isEmpty()) {
            return;
        }
        Map<String, List<PostImage>> claimable = new HashMap<>();
        List<PostImage> unbound = postImageMapper.selectList(Wrappers.<PostImage>lambdaQuery()
                .eq(PostImage::getPostId, PostImage.UNBOUND_POST_ID)
                .in(PostImage::getUrl, urls));
        for (PostImage image : unbound) {
            claimable.computeIfAbsent(image.getUrl(), key -> new ArrayList<>()).add(image);
        }

        int sort = 0;
        for (String url : urls) {
            List<PostImage> candidates = claimable.get(url);
            if (candidates != null && !candidates.isEmpty()) {
                PostImage claimed = candidates.remove(0);
                claimed.setPostId(postId);
                claimed.setSort(sort);
                if (claimed.getThumbUrl() == null || claimed.getThumbUrl().isBlank()) {
                    claimed.setThumbUrl(ThumbnailUrls.derive(url));
                }
                postImageMapper.updateById(claimed);
            } else {
                PostImage image = new PostImage();
                image.setPostId(postId);
                image.setUrl(url);
                image.setThumbUrl(ThumbnailUrls.derive(url));
                image.setWidth(0);
                image.setHeight(0);
                image.setSort(sort);
                // CR-006：新图一律是"尚未被人工判定"（0），前台**不隐藏** 0
                image.setAuditStatus(PostImage.AUDIT_PENDING);
                image.setCreatedAt(LocalDateTime.now());
                postImageMapper.insert(image);
            }
            sort++;
        }
    }

    /**
     * 编辑时替换图片集合：<b>保留 URL 未变的那几行</b>，只增删差异部分。
     *
     * <p>为什么不"全删再全插"：{@code post_image.audit_status} 是人工审核结果
     * （0/1/2，CR-006）。全删再插会把"某张图已被人工确认通过"或"已判定违规"
     * 的结果一起抹掉，于是作者只要编辑一次帖子，被判违规的图片就能重新回到前台 ——
     * 这是审核链上的一条真实绕过路径。</p>
     */
    private void rebindImages(long postId, List<String> newUrls) {
        Map<String, List<PostImage>> existing = new LinkedHashMap<>();
        for (PostImage image : postImageMapper.selectList(Wrappers.<PostImage>lambdaQuery()
                .eq(PostImage::getPostId, postId)
                .orderByAsc(PostImage::getSort))) {
            existing.computeIfAbsent(image.getUrl(), key -> new ArrayList<>()).add(image);
        }

        List<Long> toDelete = new ArrayList<>();
        int sort = 0;
        for (String url : newUrls) {
            List<PostImage> candidates = existing.get(url);
            if (candidates != null && !candidates.isEmpty()) {
                PostImage kept = candidates.remove(0);
                if (kept.getSort() == null || kept.getSort() != sort) {
                    kept.setSort(sort);
                    postImageMapper.updateById(kept);
                }
            } else {
                PostImage image = new PostImage();
                image.setPostId(postId);
                image.setUrl(url);
                image.setThumbUrl(ThumbnailUrls.derive(url));
                image.setWidth(0);
                image.setHeight(0);
                image.setSort(sort);
                image.setAuditStatus(PostImage.AUDIT_PENDING);
                image.setCreatedAt(LocalDateTime.now());
                postImageMapper.insert(image);
            }
            sort++;
        }
        // 被移出帖子图片集合的行一律删掉（它们的审核结果对这篇帖子已无意义）
        for (List<PostImage> leftovers : existing.values()) {
            for (PostImage image : leftovers) {
                toDelete.add(image.getId());
            }
        }
        if (!toDelete.isEmpty()) {
            postImageMapper.delete(Wrappers.<PostImage>lambdaQuery().in(PostImage::getId, toDelete));
        }
    }

    /**
     * 该帖<b>全部</b>图片行的 URL（含 {@code audit_status=2} 的隐藏图），按顺序排列。
     *
     * <p>编辑的"内容是否变更"判定必须基于<b>全部</b>行：若只比对"前台可见的那些"，
     * 作者在一个带违规图的帖子里改标题时，隐藏图的差异会被算进来 ——
     * 明明没动图片却被判成"图片变更"（或反之），于是重审判定的结果与用户的操作对不上。</p>
     */
    private List<String> listAllImageUrls(long postId) {
        return postImageMapper.selectList(Wrappers.<PostImage>lambdaQuery()
                        .eq(PostImage::getPostId, postId)
                        .orderByAsc(PostImage::getSort)
                        .orderByAsc(PostImage::getId))
                .stream().map(PostImage::getUrl).toList();
    }

    /**
     * 帖子对外可见的图片（CR-006）：<b>只隐藏 {@code audit_status=2}</b>，不过滤 0。
     *
     * <p>把默认值 0（尚未被人工判定）也挡掉，会导致每张图都要人工点一次才能显示，
     * 与 §8.6 第 5 条的先发后审矛盾，M3 的验收（带图帖正确展示）也无法达成。
     * 图片的审核出口（置 1／2）归 M5/M6。</p>
     */
    private List<PostImage> listVisibleImages(long postId) {
        return postImageMapper.selectList(Wrappers.<PostImage>lambdaQuery()
                .eq(PostImage::getPostId, postId)
                .ne(PostImage::getAuditStatus, PostImage.AUDIT_REJECTED)
                .orderByAsc(PostImage::getSort)
                .orderByAsc(PostImage::getId));
    }

    // ==================================================================
    // VO 组装
    // ==================================================================

    /**
     * 实体 → 详情 VO。
     *
     * <p>作者的昵称/头像需要一次用户查询：本方法在单帖路径上调用（详情、发帖、改帖），
     * 一次 {@code selectById} 是可以接受的；列表走 {@link #toSummaries} 的批量版本，
     * 避免 20 条列表打 20 次用户表（N+1 查询）。</p>
     */
    private PostDetailVO toDetail(Post post, Board board, User author,
                                  List<PostImage> images, int viewCount) {
        return new PostDetailVO(
                post.getId(),
                post.getBoardId(),
                board == null ? null : board.getName(),
                board != null && board.getIsResource() != null && board.getIsResource() == 1,
                post.getTitle(),
                post.getContent(),
                post.getCoverUrl(),
                nullToZero(post.getImageCount()),
                images.stream().map(PostImageVO::from).toList(),
                post.getDiskType(),
                post.getDiskUrl(),
                post.getDiskCode(),
                viewCount,
                nullToZero(post.getLikeCount()),
                nullToZero(post.getCommentCount()),
                nullToZero(post.getCollectCount()),
                post.getIsTop() != null && post.getIsTop() == 1,
                post.getIsEssence() != null && post.getIsEssence() == 1,
                nullToZero(post.getStatus()),
                UserBriefVO.from(author),
                post.getCreatedAt(),
                post.getUpdatedAt());
    }

    /**
     * 批量组装列表项：版块名与作者信息<b>各查一次</b>（而不是每条一次）。
     *
     * <p>列表是最高频的读接口，N+1 查询在 20 条一页时就是 41 次往返 ——
     * 在 2 核 2G 的单机上这是能明显感觉到的那种慢。</p>
     */
    private List<PostSummaryVO> toSummaries(List<Post> posts) {
        if (posts == null || posts.isEmpty()) {
            return List.of();
        }
        List<Long> boardIds = posts.stream().map(Post::getBoardId).distinct().toList();
        List<Long> userIds = posts.stream().map(Post::getUserId).distinct().toList();

        Map<Long, String> boardNames = new HashMap<>();
        for (Board board : boardMapper.selectBatchIds(boardIds)) {
            boardNames.put(board.getId(), board.getName());
        }
        Map<Long, User> authors = new HashMap<>();
        for (User user : userMapper.selectBatchIds(userIds)) {
            authors.put(user.getId(), user);
        }

        List<PostSummaryVO> result = new ArrayList<>(posts.size());
        for (Post post : posts) {
            result.add(new PostSummaryVO(
                    post.getId(),
                    post.getBoardId(),
                    boardNames.get(post.getBoardId()),
                    post.getTitle(),
                    // 封面 = 首图缩略图（post.cover_url 的列注释口径）
                    post.getCoverUrl(),
                    nullToZero(post.getImageCount()),
                    post.getIsTop() != null && post.getIsTop() == 1,
                    post.getIsEssence() != null && post.getIsEssence() == 1,
                    nullToZero(post.getViewCount()),
                    nullToZero(post.getLikeCount()),
                    nullToZero(post.getCommentCount()),
                    nullToZero(post.getCollectCount()),
                    UserBriefVO.from(authors.get(post.getUserId())),
                    post.getCreatedAt()));
        }
        return result;
    }

    // ==================================================================
    // 小工具
    // ==================================================================

    /** 空白归一成 {@code null}（避免库里出现"空字符串正文"这种与 NULL 语义重复的状态）。 */
    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 计数类字段的 null 兜底（历史行可能为 null，直接拆箱会 NPE 到接口层）。 */
    private static int nullToZero(Integer value) {
        return value == null ? 0 : value;
    }

    /** 转义 LIKE 通配符：{@code \} {@code %} {@code _} 三个字符（MySQL 默认转义符是反斜杠）。 */
    private static String escapeLike(String keyword) {
        return keyword.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
