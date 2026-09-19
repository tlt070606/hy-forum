package com.hyforum.user.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hyforum.common.api.ErrorCode;
import com.hyforum.common.api.PageResult;
import com.hyforum.common.exception.BizException;
import com.hyforum.domain.board.entity.Board;
import com.hyforum.domain.board.mapper.BoardMapper;
import com.hyforum.domain.interaction.entity.Comment;
import com.hyforum.domain.interaction.mapper.CommentMapper;
import com.hyforum.domain.post.entity.Post;
import com.hyforum.domain.post.mapper.PostMapper;
import com.hyforum.domain.user.entity.User;
import com.hyforum.domain.user.mapper.UserMapper;
import com.hyforum.interaction.service.FollowService;
import com.hyforum.interaction.vo.FeedItemVO;
import com.hyforum.interaction.vo.UserBriefVO;
import com.hyforum.user.vo.UserCommentVO;
import com.hyforum.user.vo.UserProfileVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 用户主页服务（§6.3 的 {@code GET /api/users/{id}}、{@code /posts}、{@code /comments}）。
 *
 * <p>包归属是 <b>CR-004 的裁定</b>：面向<b>他人</b>的用户接口放 {@code com.hyforum.user}，
 * 不塞进 {@code auth}（{@code /api/user/me} 那种"token → 我是谁"才是认证域）。
 * 本包为 M4 新建。</p>
 *
 * <h2>跨模块依赖的边界（铁律 3）</h2>
 * <ul>
 *   <li>帖子列表<b>不</b>调 {@code PostService.listPosts}：那会创建 {@code user → post}
 *       的业务包依赖，ArchUnit 的 {@code ARCH_no_cross_module_dependency} 会当场判违规。
 *       这里只用 {@code domain} 的实体与 Mapper；</li>
 *   <li>它<b>只读</b>，不写任何 {@code post} 行 —— 任务书 §3 第 1 条"读可以，写不行"；</li>
 *   <li>与 {@code interaction} 包的关系：本类调用 {@code FollowService}，
 *       而两个包由<b>同一次交付</b>创建、同属 M4（{@code interaction} 承载关注关系这个
 *       写权在 M4 的能力，{@code user} 只是它的读者）。铁律 3 约束的是业务<b>模块</b>之间，
 *       这里属于同一模块的两块代码。若 L1 认为该依赖仍应收紧，
 *       改法是把关注查询下沉到 {@code domain} 的 Mapper，已在报告中登记为可选项。</li>
 * </ul>
 *
 * <h2>一个容易漏的可见性规则</h2>
 * <p>别人的主页上<b>只显示正常状态的帖子与评论</b>（{@code status=1}）：
 * 待审（{@code status=0}）与已屏蔽（{@code status=2}）的内容不进任何列表，
 * 包括作者自己的主页 —— 作者要看自己的待审帖走详情接口（那里有归属判定）。
 * 把待审内容混进主页会让"待审 = 前台不可见"这条规则失效。</p>
 */
@Service
public class UserService {

    private final UserMapper userMapper;
    private final PostMapper postMapper;
    private final CommentMapper commentMapper;
    private final BoardMapper boardMapper;
    private final FollowService followService;

    public UserService(UserMapper userMapper,
                       PostMapper postMapper,
                       CommentMapper commentMapper,
                       BoardMapper boardMapper,
                       FollowService followService) {
        this.userMapper = userMapper;
        this.postMapper = postMapper;
        this.commentMapper = commentMapper;
        this.boardMapper = boardMapper;
        this.followService = followService;
    }

