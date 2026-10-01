package com.hyforum.admin.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hyforum.admin.vo.AdminReportVO;
import com.hyforum.common.api.ErrorCode;
import com.hyforum.common.api.PageResult;
import com.hyforum.common.exception.BizException;
import com.hyforum.domain.admin.entity.AdminOperationLog;
import com.hyforum.domain.interaction.entity.Comment;
import com.hyforum.domain.interaction.mapper.CommentMapper;
import com.hyforum.domain.post.entity.Post;
import com.hyforum.domain.post.mapper.PostMapper;
import com.hyforum.domain.report.entity.Report;
import com.hyforum.domain.report.mapper.ReportMapper;
import com.hyforum.domain.user.entity.User;
import com.hyforum.domain.user.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 举报处理（M6 批次二；PLAN M6 验收"处理举报"）。
 *
 * <h2>本服务只登记"处置结论"，不动被举报内容</h2>
 * <p>屏蔽帖子/评论走各自的审核端点（{@code PUT /api/admin/posts|comments/{id}/status}）——
 * 那里沉淀着可见性与计数的全部口径（评论的计数同步在 {@code CommentAuditService}）。
 * 举报处置若顺手"再屏蔽一次"，就会出现第二个写入口，口径必然漂移。
 * 正确的编排是管理端前端串两步：处置内容 → 登记举报结论。本端点只负责后者。</p>
 *
 * <h2>留痕的 target 指向被举报对象，而不是举报行</h2>
 * <p>{@code admin_operation_log.idx_target} 的设计意图是"某个对象被谁处置过"——
 * 追溯链的锚点是帖子/评论/用户本身。因此本动作的 log.target = 举报的 target，
 * 举报行 id 记在 detail（{@code report:123:status:0->1}）。</p>
 */
@Service
public class AdminReportService {

    private static final Logger log = LoggerFactory.getLogger(AdminReportService.class);

    /** 动作编码（schema 表 16 的 action 列注释枚举之一）。 */
    public static final String ACTION_REPORT_DISPOSE = "REPORT_DISPOSE";

    /** 处置说明长度上限，与 {@code report.handle_note} 的 VARCHAR(200) 一致。 */
    private static final int MAX_NOTE_LENGTH = 200;

    /** 举报对象摘要的截断长度（列表可读即可，全文在详情里）。 */
    private static final int SUMMARY_MAX_LENGTH = 50;

    private final ReportMapper reportMapper;
    private final PostMapper postMapper;
    private final CommentMapper commentMapper;
    private final UserMapper userMapper;
    private final AdminOperationLogger operationLogger;

    public AdminReportService(ReportMapper reportMapper,
                              PostMapper postMapper,
                              CommentMapper commentMapper,
                              UserMapper userMapper,
                              AdminOperationLogger operationLogger) {
        this.reportMapper = reportMapper;
        this.postMapper = postMapper;
        this.commentMapper = commentMapper;
        this.userMapper = userMapper;
        this.operationLogger = operationLogger;
    }

