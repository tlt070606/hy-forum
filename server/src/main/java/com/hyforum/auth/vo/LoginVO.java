package com.hyforum.auth.vo;

import com.hyforum.domain.user.vo.UserVO;

/**
 * 登录成功返回（docs/技术方案.md §6.2：登录"返回 token 与用户信息"）。
 *
 * <p>顺带承载注册后自动登录的返回结构 —— 注册接口的契约只写了"参数"，未写返回体，
 * 本实现选择返回"用户信息 + 不下发 token"，让前端注册成功后走正常登录流程：
 * 这样注册与登录的鉴权路径只有一条，不需要维护"注册时顺带发 token"的第二条路径。</p>
 *
 * @param token 登录态 token（请求头 {@code Authorization: {token}}）
 * @param user  当前用户信息
 */
public record LoginVO(String token, UserVO user) {
}