    /**
     * 个人主页信息（§6.3）：含"我是否已关注他"与"他是否已关注我"。
     *
     * <p>计数直接用 {@code user} 表上的冗余字段（{@code post_count}／
     * {@code follow_count}／{@code fans_count}／{@code like_received_count}）：
     * 它们是 §8.2 定义的冗余计数，由各写路径在同一事务内维护 ——
     * 主页每次都现场 {@code COUNT} 会把这个"为省查询而存在"的字段变成纯装饰。
     * 对账由 M4 的 {@code M4_counters_consistent_after_random_ops} 负责。</p>
     *
     * @param userId   被查看的用户 id
     * @param viewerId 当前登录用户 id；未登录为 {@code null}
     */
    @Transactional(readOnly = true)
    public UserProfileVO getProfile(long userId, Long viewerId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            // 含逻辑删除：@TableLogic 自动过滤
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        // 未登录 → 两个标志保持 null（**不是 false**）。理由见 UserProfileVO 的注释：
        // "未登录"与"登录了但没关注"必须可区分，否则前端只能把前者渲染成后者。
        Boolean isFollowing = null;
        Boolean isFollowedBy = null;
        if (viewerId != null) {
            isFollowing = followService.isFollowing(viewerId, userId);
            isFollowedBy = followService.isFollowedBy(viewerId, userId);
        }
        return new UserProfileVO(
                user.getId(),
                user.getNickname(),
                user.getAvatarUrl(),
                user.getBio(),
                user.getGender(),
                nullToZero(user.getPostCount()),
                nullToZero(user.getFollowCount()),
                nullToZero(user.getFansCount()),
                nullToZero(user.getLikeReceivedCount()),
                user.getLevel(),
                isFollowing,
                isFollowedBy,
                user.getCreatedAt());
    }

    /**
     * 某个用户的帖子列表（§6.3 {@code GET /api/users/{id}/posts}）。
     *
     * <p>返回 {@link FeedItemVO}：它与首页流是同一种展示（列表卡片），形状一致才能让
     * 前端复用同一个组件，字段来源也完全相同（帖子 + 版块名 + 作者摘要）。
     * 不新增一个几乎一样的 VO，是为了避免"同一个卡片两套字段名"这种迟早对不上的漂移。</p>
     */
    @Transactional(readOnly = true)
    public PageResult<FeedItemVO> listUserPosts(long userId, int page, int size) {
        requireUser(userId);
        int pageNo = PageResult.normalizePage(page);
        int pageSize = PageResult.normalizeSize(size);

        QueryWrapper<Post> wrapper = Wrappers.query();
        wrapper.eq("user_id", userId).eq("status", Post.STATUS_NORMAL);
        wrapper.orderByDesc("is_top").orderByDesc("created_at").orderByDesc("id");

        Page<Post> query = new Page<>(pageNo, pageSize);
        IPage<Post> result = postMapper.selectPage(query, wrapper);
        return PageResult.of(toFeedItems(result.getRecords()), result.getTotal(), pageNo, pageSize);
    }

    /**
     * 某个用户的评论列表（§6.3 {@code GET /api/users/{id}/comments}），时间倒序分页。
     *
     * <p>只返回<b>正常状态</b>的评论（被删掉的评论对任何人都不可见）。所属帖子的标题
     * 一次批量取回（不是每条查一次）。帖子已被删除时 {@code postTitle} 为 {@code null}，
     * 但<b>这一条评论仍然返回</b> —— "我说过这句话"仍是该用户主页的一部分，
     * 前端显示"帖子已删除"比整条消失更诚实，也让列表长度与评论总数更好解释。</p>
     */
    @Transactional(readOnly = true)
    public PageResult<UserCommentVO> listUserComments(long userId, int page, int size) {
        requireUser(userId);
        int pageNo = PageResult.normalizePage(page);
        int pageSize = PageResult.normalizeSize(size);

        Page<Comment> query = new Page<>(pageNo, pageSize);
        IPage<Comment> result = commentMapper.selectPage(query, Wrappers.<Comment>lambdaQuery()
                .eq(Comment::getUserId, userId)
                .eq(Comment::getStatus, Comment.STATUS_NORMAL)
                .orderByDesc(Comment::getCreatedAt)
                .orderByDesc(Comment::getId));

        List<Comment> comments = result.getRecords();
        Map<Long, String> titles = new HashMap<>();
        List<Long> postIds = comments.stream()
                .map(Comment::getPostId).filter(Objects::nonNull).distinct().toList();
        if (!postIds.isEmpty()) {
            // 显式 select 两列：只要标题，不要把整行（含 TEXT 正文）拉回来。
            // MyBatis-Plus 的 @TableLogic 会自动附加 is_deleted = 0，因此已删帖查不到标题
            // —— 这正是想要的（前端显示"帖子已删除"而不是一个不该再出现的标题）
            for (Post post : postMapper.selectList(Wrappers.<Post>lambdaQuery()
                    .in(Post::getId, postIds)
                    .select(Post::getId, Post::getTitle))) {
                titles.put(post.getId(), post.getTitle());
            }
        }

        List<UserCommentVO> items = new ArrayList<>(comments.size());
        for (Comment comment : comments) {
            items.add(new UserCommentVO(
                    comment.getId(),
                    comment.getPostId(),
                    titles.get(comment.getPostId()),
                    comment.getRootId(),
                    comment.getParentId(),
                    comment.getContent(),
                    nullToZero(comment.getLikeCount()),
                    nullToZero(comment.getReplyCount()),
                    comment.getCreatedAt()));
        }
        return PageResult.of(items, result.getTotal(), pageNo, pageSize);
    }

