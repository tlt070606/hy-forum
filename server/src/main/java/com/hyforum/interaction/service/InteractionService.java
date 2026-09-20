package com.hyforum.interaction.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hyforum.common.api.ErrorCode;
import com.hyforum.common.api.PageResult;
import com.hyforum.common.exception.BizException;
import com.hyforum.common.notify.NotificationPublisher;
import com.hyforum.common.notify.NotificationType;
import com.hyforum.domain.interaction.entity.Comment;
import com.hyforum.domain.interaction.entity.CommentLike;
import com.hyforum.domain.interaction.entity.PostCollect;
import com.hyforum.domain.interaction.entity.PostLike;
import com.hyforum.domain.interaction.mapper.CommentLikeMapper;
import com.hyforum.domain.interaction.mapper.CommentMapper;
import com.hyforum.domain.interaction.mapper.PostCollectMapper;
import com.hyforum.domain.interaction.mapper.PostLikeMapper;
import com.hyforum.domain.notify.entity.Notification;
import com.hyforum.domain.post.entity.Post;
import com.hyforum.domain.post.mapper.PostMapper;
import com.hyforum.interaction.vo.CollectionItemVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 帖子互动服务：点赞 / 收藏 / 取消（§6.5、§8.1、§8.2）与"我的收藏列表"（§6.3）。
 *
 * <h2>幂等的实现口径（P1-4 定案，任务书 §5.2）</h2>
 * <ul>
 *   <li><b>加</b>：直接 {@code INSERT}，捕获 {@link DuplicateKeyException} → 视为"已点赞/已收藏"，
 *       返回成功；计数只在真的插入成功时才 +1；</li>
 *   <li><b>减</b>：{@code DELETE} 的<b>影响行数</b>决定要不要递减；0 行 → 幂等成功、不动计数；</li>
 *   <li><b>禁止</b>"先 {@code SELECT} 判断存在再 {@code INSERT/UPDATE}"的两步式 ——
 *       并发下 20 个线程都会读到"不存在"，于是 20 次插入、19 次撞唯一键之外还会多减计数。</li>
 * </ul>
 *
 * <h2>计数不变量（本类的核心断言）</h2>
 * <pre>
 *   post.like_count    == COUNT(post_like   WHERE post_id = ?)
 *   post.collect_count == COUNT(post_collect WHERE post_id = ?)
 * </pre>
 * <p>两者都在<b>同一事务</b>内随关系表增减，递减一律带下限 0。
 * 任务书 §5.3 把"定时校准任务"从本任务裁掉了，因此判据就是上面这两个等式本身
 * （随机增删序列跑完后必须成立，见 {@code M4_counters_consistent_after_random_ops}）。</p>
 */
@Service
public class InteractionService {

    private static final Logger log = LoggerFactory.getLogger(InteractionService.class);

    private final PostLikeMapper postLikeMapper;
    private final PostCollectMapper postCollectMapper;
    private final CommentLikeMapper commentLikeMapper;
    private final CommentMapper commentMapper;
    private final PostMapper postMapper;

    /**
     * 通知发布（M5 触发点）。
     *
     * <p>依赖的是 {@code common.notify} 里的<b>接口</b>，不是 {@code notify} 包的实现 ——
     * 否则会构成业务包互相依赖（铁律 3，ArchUnit 按包判）。同 {@code PostService}
     * 依赖 {@code common.audit.SensitiveTextChecker} 而不是 {@code audit} 包的形状。</p>
     */
    private final NotificationPublisher notificationPublisher;

    public InteractionService(PostLikeMapper postLikeMapper,
                              PostCollectMapper postCollectMapper,
                              CommentLikeMapper commentLikeMapper,
                              CommentMapper commentMapper,
                              PostMapper postMapper,
                              NotificationPublisher notificationPublisher) {
        this.postLikeMapper = postLikeMapper;
        this.postCollectMapper = postCollectMapper;
        this.commentLikeMapper = commentLikeMapper;
        this.commentMapper = commentMapper;
        this.postMapper = postMapper;
        this.notificationPublisher = notificationPublisher;
    }

    // ==================================================================
    // 点赞
    // ==================================================================

