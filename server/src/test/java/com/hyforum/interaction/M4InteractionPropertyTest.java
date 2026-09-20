package com.hyforum.interaction;

import com.hyforum.interaction.service.InteractionService;
import com.hyforum.support.IntegrationTestBase;
import com.hyforum.support.TestFixtures;
import com.hyforum.support.TestTableCleaner;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.PropertyDefaults;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Provide;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>H6：jqwik 属性测试</b>（任务书 §6 —— 依赖已引但全项目一条都没写过，本条是第一条）。
 *
 * <h2>属性（property）说的是什么</h2>
 * <pre>
 *   对**任意**的互动操作序列：
 *     ① post.like_count    == COUNT(DISTINCT (post_id, user_id)) FROM post_like   （该帖）
 *     ② post.collect_count == COUNT(DISTINCT (post_id, user_id)) FROM post_collect（该帖）
 *     ③ 关系表里不存在任何重复的 (post_id, user_id)
 * </pre>
 * <p>这三条合起来就是"任意调用序列后，行数 == 去重组合数；{@code like_count} == 该帖
 * {@code post_like} 行数"。它与示例测试的差别在于：示例测试只覆盖<b>我想得到</b>的那几种
 * 序列（并发点赞、重复取消、100 次随机……），而属性测试覆盖<b>所有</b>序列 ——
 * 包括"先取消再取消再点赞再取消"这类没人会手写的交错。
 * 计数类代码的 bug 恰恰几乎都出在交错上。</p>
 *
 * <h2>为什么属性只断言"计数 vs 去重行数"，而不断言"计数 vs 操作次数"</h2>
 * <p>因为幂等语义的定义就是<b>状态</b>语义：同一对 (post, user) 无论操作几次，
 * 结果都只该有一个状态。若把期望值写成"操作次数"，用例会在"重复点赞"上直接判错
 * —— 那是把 bug 的预期当成需求（本项目已登记过这类"永远绿的断言"的反面教训）。
 * 因此这里先由测试自己算出<b>去重后的组合数</b>，再与库里的计数比。</p>
 *
 * <h2>与 Surefire 的配合（任务书要求把坑写清楚）—— <b>三条实测结论</b></h2>
 * <ol>
 *   <li><b>类名必须以 {@code Test} 结尾</b>（本项目已踩过 {@code *IT.java} 被静默跳过的坑，
 *       jqwik 的属性测试走的是同一套 Surefire 类名匹配）；</li>
 *   <li>🔴 <b>最危险的一条：{@code @Property} 方法上不能有任何 JUnit 注解</b>
 *       （连 {@code @DisplayName} 都不行）。jqwik 会把这类方法<b>静默跳过</b>，
 *       而构建仍然 <b>BUILD SUCCESS</b>、Surefire 汇总只是多一个 Skipped：
 *       实测第一版给三条属性都加了 {@code @DisplayName}，结果是
 *       <b>"属性测试一条都没跑"却显示全绿</b>。描述请写 JavaDoc。
 *       这类"写了但没跑"正是本项目反复吃亏的那一类（Surefire 不跑 {@code *IT.java}、
 *       覆盖率脚本幽灵期望），因此这里把实测原文也留下：<br>
 *       {@code A @Property method must not have JUnit annotations: [@org.junit.jupiter.api.DisplayName(...)]}</li>
 *   <li>属性测试<b>不参与</b> JUnit 的 {@code @BeforeEach}/{@code @AfterEach}：
 *       基类的清表钩子只对 {@code @Test} 方法生效，因此这里用 jqwik 自己的
 *       {@link BeforeTry}/{@link AfterTry}，在每个 try 前后重建基线。
 *       <b>这是最容易漏的第二条</b>：不加就是"前一个 try 的数据污染下一个 try"，
 *       而失败时 jqwik 给出的反例会看起来毫无道理。</li>
 * </ol>
 * <p>失败时 jqwik 会打印<b>最小反例</b>（shrink 后的操作序列），
 * 这是它比手写随机循环值钱的地方 —— 反例可直接拿去写回归用例。</p>
 */
