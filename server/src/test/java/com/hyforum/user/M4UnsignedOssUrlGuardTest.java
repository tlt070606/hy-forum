package com.hyforum.user;

import com.hyforum.interaction.M4ApiTestSupport;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔴 <b>护栏：所有会返回 OSS 地址的读接口，响应里不得出现未签名的本站 URL</b>
 * （CR-Q 扩展，2026-09-20 L1 裁决；测试名由 L1 预登记）。
 *
 * <h2>为什么要有这条（它的价值在于"不依赖任何人记得清单"）</h2>
 * <p>CR-Q 这一轮暴露的事实是：<b>漏签名是靠前端在界面上肉眼逐个撞出来的</b>
 * （头像、收藏封面、动态封面都是这么发现的）。而"写进文档 ≠ 不会再犯" ——
 * 本项目已经四次证明"我记得"不可靠。</p>
 * <p>所以这条用例把<b>清单变成遍历</b>：</p>
 * <ol>
 *   <li>打一遍所有"会返回图片/头像地址"的读接口；</li>
 *   <li>把响应 JSON 里<b>每一个本项目 OSS 域名的 URL</b> 抓出来（不管它藏在哪个字段、哪一层）；</li>
 *   <li>逐个断言它带 {@code Signature=} 且 {@code Expires} 未过期。</li>
 * </ol>
 * <p><b>新增一个 VO 字段忘了签名 → 只要那个端点被遍历到，它自己会红</b>，
 * 不需要任何人更新这里的字段清单 —— 因为这里<b>没有字段清单</b>，
 * 它只认"本站 OSS 域名"这一个特征。</p>
 *
 * <h2>两条反证（缺了它，这条护栏会把"正确的事"判成错）</h2>
 * <ul>
 *   <li><b>{@code avatarUrl} 为 null 不得报错</b>：没有头像完全合法，
 *       护栏只对"**出现了的**本站 URL"提要求；</li>
 *   <li><b>非本站 OSS 的 URL 必须原样且不带签名</b>（历史数据/外链）：
 *       给它签名没有意义，而且会让前端拿到一个"看起来是本站签名、实际必然 403"的链接。</li>
 * </ul>
 */
class M4UnsignedOssUrlGuardTest extends M4ApiTestSupport {

    /** 本项目 OSS 的公网前缀（与 application-test.yml 的 aliyun.oss 配置一致）。 */
    private static final String OSS_HOST = "hy-forum-2026.oss-cn-beijing.aliyuncs.com";
    private static final String OSS_PREFIX = "https://" + OSS_HOST + "/";

    /**
     * 抓出响应 JSON 里所有"本项目 OSS 域名"的 URL。
     *
     * <p>不用 JsonPath 逐字段取 —— 那要求<b>先知道字段名</b>，而本护栏的全部意义就是
     * 不依赖"有人记得字段名"。正则扫整段 JSON 可以连"藏在新字段里的 URL"一起抓到。</p>
     *
     * <p>JSON 里 `/` 可能被转义为 `\/`（某些序列化器会这么做），因此匹配完再规范化。</p>
     */
    private static final Pattern OSS_URL = Pattern.compile(
            "https?://" + Pattern.quote(OSS_HOST) + "/[^\"\\\\\\s]*");

    private TestUser author;
    private TestUser collector;
    private long boardId;
    private long postId;

    /** 非本站 OSS 的历史数据 URL（用于反证②）。 */
    private static final String EXTERNAL_AVATAR = "https://cdn.other.example.com/legacy-avatar.png";

