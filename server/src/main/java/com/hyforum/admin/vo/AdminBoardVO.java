package com.hyforum.admin.vo;

import java.time.LocalDateTime;

/**
 * 后台版块列表项（M6 批次二）。与前台 {@code BoardVO} 分开：后台要看到
 * 停用版块与 post_count 管理口径，前台只出启用版块的展示字段。
 */
public record AdminBoardVO(
        long id,
        String name,
        String slug,
        String description,
        int isResource,
        int sort,
        int status,
        int postCount,
        LocalDateTime createdAt) {
}
