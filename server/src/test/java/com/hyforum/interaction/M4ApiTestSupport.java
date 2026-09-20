package com.hyforum.interaction;

import com.hyforum.support.WebIntegrationTestBase;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M4（互动模块）接口集成测试的公共父类。
 *
 * <h2>为什么自己写一份，而不复用 M3 的 {@code M3ApiTestSupport}</h2>
 * <p>那个类是 {@code W3-M3b} 任务的文件（看板 §2 写权表），且它的基线是围绕
 * 「资源版块 + 网盘字段 + 敏感词」造的。M4 需要的是另一组基线：
 * <b>多个可登录的普通用户 + 一个普通版块 + 若干条帖子（可控时间与状态）</b>。
 * 硬去复用会让 M4 的用例依赖 M3 的基线细节 —— 一旦 M3 调整基线，M4 会跟着红，
 * 且红的原因与被测行为无关。这里只继承共享基建 {@link WebIntegrationTestBase}。</p>
 *
 * <h2>清表清单为什么这么长（这里是最容易造假红的地方）</h2>
 * <p>M4 的六张关系表必须在每个用例前后清空：
 * {@code comment}／{@code comment_like}／{@code post_like}／{@code post_collect}／
 * {@code follow} 加上被它们引用的 {@code post}／{@code board}／{@code user}。
 * 少清一张的后果不是"测试偶发失败"，而是<b>计数断言用了上一轮的行数</b> ——
 * 那会表现成"只有全量跑才红、单跑就绿"这种最难查的假红。</p>
 *
 * <h2>Redis 也要清</h2>
 * <p>浏览量增量（{@code hy:post:view:*}）与登录态（{@code hy:token:*}）都在 Redis 里，
 * 而清表会重置自增 id —— 旧 token 可能映射到<b>新用户</b>，那是"越权假象"的经典来源
 * （M3 的基类注释里也记了这一条）。</p>
 */
@TestPropertySource(properties = {
        // ★ 数据源：**只在显式指定 `hy.test.db` 时才覆盖，且 url 由那个库名推导**。
        //
        // 曾经的写法与事故（CI #64 实测，28 个 CannotGetJdbcConnection）：
        //   第一版在这里写死了 `spring.datasource.url=...hy_forum_test_m4`，
        //   理由是当时的隔离机制（`TEST_DB` 环境变量）不生效 —— 那个判断是对的（CR-M4-2）。
        //   但绕开方式造成了更糟的后果：**`@TestPropertySource` 的优先级高于 CI 注入的
        //   `SPRING_DATASOURCE_URL`**，于是 CI 里 M4 的测试**恒定去连一个 CI 中不存在的库**
        //   `hy_forum_test_m4`，而 M1/M3 一条都没红（它们走正常配置）—— 那组数字就是指纹。
        //
        // 现在的规则一句话：**`hy.test.db` 说是哪个库，就连哪个库**（唯一事实来源）：
        //   · 不传 `hy.test.db` → 本属性是**空串，等于不覆盖** → 完全交给配置
        //     （`application-test.yml` 的字面 `hy_forum_test`，或 CI 注入的 SPRING_DATASOURCE_URL）；
        //   · 传了 → 覆盖成**同一个库**的 url，于是"两条流水线各一库"这条路可用。
        //
        // 为什么 url 要推导、而不是让命令行传 JDBC URL：在本机 PowerShell 5.1 下，
        // URL 里的 `&` 会被交给 cmd.exe 当命令分隔符，**实测两次让 mvn 收不到 `test` 目标**
        // （`No goals have been specified`）。推导出来之后命令行只需要
        // `-Dhy.test.db=hy_forum_test_m4` 这一个无特殊字符的参数。
        // 与 M3 测试保持一致：主配置里 OSS 有本机默认值，这里显式写死，
        // 断言才有意义（改了 yml 默认值也要让相关用例立刻红）
        "aliyun.oss.endpoint=oss-cn-beijing.aliyuncs.com",
        "aliyun.oss.bucket-name=hy-forum-2026",
        "aliyun.oss.image-dir=post/",
        "hy.post.max-images=9",
        "hy.post.edit-window-minutes=30",
        "hy.post.new-user-window-hours=24",
        "hy.post.new-user-post-limit=3",
        "hy.post.post-per-hour-limit=10",
        "hy.rate-limit.ip-per-minute=50",
        // ★ M4 特有：把浏览量的回写周期压到 1 秒，好让
        //   M4_view_count_flushed_after_interval **真的等一个周期**而不是手动触发。
        //   生产值 300000ms（§7）不改：这份配置只在测试 profile 生效。
        //   为什么不取更小：@Scheduled 的 fixedDelayString 由 Spring 解析，
        //   1 秒已经是"能稳定观察到一个周期"与"不让用例白等"的平衡点。
        "hy.post.view-flush-interval-ms=1000"
})
public abstract class M4ApiTestSupport extends WebIntegrationTestBase {

