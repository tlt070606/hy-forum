package com.hyforum.common.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记接口/方法为「<b>登录可选</b>」：未登录也放行，登录了则能拿到真实用户身份。
 *
 * <h2>它解决的问题（H13 / M4 任务书 §5.4）</h2>
 * <p>在本注解出现之前，"登录可选"只有一种写法：端点标 {@link AllowAnonymous}
 * （人人可访问），然后在方法体里显式调 {@code StpUserUtil.currentUserId()} ——
 * 取得到就是登录用户，取不到就是 {@code null}。M3 的帖子详情就是这么写的，
 * 并在类注释里如实登记为"对 {@code CurrentUser} 纪律的一处刻意例外"。</p>
 *
 * <p>那种写法<b>能用但会扩散</b>，而且有两种真实的错法：</p>
 * <ol>
 *   <li><b>静默错法</b>：写成"取不到就当 {@code 0}"。于是匿名用户的 id 变成 0，
 *       而 {@code 0} 在业务上根本不是"未登录"，会被拿去做归属判定（例如
 *       "我的收藏"就查不出东西、或更糟：与某个真实 id 撞上）；</li>
 *   <li><b>重复错法</b>：每个"登录可选"的端点都要自己写一遍"取得到/取不到"的分支，
 *       漏掉一处就是一处越权或一处功能失效。</li>
 * </ol>
 *
 * <p>本注解把这层语义收敛成<b>一处实现、多处声明</b>：
 * 由 {@code AuthInterceptor} 统一解析登录态（含"账号被封禁即时失效"这条既有规则），
 * 业务代码只从 {@link CurrentUser#idOrNull()} 读，未登录时确定地拿到 {@code null}。</p>
 *
 * <h2>与 {@link AllowAnonymous} 的区别（不要混用）</h2>
 * <table>
 *   <caption>三种鉴权模式</caption>
 *   <tr><th>模式</th><th>标注</th><th>拦截器行为</th><th>业务如何取当前用户</th></tr>
 *   <tr><td>完全公开</td><td>{@code @AllowAnonymous}</td>
 *       <td>不解析登录态（连 token 都不看）</td><td>拿不到，也不该拿</td></tr>
 *   <tr><td><b>登录可选</b></td><td>{@code @OptionalLogin}</td>
 *       <td>尝试解析；失败/缺失一律放行，<b>不写身份</b></td>
 *       <td>{@code CurrentUser.idOrNull()}</td></tr>
 *   <tr><td>必须登录</td><td>不标注</td><td>未登录 → 401</td>
 *       <td>{@code CurrentUser.requireId()}</td></tr>
 * </table>
 *
 * <p><b>{@code @OptionalLogin} 优先于 {@code @AllowAnonymous}</b>：两者同时标注时按"可选"处理。
 * 原因：被标成"完全公开"却仍然需要身份的端点是自相矛盾的，
 * 让 {@code @OptionalLogin} 胜出至少能让"谁在看"这个信息不丢（fail-open 到有身份）。
 * 反过来（让匿名胜出）会静默地把已登录用户看成匿名，是更难查的一类 bug。</p>
 *
 * <h2>安全边界（必须清楚，否则会以为它管得比实际多）</h2>
 * <ul>
 *   <li><b>匿名请求带来任何身份</b>：不会。没有 token / 无效 token → 身份为 {@code null}；</li>
 *   <li><b>被封禁的账号</b>：其 token 一旦被解析出来，仍然按"封禁即时生效"处理 →
 *       <b>返回 1004 而不是当成匿名</b>（降级成匿名会让封禁变成一个可绕过的软限制）；</li>
 *   <li><b>它不替代归属校验</b>：写了 {@code @OptionalLogin} 的端点若做写操作，
 *       仍必须自己判断"有没有登录、是不是本人"（如删除评论要 403）。</li>
 * </ul>
 *
 * <p>实现位置见 {@code com.hyforum.common.web.AuthInterceptor}；
 * 测试见 {@code com.hyforum.common.M4OptionalLoginTest}（断言"未登录拿到空用户、
 * 已登录拿到真实用户"，并覆盖上面两条边界）。</p>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
public @interface OptionalLogin {
}