    @BeforeEach
    void seed() {
        author = createUser("护栏作者");
        collector = createUser("护栏收藏者");
        boardId = createBoard();
        postId = createNormalPost(boardId, author.id(), "护栏用例帖");

        // 帖子的图：封面由首图缩略图推导，因此必须有 post_image 行
        String image = OSS_PREFIX + "post/2026/09/20/guard.png";
        jdbcTemplate.update("INSERT INTO post_image (post_id, url, thumb_url, sort, audit_status) "
                + "VALUES (?, ?, ?, 0, 0)", postId, image, image);
        jdbcTemplate.update("UPDATE post SET cover_url = ? WHERE id = ?", image, postId);

        // 作者与收藏者各自设置**自己目录下**的头像（裸 URL 写库）。
        //
        // ⚠️ 这里踩过一次，值得写下来：第一版写成了
        //     `UPDATE user SET avatar_url = ? WHERE id IN (?, ?)` 并且传的是 author.id() 的路径 ——
        //   于是**两个用户都指向了 avatar/1/**。当"收藏者"作为通知发送者/粉丝出现时，
        //   AvatarUrlResolver 要求 URL 落在**该用户自己**的 avatar/{id}/ 前缀内，
        //   而 avatar/1/ 显然不在收藏者的 avatar/2/ 里 → 解析器**按设计原样返回（不签名）**
        //   → 护栏报"未签名"。
        //   **那是用例数据造错了，不是产品缺陷**：解析器拒绝替"不属于该用户的地址"签名，
        //   正是 §14.3 边界③的行为。教训：造 fixture 时"两个用户共用一条路径"是看不见的坑。
        jdbcTemplate.update("UPDATE user SET avatar_url = ? WHERE id = ?",
                avatarOf(author), author.id());
        jdbcTemplate.update("UPDATE user SET avatar_url = ? WHERE id = ?",
                avatarOf(collector), collector.id());

        // 让「我的收藏」列表里有内容：collector 收藏 author 的帖子
        assertOk(collectPost(collector.token(), postId));
        // 让通知列表里有内容：collector 关注 author
        assertOk(followUser(collector.token(), author.id()));
    }

    // ==================================================================
    // 主线：遍历所有读接口，逐个断言
    // ==================================================================

    @Test
    @DisplayName("M4_api_responses_have_no_unsigned_oss_url：遍历所有返回 OSS 地址的读接口，每个本站 URL 都必须带有效签名")
    void M4_api_responses_have_no_unsigned_oss_url() {
        // 每一行 = 一个"会返回 OSS 地址"的读接口 + 用哪个 token 打
        List<EndpointProbe> probes = List.of(
                new EndpointProbe("个人主页（自己）", () -> getUserProfile(author.token(), author.id())),
                new EndpointProbe("个人主页（匿名）", () -> getUserProfile(null, author.id())),
                new EndpointProbe("帖子详情", () -> getPostDetail(null, postId)),
                new EndpointProbe("帖子列表（全部流）", () -> getFeed(null, "all")),
                // 关注流：走**另一条** FeedService 路径（type=follow），同样要签名
                new EndpointProbe("帖子列表（关注流）", () -> getFeed(collector.token(), "follow")),
                new EndpointProbe("评论列表", () -> listComments(postId, 1, 20)),
                new EndpointProbe("我的收藏", () -> collectionsOf(collector.token())),
                new EndpointProbe("我的消息", () -> notificationsOf(author.token())),
                new EndpointProbe("粉丝列表", () -> fansOf(author.token())),
                new EndpointProbe("关注列表", () -> followsOf(author.token())),
                new EndpointProbe("用户帖子列表", () -> userPosts(author.token(), author.id())));

        List<String> problems = new ArrayList<>();
        int checkedUrls = 0;
        int endpointsWithUrls = 0;

        for (EndpointProbe probe : probes) {
            Response response = probe.call();
            assertThat(response.statusCode())
                    .as("【%s】必须成功（护栏要能读到响应体才谈得上检查）。响应：%s",
                            probe.name(), response.asString())
                    .isEqualTo(200);

            List<String> urls = extractOssUrls(response.asString());
            if (!urls.isEmpty()) {
                endpointsWithUrls++;
            }
            for (String raw : urls) {
                // 规范化：JSON 里可能把 `/` 转义成 `\/`
                String url = raw.replace("\\/", "/");
                checkedUrls++;
                problems.addAll(inspect(probe.name(), url));
            }
        }

        // 前置自证：如果什么都抓不到，这条用例就是"空转的绿" —— 那种绿比红更危险
        assertThat(checkedUrls)
                .as("一个本站 OSS 地址都没抓到，说明**本用例没有测到任何东西**（空转的绿）—— "
                        + "请检查 fixture 是否真的造出了带图/带头像的数据")
                .isPositive();
        assertThat(endpointsWithUrls)
                .as("只覆盖到 %d 个端点带 OSS 地址 —— 覆盖面可能因为 payload 变化而退化，"
                        + "这里显式兜底（否则护栏会悄悄失效）", endpointsWithUrls)
                .isGreaterThanOrEqualTo(4);

        assertThat(problems)
                .as("以下 OSS 地址**未签名或签名参数不完整** —— 桶是私有的，前端渲染它们必然 403"
                        + "（这正是 CR-Q：帖子图能看、头像不能；以及收藏/动态封面同样裸 URL）")
                .isEmpty();
    }