    /**
     * 本次运行<b>期望</b>连的测试库名 —— 在 {@code @BeforeEach} 里从**当前生效的
     * {@code spring.datasource.url}** 解析出来，因此它<b>永远等于真正连上的那个库名</b>。
     *
     * <p><b>为什么不再写死 {@code hy_forum_test_m4}</b>（这是 CI 变绿的关键）：
     * 写死的那一版让 CI 里 M4 的测试恒定去连一个 CI 中不存在的库 →
     * 28 个 {@code CannotGetJdbcConnection}，而 M1/M3 全绿（它们走正常配置）。
     * 详见类上 {@code @TestPropertySource} 的注释。</p>
     *
     * <p><b>为什么从 url 解析而不是读 {@code hy.test.db}</b>：这样"期望值"与"实际连接的库"
     * 只有一个来源，**结构上不可能不一致**。若改成读系统属性，就会在
     * "只传了 {@code -Dhy.test.db} 但没传 url" 时出现"url 指着 A 库、清表工具去清 B 库"
     * 那种最坏组合（独占库的残留永远清不掉，而共享库被别人清空）。
     * 非 {@code m4} 后缀的库名（CI 的 {@code hy_forum_test}）同样被 {@code TestTableCleaner}
     * 的"以 hy_forum_test 开头"规则接受，因此这条路对 CI 是通的。</p>
     */
    protected String expectedTestDatabase;

