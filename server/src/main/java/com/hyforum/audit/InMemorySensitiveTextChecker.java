package com.hyforum.audit;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hyforum.common.audit.SensitiveTextChecker;
import com.hyforum.domain.sensitiveword.entity.SensitiveWord;
import com.hyforum.domain.sensitiveword.mapper.SensitiveWordMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 敏感词检查的 <b>M1 最小实现</b>（技术方案 §8.6 的占位实现，完整能力归 M5）。
 *
 * <p>为什么不直接在每次请求查库：敏感词表会随运营持续增删，检查又是高频动作
 * （每个内容入口都要过）。§8.6 第 1 条明确"服务启动时加载进内存"，因此这里也在内存里维护一份。</p>
 *
 * <p><b>刷新策略（必须显式说明）</b>：</p>
 * <ul>
 *   <li>启动时加载一次（{@code @PostConstruct}）；</li>
 *   <li>提供 {@link #reload()}，供后台在增删敏感词后调用；
 *       M5/M6 实现 {@code POST /api/admin/sensitive-words} 时<b>必须</b>在写库成功后调用它 ——
 *       否则 {@code M6_sensitive_word_crud_affects_filtering}（新增词后立即拦截）会失败；</li>
 * </ul>
 *
 * <p><b>与 M5 的明确交接</b>：本类只做"子串包含"匹配（朴素匹配）。§8.6 要求
 * DFA 算法、图片审核、审核队列、降级策略，以及"新增词后重启生效"的验收项
 * {@code M5_sensitive_words_loaded_at_startup}，都由 M5 在 {@code com.hyforum.audit}
 * 内实现 {@code ContentAuditService} 时一并替换。替换时保持
 * {@link SensitiveTextChecker} 接口不变，auth 侧调用点零改动。</p>
 */
@Service
public class InMemorySensitiveTextChecker implements SensitiveTextChecker {

    private static final Logger log = LoggerFactory.getLogger(InMemorySensitiveTextChecker.class);

    private final SensitiveWordMapper sensitiveWordMapper;

    /**
     * 词库快照。
     *
     * <p>用 {@link AtomicReference} 持有一个不可变集合，使"刷新"与"读取"无锁并发安全：
     * 读到的永远是某一个完整快照，不会出现"读到一半词库被清空"的中间态。</p>
     */
    private final AtomicReference<Set<String>> words = new AtomicReference<>(Set.of());

    public InMemorySensitiveTextChecker(SensitiveWordMapper sensitiveWordMapper) {
        this.sensitiveWordMapper = sensitiveWordMapper;
    }

    /** 启动时加载（技术方案 §8.6 第 1 条）。加载失败不让应用起不来，只记录并留空词库。 */
    @jakarta.annotation.PostConstruct
    public void init() {
        try {
            reload();
        } catch (RuntimeException ex) {
            // 不 fail-fast：敏感词库缺失会削弱内容安全，但不应导致整个服务不可用；
            // 这个 WARN 是运维需要立刻处理的信号。
            log.warn("敏感词库启动加载失败，当前按空词库运行，需人工排查：{}", ex.getMessage());
        }
    }

    /** 重新加载词库。后台增删敏感词后必须调用。 */
    public void reload() {
        List<SensitiveWord> rows = sensitiveWordMapper.selectList(
                Wrappers.<SensitiveWord>lambdaQuery().select(SensitiveWord::getWord));
        Set<String> snapshot = rows.stream()
                .map(SensitiveWord::getWord)
                .filter(w -> w != null && !w.isBlank())
                .map(String::trim)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        words.set(snapshot);
        log.info("敏感词库已加载，词条数={}", snapshot.size());
    }

    @Override
    public boolean containsSensitive(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        Set<String> snapshot = words.get();
        if (snapshot.isEmpty()) {
            return false;
        }
        for (String word : snapshot) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /** 当前已加载词条数（供测试与排障使用，避免测试去反射读私有字段）。 */
    public int loadedWordCount() {
        return words.get().size();
    }
}
