package com.hyforum.post;

import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 发帖（docs/技术方案.md §6.5、§8.4、§8.6、§5.5）。
 *
 * <p>覆盖的验收项与方法名（名字逐字取自任务书 §6.2，不得改动）：</p>
 * <ul>
 *   <li>{@code M3_publish_resource_post_with_images} —— 落库 + 详情页字段完整；</li>
 *   <li>{@code M3_disk_code_may_be_empty} —— 阿里云盘／夸克场景可保存；</li>
 *   <li>{@code M3_reject_external_image_url} —— 非本项目 OSS 前缀 → 拒绝；</li>
 *   <li>{@code M3_image_count_capped_at_nine} —— 第 10 张 → 拒绝；</li>
 * </ul>
 * <p>另含两条本任务新增的用例（待 L1 登记进映射表），对应用户已裁决的两条状态机规则：</p>
 * <ul>
 *   <li>{@code M3_publish_visible_immediately_when_clean} —— 未命中敏感词 → {@code status=1} <b>直接可见</b>（§8.6 第 5 条「先发后审」）；</li>
 *   <li>{@code M3_publish_pending_when_sensitive_hit} —— 命中敏感词 → {@code status=0} 且不对普通用户展示（§8.6 第 2 条）。</li>
 * </ul>
 *
 * <h2>为什么"命中敏感词"这条不能断言 2001</h2>
 * <p>2001 是「内容包含敏感词 → 拒绝」的码，用于注册昵称那种"直接拒收"的场景。
 * 帖子走的是另一条路：<b>收下但置 {@code status=0} 进待审队列</b>（§8.6 第 2 条）。
 * 若实现成"命中就返回 2001 拒收"，用户的草稿会被直接丢掉，而 M5 的审核队列也永远收不到东西 ——
 * 这是把"先发后审"错写成"先审后发"的典型症状。</p>
 */
@DisplayName("M3 · 发帖")
class M3PostPublishTest extends M3ApiTestSupport {

