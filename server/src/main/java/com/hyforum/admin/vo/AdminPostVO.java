package com.hyforum.admin.vo;

import java.time.LocalDateTime;

/**
 * 后台帖子列表项（M6）。与前台 {@code PostCardVO} 分开建：后台要看的字段
 * （作者 id、原始状态）与前台卡片（封面、计数、签名 URL）几乎不重叠。
 *
 * @param id             帖子 id
 * @param title          标题
 * @param authorId       作者 id
 * @param authorNickname 作者昵称（批量装配，避免 N+1）
 * @param status         0 待审核 / 1 正常 / 2 已屏蔽
 * @param isTop          1 置顶（M6 批次三补：管理端按钮需要知道当前态）
 * @param isEssence      1 加精
 * @param createdAt      发布时间
 */
public record AdminPostVO(
        long id,
        String title,
        long authorId,
        String authorNickname,
        int status,
        int isTop,
        int isEssence,
        LocalDateTime createdAt) {
}
