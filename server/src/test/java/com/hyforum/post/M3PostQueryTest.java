package com.hyforum.post;

import com.hyforum.post.service.PostViewCounter;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 帖子列表 / 详情 / 搜索（docs/技术方案.md §6.5、§8.3、§8.5；docs/ops/deployment.md §5）。
 *
 * <p>覆盖的验收项与方法名：</p>
 * <ul>
 *   <li>{@code M3_list_returns_summary_only} —— 响应无正文字段；</li>
 *   <li>{@code M3_page_size_hard_cap_20} —— {@code size=100} → 实际 20；</li>
 *   <li>{@code M3_list_uses_thumb_url} —— 列表返回的是缩略图 URL；</li>
 *   <li>{@code M3_search_matches_title_and_content} ——（本任务新增，待 L1 登记）；</li>
 *   <li>{@code M3_view_count_increments_in_redis} ——（本任务新增）对应 L1 裁决第 4 条。</li>
 * </ul>
 */
@DisplayName("M3 · 列表 / 详情 / 搜索")
class M3PostQueryTest extends M3ApiTestSupport {

    /** 浏览量计数器（用于验证回写路径；只用公开方法，不碰私有实现）。 */
    @Autowired
    private PostViewCounter viewCounter;

    /**
     * 验收项：{@code M3_list_returns_summary_only}（deployment §5：列表页只返回摘要，正文不回传）。
     *
     * <p>为什么这条是"配额级"的要求：正文是 TEXT（单帖可达数 KB），
     * 一页 20 条就是几十上百 KB，而列表页一个字都不显示。在 2 GiB 单机上，
     * 带宽与序列化成本都白花。</p>
     *
     * <p>断言方式是<b>看报文里有没有 {@code content} 这个 key</b>，而不是看它是否为空 ——
     * 字段存在但为 null 也会占报文、也会让前端以为"这条帖子没有正文"。</p>
     */
    @Test
    void M3_list_returns_summary_only() {
        TestUser author = createFreshUser();
        long boardId = createNormalBoard();
        String marker = "正文标记-不应出现在列表里-73b1";

        long postId = createPostAndGetId(author.token(),
                postCreateBody(boardId, "列表摘要用例帖子", marker));

        Response list = listPosts(Map.of("boardId", boardId));
        assertThat(list.jsonPath().getInt("code")).isZero();
        List<Map<String, Object>> items = list.jsonPath().getList("data.list");
        assertThat(items).hasSize(1);

        Map<String, Object> item = items.get(0);
        assertThat(item)
                .as("列表项不得含正文字段（deployment §5），实际字段：%s", item.keySet())
                .doesNotContainKey("content");
        assertThat(item).containsKeys("id", "title", "boardId", "author", "createdAt");
        assertThat(String.valueOf(item.get("id"))).isEqualTo(String.valueOf(postId));

        // ---------- 反证：同一篇帖子的详情里必须有正文 ----------
        // 没有这一步，"列表没有 content" 也可能是因为正文压根没存进去
        Response detail = getPostDetail(postId);
        assertThat(detail.jsonPath().getString("data.content"))
                .as("正文必须存在，只是列表不返回它")
                .isEqualTo(marker);
    }

    /**
     * 验收项：{@code M3_page_size_hard_cap_20}（docs/ops/deployment.md §5）。
     *
     * <p>分页硬上限是保护 2 核 2G 单机的措施：{@code size=100000} 这种请求
     * 会让一次查询把整张表拉出来。上限在<b>两处</b>都要有（业务层裁剪 + MyBatis-Plus 的
     * {@code maxLimit} 兜底，见 {@code CommonConfig}），本用例断言的是最终生效值。</p>
     */
    @Test
    void M3_page_size_hard_cap_20() {
        TestUser author = createFreshUser();
        long boardId = createNormalBoard();
        createPostAndGetId(author.token(), postCreateBody(boardId, "分页用例帖子", "正文"));

        // ---------- size=100 → 实际 20 ----------
        Response capped = listPosts(Map.of("boardId", boardId, "size", 100));
        assertThat(capped.jsonPath().getInt("code")).isZero();
        assertThat(capped.jsonPath().getInt("data.size"))
                .as("分页硬上限是 20，size=100 必须被裁剪：%s", capped.asString())
                .isEqualTo(20);
        assertThat(capped.jsonPath().getList("data.list").size()).isLessThanOrEqualTo(20);

        // ---------- 反证：size=5 时不能被"一律 20"掩盖 ----------
        Response small = listPosts(Map.of("boardId", boardId, "size", 5));
        assertThat(small.jsonPath().getInt("data.size"))
                .as("小于上限的 size 必须原样生效（否则所谓裁剪其实是无视入参）")
                .isEqualTo(5);
        assertThat(small.jsonPath().getInt("data.page")).isEqualTo(1);
    }

