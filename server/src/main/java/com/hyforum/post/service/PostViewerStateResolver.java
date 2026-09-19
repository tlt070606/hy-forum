package com.hyforum.post.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hyforum.common.oss.OssReadUrlSigner;
import com.hyforum.common.oss.OssThumbnailUrls;
import com.hyforum.domain.interaction.entity.PostCollect;
import com.hyforum.domain.interaction.entity.PostLike;
import com.hyforum.domain.interaction.mapper.PostCollectMapper;
import com.hyforum.domain.interaction.mapper.PostLikeMapper;
import com.hyforum.domain.post.entity.PostImage;
import com.hyforum.domain.post.mapper.PostImageMapper;
import com.hyforum.post.vo.PostDetailVO;
import com.hyforum.post.vo.PostSummaryVO;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 给帖子列表/详情补上"<b>请求者视角字段</b>"（CR-K 的 {@code liked}/{@code collected}
 * 与 CR-L 的 {@code imageThumbs}）。
 *
 * <h2>⚠️ 为什么这个类必须住在 {@code post} 包里（而不是复用 interaction 的实现）</h2>
 * <p>第一版我把这两个字段交给 {@code interaction} 的
 * {@code InteractionService} / {@code CardViewerStateEnricher} 去查，并在
 * {@code PostController} 里注入它们 —— <b>结果被 ArchUnit 当场判违规（9 处）</b>：</p>
 * <pre>
 *   Rule '七个业务包之间禁止相互依赖（只允许依赖 common 与 domain）' was violated (9 times):
 *     Field &lt;PostController.enricher&gt; has type &lt;com.hyforum.interaction.service.CardViewerStateEnricher&gt;
 *     Field &lt;PostController.interactionService&gt; has type &lt;com.hyforum.interaction.service.InteractionService&gt;
 *     Method &lt;PostController.withViewerState&gt; calls method &lt;InteractionService.collectedPostIds&gt;
 * </pre>
 * <p>这就是<b>铁律 3 的原意</b>：{@code post} 不得依赖 {@code interaction}，
 * <b>即使依赖发生在 controller 层也一样</b> —— 规则按包判，不按"这一层算不算业务逻辑"判
 * （我一开始以为"接入层调用两个 Service 不算模块依赖"，那是错的）。</p>
 *
 * <p><b>正确的做法就是本类：读 {@code domain} 的实体与 Mapper。</b>
 * 任务书 §3 第 1 条明确"需要帖子的存在性/可见性判断 → 用 {@code domain} 的实体与 Mapper
 * （<b>读可以</b>，写不行）"，而 {@code domain.interaction.mapper} 正是 {@code domain} 的一部分 ——
 * {@code post} 读它<b>不构成</b>对 {@code interaction} 业务包的依赖。
 * 关系表只在<b>写</b>的时候才必须走 {@code interaction}（幂等与计数的唯一写入口）。</p>
 *
 * <h2>代价（如实登记）</h2>
 * <p>于是"哪些帖子被我点过赞"这段查询在 {@code post} 与 {@code interaction} 各有一份
 * （{@code interaction} 那份在 {@code InteractionService.likedPostIds}，服务于
 * {@code FeedItemVO}）。<b>两份实现存在漂移风险</b>，但两者都是"对 domain mapper 的单条件集合查询"，
 * 逻辑薄、且都有用例覆盖。真正的收敛方式是 L1 裁决把公共视图/查询下沉到 {@code domain}
 * （或给 {@code common} 加一个接口由 {@code interaction} 实现）—— 已登记为 CR-M4-7。</p>
 */
@Component
public class PostViewerStateResolver {

    /** CR-L：信息流卡片最多 3 张缩略图（每页 20 条 × 9 张 = 每页 180 个 URL 都要现签）。 */
    private static final int THUMB_LIMIT = 3;

    private final PostLikeMapper postLikeMapper;
    private final PostCollectMapper postCollectMapper;
    private final PostImageMapper postImageMapper;
    private final OssReadUrlSigner readUrlSigner;

    public PostViewerStateResolver(PostLikeMapper postLikeMapper,
                                   PostCollectMapper postCollectMapper,
                                   PostImageMapper postImageMapper,
                                   OssReadUrlSigner readUrlSigner) {
        this.postLikeMapper = postLikeMapper;
        this.postCollectMapper = postCollectMapper;
        this.postImageMapper = postImageMapper;
        this.readUrlSigner = readUrlSigner;
    }

