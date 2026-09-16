package com.hyforum.post.service;

import com.hyforum.domain.post.mapper.PostMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 帖子浏览量计数器（docs/技术方案.md §7 的 {@code hy:post:view:{postId}} 与 §8.3）——
 * <b>L1 裁决第 4 条</b>：详情页的浏览量 {@code +1} <b>走 Redis</b>，
 * 不是每条都 {@code UPDATE post SET view_count = view_count + 1}。
 *
 * <h2>做法与取舍（L1 要求"由你设计并在代码注释里写明取舍"）</h2>
 * <p><b>选的是「Redis 计数 + 定时批量回写」</b>，也正是 §7／§8.3 写明的方案：</p>
 * <ol>
 *   <li>详情接口只做 {@code INCR hy:post:view:{id}}（一次 Redis 往返、无行锁）；</li>
 *   <li>每 5 分钟（{@code hy.post.view-flush-interval-ms}，见 {@link #flushPendingViews()}）
 *       用 {@code SCAN} 扫出所有计数键，逐个 {@code GETDEL} 取走增量，
 *       再 {@code UPDATE post SET view_count = view_count + delta} 累加回库。</li>
 * </ol>
 *
 * <p><b>为什么不选"读时合并"（详情页把 DB 值与 Redis 增量相加后直接返回，永不回写）</b>：
 * 那样数据库里的 {@code view_count} 永远是过期的，而它是<b>列表页排序输入</b>
 * （§8.5 的热门排序含 {@code view_count}）与后台统计的数据来源 ——
 * 一个"只在详情页正确"的字段会让热门榜长期偏低，且没有任何地方能看出原因。
 * 回写方案让数据库最多滞后 5 分钟，代价是一个定时任务。</p>
 *
 * <p><b>取舍的代价（如实记录）</b>：</p>
 * <ul>
 *   <li><b>回写失败会丢计数</b>：{@code GETDEL} 之后若 UPDATE 失败（DB 抖动），
 *       这一批增量就没了。刻意<b>不</b>为此引入事务/重试/消息队列 ——
 *       浏览量是弱一致指标，为它上重装备与铁律 7（禁重组件）冲突，也不划算；
 *       §8.2 本来就留了"管理员手动触发的计数校准任务"作为兜底。</li>
 *   <li><b>数据库滞后 ≤5 分钟</b>：因此列表页的 {@code viewCount} 可能偏小一点；
 *       详情页则把未回写增量算进去（见 {@link #recordAndGetPendingDelta}），用户看到的是实时值。</li>
 *   <li><b>用 SCAN 而不是 KEYS</b>：{@code KEYS} 在大 key 空间下会阻塞 Redis 单线程，
 *       属于典型的"本地几千条数据看不出问题、线上一次事故"的写法。</li>
 *   <li><b>进程重启会丢未回写的增量</b>（Redis 里的键仍在，下次回写会带走它们，
 *       所以只有 Redis 自身丢数据时才会真的丢）—— 可接受。</li>
 * </ul>
 *
 * <p><b>Redis 故障时的行为</b>：详情页<b>不因为浏览量而失败</b>。
 * 浏览量是附属信息，让"Redis 抖一下 → 详情页 500"是不可接受的可用性代价，
 * 因此这里捕获异常并记 WARN，计数按 0 处理（宁可少算，不可打不开）。</p>
 */
@Component
public class PostViewCounter {

    private static final Logger log = LoggerFactory.getLogger(PostViewCounter.class);

    /** Redis 键前缀，与技术方案 §7 的 {@code hy:post:view:{postId}} 一致。 */
    public static final String KEY_PREFIX = "hy:post:view:";

    /** 扫描批次大小（SCAN 的 COUNT 提示值）。 */
    private static final long SCAN_BATCH = 500L;

    private final StringRedisTemplate redis;
    private final PostMapper postMapper;

    public PostViewCounter(StringRedisTemplate redis, PostMapper postMapper) {
        this.redis = redis;
        this.postMapper = postMapper;
    }

    /**
     * 记一次浏览：{@code INCR} 并把"当前未回写的增量"返回给调用方。
     *
     * <p>返回值直接用于详情响应，因此<b>包含本次浏览</b>
     * （用户点开帖子时看到的数字里应该有自己这一次）。</p>
     *
     * @param postId 帖子 id
     * @return 未回写的增量；Redis 不可用时返回 0
     */
    public long recordAndGetPendingDelta(long postId) {
        try {
            Long value = redis.opsForValue().increment(KEY_PREFIX + postId);
            return value == null ? 0L : value;
        } catch (RuntimeException ex) {
            // 见类注释：浏览量不得成为详情页的可用性单点
            log.warn("浏览量 Redis 计数失败（按 0 处理，不影响详情页返回）：postId={} 原因={}",
                    postId, ex.getMessage());
            return 0L;
        }
    }

    /**
     * 读取"当前未回写的增量"，<b>不做 +1</b>。
     *
     * <p>给编辑等"需要显示最新浏览量、但不应把浏览量加一次"的场景用（详情接口负责 +1）。</p>
     *
     * @param postId 帖子 id
     * @return 增量计数的原始字符串（不存在返回 {@code null}）
     */
    public String pendingDelta(long postId) {
        return redis.opsForValue().get(KEY_PREFIX + postId);
    }

    /**
     * 把 Redis 里累积的浏览量批量回写数据库（§7：每 5 分钟一次）。
     *
     * <p>用 {@code GETDEL}（Redis 6.2+ 的 {@code GETDEL}，Spring Data Redis 在可用时用它，
     * 否则退化为 GET+DEL）取走增量：<b>取走而不是只读</b>，
     * 否则下一次回写会把同一批浏览量再累加一遍（这是本方案最容易写错的一步）。</p>
     *
     * <p>{@code fixedDelay} 而不是 {@code fixedRate}：回写要读 Redis + 写 MySQL，
     * 用固定延迟可以自然错开，不会在回写变慢时堆积任务。</p>
     */
    @Scheduled(fixedDelayString = "${hy.post.view-flush-interval-ms:300000}")
    public void flushPendingViews() {
        int flushed = 0;
        try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions()
                .match(KEY_PREFIX + "*")
                .count(SCAN_BATCH)
                .build())) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                String value = redis.opsForValue().getAndDelete(key);
                if (value == null) {
                    continue;   // 另一个实例/上一次扫描已取走
                }
                long delta;
                try {
                    delta = Long.parseLong(value.trim());
                } catch (NumberFormatException ex) {
                    log.warn("浏览量增量值非法，已丢弃：key={} value={}", key, value);
                    continue;
                }
                if (delta <= 0) {
                    continue;
                }
                long postId = Long.parseLong(key.substring(KEY_PREFIX.length()));
                int affected = postMapper.addViewCount(postId, delta);
                if (affected == 0) {
                    // 帖子被物理删除：增量无处可写，丢弃并记一笔（逻辑删除的帖子行仍在，不会走到这里）
                    log.warn("浏览量回写目标不存在，增量已丢弃：postId={} delta={}", postId, delta);
                } else {
                    flushed++;
                }
            }
        } catch (RuntimeException ex) {
            // 定时任务抛异常会被 Spring 吞掉（只记日志），这里显式记录以便运维能看见
            log.error("浏览量回写失败：{}", ex.getMessage(), ex);
            return;
        }
        if (flushed > 0) {
            log.info("浏览量回写完成，涉及 {} 个帖子", flushed);
        }
    }
}
