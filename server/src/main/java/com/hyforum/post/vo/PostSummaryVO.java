package com.hyforum.post.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 帖子列表项（docs/ops/deployment.md §5：列表<b>只返回摘要</b>）。
 *
 * <p><b>这个 VO 里没有 {@code content}，这是刻意的</b>：正文是 TEXT（单帖可达数 KB），
 * 而列表页一个字都不显示它的正文。一页 20 条就是几十上百 KB 的纯浪费 ——
 * 在 2 核 2G 的单机上，带宽与序列化成本都要算进去。
 * 对应验收项 {@code M3_list_returns_summary_only}（断言"报文里没有 content 这个 key"，
 * 而不是"content 为 null"：字段存在但为 null 同样占报文、同样会误导前端）。</p>
 *
 * @param id           帖子 id
 * @param boardId      版块 id
 * @param boardName    版块名（列表要显示，避免前端再查一次版块表）
 * @param title        标题
 * @param coverUrl     封面 URL = <b>首图缩略图</b>（原图只出现在详情页）
 * @param imageCount   图片数（前端据此决定是否显示九宫格角标）
 * @param isTop        是否置顶
 * @param isEssence    是否加精
 * @param viewCount    浏览量（<b>允许滞后 ≤5 分钟</b>，见 PostViewCounter 的取舍说明）
 * @param likeCount    点赞数
 * @param commentCount 评论数
 * @param collectCount 收藏数
 * @param author       作者摘要
 * @param createdAt    发布时间
 */
@Schema(name = "PostSummaryVO", description = "帖子列表项（不含正文）")
public record PostSummaryVO(
        Long id,
        Long boardId,
        String boardName,
        String title,
        String coverUrl,
        Integer imageCount,
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
}