    /**
     * 后台举报列表（§6.11 {@code GET /api/admin/reports}）：可按 {@code status} 筛选。
     *
     * <p>时间升序（先处理早的），与其他队列一致。每行带被举报对象摘要与举报人昵称
     * （批量装配，不 N+1）。</p>
     */
    @Transactional(readOnly = true)
    public PageResult<AdminReportVO> listReports(Integer status, int page, int size) {
        if (status != null && status != 0 && status != 1 && status != 2) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "status 只支持 0（待处理）/ 1（已处理）/ 2（已驳回），收到：" + status);
        }
        int pageNo = PageResult.normalizePage(page);
        int pageSize = PageResult.normalizeSize(size);

        IPage<Report> result = reportMapper.selectPage(new Page<>(pageNo, pageSize),
                Wrappers.<Report>lambdaQuery()
                        .eq(status != null, Report::getStatus, status)
                        .orderByAsc(Report::getCreatedAt)
                        .orderByAsc(Report::getId));

        List<Report> rows = result.getRecords();
        Map<Long, String> reporters = loadNicknames(rows);
        Map<String, String> summaries = loadTargetSummaries(rows);
        List<AdminReportVO> items = rows.stream()
                .map(row -> new AdminReportVO(
                        row.getId(),
                        row.getTargetType(),
                        row.getTargetId(),
                        summaries.getOrDefault(row.getTargetType() + ":" + row.getTargetId(), "(已删除)"),
                        row.getUserId(),
                        reporters.getOrDefault(row.getUserId(), ""),
                        row.getReasonType(),
                        row.getReasonDetail(),
                        row.getStatus() == null ? 0 : row.getStatus(),
                        row.getHandleNote(),
                        row.getHandledAt(),
                        row.getCreatedAt()))
                .toList();
        return PageResult.of(items, result.getTotal(), pageNo, pageSize);
    }

    /**
     * 处置举报（§6.11 {@code PUT /api/admin/reports/{id}/dispose}）。
     *
     * @param outcome 1 = 已处理（内容已按违规处置）/ 2 = 已驳回（内容未违规）
     * @param note    处置说明（<b>必填</b>，合规 C9：处置依据可追溯）
     * @param ip      操作来源 IP
     * @return 留痕行 id
     */
    @Transactional
    public long dispose(long adminId, long reportId, int outcome, String note, String ip) {
        if (outcome != 1 && outcome != 2) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "outcome 只支持 1（已处理）或 2（已驳回），收到：" + outcome);
        }
        if (note == null || note.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "处置说明必须提供（合规 C9）");
        }
        String trimmedNote = note.trim();
        if (trimmedNote.length() > MAX_NOTE_LENGTH) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "note 不能超过 " + MAX_NOTE_LENGTH + " 字符");
        }
        Report report = reportMapper.selectById(reportId);
        if (report == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "举报不存在");
        }

        int previous = report.getStatus() == null ? 0 : report.getStatus();
        reportMapper.update(null, Wrappers.<Report>lambdaUpdate()
                .eq(Report::getId, reportId)
                .set(Report::getStatus, outcome)
                .set(Report::getHandlerId, adminId)
                .set(Report::getHandleNote, trimmedNote)
                .set(Report::getHandledAt, LocalDateTime.now()));

        // 留痕 target = 被举报对象（见类注释）；举报行 id 进 detail
        long logId = operationLogger.log(adminId, ACTION_REPORT_DISPOSE,
                report.getTargetType(), report.getTargetId(), trimmedNote,
                "report:" + reportId + ":status:" + previous + "->" + outcome, ip);
        log.info("举报处置留痕：adminId={} reportId={} {}->{}，note={}",
                adminId, reportId, previous, outcome, trimmedNote);
        return logId;
    }

    /** 批量取举报人昵称。 */
    private Map<Long, String> loadNicknames(List<Report> rows) {
        List<Long> ids = rows.stream().map(Report::getUserId).distinct().toList();
        Map<Long, String> result = new HashMap<>();
        if (ids.isEmpty()) {
            return result;
        }
        for (User user : userMapper.selectBatchIds(ids)) {
            result.put(user.getId(), user.getNickname());
        }
        return result;
    }

    /**
     * 批量解析被举报对象摘要。{@code selectBatchIds} 带 @TableLogic（软删对象查不到），
     * 查不到的按"(已删除)"处理 —— 处置界面上能看出"对象已经没了"，而不是一行空文案。
     */
    private Map<String, String> loadTargetSummaries(List<Report> rows) {
        List<Long> postIds = rows.stream().filter(r -> r.getTargetType() == 1)
                .map(Report::getTargetId).distinct().toList();
        List<Long> commentIds = rows.stream().filter(r -> r.getTargetType() == 2)
                .map(Report::getTargetId).distinct().toList();
        List<Long> userIds = rows.stream().filter(r -> r.getTargetType() == 3)
                .map(Report::getTargetId).distinct().toList();

        // 键是 "targetType:targetId" 复合串（举报可指向三类对象，id 可能互相撞）
        Map<String, String> summaries = new HashMap<>();
        if (!postIds.isEmpty()) {
            for (Post post : postMapper.selectBatchIds(postIds)) {
                summaries.put("1:" + post.getId(), truncate(post.getTitle()));
            }
        }
        if (!commentIds.isEmpty()) {
            for (Comment comment : commentMapper.selectBatchIds(commentIds)) {
                summaries.put("2:" + comment.getId(), truncate(comment.getContent()));
            }
        }
        if (!userIds.isEmpty()) {
            for (User user : userMapper.selectBatchIds(userIds)) {
                summaries.put("3:" + user.getId(), user.getUsername());
            }
        }
        return summaries;
    }

    private static String truncate(String text) {
        if (text == null) {
            return "(无内容)";
        }
        return text.length() <= SUMMARY_MAX_LENGTH ? text : text.substring(0, SUMMARY_MAX_LENGTH) + "…";
    }
}
