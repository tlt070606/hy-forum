package com.hyforum.admin.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hyforum.admin.vo.AdminSensitiveWordVO;
import com.hyforum.common.api.ErrorCode;
import com.hyforum.common.api.PageResult;
import com.hyforum.common.audit.SensitiveTextChecker;
import com.hyforum.common.exception.BizException;
import com.hyforum.domain.admin.entity.AdminOperationLog;
import com.hyforum.domain.sensitiveword.entity.SensitiveWord;
import com.hyforum.domain.sensitiveword.mapper.SensitiveWordMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 敏感词管理（M6 批次二；PLAN M6 验收"维护敏感词"）。
 *
 * <h2>改词库必须刷新内存快照</h2>
 * <p>检查器（audit 包实现）持有一个启动期/刷新期加载的<b>内存快照</b>，
 * 增删词若只写表不刷新，新词要等下次重启才生效 —— 那是"配置改了但没人知道"的
 * 经典暗坑。因此本服务每个写方法在<b>同一事务提交后</b>调用
 * {@link SensitiveTextChecker#refresh()}（common 端口，铁律 3 允许 admin → common）。
 * 刷新失败只打 WARN 不回滚：词已落库，重启后仍会生效；把词库写操作整体回滚
 * 反而让"为什么没保存上"更难排查。</p>
 */
@Service
public class AdminSensitiveWordService {

    private static final Logger log = LoggerFactory.getLogger(AdminSensitiveWordService.class);

    public static final String ACTION_SENSITIVE_WORD_CREATE = "SENSITIVE_WORD_CREATE";
    public static final String ACTION_SENSITIVE_WORD_DELETE = "SENSITIVE_WORD_DELETE";

    /** 词长上限，与 {@code sensitive_word.word} 的 VARCHAR(100) 一致。 */
    private static final int MAX_WORD_LENGTH = 100;

    private final SensitiveWordMapper sensitiveWordMapper;
    private final SensitiveTextChecker sensitiveTextChecker;
    private final AdminOperationLogger operationLogger;

    public AdminSensitiveWordService(SensitiveWordMapper sensitiveWordMapper,
                                     SensitiveTextChecker sensitiveTextChecker,
                                     AdminOperationLogger operationLogger) {
        this.sensitiveWordMapper = sensitiveWordMapper;
        this.sensitiveTextChecker = sensitiveTextChecker;
        this.operationLogger = operationLogger;
    }

    /** 敏感词列表（id 倒序 = 新增在前）。 */
    @Transactional(readOnly = true)
    public PageResult<AdminSensitiveWordVO> list(int page, int size) {
        int pageNo = PageResult.normalizePage(page);
        int pageSize = PageResult.normalizeSize(size);
        IPage<SensitiveWord> result = sensitiveWordMapper.selectPage(new Page<>(pageNo, pageSize),
                Wrappers.<SensitiveWord>lambdaQuery().orderByDesc(SensitiveWord::getId));
        List<AdminSensitiveWordVO> items = result.getRecords().stream()
                .map(w -> new AdminSensitiveWordVO(w.getId(), w.getWord(), w.getCreatedAt()))
                .toList();
        return PageResult.of(items, result.getTotal(), pageNo, pageSize);
    }

    /**
     * 新增敏感词（{@code POST /api/admin/sensitive-words}），事务提交后刷新内存快照。
     *
     * @return 留痕行 id
     */
    @Transactional
    public long add(long adminId, String word, String ip) {
        if (word == null || word.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "敏感词不能为空");
        }
        String trimmed = word.trim();
        if (trimmed.length() > MAX_WORD_LENGTH) {
            throw new BizException(ErrorCode.BAD_REQUEST, "敏感词不能超过 " + MAX_WORD_LENGTH + " 字");
        }
        SensitiveWord entity = new SensitiveWord();
        entity.setWord(trimmed);
        try {
            sensitiveWordMapper.insert(entity);
        } catch (DuplicateKeyException ex) {
            // uk_word 撞键：重复添加是管理端最常见的误操作，给可读说法而不是 500
            throw new BizException(ErrorCode.BAD_REQUEST, "敏感词已存在：" + trimmed);
        }
        refreshSnapshotSafely();
        long logId = operationLogger.log(adminId, ACTION_SENSITIVE_WORD_CREATE,
                AdminOperationLog.TARGET_SENSITIVE_WORD, entity.getId(), null,
                "word:" + trimmed, ip);
        log.info("敏感词新增：adminId={} word={}", adminId, trimmed);
        return logId;
    }

    /**
     * 删除敏感词（{@code DELETE /api/admin/sensitive-words/{id}}；表无 is_deleted，物理删除）。
     *
     * @return 留痕行 id
     */
    @Transactional
    public long remove(long adminId, long wordId, String ip) {
        SensitiveWord word = sensitiveWordMapper.selectById(wordId);
        if (word == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "敏感词不存在");
        }
        sensitiveWordMapper.deleteById(wordId);
        refreshSnapshotSafely();
        long logId = operationLogger.log(adminId, ACTION_SENSITIVE_WORD_DELETE,
                AdminOperationLog.TARGET_SENSITIVE_WORD, wordId, null,
                "word:" + word.getWord(), ip);
        log.info("敏感词删除：adminId={} word={}", adminId, word.getWord());
        return logId;
    }

    /**
     * 刷新内存快照（失败不回滚业务，只 WARN —— 见类注释）。
     */
    private void refreshSnapshotSafely() {
        try {
            sensitiveTextChecker.refresh();
        } catch (RuntimeException ex) {
            log.warn("敏感词快照刷新失败（词已落库，重启后生效，请人工排查）：{}", ex.getMessage());
        }
    }
}
