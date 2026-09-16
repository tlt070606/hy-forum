package com.hyforum.post.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 帖子详情（docs/技术方案.md §6.5 {@code GET /api/posts/{id}}）。
 *
 * <p>发帖（{@code POST}）与改帖（{@code PUT}）成功时也返回本形状：前端拿到响应即可直接渲染详情页，
 * 不必再发一次 GET —— 而<b>那一次多余的 GET 会平白 +1 浏览量</b>（§8.3 的计数口径是"访问详情即 +1"）。</p>
 *
 * <p>{@code status} 是刻意暴露的：在「先发后审」模式下，作者需要知道自己的帖子正在待审
 * （{@code 0}），否则他只会看到"帖子发出去了但列表里找不到"这种无从解释的现象。</p>
 *
 * @param id              帖子 id
 * @param boardId         版块 id
 * @param boardName       版块名
 * @param boardIsResource 是否资源版块（前端据此显示网盘卡片；非资源版块时网盘字段必为 null）
 * @param title           标题
 * @param content         正文（列表接口不返回它，见 {@link PostSummaryVO}）
 * @param coverUrl        封面 URL = 首图缩略图
 * @param imageCount      图片数
 * @param images          图片列表（<b>已过滤掉 {@code audit_status=2} 的违规图</b>，见 CR-006）
 * @param diskType        网盘类型 1–6；非资源版块或未填时为 null
 * @param diskUrl         网盘链接（<b>已归一化：不含 {@code pwd} 参数</b>，ADR-0008）
 * @param diskCode        提取码，可为 null（阿里云盘/夸克无提取码机制）
 * @param viewCount       浏览量（数据库值 + Redis 未回写增量，见 §8.3）
 * @param likeCount       点赞数
 * @param commentCount    评论数
 * @param collectCount    收藏数
 * @param isTop           是否置顶
 * @param isEssence       是否加精
 * @param status          0 待审核 / 1 正常 / 2 已屏蔽
 * @param author          作者摘要
 * @param createdAt       发布时间
 * @param updatedAt       最后更新时间
 */
@Schema(name = "PostDetailVO", description = "帖子详情")
public record PostDetailVO(
        Long id,
        Long boardId,
        String boardName,
        @JsonProperty("boardIsResource")
        Boolean boardIsResource,
        String title,
        String content,
        String coverUrl,
        Integer imageCount,
        List<PostImageVO> images,
        Integer diskType,
        String diskUrl,
        String diskCode,
        Integer viewCount,
        Integer likeCount,
        Integer commentCount,
        Integer collectCount,
        @JsonProperty("isTop")
        Boolean isTop,
        @JsonProperty("isEssence")
        Boolean isEssence,
        @Schema(description = "0 待审核 / 1 正常 / 2 已屏蔽")
        Integer status,
        UserBriefVO author,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
