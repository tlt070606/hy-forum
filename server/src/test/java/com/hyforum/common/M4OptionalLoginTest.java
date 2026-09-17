package com.hyforum.common;

import com.hyforum.interaction.M4ApiTestSupport;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @OptionalLogin} 的语义测试（任务书 §5.4 明确要求："写一条测试证明
 * <b>未登录时拿到空用户、已登录时拿到真实用户</b> —— 不要让'取不到就当 0'
 * 这种静默错法有机会存在"）。
 *
 * <h2>为什么这条用例值得单独存在</h2>
 * <p>在 {@code @OptionalLogin} 出现之前，"登录可选"的写法是
 * {@code @AllowAnonymous} + 方法内自己 {@code StpUserUtil.currentUserId()}（H13）。
 * 那种写法有两种真实的错法，而<b>两种都能通过"接口返回 200"这类粗断言</b>：</p>
 * <ol>
 *   <li>把取不到的用户当成 {@code 0}（或 {@code -1}、或字符串 {@code "null"}）
 *       —— 匿名用户于是有了一个"身份"，所有归属判定都开始指向一个不存在的用户；</li>
 *   <li>把"未登录"与"未关注"压成同一个响应值 —— 前端再也分不清该弹登录框
 *       还是显示关注按钮。</li>
 * </ol>
 * <p>因此本类<b>不打底层方法</b>，全部走真实 HTTP + 真实拦截器链：
 * 只有在拦截器与 Controller 都真的按约定工作时，这两个语义才会同时成立。</p>
 *
 * <p><b>路径选择</b>：{@code GET /api/users/{id}} 与 {@code GET /api/feed} 都用
 * {@code @OptionalLogin}，本类用前者做主要探针（它的响应里有明确的布尔/空值字段），
 * 用它做反证（{@code type=follow} 未登录必须 401 —— 证明"可选"≠"免鉴权"）。</p>
 */
class M4OptionalLoginTest extends M4ApiTestSupport {

    private TestUser viewer;
    private TestUser other;

    @BeforeEach
    void seed() {
        viewer = createUser("观察者");
        other = createUser("被观察者");
    }

    @Test
    @DisplayName("未登录：CurrentUser 为空 → 关注状态是 null（不是 false、也不是 0）")
    void optional_login_anonymous_gets_null_user() {
        Response response = getUserProfile(null, other.id());

        assertOk(response);
        // 未登录时必须是 null：false 会把"没登录"渲染成"没关注"
        assertThat((Object) response.jsonPath().get("data.isFollowing"))
                .as("未登录时 isFollowing 必须是 null。响应：%s", response.asString())
                .isNull();
        assertThat((Object) response.jsonPath().get("data.isFollowedBy"))
                .as("未登录时 isFollowedBy 必须是 null")
                .isNull();

        // "取不到就当 0" 的错法会在响应里留下痕迹：某个 id 字段变成 0。
        // 这里显式断言"该用户的 id 不是 0"，把那种错法钉死
        assertThat(response.jsonPath().getLong("data.id"))
                .as("被查看用户的 id 绝不能因为'当前用户为空'而被替换成 0")
                .isEqualTo(other.id());

        // 未登录访问**关注流**必须仍然 401：可选登录不等于免鉴权
        Response followFeed = getFeed(null, "follow");
        assertThat(followFeed.statusCode())
                .as("同一套 OptionalLogin 下的关注流必须仍要求登录（否则'可选'被实现成了'免鉴权'）")
                .isEqualTo(401);
    }

    @Test
    @DisplayName("已登录：CurrentUser 为真实用户 → 关注状态是真实布尔值")
    void optional_login_authenticated_gets_real_user() {
        // 先建立一段真实关系，好让"真实用户"与"空用户"的差别可被观察
        assertOk(followUser(viewer.token(), other.id()));

        Response asViewer = getUserProfile(viewer.token(), other.id());
        assertOk(asViewer);
        assertThat((Object) asViewer.jsonPath().get("data.isFollowing"))
                .as("已登录且已关注时必须是 true（而不是 null）")
                .isEqualTo(true);
        assertThat((Object) asViewer.jsonPath().get("data.isFollowedBy"))
                .as("对方没关注我 → false（这里 true/false/null 三态必须都能出现）")
                .isEqualTo(false);

        // 换一个没关注的人来看：必须是 false（与"未登录的 null"可区分）
        TestUser third = createUser("第三人");
        Response asThird = getUserProfile(third.token(), other.id());
        assertOk(asThird);
        assertThat((Object) asThird.jsonPath().get("data.isFollowing"))
                .as("已登录但没关注 → false；若这里也是 null，说明拦截器根本没解析登录态")
                .isEqualTo(false);

        // 未登录看同一个主页：必须是 null（同一 URL、同一份数据，只有身份不同）
        Response anonymous = getUserProfile(null, other.id());
        assertOk(anonymous);
        assertThat((Object) anonymous.jsonPath().get("data.isFollowing"))
                .as("同一主页在未登录时必须回到 null —— 三态在同一用例里被完整覆盖")
                .isNull();
    }

    @Test
    @DisplayName("边界：坏 token 按匿名处理（不 500）；封禁账号仍然 1004（不降级成匿名）")
    void optional_login_boundaries() {
        // ① 无效 token：按匿名处理。若实现成"解析失败就抛异常"，这里会变成 500 或 401
        Response badToken = io.restassured.RestAssured.given()
                .header("Authorization", "not-a-real-token-1234567890")
                .get("/api/users/" + other.id());
        assertThat(badToken.statusCode())
                .as("坏 token 打到 OptionalLogin 端点必须按匿名放行（200），而不是 401/500。响应：%s",
                        badToken.asString())
                .isEqualTo(200);
        assertOk(badToken);
        assertThat((Object) badToken.jsonPath().get("data.isFollowing"))
                .as("坏 token 不能凭空解析出一个用户")
                .isNull();

        // ② 被封禁的账号：即便带了有效 token，也必须 1004 —— **不得**降级成匿名。
        //    降级会把"封禁"变成可绕过的软限制：封了号还能匿名看内容，且日志里看不出他来过。
        assertOk(followUser(viewer.token(), other.id()));
        jdbcTemplate.update("UPDATE `user` SET status = 0 WHERE id = ?", viewer.id());
        try {
            Response banned = getUserProfile(viewer.token(), other.id());
            assertThat(banned.jsonPath().getInt("code"))
                    .as("封禁账号的 token 必须被拒（1004），而不是被当成匿名用户。响应：%s", banned.asString())
                    .isEqualTo(1004);
        } finally {
            // 恢复，避免影响同一次运行里的其它用例（清表虽会重置，但显式恢复更稳）
            jdbcTemplate.update("UPDATE `user` SET status = 1 WHERE id = ?", viewer.id());
        }
    }
}