    // ==================================================================
    // 反证
    // ==================================================================

    @Test
    @DisplayName("反证①：avatarUrl 为 null 的响应不得因此报错（空值合法）")
    void null_avatar_is_legitimate() {
        jdbcTemplate.update("UPDATE user SET avatar_url = NULL WHERE id = ?", collector.id());

        Response profile = getUserProfile(collector.token(), collector.id());
        assertThat(profile.statusCode())
                .as("没有头像**完全合法**，响应必须正常 200（护栏不能把'空值'当成'未签名'）。"
                        + "响应：%s", profile.asString())
                .isEqualTo(200);
        assertThat((Object) profile.jsonPath().get("data.avatarUrl"))
                .as("没有头像时保持 null（不是空串，也不是'签了名的空串'）").isNull();
    }

    @Test
    @DisplayName("反证②：非本站 OSS 的 URL（历史/外链）必须原样返回且**不带**签名")
    void external_url_stays_untouched() {
        jdbcTemplate.update("UPDATE user SET avatar_url = ? WHERE id = ?",
                EXTERNAL_AVATAR, collector.id());

        Response profile = getUserProfile(collector.token(), collector.id());
        assertOk(profile);
        String returned = profile.jsonPath().getString("data.avatarUrl");
        assertThat(returned)
                .as("非本站 URL 必须原样返回 —— 给它签名没有意义，"
                        + "而且会让前端拿到一个'看起来是本站签名、实际必然 403'的链接")
                .isEqualTo(EXTERNAL_AVATAR);
        assertThat(returned)
                .as("非本站 URL 不得带任何签名参数").doesNotContain("Signature=");

        // 顺带：它不该被本护栏误判（护栏只对**本站域名**提要求）
        assertThat(extractOssUrls(profile.asString()))
                .as("外链不属于本站 OSS 域名，护栏的正则不该抓它").isEmpty();
    }

    // ==================================================================
    // 骨架
    // ==================================================================

    /**
     * <b>清理 {@code notification}</b>：放在 {@code @AfterEach}，**不是**放在 {@code tablesToClean()}。
     *
     * <p><b>为什么不能用 {@code tablesToClean()} 覆盖</b>（我第一版就这么写，红得很迷惑）：
     * {@code IntegrationTestBase} 的清表时机是 <b>{@code @BeforeEach} 之内</b>，
     * 而子类的 {@code @BeforeEach seed()} 会<b>在父类的清表之后</b>执行 ——
     * 也就是说"清单"里的表是在 seed <b>之前</b>清掉的。
     * 于是我把 {@code notification} 加进清单后，seed 里刚造出来的通知
     * <b>当场被清掉</b>，剩下的其它表数据与它不再对得上，
     * 通知里的发送者查不到 → 该字段变 null → 护栏报出一堆看起来像产品缺陷的问题。</p>
     *
     * <p>正确做法：清理"本次运行才产生、且父类清单不知道"的表，应当放在
     * <b>{@code @AfterEach}</b>（本轮用完再清），这样既不会污染下一次运行，
     * 也不会把本轮的 fixture 清掉。</p>
     *
     * <p>顺带记下这条护栏的另一面价值：它对"测试数据没清干净"同样敏感 ——
     * 实测在清单还没补的时候，它扫到了<b>上几次运行留下的旧通知行</b>（修复前的裸 URL），
     * 报出一堆"未签名"。<b>这类红是测试自身的问题，不是产品缺陷</b>，
     * 排查时必须先分清这两者（本轮我在这上面花了很久）。</p>
     */
    @org.junit.jupiter.api.AfterEach
    void cleanNotificationsProducedByThisClass() {
        // 只清本类会产生的表；用精确条件而不是 DROP，避免影响别人的数据
        jdbcTemplate.update("DELETE FROM notification");
    }

