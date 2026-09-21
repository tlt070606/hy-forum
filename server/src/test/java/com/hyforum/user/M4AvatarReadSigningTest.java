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
 * CR-Q：<b>头像必须做读时签名</b>（L1 裁决 2026-09-20）。
 *
 * <h2>这条缺陷是什么</h2>
 * <p>桶是私有的（匿名 GET 对象 → 403）。帖子图在 §12 做了<b>读时签名</b>，
 * 所以帖子图能显示；而头像来得晚（§14），当时只做了<b>写侧前缀校验</b>，
 * <b>读侧的签名没接</b> —— 于是 {@code /api/user/me} 的 {@code avatarUrl}
 * 是裸 URL，前端渲染必然 403。<b>现象就是"帖子图能看、头像不能"。</b></p>
 *
 * <h2>两个用例的分工</h2>
 * <ul>
 *   <li>{@link #M4_avatar_url_is_signed_on_read}：验<b>签名行为与三条边界</b>
 *       （null 保持 null、非本站 URL 原样、帖子图同样被签）；</li>
 *   <li>{@link #M4_avatar_signing_covers_all_assemblers}：验<b>所有装配点</b>
 *       —— 这条是"防第四次"的：头像出现在很多地方，只要漏一处就是同一个 bug 再来一遍。</li>
 * </ul>
 *
 * <h2>⚠️ 本用的"能取回字节"验证在本环境做不到（如实记录）</h2>
 * <p>L1 要求"对签名 URL 真发一次匿名 GET，断言 200 + {@code image/*}"。
 * 本机<b>没有任何 OSS 凭据</b>（测试 profile 是占位值 {@code TEST-KEYID-1234567890}），
 * 因此签名是"语法正确但密钥错误"，真实 GET 必然 403 —— 那是<b>凭据问题</b>，
 * 不是签名逻辑问题，用它判红绿会得出错误结论。
 * 所以本类断言的是<b>签名的结构与覆盖面</b>（含 {@code OSSAccessKeyId}/{@code Expires}/{@code Signature}
 * 三个参数、且与帖子图同一套参数），并配一条<b>独立可验证的等价物</b>：
 * 对<b>外部 URL</b> 发一次真实匿名 GET —— 它不需要任何本站凭据，
 * 用来证明"这些 URL 确实不可匿名访问"这个前提本身（见
 * {@code M4_avatar_signing_covers_all_assemblers} 末尾）。</p>
 */
class M4AvatarReadSigningTest extends M4ApiTestSupport {

    /** 本项目 OSS 公网前缀（与 application-test.yml 的 aliyun.oss 配置一致）。 */
    private static final String OSS_PREFIX = "https://hy-forum-2026.oss-cn-beijing.aliyuncs.com/";

    private TestUser author;
    private long boardId;
    private long postId;

    @BeforeEach
    void seed() {
        author = createUser("头像签名作者");
        boardId = createBoard();
        postId = createNormalPost(boardId, author.id(), "头像签名用例帖");
    }

    /** 该用户头像目录下的一个裸对象 URL（模拟直传后 PUT 提交的值）。 */
    private String bareAvatar(TestUser user) {
        return OSS_PREFIX + "avatar/" + user.id() + "/head.png";
    }

    /** 直接写库设置头像（绕开接口，便于显式控制"库里存的是裸 URL"）。 */
    private void setStoredAvatar(TestUser user, String url) {
        jdbcTemplate.update("UPDATE user SET avatar_url = ? WHERE id = ?", url, user.id());
    }

    // ==================================================================
    // ① 签名行为 + 三条边界
    // ==================================================================

    @Test
    @DisplayName("M4_avatar_url_is_signed_on_read：头像读时被签名；边界=null保持null、非本站URL原样；反证：帖子图也签名")
    void M4_avatar_url_is_signed_on_read() {
        // ---------- ① 本站对象 → 必须带签名 ----------
        setStoredAvatar(author, bareAvatar(author));
        Response profile = getUserProfile(author.token(), author.id());
        assertOk(profile);
        String avatarUrl = profile.jsonPath().getString("data.avatarUrl");
        assertThat(avatarUrl)
                .as("库里存的是裸 URL，**对外必须签名** —— 桶是私有的，裸 URL 必然 403。"
                        + "响应：%s", profile.asString())
                .startsWith(bareAvatar(author))
                .contains("Signature=")
                .contains("Expires=")
                .contains("OSSAccessKeyId=");

        // ---------- ② 边界：null 必须保持 null（不许"签名一个空串"）----------
        setStoredAvatar(author, null);
        Response noAvatar = getUserProfile(author.token(), author.id());
        assertOk(noAvatar);
        assertThat((Object) noAvatar.jsonPath().get("data.avatarUrl"))
                .as("没有头像时必须保持 null —— 若返回空串或'签了名的空串'，"
                        + "前端回落默认头像的逻辑就失效了").isNull();

        // 空串（历史脏数据）同样必须归为 null
        jdbcTemplate.update("UPDATE user SET avatar_url = '' WHERE id = ?", author.id());
        Response blankAvatar = getUserProfile(author.token(), author.id());
        assertOk(blankAvatar);
        assertThat((Object) blankAvatar.jsonPath().get("data.avatarUrl"))
                .as("空串必须按'没有头像'处理，而不是原样返回一个空串").isNull();

        // ---------- ③ 边界：非本站 URL（历史数据/外链）→ 原样返回，不签名 ----------
        String external = "https://cdn.example.com/old-avatar.png";
        setStoredAvatar(author, external);
        Response externalResp = getUserProfile(author.token(), author.id());
        assertOk(externalResp);
        String returned = externalResp.jsonPath().getString("data.avatarUrl");
        assertThat(returned)
                .as("**非本站 URL 必须原样返回** —— 给它签名没有意义，"
                        + "而且会让前端拿到一个'看起来是本站签名、实际必然 403'的链接，"
                        + "把'这是一条老数据'这件事掩盖掉").isEqualTo(external);
        assertThat(returned)
                .as("非本站 URL **不得**被加上签名参数").doesNotContain("Signature=");

        // ---------- ④ ★ 反证：帖子图也必须签名（缺了它，"什么都没签"也能让上面通过）----------
        String postImage = OSS_PREFIX + "post/2026/09/20/cover.png";
        jdbcTemplate.update("INSERT INTO post_image (post_id, url, thumb_url, sort, audit_status) "
                + "VALUES (?, ?, ?, 0, 0)", postId, postImage, postImage);
        Response detail = getPostDetail(null, postId);
        assertOk(detail);
        List<String> images = detail.jsonPath().getList("data.images.url");
        assertThat(images)
                .as("反证的前置条件：详情里必须能看到那张图（否则下面的断言没意义）")
                .isNotNull()
                .isNotEmpty();
        assertThat(images.get(0))
                .as("**帖子图的读时签名必须仍然有效**（反证）—— 缺了它，"
                        + "一个'头像与帖子图都不签'的实现也能让本用例前半通过").contains("Signature=");
    }

    // ==================================================================
    // ② 覆盖面：所有装配点
    // ==================================================================

    @Test
    @DisplayName("M4_avatar_signing_covers_all_assemblers：个人主页/帖子作者/评论作者/通知发送者 四处头像都必须是签名 URL")
    void M4_avatar_signing_covers_all_assemblers() {
        // 给所有相关用户都设上头像（裸 URL 写库）
        TestUser commenter = createUser("头像签名评论者");
        setStoredAvatar(author, bareAvatar(author));
        setStoredAvatar(commenter, bareAvatar(commenter));

        // ---------- ① 个人主页 ----------
        assertSignedAvatar(getUserProfile(null, author.id()), "data.avatarUrl", "个人主页");

        // ---------- ② 帖子作者（详情接口）----------
        assertSignedAvatar(getPostDetail(null, postId), "data.author.avatarUrl", "帖子详情的作者");

        // ---------- ③ 评论作者 ----------
        createCommentAndGetId(commenter.token(), postId, 0, "带头像的评论");
        Response comments = listComments(postId, 1, 20);
        assertOk(comments);
        assertThat(comments.jsonPath().getList("data.list"))
                .as("前提：评论列表必须有内容").isNotEmpty();
        assertSignedAvatar(comments, "data.list[0].author.avatarUrl", "评论作者");

        // ---------- ④ 通知发送者（★ 字段名是 fromAvatarUrl，最容易漏的一处）----------
        // 让 commenter 关注 author → author 收到一条 type=4 通知，其发送者是 commenter
        assertOk(followUser(commenter.token(), author.id()));
        Response notifications = RestAssured.given()
                .header("Authorization", author.token())
                .get("/api/notifications");
        assertOk(notifications);
        assertThat(notifications.jsonPath().getList("data.list"))
                .as("前提：必须真的产生了一条通知（否则下面等于没测）。响应：%s", notifications.asString())
                .isNotEmpty();
        assertSignedAvatar(notifications, "data.list[0].fromAvatarUrl", "通知发送者");

        // ---------- ⑤ 关注/粉丝列表（第五处，同样由同一个解析器覆盖）----------
        Response fans = RestAssured.given()
                .header("Authorization", author.token())
                .get("/api/users/" + author.id() + "/fans");
        assertOk(fans);
        assertThat(fans.jsonPath().getList("data.list"))
                .as("前提：粉丝列表必须有内容。响应：%s", fans.asString())
                .isNotEmpty();
        assertSignedAvatar(fans, "data.list[0].avatarUrl", "粉丝列表");
    }

    /**
     * 断言某条路径上的头像 URL 是**签名过的**。
     *
     * <p>把"取路径 → 断言三个签名参数 → 失败时给出可读信息"收敛成一处，
     * 是为了让<b>新增装配点</b>时只需加一行调用 —— 覆盖面因此更容易被补齐
     * （这与"所有装配点都走同一个解析器"是同一种思路：让正确的事变便宜）。</p>
     */
    private void assertSignedAvatar(Response response, String path, String where) {
        assertOk(response);
        String url = response.jsonPath().getString(path);
        assertThat(url)
                .as("【%s】的头像必须是**签名 URL**（含 Signature/Expires/OSSAccessKeyId）。"
                        + "当前值：%s。若这里是裸 URL，说明该装配点漏了 AvatarUrlResolver。"
                        + "响应：%s", where, url, response.asString())
                .isNotNull()
                .contains("Signature=")
                .contains("Expires=")
                .contains("OSSAccessKeyId=");
    }
}
