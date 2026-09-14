package com.hyforum.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 造数工具：用**纯 JDBC**写入 schema 里已有的最小合法行。
 *
 * <h2>为什么用 JDBC 而不是业务 Service</h2>
 * 测试基建必须在**业务代码还没写**的时候就能跑通（任务书 §5.2 的原话：
 * 「样板必须在没有业务代码时就能跑通，否则 M4 只能边写业务边试基建，样板就不成其为样板」）。
 * 因此这里只依赖 {@code docs/db/schema.sql} 的字段，不依赖任何 Mapper/Service。
 *
 * <h2>为什么只造「必需字段」</h2>
 * 只填 NOT NULL 且无默认值的列，其余交给数据库默认值 ——
 * 这样 schema 加新列时本工具不需要跟着改（减少第二份事实来源）。
 * 注意：基线规定**不使用外键**（{@code docs/db/README.md}），
 * 所以像 {@code post_like}/{@code comment} 这类表即使没有父行也能写入；
 * 但「点赞真实帖子」比「点赞不存在的 id」更接近生产，故仍提供了 {@link #seedPost()}。
 *
 * <h2>取值唯一性</h2>
 * {@code user.uk_username}、{@code board.uk_slug} 都是唯一索引。这里用进程内自增序号 +
 * 纳秒后缀生成取值，避免「上一个测试没清干净」导致唯一键冲突而误判。
 */
public class TestFixtures {

    /** 进程内自增序号：让同一次运行里造出的 username/slug 互不重复 */
    private static final AtomicInteger SEQ = new AtomicInteger();

    /**
     * 本次 JVM 运行的随机短标签（4 位十六进制）。
     *
     * <p>为什么不用 {@code System.nanoTime()} 拼取值：{@code user.username} 只有
     * <b>VARCHAR(20)</b>、{@code board.slug} 是 VARCHAR(30)。用纳秒（19 位）拼出来的字符串
     * 会直接触发 <i>Data too long for column 'username'</i>
     * —— 这是实测踩到的坑（见交付报告）。因此这里只允许生成 ≤10 字符、且长度有界的取值。</p>
     */
    private static final String RUN_TAG =
            String.format("%04x", ThreadLocalRandom.current().nextInt(0x10000));

    /** 占位用密码哈希（BCrypt 形态的假值）。测试不校验登录，不需要真实哈希 */
    private static final String FAKE_PASSWORD_HASH = "$2a$10$0123456789012345678901uGZ0hZ8fTgWQ0k6fJm9pQ0mVQk1Q0mVQk1Q0m";

    private final JdbcTemplate jdbcTemplate;

    public TestFixtures(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 一次性造好的最小数据集：一条 user + 一个 board + 一篇 post */
    public record SeedPost(long userId, long boardId, long postId) {
    }

    /** 造一个用户（只填 username/password_hash/nickname 三个 NOT NULL 列） */
    public long insertUser(String nickname) {
        String username = nextUsername();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO `user` (username, password_hash, nickname) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, username);
            ps.setString(2, FAKE_PASSWORD_HASH);
            ps.setString(3, nickname);
            return ps;
        }, keyHolder);
        return requireGeneratedKey(keyHolder, "user");
    }

    /** 造一个版块（name/slug 为 NOT NULL；is_resource=0 默认普通版块） */
    public long insertBoard(String name) {
        String slug = "tb" + RUN_TAG + String.format("%04d", SEQ.incrementAndGet() % 10000);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO `board` (name, slug) VALUES (?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            ps.setString(2, slug);
            return ps;
        }, keyHolder);
        return requireGeneratedKey(keyHolder, "board");
    }

    /**
     * 生成唯一且**不超过 VARCHAR(20)** 的 username：{@code tu} + 4 位运行标签 + 4 位序号 = 最长 10 字符。
     */
    private static String nextUsername() {
        return "tu" + RUN_TAG + String.format("%04d", SEQ.incrementAndGet() % 10000);
    }

    /** 造一篇帖子（board_id/user_id/title 为 NOT NULL） */
    public long insertPost(long boardId, long userId, String title) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO `post` (board_id, user_id, title) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, boardId);
            ps.setLong(2, userId);
            ps.setString(3, title);
            return ps;
        }, keyHolder);
        return requireGeneratedKey(keyHolder, "post");
    }

    /** 造「一个用户 + 一个版块 + 一篇帖子」，返回三者 id（M4/M5/M6 的点赞、收藏、评论测试都会用到） */
    public SeedPost seedPost() {
        long userId = insertUser("测试用户");
        long boardId = insertBoard("测试版块");
        long postId = insertPost(boardId, userId, "测试帖子标题");
        return new SeedPost(userId, boardId, postId);
    }

    /**
     * 取回自增主键。
     *
     * <p>**不能用 {@code SELECT LAST_INSERT_ID()} 另发一条查询代替**：{@code JdbcTemplate}
     * 每次调用都可能拿到连接池里不同的连接，而 {@code LAST_INSERT_ID()} 是**连接级**状态，
     * 换条连接就取到别人的（或 0）。这也是并发测试里最容易出现的隐性错误。</p>
     */
    private static long requireGeneratedKey(KeyHolder keyHolder, String table) {
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入 " + table + " 后没有拿到自增主键，请检查表结构与插入语句");
        }
        return key.longValue();
    }
}