    /**
     * 给一页摘要补上 {@code liked}/{@code collected}/{@code imageThumbs}。
     *
     * <p>查询次数与页大小无关：未登录时 1 次（只取缩略图），登录时 3 次
     * （点赞集合、收藏集合、图片行各一次）。</p>
     *
     * @param items    该页摘要
     * @param viewerId 当前请求者；未登录传 {@code null} → 两个布尔恒 {@code false}
     */
    public List<PostSummaryVO> enrich(List<PostSummaryVO> items, Long viewerId) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<Long> postIds = items.stream().map(PostSummaryVO::id).toList();
        Set<Long> liked = likedPostIds(viewerId, postIds);
        Set<Long> collected = collectedPostIds(viewerId, postIds);
        Map<Long, List<String>> thumbs = loadThumbnails(postIds);

        return items.stream()
                .map(item -> item.withViewerState(
                        liked.contains(item.id()),
                        collected.contains(item.id()),
                        thumbs.getOrDefault(item.id(), List.of())))
                .toList();
    }

    /** 详情：只补 {@code liked}/{@code collected}（详情页已给完整 images，不重复给 thumbs）。 */
    public PostDetailVO enrich(PostDetailVO detail, Long viewerId) {
        List<Long> ids = List.of(detail.id());
        return detail.withViewerState(
                likedPostIds(viewerId, ids).contains(detail.id()),
                collectedPostIds(viewerId, ids).contains(detail.id()));
    }

    /**
     * 一批帖子里哪些被该用户点过赞。
     *
     * <p>未登录或空列表<b>直接返回空集合、不查库</b>：契约要求未登录时 {@code liked} 恒 {@code false}
     * （§13.1），返回空集合表达"确定地没有"，而不是"取不到就凑一个"。</p>
     */
    private Set<Long> likedPostIds(Long viewerId, List<Long> postIds) {
        if (viewerId == null || postIds.isEmpty()) {
            return Set.of();
        }
        return postLikeMapper.selectList(Wrappers.<PostLike>lambdaQuery()
                        .eq(PostLike::getUserId, viewerId)
                        .in(PostLike::getPostId, postIds)
                        .select(PostLike::getPostId))
                .stream().map(PostLike::getPostId).collect(Collectors.toSet());
    }

    /** 一批帖子里哪些被该用户收藏过（与点赞同源同形状）。 */
    private Set<Long> collectedPostIds(Long viewerId, List<Long> postIds) {
        if (viewerId == null || postIds.isEmpty()) {
            return Set.of();
        }
        return postCollectMapper.selectList(Wrappers.<PostCollect>lambdaQuery()
                        .eq(PostCollect::getUserId, viewerId)
                        .in(PostCollect::getPostId, postIds)
                        .select(PostCollect::getPostId))
                .stream().map(PostCollect::getPostId).collect(Collectors.toSet());
    }

    /**
     * 一次查出该页帖子的图片行，按帖子分组取前 3 张<b>缩略图</b>，并逐张读时签名。
     *
     * <ul>
     *   <li>过滤 {@code audit_status=2}（已判定违规）—— 与详情页可见性规则一致（CR-006：
     *       隐藏 2、不隐藏 0）。列表若把违规图当缩略图放出去，等于审核结论在前台失效；</li>
     *   <li>签名必须在<b>拼好 {@code x-oss-process} 之后</b>做：那是 v1 的签名参数，
     *       顺序错了会 403，现象是"大图能看、缩略图 403"。这里复用 M3b 的
     *       {@code OssThumbnailUrls.derive} + {@code OssReadUrlSigner}，不自己拼 URL。</li>
     * </ul>
     */
    private Map<Long, List<String>> loadThumbnails(List<Long> postIds) {
        List<PostImage> images = postImageMapper.selectList(Wrappers.<PostImage>lambdaQuery()
                .in(PostImage::getPostId, postIds)
                .ne(PostImage::getAuditStatus, PostImage.AUDIT_REJECTED)
                .orderByAsc(PostImage::getPostId)
                .orderByAsc(PostImage::getSort)
                .orderByAsc(PostImage::getId));

        Map<Long, List<String>> result = new HashMap<>();
        for (PostImage image : images) {
            List<String> perPost = result.computeIfAbsent(image.getPostId(), key -> new ArrayList<>());
            if (perPost.size() >= THUMB_LIMIT) {
                continue;   // 已够 3 张：后面的不签（省的是签名成本，查询成本已付）
            }
            String bare = image.getThumbUrl() != null && !image.getThumbUrl().isBlank()
                    ? image.getThumbUrl()
                    : OssThumbnailUrls.derive(image.getUrl());
            perPost.add(readUrlSigner.sign(bare));
        }
        return result;
    }
}
