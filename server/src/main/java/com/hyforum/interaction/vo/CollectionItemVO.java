package com.hyforum.interaction.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 「我的收藏」列表项（§6.3 {@code GET /api/user/collections}）。
 *
 * <p>契约没有细化这个列表的形状，因此按<b>最小可用</b>给：列表页要显示什么就给什么，
 * 不给正文（TEXT 字段，一页 20 条是几十上百 KB 的纯浪费 —— 与
 * {@code PostSummaryVO} 刻意不带 {@code content} 同源）。</p>
 *
 * <p>为什么不直接复用帖子列表项 VO：那一个在 {@code com.hyforum.post.vo}，
 * 而 {@code interaction} 包不得依赖 {@code post} 包（铁律 3 + ArchUnit）。
 * 取舍与 {@code UserBriefVO} 的说明一致，已在交付报告中提 CR 收敛。</p>
 *
 * <h2>CR-Q 扩展（2026-09-20）：补签名 + 补字段</h2>
 * <ul>
 *   <li>{@code coverUrl} 此前是<b>库里存的裸 URL</b> —— 桶私有，前端渲染必然 403。
 *       现在经 {@code common.oss.OssUrls} 签名（与 {@code GET /api/posts} 的 coverUrl <b>同一手法</b>，
 *       不另起一套）；</li>
 *   <li>新增 {@code imageThumbs}：与帖子列表卡片同一口径（前 3 张、已签名）；</li>
 *   <li>新增 {@code author}：<b>收藏卡片要显示作者头像</b>，而此前本 VO 一个作者字段都没有。</li>
 * </ul>
 * <p>注意 {@code author} 的类型是 {@code interaction.vo.UserBriefVO}
 * （本包已有一个，不能引 {@code post.vo} 的那个 —— 铁律 3）。</p>
 *
 * @param postId       帖子 id
 * @param boardId      版块 id
 * @param title        标题
 * @param coverUrl     封面（首图缩略图）；<b>已做读时签名</b>
 * @param imageThumbs  前 3 张图缩略图；<b>已签名</b>，无图时为空列表（不是 null）
 * @param author       作者摘要（含头像；头像也已签名）
 * @param imageCount   图片数
 * @param likeCount    点赞数
 * @param commentCount 评论数
 * @param collectCount 收藏数
 * @param createdAt    帖子发布时间
 */
@Schema(name = "CollectionItemVO", description = "我的收藏列表项（不含正文）")
public record CollectionItemVO(
        Long postId,
        Long boardId,
        String title,
        @Schema(description = "封面地址，已做读时签名")
        String coverUrl,
        @Schema(description = "前 3 张图缩略图，已做读时签名")
        List<String> imageThumbs,
        @Schema(description = "作者摘要（含已签名的头像）")
        UserBriefVO author,
        Integer imageCount,
        Integer likeCount,
        Integer commentCount,
        Integer collectCount,
        LocalDateTime createdAt) {
}
