package com.hyforum.interaction;

import com.hyforum.domain.post.entity.PostImage;
import com.hyforum.domain.post.mapper.PostImageMapper;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CR-K / CR-L / CR-M 的契约用例（任务书 §13，2026-09-17 L1 裁决）。
 *
 * <p>§13.1 的三条是<b>硬要求</b>，其中第 ③ 条是反证：
 * ① 登录后点赞 → 同一帖的<b>列表与详情</b>都返回 {@code liked=true}；
 * ② <b>未登录</b>请求同一帖 → {@code liked=false} 且 <b>HTTP 200</b>（不是 401）；
 * ③ 别人的点赞<b>不影响</b>我的 {@code liked}（反证：用另一个用户的 token 看到的是他自己的状态）。</p>
 *
 * <p>为什么这三条要一起存在：只写 ① 的话，一个"永远返回 true"的实现会通过；
 * 只写 ② 的话，一个"永远返回 false"的实现会通过；
 * 只写 ①② 的话，一个"把两个人的点赞混在一起算"的实现会通过。
 * 三条合起来才把"相对于请求者"这件事钉死 —— 而那句话正是 CR-K 的全部内容。</p>
 */
class M4ViewerStateContractTest extends M4ApiTestSupport {

    @Autowired
    private PostImageMapper postImageMapper;

    private TestUser author;
    private TestUser alice;
    private TestUser bob;
    private long boardId;
    private long postId;

    @BeforeEach
    void seed() {
        author = createUser("作者");
        alice = createUser("爱丽丝");
        bob = createUser("鲍勃");
        boardId = createBoard();
        postId = createNormalPost(boardId, author.id(), "CR-K 契约用例帖");
    }

    // ==================================================================
    // CR-K 三条硬要求
    // ==================================================================

    @Test
    @DisplayName("CR-K①：登录后点赞 → 列表与详情都返回 liked=true")
    void CRK_liked_true_in_both_list_and_detail_after_liking() {
        assertOk(likePost(alice.token(), postId));

        Response detail = getPostDetail(alice.token(), postId);
        assertOk(detail);
        assertThat(detail.jsonPath().getBoolean("data.liked"))
                .as("点赞后详情必须 liked=true。响应：%s", detail.asString())
                .isTrue();
        assertThat(detail.jsonPath().getBoolean("data.collected"))
                .as("只点了赞、没收藏 → collected 必须是 false（两个字段不能一起变）")
                .isFalse();

        Response list = listPostsWith(alice.token());
        assertOk(list);
        int idx = indexOfPost(list, postId);
        assertThat(idx).as("该帖必须在列表里").isNotNegative();
        assertThat(list.jsonPath().getBoolean("data.list[" + idx + "].liked"))
                .as("**列表**也必须 liked=true —— 只修详情会让列表继续显示未点赞，"
                        + "而 CR-K 的诉求就是'界面在说谎'。响应：%s", list.asString())
                .isTrue();

        // 反向自证：取消点赞后两处都回到 false（只断言"点了变 true"的话，
        // 一个恒为 true 的实现会通过）
        assertOk(unlikePost(alice.token(), postId));
        assertThat(getPostDetail(alice.token(), postId).jsonPath().getBoolean("data.liked"))
                .as("取消点赞后详情必须回到 false").isFalse();
        Response afterList = listPostsWith(alice.token());
        assertThat(afterList.jsonPath().getBoolean("data.list[" + indexOfPost(afterList, postId) + "].liked"))
                .as("取消点赞后列表也必须回到 false").isFalse();
    }

