package com.hyforum.interaction.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hyforum.common.api.ErrorCode;
import com.hyforum.common.api.PageResult;
import com.hyforum.common.exception.BizException;
import com.hyforum.domain.board.entity.Board;
import com.hyforum.domain.board.mapper.BoardMapper;
import com.hyforum.domain.post.entity.Post;
import com.hyforum.domain.post.mapper.PostMapper;
import com.hyforum.domain.user.entity.User;
import com.hyforum.domain.user.mapper.UserMapper;
import com.hyforum.interaction.vo.FeedItemVO;
import com.hyforum.interaction.vo.UserBriefVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 首页双流（docs/技术方案.md §6.5、§8.5）。
 *
 * <table>
 *   <caption>两条流的可见性规则</caption>
 *   <tr><th>{@code type}</th><th>SQL 条件</th><th>未登录</th></tr>
 *   <tr><td>{@code follow}</td>
 *       <td>{@code user_id IN (我关注的人)} 且 {@code status=1} 且未删除</td>
 *       <td><b>401 引导登录</b></td></tr>
 *   <tr><td>{@code all}</td>
 *       <td>{@code status=1} 且未删除，时间倒序（另支持 {@code sort=hot}）</td>
 *       <td>正常返回</td></tr>
 * </table>
 *
 * <h2>三条容易写错的点</h2>
 * <ol>
 *   <li><b>未登录访问关注流必须 401，不是空列表</b>（任务书 §5.5 第 1 条）。
 *       返回空列表会让前端显示"你关注的人还没有发帖"，而真相是"你根本没登录" ——
 *       用户会去关注更多人，问题却一直不变。</li>
 *   <li><b>关注流必须过滤待审帖（{@code status=0}）与已删帖</b>（第 7 条）。
 *       "我关注的人发的"不是可见性的依据 —— 待审内容对普通用户不可见这条规则
 *       不因为"是熟人发的"而放宽。</li>
 *   <li><b>全部流按时间倒序</b>（第 6 条），分页 {@code size} 硬上限 20 由
 *       {@link PageResult#normalizeSize} 统一收敛。</li>
 * </ol>
 *
 * <p><b>为什么这条流的实现放在 {@code interaction} 而不是 {@code post}</b>：
 * 任务书 §2 明确把 {@code GET /api/feed} 划进本任务的写权（它在 §6.5 里，
 * 但关注关系由本模块拥有，而 {@code post} 包不归 M4 改）。
 * 查询只用 {@code domain} 的实体与 Mapper，没有跨模块调用。</p>
 */
@Service
public class FeedService {

    /** 关注流。 */
    public static final String TYPE_FOLLOW = "follow";

    /** 全部流。 */
    public static final String TYPE_ALL = "all";

    /** 时间倒序（默认）。 */
    private static final String SORT_LATEST = "latest";

    /** 热度排序（§8.5：额外提供"热门"入口）。 */
    private static final String SORT_HOT = "hot";

    private final PostMapper postMapper;
    private final BoardMapper boardMapper;
    private final UserMapper userMapper;
    private final FollowService followService;

    public FeedService(PostMapper postMapper,
                       BoardMapper boardMapper,
                       UserMapper userMapper,
                       FollowService followService) {
        this.postMapper = postMapper;
        this.boardMapper = boardMapper;
        this.userMapper = userMapper;
        this.followService = followService;
    }

    /**
     * 取首页流。
     *
     * @param type     {@code follow} 或 {@code all}（缺省 {@code all}）
     * @param sort     {@code latest}（默认）或 {@code hot}
     * @param viewerId 当前登录用户 id；未登录为 {@code null}
     */
    @Transactional(readOnly = true)
    public PageResult<FeedItemVO> feed(String type, String sort, Long viewerId, int page, int size) {
        String mode = normalizeType(type);
        String sortMode = normalizeSort(sort);
        int pageNo = PageResult.normalizePage(page);
        int pageSize = PageResult.normalizeSize(size);

        QueryWrapper<Post> wrapper = Wrappers.query();
        wrapper.eq("status", Post.STATUS_NORMAL);

        if (TYPE_FOLLOW.equals(mode)) {
            if (viewerId == null) {
                // 口径第 1 条：引导登录，而不是给一个空列表。
                // 用 401（契约里"未登录"的码），前端据此弹登录框。
                throw new BizException(ErrorCode.UNAUTHORIZED, "关注流需要登录后查看");
            }
            List<Long> following = followService.followingIds(viewerId);
            if (following.isEmpty()) {
                // 没关注任何人 → 空列表（这是真实的业务状态，不是错误）
                return PageResult.of(List.of(), 0L, pageNo, pageSize);
            }
            wrapper.in("user_id", following);
        }

        applySort(wrapper, sortMode);

        Page<Post> query = new Page<>(pageNo, pageSize);
        IPage<Post> result = postMapper.selectPage(query, wrapper);
        return PageResult.of(toItems(result.getRecords()), result.getTotal(), pageNo, pageSize);
    }

    /** 排序：默认时间倒序；{@code hot} 用 §8.5 的热度公式。 */
    private void applySort(QueryWrapper<Post> wrapper, String sortMode) {
        if (SORT_HOT.equals(sortMode)) {
            // 列名与公式都是代码里的常量，不含任何用户输入（§9 禁的是把参数拼进 SQL）
            wrapper.orderByDesc("(like_count * 3 + comment_count * 5 + view_count)")
                    .orderByDesc("created_at");
            return;
        }
        wrapper.orderByDesc("is_top").orderByDesc("created_at").orderByDesc("id");
    }

    /** {@code type} 归一；非法取值 → 400（静默回落默认值会让前端的"关注"入口失效却不报错）。 */
    private String normalizeType(String type) {
        if (type == null || type.isBlank()) {
            return TYPE_ALL;
        }
        String value = type.trim().toLowerCase(java.util.Locale.ROOT);
        if (TYPE_FOLLOW.equals(value) || TYPE_ALL.equals(value)) {
            return value;
        }
        throw new BizException(ErrorCode.BAD_REQUEST, "type 只支持 follow / all，收到：" + type);
    }

    /** {@code sort} 归一；非法取值 → 400。 */
    private String normalizeSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return SORT_LATEST;
        }
        String value = sort.trim().toLowerCase(java.util.Locale.ROOT);
        if (SORT_LATEST.equals(value) || SORT_HOT.equals(value)) {
            return value;
        }
        throw new BizException(ErrorCode.BAD_REQUEST, "sort 只支持 latest / hot，收到：" + sort);
    }

    /** 批量组装列表项：版块与作者各查一次（一次 {@code IN}），避免 N+1。 */
    private List<FeedItemVO> toItems(List<Post> posts) {
        if (posts == null || posts.isEmpty()) {
            return List.of();
        }
        List<Long> boardIds = posts.stream().map(Post::getBoardId).distinct().toList();
        List<Long> userIds = posts.stream().map(Post::getUserId).distinct().toList();

        Map<Long, String> boardNames = new HashMap<>();
        for (Board board : boardMapper.selectBatchIds(boardIds)) {
            boardNames.put(board.getId(), board.getName());
        }
        Map<Long, UserBriefVO> authors = new HashMap<>();
        for (User user : userMapper.selectBatchIds(userIds)) {
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
