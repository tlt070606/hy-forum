package com.hyforum.user;

import com.hyforum.interaction.M4ApiTestSupport;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CR-Q 扩展：{@code CollectionItemVO} 补 {@code author} 与 {@code imageThumbs}
 * （测试名由 L1 预登记）。
 *
 * <h2>为什么要补这两个字段</h2>
 * <ul>
 *   <li><b>{@code author}</b>：收藏卡片要显示<b>作者头像</b>，而此前这个 VO
 *       <b>一个作者字段都没有</b> —— 前端根本拿不到作者；</li>
 *   <li><b>{@code imageThumbs}</b>：与帖子列表卡片同一口径（前 3 张、逐个读时签名）；</li>
 *   <li>顺带：{@code coverUrl} 此前是<b>库里存的裸 URL</b>（桶私有 → 前端 403）。</li>
 * </ul>
 *
 * <p>三个字段都与 {@code GET /api/posts} 的卡片<b>同一手法</b>（经
 * {@code common.oss.OssUrls}），不另起一套 —— 见 {@code OssUrls} 的类注释。</p>
 */
class M4CollectionItemTest extends M4ApiTestSupport {

    private static final String OSS_PREFIX = "https://hy-forum-2026.oss-cn-beijing.aliyuncs.com/";

    private TestUser author;
    private TestUser collector;
    private long postId;

    @BeforeEach
    void seed() {
        author = createUser("收藏作者");
        collector = createUser("收藏者");
        long boardId = createBoard();
        postId = createNormalPost(boardId, author.id(), "收藏卡片用例帖");

        // 作者头像（自己目录下）
        jdbcTemplate.update("UPDATE user SET avatar_url = ? WHERE id = ?",
                OSS_PREFIX + "avatar/" + author.id() + "/head.png", author.id());

        // 4 张图（>3，用来验证"最多 3 张"）+ 封面 = 首图缩略图
        for (int i = 0; i < 4; i++) {
            String url = OSS_PREFIX + "post/2026/09/20/c" + i + ".png";
            jdbcTemplate.update("INSERT INTO post_image (post_id, url, thumb_url, sort, audit_status) "
                    + "VALUES (?, ?, ?, ?, 0)", postId, url, url, i);
        }
        jdbcTemplate.update("UPDATE post SET cover_url = ? WHERE id = ?",
                OSS_PREFIX + "post/2026/09/20/c0.png", postId);
        jdbcTemplate.update("UPDATE post SET image_count = 4 WHERE id = ?", postId);

        // 收藏者收藏该帖 → 出现在"我的收藏"里
        assertOk(collectPost(collector.token(), postId));
    }

    @Test
    @DisplayName("M4_collection_item_has_author_and_thumbs：收藏项必须有 author（含已签名头像）、imageThumbs（≤3 且已签名）、已签名 coverUrl")
    void M4_collection_item_has_author_and_thumbs() {
        Response response = RestAssured.given()
                .header("Authorization", collector.token())
                .get("/api/user/collections");
        assertOk(response);

        List<java.util.Map<String, Object>> items = response.jsonPath().getList("data.list");
        assertThat(items)
                .as("前提：收藏列表必须有一条（否则下面的断言等于没测）。响应：%s", response.asString())
                .isNotEmpty();
        java.util.Map<String, Object> item = items.get(0);

        // ---------- ① 新增 author 字段 ----------
        assertThat(item.get("author"))
                .as("**收藏项必须有 author** —— 收藏卡片要显示作者头像，而此前本 VO 一个作者字段都没有。"
                        + "响应：%s", response.asString())
                .isNotNull();
        assertThat(response.jsonPath().getString("data.list[0].author.nickname"))
                .as("author 要带昵称（卡片显示用）").isNotBlank();

        String authorAvatar = response.jsonPath().getString("data.list[0].author.avatarUrl");
        assertThat(authorAvatar)
                .as("author 的头像也必须是**签名 URL**（它经 AvatarUrlResolver，与其他装配点同一入口）。"
                        + "实际：%s", authorAvatar)
                .contains("Signature=");

        // ---------- ② 新增 imageThumbs（≤3，且逐个签名）----------
        List<String> thumbs = response.jsonPath().getList("data.list[0].imageThumbs");
        assertThat(thumbs)
                .as("**收藏项必须有 imageThumbs**（与列表卡片同一口径）。响应：%s", response.asString())
                .isNotNull()
                .isNotEmpty();
        assertThat(thumbs.size())
                .as("缩略图最多 3 张（本用例造了 4 张图，用来验证这个上限真的生效）。实际 %d 张",
                        thumbs.size())
                .isLessThanOrEqualTo(3);
        for (String thumb : thumbs) {
            assertThat(thumb)
                    .as("每一张缩略图都必须是签名 URL。实际：%s", thumb)
                    .contains("Signature=");
        }

        // ---------- ③ coverUrl 也必须已签名 ----------
        String cover = response.jsonPath().getString("data.list[0].coverUrl");
        assertThat(cover)
                .as("**封面必须是签名 URL** —— 此前这里是库里存的裸 URL，桶私有 → 前端 403。实际：%s", cover)
                .contains("Signature=");

        // ---------- ④ 反证：非收藏者看不到这条 ----------
        // 缺了它，"列表返回了别人的收藏"这种越权也能让上面全过
        Response asAuthor = RestAssured.given()
                .header("Authorization", author.token())
                .get("/api/user/collections");
        assertOk(asAuthor);
        assertThat(asAuthor.jsonPath().getList("data.list"))
                .as("**收藏列表必须是'我的'** —— 作者自己没收藏过，列表必须为空（反证越权）")
                .isEmpty();
    }
}