package com.hyforum.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 并发测试工具：「N 个线程同时开跑，各自报告成功还是抛了什么异常」。
 *
 * <h2>为什么必须用它，而不是随便 {@code new Thread}</h2>
 * <ol>
 *   <li><b>真并发、可自证</b>：先用 {@code ready} 闩锁等齐全部线程，再用 {@code start} 闩锁同时放行。
 *       因此「第一次跑就绿」时可以先看 {@link Outcome#readyThreads()}：
 *       如果它 &lt; 线程总数，说明测试根本没并发起来（例如误在事务里串行执行），断言就不可信
 *       —— 这是任务书 §7 明确提醒的坑。</li>
 *   <li><b>异常不吞</b>：每个线程的异常都被收集起来（而不是打印到了日志里就当没发生），
 *       测试可以断言「恰好 19 个线程拿到 DuplicateKeyException」这种精确语义。</li>
 *   <li><b>不会挂死</b>：闩锁与线程池等待都有超时，超时直接抛异常，而不是让 CI 卡到天荒地老。</li>
 * </ol>
 *
 * <h2>用法（幂等样板的核心三行）</h2>
 * <pre>{@code
 * Outcome outcome = ConcurrencyTestSupport.runConcurrently(20, i ->
 *         jdbcTemplate.update("INSERT INTO post_like (post_id, user_id) VALUES (?, ?)", postId, userId));
 * assertThat(outcome.successCount()).isEqualTo(1);
 * assertThat(outcome.countOf(DuplicateKeyException.class)).isEqualTo(19);
 * }</pre>
 *
 * <p>注意：并发任务各自独立提交事务（{@code JdbcTemplate} 默认自动提交），
 * 所以**不能**用 {@code @Transactional} 回滚来清理数据，必须在测试前后显式清表
 * （见 {@link TestTableCleaner}）。</p>
 */
public final class ConcurrencyTestSupport {

    /** 等待「全部线程就位」与「线程池收尾」的超时（秒）—— 足够宽，但避免 CI 无限等待 */
    private static final long READY_TIMEOUT_SECONDS = 30;
    private static final long FINISH_TIMEOUT_SECONDS = 120;

    /** 沿 cause 链查找异常时的最大深度（防止自引用 cause 造成死循环） */
    private static final int MAX_CAUSE_DEPTH = 20;

    private ConcurrencyTestSupport() {
        // 工具类，不实例化
    }

    /** 并发任务：入参是线程序号（0..n-1），便于断言「各线程互不相同」的场景 */
    @FunctionalInterface
    public interface IndexedTask {
        void run(int index) throws Exception;
    }

    /**
     * 并发执行同一任务 {@code threadCount} 次，同时放行。
     *
     * @param threadCount 线程数（幂等类测试建议 ≥ 20：线程太少会掩盖竞态）
     * @param task        每个线程执行的任务；抛异常即计为该线程失败
     * @return 汇总结果（成功数、异常清单、按类型计数）
     */
    public static Outcome runConcurrently(int threadCount, IndexedTask task) {
        if (threadCount <= 0) {
            throw new IllegalArgumentException("threadCount 必须 > 0，实际：" + threadCount);
        }

        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        List<Long> successNanos = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(threadCount, runnable -> {
            Thread t = new Thread(runnable, "hy-concurrency-test");
            t.setDaemon(true);   // 测试断言失败时不要因为仍有线程存活而挂住 JVM
            return t;
        });

        try {
            List<Callable<Void>> jobs = new ArrayList<>(threadCount);
            for (int i = 0; i < threadCount; i++) {
                final int index = i;
                jobs.add(() -> {
                    ready.countDown();                  // 报到：我已就位
                    // 全部线程就位后才一起放行 —— 这是"同时开跑"的唯一保证
                    if (!start.await(READY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("等待同时开跑的闩锁超时（说明有线程没能就位）");
                    }
                    long begin = System.nanoTime();
                    try {
                        task.run(index);
                        successNanos.add(System.nanoTime() - begin);
                    } catch (Throwable t) {
                        failures.add(t);
                    }
                    return null;
                });
            }
            for (Callable<Void> job : jobs) {
                pool.submit(job);
            }

            if (!ready.await(READY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        "只有 " + (threadCount - ready.getCount()) + "/" + threadCount
                                + " 个线程就位；请检查测试是否被串行化（例如误加了 @Transactional）");
            }
            // 放行前记录真实就位数，供 Outcome.readyThreads() 自证「确实并发了」
            int readyThreads = threadCount - (int) ready.getCount();
            start.countDown();                          // 同时放行

            pool.shutdown();
            if (!pool.awaitTermination(FINISH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("并发任务在 " + FINISH_TIMEOUT_SECONDS + " 秒内没有全部结束（疑似死锁）");
            }
            return new Outcome(threadCount, readyThreads, failures, successNanos);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("并发测试被中断", e);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 并发执行结果。
     *
     * <p>{@code successCount() + failureCount() == total()} 恒成立，可直接用于断言「没有线程"消失"」。</p>
     */
    public static final class Outcome {

        private final int total;
        private final int readyThreads;
        private final List<Throwable> failures;
        private final List<Long> successNanos;

        Outcome(int total, int readyThreads, List<Throwable> failures, List<Long> successNanos) {
            this.total = total;
            this.readyThreads = readyThreads;
            this.failures = failures;
            this.successNanos = successNanos;
        }

        /** 收到「就位」信号的线程数（等于 total 才说明真并发了） */
        public int readyThreads() {
            return readyThreads;
        }

        public int total() {
            return total;
        }

        public int successCount() {
            return successNanos.size();
        }

        public int failureCount() {
            return failures.size();
        }

        /** @return 抛出的异常实例（顺序不保证，因为线程完成顺序不确定） */
        public List<Throwable> failures() {
            return List.copyOf(failures);
        }

        /**
         * 统计「cause 链上能匹配到该类型」的失败线程数。
         *
         * <p>为什么要沿 cause 链找：Spring 的 SQL 翻译、MyBatis 的包装、
         * {@code ExecutionException} 之类都可能把真正的原因藏在下层。
         * 直接判断顶层类型会让断言变得脆弱。</p>
         */
        public int countOf(Class<? extends Throwable> type) {
            int count = 0;
            for (Throwable t : failures) {
                if (hasCauseOfType(t, type)) {
                    count++;
                }
            }
            return count;
        }

        private static boolean hasCauseOfType(Throwable root, Class<? extends Throwable> type) {
            Throwable current = root;
            for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
                if (type.isInstance(current)) {
                    return true;
                }
                current = current.getCause() == current ? null : current.getCause();
            }
            return false;
        }

        /** @return 最慢/最快成功耗时（毫秒）；断言失败时贴出来有助于判断是否真的竞争了 */
        public String timingSummary() {
            if (successNanos.isEmpty()) {
                return "成功线程耗时：无";
            }
            long min = Long.MAX_VALUE;
            long max = Long.MIN_VALUE;
            long sum = 0;
            for (long n : successNanos) {
                min = Math.min(min, n);
                max = Math.max(max, n);
                sum += n;
            }
            return String.format("成功线程耗时：min=%dms max=%dms avg=%dms",
                    min / 1_000_000, max / 1_000_000, (sum / successNanos.size()) / 1_000_000);
        }

        /** 失败清单摘要（把顶层异常类型与 cause 链拼成一行，断言失败时直接看得见原因） */
        public String failuresSummary() {
            if (failures.isEmpty()) {
                return "无失败";
            }
            StringBuilder sb = new StringBuilder();
            for (Throwable t : failures) {
                sb.append("\n    - ");
                Throwable current = t;
                for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
                    sb.append(current.getClass().getSimpleName());
                    if (current.getMessage() != null) {
                        sb.append("(").append(current.getMessage().replace('\n', ' ')).append(")");
                    }
                    current = current.getCause() == current ? null : current.getCause();
                    if (current != null) {
                        sb.append(" ← ");
                    }
                }
            }
            return sb.toString();
        }

        @Override
        public String toString() {
            return String.format("总线程=%d 成功=%d 失败=%d；%s；失败明细：%s",
                    total, successCount(), failureCount(), timingSummary(), failuresSummary());
        }
    }
}
