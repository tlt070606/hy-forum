package com.hyforum.post;

import com.hyforum.audit.InMemorySensitiveTextChecker;
import com.hyforum.support.WebIntegrationTestBase;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M3（版块 + 帖子）接口集成测试的公共父类。
 *
 * <h2>为什么自己写一份而不是复用 {@code com.hyforum.auth.AuthApiTestSupport}</h2>
 * <p>那个类是 <b>W1-M1 任务独占</b>的文件（见 docs/agents/工作计划.md §2），
 * 且它的基线恢复是围绕「注册/登录/邀请码」写的（会把 {@code sys_config}、
 * {@code admin}、{@code invite_code} 全部重建）。M3 需要的是另一组基线：
 * <b>一个可登录的普通用户 + 一个资源版块 + 一个普通版块 + 一条敏感词</b>。
 * 硬去复用会让 M3 的用例依赖 M1 的基线细节 —— 一旦 M1 调整基线，M3 会跟着红，
 * 而且红的原因与被测行为无关。这里只继承共享基建 {@link WebIntegrationTestBase}。</p>
 *
 * <h2>{@code @TestPropertySource} 里的取值为什么显式写一遍</h2>
 * <p>{@code aliyun.oss.*} 是 <b>测试专用取值</b>：图片 URL 归属校验
 * （技术方案 §8.4 第 4 条）必须有一个「本项目 OSS 前缀」才能成立。
 * 主配置里虽已给 endpoint/bucket 配了本机默认值，但用例把它们<b>显式写死</b>，
 * 这样测的是"读配置这个行为"，而不是"碰巧与本机默认值一致"——
 * 将来有人改了 yml 的默认值，这里也会立刻红，而不是静默地换个前缀继续跑绿。</p>
 * <p>这里不含任何 AccessKey（签名与密钥属第二交付段，且一律走环境变量）。</p>
 */
@TestPropertySource(properties = {
        // 图片 URL 允许前缀 = https://{bucket}.{endpoint}/ + image-dir（OSS 虚拟主机域名形态）
        "aliyun.oss.endpoint=oss-cn-beijing.aliyuncs.com",
        "aliyun.oss.bucket-name=hy-forum-2026",
        "aliyun.oss.image-dir=post/",
        // 契约值：单帖图片 ≤ 9（技术方案 §11）、编辑窗口 30 分钟（§6.5）
        "hy.post.max-images=9",
        "hy.post.edit-window-minutes=30",
        // 契约值：发帖限流（技术方案 §8.7）
        "hy.post.new-user-window-hours=24",
        "hy.post.new-user-post-limit=3",
        "hy.post.post-per-hour-limit=10",
        // 登录/注册的 IP 限流：M3 用例会多次登录，配额与 M1 保持一致（50/min）
        "hy.rate-limit.ip-per-minute=50"
})
public abstract class M3ApiTestSupport extends WebIntegrationTestBase {

    /** 测试用的 OSS 图片前缀（与上面两个属性拼出来的一致；断言里直接引用它，避免两处各写一遍）。 */
    protected static final String OSS_IMAGE_PREFIX =
            "https://hy-forum-2026.oss-cn-beijing.aliyuncs.com/post/";

    /** 测试用户口令：满足契约的"8–32 位且含字母与数字"。 */
    protected static final String TEST_PASSWORD = "Passw0rd123";

    /** 测试库里的敏感词：用于验证"命中敏感词 → status=0 待审"（§8.6 第 2 条）。 */
    protected static final String SENSITIVE_WORD = "测试违禁词";

    /** 进程内自增序号：让每次造的用户名/版块 slug 都不撞唯一索引。 */
    private static final AtomicInteger SEQ = new AtomicInteger();

    /** 本次 JVM 的短标签（4 位十六进制），避免与上一次运行的残留撞唯一键。 */
    private static final String RUN_TAG =
            String.format("%04x", ThreadLocalRandom.current().nextInt(0x10000));

    @Autowired
    protected PasswordEncoder passwordEncoder;

    /**
     * 敏感词检查器（M1 交付的朴素实现）。
     *
     * <p>这里引用的是 {@code com.hyforum.audit} 包的<b>实现类</b>，而不是
     * {@code common.audit.SensitiveTextChecker} 接口 —— 因为用例需要调用
     * {@code reload()} 把刚写库的词加载进内存，该方法不在接口上。
     * 铁律 3 约束的是<b>主代码</b>（ArchUnit 用 {@code DoNotIncludeTests} 导入），
     * 且 M1 的 {@code AuthApiTestSupport} 已用同一方式，此处沿用同一惯例。</p>
     */
    @Autowired
    protected InMemorySensitiveTextChecker sensitiveTextChecker;