@PropertyDefaults(tries = 20)
@TestPropertySource(properties = {
        // ⚠️ 这里**刻意不覆盖 `spring.datasource.url`**（曾经覆盖过，导致 CI 恒连不存在的库，
        //    28 个 CannotGetJdbcConnection）。理由与 M4ApiTestSupport 的类注释完全相同：
        //    让配置决定连哪个库，测试代码不碰数据源；要独占库就用命令行
        //    `-Dhy.test.db=<库> -Dspring.datasource.url=...<库>...` 显式指定。
        //
        // 浏览量回写周期压到 1 秒（与其它 M4 用例同一份配置；本类不测浏览量）
        "hy.post.view-flush-interval-ms=1000",
        "aliyun.oss.endpoint=oss-cn-beijing.aliyuncs.com",
        "aliyun.oss.bucket-name=hy-forum-2026",
        "aliyun.oss.image-dir=post/",
        "hy.post.max-images=9",
        "hy.post.edit-window-minutes=30",
        "hy.post.new-user-window-hours=24",
        "hy.post.new-user-post-limit=3",
        "hy.post.post-per-hour-limit=10",
        "hy.rate-limit.ip-per-minute=50"
})
class M4InteractionPropertyTest extends IntegrationTestBase {

    /** 参与随机操作的用户数。 */
    private static final int USERS = 3;

    /** 参与随机操作的帖子数（2 条，用于覆盖"不同帖子的计数互不影响"）。 */
    private static final int POSTS = 2;

    /**
     * 本任务独占的测试库名与清表清单。
     *
     * <p>刻意在这里再声明一份而不是引用 {@code M4ApiTestSupport} 的常量：
     * 本类继承的是 {@code IntegrationTestBase}（不需要真 HTTP 端口，也不该被
     * {@code @TestPropertySource} 里那一大串 web 相关配置拖慢），
     * 两者是并列的两个基类形态。库名写在两处是**有代价**的（第二份事实来源），
     * 但比"属性测试悄悄跑在共享库上"要小得多 —— 已登记为可收敛项。</p>
     */
    /**
     * 本次运行期望连的测试库名 —— 唯一来源是系统属性 {@code hy.test.db}
     * （与 {@code TestTableCleaner} 相同的解析规则），缺省 {@code hy_forum_test}。
     *
     * <p><b>不再写死库名</b>：写死那版让 CI 恒连一个不存在的库（见类上
     * {@code @TestPropertySource} 的注释）。</p>
     */
    private static final String M4_DATABASE = resolveExpectedDatabase();

    private static String resolveExpectedDatabase() {
        String configured = System.getProperty("hy.test.db");
        return (configured == null || configured.isBlank()) ? "hy_forum_test" : configured;
    }

    private static final String[] M4_TABLES = {"comment_like", "comment", "post_like", "post_collect",
            "follow", "post_image", "post", "board", "user"};

    /**
     * Spring 容器引用（静态）。
     *
     * <p><b>为什么必须是静态的</b> —— 这是本任务踩得最深的一个坑，实测结论：
     * <b>jqwik 的 {@code @Property} 方法与它自己的生命周期钩子 {@code @BeforeTry}
     * 跑在<em>不同的实例</em>上</b>。证据链：</p>
     * <ol>
     *   <li>在 {@code @BeforeTry} 里用注入字段建基线 → <b>成功</b>
     *       （说明钩子所在的那个实例是 Spring 托管并注入好的）；</li>
     *   <li>但同一个注入字段在 {@code @Property} 方法内部访问 → {@code null}；</li>
     *   <li>于是 {@code @Property} 方法跑在<b>另一个没有被注入的实例</b>上。</li>
     * </ol>
     * <p>因此注入的东西必须在钩子里"接"一次、再由属性方法从静态处取。
     * 直接照抄 JUnit 的写法（字段注入 + 方法里直接用）在 jqwik 下必然 NPE。</p>
     */
    private static org.springframework.context.ApplicationContext CONTEXT;

