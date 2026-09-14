package com.hyforum.schema;

import com.hyforum.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatObject;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * schema 不变量测试：{@code comment} 表的**两层结构**约束
 * {@code chk_comment_two_levels}（P1-3 定案：楼中楼归并语义）。
 *
 * <h2>它守护什么</h2>
 * 契约规定主楼是 {@code (parent_id=0, root_id=0)}，楼中楼是 {@code (x, x)}（x = 所属主楼 id）。
 * 三层结构因此在数据上不可能被表达。这条约束是「楼中楼不超过两层」的最后一道机械防线
 * —— 业务代码写错、或有人手工改库，都要被它挡住。
 * 本测试把 M0 阶段 {@code scripts/verify_m0.ps1} 里的一次性实证，变成 **CI 每次自动跑**的守护。
 *
 * <h2>⚠️ 为什么必须双向断言（本类的重点）</h2>
 * 「只测拒绝」是不够的：把约束写成 {@code CHECK(1=0)} 同样能让 3 个非法组合全部被拒绝，
 * 于是「拒绝测试」全绿 —— 但它会让**所有合法评论都写不进去**。
 * 这是 L1 在 M0 阶段真实踩过的坑，所以本测试的两个方向缺一不可：
 * <ol>
 *   <li>合法组合 {@code (0,0)}、{@code (x,x)} **必须写入成功**（这条才拦得住 {@code CHECK(1=0)}）；</li>
 *   <li>三种非法组合 {@code (5,0)}、{@code (0,7)}、{@code (3,9)} **必须失败**，
 *       且失败原因**必须是这个 CHECK 约束本身**（错误码 3819 + 消息里出现约束名）。</li>
 * </ol>
 *
 * <h2>为什么断言 SQL 错误码而不是 Spring 异常类型</h2>
 * Spring 的 SQL 异常翻译表随版本变化（3819 是否被翻成
 * {@code DataIntegrityViolationException} 并不稳定）。
 * 直接断言 MySQL 错误码 3819（{@code ER_CHECK_CONSTRAINT_VIOLATED}）与约束名，
 * 既能钉死「是这个约束拦的」，又不会因为 Spring 版本升级而虚假变红。
 *
 * <p>另外：基线的表**无外键**，所以 {@code comment} 里的 {@code post_id}/{@code user_id}
 * 不需要真实存在——本测试因此只insert评论本身，不造 user/post 种子数据。</p>
 */
class SchemaInvariantTest extends IntegrationTestBase {

    /** 契约里的约束名（docs/db/schema.sql 表 5） */
    private static final String CONSTRAINT_NAME = "chk_comment_two_levels";

    /** MySQL 错误码：CHECK 约束违约（ER_CHECK_CONSTRAINT_VIOLATED） */
    private static final int ER_CHECK_CONSTRAINT_VIOLATED = 3819;

    @Override
    protected String[] tablesToClean() {
        return new String[]{"comment"};
    }

    @Test
    @DisplayName("chk_comment_two_levels：合法 (0,0)/(x,x) 必须写入成功，三种非法组合必须被这个约束拒绝")
    void SCHEMA_check_constraint_rejects_violation() {
        // ---------- 前置：约束必须真实存在，且不是「空转」的写法 ----------
        String clause = jdbcTemplate.queryForObject(
                "SELECT CHECK_CLAUSE FROM information_schema.CHECK_CONSTRAINTS "
                        + "WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = ?",
                String.class, CONSTRAINT_NAME);
        assertThat(clause)
                .as("约束 %s 必须存在于测试库；它由 docs/db/schema.sql 派生（scripts/init_test_db.ps1 重建）", CONSTRAINT_NAME)
                .isNotNull();
        assertThat(clause)
                .as("约束定义必须同时引用 parent_id 与 root_id；"
                        + "若只引用其中一个（或写成 1=0 之类），下面的合法写入断言就会失败——这正是要双向断言的原因")
                .contains("parent_id")
                .contains("root_id");

        // ---------- 方向一：合法组合必须写入成功 ----------
        // ① 主楼：(0, 0)
        long mainCommentId = insertComment(0L, 0L);
        assertThat(mainCommentId)
                .as("合法主楼 (parent_id=0, root_id=0) 必须写入成功；"
                        + "若这里失败，说明约束被写成了 CHECK(1=0) 这类「只拒绝、不放行」的形式")
                .isPositive();

        // ② 楼中楼：(x, x)，x = 主楼 id（归并语义：parent_id 与 root_id 恒等于所属主楼 id）
        long replyCommentId = insertComment(mainCommentId, mainCommentId);
        assertThat(replyCommentId)
                .as("合法楼中楼 (parent_id=root_id=%s) 必须写入成功", mainCommentId)
                .isPositive();

        // 方向一的补强：两行必须真的落库（而不是被某种静默忽略"成功"了）
        Integer legalRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM `comment` WHERE (parent_id = 0 AND root_id = 0) OR (parent_id <> 0 AND parent_id = root_id)",
                Integer.class);
        assertThat(legalRows).as("两条合法评论必须都落库").isEqualTo(2);