    /**
     * 每个用例前把测试库与 Redis 恢复成确定起点。
     *
     * <p>清表交给父类（{@link #tablesToClean()}），这里补三件父类管不到的事：
     * 敏感词库、Redis 中的浏览量增量与限流计数。</p>
     */
    @BeforeEach
    void restoreM3Baseline() {
        // ① 敏感词：内存词库是启动时加载的，写完库必须 reload，
        //    否则用例测的是"上一次加载的旧词库"（这正是 H3 登记的坑）
        jdbcTemplate.update("DELETE FROM sensitive_word WHERE word = ?", SENSITIVE_WORD);
        jdbcTemplate.update("INSERT INTO sensitive_word (word) VALUES (?)", SENSITIVE_WORD);
        sensitiveTextChecker.reload();

        // ② Redis 清场。三类键都必须清，否则用例之间会互相污染：
        //    · hy:post:view:*  —— 浏览量增量是"跨请求累积"的状态，残留会让 viewCount 断言随机失败；
        //    · hy:rl:*         —— 限流计数残留会让"第 4 帖必须被拒"的用例莫名其妙地绿或红；
        //    · hy:token:*      —— 清表会重置自增 id，旧 token 可能映射到新用户（越权假象的来源）。
        clearRedisKeys("hy:post:view:*");
        clearRedisKeys("hy:rl:*");
        clearRedisKeys("hy:token:*");
    }

    /** 父类声明的清表清单：帖子相关三张 + user（用例自己造用户，必须从零开始）。 */
    @Override
    protected String[] tablesToClean() {
        return new String[]{"post_image", "post", "board", "user"};
    }

