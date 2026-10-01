package com.hyforum.admin.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hyforum.common.api.PageResult;
import com.hyforum.domain.admin.entity.Admin;
import com.hyforum.domain.admin.entity.AdminOperationLog;
import com.hyforum.domain.admin.mapper.AdminMapper;
import com.hyforum.domain.admin.mapper.AdminOperationLogMapper;
import com.hyforum.admin.vo.AdminLogVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 操作留痕查询（M6；§6.11）：PLAN M6 验收"每个动作均可在 admin_operation_log 中追溯"的读侧。
 *
 * <p>按 {@code action} 筛选、时间倒序（最新动作在前 —— 审计场景关心的是"最近发生了什么"）。
 * 只读，本类<b>永不</b>提供更新/删除路径（留痕表只增不改不删，见实体注释）。</p>
 */
@Service
public class AdminLogService {

    private final AdminOperationLogMapper logMapper;
    private final AdminMapper adminMapper;

    public AdminLogService(AdminOperationLogMapper logMapper, AdminMapper adminMapper) {
        this.logMapper = logMapper;
        this.adminMapper = adminMapper;
    }

    /** 留痕列表（{@code GET /api/admin/logs}），可按 action 筛选。 */
    @Transactional(readOnly = true)
    public PageResult<AdminLogVO> list(String action, int page, int size) {
        int pageNo = PageResult.normalizePage(page);
        int pageSize = PageResult.normalizeSize(size);

        IPage<AdminOperationLog> result = logMapper.selectPage(new Page<>(pageNo, pageSize),
                Wrappers.<AdminOperationLog>lambdaQuery()
                        .eq(action != null && !action.isBlank(), AdminOperationLog::getAction, action)
                        .orderByDesc(AdminOperationLog::getId));

        List<AdminOperationLog> rows = result.getRecords();
        Map<Long, String> adminNames = loadAdminNames(rows);
        List<AdminLogVO> items = rows.stream()
                .map(row -> new AdminLogVO(
                        row.getId(),
                        row.getAdminId(),
                        adminNames.getOrDefault(row.getAdminId(), ""),
                        row.getAction(),
                        row.getTargetType(),
                        row.getTargetId(),
                        row.getReason(),
                        row.getDetail(),
                        row.getIp(),
                        row.getCreatedAt()))
                .toList();
        return PageResult.of(items, result.getTotal(), pageNo, pageSize);
    }

    /** 批量取操作人用户名（一次 IN 查询）。 */
    private Map<Long, String> loadAdminNames(List<AdminOperationLog> rows) {
        List<Long> ids = rows.stream().map(AdminOperationLog::getAdminId).distinct().toList();
        Map<Long, String> result = new HashMap<>();
        if (ids.isEmpty()) {
            return result;
        }
        for (Admin admin : adminMapper.selectBatchIds(ids)) {
            result.put(admin.getId(), admin.getUsername());
        }
        return result;
    }
}
