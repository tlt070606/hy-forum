package com.hyforum.interaction;

import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M4 关注与首页双流：关注幂等、禁止关注自己、关注流过滤、全部流排序、未登录引导登录。
 *
 * <p>对应验收项（任务书 §7.1）：{@code M4_follow_idempotent}、
 * {@code M4_follow_self_rejected}、{@code M4_feed_follow_only}、
 * {@code M4_feed_all_ordered_by_time}、{@code M4_feed_follow_requires_login}。</p>
 *
 * <h2>造数为什么显式指定 {@code created_at}</h2>
 * <p>排序断言不能依赖"连续插入的时间一定递增"：{@code DATETIME} 的精度是<b>秒</b>，
 * 同一秒内插入的多条帖子在 MySQL 里的相对顺序不确定 —— 那种用例会偶发红，
 * 而偶发红比必红难查得多。因此这里一律用"现在 - N 秒"把时间拉开。</p>
 */
class M4FeedAndFollowTest extends M4ApiTestSupport {

    private TestUser me;
    private TestUser followed;
    private TestUser stranger;
    private long boardId;

    @BeforeEach
    void seed() {
        me = createUser("我");
        followed = createUser("我关注的人");
        stranger = createUser("陌生人");
        boardId = createBoard();
    }

    // ==================================================================
    // 关注幂等与自我关注
    // ==================================================================

