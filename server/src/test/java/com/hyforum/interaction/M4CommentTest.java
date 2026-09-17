package com.hyforum.interaction;

import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M4 评论：楼中楼归并语义、reply_count 一致性、前 2 条预览。
 *
 * <p>对应验收项（任务书 §7.1）：{@code M4_comment_no_third_level}、
 * {@code M4_comment_reply_count_consistent}、{@code M4_comment_list_previews_two_replies}。</p>
 *
 * <h2>这条用例在测什么（P1-3 的三层防线）</h2>
 * <p>技术方案 §6.6 说得很明确：<b>只靠数据库 CHECK 是不够的</b>，
 * 因为它拦不住"指向了错的楼层"。所以三层必须各自被证明：</p>
 * <ol>
 *   <li><b>唯一写入口的归并</b>：回复楼中楼 → 落库那行的 {@code parent_id = root_id = 主楼 id}
 *       （不是报错、也不是照抄传入值）；</li>
 *   <li><b>对抗性用例</b>：<b>伪造</b> {@code parentId} 直接指向一条楼中楼 → 仍然归并；</li>
 *   <li><b>数据库第二道防线</b>：绕过应用直接 {@code INSERT} 非法组合
 *       （{@code parent_id <> root_id}）→ 必须被 {@code chk_comment_two_levels} 拒，
 *       且报的是 <b>MySQL 错误码 3819</b>（CHECK 约束违反），不是别的错误。</li>
 * </ol>
 * <p>第 3 条<b>必须断言错误码而不是"抛了异常"</b>：任何 SQL 语法错误、字段长度超限
 * 都会抛异常，那样这条断言是永远绿的 —— 它证明不了 CHECK 约束真的在工作。</p>
 */
class M4CommentTest extends M4ApiTestSupport {

    private TestUser author;
    private TestUser commenter;
    private long postId;

    @BeforeEach
    void seed() {
        author = createUser("楼主");
        commenter = createUser("评论者");
        long boardId = createBoard();
        postId = createNormalPost(boardId, author.id(), "M4 评论用例帖");
    }

    // ==================================================================
    // 楼中楼不超过两层
    // ==================================================================