    /**
     * 验收项：{@code M3_list_uses_thumb_url}（技术方案 §8.4：缩略图用 OSS 图片处理参数生成）。
     *
     * <p>列表页用原图会让首屏流量放大一个数量级，而缩略图只有几十 KB。
     * 因此 {@code post.cover_url} 存的是<b>首图缩略图</b>（列注释：
     * 「列表页封面，取首图缩略图」），列表返回的必须是它而不是原图。</p>
     */
    @Test
    void M3_list_uses_thumb_url() {
        TestUser author = createFreshUser();
        long boardId = createNormalBoard();
        String originalUrl = ossImage("2026/09/15/thumb-case.jpg");

        long postId = createPostAndGetId(author.token(),
                withImages(postCreateBody(boardId, "缩略图用例帖子", "正文"), List.of(originalUrl)));

        Response list = listPosts(Map.of("boardId", boardId));
        String coverUrl = list.jsonPath().getString("data.list[0].coverUrl");
        assertThat(coverUrl)
                .as("列表必须返回封面 URL：%s", list.asString())
                .isNotBlank();
        assertThat(coverUrl)
                .as("列表里必须是缩略图（带 OSS 图片处理参数），而不是原图：%s", coverUrl)
                .isNotEqualTo(originalUrl)
                .contains("x-oss-process");

        // ---------- 与详情里的 thumbUrl 同源（避免列表/详情两处各生成一套缩略图 URL） ----------
        Response detail = getPostDetail(postId);
        assertThat(detail.jsonPath().getString("data.images[0].thumbUrl"))
                .as("详情里的缩略图必须与列表封面一致")
                .isEqualTo(coverUrl);
        assertThat(detail.jsonPath().getString("data.images[0].url"))
                .as("详情里同时给出原图 URL（点开看大图）")
                .isEqualTo(originalUrl);
    }

    /**
     * 本任务新增用例：{@code M3_search_matches_title_and_content}（§6.5：检索标题与正文）。
     *
     * <p>检索口径按 §6.5 的说明用 {@code LIKE '%keyword%'}（1000–10000 条规模下足够），
     * 该节也留了 {@code MATCH ... AGAINST} 的升级路径。</p>
     *
     * <p>同时断言<b>待审帖不进搜索</b>：搜索是前台入口，若它绕过了状态过滤，
     * 那么"命中敏感词就不对普通用户展示"这条规则可以被搜索框直接绕过。</p>
     */
    @Test
    void M3_search_matches_title_and_content() {
        // 用**老用户**：本用例要发 4 帖（标题命中 / 正文命中 / 无关 / 待审），
        // 而新注册用户的额度是 3 帖/24h（§8.7）—— 用新用户会在第 4 帖撞上 2002，
        // 用例就变成在测限流而不是在测搜索（这个坑第一次跑就踩到了，见交付报告的"红→绿"记录）
        TestUser author = createEstablishedUser();
        long boardId = createNormalBoard();
        String keyword = "量子退火算法";

        long inTitle = createPostAndGetId(author.token(),
                postCreateBody(boardId, "聊聊" + keyword + "的直觉理解", "正文"));
        long inContent = createPostAndGetId(author.token(),
                postCreateBody(boardId, "求助：一个优化问题", "我在读" + keyword + "相关的材料。"));
        createPostAndGetId(author.token(), postCreateBody(boardId, "毫不相干的帖子", "这里的正文没有关键词。"));
        // 待审帖：标题命中关键词但含敏感词 → status=0，不得出现在前台搜索结果里
        long pending = createPostAndGetId(author.token(),
                postCreateBody(boardId, "标题含" + keyword + "和" + SENSITIVE_WORD, "正文"));

        Response found = searchPosts(keyword);
        assertThat(found.jsonPath().getInt("code")).as("%s", found.asString()).isZero();
        List<Object> ids = found.jsonPath().getList("data.list.id");
        assertThat(found.jsonPath().getInt("data.total"))
                .as("标题命中 1 条 + 正文命中 1 条（待审那条不计）：%s", found.asString())
                .isEqualTo(2);
        assertThat(ids).hasSize(2);
        assertThat(asStrings(ids))
                .as("必须同时命中标题与正文")
                .containsExactlyInAnyOrder(String.valueOf(inTitle), String.valueOf(inContent));
        assertThat(asStrings(ids)).doesNotContain(String.valueOf(pending));

        // ---------- 反证：搜不到的词必须是 0 条（否则上面"2 条"可能来自不过滤） ----------
        Response none = searchPosts("这个词一定不存在-9f3ac1");
        assertThat(none.jsonPath().getInt("data.total")).isZero();

        // ---------- 参数校验：keyword 必填 ----------
        Response missing = io.restassured.RestAssured.given().get("/api/posts/search");
        assertThat(missing.jsonPath().getInt("code"))
                .as("缺少 keyword 必须是 400 参数错误：%s", missing.asString())
                .isEqualTo(400);
    }