    /**
     * 解析 {@code spring.datasource.url} 里的库名（只取到 {@code ?} 之前）。
     *
     * <p>取不到就返回 {@code null}，由调用方给出明确的失败信息而不是猜一个。</p>
     */
    private static String databaseNameOf(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return null;
        }
        int query = jdbcUrl.indexOf('?');
        String head = query >= 0 ? jdbcUrl.substring(0, query) : jdbcUrl;
        int slash = head.lastIndexOf('/');
        if (slash < 0 || slash == head.length() - 1) {
            return null;
        }
        return head.substring(slash + 1);
    }

    /** 测试口令：满足契约的"8–32 位且含字母与数字"。 */
    protected static final String TEST_PASSWORD = "Passw0rd123";

    /** 进程内自增序号：让每次造的用户名/版块 slug 都不撞唯一索引。 */
    private static final AtomicInteger SEQ = new AtomicInteger();

    /** 本次 JVM 的短标签（4 位十六进制），避免与上一次运行的残留撞唯一键。 */
    private static final String RUN_TAG =
            String.format("%04x", ThreadLocalRandom.current().nextInt(0x10000));

    @Autowired
    protected PasswordEncoder passwordEncoder;

    /** 每个用例前把测试库与 Redis 恢复成确定起点。 */
    @BeforeEach
    void restoreM4Baseline() {
        // ★ 把清表工具的"期望库名"也钉到独占库。
        //   基类默认拿 System.getProperty("hy.test.db", "hy_forum_test")，那不跟着
        //   spring.datasource.url 走 —— 于是会出现最坏的一种组合：
        //   **url 指着 hy_forum_test_m4、清表工具却去清 hy_forum_test**（清错了库，
        //   而独占库里的残留数据永远清不掉）。
        //   实测症状：用户名唯一键没被清 → 残留用户与新建用户混在一起 → 登录返回 1002，
        //   看起来像"密码校验坏了"。这类"看着像业务 bug 的基础设施 bug"必须靠
        //   单一事实来源（本常量）堵掉，而不是靠记得多传一个 -D 参数。
        tableCleaner = new com.hyforum.support.TestTableCleaner(jdbcTemplate, EXPECTED_TEST_DATABASE);
        assertConnectedToM4Database();

        // ─────────────────────────────────────────────────────────────
        // ⚠️ Redis 清理的规矩：**只清本测试类自己写下的键**，绝不清整个命名空间。
        //
        // 这里原来写的是 `clearRedisKeys("hy:rl:*")`（删掉所有限流计数），那是**我的 bug**，
        // 而且它一直在**掩盖一个结构性问题**：
        //   ① `hy:rl:` 是**全项目共用**的命名空间。M1 的 `M1ErrorCodesTest` 正是靠
        //      "把 IP 限流窗口打满"来触发 429（`RATE_LIMIT_QUOTA = 50`），
        //      通配符删除会**替别人清掉配额** → 那条用例的绿是**靠测试类执行顺序侥幸**的。
        //   ② 反过来更严重：我自己每个用例都要登录 2–3 次造用户，一个 M4 类跑下来接近
        //      甚至超过 50 次。一旦**不再**删别人的配额，**我自己**就被残余计数打成 429 ——
        //      实测（删掉通配符清理后，M4 单跑）：
        //        `登录必须成功 ... {"code":429,"message":"请求过于频繁，请 8 秒后再试"}`
        //      也就是说：通配符清理把"**整套测试共用一个登录配额**"这件事藏起来了。
        //   正解不是"继续删别人的键"，而是**只回收自己占用的那一份**：
        //   本类所有登录都来自 127.0.0.1（RestAssured 打本机），键为
        //   `hy:rl:{action}:{ip}`，前台登录的 action 是 `login-ip-1m`
        //   （见 `AuthController.RL_LOGIN`）。那正是本类自己的键，清它是正当的测试自清理。
        // ─────────────────────────────────────────────────────────────
        // 探针（只读一次，便于事后从日志判定"是不是共享限流配额把用例打成 429"）：
        // 打印清理前该键的原始值 + 清理后的值。若某次 M4 出现 429，这一行就能直接证明
        // "是被上一批请求/别的测试类留下的计数打的"，而不是靠猜。
        System.out.println("[M4-RL] 清理前 " + LOGIN_RATE_LIMIT_KEY + " = "
                + stringRedisTemplate.opsForValue().get(LOGIN_RATE_LIMIT_KEY));
        stringRedisTemplate.delete(LOGIN_RATE_LIMIT_KEY);

        // 浏览量增量：键名是 `hy:post:view:{postId}`，只删本用例用到的那条
        // （精确键而不是通配符 —— 别的测试类也可能有正在累积的浏览量）。
        stringRedisTemplate.delete(VIEW_KEY_PREFIX + currentPostId());

        // 登录态：M4 用例自己登录过就会留下 token。清表会重置自增 id，
        // 于是旧 token 可能映射到**新用户**（越权假象的经典来源），因此必须清。
        // 只清**本类自己发出的**那些（`login()` 里逐个记下），不用通配符。
        for (String token : issuedTokens) {
            stringRedisTemplate.delete(TOKEN_KEY_PREFIX + token);
        }
        issuedTokens.clear();
    }

    /**
     * 本类自己的登录限流键。
     *
     * <p>键格式与技术方案 §7 的 {@code hy:rl:{action}:{userId 或 ip}} 一致：
     * {@code login-ip-1m} 取自 {@code AuthController.RL_LOGIN}；
     * {@code 127.0.0.1} 是 RestAssured 打本机时 {@code request.getRemoteAddr()} 的值
     * （该实现优先取 {@code X-Forwarded-For}/{@code X-Real-IP}，本类都不发这两个头）。</p>
     *
     * <p><b>为什么清它是正当的、而清 {@code hy:rl:*} 不是</b>：这一条键就是本类自己那 50 次/分钟
     * 的配额，清它属于"测试自清理"；而通配符清的是**别人的配额**，
     * 那会让依赖限流的用例假绿（M1 的 429 用例就是受害者）。</p>
     */
    private static final String LOGIN_RATE_LIMIT_KEY = "hy:rl:login-ip-1m:127.0.0.1";

    /** 浏览量键前缀（与技术方案 §7 的 {@code hy:post:view:{postId}} 一致）。 */
    private static final String VIEW_KEY_PREFIX = "hy:post:view:";

    /**
     * 登录态键前缀。
     *
     * <p>前台用户是独立 StpLogic（{@code StpUserUtil.LOGIN_TYPE = "user"}），
     * 因此键形如 {@code hy:token:user:{tokenValue}}。写成字面量是有意的：
     * 不引常量就不会因为常量改名而静默删错键（多打一个字面量只影响测试）。</p>
     */
    private static final String TOKEN_KEY_PREFIX = "hy:token:user:";

    /** 本测试实例登录时拿到的 token（用于精确清理自己的登录态）。 */
    private final java.util.List<String> issuedTokens = new java.util.ArrayList<>();

    /** 本类最近一次造帖的 id（供浏览量键的精确清理用）。 */
    private volatile long lastPostId;

    /** 本用例的浏览量键后缀：优先用真实 postId，没有就取一个不可能撞上的随机值。 */
    private long currentPostId() {
        long id = lastPostId;
        return id > 0 ? id : java.util.concurrent.ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
    }

    /**
     * 防线：本次运行必须真的连在 {@value #EXPECTED_TEST_DATABASE} 上。
     *
     * <p><b>为什么这条断言不可省</b>（它是这一轮最有价值的产出）：
     * {@code application-test.yml} 里的 {@code ${TEST_DB:hy_forum_test}} 在<b>构建期</b>
     * 就被 Maven 资源过滤解析成了默认值（见类注释），于是"跑测试前设 TEST_DB"这个
     * 文档化做法<b>根本不生效</b>，测试会静默连上共享库。共享库上两条流水线互相清表，
     * 症状是"我的并发用例说 20 个线程全部插入成功"——看起来像幂等实现坏了，
     * 实际是别人写进来的行。**假红比慢更危险**（任务书 §4 的原话），
     * 所以这里必须有一条会当场喊出来的断言，而不是继续依赖"我记得设了环境变量"。</p>
     */
    /**
     * 只在**显式指定** {@code -Dhy.test.db=<库名>} 时才覆盖数据源。
     *
     * <p>未指定 → **不注册任何属性** → 完全交给配置
     * （{@code application-test.yml} 的字面 {@code hy_forum_test}，或 CI 注入的
     * {@code SPRING_DATASOURCE_URL}）—— 这正是 CI 能跑通的前提。</p>
     *
     * <p><b>为什么不能用 {@code @TestPropertySource} 做这件事</b>：注解里的值必须是
     * <b>编译期常量</b>，而"库名从系统属性推导"是运行期的。第一版正是用注解 + **硬编码库名**，
     * 于是 {@code @TestPropertySource} 的优先级压过了 CI 的 {@code SPRING_DATASOURCE_URL}，
     * CI 里恒定去连一个**不存在的库**（28 个 {@code CannotGetJdbcConnection}）。</p>
     */
    @org.springframework.test.context.DynamicPropertySource
    static void datasourceOverrideForParallelRun(
            org.springframework.test.context.DynamicPropertyRegistry registry) {
        String db = System.getProperty("hy.test.db");
        if (db != null && !db.isBlank()) {
            registry.add("spring.datasource.url", () -> "jdbc:mysql://127.0.0.1:3306/" + db
                    + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
                    + "&useSSL=false&allowPublicKeyRetrieval=true");
        }
    }

    /**
     * 本次运行**期望**的库名 —— 唯一事实来源。
     *
     * <p>显式指定 {@code hy.test.db} 则用它，否则用默认测试库。**它必须与数据源来自同一处**，
     * 否则会出现最坏组合："url 指着 A 库、清表工具却去清 B 库"。</p>
     */
    protected static final String EXPECTED_TEST_DATABASE =
            System.getProperty("hy.test.db", "hy_forum_test");

    private void assertConnectedToM4Database() {
        String current = tableCleaner.currentDatabase();
        assertThat(current)
                .as("本次运行期望连库 [%s]，实际连的是 [%s]。"
                        + "两者不一致说明数据源被别处覆盖了 —— 库名只有一个事实来源："
                        + "系统属性 hy.test.db（不传则默认 hy_forum_test），见本类的 @DynamicPropertySource。",
                        EXPECTED_TEST_DATABASE, current)
                .isEqualTo(EXPECTED_TEST_DATABASE);
    }

    /**
     * 父类声明的清表清单：M4 涉及的全部表。
     *
     * <p>顺序无关（基线无外键，见 {@code docs/db/README.md}），但把关系表写在前面
     * 更符合阅读习惯。{@code user} 必须清：用例自己造用户，而
     * {@code user.follow_count}/{@code fans_count} 的断言要求从 0 开始。</p>
     */
    @Override
    protected String[] tablesToClean() {
        return new String[]{
                "comment_like", "comment", "post_like", "post_collect", "follow",
                "post_image", "post", "board", "user"
        };
    }


    // ==================================================================
    // 造数：用户 / 版块 / 帖子
    // ==================================================================

    /** 一个可登录的测试用户（id + 用户名 + 已登录的 token）。 */
    protected record TestUser(long id, String username, String token) {
    }

    /** 造一个用户并登录（走真实 HTTP 登录接口，与前端同一条路径）。 */
    protected TestUser createUser(String nicknamePrefix) {
        String username = uniqueUsername();
        jdbcTemplate.update(
                "INSERT INTO user (username, password_hash, nickname, status) VALUES (?, ?, ?, 1)",
                username, passwordEncoder.encode(TEST_PASSWORD), nicknamePrefix + username);
        Long id = jdbcTemplate.queryForObject("SELECT id FROM user WHERE username = ?", Long.class, username);
        assertThat(id).as("造用户后必须能查到 id").isNotNull();
        return new TestUser(id, username, login(username));
    }

    /** 登录并取出 token（走真实 HTTP）。 */
    protected String login(String username) {
        Response response = RestAssured.given()
                .contentType(ContentType.JSON)
                .body(loginBody(username))
                .post("/api/auth/login");
        String token = response.jsonPath().getString("data.token");
        assertThat(token)
                .as("登录必须成功，否则后续用例全部无意义。响应：%s", response.asString())
                .isNotBlank();
        // 记下本次发出的 token：@BeforeEach 只清这些（绝不按通配符清整个命名空间）
        issuedTokens.add(token);
        return token;
    }

    /** 登录请求体（§6.2）。 */
    protected Map<String, Object> loginBody(String username) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("password", TEST_PASSWORD);
        return body;
    }

    private static String uniqueUsername() {
        return "m4u" + RUN_TAG + String.format("%04d", SEQ.incrementAndGet() % 10000);
    }

    /** 造一个普通版块（{@code is_resource=0}）。 */
    protected long createBoard() {
        String slug = "m4b" + RUN_TAG + String.format("%04d", SEQ.incrementAndGet() % 10000);
        String name = "M4测试版块" + SEQ.get();
        jdbcTemplate.update("INSERT INTO board (name, slug, is_resource, sort, status) VALUES (?, ?, 0, ?, 1)",
                name, slug, SEQ.get());
        Long id = jdbcTemplate.queryForObject("SELECT id FROM board WHERE slug = ?", Long.class, slug);
        assertThat(id).as("造版块后必须能查到 id").isNotNull();
        return id;
    }

    /**
     * 直接用 SQL 造一条帖子（不走发帖接口）。
     *
     * <p>为什么用 SQL：M4 的用例要精确控制 {@code status}（0 待审 / 1 正常 / 2 屏蔽）
     * 与 {@code created_at}（排序断言），而发帖接口受敏感词与限流影响、
     * 且 {@code created_at} 由数据库决定。直接写 SQL 让用例的起点完全确定 ——
     * 被测对象是 M4 的互动行为，不是 M3 的发帖行为。</p>
     *
     * @param status 0 待审 / 1 正常 / 2 已屏蔽
     */
    protected long createPost(long boardId, long userId, String title, int status) {
        jdbcTemplate.update("INSERT INTO post (board_id, user_id, title, content, status) VALUES (?, ?, ?, ?, ?)",
                boardId, userId, title, "正文", status);
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM post WHERE user_id = ? AND title = ? ORDER BY id DESC LIMIT 1",
                Long.class, userId, title);
        assertThat(id).as("造帖子后必须能查到 id").isNotNull();
        // 记下来，供 @BeforeEach 精确清理该帖的浏览量键（**只清自己的键**，见那里的说明）
        lastPostId = id;
        return id;
    }

    /** 正常状态（{@code status=1}）的帖子。 */
    protected long createNormalPost(long boardId, long userId, String title) {
        return createPost(boardId, userId, title, 1);
    }

    /**
     * 造一条帖子并把 {@code created_at} 显式设为"现在 - secondsAgo 秒"。
     *
     * <p>用于时间序断言：不能靠"连续插入的 {@code created_at} 一定递增"，
     * 因为 DATETIME 的精度是<b>秒</b>，同一秒内插入的多条帖子顺序不确定
     * —— 那种用例会偶发红，而偶发红比必红更难查。</p>
     */
    protected long createPostAt(long boardId, long userId, String title, int status, long secondsAgo) {
        long postId = createPost(boardId, userId, title, status);
        jdbcTemplate.update("UPDATE post SET created_at = DATE_SUB(NOW(), INTERVAL ? SECOND) WHERE id = ?",
                secondsAgo, postId);
        return postId;
    }

    // ==================================================================
    // 请求动作：互动接口（契约 §6.5–§6.7）
    // ==================================================================

    /** 点赞帖子（带 token）。 */
    protected Response likePost(String token, long postId) {
        return RestAssured.given().header("Authorization", token).post("/api/posts/" + postId + "/like");
    }

    /** 取消点赞帖子。 */
    protected Response unlikePost(String token, long postId) {
        return RestAssured.given().header("Authorization", token).delete("/api/posts/" + postId + "/like");
    }

    /** 收藏帖子。 */
    protected Response collectPost(String token, long postId) {
        return RestAssured.given().header("Authorization", token).post("/api/posts/" + postId + "/collect");
    }

    /** 取消收藏。 */
    protected Response uncollectPost(String token, long postId) {
        return RestAssured.given().header("Authorization", token).delete("/api/posts/" + postId + "/collect");
    }

    /** 关注用户。 */
    protected Response followUser(String token, long userId) {
        return RestAssured.given().header("Authorization", token).post("/api/follow/" + userId);
    }

    /** 取消关注。 */
    protected Response unfollowUser(String token, long userId) {
        return RestAssured.given().header("Authorization", token).delete("/api/follow/" + userId);
    }

    /** 发表评论；{@code parentId = 0} 表示主楼。 */
    protected Response createComment(String token, long postId, long parentId, String content) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("postId", postId);
        body.put("parentId", parentId);
        body.put("content", content);
        return RestAssured.given()
                .contentType(ContentType.JSON)
                .header("Authorization", token)
                .body(body)
                .post("/api/comments");
    }

    /** 发表评论并取回它的 id（失败时把真实响应打出来，而不是抛 NPE）。 */
    protected long createCommentAndGetId(String token, long postId, long parentId, String content) {
        Response response = createComment(token, postId, parentId, content);
        assertThat(response.jsonPath().getInt("code"))
                .as("发评论必须成功（否则后续断言没有意义）。响应：%s", response.asString())
                .isZero();
        Object id = response.jsonPath().get("data.id");
        assertThat(id).as("发评论响应必须带 id。响应：%s", response.asString()).isNotNull();
        return Long.parseLong(String.valueOf(id));
    }

    /** 删除评论。 */
    protected Response deleteComment(String token, long commentId) {
        return RestAssured.given().header("Authorization", token).delete("/api/comments/" + commentId);
    }

    /** 评论点赞。 */
    protected Response likeComment(String token, long commentId) {
        return RestAssured.given().header("Authorization", token).post("/api/comments/" + commentId + "/like");
    }

    /** 取消评论点赞。 */
    protected Response unlikeComment(String token, long commentId) {
        return RestAssured.given().header("Authorization", token).delete("/api/comments/" + commentId + "/like");
    }

    /** 主楼评论列表（公开接口）。 */
    protected Response listComments(long postId, int page, int size) {
        return RestAssured.given()
                .queryParams(Map.of("page", page, "size", size))
                .get("/api/posts/" + postId + "/comments");
    }

    /** 楼中楼列表（公开接口）。 */
    protected Response listReplies(long rootId, int page, int size) {
        return RestAssured.given()
                .queryParams(Map.of("page", page, "size", size))
                .get("/api/comments/" + rootId + "/replies");
    }

    /** 首页流（可带 token；{@code type=follow|all}）。 */
    protected Response getFeed(String token, String type) {
        var request = RestAssured.given().queryParam("type", type);
        if (token != null) {
            request = request.header("Authorization", token);
        }
        return request.get("/api/feed");
    }

    /** 我的收藏列表。 */
    protected Response listMyCollections(String token, int page, int size) {
        return RestAssured.given()
                .header("Authorization", token)
                .queryParams(Map.of("page", page, "size", size))
                .get("/api/user/collections");
    }

    /** 个人主页（可带 token）。 */
    protected Response getUserProfile(String token, long userId) {
        var request = RestAssured.given();
        if (token != null) {
            request = request.header("Authorization", token);
        }
        return request.get("/api/users/" + userId);
    }

    // ==================================================================
    // 落库断言用到的小工具
    // ==================================================================

    /**
     * 帖子详情。
     *
     * @param token 带 token 时走"可选鉴权"分支（CR-K 的 {@code liked} 会反映该用户的状态）；
     *              <b>传 {@code null} 表示未登录</b>（那时 {@code liked} 必须仍是 {@code false}
     *              且 HTTP 仍为 200 —— §13.1 的第 ② 条硬断言）
     */
    protected Response getPostDetail(String token, long postId) {
        io.restassured.specification.RequestSpecification request = RestAssured.given();
        if (token != null) {
            request = request.header("Authorization", token);
        }
        return request.get("/api/posts/" + postId);
    }

    /** 单值查询（列名由测试自己写死，不接受外部输入）。 */
    protected <T> T scalar(String sql, Class<T> type, Object... args) {
        return jdbcTemplate.queryForObject(sql, type, args);
    }

    /** 某张关系表的行数（表名由测试写死；这里只为少写几行重复 SQL）。 */
    protected int countRows(String table, String whereColumn, Object value) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM `" + table + "` WHERE `" + whereColumn + "` = ?", Integer.class, value);
        return count == null ? 0 : count;
    }

    /** 帖子的一列值。 */
    protected <T> T postColumn(long postId, String column, Class<T> type) {
        return scalar("SELECT `" + column + "` FROM post WHERE id = ?", type, postId);
    }

    /** 用户的一列值。 */
    protected <T> T userColumn(long userId, String column, Class<T> type) {
        return scalar("SELECT `" + column + "` FROM `user` WHERE id = ?", type, userId);
    }

    /** 评论的一列值。 */
    protected <T> T commentColumn(long commentId, String column, Class<T> type) {
        return scalar("SELECT `" + column + "` FROM `comment` WHERE id = ?", type, commentId);
    }

    /** 断言响应 {@code code=0}（失败时把响应体打出来）。 */
    protected void assertOk(Response response) {
        assertThat(response.jsonPath().getInt("code"))
                .as("接口应返回 code=0。响应：%s", response.asString())
                .isZero();
    }

    /** 断言响应里的 {@code data.list} 长度。 */
    protected void assertListSize(Response response, int expected) {
        assertThat(response.jsonPath().getList("data.list"))
                .as("列表长度不符。响应：%s", response.asString())
                .hasSize(expected);
    }

    /** 从响应里取 {@code data.list} 中某个字段的整数值列表（保持返回顺序）。 */
    protected List<Integer> intList(Response response, String path) {
        return response.jsonPath().getList(path);
    }
}
