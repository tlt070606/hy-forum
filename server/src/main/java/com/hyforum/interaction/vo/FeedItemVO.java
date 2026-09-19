package com.hyforum.interaction.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 首页流条目（§6.5 {@code GET /api/feed} 的 {@code type=follow|all}）
 * 与个人主页的帖子列表（§6.3 {@code GET /api/users/{id}/posts}）。
 *
 * <p>不带 {@code content}：与 {@code PostSummaryVO} 同一个理由（正文是 TEXT，
 * 列表页一个字都不显示它）。字段是"列表页真的会画出来"的那一组 ——
 * 首页要显示版块名与作者，因此带上 {@code boardName} 与 {@code author}。</p>
 *
 * <h2>为什么这里也要有 CR-K / CR-L 的那几个字段</h2>
 * <p>CR-K 的诉求是"<b>界面在说谎</b>"：刷新后卡片只能显示未点赞/未收藏。
 * 而首页流与个人主页的卡片<b>和 {@code /api/posts} 的卡片是同一个界面组件</b> ——
 * 若只在 {@code PostSummaryVO} 上补 {@code liked}/{@code collected}，
 * 同一个帖子会在首页显示"未点赞"、点进版块列表却显示"已点赞"。
 * 那正是 CR-K 要消灭的那种谎，只是从"刷新后丢状态"换成了"换个入口丢状态"。
 * 因此本 VO 与 {@code PostSummaryVO} 保持同一口径（同样由 Controller 层补齐）。</p>
 *
 * <p><b>与 {@code PostSummaryVO} 的关系</b>：两者形状高度相近，但那个类在
 * {@code com.hyforum.post.vo}，而 {@code interaction} 包不得依赖 {@code post} 包
 * （铁律 3 + ArchUnit）。已在交付报告中提 CR，建议把这类"跨模块共用的视图形状"
 * 收敛到 {@code domain} 下的公共 VO。</p>
 *
 * @param id              帖子 id
 * @param boardId         版块 id
 * @param boardName       版块名
 * @param title           标题
 * @param coverUrl        封面（首图缩略图，读时签名）
 * @param imageCount      图片数
 * @param imageThumbs     CR-L：前最多 3 张缩略图（读时签名）；无图时空数组
 * @param diskType        CR-L：网盘类型 1–6；非资源帖为 null
 * @param hasDiskResource CR-L：是否带网盘资源（链接本身只在详情页给）
 * @param liked           CR-K：当前请求者是否点过赞；未登录恒 false
 * @param collected       CR-K：当前请求者是否收藏过；未登录恒 false
 * @param isTop           是否置顶
 * @param isEssence       是否加精
 * @param viewCount       浏览量
 * @param likeCount       点赞数
 * @param commentCount    评论数
 * @param collectCount    收藏数
 * @param author          作者摘要
 * @param createdAt       发布时间
 */
@Schema(name = "FeedItemVO", description = "首页流 / 个人主页的帖子卡片（不含正文）")
public record FeedItemVO(
        Long id,
        Long boardId,
        String boardName,
        String title,
        String coverUrl,
        Integer imageCount,
        @Schema(description = "前最多 3 张缩略图（读时签名）；无图时为空数组")
        List<String> imageThumbs,
        @Schema(description = "网盘类型 1 百度 / 2 阿里 / 3 夸克 / 4 天翼 / 5 迅雷 / 6 其他；非资源帖为 null")
        Integer diskType,
        @Schema(description = "是否带网盘资源（前端据此渲染资源标记；列表刻意不发 diskUrl）")
        Boolean hasDiskResource,
        @Schema(description = "当前请求者是否点过赞（相对于请求者，未登录恒为 false；未登录不会因此报 401）")
        Boolean liked,
        @Schema(description = "当前请求者是否收藏过（相对于请求者，未登录恒为 false）")
        Boolean collected,
        @JsonProperty("isTop")
        Boolean isTop,
        @JsonProperty("isEssence")
        Boolean isEssence,
        Integer viewCount,
        Integer likeCount,
        Integer commentCount,
        Integer collectCount,
        UserBriefVO author,
        LocalDateTime createdAt) {

    /**
     * 带出请求者视角字段的副本（口径与 {@code PostSummaryVO#withViewerState} 完全一致）。
     *
     * @param liked       请求者是否点过赞（未登录传 {@code false}）
     * @param collected   请求者是否收藏过（未登录传 {@code false}）
     * @param imageThumbs 前最多 3 张缩略图（无图传空列表，<b>不要传 null</b>）
     */
    public FeedItemVO withViewerState(Boolean liked, Boolean collected, List<String> imageThumbs) {
        return new FeedItemVO(
                id, boardId, boardName, title, coverUrl, imageCount,
                imageThumbs == null ? List.of() : imageThumbs,
                diskType, hasDiskResource,
                liked != null && liked,
                collected != null && collected,
                isTop, isEssence, viewCount, likeCount, commentCount, collectCount,
                author, createdAt);
    }
}