    /**
     * 本任务新增用例：{@code M3_view_count_increments_in_redis}
     * —— 对应 L1 裁决第 4 条与技术方案 §8.3。
     *
     * <p>它要防的是一条具体的错法：把详情接口实现成
     * {@code UPDATE post SET view_count = view_count + 1}。
     * 那种写法功能上"也对"，但它是本项目<b>最高频的写操作</b>
     * （每个访客一次），会放大行锁与磁盘压力 —— 在 2 GiB 单机上正是要避免的。</p>
     *
     * <p>因此断言分三层，缺一层都可能是假绿：</p>
     * <ol>
     *   <li>Redis 键 {@code hy:post:view:{id}} 的计数在涨（说明 +1 真的走了 Redis）；</li>
     *   <li><b>数据库里的 view_count 一直没动</b>（这是"没有每条 UPDATE"的直接证据）；</li>
     *   <li>回写路径可用（触发一次回写后数据库才涨，且 Redis 键被清掉）——
     *       否则"没写库"也可能是因为<b>永远不写库</b>，那浏览量就丢了。</li>
     * </ol>
     */
    @Test
    void M3_view_count_increments_in_redis() {
        TestUser author = createFreshUser();
        long boardId = createNormalBoard();
        long postId = createPostAndGetId(author.token(), postCreateBody(boardId, "浏览量用例帖子", "正文"));

        // 起点：库里已有 5 次浏览（模拟历史数据），Redis 里没有增量
        jdbcTemplate.update("UPDATE post SET view_count = 5 WHERE id = ?", postId);
        String redisKey = "hy:post:view:" + postId;
        assertThat(stringRedisTemplate.opsForValue().get(redisKey)).isNull();

        // ---------- 第 1 次访问 ----------
        Response first = getPostDetail(postId);
        assertThat(first.jsonPath().getInt("data.viewCount"))
                .as("详情返回的浏览量应把 Redis 里的未回写增量算上（5 + 1）：%s", first.asString())
                .isEqualTo(6);
        assertThat(stringRedisTemplate.opsForValue().get(redisKey))
                .as("浏览量 +1 必须落在 Redis 键 hy:post:view:%s 上（技术方案 §7/§8.3）", postId)
                .isEqualTo("1");
        assertThat(postColumn(postId, "view_count", Integer.class))
                .as("详情接口不得每次都 UPDATE post.view_count —— 数据库此刻必须仍是 5")
                .isEqualTo(5);

        // ---------- 第 2 次访问 ----------
        Response second = getPostDetail(postId);
        assertThat(second.jsonPath().getInt("data.viewCount")).isEqualTo(7);
        assertThat(stringRedisTemplate.opsForValue().get(redisKey)).isEqualTo("2");
        assertThat(postColumn(postId, "view_count", Integer.class)).isEqualTo(5);

        // ---------- 回写 ----------
        viewCounter.flushPendingViews();
        assertThat(postColumn(postId, "view_count", Integer.class))
                .as("回写后数据库必须是 5 + 2 = 7")
                .isEqualTo(7);
        assertThat(stringRedisTemplate.opsForValue().get(redisKey))
                .as("回写后 Redis 增量键必须被清掉，否则同一批浏览量会被反复累加")
                .isNull();
    }

    /** 把 JSON 里的 id 列表统一成字符串（Integer/Long 混淆会让断言静默失真）。 */
    private static List<String> asStrings(List<Object> ids) {
        return ids.stream().map(String::valueOf).toList();
    }
}
