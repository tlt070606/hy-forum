package com.hyforum.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 集成测试共享基类（数据库 + Redis）。
 *
 * <h2>它解决的三个问题</h2>
 * <ol>
 *   <li><b>统一环境</b>：{@code @ActiveProfiles("test")} 让所有集成测试都读
 *       {@code server/src/test/resources/application-test.yml}（该文件由 W1-M1 任务独占维护，
 *       本基类**只引用、不另建一份配置**）。</li>
 *   <li><b>防误伤开发库</b>：{@code @BeforeEach} 先执行 {@link TestTableCleaner#assertTestDatabase()}，
 *       当前库不是 {@code hy_forum_test} 就直接失败。放弃 Testcontainers 后这是最后一道防线。</li>
 *   <li><b>起点干净</b>：子类通过覆盖 {@link #tablesToClean()} 声明要清的表，基类在
 *       {@code @BeforeEach}/{@code @AfterEach} 各清一次。
 *       <b>并发测试必须走这条路</b>，不能靠 {@code @Transactional} 回滚 —— 各线程事务相互独立，
 *       回滚只回滚测试线程自己的那份，别的线程写的数据会留下来。</li>
 * </ol>
 *
 * <h2>为什么默认不清任何表</h2>
 * 清表是破坏性的，默认开启会让「只读断言」的测试也付出代价，且容易误删别的测试正在用的种子数据。
 * 因此默认 {@link #tablesToClean()} 返回空数组（=不清表），由子类显式声明。
 *
 * <h2>用法</h2>
 * <pre>{@code
 * class CommentTest extends IntegrationTestBase {
 *     @Override protected String[] tablesToClean() { return new String[]{"comment", "post", "user"}; }
 *     @Test void M4_xxx() { ... jdbcTemplate / stringRedisTemplate / fixtures / tableCleaner ... }
 * }
 * }</pre>
 *
 * <p>需要真 HTTP 端口的接口测试请改用 {@link WebIntegrationTestBase}。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    /** 直接用 SQL 操作 schema 里已有的表：业务代码还没写时也能跑（见 TestFixtures 的说明） */
    @Autowired
    protected JdbcTemplate jdbcTemplate;

    /** 验证码 / 登录态都放 Redis；集成测试要能连上 VM 里的 hy-redis（192.168.100.128:6380） */
    @Autowired
    protected StringRedisTemplate stringRedisTemplate;

    /** 清表工具（含「必须连测试库」的防线） */
    protected TestTableCleaner tableCleaner;

    /** 造数工具 */
    protected TestFixtures fixtures;

    @BeforeEach
    void initSupportObjectsAndCleanUp() {
        tableCleaner = new TestTableCleaner(jdbcTemplate);
        fixtures = new TestFixtures(jdbcTemplate);
        cleanDeclaredTables();
    }

    @AfterEach
    void cleanDeclaredTablesAfterTest() {
        // 收尾清理失败不应把「本来失败的测试」变成另一个错误，故这里吞掉异常但打印出来
        try {
            cleanDeclaredTables();
        } catch (RuntimeException e) {
            System.err.println("[IntegrationTestBase] @AfterEach 清表失败（不影响断言结果，但测试库可能残留数据）：" + e);
        }
    }

    /**
     * 声明本测试类需要清空的表（顺序无关：基线无外键）。
     * 返回空数组表示不清表（默认）。并发测试必须覆盖本方法。
     */
    protected String[] tablesToClean() {
        return new String[0];
    }

    private void cleanDeclaredTables() {
        String[] tables = tablesToClean();
        if (tables.length > 0) {
            tableCleaner.truncate(tables);
        } else {
            // 即使不清表也要校验库名：否则配错了库的测试会在开发库上乱写
            tableCleaner.assertTestDatabase();
        }
    }
}