        // ---------- 方向二：三种非法组合必须失败 ----------
        // (5,0)   parent_id<>0 但 root_id=0        → 想表达"有父却又是主楼"
        assertRejectedAsTwoLevelViolation(5L, 0L);
        // (0,7)   parent_id=0  但 root_id<>0       → 想表达"是主楼却有归并目标"
        assertRejectedAsTwoLevelViolation(0L, 7L);
        // (3,9)   parent_id<>0 且 parent_id<>root_id → 想表达"楼中楼的楼中楼"（第三层）
        assertRejectedAsTwoLevelViolation(3L, 9L);

        // 方向二的补强：非法写入不得到数据库里留下任何痕迹
        Integer totalRows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM `comment`", Integer.class);
        assertThat(totalRows)
                .as("被拒绝的 3 次写入不得留下任何行（当前应只有 2 条合法评论）")
                .isEqualTo(2);
    }

    /**
     * 断言某组合被 {@code chk_comment_two_levels} 拒绝。
     *
     * <p>断言三步走，缺一不可：
     * ① 确实抛异常；② 根因是 MySQL 错误码 3819（CHECK 违约，不是 NOT NULL/超长之类的别的原因）；
     * ③ 消息里出现本约束名（排除"被别的 CHECK 拦下"这种张冠李戴）。</p>
     */
    private void assertRejectedAsTwoLevelViolation(long parentId, long rootId) {
        Throwable thrown = catchThrowable(() -> insertComment(parentId, rootId));

        assertThat(thrown)
                .as("非法组合 (parent_id=%s, root_id=%s) 必须被拒绝：两层结构不变量不允许它落库", parentId, rootId)
                .isInstanceOf(DataAccessException.class);

        SQLException sqlException = rootSqlException(thrown);
        // 注意用 assertThatObject：java.sql.SQLException 自身实现了 Iterable<Throwable>，
        // 直接 assertThat(sqlException) 会因 Iterable 与 Object 两个重载而产生歧义（编译期就报错）
        assertThatObject(sqlException)
                .as("非法组合 (parent_id=%s, root_id=%s) 的拒绝原因必须是 SQL 层错误", parentId, rootId)
                .isNotNull();
        assertThat(sqlException.getErrorCode())
                .as("必须是 CHECK 约束违约（MySQL %s）；错误码 %s 说明是别的原因拦下的，断言就等于没测到这个约束",
                        ER_CHECK_CONSTRAINT_VIOLATED, sqlException.getErrorCode())
                .isEqualTo(ER_CHECK_CONSTRAINT_VIOLATED);
        assertThat(sqlException.getMessage())
                .as("错误消息里必须出现约束名 %s，才能证明是**这个**约束拦的", CONSTRAINT_NAME)
                .contains(CONSTRAINT_NAME);
    }

    /** 插入一条评论，只填 NOT NULL 列；parent_id/root_id 由调用方给出（这正是被测对象） */
    private long insertComment(long parentId, long rootId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO `comment` (post_id, user_id, parent_id, root_id, content) VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, 1L);                 // post_id：无外键，基线允许引用不存在的帖子
            ps.setLong(2, 1L);                 // user_id：同上
            ps.setLong(3, parentId);
            ps.setLong(4, rootId);
            ps.setString(5, "schema 不变量测试内容");
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException(
                    "插入 comment 后没有拿到自增主键（parent_id=" + parentId + ", root_id=" + rootId + "）");
        }
        return key.longValue();
    }

    /** 沿 cause 链取最底层的 {@link SQLException}（Spring 会把 SQLException 包在 DataAccessException 里） */
    private static SQLException rootSqlException(Throwable thrown) {
        Throwable current = thrown;
        for (int depth = 0; current != null && depth < 20; depth++) {
            if (current instanceof SQLException sqlException) {
                return sqlException;
            }
            current = (current.getCause() == current) ? null : current.getCause();
        }
        return null;
    }
}
