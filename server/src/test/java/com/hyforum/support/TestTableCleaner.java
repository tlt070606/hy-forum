package com.hyforum.support;

import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 测试库清表工具。
 *
 * <h2>为什么需要它</h2>
 * 本项目**不使用 Testcontainers**（不装 Docker Desktop，理由见 <code>docs/testing/README.md</code> §3.1），
 * 隔离层从「容器」下移到「独立测试库 {@code hy_forum_test} + 测试内自行清理」。
 * 因此每个会写库的测试都必须自己把起点恢复干净：
 * <ul>
 *   <li>普通测试优先用 {@code @Transactional} 自动回滚（更快，但**并发/多线程测试无效** —— 各线程事务边界不同）；</li>
 *   <li>并发测试（如 {@code SAMPLE_idempotency_concurrent_unique_index}）必须在
 *       {@code @BeforeEach}/{@code @AfterEach} 里显式清表，也就是用本工具。</li>
 * </ul>
 *
 * <h2>安全防线（本类最重要的一点）</h2>
 * 清表是破坏性操作。若测试配置写错、连到了开发库 {@code hy_forum}，
 * 「清表」会直接抹掉开发数据。因此 {@link #assertTestDatabase()} 会强制校验
 * {@code SELECT DATABASE()} 等于 {@code hy_forum_test}，**不匹配就抛异常终止测试**
 * （宁可测试红，也不要静默清空开发库）。
 * 期望库名可用系统属性 {@code -Dhy.test.db=xxx} 覆盖（仅用于 CI 里的临时实例）。
 *
 * <h2>为什么用 TRUNCATE 而不是 DELETE</h2>
 * <ul>
 *   <li>{@code docs/db/README.md} 基线规定**不使用外键约束**（库内实测外键数 = 0），
 *       因此 TRUNCATE 不会被外键挡住，也不需要考虑清表顺序；</li>
 *   <li>TRUNCATE 会重置 AUTO_INCREMENT，让「id 从 1 开始」这类断言稳定，且比 DELETE 快。</li>
 * </ul>
 */
public class TestTableCleaner {

    /** 测试库名（契约级约定：集成测试只允许连它） */
    public static final String EXPECTED_TEST_DATABASE = "hy_forum_test";

    /** 允许清表的表名形态：只允许 字母/数字/下划线，防止表名拼接出意外 SQL */
    private static final Pattern SAFE_TABLE_NAME = Pattern.compile("^[A-Za-z0-9_]+$");

    private final JdbcTemplate jdbcTemplate;

    /** 期望的当前库名；不匹配则拒绝执行任何破坏性操作 */
    private final String expectedDatabase;

    public TestTableCleaner(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, System.getProperty("hy.test.db", EXPECTED_TEST_DATABASE));
    }

    public TestTableCleaner(JdbcTemplate jdbcTemplate, String expectedDatabase) {
        this.jdbcTemplate = jdbcTemplate;
        this.expectedDatabase = expectedDatabase;
    }

    /** 也支持直接用 DataSource 构造（不依赖 Spring 的 JdbcTemplate bean 时用） */
    public TestTableCleaner(DataSource dataSource) {
        this(new JdbcTemplate(dataSource));
    }

    /** @return 当前连接的数据库名（连接串里没写库时为 null） */
    public String currentDatabase() {
        return jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
    }

    /**
     * 防线：当前必须连在测试库上，否则抛异常。
     *
     * <p>调用方（{@link IntegrationTestBase}）在 {@code @BeforeEach} 里调用它，
     * 使「配错了库」表现为一条明确的测试失败，而不是数据被清空。</p>
     *
     * <p><b>2026-09-17 放宽：从"等于 hy_forum_test"改为"以 hy_forum_test 开头"。</b>
     * 原因：两条流水线并行开工时必须各用一个库，否则会互相清表 —— 实测过两次
     * （全量 40 秒膨胀到 8 分 18 秒；以及一次 8 失败 / 24 错误，确认无并发后同一份代码 49/49）。
     * 放宽后 {@code hy_forum_test_m4} 这类库名被接受，而**开发库 {@code hy_forum} 仍然被拒**
     * （它不以 {@code hy_forum_test} 开头）—— 这条防线的目的（宁可测试失败，也不清开发库）没有变弱。</p>
     */
    public void assertTestDatabase() {
        String current = currentDatabase();
        if (!isAcceptableTestDatabase(current)) {
            throw new IllegalStateException(
                    "拒绝执行：当前连接的是库 [" + current + "]，而测试只允许连以 [" + expectedDatabase + "] 开头的库。"
                            + "请检查 server/src/test/resources/application-test.yml（由 W1-M1 任务独占维护），"
                            + "或本次运行是否漏了 TEST_DB 环境变量。"
                            + "本条防线的作用是：宁可测试失败，也不要把开发库 hy_forum 的数据清掉。");
        }
    }

    /**
     * 库名判定规则（**纯函数，可单测**）。
     *
     * <p>抽成静态方法是为了让"该拒的必须拒"这件事**可以被测试**：
     * 直接拿开发库去跑一遍是验证不了的（万一防线失效，开发库当场被清空）。
     * 有了它就能用名字做断言，而不碰任何真实数据。</p>
     *
     * @param name 连接串里当前的库名
     * @return true = 允许清表
     */
    public static boolean isAcceptableTestDatabase(String name) {
        return name != null && name.startsWith(EXPECTED_TEST_DATABASE);
    }

    /**
     * 清空指定表（TRUNCATE，重置自增）。
     *
     * @param tables 表名（不含库名）；顺序无关（基线无外键）
     */
    public void truncate(String... tables) {
        assertTestDatabase();   // 破坏性操作前再确认一次
        for (String table : tables) {
            if (table == null || !SAFE_TABLE_NAME.matcher(table).matches()) {
                throw new IllegalArgumentException("非法表名：" + table + "（只允许字母/数字/下划线）");
            }
            jdbcTemplate.execute("TRUNCATE TABLE `" + table + "`");
        }
    }

    /**
     * 清空**当前库里的全部表**。
     *
     * <p>表清单从 {@code information_schema} 现场读取，而不是在测试里硬编码一份 16 张表的名单 ——
     * 硬编码的名单会在 schema 变更后悄悄过期（这正是项目反复强调的「不要维护第二份事实来源」）。</p>
     *
     * @return 实际清空的表数量
     */
    public int truncateAllTables() {
        assertTestDatabase();
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT TABLE_NAME FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE' ORDER BY TABLE_NAME",
                String.class);
        truncate(tables.toArray(new String[0]));
        return tables.size();
    }
}
