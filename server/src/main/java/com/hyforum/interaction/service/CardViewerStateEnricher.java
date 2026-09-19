package com.hyforum.interaction.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hyforum.common.oss.OssReadUrlSigner;
import com.hyforum.common.oss.OssThumbnailUrls;
import com.hyforum.domain.post.entity.PostImage;
import com.hyforum.domain.post.mapper.PostImageMapper;
import com.hyforum.interaction.vo.FeedItemVO;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 列表卡片的"<b>请求者视角字段</b>"装配器（CR-K 的 {@code liked}/{@code collected}
 * 与 CR-L 的 {@code imageThumbs}）。
 *
 * <h2>为什么单独成一个类，而不是在三个 Controller 里各写一遍</h2>
 * <p>{@code /api/feed}（首页流）、{@code /api/users/{{id}}/posts}（个人主页）、
 * {@code /api/posts}（版块列表）返回的是<b>同一个卡片组件</b>要的三个不同 VO。
 * 若每个 Controller 各写一遍"补齐",必然出现"改了俩漏了一个"——
 * 而漏掉的那个入口会静默地永远显示未点赞：**那就是 CR-K 要消灭的那种谎，
 * 只是从"刷新后丢状态"换成"某个入口丢状态"**。因此这里只实现一次。</p>
 *
 * <h2>查询次数与页大小无关</h2>
 * <p>一批帖子固定 3 次查询：① 点赞关系；② 收藏关系；③ 图片行（在内存里按 sort 取前 3）。
 * 未登录时前两次直接跳过（返回空集合，不查库）。</p>
 *
 * <p>本类放在 {@code interaction} 包：它要查的两张关系表与"最多 3 张缩略图"的口径
 * 都属于互动/展示范畴，而 {@code post} 与 {@code user} 两个业务包都<b>不能</b>
 * 互相依赖（铁律 3），却都可以依赖本包 —— 这是让"同一口径只写一次"成立的最小代价。</p>
 */
@Component
public class CardViewerStateEnricher {

    /** CR-L：信息流卡片最多 3 张缩略图（每页 20 条 × 9 张 = 每页 180 个 URL 都要现签）。 */
    private static final int THUMB_LIMIT = 3;

    private final InteractionService interactionService;
    private final PostImageMapper postImageMapper;
    private final OssReadUrlSigner readUrlSigner;

    public CardViewerStateEnricher(InteractionService interactionService,
                                   PostImageMapper postImageMapper,
                                   OssReadUrlSigner readUrlSigner) {
        this.interactionService = interactionService;
        this.postImageMapper = postImageMapper;
        this.readUrlSigner = readUrlSigner;
    }

    /**
     * 给一页卡片补上 {@code liked}/{@code collected}/{@code imageThumbs}。
     *
     * @param items    该页卡片（可为空）
     * @param viewerId 当前请求者；<b>未登录传 {@code null}</b> → 两个布尔恒 {@code false}
     * @return 补齐后的新列表（记录是 immutable，因此是复制而不是就地改）
     */
    public List<FeedItemVO> enrich(List<FeedItemVO> items, Long viewerId) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<Long> postIds = items.stream().map(FeedItemVO::id).toList();
        Set<Long> liked = interactionService.likedPostIds(viewerId, postIds);
        Set<Long> collected = interactionService.collectedPostIds(viewerId, postIds);
        Map<Long, List<String>> thumbs = loadThumbnails(postIds);
        System.out.println("[DIAG-enrich] viewerId=" + viewerId + " postIds=" + postIds
                + " liked=" + liked + " collected=" + collected);

        return items.stream()
                .map(item -> item.withViewerState(
                        liked.contains(item.id()),
                        collected.contains(item.id()),
                        thumbs.getOrDefault(item.id(), List.of())))
                .toList();
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
     *
     * <p><b>为什么要 public</b>：{@code post} 的列表（{@code PostSummaryVO}）与
     * {@code interaction} 的卡片（{@code FeedItemVO}）是两套 VO，无法共用同一个
     * {@link #enrich} 返回值，但<b>必须共用同一份缩略图口径</b>。
     * 所以这里暴露"取缩略图"这一步给 {@code PostController} 用。</p>
     */
    public Map<Long, List<String>> loadThumbnails(List<Long> postIds) {
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
                // 已经够 3 张：后面的不签（省的是签名成本，查询成本已经付过了）
                continue;
            }
            String bare = image.getThumbUrl() != null && !image.getThumbUrl().isBlank()
                    ? image.getThumbUrl()
                    : OssThumbnailUrls.derive(image.getUrl());
            perPost.add(readUrlSigner.sign(bare));
        }
        return result;
    }
}
