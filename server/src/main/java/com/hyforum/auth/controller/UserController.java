package com.hyforum.auth.controller;

import com.hyforum.auth.service.AuthService;
import com.hyforum.common.api.ApiResponse;
import com.hyforum.domain.user.vo.UserVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户接口（docs/技术方案.md §6.3）。
 *
 * <p>M1 只实现契约表格里的第一行 {@code GET /api/user/me} —— 它是里程碑验收标准
 * 「Knife4j 中可完成 注册 → 登录 → 获取 /api/user/me」的最后一环。
 * 其余四行（{@code PUT /api/user/me}、{@code GET /api/users/{id}}、
 * {@code GET /api/users/{id}/posts}、{@code /api/user/collections}）分别依赖
 * 资料编辑、个人主页、帖子与收藏列表，属于 M3/M4 的范围，本里程碑不提前实现
 * （提前实现等于用还没有契约支撑的私有形状占坑）。</p>
 *
 * <p>归属说明：本类放在 {@code com.hyforum.auth} 包内而不是新建 {@code user} 包 ——
 * 技术方案 §3.3 v1.8 定死了七个业务包，没有 {@code user} 包；{@code /api/user/**}
 * 的"当前登录人自助信息"与认证强相关（依赖登录态），因此归 auth。
 * 该归属已在交付报告中登记，供 L1 在 §2 所有权表里确认。</p>
 */
@RestController
@RequestMapping("/api/user")
@Tag(name = "用户", description = "当前登录用户信息")
public class UserController {

    private final AuthService authService;

    public UserController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 当前登录用户信息（§6.3，需要登录）。
     *
     * <p>不加 {@code @AllowAnonymous}：拦截器会校验前台登录态，未登录返回 401。</p>
     */
    @GetMapping("/me")
    @Operation(summary = "当前登录用户信息", description = "需要登录；用户 id 取自登录态，不接受前端传参")
    public ApiResponse<UserVO> me() {
        return ApiResponse.ok(authService.currentUser());
    }
}
