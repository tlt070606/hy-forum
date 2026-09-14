package com.hyforum.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 登录请求（docs/技术方案.md §6.2）：{@code username、password}。
 *
 * <p>刻意<b>不含</b>验证码字段：§6.2 的登录参数只有这两个。登录的防刷由 §8.7
 * 「同一 IP ≤ 10 次/分钟」与登录失败锁定（P1-9，尚未结案）承担，
 * 不在这里私自加一个契约外的必填项 —— 那会让前端与文档不一致。</p>
 *
 * @param username 登录名
 * @param password 明文密码（WRITE_ONLY，不参与序列化）
 */
public record LoginRequest(
        @NotBlank(message = "用户名不能为空")
        String username,

        @NotBlank(message = "密码不能为空")
        @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY)
        String password) {
}