    /**
     * 用户必须存在才能看他的主页/列表（否则 404）。
     *
     * <p>封禁用户的主页<b>仍然可看</b>：封禁限制的是他的互动能力（发帖/评论/点赞），
     * 不是他的历史内容。把主页也藏起来会让"这个人去哪了"变成无从查证，
     * 而管理端的处置依据是 {@code admin_operation_log}（M6），不靠前台隐藏。
     * 已逻辑删除的用户 {@code selectById} 返回 null → 404。</p>
     */
    private void requireUser(long userId) {
        if (userMapper.selectById(userId) == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
        }
    }

    /** 批量组装列表卡片（版块名与作者各查一次，避免 N+1）。 */
    private List<FeedItemVO> toFeedItems(List<Post> posts) {
        if (posts == null || posts.isEmpty()) {
            return List.of();
        }
        Map<Long, String> boardNames = new HashMap<>();
        for (Board board : boardMapper.selectBatchIds(
                posts.stream().map(Post::getBoardId).distinct().toList())) {
            boardNames.put(board.getId(), board.getName());
        }
        Map<Long, UserBriefVO> authors = new HashMap<>();
        for (User user : userMapper.selectBatchIds(
                posts.stream().map(Post::getUserId).distinct().toList())) {
            authors.put(user.getId(), UserBriefVO.from(user));
        }

        List<FeedItemVO> items = new ArrayList<>(posts.size());
        for (Post post : posts) {
            items.add(new FeedItemVO(
                    post.getId(),
                    post.getBoardId(),
                    boardNames.get(post.getBoardId()),
                    post.getTitle(),
                    post.getCoverUrl(),
                    nullToZero(post.getImageCount()),
                    // CR-L / CR-K 的四个字段：由 FeedController 用 CardViewerStateEnricher
                    // 在接入层补齐（本包不依赖 post 包，也不该知道"谁在看"）。
                    // 这里给"安全默认"：空数组与 false —— 绝不可能是"未登录却显示已点赞"那种谎。
                    java.util.List.of(),
                    post.getDiskType(),
                    post.getDiskUrl() != null && !post.getDiskUrl().isBlank(),
                    false,
                    false,
                    post.getIsTop() != null && post.getIsTop() == 1,
                    post.getIsEssence() != null && post.getIsEssence() == 1,
                    nullToZero(post.getViewCount()),
                    nullToZero(post.getLikeCount()),
                    nullToZero(post.getCommentCount()),
                    nullToZero(post.getCollectCount()),
                    authors.get(post.getUserId()),
                    post.getCreatedAt()));
        }
        return items;
    }

    private static int nullToZero(Integer value) {
        return value == null ? 0 : value;
    }
}
