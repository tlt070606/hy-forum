package com.hyforum.post.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 帖子列表项（docs/ops/deployment.md §5：列表<b>只返回摘要</b>）。
 *
 * <p><b>这个 VO 里没有 {@code content}，这是刻意的</b>：正文是 TEXT（单帖可达数 KB），
 * 而列表页一个字都不显示它的正文。一页 20 条就是几十上百 KB 的纯浪费 ——
 * 在 2 核 2G 的单机上，带宽与序列化成本都要算进去。
 * 对应验收项 {@code M3_list_returns_summary_only}（断言"报文里没有 content 这个 key"，
 * 而不是"content 为 null"：字段存在但为 null 同样占报文、同样会误导前端）。</p>
 *
 * <h2>CR-L：列表的图只给"最多 3 张缩略图"，<b>刻意不给网盘链接</b></h2>
 * <p>{@code imageThumbs} 最多 3 张，是因为列表每页 20 条：给 9 张就是
 * <b>每页 180 个 URL 都要逐条读时签名</b>，载荷与签名成本都被放大 3 倍，
 * 而信息流里第 4 张之后本来就看不清。前 3 张 + 总数（{@code imageCount}）
 * 是信息流的标准做法，卡片按"3 图网格 + 角标 +N"渲染。</p>
 * <p><b>不发 {@code diskUrl}／{@code diskCode}</b>：这是 L1 的<b>产品性裁决</b>，不是技术限制 ——
 * 网盘链接是资源帖的核心价值，放在列表就等于把用户截留在列表页，
 * 而且列表载荷会变重（还要连带处理提取码语义）。
 * 列表只给 {@code diskType} 与 {@code hasDiskResource}（前端据此渲染"资源"标记），
 * <b>"复制链接"这个动作放详情页</b>（那里有完整信息与提取码，也是唯一合理的复制位置）。</p>
 *
 * @param id              帖子 id
 * @param boardId         版块 id
 * @param boardName       版块名（列表要显示，避免前端再查一次版块表）
 * @param title           标题
 * @param coverUrl        封面 URL = <b>首图缩略图</b>（原图只出现在详情页）
 * @param imageCount      图片数（前端据此决定是否显示九宫格角标与 "+N"）
 * @param imageThumbs     前 <b>最多 3 张</b>缩略图 URL（读时签名，与详情一致）；
 *                        无图时为<b>空数组</b>（不是 null：null 会逼前端写两套判断）
 * @param diskType        网盘类型 1–6；非资源帖为 null（CR-L：列表只给类型与下面的布尔标记）
 * @param hasDiskResource 是否带网盘资源（= 有 {@code diskUrl}）。前端据此渲染"资源"标记，
 *                        而<b>不需要</b>拿到链接本身 —— 链接只在详情页给
 * @param liked           <b>当前查看者</b>是否点过赞；未登录一律 {@code false}（CR-K）
 * @param collected       <b>当前查看者</b>是否收藏过；未登录一律 {@code false}（CR-K）
 * @param isTop           是否置顶
 * @param isEssence       是否加精
 * @param viewCount       浏览量（<b>允许滞后 ≤5 分钟</b>，见 PostViewCounter 的取舍说明）
 * @param likeCount       点赞数
 * @param commentCount    评论数
 * @param collectCount    收藏数
 * @param author          作者摘要
 * @param createdAt       发布时间
 */
@Schema(name = "PostSummaryVO", description = "帖子列表项（不含正文）")
public record PostSummaryVO(
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
        @Schema(description = "是否带网盘资源（前端据此渲染资源标记；"
                + "链接本身只在详情页给，列表刻意不发 diskUrl）")
        Boolean hasDiskResource,
        @Schema(description = "当前请求者是否点过赞（相对于请求者，未登录恒为 false；"
                + "未登录不会因此报 401）")
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
     * 带出<b>请求者视角字段</b>的副本：{@code liked} / {@code collected} / {@code imageThumbs}。
     *
     * <p><b>为什么做成"复制一份"而不是让 Service 直接填</b>（这是刻意的架构选择，不是偷懒）：</p>
     * <ul>
     *   <li>{@code liked}/{@code collected} 要查互动关系表（{@code post_like}/{@code post_collect}），
     *       而那两个 Mapper 属于 {@code com.hyforum.interaction} 模块。
     *       <b>铁律 3 + ArchUnit 的 {@code ARCH_no_cross_module_dependency} 禁止业务包互相依赖</b> ——
     *       所以 {@code PostService} 里<b>不能</b>注入 {@code InteractionService}；</li>
     *   <li>而"当前请求者是谁"这件事本来就属于<b>接入层</b>的知识（{@code @OptionalLogin} →
     *       {@code CurrentUser.idOrNull()}），把它放在 Controller 才是最自然的位置；</li>
     *   <li>这样 {@code PostService} 完全不感知"请求者"，它的职责保持"把帖子查出来"。</li>
     * </ul>
     *
     * <p>记录是 immutable 的，所以只能复制 —— 这反而是好事：不可能出现
     * "某条已被下游引用的 VO 被就地改掉"这类难查的共享可变状态问题。</p>
     *
     * @param liked        请求者是否点过赞（未登录传 {@code false}）
     * @param collected    请求者是否收藏过（未登录传 {@code false}）
     * @param imageThumbs  前最多 3 张缩略图（无图传空列表，<b>不要传 null</b>）
     */
    public PostSummaryVO withViewerState(Boolean liked, Boolean collected, List<String> imageThumbs) {
        return new PostSummaryVO(
                id, boardId, boardName, title, coverUrl, imageCount,
                // 归一化：null 一律落成空数组，避免前端写两套判断（契约里已写明"无图为空数组"）
                imageThumbs == null ? List.of() : imageThumbs,
                diskType,
                // hasDiskResource 与 diskUrl 是同一个事实的两面，而 diskUrl 不在列表里 ——
                // 因此它由能读到实体的一方算好传进来（见 PostService.toSummaries）
                hasDiskResource,
                // 未登录时这里是 false（不是 null）：契约 §13.1 明确"未登录一律 false"
                liked != null && liked,
                collected != null && collected,
                isTop, isEssence, viewCount, likeCount, commentCount, collectCount,
                author, createdAt);
    }
}
