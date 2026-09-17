package com.hyforum.interaction.controller;

import com.hyforum.common.api.ApiResponse;
import com.hyforum.common.api.PageResult;
import com.hyforum.common.security.CurrentUser;
import com.hyforum.common.security.OptionalLogin;
import com.hyforum.interaction.service.FeedService;
import com.hyforum.interaction.vo.FeedItemVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 首页双流（docs/技术方案.md §6.5、§8.5）。
 *
 * <p><b>这是全项目第一个用 {@link OptionalLogin} 的端点</b>（任务书 §5.4 允许的唯一
 * {@code common/**} 扩展点）：</p>
 * <ul>
 *   <li>{@code type=all}：未登录也能看全部流；</li>
 *   <li>{@code type=follow}：未登录 → <b>401 引导登录</b>（不返回空列表）。</li>
 * </ul>
 *
 * <p>注意"未登录时拿到空用户"这件事由拦截器保证：{@code CurrentUser.idOrNull()}
 * 确定地返回 {@code null}。业务代码里<b>没有</b>"取不到就当 0"的分支 ——
 * 那正是 H13 记下的静默错法，{@code OptionalLogin} 就是为了不给它出现的形状。</p>
 */
@RestController
@Tag(name = "首页流", description = "关注流 / 全部流")
public class FeedController {

    private final FeedService feedService;

    public FeedController(FeedService feedService) {
        this.feedService = feedService;
    }

    /**
     * 首页流（§6.5）。
     *
     * @param type {@code follow} 或 {@code all}（缺省 all）
     * @param sort {@code latest}（默认，时间倒序）或 {@code hot}（§8.5 的热度入口）
     */
    @GetMapping("/api/feed")
    @OptionalLogin
    @Operation(summary = "首页流",
            description = "type=follow 需登录（未登录返回 401 引导登录）；type=all 公开；分页上限 20")
    public ApiResponse<PageResult<FeedItemVO>> feed(
            @RequestParam(required = false, defaultValue = "all") String type,
            @RequestParam(required = false, defaultValue = "latest") String sort,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        return ApiResponse.ok(feedService.feed(type, sort, CurrentUser.idOrNull(), page, size));
    }
}
