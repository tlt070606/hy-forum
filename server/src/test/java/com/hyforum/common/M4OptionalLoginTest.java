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
    @DisplayName("行为保持：未标 @OptionalLogin 的受保护端点，匿名访问仍是 401（挡住「顺手放宽 /api/**」）")
    void optional_login_does_not_widen_protected_endpoints() {
        // ─────────────────────────────────────────────────────────────
        // 这条用例的唯一目的是**挡住装配层的事故**，不是挡业务逻辑。
        //
        // 背景：为让 @OptionalLogin 生效，拦截器里加了一个新分支
        // （`if (isOptionalLogin(handler)) { resolveOptionalUser(); return true; }`）。
        // 这类"在拦截器里多一条 return true"的改动，最危险的错法不是"没生效"，
        // 而是**顺手把别的端点也放行了** —— 例如判断条件写成 `!hasAnnotation(AllowAnonymous)`
        // 这样的宽条件、或把分支提到 isAnonymous 之前却不检查注解。
        // 那种错法**不会让任何既有用例变红**（既有用例大多带着 token 跑），
        // 却会让所有受保护端点变成匿名可访问 —— 典型的"静默越权"。
        //
        // 因此这里点名三个**必须仍然 401** 的端点，其中 ② 是另一个 agent 的断言
        // 依赖的那个（/api/oss/signature 刻意不加 @AllowAnonymous，理由是
        // "签名的滥用面是匿名刷签名 + 刷 OSS 流量"）。
        // ─────────────────────────────────────────────────────────────

        // ① 未标注解的前台端点（M4 自己的写接口，归 M4 负责）
        Response collections = io.restassured.RestAssured.given().get("/api/user/collections");
        assertThat(collections.statusCode())
                .as("未标 @OptionalLogin 的前台端点匿名访问必须仍 401。响应：%s", collections.asString())
                .isEqualTo(401);
        assertThat(collections.jsonPath().getInt("code")).isEqualTo(401);

        // ② M3 的签名接口 —— 另一个 agent 有一条断言依赖它必须 401
        Response signature = io.restassured.RestAssured.given().get("/api/oss/signature");
        assertThat(signature.statusCode())
                .as("GET /api/oss/signature 匿名访问必须仍然 401（它刻意不加 @AllowAnonymous）。响应：%s",
                        signature.asString())
                .isEqualTo(401);
        assertThat(signature.jsonPath().getInt("code")).isEqualTo(401);

        // ③ 后台端点：@OptionalLogin 不该碰 /api/admin（后台没有"匿名也能看"的语义）
        Response admin = io.restassured.RestAssured.given().get("/api/admin/stats");
        assertThat(admin.statusCode())
                .as("后台端点匿名访问必须仍被拒（绝不能因为 @OptionalLogin 而放行）。响应：%s", admin.asString())
                .isIn(401, 403, 404);   // 接受 404：该端点若尚未实现，不该因此判红

        // ④ 反向自证：**同一次运行里**，标了 @OptionalLogin 的端点匿名访问必须是 200。
        //    缺了这一步，上面三条 401 可能只是因为"拦截器把所有人都拦了" ——
        //    那样 @OptionalLogin 根本没生效，而三条 401 照样绿。
        Response optional = getUserProfile(null, other.id());
        assertThat(optional.statusCode())
                .as("标了 @OptionalLogin 的端点匿名访问必须 200（否则上面的 401 可能只是'全都拦了'）")
                .isEqualTo(200);
        assertOk(optional);
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
