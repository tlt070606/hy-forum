package com.hyforum.admin.vo;

import java.time.LocalDateTime;

/**
 * 后台邀请码列表项（M6）。
 *
 * @param id            邀请码行 id
 * @param code          邀请码字符串
 * @param status        0 未使用 / 1 已使用 / 2 已失效
 * @param expireAt      过期时间（null = 永不过期）
 * @param usedByUserId  使用者 id（未使用为 null）
 * @param usedAt        使用时间（未使用为 null）
 * @param createdAt     生成时间
 */
public record AdminInviteCodeVO(
        long id,
        String code,
        int status,
        LocalDateTime expireAt,
        Long usedByUserId,
        LocalDateTime usedAt,
        LocalDateTime createdAt) {
}
