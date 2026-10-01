package com.hyforum.admin.vo;

import java.time.LocalDateTime;

/**
 * 后台用户列表项（M6）。刻意<b>不含</b> email / bio / 头像：
 * 后台列表只回答"这个人是谁、什么状态"，字段最小化（数据最小化原则，
 * 与 {@code UserVO} 不含 passwordHash 同一思路）。
 *
 * @param id        用户 id
 * @param username  登录名
 * @param nickname  昵称
 * @param status    1 正常 / 0 封禁
 * @param postCount 发帖数（冗余计数，只读展示）
 * @param createdAt 注册时间
 */
public record AdminUserVO(
        long id,
        String username,
        String nickname,
        int status,
        int postCount,
        LocalDateTime createdAt) {
}
