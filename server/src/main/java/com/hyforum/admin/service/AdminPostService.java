package com.hyforum.admin.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hyforum.common.api.ErrorCode;
import com.hyforum.common.api.PageResult;
import com.hyforum.common.exception.BizException;
import com.hyforum.domain.admin.entity.AdminOperationLog;
import com.hyforum.domain.post.entity.Post;
import com.hyforum.domain.post.mapper.PostMapper;
import com.hyforum.domain.user.entity.User;
import com.hyforum.domain.user.mapper.UserMapper;
import com.hyforum.admin.vo.AdminPostVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 帖子审核（M6；docs/技术方案.md §6.11）——审核队列的出口补上"帖子"这一半。
 *
 * <h2>与评论审核（M5）的同一套规矩</h2>
 * <ul>
 *   <li><b>两个方向</b>：放行（1）/ 屏蔽（2），不接受 0 —— 把已处置的内容塞回队列没有业务含义；</li>
 *   <li><b>屏蔽必填理由</b>（合规 C9：处置依据可追溯）；放行不强制；</li>
 *   <li><b>留痕与业务改动同一事务</b>（§6.11 留痕红线）：留痕失败 → 状态改动一并回滚；</li>
 *   <li><b>adminId 来自后台登录态</b>，不接受请求体传入；前台 token 进不来（§9 双 StpLogic）。</li>
 * </ul>
 *
 * <p>为什么不复用 {@code audit} 包的 {@code CommentAuditService}：铁律 3 禁止业务包互相依赖
 * （{@code admin → audit} 会红）。两条审核路径共享的只是"规矩"（上面四条），不是代码 ——
 * 各自实现、各自的 ArchUnit 守着边界，这与 {@code AdminAuthController#clientIp} 复制前台
 * 同一形态的理由。</p>
 */
@Service
public class AdminPostService {

    private static final Logger log = LoggerFactory.getLogger(AdminPostService.class);

    /** 动作编码（schema 表 16 的 action 列注释枚举之一）。 */
    public static final String ACTION_POST_STATUS = "POST_STATUS";

    /** 理由长度上限，与 {@code admin_operation_log.reason} 的 VARCHAR(200) 一致。 */
    private static final int MAX_REASON_LENGTH = 200;

    private final PostMapper postMapper;
    private final UserMapper userMapper;
    private final AdminOperationLogger operationLogger;

    public AdminPostService(PostMapper postMapper,
                            UserMapper userMapper,
                            AdminOperationLogger operationLogger) {
        this.postMapper = postMapper;
        this.userMapper = userMapper;
        this.operationLogger = operationLogger;
    }

    /**
     * 后台帖子列表（§6.11 {@code GET /api/admin/posts}）：可按 {@code status} 筛选。
     *
     * <p>与评论队列同一口径：<b>不传 status 返回全部状态</b> —— 后台的职责就是看见
     * 前台看不见的东西；"只看待审队列"是前端传 {@code status=0} 的事。
     * 时间升序（先处理早的），与评论队列一致。</p>
     */
    @Transactional(readOnly = true)
    public PageResult<AdminPostVO> listPosts(Integer status, int page, int size) {
        int pageNo = PageResult.normalizePage(page);
        int pageSize = PageResult.normalizeSize(size);

        IPage<Post> result = postMapper.selectPage(new Page<>(pageNo, pageSize),
                Wrappers.<Post>lambdaQuery()
                        .eq(status != null, Post::getStatus, status)
                        .orderByAsc(Post::getCreatedAt)
                        .orderByAsc(Post::getId));

        List<Post> rows = result.getRecords();
        Map<Long, String> authors = loadAuthors(rows);
        List<AdminPostVO> items = rows.stream()
                .map(post -> new AdminPostVO(
                        post.getId(),
                        post.getTitle(),
                        post.getUserId(),
                        authors.getOrDefault(post.getUserId(), ""),
                        post.getStatus() == null ? 0 : post.getStatus(),
                        post.getCreatedAt()))
                .toList();
        return PageResult.of(items, result.getTotal(), pageNo, pageSize);
    }

    /**
     * 帖子审核（§6.11 {@code PUT /api/admin/posts/{id}/status}）：放行 or 屏蔽。
     *
     * <p>顺序与评论审核一致：校验 → 改状态 → 留痕，全在同一事务。
     * 屏蔽（status→2）<b>不动任何计数</b>：帖子被屏蔽后前台不可见，
     * 但 {@code user.post_count} 与 {@code comment_count} 的现有口径都不含 status 维度
     * （分别只按 is_deleted 过滤），本方法不得越权改口径 —— 那是另一个需要裁定的变更。</p>
     *
     * @param adminId 操作管理员 id
     * @param postId  帖子 id
     * @param status  目标状态：1 放行 / 2 屏蔽
     * @param reason  处置理由；<b>屏蔽时必填</b>
     * @param ip      操作来源 IP（可为 null）
     * @return 留痕行 id
     */
    @Transactional
    public long reviewPost(long adminId, long postId, int status, String reason, String ip) {
        if (status != Post.STATUS_NORMAL && status != Post.STATUS_BLOCKED) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "status 只支持 1（放行）或 2（屏蔽），收到：" + status
                            + "。不接受 0（待审核）——那会把已处置的内容重新塞回队列");
        }
        Post post = postMapper.selectById(postId);
        if (post == null) {
            // 已逻辑删除的帖子也算"不存在"：审核一个用户已经删掉的东西没有意义
            throw new BizException(ErrorCode.NOT_FOUND, "帖子不存在");
        }
        String normalizedReason = blankToNull(reason);
        if (status == Post.STATUS_BLOCKED && normalizedReason == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "屏蔽帖子必须提供 reason（合规 C9：处置依据可追溯）");
        }
        if (normalizedReason != null && normalizedReason.length() > MAX_REASON_LENGTH) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "reason 不能超过 " + MAX_REASON_LENGTH + " 字符");
        }

        int previous = post.getStatus() == null ? -1 : post.getStatus();
        postMapper.update(null, Wrappers.<Post>lambdaUpdate()
                .eq(Post::getId, postId)
                .set(Post::getStatus, status));

        long logId = operationLogger.log(adminId, ACTION_POST_STATUS,
                AdminOperationLog.TARGET_POST, postId, normalizedReason,
                "status:" + previous + "->" + status, ip);
        log.info("帖子审核留痕：adminId={} postId={} status:{}->{}，reason={}",
                adminId, postId, previous, status, normalizedReason);
        return logId;
    }

    /** 批量取作者昵称（一次 IN 查询，避免 N+1）。 */
    private Map<Long, String> loadAuthors(List<Post> rows) {
        List<Long> ids = rows.stream().map(Post::getUserId).distinct().toList();
        Map<Long, String> result = new HashMap<>();
        if (ids.isEmpty()) {
            return result;
        }
        for (User user : userMapper.selectBatchIds(ids)) {
            result.put(user.getId(), user.getNickname());
        }
        return result;
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