    /**
     * 点赞帖子（§6.5 {@code POST /api/posts/{id}/like}）：幂等。
     *
     * @param userId 当前登录用户 id
     * @param postId 帖子 id
     */
    @Transactional
    public void likePost(long userId, long postId) {
        Post post = requireVisiblePost(postId);
        PostLike like = new PostLike();
        like.setPostId(postId);
        like.setUserId(userId);
        like.setCreatedAt(LocalDateTime.now());
        try {
            postLikeMapper.insert(like);
        } catch (DuplicateKeyException ex) {
            // 唯一索引 uk_post_user 拦下了重复：这就是幂等语义，直接返回成功
            log.debug("重复点赞帖子，幂等返回：postId={} userId={}", postId, userId);
            return;
        }
        postMapper.update(null, Wrappers.<Post>lambdaUpdate()
                .setSql("like_count = like_count + 1")
                .eq(Post::getId, postId));

        // ★ M5 触发点：点赞成功 → 通知帖子作者（同一事务内，任务书 §5 第 1 条）。
        //   放在"计数已递增"之后：只有真正落了一条点赞关系才通知。
        //   三点说明：
        //   ① 接收人是**帖子作者**，取自已读出的实体（无需再查一次）；
        //   ② "自己点赞自己的帖子"**不在本方法判断** —— 由 NotificationPublisher 的实现
        //      统一判断（§5 第 2 条）。触发点只如实描述"谁对谁做了什么"；
        //   ③ targetType=1（帖子）+ targetId，前端据此拼跳转链接；content 留空
        //      （文案是展示层的事，后端硬编码"赞了你"以后改文案要发后端）。
        notificationPublisher.publish(NotificationType.LIKE, post.getUserId(), userId,
                Notification.TARGET_POST, postId, null);
    }

    /**
     * 取消点赞（{@code DELETE /api/posts/{id}/like}）：幂等。
     *
     * <p>刻意<b>不校验帖子是否可见</b>：取消点赞是"清理自己的数据"，
     * 若帖子已被删除或屏蔽就返回 404，用户就永远取消不掉自己那条点赞记录，
     * 而 {@code post_like} 的行数又会被 {@code like_count} 的对账看见 ——
     * 于是留下一个只能靠人工修数据才能清掉的残留。这里以关系行为准。</p>
     */
    @Transactional
    public void unlikePost(long userId, long postId) {
        int affected = postLikeMapper.delete(Wrappers.<PostLike>lambdaQuery()
                .eq(PostLike::getPostId, postId)
                .eq(PostLike::getUserId, userId));
        if (affected == 0) {
            log.debug("取消点赞：本来就未点赞，幂等返回 postId={} userId={}", postId, userId);
            return;
        }
        postMapper.update(null, Wrappers.<Post>lambdaUpdate()
                .setSql("like_count = IF(like_count > 0, like_count - 1, 0)")
                .eq(Post::getId, postId));
    }

    // ==================================================================
    // 收藏
    // ==================================================================

    /** 收藏帖子（{@code POST /api/posts/{id}/collect}）：幂等，同 {@link #likePost}。 */
    @Transactional
    public void collectPost(long userId, long postId) {
        requireVisiblePost(postId);
        PostCollect collect = new PostCollect();
        collect.setPostId(postId);
        collect.setUserId(userId);
        collect.setCreatedAt(LocalDateTime.now());
        try {
            postCollectMapper.insert(collect);
        } catch (DuplicateKeyException ex) {
            log.debug("重复收藏帖子，幂等返回：postId={} userId={}", postId, userId);
            return;
        }
        postMapper.update(null, Wrappers.<Post>lambdaUpdate()
                .setSql("collect_count = collect_count + 1")
                .eq(Post::getId, postId));
    }

    /** 取消收藏（{@code DELETE /api/posts/{id}/collect}）：幂等，同 {@link #unlikePost}。 */
    @Transactional
    public void uncollectPost(long userId, long postId) {
        int affected = postCollectMapper.delete(Wrappers.<PostCollect>lambdaQuery()
                .eq(PostCollect::getPostId, postId)
                .eq(PostCollect::getUserId, userId));
        if (affected == 0) {
            log.debug("取消收藏：本来就未收藏，幂等返回 postId={} userId={}", postId, userId);
            return;
        }
        postMapper.update(null, Wrappers.<Post>lambdaUpdate()
                .setSql("collect_count = IF(collect_count > 0, collect_count - 1, 0)")
                .eq(Post::getId, postId));
    }

