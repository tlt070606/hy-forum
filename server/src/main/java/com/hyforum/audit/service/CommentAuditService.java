package com.hyforum.audit.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hyforum.common.api.ErrorCode;
import com.hyforum.common.api.PageResult;
import com.hyforum.common.exception.BizException;
import com.hyforum.domain.admin.entity.AdminOperationLog;
import com.hyforum.domain.admin.mapper.AdminOperationLogMapper;
import com.hyforum.domain.interaction.entity.Comment;
import com.hyforum.domain.interaction.mapper.CommentMapper;
import com.hyforum.domain.post.entity.Post;
import com.hyforum.domain.post.mapper.PostMapper;
import com.hyforum.domain.user.entity.User;
import com.hyforum.domain.user.mapper.UserMapper;
import com.hyforum.audit.vo.AdminCommentVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 审核队列的<b>出口</b>与后台评论查询（docs/技术方案.md §6.11、§8.6；任务书 §6 第 7 条）。
 *
 * <h2>这个类存在的理由：队列必须有出口（本项目已经吃过一次）</h2>
 * <p>§8.6 第 2 条要求"命中敏感词的评论置 {@code status=0}（待审核）、不对普通用户展示、
 * 进入后台审核队列"，而 {@code PUT /api/admin/comments/{id}/status} 是那个队列的
 * <b>唯一处理出口</b> —— 没有它，待审内容既无法放行也无法屏蔽，队列<b>只进不出</b>。
 * 这正是 P1-2／CR-006 已经记录过一次的缺陷类型（图片审核队列当时也是只进不出），
 * 所以本任务把出口做出来而不是推给 M6。</p>
 *
 * <h2>两个方向都要有（任务书 §5 第 4 条）</h2>
 * <ul>
 *   <li><b>放行</b>：{@code status → 1}（正常可见）；</li>
 *   <li><b>屏蔽</b>：{@code status → 2}（前台不可见）。</li>
 * </ul>
 * <p>任一方向<b>都要留痕</b>（{@code admin_operation_log}，合规 C9）。
 * 屏蔽是"处置性动作"，因此<b>理由必填</b>；放行不强制（它是恢复可见，不是处置）。</p>
 *
 * <h2>留痕与业务改动必须同一事务（§6.11 留痕红线）</h2>
 * <p>本类所有写方法都是 {@code @Transactional}：留痕失败则业务改动一起回滚，
 * <b>不允许出现"已处置但无记录"</b>。这一条不是形式 —— 本项目把它写进契约就是因为
 * "先处置、后补日志"在异常路径下必然漏记，而漏记是合规事故。</p>
 *
 * <h2>安全边界：本类不信任调用方传来的"我是管理员"</h2>
 * <p>{@code adminId} 一律由 Controller 从后台登录态取（{@code StpAdminUtil.currentAdminId()}），
 * 不接受请求体传入。前台 token 也进不来（前后台两套独立 StpLogic，§9 后台隔离）。</p>
 */
@Service
public class CommentAuditService {

    private static final Logger log = LoggerFactory.getLogger(CommentAuditService.class);

    /** 动作编码（schema 表 16 的 action 列注释里登记的枚举之一）。 */
    public static final String ACTION_COMMENT_STATUS = "COMMENT_STATUS";

    /** 理由长度上限，与 {@code admin_operation_log.reason} 的 VARCHAR(200) 一致。 */
    private static final int MAX_REASON_LENGTH = 200;

    private final CommentMapper commentMapper;
    private final PostMapper postMapper;
    private final UserMapper userMapper;
    private final AdminOperationLogMapper operationLogMapper;

    public CommentAuditService(CommentMapper commentMapper,
                               PostMapper postMapper,
                               UserMapper userMapper,
                               AdminOperationLogMapper operationLogMapper) {
        this.commentMapper = commentMapper;
        this.postMapper = postMapper;
        this.userMapper = userMapper;
        this.operationLogMapper = operationLogMapper;
    }

