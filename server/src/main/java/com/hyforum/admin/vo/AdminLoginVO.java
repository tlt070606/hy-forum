package com.hyforum.admin.vo;

/**
 * 管理员登录返回（docs/技术方案.md §6.11：{@code POST /api/admin/login}）。
 *
 * <p>与前台 {@code LoginVO} 刻意分开：后台的字段集与前端渲染需求不同，
 * 且后台 token 与前台 token 属于两套隔离的登录态（§9），
 * 共用一个 VO 会诱导后续把两者混用。</p>
 *
 * @param token     后台登录态 token（请求头 {@code Authorization: {token}}）
 * @param adminId   管理员 id
 * @param nickname  管理员昵称（后台右上角展示）
 * @param role      角色：SUPER_ADMIN / ADMIN
 */
public record AdminLoginVO(String token, Long adminId, String nickname, String role) {
}