    @Test
    @DisplayName("CR-K②：未登录请求同一帖 → liked/collected=false 且 HTTP 200（不是 401）")
    void CRK_anonymous_gets_false_and_http_200() {
        // 先让"另一个已登录用户"点上赞，制造出"这帖确实有点赞"的事实 ——
        // 否则"未登录看到 false"可能只是因为"根本没人点过"，那条断言就没有区分力
        assertOk(likePost(alice.token(), postId));
        assertOk(collectPost(alice.token(), postId));

        Response detail = RestAssured.given().get("/api/posts/" + postId);
        assertThat(detail.statusCode())
                .as("未登录看详情必须仍是 **200**，绝不能因为要算 liked 就变成 401。响应：%s", detail.asString())
                .isEqualTo(200);
        assertOk(detail);
        assertThat(detail.jsonPath().getBoolean("data.liked"))
                .as("未登录时 liked 必须是 false（不是 null、也不是 true）")
                .isFalse();
        assertThat(detail.jsonPath().getBoolean("data.collected"))
                .as("未登录时 collected 必须是 false")
                .isFalse();

        Response list = RestAssured.given().get("/api/posts");
        assertThat(list.statusCode()).as("未登录看列表必须 200").isEqualTo(200);
        assertOk(list);
        int idx = indexOfPost(list, postId);
        assertThat(list.jsonPath().getBoolean("data.list[" + idx + "].liked"))
                .as("未登录时列表的 liked 也必须是 false").isFalse();

        // 但"点赞总数"依然是真实的 —— 这两个字段的含义必须能被区分开：
        // liked = 我的状态；likeCount = 全站的状态。未登录只该让前者为 false。
        assertThat(detail.jsonPath().getInt("data.likeCount"))
                .as("likeCount 是**全站**计数，未登录也必须如实返回 1（不能因为 liked=false 一起变成 0）")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("CR-K③（反证）：别人的点赞不影响我的 liked —— 各自看到自己的状态")
    void CRK_other_users_like_does_not_affect_mine() {
        assertOk(likePost(alice.token(), postId));
        assertOk(collectPost(alice.token(), postId));

        // 爱丽丝：自己点的 → true
        assertThat(getPostDetail(alice.token(), postId).jsonPath().getBoolean("data.liked"))
                .as("爱丽丝自己点的赞，她看到 true").isTrue();

        // 鲍勃：没点过 → 必须 false。这是**反证的核心**：
        // 如果实现把"这帖有没有被任何人点赞"当成 liked，鲍勃会看到 true
        Response bobDetail = getPostDetail(bob.token(), postId);
        assertThat(bobDetail.jsonPath().getBoolean("data.liked"))
                .as("鲍勃没点过赞 → 必须 false（若为 true，说明实现查的是'这帖有没有被任何人点赞'，"
                        + "而契约要求的是**相对于请求者**）。响应：%s", bobDetail.asString())
                .isFalse();
        assertThat(bobDetail.jsonPath().getBoolean("data.collected")).isFalse();

        // 鲍勃点一下 → 他 true，而爱丽丝仍然是 true（互不影响）
        assertOk(likePost(bob.token(), postId));
        assertThat(getPostDetail(bob.token(), postId).jsonPath().getBoolean("data.liked")).isTrue();
        assertThat(getPostDetail(alice.token(), postId).jsonPath().getBoolean("data.liked")).isTrue();

        // 鲍勃取消 → 他 false，爱丽丝**不受影响**（这条最能抓"共享状态"的实现）
        assertOk(unlikePost(bob.token(), postId));
        assertThat(getPostDetail(bob.token(), postId).jsonPath().getBoolean("data.liked")).isFalse();
        assertThat(getPostDetail(alice.token(), postId).jsonPath().getBoolean("data.liked"))
                .as("鲍勃取消点赞不得影响爱丽丝的 liked")
                .isTrue();

        // 列表侧同样验证一遍（列表与详情是两条独立的组装路径，必须都正确）
        Response bobList = listPostsWith(bob.token());
        assertThat(bobList.jsonPath().getBoolean("data.list[" + indexOfPost(bobList, postId) + "].liked"))
                .as("列表里鲍勃也必须是 false").isFalse();
        Response aliceList = listPostsWith(alice.token());
        assertThat(aliceList.jsonPath().getBoolean("data.list[" + indexOfPost(aliceList, postId) + "].liked"))
                .as("列表里爱丽丝必须仍是 true").isTrue();
    }

    @Test
    @DisplayName("CR-K 补充：首页流与个人主页的卡片也带 liked/collected（否则换个入口又丢状态）")
    void CRK_feed_and_profile_cards_carry_viewer_state() {
        assertOk(likePost(alice.token(), postId));

        // 首页流（全部流）
        Response feed = getFeed(alice.token(), "all");
        assertOk(feed);
        int idx = indexOfInPath(feed, "data.list.id", postId);
        assertThat(idx).as("该帖必须在全部流里").isNotNegative();
        assertThat(feed.jsonPath().getBoolean("data.list[" + idx + "].liked"))
                .as("首页流卡片必须也带 liked=true —— 否则同一个卡片组件在首页显示'未点赞'").isTrue();

        // 个人主页（作者的帖子列表）
        Response profilePosts = RestAssured.given()
                .header("Authorization", alice.token())
                .get("/api/users/" + author.id() + "/posts");
        assertOk(profilePosts);
        int pIdx = indexOfInPath(profilePosts, "data.list.id", postId);
        assertThat(pIdx).as("该帖必须在作者主页的帖子列表里").isNotNegative();
        assertThat(profilePosts.jsonPath().getBoolean("data.list[" + pIdx + "].liked"))
                .as("个人主页卡片也必须带 liked=true").isTrue();
    }

    // ==================================================================
    // CR-L
    // ==================================================================

    @Test
    @DisplayName("CR-L：列表最多给 3 张缩略图；imageCount 是总数；hasDiskResource 标记资源帖；列表不发 diskUrl")
    void CRL_list_carries_at_most_three_thumbs_and_disk_flag() {
        // 造 5 张图 → imageCount=5，但 imageThumbs 只能 3 张
        for (int i = 1; i <= 5; i++) {
            insertImage(postId, "https://hy-forum-2026.oss-cn-beijing.aliyuncs.com/post/crl-" + i + ".jpg", i);
        }
        jdbcTemplate.update("UPDATE post SET image_count = 5 WHERE id = ?", postId);

        Response list = listPostsWith(alice.token());
        assertOk(list);
        int idx = indexOfPost(list, postId);

        assertThat(list.jsonPath().getInt("data.list[" + idx + "].imageCount"))
                .as("imageCount 是**总数**（5），不是缩略图数").isEqualTo(5);
        List<String> thumbs = list.jsonPath().getList("data.list[" + idx + "].imageThumbs");
        assertThat(thumbs)
                .as("缩略图**最多 3 张**（每页 20 条 × 9 张 = 180 个 URL 都要现签，成本翻 3 倍）。"
                        + "实际：%s", thumbs)
                .hasSize(3);
        assertThat(thumbs)
                .as("元素必须是**签名后**的 URL（不能是库里的裸 URL：桶是私有的，裸 URL 一律 403）")
                .allSatisfy(url -> assertThat(url)
                        .as("缩略图 URL 应含 OSS 签名参数；实际：%s", url)
                        .contains("Signature="));
        assertThat(thumbs)
                .as("缩略图应带 x-oss-process（缩略图而非原图）；实际：%s", thumbs)
                .allSatisfy(url -> assertThat(url).contains("x-oss-process"));

        // 无图帖子：空数组而不是 null
        long bare = createNormalPost(boardId, author.id(), "CR-L 无图帖");
        Response list2 = listPostsWith(alice.token());
        int bareIdx = indexOfPost(list2, bare);
        assertThat(list2.jsonPath().getList("data.list[" + bareIdx + "].imageThumbs"))
                .as("无图时 imageThumbs 必须是**空数组**（null 会逼前端写两套判断）")
                .isEmpty();

        // hasDiskResource：普通帖为 false；造一条带 diskUrl 的帖子为 true
        assertThat(list2.jsonPath().getBoolean("data.list[" + bareIdx + "].hasDiskResource"))
                .as("无网盘链接的帖子 hasDiskResource 必须是 false")
                .isFalse();
        long withDisk = createNormalPost(boardId, author.id(), "CR-L 资源帖");
        jdbcTemplate.update("UPDATE post SET disk_type = 3, disk_url = ? WHERE id = ?",
                "https://pan.quark.cn/s/abcdef", withDisk);
        Response list3 = listPostsWith(alice.token());
        int diskIdx = indexOfPost(list3, withDisk);
        assertThat(list3.jsonPath().getBoolean("data.list[" + diskIdx + "].hasDiskResource"))
                .as("有 diskUrl 的帖子 hasDiskResource 必须是 true（前端据此渲染资源标记）")
                .isTrue();
        assertThat(list3.jsonPath().getInt("data.list[" + diskIdx + "].diskType"))
                .as("列表仍给 diskType（已有字段，保留）").isEqualTo(3);

        // ★ CR-L 的硬边界：列表**不得**出现 diskUrl / diskCode
        //   用"整个报文字符串里搜 key"来断言，而不是断言字段为 null ——
        //   字段存在但为 null 同样占报文，也说明契约被改了
        String body = list3.asString();
        assertThat(body)
                .as("CR-L 明确**不批准**列表给 diskUrl（产品裁决：放了就把用户截留在列表页）。"
                        + "报文里不得出现该 key")
                .doesNotContain("diskUrl");
        assertThat(body)
                .as("diskCode 同样不得出现在列表里")
                .doesNotContain("diskCode");
        assertThat(body)
                .as("列表也不得泄漏网盘链接本身的值")
                .doesNotContain("pan.quark.cn");
    }

    // ==================================================================
    // CR-M
    // ==================================================================

    @Test
    @DisplayName("CR-M：gender 取值语义写进契约；level 标注为 reserved（恒为 1、前端不得展示）")
    void CRM_gender_and_level_semantics_are_documented() {
        Response doc;
        try {
            doc = RestAssured.given().get("/v3/api-docs");
        } catch (RuntimeException ex) {
            doc = null;
        }
        assertThat(doc)
                .as("测试上下文里取不到 /v3/api-docs，无法断言注解 —— 请检查 springdoc 是否在 test profile 下可用")
                .isNotNull();
        assertThat(doc.statusCode()).as("契约导出端点必须可用").isEqualTo(200);
        String spec = doc.asString();

        // gender：必须把 0/1/2 三个取值写出来（CR-M 要求"以 schema.sql 列注释为准"）
        assertThat(spec)
                .as("契约里必须有 gender 的取值语义（0 未知 / 1 男 / 2 女）")
                .contains("0 = 未知 / 1 = 男 / 2 = 女");
        // level：必须标 reserved 且写明"前端不得展示"
        assertThat(spec)
                .as("契约里必须把 level 标成 reserved（不能让它以一个无口径的数字出现在前端）")
                .contains("reserved");
        assertThat(spec)
                .as("level 的说明里必须写明前端不得展示")
                .contains("前端不得展示");

        // 运行期事实核对：level 确实恒为 1（我实测 AuthService.register 里 setLevel(1)、
        // schema 默认值也是 1）。这条断言是为了防止"文档说 reserved、实现却在写别的值"
        Response me = RestAssured.given().header("Authorization", alice.token()).get("/api/user/me");
        assertOk(me);
        assertThat(me.jsonPath().getInt("data.level"))
                .as("当前实现下 level 恒为 1（任务书 §13.3 原文说'恒为 0'，实测是 1 —— 已如实登记）"
                        + "。若这条变了，说明有人加了写入口径，那时必须同步改契约说明")
                .isEqualTo(1);
    }

    // ==================================================================
    // 小工具
    // ==================================================================

    /** 带 token 取版块列表（可选鉴权：这层决定 liked/collected）。 */
    private Response listPostsWith(String token) {
        return RestAssured.given().header("Authorization", token).get("/api/posts");
    }

    /** 在 {@code data.list} 里按 id 找下标（不依赖顺序假设）。 */
    private int indexOfPost(Response response, long id) {
        return indexOfInPath(response, "data.list.id", id);
    }

    private int indexOfInPath(Response response, String path, long id) {
        List<Integer> ids = response.jsonPath().getList(path);
        if (ids == null) {
            return -1;
        }
        for (int i = 0; i < ids.size(); i++) {
            if (ids.get(i) != null && ids.get(i).longValue() == id) {
                return i;
            }
        }
        return -1;
    }

    /** 直接插一行图片（绕过上传接口：本用例验的是列表的读出口径，不是上传）。 */
    private void insertImage(long postId, String bareUrl, int sort) {
        PostImage image = new PostImage();
        image.setPostId(postId);
        image.setUrl(bareUrl);
        image.setThumbUrl(com.hyforum.common.oss.OssThumbnailUrls.derive(bareUrl));
        image.setWidth(100);
        image.setHeight(100);
        image.setSort(sort);
        image.setAuditStatus(PostImage.AUDIT_PENDING);
        image.setCreatedAt(java.time.LocalDateTime.now());
        postImageMapper.insert(image);
    }
}
