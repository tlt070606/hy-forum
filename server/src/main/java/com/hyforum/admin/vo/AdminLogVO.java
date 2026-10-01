package com.hyforum.admin.vo;

import java.time.LocalDateTime;

/**
 * 后台操作留痕列表项（M6）。字段与 {@code admin_operation_log} 表一一对应，
 * 另补 {@code adminUsername}（批量装配，让"谁做的"直接可读，不用拿 id 再查一次）。
 */
public record AdminLogVO(
        long id,
        long adminId,
        String adminUsername,
        String action,
        Integer targetType,
        Long targetId,
        String reason,
        String detail,
        String ip,
        LocalDateTime createdAt) {
}