    /**
     * 我的收藏列表（§6.3 {@code GET /api/user/collections}）：按收藏时间倒序分页。
     *
     * <p>它与 {@code post.collect_count} 必须一致（任务书 §5.5 第 4 条：
     * 随机 100 次操作后仍成立）。请注意两者的口径差别：</p>
     * <ul>
     *   <li>{@code total} 用<b>关系表</b> {@code post_collect} 的行数 —— 这是"我收藏了几篇"的
     *       权威答案；</li>
     *   <li>即使收藏的那篇帖子已被删除，关系行也<b>不</b>在这里被隐藏掉（帖子本身会被过滤掉，
     *       见下）—— 保留关系行是为了让 {@code collect_count} 的对账等式仍然成立。</li>
     * </ul>
     *
     * <p>已删除/已屏蔽的帖子<b>不返回</b>：收藏列表是前台入口，把不可见内容列出来会泄露
     * "这条内容存在但被处置了"。这类行在列表里被跳过，因此列表长度可能小于
     * {@code collect_count} —— 这正是 M4 那条验收（"收藏列表长度 == collect_count"）
     * 必须<b>在没有删帖的场景下</b>断言的原因，测试里已按此写。</p>
     */
    @Transactional(readOnly = true)
    public PageResult<CollectionItemVO> listMyCollections(long userId, int page, int size) {
        int pageNo = PageResult.normalizePage(page);
        int pageSize = PageResult.normalizeSize(size);
        Page<PostCollect> query = new Page<>(pageNo, pageSize);
        IPage<PostCollect> result = postCollectMapper.selectPage(query, Wrappers.<PostCollect>lambdaQuery()
                .eq(PostCollect::getUserId, userId)
                .orderByDesc(PostCollect::getCreatedAt)
                .orderByDesc(PostCollect::getId));

        List<Long> postIds = result.getRecords().stream().map(PostCollect::getPostId).toList();
        if (postIds.isEmpty()) {
            // ★ 必须提前返回：MyBatis-Plus 的 {@code in(...)} 遇到**空集合**会生成
            //   `... WHERE id IN () AND is_deleted=0`，那是**非法 SQL**（MySQL 语法错误），
            //   表现成"我的收藏为空时接口 500"。这是一类很容易漏的空集合坑：
            //   只在"有数据"的路径上测，永远看不到它。
            return PageResult.of(List.of(), result.getTotal(), pageNo, pageSize);
        }
        List<Post> visible = postMapper.selectBatchIds(postIds).stream()
                // MyBatis-Plus 的 @TableLogic 已过滤逻辑删除；这里再挡掉待审与屏蔽
                .filter(post -> post.getStatus() != null && post.getStatus() == Post.STATUS_NORMAL)
                .toList();

        List<CollectionItemVO> items = visible.stream()
                .map(post -> new CollectionItemVO(post.getId(), post.getBoardId(), post.getTitle(),
                        post.getCoverUrl(), nullToZero(post.getImageCount()),
                        nullToZero(post.getLikeCount()), nullToZero(post.getCommentCount()),
                        nullToZero(post.getCollectCount()), post.getCreatedAt()))
                .toList();
        return PageResult.of(items, result.getTotal(), pageNo, pageSize);
    }

    // ==================================================================
    // 删帖后的对账（M4_delete_post_decrements_counters）
    // ==================================================================

