package com.hyforum.admin.vo;

import java.time.LocalDateTime;

/**
 * 后台举报列表项（M6 批次二）。
 *
 * @param id               举报行 id
 * @param targetType       1 帖子 / 2 评论 / 3 用户
 * @param targetId         被举报对象 id
 * @param targetSummary    被举报对象摘要（帖子标题 / 评论内容前 50 字 / 用户名）；
 *                         对象已删除或不存在时为"(已删除)"——处置时能看见背景
 * @param reporterId       举报人 id
 * @param reporterNickname 举报人昵称
 * @param reasonType       1 违法违规 2 色情低俗 3 广告垃圾 4 侵权 5 其他
 * @param reasonDetail     举报人补充说明
 * @param status           0 待处理 / 1 已处理 / 2 已驳回
 * @param handleNote       处置说明（处置时必填，C9）
 * @param handledAt        处置时间
 * @param createdAt        举报时间
 */
public record AdminReportVO(
        long id,
        int targetType,
        long targetId,
        String targetSummary,
        long reporterId,
        String reporterNickname,
        int reasonType,
        String reasonDetail,
        int status,
        String handleNote,
        LocalDateTime handledAt,
        LocalDateTime createdAt) {
}