    /** 按 pattern 删除 Redis 键（仅测试用；生产代码不用 KEYS，见 PostViewCounter 的说明）。 */
    protected void clearRedisKeys(String pattern) {
        java.util.Set<String> keys = stringRedisTemplate.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
        }
    }

    // ==================================================================
    // 造数：用户 / 版块
    // ==================================================================

    /** 一个可登录的测试用户（id + 用户名 + 已登录的 token）。 */
    protected record TestUser(long id, String username, String token) {
    }

    /**
     * 造一个"新注册用户"（{@code created_at = NOW()}）并登录。
     *
     * <p>用于限流用例：§8.7 对新注册用户 24 小时内限 3 帖，判定依据就是
     * {@code user.created_at}，因此这个时间必须是后端自己写出来的「现在」，
     * 不能由测试随便挑一个值。</p>
     */
    protected TestUser createFreshUser() {
        return createUser("NOW()", "新用户");
    }

    /**
     * 造一个"老用户"（{@code created_at = 2 天前}）并登录。
     *
     * <p>用于限流用例的反证：他的额度是 10 帖/小时，不该被新用户的 3 帖额度限制。</p>
     */
    protected TestUser createEstablishedUser() {
        return createUser("DATE_SUB(NOW(), INTERVAL 2 DAY)", "老用户");
    }

    private TestUser createUser(String createdAtExpression, String nicknamePrefix) {
        String username = uniqueUsername();
        // created_at 表达式是测试自己拼的常量（不含任何外部输入），不用参数占位；
        // 用户名/口令哈希仍走参数化，禁止字符串拼接 SQL（技术方案 §9）
        jdbcTemplate.update(
                "INSERT INTO user (username, password_hash, nickname, status, created_at) VALUES (?, ?, ?, 1, "
                        + createdAtExpression + ")",
                username, passwordEncoder.encode(TEST_PASSWORD), nicknamePrefix + username);
        Long id = jdbcTemplate.queryForObject("SELECT id FROM user WHERE username = ?", Long.class, username);
        assertThat(id).as("造用户后必须能查到 id").isNotNull();
        return new TestUser(id, username, login(username));
    }

    /** 登录并取出 token（走真实 HTTP，与前端同一条路径）。 */
    protected String login(String username) {
        Response response = RestAssured.given()
                .contentType(ContentType.JSON)
                .body(loginBody(username))
                .post("/api/auth/login");
        String token = response.jsonPath().getString("data.token");
        assertThat(token)
                .as("登录必须成功，否则后续用例全部无意义。响应：%s", response.asString())
                .isNotBlank();
        return token;
    }

    /** 登录请求体（§6.2）。 */
    protected Map<String, Object> loginBody(String username) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("password", TEST_PASSWORD);
        return body;
    }

    /** 用户名：{@code m3u<运行标签><序号>}，最长 11 字符，远小于 VARCHAR(20)。 */
    private static String uniqueUsername() {
        return "m3u" + RUN_TAG + String.format("%04d", SEQ.incrementAndGet() % 10000);
    }

    /**
     * 造一个版块。
     *
     * @param name       版块名
     * @param isResource 1 = 资源版块（发帖必须给网盘字段）
     * @param status     1 启用 / 0 停用
     */
    protected long createBoard(String name, int isResource, int status) {
        String slug = "m3b" + RUN_TAG + String.format("%04d", SEQ.incrementAndGet() % 10000);
        jdbcTemplate.update("INSERT INTO board (name, slug, is_resource, sort, status) VALUES (?, ?, ?, ?, ?)",
                name, slug, isResource, SEQ.incrementAndGet(), status);
        Long id = jdbcTemplate.queryForObject("SELECT id FROM board WHERE slug = ?", Long.class, slug);
        assertThat(id).as("造版块后必须能查到 id").isNotNull();
        return id;
    }

    /** 资源版块（is_resource=1）。 */
    protected long createResourceBoard() {
        return createBoard("资源分享", 1, 1);
    }

    /** 普通版块（is_resource=0）。 */
    protected long createNormalBoard() {
        return createBoard("综合讨论", 0, 1);
    }

    /** 生成本项目 OSS 目录下的图片 URL（形状与直传产物一致）。 */
    protected String ossImage(String fileName) {
        return OSS_IMAGE_PREFIX + fileName;
    }

    // ==================================================================
    // 请求体构造：契约字段名只在这里写一次
    // ==================================================================

    /** 发帖请求体（§6.5 POST /api/posts）。 */
    protected Map<String, Object> postCreateBody(long boardId, String title, String content) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("boardId", boardId);
        body.put("title", title);
        body.put("content", content);
        return body;
    }

    /** 改帖请求体（§6.5 PUT /api/posts/{id}；不含 boardId —— 版块不可改）。 */
    protected Map<String, Object> postUpdateBody(String title, String content) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        body.put("content", content);
        return body;
    }

    /** 给请求体加图片 URL 列表。 */
    protected Map<String, Object> withImages(Map<String, Object> body, List<String> images) {
        body.put("images", new ArrayList<>(images));
        return body;
    }

    /** 给请求体加网盘字段（§6.5：资源版需 diskType、diskUrl，diskCode 可选）。 */
    protected Map<String, Object> withDisk(Map<String, Object> body, Integer diskType,
                                           String diskUrl, String diskCode) {
        body.put("diskType", diskType);
        body.put("diskUrl", diskUrl);
        body.put("diskCode", diskCode);
        return body;
    }

    // ==================================================================
    // 请求动作
    // ==================================================================

    /** 发帖（带 token）。 */
    protected Response createPost(String token, Map<String, Object> body) {
        return RestAssured.given()
                .contentType(ContentType.JSON)
                .header("Authorization", token)
                .body(body)
                .post("/api/posts");
    }

    /** 改帖（带 token）。 */
    protected Response updatePost(String token, long postId, Map<String, Object> body) {
        return RestAssured.given()
                .contentType(ContentType.JSON)
                .header("Authorization", token)
                .body(body)
                .put("/api/posts/" + postId);
    }

    /** 删帖（带 token）。 */
    protected Response deletePost(String token, long postId) {
        return RestAssured.given()
                .header("Authorization", token)
                .delete("/api/posts/" + postId);
    }

    /** 帖子详情（匿名）。 */
    protected Response getPostDetail(long postId) {
        return RestAssured.given().get("/api/posts/" + postId);
    }

    /** 帖子详情（带 token，用于"作者可见自己待审帖"这类可选鉴权场景）。 */
    protected Response getPostDetail(String token, long postId) {
        return RestAssured.given()
                .header("Authorization", token)
                .get("/api/posts/" + postId);
    }

    /** 帖子列表。 */
    protected Response listPosts() {
        return RestAssured.given().get("/api/posts");
    }

    /** 帖子列表（带查询参数）。 */
    protected Response listPosts(Map<String, Object> query) {
        return RestAssured.given().queryParams(query).get("/api/posts");
    }

    /** 搜索（带关键词）。 */
    protected Response searchPosts(String keyword) {
        return RestAssured.given().queryParam("keyword", keyword).get("/api/posts/search");
    }

    // ==================================================================
    // 落库断言用到的小工具
    // ==================================================================

    /** 某个帖子的某列值（列名由测试自己写死，不接受外部输入）。 */
    protected <T> T postColumn(long postId, String column, Class<T> type) {
        return jdbcTemplate.queryForObject("SELECT " + column + " FROM post WHERE id = ?", type, postId);
    }

    /** 某帖的图片行数。 */
    protected int countImages(long postId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM post_image WHERE post_id = ?", Integer.class, postId);
        return count == null ? 0 : count;
    }

    /**
     * 发帖成功并把 id 取出来。
     *
     * <p>先断言 {@code code=0} 再取 id：直接 {@code getInt("data.id")} 在"接口还没实现"
     * 时会抛出难以阅读的 {@code NullPointerException}（把 null 拆箱），
     * 而失败信息里应该出现的是<b>真实的响应报文</b>（例如 404），那才是能定位问题的证据。</p>
     */
    protected long createPostAndGetId(String token, Map<String, Object> body) {
        Response response = createPost(token, body);
        assertThat(response.jsonPath().getInt("code"))
                .as("发帖必须成功（否则后续断言没有意义）。响应：%s", response.asString())
                .isZero();
        Object id = response.jsonPath().get("data.id");
        assertThat(id)
                .as("发帖响应必须带新帖 id。响应：%s", response.asString())
                .isNotNull();
        return Long.parseLong(String.valueOf(id));
    }
}