    /**
     * 帖子被删除前，把与本帖绑定的互动关系与冗余计数一起收尾。
     *
     * <h2>⚠️ 调用时机是硬约束：必须在 {@code post.is_deleted} 置 1 <b>之前</b>调用</h2>
     * <p>原因是实测出来的（不是推测）：{@code Post} 实体上有 {@code @TableLogic}，
     * MyBatis-Plus 会给<b>每一条</b> UPDATE 自动附加 {@code AND is_deleted = 0}。
     * 因此帖子一旦被逻辑删除，这里的计数 UPDATE 会<b>影响 0 行</b>、
     * 静默什么都不做 —— 而关系行是物理删除的，照样会删掉。
     * 两者叠加的结果是最坏的一种：<b>关系表空了、计数还留着旧值</b>，
     * 于是 M4 的核心不变量（{@code like_count == COUNT(post_like)}）永久不成立。</p>
     * <p>实测证据（临时用例 {@code M4DiagSetSqlTest}，已删除，只留结论）：</p>
     * <pre>
     *   DIAG D 已逻辑删除的帖子 update 影响行数=0   like_count=0（未被写入）
     *   DIAG A/B/C 未删除时：影响行数=1，多赋值与 IF 表达式均正常生效
     * </pre>
     * <p>所以正确顺序是「先 {@code onPostDeleted} → 再逻辑删除」。
     * 本方法因此在实现上<b>先做关系表清理与计数递减，再返回</b>，
     * 调用方（将来接进 M3 删帖路径时）必须把它放在 {@code postMapper.deleteById(...)} 之前。
     * 这一点同时写在交付报告的 CR-M4-2 里。</p>
     *
     * <h2>为什么需要它，以及为什么它要单独存在</h2>
     * <p>M3 的 {@code DELETE /api/posts/{id}} <b>不会</b>递减
     * {@code like_count}／{@code collect_count}／{@code comment_count}
     * —— 那是 {@code post} 包（W3-M3b 写权）的文件，M4 无权改；
     * 而"删除后计数必须递减"是 M4 的验收项（任务书 §7.1 第 15 条）。
     * 因此把收尾逻辑作为<b>一个公开入口</b>放在这里，由 {@code post} 模块删除时调用
     * （一次调用，不引入跨模块依赖 —— 调用方向是 {@code post → interaction}，
     * 而铁律 3 允许业务包依赖 {@code common}/{@code domain}，不允许互相依赖，
     * 所以真正落地时需要 L1 裁决：见交付报告里的 <b>CR-M4-2</b>）。</p>
     *
     * <h2>为什么关系行要删，而评论行只做逻辑删除</h2>
     * <p>因为两类表的语义不同，而 M4 的核心不变量是
     * {@code like_count == COUNT(post_like)}：</p>
     * <ul>
     *   <li>{@code post_like}／{@code post_collect}／{@code comment_like} 是<b>纯关系表</b>
     *       （没有 {@code is_deleted} 列），取消就是物理删除，所以这里也必须物理删除 ——
     *       留一行"已取消"的墓碑会让计数等式永久不成立；</li>
     *   <li>{@code comment} 表本身采用<b>逻辑删除</b>（{@code @TableLogic}，实测
     *       {@code CommentMapper.delete} 生成的是 {@code UPDATE comment SET is_deleted=1}），
     *       因此这里也是逻辑删除。那个等式对评论的正确形式是
     *       {@code comment_count == COUNT(comment WHERE is_deleted = 0)}
     *       —— 计数递减的是<b>可见</b>评论数，与逻辑删除并不矛盾。
     *       刻意<b>不</b>为此改成物理删除：对本项目"评论从不物理删除"这条一致性
     *       更重要，而且软删的评论行还能给 M5/M6 的审核追溯留证据（`is_deleted` 语义
     *       是全表统一的，为一个边缘场景破例会让"为什么这张表不一样"无从回答）。</li>
     * </ul>
     *
     * @param postId 被删除的帖子 id（<b>调用时该帖必须尚未被逻辑删除</b>）
     * @return 本次收尾清掉的关系行数（点赞 + 收藏 + 评论），便于调用方记录
     */
    @Transactional
    public int onPostDeleted(long postId) {
        int removedLikes = postLikeMapper.delete(Wrappers.<PostLike>lambdaQuery()
                .eq(PostLike::getPostId, postId));
        int removedCollects = postCollectMapper.delete(Wrappers.<PostCollect>lambdaQuery()
                .eq(PostCollect::getPostId, postId));

        // 评论：先把它们的点赞关系物理清掉（comment_like 是纯关系表，留着就是孤儿行），
        // 再把评论本身**逻辑**删除（comment 表用 @TableLogic，`DELETE` 会被翻译成
        // `UPDATE comment SET is_deleted = 1` —— 实测 SQL 见交付报告）。
        // 因此下面的 comment_count 递减的是"可见评论数"，与逻辑删除一致。
        List<Long> commentIds = commentMapper.selectList(Wrappers.<Comment>lambdaQuery()
                        .eq(Comment::getPostId, postId).select(Comment::getId))
                .stream().map(Comment::getId).toList();
        int removedCommentLikes = 0;
        if (!commentIds.isEmpty()) {
            removedCommentLikes = commentLikeMapper.delete(Wrappers.<CommentLike>lambdaQuery()
                    .in(CommentLike::getCommentId, commentIds));
        }
        int removedComments = commentMapper.delete(Wrappers.<Comment>lambdaQuery()
                .eq(Comment::getPostId, postId));

        // 计数按"实际删掉的行数"递减，而不是按冗余字段的原值：
        // 冗余值一旦已经偏小，用原值减会减出负数（虽然被兜底，但会掩盖漂移）。
        //
        // ⚠️ 两个实测踩到的坑，写在这里免得后来者重犯：
        // ① **多个赋值不能写成多次 setSql 链式调用** —— LambdaUpdateWrapper.setSql 是
        //    "设置"而不是"追加"，连续调用只有**最后一次**生效。必须拼成一条带逗号的
        //    赋值列表。第一版就是这么写的，症状是"点赞计数删帖后不归零、而收藏与评论
        //    归零了"，看起来像只漏了一个字段。
        // ② **不要用 `GREATEST(c - 1, 0)` 给 UNSIGNED 列兜底**：MySQL 会先在无符号域
        //    算出 `c - 1`，c=0 时直接抛 1690（BIGINT UNSIGNED value is out of range），
        //    根本轮不到 GREATEST。必须写成 `IF(c > 0, c - 1, 0)` —— 条件为假时
        //    **不做减法**，这才是真的"下限 0"。实测对照（临时表跑两种写法）见交付报告。
        //    ⚠️ 同一个缺陷也存在于 M3 的 PostService.delete（它的 post_count 用的就是
        //       GREATEST）—— 删掉一条 post_count 已经为 0 的帖子会 500。已登记 CR-M4-3，
        //       M4 不越界去改别人的文件。
        LambdaUpdateWrapper<Post> update = Wrappers.<Post>lambdaUpdate()
                .eq(Post::getId, postId)
                .setSql("like_count = IF(like_count > 0, like_count - " + removedLikes + ", 0), "
                        + "collect_count = IF(collect_count > 0, collect_count - " + removedCollects + ", 0), "
                        + "comment_count = IF(comment_count > 0, comment_count - " + removedComments + ", 0)");
        int affectedPost = postMapper.update(null, update);
        if (affectedPost == 0) {
            // 说明帖子已经被逻辑删除（@TableLogic 的 `is_deleted = 0` 把 UPDATE 挡掉了）。
            // 关系行已经清空、计数却没退 —— 这种不一致必须喊出来，不能静默留着
            log.error("删帖收尾时目标帖子已不可更新（可能已被逻辑删除），"
                    + "计数将与被清空的关系表不一致，需要人工核对：postId={} 清掉的关系行={}",
                    postId, removedLikes + removedCollects + removedComments);
        }

        if (removedLikes + removedCollects + removedComments > 0) {
            log.info("删帖前的互动收尾：postId={} 点赞行={} 收藏行={} 评论行={} 评论点赞行={}",
                    postId, removedLikes, removedCollects, removedComments, removedCommentLikes);
        }
        return removedLikes + removedCollects + removedComments;
    }