    /**
     * 评论审核处理（§6.11 {@code PUT /api/admin/comments/{id}/status}）：放行 or 屏蔽。
     *
     * <p>顺序刻意是「<b>先校验 → 再改状态 → 再留痕</b>」，且全在同一事务里：</p>
     * <ol>
     *   <li>校验目标评论存在；</li>
     *   <li>校验目标状态合法（只接受 1 放行 / 2 屏蔽 —— 不接受把评论改回 0"待审"，
     *       那等于把一个已经处置过的东西重新塞回队列，没有任何业务含义）；</li>
     *   <li>屏蔽时校验理由必填（合规要求可追溯处置依据）；</li>
     *   <li>改 {@code comment.status}；</li>
     *   <li>写留痕行。留痕失败 → 整个事务回滚，状态改动一并撤销。</li>
     * </ol>
     *
     * @param adminId  操作管理员 id（由 Controller 从后台登录态取，不来自请求体）
     * @param commentId 评论 id
     * @param status   目标状态：1 放行 / 2 屏蔽
     * @param reason   处置理由；<b>屏蔽时必填</b>
     * @param ip       操作来源 IP（可为 null）
     * @return 实际写入的留痕行 id
     */
    @Transactional
    public long reviewComment(long adminId, long commentId, int status, String reason, String ip) {
        if (status != Comment.STATUS_NORMAL && status != Comment.STATUS_BLOCKED) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "status 只支持 1（放行）或 2（屏蔽），收到：" + status
                            + "。不接受 0（待审）——那会把已处置的内容重新塞回队列");
        }
        Comment comment = commentMapper.selectById(commentId);
        if (comment == null) {
            // 已逻辑删除的评论也算"不存在"：审核一个用户已经删掉的东西没有意义
            throw new BizException(ErrorCode.NOT_FOUND, "评论不存在");
        }
        String normalizedReason = blankToNull(reason);
        if (status == Comment.STATUS_BLOCKED && normalizedReason == null) {
            // 屏蔽是处置性动作：没有理由就无法追溯依据（C9）
            throw new BizException(ErrorCode.BAD_REQUEST, "屏蔽评论必须提供 reason（合规 C9：处置依据可追溯）");
        }
        if (normalizedReason != null && normalizedReason.length() > MAX_REASON_LENGTH) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "reason 不能超过 " + MAX_REASON_LENGTH + " 字符");
        }

        int previous = comment.getStatus() == null ? -1 : comment.getStatus();
        commentMapper.update(null, Wrappers.<Comment>lambdaUpdate()
                .eq(Comment::getId, commentId)
                .set(Comment::getStatus, status));

        return writeLog(adminId, commentId, previous, status, normalizedReason, ip);
    }

    /**
     * 写一条留痕。
     *
     * <p>抽成私有方法是为了让"留痕字段填全"只有一处实现 ——
     * 两处各拼一遍必然有一处漏 {@code detail} 或 {@code reason}，
     * 而漏了 {@code reason} 的后果是合规审计时无法追溯。</p>
     */
    private long writeLog(long adminId, long commentId, int previous, int current,
                          String reason, String ip) {
        AdminOperationLog entry = new AdminOperationLog();
        entry.setAdminId(adminId);
        entry.setAction(ACTION_COMMENT_STATUS);
        entry.setTargetType(AdminOperationLog.TARGET_COMMENT);
        entry.setTargetId(commentId);
        entry.setReason(reason);
        // detail 记成 status:0->1 这种可读的形状（schema 列注释给的例子）
        entry.setDetail("status:" + previous + "->" + current);
        entry.setIp(ip);
        entry.setCreatedAt(LocalDateTime.now());
        operationLogMapper.insert(entry);
        log.info("评论审核留痕：adminId={} commentId={} {}，reason={}",
                adminId, commentId, entry.getDetail(), reason);
        return entry.getId();
    }

    /**
     * 后台评论列表（§6.11 {@code GET /api/admin/comments}）：可按 {@code status} 筛选。
     *
     * <p><b>不传 status 时返回全部状态</b>（含 0 待审、2 已屏蔽）：
     * 后台的职责就是看见前台看不见的东西；而"只看待审队列"是前端传 {@code status=0} 的事。
     * 若这里默认只给正常评论，审核队列就永远看不到待审内容 —— 又是一个"只进不出"。</p>
     *
     * <p>刻意<b>不</b>过滤 {@code is_deleted}：MyBatis-Plus 的 {@code @TableLogic} 已经自动附加
     * {@code is_deleted = 0}。后台也不该看到用户已删的评论（那已经是"不存在"了）。</p>
     */
    @Transactional(readOnly = true)
    public PageResult<AdminCommentVO> listComments(Integer status, int page, int size) {
        int pageNo = PageResult.normalizePage(page);
        int pageSize = PageResult.normalizeSize(size);

        var query = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<Comment>(pageNo, pageSize);
        var result = commentMapper.selectPage(query, Wrappers.<Comment>lambdaQuery()
                .eq(status != null, Comment::getStatus, status)
                .orderByAsc(Comment::getCreatedAt)
                .orderByAsc(Comment::getId));

        List<Comment> rows = result.getRecords();
        Map<Long, String> postTitles = loadPostTitles(rows);
        Map<Long, String> authors = loadAuthors(rows);
        List<AdminCommentVO> items = new ArrayList<>(rows.size());
        for (Comment row : rows) {
            items.add(new AdminCommentVO(
                    row.getId(),
                    row.getPostId(),
                    postTitles.get(row.getPostId()),
                    row.getUserId(),
                    authors.get(row.getUserId()),
                    row.getParentId(),
                    row.getContent(),
                    row.getStatus(),
                    row.getCreatedAt()));
        }
        return PageResult.of(items, result.getTotal(), pageNo, pageSize);
    }

    private Map<Long, String> loadPostTitles(List<Comment> rows) {
        List<Long> ids = rows.stream().map(Comment::getPostId).distinct().toList();
        Map<Long, String> titles = new HashMap<>();
        if (ids.isEmpty()) {
            return titles;
        }
        for (Post post : postMapper.selectList(Wrappers.<Post>lambdaQuery()
                .in(Post::getId, ids).select(Post::getId, Post::getTitle))) {
            titles.put(post.getId(), post.getTitle());
        }
        return titles;
    }

    private Map<Long, String> loadAuthors(List<Comment> rows) {
        List<Long> ids = rows.stream().map(Comment::getUserId).distinct().toList();
        Map<Long, String> authors = new HashMap<>();
        if (ids.isEmpty()) {
            return authors;
        }
        for (User user : userMapper.selectBatchIds(ids)) {
            authors.put(user.getId(), user.getNickname());
        }
        return authors;
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
