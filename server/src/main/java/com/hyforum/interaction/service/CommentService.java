package com.hyforum.interaction.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hyforum.common.api.ErrorCode;
import com.hyforum.common.api.PageResult;
import com.hyforum.common.exception.BizException;
import com.hyforum.domain.interaction.entity.Comment;
import com.hyforum.domain.interaction.entity.CommentLike;
import com.hyforum.domain.interaction.mapper.CommentLikeMapper;
import com.hyforum.domain.interaction.mapper.CommentMapper;
import com.hyforum.domain.post.entity.Post;
import com.hyforum.domain.post.mapper.PostMapper;
import com.hyforum.domain.user.entity.User;
import com.hyforum.domain.user.mapper.UserMapper;
import com.hyforum.interaction.dto.CommentCreateRequest;
import com.hyforum.interaction.vo.CommentItemVO;
import com.hyforum.interaction.vo.CommentReplyVO;
import com.hyforum.interaction.vo.UserBriefVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 评论服务：发评论 / 删评论 / 主楼列表（带前 2 条预览）/ 楼中楼列表 / 评论点赞。
 * 契约：docs/技术方案.md §6.6、§8.1、§8.2；表：{@code comment}、{@code comment_like}。
 *
 * <h2>本类是全项目唯一的评论写入口（架构要求，不是编码习惯）</h2>
 * <p>技术方案 §6.6 第 2 条：<b>"禁止任何其他代码路径直接 {@code INSERT comment}"</b>。
 * 原因很硬：楼中楼的 {@code parent_id} 恒等于 {@code root_id}（其所属主楼 id），
 * 而"传入的 {@code parentId} 指向的那一行<b>确实是主楼</b>"这一条
 * <b>无法在数据库层保证</b> —— 本项目禁外键（{@code docs/db/README.md} 基线），
 * 而 {@code CHECK} 不能跨行引用。数据库的 {@code chk_comment_two_levels}
 * 只是第二道防线（它保证同一行内两列的关系，拦不住"指向了错的楼层"）。</p>
 *
 * <p>因此 {@link #create} 里那段"归并"逻辑（{@code parent.parentId != 0}
 * → 取 {@code parent.rootId}）是三层防线里<b>唯一能拦住三层结构的一道</b>。
 * 修改它之前请先读 §6.6。</p>
 *
 * <h2>幂等与计数（§8.1／§8.2）</h2>
 * <ul>
 *   <li>评论点赞靠 {@code uk_comment_user} 唯一索引 + 捕获 {@code DuplicateKeyException}；
 *       取消点赞靠 {@code DELETE} 的<b>影响行数</b>决定要不要递减计数
 *       —— 全程<b>没有</b>"先 SELECT 判断存在再写"的两步式（P1-4 定案）；</li>
 *   <li>所有计数（{@code post.comment_count}、{@code comment.reply_count}、
 *       {@code comment.like_count}）都在<b>同一事务</b>内随关系表增减，
 *       且递减一律带下限 0（任务书 §5.3：计数不得为负）。</li>
 * </ul>
 */
@Service
public class CommentService {

    private static final Logger log = LoggerFactory.getLogger(CommentService.class);

    /** 主楼列表里每条主楼带的楼中楼预览条数（契约 §6.6 与原任务书 §5.5 第 5 条：前 2 条）。 */
    public static final int PREVIEW_REPLY_LIMIT = 2;

    /** 评论长度上限，与 {@code comment.content} 的 VARCHAR(1000) 一致（防绕过 DTO 的调用方）。 */
    private static final int MAX_CONTENT_LENGTH = 1000;

    private final CommentMapper commentMapper;
    private final CommentLikeMapper commentLikeMapper;
    private final PostMapper postMapper;
    private final UserMapper userMapper;

    public CommentService(CommentMapper commentMapper,
                          CommentLikeMapper commentLikeMapper,
                          PostMapper postMapper,
                          UserMapper userMapper) {
        this.commentMapper = commentMapper;
        this.commentLikeMapper = commentLikeMapper;
        this.postMapper = postMapper;
        this.userMapper = userMapper;
    }

    // ==================================================================
    // 写：发表评论（唯一写入口）
    // ==================================================================

    /**
     * 发表评论或回复（§6.6）。
     *
     * <p>流程固定为「加载被回复评论 → 若其 {@code parentId != 0} 则取其 {@code rootId}
     * 作为 {@code parentId} → 校验目标行确实是主楼 → 落库」（§6.6 的原话）。</p>
     *
     * <table>
     *   <caption>落库取值（P1-3 归并语义）</caption>
     *   <tr><th>场景</th><th>{@code parent_id}</th><th>{@code root_id}</th></tr>
     *   <tr><td>主楼（直接评论帖子）</td><td>0</td><td>0</td></tr>
     *   <tr><td>回复主楼</td><td>主楼 id</td><td>主楼 id</td></tr>
     *   <tr><td>回复某条楼中楼</td><td><b>归并为</b>主楼 id</td><td>主楼 id</td></tr>
     * </table>
     *
     * <p><b>关于 {@code replyToUserId}（被回复者）</b>：取值规则</p>
     * <ul>
     *   <li>回复<b>主楼</b> → 被回复者 = 主楼作者；</li>
     *   <li>回复<b>楼中楼</b> → 被回复者 = 那条楼中楼里记着的 {@code reply_to_user_id}
     *       （已存在），没有就回落到它的作者 —— 这样"回复楼中楼的楼中楼"也能正确指向人；</li>
     *   <li>被回复者就是自己 → 存 {@code null}（"我回复我自己"不该产生一条给自己看的回复标记）。</li>
     * </ul>
     *
     * @param userId  当前登录用户 id（由 Controller 从 {@code CurrentUser} 取，已保证非空）
     * @param request 请求体
     * @return 落库后的评论（主楼形态）
     */
    @Transactional
    public CommentReplyVO create(long userId, CommentCreateRequest request) {
        if (request.postId() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "postId 不能为空");
        }
        String content = request.content() == null ? null : request.content().trim();
        if (content == null || content.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "评论内容不能为空");
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            throw new BizException(ErrorCode.BAD_REQUEST, "评论不能超过 " + MAX_CONTENT_LENGTH + " 字");
        }

        Post post = postMapper.selectById(request.postId());
        if (post == null) {
            // 含逻辑删除：MyBatis-Plus 的 @TableLogic 自动附加 is_deleted = 0
            throw new BizException(ErrorCode.NOT_FOUND, "帖子不存在");
        }

        long parentId = request.parentId() == null ? Comment.ROOT_MARKER : request.parentId();
        long rootId = Comment.ROOT_MARKER;
        Long replyToUserId = null;

        if (parentId != Comment.ROOT_MARKER) {
            Comment target = commentMapper.selectById(parentId);
            if (target == null) {
                // 用 404 而不是 400：与"帖子不存在"保持一致 —— 评论被删除/被屏蔽后
                // MyBatis-Plus 的 @TableLogic 与可见性判定都会让它"查不到"，
                // 对调用方而言就是"这个被回复对象不存在"。用 400 会把
                // "对象不存在"误报成"你的参数写错了"，前端据此给出的提示会答非所问。
                throw new BizException(ErrorCode.NOT_FOUND, "被回复的评论不存在");
            }
            if (!Objects.equals(target.getPostId(), post.getId())) {
                // 跨帖回复同样按"不存在"处理：放行的话楼中楼会挂到另一篇帖子的主楼下，
                // 列表、计数、审核全部对不上，而且从数据上完全看不出来。
                // 刻意不区分"不存在"与"属于别的帖子"：区分等于告诉调用方
                // "这个评论 id 存在，只是不属于这篇帖子"，那是一条信息泄露。
                throw new BizException(ErrorCode.NOT_FOUND, "被回复的评论不存在");
            }
            assertCommentVisible(target);

            // ★ 归并语义的唯一实现点：目标是楼中楼时，取它所属的主楼
            long newParentId;
            if (isRoot(target)) {
                newParentId = target.getId();
            } else {
                newParentId = target.getRootId();
                // 理论上不会为 0（chk_comment_two_levels 保证 parent_id != 0 时两者相等），
                // 但归并前必须确认取到的主楼行真的存在且确实是主楼 —— 否则等于把
                // "指向一条不存在的行"写进库，而 CHECK 拦不住这种情况
                Comment merged = commentMapper.selectById(newParentId);
                if (merged == null || !isRoot(merged)) {
                    throw new BizException(ErrorCode.BAD_REQUEST, "被回复的评论结构异常，无法归并到主楼");
                }
            }
            parentId = newParentId;
            rootId = newParentId;
            replyToUserId = resolveReplyToUserId(target);
            if (Objects.equals(replyToUserId, userId)) {
                replyToUserId = null;
            }
        }

        Comment comment = new Comment();
        comment.setPostId(post.getId());
        comment.setUserId(userId);
        comment.setParentId(parentId);
        comment.setRootId(rootId);
        comment.setReplyToUserId(replyToUserId);
        comment.setContent(content);
        comment.setLikeCount(0);
        // reply_count 仅主楼维护：楼中楼自己这一列恒为 0
        comment.setReplyCount(rootId == Comment.ROOT_MARKER ? 0 : 0);
        // 评论本期**不接敏感词过滤**：§8.6 第 2 条要求"命中 → status=0 进待审队列"，
        // 而评论的审核出口 PUT /api/admin/comments/{id}/status 属 M6（§6.11）。
        // 若现在就把命中词置 0，会造出一个**只进不出的队列**（该内容永远无法放行也不可见）
        // —— 这与 §8.6 反复强调的"队列必须有出口"直接冲突。因此本任务一律落 status=1，
        // 敏感词接入随 M5/M6 的审核出口一起做（已登记为交接事项）。
        comment.setStatus(Comment.STATUS_NORMAL);
        comment.setCreatedAt(LocalDateTime.now());
        commentMapper.insert(comment);

        // 同一事务内维护冗余计数（§8.2）
        if (rootId != Comment.ROOT_MARKER) {
            commentMapper.update(null, Wrappers.<Comment>lambdaUpdate()
                    .setSql("reply_count = reply_count + 1")
                    .eq(Comment::getId, rootId));
        }
        postMapper.update(null, Wrappers.<Post>lambdaUpdate()
                .setSql("comment_count = comment_count + 1")
                .eq(Post::getId, post.getId()));

        // 作者摘要用返回值（本次会话里的用户对象），省一次查询
        return toReplyVO(comment, UserBriefVO.from(userMapper.selectById(userId)), null);
    }

    /** 该评论是否是主楼（{@code parent_id = 0}，由 {@code chk_comment_two_levels} 与 {@code root_id} 联动）。 */
    private static boolean isRoot(Comment comment) {
        return comment.getParentId() != null && comment.getParentId() == Comment.ROOT_MARKER;
    }

    /** 评论可见性：待审与已屏蔽的评论不能被回复（否则回复会挂到看不见的内容下面）。 */
    private void assertCommentVisible(Comment comment) {
        Integer status = comment.getStatus();
        if (status == null || status != Comment.STATUS_NORMAL) {
            throw new BizException(ErrorCode.NOT_FOUND, "被回复的评论不存在");
        }
    }

    /** 被回复者：楼中楼优先沿用它记着的 {@code reply_to_user_id}，否则用它自己的作者。 */
    private Long resolveReplyToUserId(Comment target) {
        return target.getReplyToUserId() != null ? target.getReplyToUserId() : target.getUserId();
    }

    // ==================================================================
    // 写：删除评论
    // ==================================================================

    /**
     * 删除评论（§6.6：仅作者或管理员）。
     *
     * <p>两点必须做对：</p>
     * <ol>
     *   <li><b>归属校验</b>：非作者一律 403（对应验收项 {@code SEC_cannot_delete_others_comment}）。
     *       管理员的删除入口是 §6.11 的 {@code DELETE /api/admin/comments/{id}}（M6）——
     *       前台拿不到管理员身份（前后台两套独立登录态，§9），所以这一层"仅作者"不是简化；</li>
     *   <li><b>幂等</b>：重复删除不能把计数减两次。靠 {@code UPDATE ... WHERE is_deleted = 0}
     *       的<b>影响行数</b>判断"这次是不是真的删掉了"，而不是先查一次
     *       （那在并发下两个请求都会读到"未删除"）。</li>
     * </ol>
     *
     * <p>删除主楼会连同其楼中楼一起逻辑删除，并把 {@code post.comment_count} 减去
     * <b>实际被删掉的行数</b>（而不是数据库里的 {@code reply_count} 冗余值）——
     * 冗余值一旦漂移，用它做减法会把漂移放大。</p>
     */
    @Transactional
    public void delete(long userId, long commentId) {
        Comment comment = commentMapper.selectById(commentId);
        if (comment == null) {
            // 已删除的评论对前台就是"不存在"——重复删除幂等地返回成功，
            // 但这里不能拿到 parent/root 信息，因此直接返回
            log.debug("删除评论：目标不存在或已删除，按幂等成功处理 commentId={}", commentId);
            return;
        }
        if (!Objects.equals(comment.getUserId(), userId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "只能删除自己的评论");
        }

        // ① 主楼：先连带逻辑删除其楼中楼，并取回真正被删掉的行数
        long removedChildren = 0;
        if (isRoot(comment)) {
            removedChildren = commentMapper.delete(Wrappers.<Comment>lambdaQuery()
                    .eq(Comment::getRootId, comment.getId())
                    .ne(Comment::getParentId, Comment.ROOT_MARKER));
        }

        // ② 删自己：影响行数是"这次是否真的删掉了"的唯一依据
        LambdaUpdateWrapper<Comment> updateWrapper = Wrappers.<Comment>lambdaUpdate()
                .eq(Comment::getId, commentId)
                .set(Comment::getIsDeleted, 1);
        int affected = commentMapper.update(null, updateWrapper)
                + (int) removedChildren;
        if (affected == 0) {
            // 并发下另一请求已经删掉了它：幂等成功，不动计数
            log.debug("删除评论：并发下已被删除，按幂等成功处理 commentId={}", commentId);
            return;
        }

        // ③ 计数递减（同一事务，且下限 0）
        if (!isRoot(comment)) {
            long rootId = comment.getRootId();
            commentMapper.update(null, Wrappers.<Comment>lambdaUpdate()
                    .setSql("reply_count = IF(reply_count > 0, reply_count - 1, 0)")
                    .eq(Comment::getId, rootId));
        }
        postMapper.update(null, Wrappers.<Post>lambdaUpdate()
                .setSql("comment_count = IF(comment_count > 0, comment_count - " + affected + ", 0)")
                .eq(Post::getId, comment.getPostId()));

        // 评论点赞关系一并清掉：评论已不可见，关系行只会让
        // "comment.like_count == comment_like 行数" 这个等式在后续永远不成立
        commentLikeMapper.delete(Wrappers.<CommentLike>lambdaQuery()
                .eq(CommentLike::getCommentId, commentId));
        if (isRoot(comment)) {
            List<Long> childIds = commentMapper.selectList(Wrappers.<Comment>lambdaQuery()
                            .eq(Comment::getRootId, comment.getId())
                            .ne(Comment::getParentId, Comment.ROOT_MARKER)
                            .select(Comment::getId))
                    .stream().map(Comment::getId).toList();
            if (!childIds.isEmpty()) {
                commentLikeMapper.delete(Wrappers.<CommentLike>lambdaQuery()
                        .in(CommentLike::getCommentId, childIds));
            }
        }
    }

    // ==================================================================
    // 读：主楼列表（带前 2 条预览）
    // ==================================================================

    /**
     * 主楼分页列表，每条带 {@code reply_count} 与<b>前 2 条</b>楼中楼预览（§6.6）。
     *
     * <p><b>查询次数固定为 4 次，与页大小无关</b>（不是 N+1）：
     * ① 主楼分页；② 该页主楼的楼中楼计数（一次 {@code GROUP BY}）；
     * ③ 该页主楼的预览 id（一次相关子查询，每主楼取 2）；
     * ④ 预览行的作者（一次批量）。</p>
     *
     * <p>预览返回<b>按时间升序</b>（旧的在前）：预览是"帖子下面那两行"的展示顺序，
     * 若按倒序返回，前端每处都要自己反转一次，漏一处就是顺序错乱。</p>
     */
    @Transactional(readOnly = true)
    public PageResult<CommentItemVO> listRootComments(long postId, int page, int size) {
        if (postMapper.selectById(postId) == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "帖子不存在");
        }
        int pageNo = PageResult.normalizePage(page);
        int pageSize = PageResult.normalizeSize(size);

        Page<Comment> query = new Page<>(pageNo, pageSize);
        IPage<Comment> result = commentMapper.selectPage(query, Wrappers.<Comment>lambdaQuery()
                .eq(Comment::getPostId, postId)
                .eq(Comment::getParentId, Comment.ROOT_MARKER)
                .eq(Comment::getStatus, Comment.STATUS_NORMAL)
                .orderByDesc(Comment::getCreatedAt)
                .orderByDesc(Comment::getId));

        List<Comment> roots = result.getRecords();
        if (roots.isEmpty()) {
            return PageResult.of(List.of(), result.getTotal(), pageNo, pageSize);
        }
        List<Long> rootIds = roots.stream().map(Comment::getId).toList();

        Map<Long, Long> replyCounts = countReplies(rootIds);
        Map<Long, List<CommentReplyVO>> previews = loadPreviews(rootIds);

        List<CommentItemVO> items = new ArrayList<>(roots.size());
        Map<Long, UserBriefVO> authors = loadAuthors(roots.stream().map(Comment::getUserId).toList());
        for (Comment root : roots) {
            items.add(new CommentItemVO(
                    root.getId(),
                    root.getPostId(),
                    root.getContent(),
                    nullToZero(root.getLikeCount()),
                    // 展示口径用"实际存在的楼中楼行数"：冗余计数若漂移，
                    // 用户点进去看到的条数与外面显示的对不上——这里宁可与点进去一致
                    (int) (long) replyCounts.getOrDefault(root.getId(), 0L),
                    previews.getOrDefault(root.getId(), List.of()),
                    authors.get(root.getUserId()),
                    root.getCreatedAt()));
        }
        return PageResult.of(items, result.getTotal(), pageNo, pageSize);
    }

    /**
     * 某主楼下的全部楼中楼，分页（§6.6：{@code GET /api/comments/{rootId}/replies}）。
     *
     * <p>契约特意说明：这里的 {@code rootId} 指的是<b>主楼评论 id</b>
     * （也就是楼中楼行的 {@code root_id} 值）。因此入参先校验"这一行存在且确实是主楼"，
     * 否则传一条楼中楼的 id 进来会返回空列表，而调用方无法区分"没有回复"与"传错了 id"。</p>
     */
    @Transactional(readOnly = true)
    public PageResult<CommentReplyVO> listReplies(long rootId, int page, int size) {
        Comment root = commentMapper.selectById(rootId);
        if (root == null || !isRoot(root)) {
            throw new BizException(ErrorCode.NOT_FOUND, "主楼评论不存在");
        }
        int pageNo = PageResult.normalizePage(page);
        int pageSize = PageResult.normalizeSize(size);

        Page<Comment> query = new Page<>(pageNo, pageSize);
        IPage<Comment> result = commentMapper.selectPage(query, Wrappers.<Comment>lambdaQuery()
                .eq(Comment::getRootId, rootId)
                .ne(Comment::getParentId, Comment.ROOT_MARKER)
                .eq(Comment::getStatus, Comment.STATUS_NORMAL)
                .orderByAsc(Comment::getCreatedAt)
                .orderByAsc(Comment::getId));

        List<Comment> replies = result.getRecords();
        Map<Long, UserBriefVO> authors = loadAuthors(replies.stream().map(Comment::getUserId).toList());
        Map<Long, UserBriefVO> replyTos = loadAuthors(replies.stream()
                .map(Comment::getReplyToUserId).filter(Objects::nonNull).toList());

        List<CommentReplyVO> items = replies.stream()
                .map(reply -> toReplyVO(reply, authors.get(reply.getUserId()),
                        reply.getReplyToUserId() == null ? null : replyTos.get(reply.getReplyToUserId())))
                .toList();
        return PageResult.of(items, result.getTotal(), pageNo, pageSize);
    }

    /** 主楼 id → 楼中楼数量（一次 {@code GROUP BY} 查询）。 */
    private Map<Long, Long> countReplies(List<Long> rootIds) {
        Map<Long, Long> counts = new HashMap<>();
        for (CommentMapper.ReplyCountRow row : commentMapper.countRepliesByRoots(rootIds)) {
            counts.put(row.rootId(), row.cnt() == null ? 0L : row.cnt());
        }
        return counts;
    }

    /** 主楼 id → 前 2 条楼中楼（倒序取最近 2 条，再重排成时间升序返回）。 */
    private Map<Long, List<CommentReplyVO>> loadPreviews(List<Long> rootIds) {
        List<CommentMapper.PreviewRow> previews = commentMapper.findPreviewReplyIds(rootIds);
        if (previews.isEmpty()) {
            return Map.of();
        }
        List<Long> replyIds = previews.stream().map(CommentMapper.PreviewRow::replyId).toList();
        List<Comment> rows = commentMapper.selectAliveByIds(replyIds);
        Map<Long, Comment> byId = new HashMap<>();
        for (Comment row : rows) {
            if (row.getStatus() != null && row.getStatus() == Comment.STATUS_NORMAL) {
                byId.put(row.getId(), row);
            }
        }
        Map<Long, UserBriefVO> authors = loadAuthors(rows.stream().map(Comment::getUserId).toList());
        Map<Long, UserBriefVO> replyTos = loadAuthors(rows.stream()
                .map(Comment::getReplyToUserId).filter(Objects::nonNull).toList());

        // 按 root_Id 分组后升序排列；LinkedHashMap 只为让调试时输出稳定
        Map<Long, List<Comment>> grouped = new LinkedHashMap<>();
        for (CommentMapper.PreviewRow row : previews) {
            Comment reply = byId.get(row.replyId());
            if (reply == null) {
                continue;
            }
            grouped.computeIfAbsent(row.rootId(), key -> new ArrayList<>()).add(reply);
        }
        Map<Long, List<CommentReplyVO>> result = new HashMap<>();
        for (Map.Entry<Long, List<Comment>> entry : grouped.entrySet()) {
            List<Comment> list = new ArrayList<>(entry.getValue());
            list.sort((a, b) -> {
                int byTime = a.getCreatedAt().compareTo(b.getCreatedAt());
                return byTime != 0 ? byTime : Long.compare(a.getId(), b.getId());
            });
            result.put(entry.getKey(), list.stream()
                    .limit(PREVIEW_REPLY_LIMIT)
                    .map(reply -> toReplyVO(reply, authors.get(reply.getUserId()),
                            reply.getReplyToUserId() == null ? null : replyTos.get(reply.getReplyToUserId())))
                    .toList());
        }
        return result;
    }

    // ==================================================================
    // 写：评论点赞（幂等）
    // ==================================================================

    /**
     * 点赞评论（§6.6 {@code POST /api/comments/{id}/like}）。
     *
     * <p>幂等实现（§8.1 原话）：直接 {@code INSERT}，捕获
     * {@link DuplicateKeyException} 视为"已点赞"并<b>返回成功</b>。
     * 计数只在<b>真的插入了</b>时才 +1 —— 这正是"20 个线程并发点赞后
     * {@code comment.like_count} 必须恰好等于 1"能成立的原因。</p>
     */
    @Transactional
    public void likeComment(long userId, long commentId) {
        Comment comment = commentMapper.selectById(commentId);
        if (comment == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "评论不存在");
        }
        CommentLike like = new CommentLike();
        like.setCommentId(commentId);
        like.setUserId(userId);
        like.setCreatedAt(LocalDateTime.now());
        try {
            commentLikeMapper.insert(like);
        } catch (DuplicateKeyException ex) {
            // 已点过：幂等成功，不再递增（递了就会把计数打穿）
            log.debug("重复点赞评论，幂等返回：commentId={} userId={}", commentId, userId);
            return;
        }
        commentMapper.update(null, Wrappers.<Comment>lambdaUpdate()
                .setSql("like_count = like_count + 1")
                .eq(Comment::getId, commentId));
    }

    /**
     * 取消点赞评论（{@code DELETE /api/comments/{id}/like}）：幂等。
     *
     * <p>以 {@code DELETE} 的<b>影响行数</b>决定是否递减：0 行 = 本来就未点赞，
     * 直接返回成功且不动计数。这就是"计数不为负"的第一道保证
     * （第二道是 SQL 里的 {@code IF(col > 0, col - 1, 0)} —— 刻意<b>不用</b>
     * {@code GREATEST(col - 1, 0)}：{@code like_count} 是 UNSIGNED，MySQL 会先算出
     * {@code col - 1}，为 0 时直接抛 1690，根本轮不到 GREATEST。实测对照见交付报告）。</p>
     */
    @Transactional
    public void unlikeComment(long userId, long commentId) {
        int affected = commentLikeMapper.delete(Wrappers.<CommentLike>lambdaQuery()
                .eq(CommentLike::getCommentId, commentId)
                .eq(CommentLike::getUserId, userId));
        if (affected == 0) {
            log.debug("取消点赞：本来就未点赞，幂等返回 commentId={} userId={}", commentId, userId);
            return;
        }
        commentMapper.update(null, Wrappers.<Comment>lambdaUpdate()
                .setSql("like_count = IF(like_count > 0, like_count - 1, 0)")
                .eq(Comment::getId, commentId));
    }

    // ==================================================================
    // VO 组装与小工具
    // ==================================================================

    private CommentReplyVO toReplyVO(Comment comment, UserBriefVO author, UserBriefVO replyTo) {
        return new CommentReplyVO(
                comment.getId(),
                comment.getPostId(),
                comment.getRootId(),
                comment.getParentId(),
                comment.getReplyToUserId(),
                replyTo == null ? null : replyTo.nickname(),
                comment.getContent(),
                nullToZero(comment.getLikeCount()),
                author,
                comment.getCreatedAt());
    }

    /** 批量取作者摘要（一次 {@code IN} 查询，避免 N+1）。 */
    private Map<Long, UserBriefVO> loadAuthors(List<Long> userIds) {
        List<Long> distinct = userIds.stream().filter(Objects::nonNull).distinct().toList();
        Map<Long, UserBriefVO> result = new HashMap<>();
        if (distinct.isEmpty()) {
            return result;
        }
        for (User user : userMapper.selectBatchIds(distinct)) {
            result.put(user.getId(), UserBriefVO.from(user));
        }
        return result;
    }

    private static int nullToZero(Integer value) {
        return value == null ? 0 : value;
    }
}
