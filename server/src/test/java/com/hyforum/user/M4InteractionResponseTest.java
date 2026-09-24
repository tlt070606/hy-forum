package com.hyforum.user;

import com.hyforum.interaction.M4ApiTestSupport;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CR-R（点赞/收藏响应体带状态与计数）与 CR-N（通知带可跳转 id）。
 *
 * <p>两个改动都是"前端拿不到必需信息"这一类：
 * CR-R 之前只回 {@code {"code":0,"message":"ok"}}，按钮只能靠猜；
 * CR-N 之前通知里只有 {@code targetType}/{@code targetId}，评论类通知<b>拿不到帖子 id</b>，
 * 前端无法跳转到"这条回复在哪个帖子下"。</p>
 */
class M4InteractionResponseTest extends M4ApiTestSupport {

    private TestUser author;
    private TestUser actor;
    private long postId;

    @BeforeEach
    void seed() {
        author = createUser("CR作者");
        actor = createUser("CR操作者");
        postId = createNormalPost(createBoard(), author.id(), "CR-R/CR-N 用例帖");
        // ★ 清掉本类可能造成的历史残留：notification 不在父类清表清单里（那是 M4 时代的清单），
        //   而"我的消息"是分页的 —— 残留几十行会让第 1 页全是旧数据，
        //   于是本用例要找的 type=3 被挤出首页，报"必须有 type=3 的通知"。
        //   实测踩到过（total=81，其中 type=3 为 0 —— 因为本轮那条被挤到第 2 页之后）。
        jdbcTemplate.update("DELETE FROM notification");
    }

    /**
     * 用完清掉本类产生的通知（与 guard 用例同一做法，理由也相同）。
     *
     * <p>为什么不能放进 {@code tablesToClean()}：父类的清表发生在 {@code @BeforeEach} 内、
     * 子类 {@code seed()} <b>之前</b> —— 放清单里会把 seed 刚造的通知当场清掉。</p>
     */
    @org.junit.jupiter.api.AfterEach
    void cleanNotifications() {
        jdbcTemplate.update("DELETE FROM notification");
    }

    // ==================================================================
    // CR-R
    // ==================================================================

    @Test
    @DisplayName("CR-R：点赞/取消/收藏/取消的响应体必须带 liked、collected、likeCount、collectCount")
    void cr_r_like_collect_response_carries_state() {
        // ---------- 点赞：liked=true 且计数为 1 ----------
        Response like = RestAssured.given().header("Authorization", actor.token())
                .post("/api/posts/" + postId + "/like");
        assertOk(like);
        assertThat(like.jsonPath().getBoolean("data.liked"))
                .as("**点赞后 liked 必须为 true** —— 前端据此把按钮点亮。响应：%s", like.asString())
                .isTrue();
        assertThat(like.jsonPath().getInt("data.likeCount"))
                .as("**必须带回最新计数**（不是让前端自己 +1）。响应：%s", like.asString())
                .isEqualTo(1);
        assertThat(like.jsonPath().getBoolean("data.collected"))
                .as("没收藏过则 collected=false").isFalse();

        // ---------- 重复点赞：幂等 —— liked 仍 true、计数**不能变成 2** ----------
        Response again = RestAssured.given().header("Authorization", actor.token())
                .post("/api/posts/" + postId + "/like");
        assertOk(again);
        assertThat(again.jsonPath().getInt("data.likeCount"))
                .as("**重复点赞计数必须仍是 1** —— 若前端拿这个值显示，'自己 +1' 的做法会显示成 2 "
                        + "（这是 CR-R 要解决的问题本身）").isEqualTo(1);

        // ---------- 收藏：collected=true，且 liked 仍为 true ----------
        Response collect = RestAssured.given().header("Authorization", actor.token())
                .post("/api/posts/" + postId + "/collect");
        assertOk(collect);
        assertThat(collect.jsonPath().getBoolean("data.collected")).isTrue();
        assertThat(collect.jsonPath().getInt("data.collectCount")).isEqualTo(1);
        assertThat(collect.jsonPath().getBoolean("data.liked"))
                .as("收藏不能把点赞状态冲掉（两个状态互相独立）").isTrue();

        // ---------- 取消点赞：liked=false、计数回落 ----------
        Response unlike = RestAssured.given().header("Authorization", actor.token())
                .delete("/api/posts/" + postId + "/like");
        assertOk(unlike);
        assertThat(unlike.jsonPath().getBoolean("data.liked"))
                .as("取消后 liked 必须为 false（前端据此熄灭按钮）").isFalse();
        assertThat(unlike.jsonPath().getInt("data.likeCount")).isZero();

        // ---------- 反证：另一个人点赞，我的状态不受影响 ----------
        Response other = RestAssured.given().header("Authorization", author.token())
                .post("/api/posts/" + postId + "/like");
        assertOk(other);
        assertThat(other.jsonPath().getBoolean("data.liked"))
                .as("**liked 是'相对于请求者'的** —— 别人点赞不能让我的按钮点亮").isTrue();
        assertThat(other.jsonPath().getInt("data.likeCount"))
                .as("但计数是全局的：此时应为 1（作者点的）").isEqualTo(1);

        Response feed = getFeed(actor.token(), "all");
        assertOk(feed);
        List<Map<String, Object>> items = feed.jsonPath().getList("data.list");
        assertThat(items).isNotEmpty();
        assertThat((Boolean) items.get(0).get("liked"))
                .as("反证：我刚取消过点赞，列表里 liked 必须是 false（与操作响应同一口径）").isFalse();
    }

