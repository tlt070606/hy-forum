package com.hyforum.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 测试基建自检：**证明测试真的连在"该连的地方"上**。
 *
 * <h2>为什么需要这条测试</h2>
 * 放弃 Testcontainers 之后（{@code docs/testing/README.md} §3.1），隔离层从「容器」下移到
 * 「独立测试库 + VM 里的 Redis」。这带来一个新风险：**测试可能悄悄连到了开发库/别的 Redis**，
 * 于是「全绿」变成了假象，甚至会把开发数据写坏。
 * 本测试把这件事变成断言，而不是靠人记得检查配置。
 *
 * <p>它同时是 DoD §5.1 的机器可验证证据：
 * 「能在测试里连上 {@code hy_forum_test}（本机 MySQL）与 {@code 192.168.100.128:6380}（VM Redis）」。</p>
 *
 * <p>注意：本类的名字前缀 {@code INFRA_} 表示「基建自检」，不是 {@code PLAN.md} §4 的验收项，
 * 因此它不在 {@code docs/testing/验收项-测试映射.md} 里
 * —— 覆盖率脚本会把它列进「多余：映射表未登记」，这是预期内的。</p>
 */
class TestInfrastructureSmokeTest extends IntegrationTestBase {

    /** schema.sql 基线：16 张表（M0 验收项 M0_table_count_is_16） */
    private static final int EXPECTED_TABLE_COUNT = 16;

    @Test
    @DisplayName("集成测试连的是测试库 hy_forum_test（不是开发库 hy_forum）")
    void INFRA_connected_to_test_database() {
        String database = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
        assertThat(TestTableCleaner.isAcceptableTestDatabase(database))
                .as("集成测试必须连以 %s 开头的库（并行流水线各有自己的库，如 hy_forum_test_m4）；"
                        + "连到开发库不仅结果不可信，还可能在清表时抹掉开发数据。实际连的是 [%s]",
                        TestTableCleaner.EXPECTED_TEST_DATABASE, database)
                .isTrue();

        // 2026-09-17 新增：把"该拒的必须拒"变成断言。
        // 为什么不直接拿开发库跑一遍：万一防线失效，开发库当场被清空 —— 用纯函数判定名字才安全。
        assertThat(TestTableCleaner.isAcceptableTestDatabase("hy_forum"))
                .as("开发库 hy_forum 必须被拒（它不以 hy_forum_test 开头）")
                .isFalse();
        assertThat(TestTableCleaner.isAcceptableTestDatabase(null))
                .as("取不到库名（连接串没写库）必须被拒")
                .isFalse();
        assertThat(TestTableCleaner.isAcceptableTestDatabase("hy_forum_test_m4"))
                .as("并行流水线的库（hy_forum_test_m4）必须被接受，否则两条流水线无法同时开工")
                .isTrue();

        Integer tables = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE'",
                Integer.class);
        assertThat(tables)
                .as("测试库的表数应与 docs/db/schema.sql 基线一致（测试库由 scripts/init_test_db.ps1 重建）")
                .isEqualTo(EXPECTED_TABLE_COUNT);

        // MySQL 版本守门：CHECK 约束需要 8.0.16+ 才会被真正强制执行，
        // 否则 SCHEMA_check_constraint_rejects_violation 会「假通过」（任务书 §7 提示）。
        String version = Objects.requireNonNull(jdbcTemplate.queryForObject("SELECT VERSION()", String.class));
        assertThat(isCheckConstraintEnforced(version))
                .as("MySQL 版本必须 >= 8.0.16（实际 %s）：低于它 CHECK 约束只解析不生效，schema 不变量测试会假通过", version)
                .isTrue();
    }

    @Test
    @DisplayName("集成测试能连上 VM 里的 Redis（192.168.100.128:6380），读写与 TTL 都正常")
    void INFRA_connected_to_redis_and_ttl_works() {
        String key = "hy:it:smoke:" + UUID.randomUUID();
        try {
            stringRedisTemplate.opsForValue().set(key, "pong", Duration.ofSeconds(60));

            assertThat(stringRedisTemplate.opsForValue().get(key))
                    .as("Redis 写入后必须能读回同一值（本机 Windows 无 Redis，连的是 VM 内的 hy-redis）")
                    .isEqualTo("pong");

            Long ttl = stringRedisTemplate.getExpire(key);
            assertThat(ttl)
                    .as("TTL 必须生效：验证码 key hy:captcha:*（300s）依赖它，TTL 不生效会导致验证码永不过期")
                    .isNotNull()
                    .isBetween(1L, 60L);
        } finally {
            stringRedisTemplate.delete(key);
        }
    }

    @Test
    @DisplayName("测试基建自身的防线可用：清表工具只认可 hy_forum_test")
    void INFRA_table_cleaner_refuses_non_test_database() {
        // 正常路径：当前就在测试库上 → 不抛异常
        tableCleaner.assertTestDatabase();

        // 防线路径：故意声明一个不存在的期望库名 → 必须拒绝
        // （这条断言才是「防线真的存在」的证据，而不是只看代码写得像防线）
        TestTableCleaner strict = new TestTableCleaner(jdbcTemplate, "definitely_not_this_database");
        assertThatThrownBy(strict::assertTestDatabase)
                .as("库名不匹配时必须拒绝执行破坏性操作，而不是默默把表清掉")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(TestTableCleaner.EXPECTED_TEST_DATABASE);
    }

    @Test
    @DisplayName("schema 基线：无外键、全部 InnoDB + utf8mb4")
    void INFRA_schema_baseline_respected() {
        Integer foreignKeys = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS "
                        + "WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_TYPE = 'FOREIGN KEY'",
                Integer.class);
        assertThat(foreignKeys)
                .as("docs/db/README.md 基线规定不使用外键约束（跨行一致性由唯一写入口保证）")
                .isZero();

        List<String> bad = jdbcTemplate.queryForList(
                "SELECT CONCAT(TABLE_NAME, '/', IFNULL(ENGINE,'?'), '/', IFNULL(TABLE_COLLATION,'?')) "
                        + "FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE='BASE TABLE' "
                        + "AND (ENGINE <> 'InnoDB' OR TABLE_COLLATION NOT LIKE 'utf8mb4%')",
                String.class);
        assertThat(bad).as("所有表都必须 InnoDB + utf8mb4").isEmpty();
    }

    /**
     * @return MySQL 版本是否 >= 8.0.16
     * <p>为什么按数字段比而不是字符串比：{@code "8.0.34" < "8.0.9"} 在字符串语义下成立，
     * 会得出错误的结论。</p>
     */
    private static boolean isCheckConstraintEnforced(String version) {
        String[] parts = version.split("[^0-9]+");
        int major = parts.length > 0 ? Integer.parseInt(parts[0]) : 0;
        int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
        int patch = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
        return major > 8 || (major == 8 && (minor > 0 || patch >= 16));
    }
}