    // ==================================================================
    // 小工具
    // ==================================================================

    /**
     * 校验帖子存在且对前台可见（正常状态、未删除），否则 404。
     *
     * <p><b>为什么可以有这一次读</b>（任务书禁止的是"先 SELECT 再 UPDATE 做幂等"）：
     * 它校验的是<b>目标资源是否存在</b>，不是"这个关系是否已经存在"。
     * 幂等判定仍然完全落在唯一索引与影响行数上 —— 没有任何一行代码依赖"这次读的结果"
     * 去决定该不该写关系行。把它去掉的话，"给一个不存在的帖子点赞"会被静默接受，
     * 那种数据在 {@code post_like} 里永远对不上任何一期对账。</p>
     */
    private Post requireVisiblePost(long postId) {
        Post post = postMapper.selectById(postId);
        if (post == null || post.getStatus() == null || post.getStatus() != Post.STATUS_NORMAL) {
            throw new BizException(ErrorCode.NOT_FOUND, "帖子不存在");
        }
        return post;
    }

    private static int nullToZero(Integer value) {
        return value == null ? 0 : value;
    }

    /** 关系是否存在（供 {@code GET /api/users/{id}} 之类需要"我是否点过赞"的场景用）。 */
    @Transactional(readOnly = true)
    public boolean hasLiked(long userId, long postId) {
        return postLikeMapper.selectCount(Wrappers.<PostLike>lambdaQuery()
                .eq(PostLike::getPostId, postId).eq(PostLike::getUserId, userId)) > 0;
    }