    // ==================================================================
    // CR-N
    // ==================================================================

    @Test
    @DisplayName("CR-N：通知带 postId 与 commentId —— 评论/回复类两个都有，点赞/关注类 commentId 为空")
    void cr_n_notification_carries_jump_ids() {
        // ① 让 actor 点赞 author 的帖子 → type=1，postId 有值、commentId 为空
        assertOk(likePostActor());
        // ② actor 发主楼评论 → type=2（评论），postId 有值、commentId 为空
        long rootId = createCommentAndGetId(actor.token(), postId, 0, "CR-N 主楼评论");
        // ③ author 回复 actor 的主楼 → type=3（回复），**两个都要有**
        createCommentAndGetId(author.token(), postId, rootId, "CR-N 回复");
        // ④ actor 关注 author → type=4（关注），两个都为空
        assertOk(followUser(actor.token(), author.id()));

        // ★ 两个收件箱都要看 —— 各自的接收人不同（这一点我第一版搞错了，值得写下来）：
        //   · 点赞(1) / 评论(2)：接收人是**帖子作者** author；
        //   · 回复(3)：接收人是**被回复的主楼作者** actor（不是帖子作者！）；
        //   · 关注(4)：接收人是**被关注者** author。
        //   第一版只查了 author 的收件箱，于是找不到 type=3，
        //   报"必须有 type=3 的回复通知"—— 而那条通知其实好好的，只是不在这个收件箱里。
        Response authorBox = RestAssured.given().header("Authorization", author.token())
                .get("/api/notifications");
        assertOk(authorBox);
        List<Map<String, Object>> authorItems = authorBox.jsonPath().getList("data.list");
        assertThat(authorItems).as("前提：author 必须收到通知。响应：%s", authorBox.asString()).isNotEmpty();

        Response actorBox = RestAssured.given().header("Authorization", actor.token())
                .get("/api/notifications");
        assertOk(actorBox);
        List<Map<String, Object>> actorItems = actorBox.jsonPath().getList("data.list");
        assertThat(actorItems).as("前提：actor 必须收到那条回复通知。响应：%s", actorBox.asString()).isNotEmpty();

        System.out.println("[CR-N] author 收件箱类型 = " + authorItems.stream()
                .map(i -> String.valueOf(i.get("type"))).collect(java.util.stream.Collectors.joining(",")));
        System.out.println("[CR-N] actor  收件箱类型 = " + actorItems.stream()
                .map(i -> String.valueOf(i.get("type"))).collect(java.util.stream.Collectors.joining(",")));

        // 按类型找（顺序不依赖时间戳，避免同秒创建导致顺序不稳）
        Map<String, Object> like = itemOfType(authorItems, 1);
        Map<String, Object> comment = itemOfType(authorItems, 2);
        Map<String, Object> reply = itemOfType(actorItems, 3);
        Map<String, Object> follow = itemOfType(authorItems, 4);

        assertThat(like).as("必须有 type=1 的点赞通知").isNotNull();
        assertThat(((Number) like.get("postId")).longValue())
                .as("**点赞通知必须带 postId**（前端据此跳到帖子）").isEqualTo(postId);
        assertThat(like.get("commentId"))
                .as("点赞通知**不该有 commentId**（它点的是帖子，不是评论）").isNull();

        assertThat(comment).as("必须有 type=2 的评论通知").isNotNull();
        assertThat(((Number) comment.get("postId")).longValue())
                .as("评论通知带 postId").isEqualTo(postId);
        assertThat(comment.get("commentId")).as("评论通知的 commentId 为空").isNull();

        assertThat(reply).as("必须有 type=3 的回复通知").isNotNull();
        assertThat(((Number) reply.get("commentId")).longValue())
                .as("**回复通知必须带 commentId**（指向被回复的那条主楼）").isEqualTo(rootId);
        assertThat(((Number) reply.get("postId")).longValue())
                .as("**回复通知也必须带 postId** —— 它由评论的 post_id 反查得到；"
                        + "缺了它前端无法跳到'这条回复在哪个帖子下'（CR-N 的核心）").isEqualTo(postId);

        assertThat(follow).as("必须有 type=4 的关注通知").isNotNull();
        assertThat(follow.get("postId")).as("关注的对象是人，没有可跳转的帖子 → postId 为空").isNull();
        assertThat(follow.get("commentId")).as("关注通知的 commentId 为空").isNull();
    }

    // ==================================================================
    // 小工具
    // ==================================================================

    private Response likePostActor() {
        return RestAssured.given().header("Authorization", actor.token())
                .post("/api/posts/" + postId + "/like");
    }

    private static Map<String, Object> itemOfType(List<Map<String, Object>> items, int type) {
        for (Map<String, Object> item : items) {
            Object value = item.get("type");
            if (value instanceof Number number && number.intValue() == type) {
                return item;
            }
        }
        return null;
    }
}