    /**
     * 验收项：{@code M3_publish_resource_post_with_images}（PLAN §4 M3 的验收标准）。
     *
     * <p>一条资源帖走完整条链路：资源版块 + 两张图 + 百度网盘链接（链接里带 {@code pwd}），
     * 断言"落库 + 详情页字段完整"两件事 —— 只断言响应体是不够的，
     * 因为响应体可以由内存里的对象拼出来而库里什么都没有（那正是"详情页看不到"的成因）。</p>
     */
    @Test
    void M3_publish_resource_post_with_images() {
        TestUser author = createFreshUser();
        long boardId = createResourceBoard();

        String image1 = ossImage("2026/09/15/1a2b3c.jpg");
        String image2 = ossImage("2026/09/15/4d5e6f.png");
        Map<String, Object> body = withDisk(
                withImages(postCreateBody(boardId, "分享一套 Java 学习资料", "正文：两本电子书，见网盘。"),
                        List.of(image1, image2)),
                1, "https://pan.baidu.com/s/1AbCdEfGh?pwd=9k2m", null);

        Response response = createPost(author.token(), body);
        assertThat(response.jsonPath().getInt("code"))
                .as("资源帖必须发布成功：%s", response.asString()).isZero();
        Object createdId = response.jsonPath().get("data.id");
        assertThat(createdId).as("发帖响应必须带新帖 id：%s", response.asString()).isNotNull();
        long postId = Long.parseLong(String.valueOf(createdId));
        assertThat(postId).as("发帖必须返回新帖 id").isPositive();

        // ---------- ① 详情页字段完整（响应的 data 就是详情形状） ----------
        assertThat(response.jsonPath().getString("data.title")).isEqualTo("分享一套 Java 学习资料");
        assertThat(response.jsonPath().getString("data.content")).contains("两本电子书");
        assertThat(response.jsonPath().getLong("data.boardId")).isEqualTo(boardId);
        assertThat(response.jsonPath().getString("data.boardName")).isEqualTo("资源分享");
        assertThat(response.jsonPath().getBoolean("data.boardIsResource")).isTrue();
        assertThat(response.jsonPath().getInt("data.imageCount")).isEqualTo(2);
        assertThat(response.jsonPath().getList("data.images")).hasSize(2);
        // §12.3 坑 2：url/thumbUrl 的语义从"裸 URL"变成"读时签名 URL"（桶是私有的）→
        // 这里改比 **base**（去掉 OSSAccessKeyId/Expires/Signature 后的对象地址）。
        // 比 base 与被测语义等价，且不会因"两次组装跨过一秒 → Expires 不同"而偶发变红。
        assertThat(bareUrl(response.jsonPath().getString("data.images[0].url")))
                .as("images[0].url 的对象地址必须还是当初提交的那张图")
                .isEqualTo(image1);
        assertThat(response.jsonPath().getString("data.images[0].thumbUrl"))
                .as("九宫格缩略图必须由 OSS 图片处理参数生成（§8.4）")
                .contains("x-oss-process");
        assertThat(bareUrl(response.jsonPath().getString("data.coverUrl")))
                .as("列表封面取首图缩略图（post.cover_url 的列注释）；比对象地址")
                .isEqualTo(bareUrl(response.jsonPath().getString("data.images[0].thumbUrl")));
        assertThat(response.jsonPath().getLong("data.author.id")).isEqualTo(author.id());
        assertThat(response.jsonPath().getString("data.author.nickname")).isNotBlank();
        assertThat(response.jsonPath().getInt("data.viewCount")).isZero();
        assertThat(response.jsonPath().getInt("data.status"))
                .as("未命中敏感词 → status=1 直接可见（§8.6 第 5 条先发后审）")
                .isEqualTo(1);

        // ---------- ② 网盘字段已归一化（§5.5 / ADR-0008） ----------
        assertThat(response.jsonPath().getInt("data.diskType")).isEqualTo(1);
        assertThat(response.jsonPath().getString("data.diskUrl"))
                .as("disk_url 必须去掉 pwd 参数")
                .isEqualTo("https://pan.baidu.com/s/1AbCdEfGh");
        assertThat(response.jsonPath().getString("data.diskCode"))
                .as("pwd 必须被解析进 disk_code")
                .isEqualTo("9k2m");

        // ---------- ③ 落库 ----------
        assertThat(postColumn(postId, "status", Integer.class)).isEqualTo(1);
        assertThat(postColumn(postId, "image_count", Integer.class)).isEqualTo(2);
        assertThat(postColumn(postId, "disk_url", String.class)).isEqualTo("https://pan.baidu.com/s/1AbCdEfGh");
        assertThat(postColumn(postId, "disk_code", String.class)).isEqualTo("9k2m");
        assertThat(postColumn(postId, "cover_url", String.class)).contains("x-oss-process");
        assertThat(countImages(postId)).isEqualTo(2);

        // 图片行的 sort 必须按提交顺序、audit_status 必须是 0（"尚未被人工判定"，CR-006）
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT url, sort, audit_status FROM post_image WHERE post_id = ? ORDER BY sort", postId);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("url")).isEqualTo(image1);
        assertThat(rows.get(1).get("url")).isEqualTo(image2);
        assertThat(rows.get(0).get("audit_status")).isEqualTo(0);
        assertThat(rows.get(1).get("audit_status")).isEqualTo(0);

        // ---------- ④ 冗余计数在同一事务内维护（§8.2） ----------
        assertThat(postColumn(postId, "board_id", Long.class)).isEqualTo(boardId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT post_count FROM board WHERE id = ?", Integer.class, boardId))
                .as("发帖必须让 board.post_count +1").isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT post_count FROM user WHERE id = ?", Integer.class, author.id()))
                .as("发帖必须让 user.post_count +1").isEqualTo(1);

        // ---------- ⑤ CR-006：前台隐藏 audit_status=2，但不隐藏 0 ----------
        // 模拟一张"已判定违规"的图（图片审核出口归 M5/M6，M3 只负责不把它显示出来）
        jdbcTemplate.update("INSERT INTO post_image (post_id, url, thumb_url, sort, audit_status) "
                + "VALUES (?, ?, ?, ?, 2)", postId, ossImage("2026/09/15/bad.jpg"),
                ossImage("2026/09/15/bad.jpg"), 9);

        Response afterHidden = getPostDetail(postId);
        List<String> visibleUrls = afterHidden.jsonPath().getList("data.images.url");
        // §12.3 坑 2：对外是签名 URL → 比 base（对象地址）
        assertThat(visibleUrls.stream().map(M3ApiTestSupport::bareUrl).toList())
                .as("audit_status=2 的图片必须被前台隐藏（CR-006）")
                .containsExactly(image1, image2);
        assertThat(visibleUrls.stream().map(M3ApiTestSupport::bareUrl).toList())
                .as("audit_status=0 的图片**不得**被隐藏 —— '默认 0' 不等于'必须人工放行才可见'（CR-006）")
                .doesNotContain(ossImage("2026/09/15/bad.jpg"));
    }

    /**
     * 验收项：{@code M3_disk_code_may_be_empty}（ADR-0008）。
     *
     * <p>阿里云盘与夸克<b>没有提取码机制</b>，因此 {@code diskCode} 必须允许为空，
     * 否则这两类分享根本无法录入 —— 这条要测的是"能存下来"，所以做成接口级用例
     * （映射表把它标为「单元」，此处刻意做得更硬：单元测试证明不了"可保存"）。</p>
     */
    @Test
    void M3_disk_code_may_be_empty() {
        TestUser author = createFreshUser();
        long boardId = createResourceBoard();

        // ---------- 阿里云盘（diskType=2）：无提取码 ----------
        long alipanPostId = createPostAndGetId(author.token(), withDisk(
                postCreateBody(boardId, "阿里云盘分享：设计素材", "见链接。"),
                2, "https://www.alipan.com/s/abcdef123456", null));
        assertThat(postColumn(alipanPostId, "disk_code", String.class))
                .as("阿里云盘无提取码，disk_code 必须允许为 NULL（ADR-0008 决策 1）")
                .isNull();
        // 注意：jsonPath().get(...) 是泛型方法，直接塞进 assertThat 会让重载解析失败
        // （实测编译报 "assertThat 引用不明确"），因此先落到 Object 变量上
        Object alipanCode = getPostDetail(alipanPostId).jsonPath().get("data.diskCode");
        assertThat(alipanCode)
                .as("详情页在无提取码时 diskCode 应为 null（前端据此只渲染「打开链接」按钮）")
                .isNull();

        // ---------- 夸克网盘（diskType=3）：无提取码 ----------
        long quarkPostId = createPostAndGetId(author.token(), withDisk(
                postCreateBody(boardId, "夸克网盘分享：字体包", "见链接。"),
                3, "https://pan.quark.cn/s/zzz999", null));
        assertThat(postColumn(quarkPostId, "disk_code", String.class)).isNull();

        // ---------- 反证：带了提取码时确实会存下来（证明"为空"不是因为字段被整体忽略） ----------
        long baiduPostId = createPostAndGetId(author.token(), withDisk(
                postCreateBody(boardId, "百度网盘分享：课件", "见链接。"),
                1, "https://pan.baidu.com/s/1XyZ", "ab12"));
        assertThat(postColumn(baiduPostId, "disk_code", String.class)).isEqualTo("ab12");
    }

    /**
     * 验收项：{@code M3_reject_external_image_url}（技术方案 §8.4 第 4 条）。
     *
     * <p>帖子里的图片 URL 由用户提交，若不校验归属，任何人都能把外站图片塞进帖子 ——
     * 既是被盗链的跳板，也让"图片审核"这个面完全失控（审的是自己 OSS 里的东西，
     * 而前台渲染的是别处的图）。</p>
     *
     * <p>本用例先做<b>反证</b>（前缀内的 URL 必须被接受），再断言拒绝 ——
     * 否则一个"拒绝一切 URL"的实现也能让"外链被拒"通过，那是假绿。</p>
     */
    @Test
    void M3_reject_external_image_url() {
        TestUser author = createFreshUser();
        long boardId = createNormalBoard();

        // ---------- ① 反证：本项目 OSS 目录内的 URL 必须被接受 ----------
        Response accepted = createPost(author.token(), withImages(
                postCreateBody(boardId, "带一张合法图片的帖子", "正文"), List.of(ossImage("ok.jpg"))));
        assertThat(accepted.jsonPath().getInt("code"))
                .as("OSS 前缀内的图片必须被接受，否则下面的拒绝断言不能说明任何问题：%s", accepted.asString())
                .isZero();
        long acceptedPostId = accepted.jsonPath().getLong("data.id");

        // ---------- ② 纯外链 → 400 ----------
        Response external = createPost(author.token(), withImages(
                postCreateBody(boardId, "外链图片帖子", "正文"), List.of("https://evil.example.com/a.jpg")));
        assertThat(external.jsonPath().getInt("code"))
                .as("非本项目 OSS 前缀的图片必须被拒（§8.4 第 4 条）：%s", external.asString())
                .isEqualTo(400);

        // ---------- ③ 混合（一张合法 + 一张外链）→ 整条拒绝，不得只存合法的那张 ----------
        Response mixed = createPost(author.token(), withImages(
                postCreateBody(boardId, "混合图片帖子", "正文"),
                List.of(ossImage("half.jpg"), "https://cdn.other.example.com/b.jpg")));
        assertThat(mixed.jsonPath().getInt("code"))
                .as("只要有一张图不属于本项目，整条帖子就必须被拒（否则库里会留下半个帖子的图片行）：%s",
                        mixed.asString())
                .isEqualTo(400);

        // ---------- ④ 库里只有 ① 那一条，且它只有 1 张图 ----------
        Integer postCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM post WHERE board_id = ?", Integer.class, boardId);
        assertThat(postCount).as("被拒的两次请求不得落库").isEqualTo(1);
        assertThat(countImages(acceptedPostId)).isEqualTo(1);
    }

    /**
     * 验收项：{@code M3_image_count_capped_at_nine}（技术方案 §11 / 九宫格）。
     *
     * <p>九宫格是产品形态约束（§4.1 详情页的"图片九宫格"），第 10 张必须被拒。
     * 同时断言"被拒后库里没有多出帖子"：只回 400 但已经写了一半数据，
     * 是比不校验更糟的结果。</p>
     */
    @Test
    void M3_image_count_capped_at_nine() {
        TestUser author = createFreshUser();
        long boardId = createNormalBoard();

        // ---------- 反证：正好 9 张必须被接受 ----------
        Response nine = createPost(author.token(), withImages(
                postCreateBody(boardId, "九张图的帖子", "正文"), images(9)));
        assertThat(nine.jsonPath().getInt("code"))
                .as("9 张图是允许的上限，必须成功：%s", nine.asString()).isZero();
        long postId = nine.jsonPath().getLong("data.id");
        assertThat(nine.jsonPath().getInt("data.imageCount")).isEqualTo(9);
        assertThat(countImages(postId)).isEqualTo(9);

        // ---------- 第 10 张 → 拒绝 ----------
        Response ten = createPost(author.token(), withImages(
                postCreateBody(boardId, "十张图的帖子", "正文"), images(10)));
        assertThat(ten.jsonPath().getInt("code"))
                .as("单帖图片上限 9，第 10 张必须被拒：%s", ten.asString())
                .isEqualTo(400);

        // ---------- 被拒的请求不得落库 ----------
        Integer postCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM post WHERE board_id = ?", Integer.class, boardId);
        assertThat(postCount).as("被拒的帖子不得落库，库里应只有 9 张图那条").isEqualTo(1);
    }

    /**
     * 本任务新增用例：{@code M3_publish_visible_immediately_when_clean}
     * —— 对应 L1 裁决第 1 条与 §8.6 第 5 条「先发后审」。
     *
     * <p>这是 M3 验收标准（"详情页正确展示"）能否达成的<b>唯一前提</b>：
     * 若实现把发帖一律写成 {@code status=0}，那么详情页对所有人都是 404/403，
     * 整个 M3 的验收永远不可能达成，而"发帖成功"的响应还照样是 {@code code=0} ——
     * 单看响应体完全看不出来。所以本用例断言的是<b>匿名读者</b>能否看到，而不是作者能否看到。</p>
     */
    @Test
    void M3_publish_visible_immediately_when_clean() {
        TestUser author = createFreshUser();
        long boardId = createNormalBoard();

        Response created = createPost(author.token(),
                postCreateBody(boardId, "这是一篇完全合规的讨论帖", "正文没有任何敏感内容。"));
        assertThat(created.jsonPath().getInt("code")).isZero();
        long postId = created.jsonPath().getLong("data.id");
        assertThat(created.jsonPath().getInt("data.status"))
                .as("未命中敏感词必须直接可见（status=1），不是待审（status=0）")
                .isEqualTo(1);
        assertThat(postColumn(postId, "status", Integer.class)).isEqualTo(1);

        // ---------- 关键：没有任何人工审核动作，匿名读者就该看得到 ----------
        Response anonymous = getPostDetail(postId);
        assertThat(anonymous.statusCode()).as("匿名读者必须能打开详情：%s", anonymous.asString()).isEqualTo(200);
        assertThat(anonymous.jsonPath().getInt("code")).isZero();
        assertThat(anonymous.jsonPath().getString("data.title")).isEqualTo("这是一篇完全合规的讨论帖");

        // 列表同样可见（否则"首页看不到新帖"）
        List<String> titles = listPosts(Map.of("boardId", boardId, "sort", "latest"))
                .jsonPath().getList("data.list.title");
        assertThat(titles).contains("这是一篇完全合规的讨论帖");
    }

    /**
     * 本任务新增用例：{@code M3_publish_pending_when_sensitive_hit}
     * —— 对应 L1 裁决第 1 条与 §8.6 第 2 条。
     *
     * <p>命中敏感词的帖子：<b>收下并置 {@code status=0}</b>，不对普通用户展示，进后台队列。
     * 用例内做了反证（作者自己拿 token 能读到它，{@code status} 就是 0）——
     * 否则"匿名 404"也可能是因为帖子压根没落库，那测的就不是"待审不可见"而是"发帖失败"。</p>
     */
    @Test
    void M3_publish_pending_when_sensitive_hit() {
        TestUser author = createFreshUser();
        long boardId = createNormalBoard();

        Response created = createPost(author.token(), postCreateBody(
                boardId, "标题里带了" + SENSITIVE_WORD, "正文"));
        assertThat(created.jsonPath().getInt("code"))
                .as("命中敏感词不是拒收（那是 2001 的语义）：帖子应当被收下并进待审队列。响应：%s",
                        created.asString())
                .isZero();
        long postId = created.jsonPath().getLong("data.id");
        assertThat(created.jsonPath().getInt("data.status"))
                .as("命中敏感词 → status=0（待审核）")
                .isZero();
        assertThat(postColumn(postId, "status", Integer.class)).isZero();

        // ---------- 反证：帖子确实存在，且作者能读到自己的待审帖 ----------
        Response byAuthor = getPostDetail(author.token(), postId);
        assertThat(byAuthor.jsonPath().getInt("code"))
                .as("作者必须能看到自己的待审帖（否则他不知道发生了什么）：%s", byAuthor.asString())
                .isZero();
        assertThat(byAuthor.jsonPath().getInt("data.status")).isZero();

        // ---------- 断言：普通读者看不到 ----------
        Response anonymous = getPostDetail(postId);
        assertThat(anonymous.statusCode())
                .as("待审帖对普通用户不可见（§8.6 第 2 条）：%s", anonymous.asString())
                .isEqualTo(404);
        assertThat(anonymous.jsonPath().getInt("code")).isEqualTo(404);

        // 列表与搜索也不得出现（否则"不对普通用户展示"等于没做）
        // 注意：用字符串比较而不是 (int) 强转 —— JSON 里小整数是 Integer、
        // 大整数是 Long，强转会在 id 变大后静默给出错误结果
        List<String> listedIds = asIdStrings(listPosts(Map.of("boardId", boardId)).jsonPath().getList("data.list.id"));
        assertThat(listedIds).as("待审帖不得出现在列表里").doesNotContain(String.valueOf(postId));
        List<String> searchedIds = asIdStrings(searchPosts(SENSITIVE_WORD).jsonPath().getList("data.list.id"));
        assertThat(searchedIds).as("待审帖不得出现在搜索结果里").doesNotContain(String.valueOf(postId));
    }

    /** 把 JSON 里的 id 列表统一成字符串（避免 Integer/Long 混淆导致的假断言）。 */
    private static List<String> asIdStrings(List<Object> ids) {
        List<String> result = new ArrayList<>();
        if (ids != null) {
            for (Object id : ids) {
                result.add(String.valueOf(id));
            }
        }
        return result;
    }

    /** 造 n 张本项目 OSS 目录下的图片 URL（文件名各不相同，便于断言顺序）。 */
    private static List<String> images(int n) {
        List<String> urls = new ArrayList<>(n);
        for (int i = 1; i <= n; i++) {
            urls.add(OSS_IMAGE_PREFIX + "2026/09/15/img" + i + ".jpg");
        }
        return urls;
    }
}