    @Test
    @DisplayName("M4_follow_idempotent：重复关注 → follow 行数=1、双方计数各 +1 一次")
    void M4_follow_idempotent() {
        // 连续关注 3 次（外加一次"取消后再关注"，覆盖计数回退后重新加一次）
        assertOk(followUser(me.token(), followed.id()));
        assertOk(followUser(me.token(), followed.id()));
        assertOk(followUser(me.token(), followed.id()));

        assertThat(countRows("follow", "user_id", me.id()))
                .as("重复关注不得产生多行关系（uk_follow 唯一索引）")
                .isEqualTo(1);
        assertThat(userColumn(me.id(), "follow_count", Integer.class))
                .as("我的 follow_count 必须恰好 1（我关注了 1 个人）")
                .isEqualTo(1);
        assertThat(userColumn(followed.id(), "fans_count", Integer.class))
                .as("他的 fans_count 必须恰好 1（只有我一个人关注他）")
                .isEqualTo(1);

        // 取消 → 两侧各减 1；重复取消不再减（幂等且不为负）
        assertOk(unfollowUser(me.token(), followed.id()));
        assertOk(unfollowUser(me.token(), followed.id()));
        assertOk(unfollowUser(me.token(), followed.id()));
        assertThat(countRows("follow", "user_id", me.id())).isZero();
        assertThat(userColumn(me.id(), "follow_count", Integer.class)).isZero();
        assertThat(userColumn(followed.id(), "fans_count", Integer.class)).isZero();

        // 取消后再关注 → 恰好回到 1（证明计数走的是"增减"而不是"累加不重置"）
        assertOk(followUser(me.token(), followed.id()));
        assertThat(userColumn(me.id(), "follow_count", Integer.class)).isEqualTo(1);
        assertThat(userColumn(followed.id(), "fans_count", Integer.class)).isEqualTo(1);

        // 反向自证：关注别人才会让"我关注的人数"再 +1（证明上一步的 1 不是碰巧）
        assertOk(followUser(me.token(), stranger.id()));
        assertThat(userColumn(me.id(), "follow_count", Integer.class)).isEqualTo(2);
        assertThat(userColumn(stranger.id(), "fans_count", Integer.class)).isEqualTo(1);
        assertThat(userColumn(followed.id(), "fans_count", Integer.class))
                .as("关注另一个人不得影响前一个被关注者的粉丝数")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("M4_follow_self_rejected：关注自己 → 明确拒绝（400）且不落任何关系行")
    void M4_follow_self_rejected() {
        Response response = followUser(me.token(), me.id());

        assertThat(response.statusCode())
                .as("关注自己必须被拒绝。响应：%s", response.asString())
                .isEqualTo(400);
        assertThat(response.jsonPath().getInt("code"))
                .as("错误码应为 400（参数错误：这个请求本身不成立），而不是 403/404")
                .isEqualTo(400);

        // 关键：不能只是"返回了错误"—— 数据库里一个字都不该变
        assertThat(countRows("follow", "user_id", me.id()))
                .as("自我关注不得落库（follow 表没有 user_id <> target_user_id 的 CHECK，"
                        + "所以这条只能靠代码拦住）")
                .isZero();
        assertThat(userColumn(me.id(), "follow_count", Integer.class)).isZero();
        assertThat(userColumn(me.id(), "fans_count", Integer.class)).isZero();
    }

    // ==================================================================
    // 关注流
    // ==================================================================

    @Test
    @DisplayName("M4_feed_follow_only：关注流只含已关注用户的帖子，并过滤待审帖与已删帖")
    void M4_feed_follow_only() {
        // 我关注的人：一条正常帖 + 一条待审帖(status=0) + 一条已删帖
        long followedNormal = createPostAt(boardId, followed.id(), "关注者-正常帖", 1, 10);
        long followedPending = createPostAt(boardId, followed.id(), "关注者-待审帖", 0, 20);
        long followedBlocked = createPostAt(boardId, followed.id(), "关注者-屏蔽帖", 2, 30);

        // 陌生人：正常帖（不该出现）
        long strangerPost = createPostAt(boardId, stranger.id(), "陌生人-正常帖", 1, 15);
        // 我自己：正常帖（不该出现 —— 关注流是"我关注的人"，不含自己）
        long myPost = createPostAt(boardId, me.id(), "我自己-正常帖", 1, 5);

        // 造一条"已删帖"：先建再逻辑删除
        long deletedPost = createPostAt(boardId, followed.id(), "关注者-已删帖", 1, 1);
        jdbcTemplate.update("UPDATE post SET is_deleted = 1 WHERE id = ?", deletedPost);

        assertOk(followUser(me.token(), followed.id()));

        Response response = getFeed(me.token(), "follow");
        assertOk(response);
        List<Integer> ids = intList(response, "data.list.id");

        assertThat(ids)
                .as("关注流必须且只能含已关注用户的正常帖。响应：%s", response.asString())
                .containsExactly((int) followedNormal);
        assertThat(ids)
                .as("待审帖（status=0）不得进关注流")
                .doesNotContain((int) followedPending);
        assertThat(ids)
                .as("已屏蔽帖（status=2）不得进关注流")
                .doesNotContain((int) followedBlocked);
        assertThat(ids)
                .as("已删除帖不得进关注流")
                .doesNotContain((int) deletedPost);
        assertThat(ids)
                .as("陌生人的帖子不得进关注流")
                .doesNotContain((int) strangerPost);
        assertThat(ids)
                .as("自己的帖子不进关注流（关注的是别人）")
                .doesNotContain((int) myPost);

        // 多关注一个人 → 他的正常帖也要出现（证明"只含已关注"不是"碰巧只剩一条"）
        assertOk(followUser(me.token(), stranger.id()));
        Response afterFollow = getFeed(me.token(), "follow");
        assertOk(afterFollow);
        assertThat(intList(afterFollow, "data.list.id"))
                .as("关注两个用户后，两人的正常帖都应出现")
                .containsExactlyInAnyOrder((int) followedNormal, (int) strangerPost);

        // 取消关注 → 对方的帖子立刻消失（关注流是实时查询，不做缓存）
        assertOk(unfollowUser(me.token(), followed.id()));
        Response afterUnfollow = getFeed(me.token(), "follow");
        assertOk(afterUnfollow);
        assertThat(intList(afterUnfollow, "data.list.id"))
                .as("取消关注后他的帖子必须立刻从关注流消失")
                .containsExactly((int) strangerPost);

        // 谁都没关注 → 空列表（这是真实业务状态，不是错误）
        assertOk(unfollowUser(me.token(), stranger.id()));
        Response empty = getFeed(me.token(), "follow");
        assertOk(empty);
        assertThat(intList(empty, "data.list.id")).isEmpty();
    }

    @Test
    @DisplayName("M4_feed_all_ordered_by_time：全部流按时间倒序，且过滤待审/屏蔽/已删帖")
    void M4_feed_all_ordered_by_time() {
        long oldest = createPostAt(boardId, me.id(), "全部流-最旧", 1, 100);
        long middle = createPostAt(boardId, followed.id(), "全部流-中间", 1, 50);
        long newest = createPostAt(boardId, stranger.id(), "全部流-最新", 1, 10);
        long pending = createPostAt(boardId, me.id(), "全部流-待审", 0, 5);
        long blocked = createPostAt(boardId, me.id(), "全部流-屏蔽", 2, 4);
        long deletedPost = createPostAt(boardId, me.id(), "全部流-已删", 1, 3);
        jdbcTemplate.update("UPDATE post SET is_deleted = 1 WHERE id = ?", deletedPost);

        Response response = getFeed(null, "all");
        assertOk(response);
        List<Integer> ids = intList(response, "data.list.id");

        assertThat(ids)
                .as("全部流必须按发布时间倒序（新的在前）。响应：%s", response.asString())
                .containsExactly((int) newest, (int) middle, (int) oldest);
        assertThat(ids).doesNotContain((int) pending, (int) blocked, (int) deletedPost);

        // 时间字段本身也要呈降序（只比 id 顺序会在"插入了多条同秒帖子"时失真）
        List<String> createdAts = response.jsonPath().getList("data.list.createdAt");
        assertThat(createdAts)
                .as("返回的 createdAt 必须逐条递减")
                .isSortedAccordingTo((left, right) -> String.valueOf(right).compareTo(String.valueOf(left)));

        // 带 token 请求全部流也必须可用（未登录与已登录都能看全部流）
        Response withToken = getFeed(me.token(), "all");
        assertOk(withToken);
        assertThat(intList(withToken, "data.list.id"))
                .as("登录与否对全部流的结果不应有差别")
                .containsExactlyElementsOf(ids);

        // 分页硬上限 20（§5.5 第 6 条）：30 条帖子请求 size=50，实际只能给 20
        for (int i = 0; i < 30; i++) {
            createPostAt(boardId, me.id(), "分页帖子 " + i, 1, 200 + i);
        }
        Response paged = io.restassured.RestAssured.given()
                .queryParams(java.util.Map.of("type", "all", "page", 1, "size", 50))
                .get("/api/feed");
        assertOk(paged);
        assertThat(paged.jsonPath().getInt("data.size"))
                .as("size 请求 50 必须被硬上限裁到 20")
                .isEqualTo(20);
        assertThat(intList(paged, "data.list.id")).hasSize(20);
    }

    @Test
    @DisplayName("M4_feed_follow_requires_login：未登录访问关注流 → 401 引导登录（不是空列表）")
    void M4_feed_follow_requires_login() {
        createPostAt(boardId, followed.id(), "有内容也不该在未登录时看到", 1, 10);

        Response response = getFeed(null, "follow");

        assertThat(response.statusCode())
                .as("未登录访问关注流必须 401（契约 §8.5 引导登录）。响应：%s", response.asString())
                .isEqualTo(401);
        assertThat(response.jsonPath().getInt("code")).isEqualTo(401);
        assertThat((Object) response.jsonPath().get("data"))
                .as("失败响应不得带数据（尤其不能带一个空列表冒充成功）")
                .isNull();

        // 反向自证：登录后同一个请求必须成功 —— 否则上面的 401 可能只是因为
        // "关注流这个接口根本不存在"或"任何请求都 401"
        assertOk(followUser(me.token(), followed.id()));
        Response loggedIn = getFeed(me.token(), "follow");
        assertOk(loggedIn);
        assertThat(loggedIn.jsonPath().getInt("data.total")).isEqualTo(1);

        // 反向自证 2：未登录访问**全部流**必须成功（401 是"关注流"特有的，不是拦截器全局生效）
        Response allFeed = getFeed(null, "all");
        assertOk(allFeed);
    }

    // ==================================================================
    // 个人主页的双向关注状态（@OptionalLogin 的对外表现）
    // ==================================================================

    @Test
    @DisplayName("个人主页：未登录时关注状态为 null，登录后为真实布尔值")
    void user_profile_follow_flags() {
        assertOk(followUser(me.token(), followed.id()));

        // 未登录：两个标志必须是 null（不是 false）——"未登录"与"没关注"是两种状态
        Response anonymous = getUserProfile(null, followed.id());
        assertOk(anonymous);
        assertThat((Object) anonymous.jsonPath().get("data.isFollowing"))
                .as("未登录时 isFollowing 必须是 null（false 会让前端显示'关注'按钮）。响应：%s",
                        anonymous.asString())
                .isNull();
        assertThat((Object) anonymous.jsonPath().get("data.isFollowedBy")).isNull();
        assertThat(anonymous.jsonPath().getString("data.nickname"))
                .as("匿名访问仍必须能拿到公开信息")
                .isNotBlank();

        // 已登录且已关注：isFollowing=true
        Response mine = getUserProfile(me.token(), followed.id());
        assertOk(mine);
        assertThat(mine.jsonPath().getBoolean("data.isFollowing")).isTrue();
        assertThat(mine.jsonPath().getBoolean("data.isFollowedBy")).isFalse();

        // 已登录但未关注：isFollowing=false（与 null 必须可区分）
        Response strangerView = getUserProfile(stranger.token(), followed.id());
        assertOk(strangerView);
        assertThat((Object) strangerView.jsonPath().get("data.isFollowing"))
                .as("未关注时必须是 false 而不是 null")
                .isEqualTo(false);

        // 双向：他关注我 → 在我的视角看他的主页，isFollowedBy=true
        assertOk(followUser(followed.token(), me.id()));
        Response both = getUserProfile(me.token(), followed.id());
        assertOk(both);
        assertThat(both.jsonPath().getBoolean("data.isFollowing")).isTrue();
        assertThat(both.jsonPath().getBoolean("data.isFollowedBy")).isTrue();

        // 不存在的用户 → 404（而不是 200 + null）
        Response missing = getUserProfile(null, 99999999L);
        assertThat(missing.statusCode())
                .as("不存在的用户必须 404。响应：%s", missing.asString())
                .isEqualTo(404);
    }

    // ==================================================================
    // 粉丝/关注列表
    // ==================================================================

    @Test
    @DisplayName("关注列表与粉丝列表：分页返回、方向不能颠倒")
    void follow_lists_are_directional() {
        assertOk(followUser(me.token(), followed.id()));

        Response following = io.restassured.RestAssured.given()
                .get("/api/users/" + me.id() + "/follows");
        assertOk(following);
        assertThat(following.jsonPath().getList("data.list.userId"))
                .as("'我关注的人'列表里必须有他。响应：%s", following.asString())
                .containsExactly((int) followed.id());

        Response myFans = io.restassured.RestAssured.given()
                .get("/api/users/" + me.id() + "/fans");
        assertOk(myFans);
        assertThat(myFans.jsonPath().getList("data.list"))
                .as("我没有粉丝，列表必须是空的（方向颠倒的实现会在这里多出一条）")
                .isEmpty();

        Response hisFans = io.restassured.RestAssured.given()
                .get("/api/users/" + followed.id() + "/fans");
        assertOk(hisFans);
        assertThat(hisFans.jsonPath().getList("data.list.userId"))
                .as("他的粉丝列表里必须有我")
                .containsExactly((int) me.id());
    }
}
