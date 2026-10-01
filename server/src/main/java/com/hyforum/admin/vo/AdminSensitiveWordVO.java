package com.hyforum.admin.vo;

import java.time.LocalDateTime;

/**
 * 后台敏感词列表项（M6 批次二）。
 */
public record AdminSensitiveWordVO(
        long id,
        String word,
        LocalDateTime createdAt) {
}