    /** 我是否收藏过该帖。 */
    @Transactional(readOnly = true)
    public boolean hasCollected(long userId, long postId) {
        return postCollectMapper.selectCount(Wrappers.<PostCollect>lambdaQuery()
                .eq(PostCollect::getPostId, postId).eq(PostCollect::getUserId, userId)) > 0;
    }

    /** 我是否点赞过该评论。 */
    @Transactional(readOnly = true)
    public boolean hasLikedComment(long userId, long commentId) {
        return commentLikeMapper.selectCount(Wrappers.<CommentLike>lambdaQuery()
                .eq(CommentLike::getCommentId, commentId).eq(CommentLike::getUserId, userId)) > 0;
    }

    /**
     * 一批帖子里，哪些是 {@code userId} 点过赞的（CR-K 的 {@code liked} 字段用）。
     *
     * <p><b>为什么是"批"而不是逐条 {@code hasLiked}</b>：列表一页 20 条，逐条问就是 20 次往返（N+1）。
     * 这里一次 {@code post_id IN (...)} 取回集合，调用方在内存里 {@code contains} ——
     * 查询次数与页大小无关。</p>
     *
     * <p>未登录（{@code userId == null}）或空列表时<b>直接返回空集合、不查库</b>：
     * 契约要求未登录时 {@code liked} 恒为 {@code false}（§13.1）。
     * 这里返回空集合表达的是"确定地没有任何点赞"，而不是"猜一个" ——
     * 与"取不到就当 0"那种静默错法有本质区别。</p>
     *
     * @param userId  当前请求者；未登录为 {@code null}
     * @param postIds 一页帖子的 id
     * @return 其中被该用户点过赞的帖子 id 集合
     */
    @Transactional(readOnly = true)
    public Set<Long> likedPostIds(Long userId, List<Long> postIds) {
        if (userId == null || postIds == null || postIds.isEmpty()) {
            return Set.of();
        }
        return postLikeMapper.selectList(Wrappers.<PostLike>lambdaQuery()
                        .eq(PostLike::getUserId, userId)
                        .in(PostLike::getPostId, postIds)
                        .select(PostLike::getPostId))
                .stream().map(PostLike::getPostId).collect(Collectors.toSet());
    }

    /** 一批帖子里，哪些是 {@code userId} 收藏过的（CR-K 的 {@code collected} 字段用）；与点赞同源同形状。 */
    @Transactional(readOnly = true)
    public Set<Long> collectedPostIds(Long userId, List<Long> postIds) {
        if (userId == null || postIds == null || postIds.isEmpty()) {
            return Set.of();
        }
        return postCollectMapper.selectList(Wrappers.<PostCollect>lambdaQuery()
                        .eq(PostCollect::getUserId, userId)
                        .in(PostCollect::getPostId, postIds)
                        .select(PostCollect::getPostId))
                .stream().map(PostCollect::getPostId).collect(Collectors.toSet());
    }

    /** 关系行的权威条数（对账用：{@code like_count} 必须等于它）。 */
    @Transactional(readOnly = true)
    public long countLikes(long postId) {
        return postLikeMapper.selectCount(Wrappers.<PostLike>lambdaQuery().eq(PostLike::getPostId, postId));
    }

    /** 收藏关系行的权威条数。 */
    @Transactional(readOnly = true)
    public long countCollects(long postId) {
        return postCollectMapper.selectCount(Wrappers.<PostCollect>lambdaQuery()
                .eq(PostCollect::getPostId, postId));
    }

    /** 判断两个 id 是否指向同一个人（供上层做归属判定时复用，避免各自写 {@code equals}）。 */
    public static boolean sameUser(Long left, Long right) {
        return Objects.equals(left, right);
    }
}