    @Test
    @DisplayName("M4_comment_no_third_level：回复楼中楼被归并到主楼；伪造 parentId 仍归并；非法组合被 CHECK 拒（3819）")
    void M4_comment_no_third_level() {
        // —— ①② 两个方向都必须归并 ——
        // 方向 ①：正常路径"回复一条楼中楼"
        // 方向 ②：对抗性路径 —— **伪造** parentId 直接指向楼中楼
        // 两者在协议上是同一个请求（parentId 就是那条楼中楼的 id），差别在于
        // "客户端有没有如实表达意图"：方向 ② 是"客户端明知故犯/或拿着过期数据"的情形。
        // 因此这里第二次调用仍然传楼中楼的 id，并断言落库结果与第一次完全一致。
        for (int round = 1; round <= 2; round++) {
            long rootId = createCommentAndGetId(commenter.token(), postId, 0, "第 " + round + " 轮的主楼");
            // 楼中楼：回复主楼 → parent_id = root_id = 主楼 id
            long replyId = createCommentAndGetId(author.token(), postId, rootId, "第 " + round + " 轮的楼中楼");

            assertThat(commentColumn(replyId, "parent_id", Long.class))
                    .as("回复主楼时 parent_id 必须等于主楼 id（第 %d 轮）", round)
                    .isEqualTo(rootId);
            assertThat(commentColumn(replyId, "root_id", Long.class))
                    .as("回复主楼时 root_id 必须等于主楼 id（第 %d 轮）", round)
                    .isEqualTo(rootId);

            // ★ 关键：回复**那条楼中楼**（parentId 指向楼中楼）
            long third = createCommentAndGetId(commenter.token(), postId, replyId, "第 " + round + " 轮回复楼中楼");

            assertThat(commentColumn(third, "parent_id", Long.class))
                    .as("回复楼中楼必须**归并**到主楼：parent_id 必须等于主楼 id %d，"
                            + "而绝不能等于楼中楼 id %d（那就是三层结构）。第 %d 轮", rootId, replyId, round)
                    .isEqualTo(rootId)
                    .isNotEqualTo(replyId);
            assertThat(commentColumn(third, "root_id", Long.class))
                    .as("归并语义要求 parent_id 恒等于 root_id（第 %d 轮）", round)
                    .isEqualTo(rootId);
            assertThat(commentColumn(third, "parent_id", Long.class))
                    .as("归并不变量：parent_id == root_id（第 %d 轮）", round)
                    .isEqualTo(commentColumn(third, "root_id", Long.class));

            // 全表自检：整个 comment 表里不允许出现"parent_id 指向一条楼中楼"的行。
            // 这一条比单点断言更硬 —— 它不关心是哪条路径写进来的
            Integer illegal = scalar(
                    "SELECT COUNT(*) FROM comment c JOIN comment p ON c.parent_id = p.id "
                            + "WHERE p.parent_id <> 0", Integer.class);
            assertThat(illegal)
                    .as("不允许存在任何 parent_id 指向楼中楼的行（第 %d 轮）", round)
                    .isZero();
        }

        // —— ③ 数据库第二道防线：绕过应用直接 INSERT 非法组合 ——
        long rootId = createCommentAndGetId(commenter.token(), postId, 0, "给 CHECK 用的主楼");
        long replyId = createCommentAndGetId(author.token(), postId, rootId, "给 CHECK 用的楼中楼");
        long postIdForSql = postId;
        long userIdForSql = commenter.id();

        // 非法形态 A：parent_id <> 0 而 root_id = 0
        assertThat(sqlErrorCodeOfInsert(rootId, 0L))
                .as("parent_id <> 0 且 root_id = 0 必须被 chk_comment_two_levels 拒绝（MySQL 3819）")
                .isEqualTo(3819);

        // 非法形态 B：parent_id 与 root_id 不相等（这正是"三层"的落库形态）
        assertThat(sqlErrorCodeOfInsert(replyId, rootId))
                .as("parent_id <> root_id（三层形态）必须被 chk_comment_two_levels 拒绝（MySQL 3819）")
                .isEqualTo(3819);

        // 非法形态 C：主楼却带 root_id —— (0, x) 同样不满足不变量
        assertThat(sqlErrorCodeOfInsert(0L, rootId))
                .as("(parent_id = 0, root_id <> 0) 也必须被拒")
                .isEqualTo(3819);

        // 反向自证：合法组合必须被接受，否则上面的"被拒"可能只是因为约束写得太严。
        // 期望值由"插入前先数一遍"得出，不写死常数 —— 写死常数会在前面的步骤改动后
        // 变成一条与被测行为无关的失败（本用例第一版就因此红过一次）
        int before = commentCountForPost(postIdForSql);
        jdbcTemplate.update(
                "INSERT INTO comment (post_id, user_id, parent_id, root_id, content) VALUES (?, ?, 0, 0, ?)",
                postIdForSql, userIdForSql, "合法：主楼 (0,0)");
        jdbcTemplate.update(
                "INSERT INTO comment (post_id, user_id, parent_id, root_id, content) VALUES (?, ?, ?, ?, ?)",
                postIdForSql, userIdForSql, rootId, rootId, "合法：楼中楼 (x,x)");
        assertThat(commentCountForPost(postIdForSql))
                .as("两种合法形态都必须插入成功（反向自证：约束没有把合法数据也挡掉）")
                .isEqualTo(before + 2);
    }