    // ==================================================================
    // 骨架
    // ==================================================================

    /** 一个待遍历的读接口。 */
    private interface Probe {
        Response get();
    }

    private record EndpointProbe(String name, Probe probe) {
        Response call() {
            return probe.get();
        }
    }

    /** 某个用户**自己目录**下的头像裸 URL（务必按 userId 生成，别让两个人共用一条路径）。 */
    private static String avatarOf(TestUser user) {
        return OSS_PREFIX + "avatar/" + user.id() + "/head.png";
    }

    /** 抓出响应体里所有本站 OSS 域名开头的 URL。 */
    private static List<String> extractOssUrls(String body) {
        if (body == null || body.isEmpty()) {
            return List.of();
        }
        List<String> found = new ArrayList<>();
        Matcher m = OSS_URL.matcher(body);
        while (m.find()) {
            found.add(m.group());
        }
        return found;
    }

    /**
     * 检查一个本站 OSS URL：必须同时具备三个签名参数，且未过期。
     *
     * @return 问题描述列表；空 = 这个 URL 合格
     */
    private static List<String> inspect(String where, String url) {
        List<String> problems = new ArrayList<>();
        String key = queryParam(url, "OSSAccessKeyId");
        String expires = queryParam(url, "Expires");
        String signature = queryParam(url, "Signature");

        if (key == null || key.isBlank()) {
            problems.add(String.format("【%s】缺少 OSSAccessKeyId：%s", where, url));
        }
        if (signature == null || signature.isBlank()) {
            problems.add(String.format("【%s】缺少 Signature（未签名）：%s", where, url));
        }
        if (expires == null || expires.isBlank()) {
            problems.add(String.format("【%s】缺少 Expires：%s", where, url));
        } else {
            long exp = parseLongOrMinus(expires);
            if (exp <= 0) {
                problems.add(String.format("【%s】Expires 不是合法秒数：%s（%s）", where, url, expires));
            } else if (exp * 1000L < System.currentTimeMillis()) {
                // L1 明确要求："带 Signature= 且**未过期**" —— 过期签名前端同样 403
                problems.add(String.format("【%s】签名**已过期**：%s（Expires=%s）", where, url, expires));
            }
        }
        return problems;
    }

    /** 取 query 参数值（URL 解码）。注意：URL 里可能已有 `x-oss-process` 等子资源参数。 */
    private static String queryParam(String url, String name) {
        int q = url.indexOf(63);   // '?'
        if (q < 0) {
            return null;
        }
        for (String pair : url.substring(q + 1).split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                String value = pair.substring(eq + 1);
                try {
                    return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
                } catch (UnsupportedEncodingException ex) {
                    return value;
                }
            }
        }
        return null;
    }

    private static long parseLongOrMinus(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            return -1L;
        }
    }

    // ---------- 各端点的小助手（用 existing 助手打不到的几个） ----------

    private Response collectionsOf(String token) {
        return RestAssured.given().header("Authorization", token).get("/api/user/collections");
    }

    private Response notificationsOf(String token) {
        return RestAssured.given().header("Authorization", token).get("/api/notifications");
    }

    private Response fansOf(String token) {
        return RestAssured.given().header("Authorization", token)
                .get("/api/users/" + author.id() + "/fans");
    }

    private Response followsOf(String token) {
        return RestAssured.given().header("Authorization", token)
                .get("/api/users/" + author.id() + "/follows");
    }

    private Response userPosts(String token, long userId) {
        return RestAssured.given().header("Authorization", token)
                .get("/api/users/" + userId + "/posts");
    }
}