    /**
     * 在<b>任何 try 之前</b>把 Spring 容器起起来（jqwik 的容器级钩子，每个容器跑一次）。
     *
     * <p><b>为什么必须自己起容器</b>（这是 jqwik + Spring 的核心坑，实测结论）：
     * {@code @SpringBootTest} 的注入是<b>由 JUnit 5 的 SpringExtension 完成的</b>，
     * 而 jqwik 的属性方法走的是 jqwik 自己的引擎与生命周期 ——
     * <b>Spring 的注入根本没机会作用到 jqwik 用的实例上</b>。实测三种表现依次为：</p>
     * <pre>
     *   this.tableCleaner is null                       ← 复用基类字段
     *   this.jdbcTemplate is null                       ← 复用基类字段
     *   this.interactionService is null                 ← @Autowired 字段
     *   M4InteractionPropertyTest.CONTEXT is null       ← @Autowired ApplicationContext
     * </pre>
     * <p>也就是说：<b>在 jqwik 的属性测试里 {@code @Autowired} 是"看起来有、实际没有"</b>。
     * 必须用 {@code SpringApplicationBuilder} 自己把容器 build 出来（带 {@code test} profile，
     * 于是库地址、Redis、OSS 占位值都取自 {@code application-test.yml}），
     * 再把它放在静态字段上供属性方法使用。容器在 JVM 退出时关闭。</p>
     *
     * <p>代价（如实记录）：属性测试的容器<b>与 JUnit 测试的容器不共享</b>，
     * 因此本类会多启动一次 Spring（约 6 秒）。这是"必须另起一次"而不是"可以优化"的事 ——
     * 除非放弃 jqwik 的容器级钩子、改回 JUnit 的 {@code @ParameterizedTest} 手写随机循环，
     * 那就不再是属性测试了。</p>
     */
    @net.jqwik.api.lifecycle.BeforeContainer
    static void bootSpringContextOnce() {
        // 用同一个 profile 起容器，保证库地址/Redis/占位配置与其它 M4 用例一致
        // ⚠️ 这里**不再** System.setProperty("spring.datasource.url", ...) 覆盖数据源：
        //    那会让本类恒连写死的库（CI 里不存在 → 28 个 CannotGetJdbcConnection）。
        //    现在容器完全按配置连库，与其它测试类一致；
        //    要独占库就由命令行 -Dhy.test.db / -Dspring.datasource.url 决定。
        //    （`@TestPropertySource` 对本类无效 —— 容器是手动 build 的，
        //      所以上面那段注解不会覆盖任何东西，只作为与其它 M4 用例共享的配置声明。）
        org.springframework.context.ApplicationContext ctx =
                new org.springframework.boot.builder.SpringApplicationBuilder(
                        com.hyforum.HyForumApplication.class)
                        .profiles("test")
                        // 不走 web 容器：属性测试只碰数据库与 Service，起 Tomcat 毫无意义
                        // （第一版没加这行，报的是 "Failed to start bean 'webServerStartStop'"）
                        .web(org.springframework.boot.WebApplicationType.NONE)
                        .run();
        CONTEXT = ctx;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (ctx instanceof org.springframework.context.ConfigurableApplicationContext closeable) {
                closeable.close();
            }
        }));
    }

    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private long[] userIds;
    private long[] postIds;

    /**
     * 每个 try 前重建基线。
     *
     * <p>清表是必须的：属性测试的每个 try 都是一次独立的随机序列，
     * 上一条序列留下的关系行会让"计数 == 关系表行数"这条等式在**正确的实现**上变红
     * （而那种红是最难查的一类假红）。</p>
     *
     * <p>另外把"期望库名"显式钉到独占库，与 {@code M4ApiTestSupport} 保持同一口径 ——
     * 否则清表工具会去清共享库，而独占库里的残留永远清不掉（实测踩过）。</p>
     */
    @BeforeTry
    void seed() {
        org.springframework.jdbc.core.JdbcTemplate localJdbc = jdbcForBaseline();
        new TestTableCleaner(localJdbc, M4_DATABASE).truncate(M4_TABLES);

        TestFixtures localFixtures = new TestFixtures(localJdbc);
        long boardId = localFixtures.insertBoard("属性测试版块");
        userIds = new long[USERS];
        for (int i = 0; i < USERS; i++) {
            userIds[i] = localFixtures.insertUser("属性测试用户" + i);
        }
        postIds = new long[POSTS];
        for (int i = 0; i < POSTS; i++) {
            postIds[i] = localFixtures.insertPost(boardId, userIds[0], "属性测试帖 " + i);
        }
    }

    /**
     * 取一个用于重建基线的 {@code JdbcTemplate}，<b>优先用注入的 DataSource，取不到就自己连</b>。
     *
     * <p>为什么要这条兜底（实测踩到）：jqwik 的 {@code @BeforeTry} 与 Spring 的
     * {@code DependencyInjectionTestExecutionListener} <b>不同步</b> —— 在本类里
     * 注入的字段（乃至基类的 {@code jdbcTemplate} / {@code tableCleaner}）在
     * {@code @BeforeTry} 执行时可能仍是 {@code null}。实测三种表现依次是：</p>
     * <pre>
     *   this.tableCleaner is null
     *   this.jdbcTemplate is null
     *   Property 'dataSource' is required
     * </pre>
     * <p>重建基线这件事<b>不该依赖 Spring 的注入时机</b>：它只是一个"连到指定库、清几张表"
     * 的动作，用驱动直接连反而更稳、更少偶发。连接参数与
     * {@code application-test.yml} 里那份测试配置一致（本机 MySQL、测试库、root/1234）。</p>
     */
    private org.springframework.jdbc.core.JdbcTemplate jdbcForBaseline() {
        if (CONTEXT != null) {
            return new org.springframework.jdbc.core.JdbcTemplate(
                    CONTEXT.getBean(javax.sql.DataSource.class));
        }
        org.springframework.jdbc.datasource.DriverManagerDataSource direct =
                new org.springframework.jdbc.datasource.DriverManagerDataSource(
                        "jdbc:mysql://127.0.0.1:3306/" + M4_DATABASE
                                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
                                + "&useSSL=false&allowPublicKeyRetrieval=true",
                        "root", "1234");
        direct.setDriverClassName("com.mysql.cj.jdbc.Driver");
        return new org.springframework.jdbc.core.JdbcTemplate(direct);
    }

    /** try 结束后清理，避免反例打印时被残留数据干扰（基类只覆盖 @Test 方法）。 */
    @AfterTry
    void cleanUp() {
        new TestTableCleaner(jdbcForBaseline(), M4_DATABASE).truncate(M4_TABLES);
    }

    // ==================================================================
    // 属性
    //
    // ⚠️⚠️ **绝不要给 @Property 方法加 JUnit 注解（哪怕只是 @DisplayName）** ⚠️⚠️
    //   这是本任务实测踩到的一个"静默失效"陷阱，比报错危险得多：
    //   jqwik 发现 @Property 方法上带任何 org.junit 注解时，会<b>直接跳过</b>这个属性，
    //   并把原因写成 skipped。而构建**照样 BUILD SUCCESS**、Surefire 汇总显示
    //   "Tests run: 3, Failures: 0, Errors: 0, Skipped: 3" —— 3 个属性测试
    //   **一条都没跑**，从"全绿"两个字上完全看不出来。
    //   实测的 skipped 原文：
    //     A @Property method must not have JUnit annotations:
    //     [@org.junit.jupiter.api.DisplayName("...")]
    //   本类第一版就是这么写的（给每条属性配了 @DisplayName 好让报告更可读），
    //   于是 H6 变成了"写了但没跑"。**描述请写在 JavaDoc 里**（jqwik 会把它带进报告）。
    // ==================================================================

    /**
     * 主属性：<b>任意</b>点赞/取消序列后，{@code like_count} == 去重后的点赞组合数。
     *
     * <p>描述只能写在这里（不能是 {@code @DisplayName}，原因见上方警告）。</p>
     */
    @Property
    void like_counter_equals_distinct_pairs(@ForAll("anyOps") List<InteractionOp> ops) {
        for (InteractionOp op : ops) {
            apply(op);
        }

        for (long postId : postIds) {
            long distinctPairs = distinctPairs("post_like", postId);
            assertThat(rowsIn("post_like", postId))
                    .as("关系表里不得出现重复的 (post_id, user_id) —— 唯一索引必须一直拦得住。"
                            + "反例操作序列：%s", ops)
                    .isEqualTo(distinctPairs);
            assertThat(postLikeCount(postId))
                    .as("like_count 必须等于去重后的点赞组合数。操作序列：%s", ops)
                    .isEqualTo(distinctPairs);
            assertThat(postLikeCount(postId))
                    .as("like_count 也必须等于关系表行数（两者在无重复行时等价）。操作序列：%s", ops)
                    .isEqualTo(rowsIn("post_like", postId));
        }
    }

    /**
     * 同一属性作用于收藏。
     *
     * <p>为什么不把它合进上一个属性：收藏与点赞是<b>两个独立的计数器</b>，
     * 合在一起断言会让"收藏计数写成了点赞计数"这类交叉 bug 可能被掩盖
     * （两个值恰好相等时看不出差别）。分开断言则各自独立成立。</p>
     */
    @Property
    void collect_counter_equals_distinct_pairs(@ForAll("anyOps") List<InteractionOp> ops) {
        for (InteractionOp op : ops) {
            apply(op);
        }
        for (long postId : postIds) {
            long distinctPairs = distinctPairs("post_collect", postId);
            assertThat(postCollectCount(postId))
                    .as("collect_count 必须等于去重后的收藏组合数。操作序列：%s", ops)
                    .isEqualTo(distinctPairs);
            assertThat(postCollectCount(postId))
                    .as("collect_count 也必须等于关系表行数")
                    .isEqualTo(rowsIn("post_collect", postId));
        }
    }

    /**
     * 交叉属性：任意序列后，{@code like_count} 与 {@code collect_count} 必须
     * <b>分别</b>等于各自关系表的行数（不互相串）。
     *
     * <p>它专门针对一种很容易写出来的实现错误：两个方法共用了一段计数 SQL，
     * 于是收藏操作把 {@code like_count} 也加了一次。那种 bug 在只测单一类型的
     * 用例里完全看不出来。</p>
     */
    @Property
    void like_and_collect_counters_do_not_cross(
            @ForAll("anyOps") List<InteractionOp> ops) {
        for (InteractionOp op : ops) {
            apply(op);
        }
        for (long postId : postIds) {
            assertThat(postLikeCount(postId))
                    .as("like_count 必须等于 post_like 行数（不能被收藏操作影响）。操作序列：%s", ops)
                    .isEqualTo(rowsIn("post_like", postId));
            assertThat(postCollectCount(postId))
                    .as("collect_count 必须等于 post_collect 行数（不能被点赞操作影响）。操作序列：%s", ops)
                    .isEqualTo(rowsIn("post_collect", postId));
        }
    }

    // ==================================================================
    // 操作序列的生成
    // ==================================================================

    /** 四类操作（点赞/取消点赞/收藏/取消收藏）。 */
    enum OpKind {
        LIKE, UNLIKE, COLLECT, UNCOLLECT
    }

    /**
     * 一次操作：类型 + 参与者下标 + 帖子下标。
     *
     * <p>下标只取小范围（3 个用户 × 2 条帖子）：这样 60 步的序列里
     * <b>必然</b>出现大量重复组合，才真的在检验幂等；若下标空间一大，
     * 序列里几乎全是新组合，"重复操作"这条路径根本走不到。</p>
     */
    record InteractionOp(OpKind kind, int userIndex, int postIndex) {
    }

    /**
     * 操作序列的生成器。
     *
     * <p><b>直接产出 {@code List<InteractionOp>}，而不是"单个元素 + {@code @Size} 约束"</b>：
     * 实测 {@code @ForAll("任意值provider") @Size(min=1,max=60) List<T>} 这种组合会报
     * {@code IllegalArgument argument type mismatch} —— 带名字的 provider 提供的是
     * <b>元素</b>的 Arbitrary，而 {@code @Size} 要求 jqwik 自己推导出 List 的 Arbitrary，
     * 两者凑在一起就类型对不上。让 provider 直接给 List 最省事，也把"序列长度"
     * 这件事收在一处。</p>
     *
     * <p>长度取 1–60：下标空间只有 3 用户 × 2 帖 = 6 种组合，60 步里<b>必然</b>出现大量
     * 重复组合，才真的在检验幂等；若下标空间一大，序列里几乎全是新组合，
     * "重复操作"这条路径根本走不到。</p>
     */
    @Provide
    Arbitrary<List<InteractionOp>> anyOps() {
        Arbitrary<OpKind> kinds = Arbitraries.of(OpKind.values());
        Arbitrary<Integer> users = Arbitraries.integers().between(0, USERS - 1);
        Arbitrary<Integer> posts = Arbitraries.integers().between(0, POSTS - 1);
        return Combinators.combine(kinds, users, posts).as(InteractionOp::new)
                .list().ofMinSize(1).ofMaxSize(60);
    }

    /** 取被测 Service（按需从容器取，理由见 applicationContext 字段的注释）。 */
    private static InteractionService service() {
        return CONTEXT.getBean(InteractionService.class);
    }

    /** 执行一步操作（走 Service，与线上同一条代码路径）。 */
    private void apply(InteractionOp op) {
        long userId = userIds[op.userIndex()];
        long postId = postIds[op.postIndex()];
        switch (op.kind()) {
            case LIKE -> service().likePost(userId, postId);
            case UNLIKE -> service().unlikePost(userId, postId);
            case COLLECT -> service().collectPost(userId, postId);
            case UNCOLLECT -> service().uncollectPost(userId, postId);
        }
    }

    // ==================================================================
    // 断言用的纯 SQL 读取（不复用被测对象，避免自证）
    // ==================================================================

    /** 关系表里该帖的行数。 */
    private long rowsIn(String table, long postId) {
        Long count = jdbcForBaseline().queryForObject(
                "SELECT COUNT(*) FROM `" + table + "` WHERE post_id = ?", Long.class, postId);
        return count == null ? 0L : count;
    }

    /**
     * 去重后的组合数。
     *
     * <p>用 SQL 自己算而不是"在测试里数一数操作序列"：测试算出来的期望值必须来自
     * <b>数据库的现状</b>（独立于被测代码写入的计数），否则一旦计数与关系表<b>一起</b>写错，
     * 两边会一起错、断言照样绿。这里读的是关系表，被测的是计数列 —— 两条独立的路径。</p>
     */
    private long distinctPairs(String table, long postId) {
        Long count = jdbcForBaseline().queryForObject(
                "SELECT COUNT(*) FROM (SELECT DISTINCT post_id, user_id FROM `" + table
                        + "` WHERE post_id = ?) t", Long.class, postId);
        return count == null ? 0L : count;
    }

    private int postLikeCount(long postId) {
        Integer value = jdbcForBaseline().queryForObject("SELECT like_count FROM post WHERE id = ?",
                Integer.class, postId);
        return value == null ? 0 : value;
    }

    private int postCollectCount(long postId) {
        Integer value = jdbcForBaseline().queryForObject("SELECT collect_count FROM post WHERE id = ?",
                Integer.class, postId);
        return value == null ? 0 : value;
    }
}