    /**
     * 绕过应用层直接 {@code INSERT} 一条 {@code (parent_id, root_id)} 组合，返回 MySQL 错误码。
     *
     * <p><b>为什么断言错误码 3819 而不是"抛没抛异常"</b>：任何 SQL 语法错误、字段超长、
     * 甚至表名写错都会抛异常 —— 那样这条断言<b>永远绿</b>，证明不了
     * {@code chk_comment_two_levels} 真的在工作。3819 是 MySQL 专给 CHECK 约束违反的错码
     * （{@code ER_CHECK_CONSTRAINT_VIOLATED}），只有约束真的拦住才会出现。</p>
     *
     * <p>为什么不用 {@code assertThatThrownBy(...).isInstanceOf(DataIntegrityViolationException.class)}：
     * 实测 MySQL 8.0.34 把 CHECK 违反以 {@code SQLIntegrityConstraintViolationException}
     * （错误码 3819）抛出，且<b>不一定</b>被 Spring 的 SQL 状态码翻译器映射成
     * {@code DataIntegrityViolationException} —— 依赖具体异常类会让用例随驱动版本漂移。
     * 取错误码既稳定又精确（本任务第一次就踩了这个断言：实际异常类型与预期不符）。</p>
     *
     * @return 期望抛出的 SQL 错误码；若压根没抛异常则断言失败
     */
    private int sqlErrorCodeOfInsert(long parentId, long rootId) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO comment (post_id, user_id, parent_id, root_id, content) VALUES (?, ?, ?, ?, ?)",
                    postId, commenter.id(), parentId, rootId, "非法组合探测用");
        } catch (RuntimeException ex) {
            Throwable current = ex;
            while (current != null) {
                if (current instanceof SQLException sql) {
                    return sql.getErrorCode();
                }
                current = current.getCause() == current ? null : current.getCause();
            }
            throw new AssertionError("非法 INSERT 抛出的异常里没有 SQLException，无法取错误码："
                    + ex.getClass().getName() + " : " + ex.getMessage(), ex);
        }
        throw new AssertionError("非法组合 (parent_id=" + parentId + ", root_id=" + rootId
                + ") 竟然插入成功了 —— chk_comment_two_levels 没生效");
    }

    /** 该帖的评论总行数（含已逻辑删除的，用于"是否插入成功"的计数）。 */
    private int commentCountForPost(long postId) {
        Integer count = scalar("SELECT COUNT(*) FROM comment WHERE post_id = ?", Integer.class, postId);
        return count == null ? 0 : count;
    }

    // ==================================================================
    // reply_count 一致性
    // ==================================================================

    @Test
    @DisplayName("M4_comment_reply_count_consistent：主楼 reply_count == 实际楼中楼数（含删除后回退）")
    void M4_comment_reply_count_consistent() {
        long rootId = createCommentAndGetId(commenter.token(), postId, 0, "计数用主楼");
        long secondRoot = createCommentAndGetId(commenter.token(), postId, 0, "计数用主楼二");

        // 给主楼加 3 条楼中楼（其中 1 条是"回复楼中楼"，验证归并后计数加在**主楼**上）
        long r1 = createCommentAndGetId(author.token(), postId, rootId, "楼中楼 1");
        long r2 = createCommentAndGetId(author.token(), postId, rootId, "楼中楼 2");
        long r3 = createCommentAndGetId(author.token(), postId, r1, "回复楼中楼 1（应归并到主楼）");

        assertThat(commentColumn(rootId, "reply_count", Integer.class))
                .as("主楼的 reply_count 必须等于实际楼中楼数 3 —— "
                        + "'回复楼中楼'也要算进主楼的计数（这是归并语义的一半）")
                .isEqualTo(3);
        assertThat(aliveReplies(rootId))
                .as("冗余计数必须等于实际行数")
                .isEqualTo(commentColumn(rootId, "reply_count", Integer.class));

        // 楼中楼自己那一列恒为 0（列注释：reply_count 仅主楼维护）
        assertThat(commentColumn(r1, "reply_count", Integer.class))
                .as("楼中楼行的 reply_count 必须是 0")
                .isZero();

        // 另一条主楼不受影响
        assertThat(commentColumn(secondRoot, "reply_count", Integer.class)).isZero();

        // 删掉一条楼中楼 → 主楼计数 -1；重复删除不再减（幂等）
        assertOk(deleteComment(author.token(), r2));
        assertThat(commentColumn(rootId, "reply_count", Integer.class))
                .as("删除一条楼中楼后，主楼 reply_count 必须减 1")
                .isEqualTo(2);
        assertOk(deleteComment(author.token(), r2));
        assertOk(deleteComment(author.token(), r2));
        assertThat(commentColumn(rootId, "reply_count", Integer.class))
                .as("重复删除已删的楼中楼**不得**继续扣计数（幂等）")
                .isEqualTo(2);

        // 评论总数（post.comment_count）必须与可见评论行数一致
        assertThat(postColumn(postId, "comment_count", Integer.class))
                .as("post.comment_count 必须等于该帖未删除的评论行数")
                .isEqualTo(aliveComments(postId));

        // 删除主楼 → 它的楼中楼一并删除，计数一起回退
        assertOk(deleteComment(commenter.token(), rootId));
        assertThat(aliveReplies(rootId)).as("删除主楼必须连带删除其楼中楼（逻辑删除）").isZero();
        assertThat(postColumn(postId, "comment_count", Integer.class))
                .as("连带删除后 comment_count 仍必须等于可见评论行数")
                .isEqualTo(aliveComments(postId));

        // ③ 被删掉的评论不能再被回复（否则回复会挂到看不见的内容下面）
        Response response = createComment(author.token(), postId, rootId, "回复已删除的主楼");
        assertThat(response.jsonPath().getInt("code"))
                .as("回复一条已删除的评论必须失败（404）。响应：%s", response.asString())
                .isEqualTo(404);
    }

    // ==================================================================
    // 前 2 条预览
    // ==================================================================

    @Test
    @DisplayName("M4_comment_list_previews_two_replies：主楼列表每条带 2 条预览，且 replyCount 是总数")
    void M4_comment_list_previews_two_replies() {
        long rootId = createCommentAndGetId(commenter.token(), postId, 0, "预览用主楼");
        // 造 4 条楼中楼：预览只能给 2 条，replyCount 必须是 4
        for (int i = 1; i <= 4; i++) {
            createCommentAndGetId(author.token(), postId, rootId, "预览楼中楼 " + i);
        }

        Response response = listComments(postId, 1, 20);
        assertOk(response);
        assertListSize(response, 1);

        Integer replyCount = response.jsonPath().getInt("data.list[0].replyCount");
        assertThat(replyCount)
                .as("replyCount 是**总数**（4），不是预览数。响应：%s", response.asString())
                .isEqualTo(4);
        assertThat(response.jsonPath().getList("data.list[0].replies"))
                .as("预览必须恰好 2 条。响应：%s", response.asString())
                .hasSize(2);

        // 预览内容是最近 2 条（3、4），并按时间升序返回
        assertThat(response.jsonPath().getList("data.list[0].replies.content"))
                .as("预览应是最近的 2 条，且按时间升序（旧的在前）。响应：%s", response.asString())
                .containsExactly("预览楼中楼 3", "预览楼中楼 4");

        // 预览里的 parentId 必须恒等于 rootId（归并语义在响应上也成立）
        assertThat(response.jsonPath().getList("data.list[0].replies.parentId"))
                .as("预览条目的 parentId 必须等于主楼 id")
                .containsExactly((int) rootId, (int) rootId);

        // 楼中楼接口拿到的总数必须与 replyCount 一致（同一个数字的两个入口）
        Response replies = listReplies(rootId, 1, 20);
        assertOk(replies);
        assertThat(replies.jsonPath().getInt("data.total"))
                .as("GET /api/comments/{rootId}/replies 的 total 必须等于主楼上的 replyCount")
                .isEqualTo(replyCount);

        // 边界：没有楼中楼的主楼 → replies 是空数组（不是 null），replyCount=0
        long bareRoot = createCommentAndGetId(commenter.token(), postId, 0, "没有回复的主楼");
        Response after = listComments(postId, 1, 20);
        assertOk(after);
        int index = indexOfRoot(after, bareRoot);
        assertThat(index).as("刚造的主楼必须在列表里（时间倒序，最新的在前）").isNotNegative();
        assertThat(after.jsonPath().getInt("data.list[" + index + "].replyCount")).isZero();
        assertThat(after.jsonPath().getList("data.list[" + index + "].replies"))
                .as("没有楼中楼时 replies 必须是空数组而不是 null")
                .isEmpty();
    }

    /** 在列表响应里找到指定主楼的数组下标（按 id 匹配，不依赖顺序假设）。 */
    private int indexOfRoot(Response response, long rootId) {
        java.util.List<Integer> ids = response.jsonPath().getList("data.list.id");
        for (int i = 0; i < ids.size(); i++) {
            if (ids.get(i) != null && ids.get(i).longValue() == rootId) {
                return i;
            }
        }
        return -1;
    }

    // ==================================================================
    // 越权删除（验收项 SEC_cannot_delete_others_comment）
    // ==================================================================

    @Test
    @DisplayName("SEC_cannot_delete_others_comment：A 的 token 删 B 的评论 → 403")
    void SEC_cannot_delete_others_comment() {
        long bComment = createCommentAndGetId(commenter.token(), postId, 0, "B 的评论");

        Response response = deleteComment(author.token(), bComment);
        assertThat(response.statusCode())
                .as("越权删除必须失败。响应：%s", response.asString())
                .isEqualTo(403);
        assertThat(response.jsonPath().getInt("code")).isEqualTo(403);

        // 关键：必须**没有**被删掉（只看状态码不够 —— 一个"先删后报错"的实现也能返回 403）
        assertThat(commentColumn(bComment, "is_deleted", Integer.class))
                .as("越权删除不得改变数据")
                .isZero();

        // 反向自证：B 自己删同一条必须成功，否则上面的 403 可能只是因为"谁都删不掉"
        assertOk(deleteComment(commenter.token(), bComment));
        assertThat(commentColumn(bComment, "is_deleted", Integer.class))
                .as("作者本人必须能删掉自己的评论")
                .isEqualTo(1);

        // 反向自证 2：查询接口必须真的会过滤已删除的评论，否则"is_deleted=1"只是账面数字
        Response after = listComments(postId, 1, 20);
        assertOk(after);
        assertThat(indexOfRoot(after, bComment))
                .as("已删除的评论不得出现在列表里")
                .isNegative();
    }

    // ==================================================================
    // 评论点赞幂等（§7.1 未单列，但 §8.1 的同一套机制必须被覆盖）
    // ==================================================================

    @Test
    @DisplayName("评论点赞幂等：重复点赞 code=0 且 like_count 只加一次；重复取消不为负")
    void comment_like_is_idempotent() {
        long commentId = createCommentAndGetId(commenter.token(), postId, 0, "点赞用的评论");

        for (int i = 0; i < 3; i++) {
            assertOk(likeComment(author.token(), commentId));
        }
        assertThat(commentColumn(commentId, "like_count", Integer.class))
                .as("重复点赞三次后 like_count 必须恰好 1")
                .isEqualTo(1);
        assertThat(countRows("comment_like", "comment_id", commentId)).isEqualTo(1);

        for (int i = 0; i < 3; i++) {
            assertOk(unlikeComment(author.token(), commentId));
        }
        assertThat(commentColumn(commentId, "like_count", Integer.class))
                .as("重复取消后 like_count 必须归零且不为负")
                .isZero();
        assertThat(countRows("comment_like", "comment_id", commentId)).isZero();
    }

    // ==================================================================
    // 小工具
    // ==================================================================

    /** 未删除的楼中楼行数。 */
    private int aliveReplies(long rootId) {
        Integer count = scalar("SELECT COUNT(*) FROM comment WHERE root_id = ? AND parent_id <> 0 "
                + "AND is_deleted = 0", Integer.class, rootId);
        return count == null ? 0 : count;
    }

    /** 该帖未删除的评论行数（主楼 + 楼中楼）。 */
    private int aliveComments(long postId) {
        Integer count = scalar("SELECT COUNT(*) FROM comment WHERE post_id = ? AND is_deleted = 0",
                Integer.class, postId);
        return count == null ? 0 : count;
    }
}
