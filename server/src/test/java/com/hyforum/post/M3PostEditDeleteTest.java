package com.hyforum.post;

import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 改帖与删帖（docs/技术方案.md §6.5、§8.6 第 6 条）。
 *
 * <p>覆盖的验收项与方法名：</p>
 * <ul>
 *   <li>{@code M3_only_author_can_edit_within_30min} —— 越权 / 超时 → 拒绝；</li>
 *   <li>{@code M3_edit_resets_status_to_pending} ——（本任务新增，待 L1 登记）对应 L1 裁决第 3 条；</li>
 *   <li>{@code M3_delete_is_soft_and_author_only} ——（本任务新增，待 L1 登记）§6.5：仅作者或管理员，逻辑删除。</li>
 * </ul>
 *
 * <h2>为什么"编辑重审"这条必须单独立一条</h2>
 * <p>它是「先发后审」模式下唯一的审核绕过口：作者可以先发一篇合规内容拿到 {@code status=1}，
 * 再通过 PUT 把标题正文改成违规内容 —— 敏感词过滤只发生在第一次提交，
 * 管理员巡查也早已放行过这条帖子。所以 §8.6 第 6 条要求：
 * <b>只要内容有变更，{@code status} 一律回到 0</b>（不是"命中敏感词才回 0"）。</p>
 */
@DisplayName("M3 · 改帖与删帖")
class M3PostEditDeleteTest extends M3ApiTestSupport {

    /**
     * 验收项：{@code M3_only_author_can_edit_within_30min}（§6.5）。
     *
     * <p>两条件缺一不可：<b>只有作者</b>能改，且<b>只在发布后 30 分钟内</b>能改。
     * 30 分钟窗口的意义是"发完发现错别字还能补救"，而不是"帖子永远可改"——
     * 否则一条已被点赞、被评论的帖子可以在事后被改成完全不同的内容，
     * 讨论记录会失真（这也是很多论坛把编辑留痕/限时的原因）。</p>
     *
     * <p>用例内每一步都带反证：先证明"作者在窗口内改得动"，再证明"别人改不动"、
     * "超时改不动" —— 否则一个"任何 PUT 都 403"的实现也能让拒绝类断言通过。</p>
     */
    @Test
    void M3_only_author_can_edit_within_30min() {
        TestUser author = createFreshUser();
        TestUser other = createFreshUser();
        long boardId = createNormalBoard();
        long postId = createPostAndGetId(author.token(), postCreateBody(boardId, "原标题", "原正文"));

        // ---------- ① 反证：作者在 30 分钟窗口内可以改 ----------
        Response byAuthor = updatePost(author.token(), postId, postUpdateBody("作者改过的标题", "原正文"));
        assertThat(byAuthor.jsonPath().getInt("code"))
                .as("作者在 30 分钟内必须能改（否则后面的拒绝断言不能说明任何问题）：%s", byAuthor.asString())
                .isZero();
        assertThat(postColumn(postId, "title", String.class)).isEqualTo("作者改过的标题");

        // ---------- ② 越权：别人改不动 ----------
        Response byOther = updatePost(other.token(), postId, postUpdateBody("别人改的标题", "别人改的正文"));
        assertThat(byOther.jsonPath().getInt("code"))
                .as("非作者必须被拒（§6.5 仅作者）：%s", byOther.asString())
                .isEqualTo(403);
        assertThat(postColumn(postId, "title", String.class))
                .as("越权请求不得产生任何写入").isEqualTo("作者改过的标题");

        // ---------- ③ 超时：把 created_at 回拨到 31 分钟前 ----------
        jdbcTemplate.update("UPDATE post SET created_at = DATE_SUB(NOW(), INTERVAL 31 MINUTE) WHERE id = ?", postId);
        Response afterWindow = updatePost(author.token(), postId, postUpdateBody("超时改的标题", "原正文"));
        assertThat(afterWindow.jsonPath().getInt("code"))
                .as("发布超过 30 分钟后即使作者也不能改：%s", afterWindow.asString())
                .isEqualTo(403);
        assertThat(postColumn(postId, "title", String.class))
                .as("超时请求不得产生任何写入").isEqualTo("作者改过的标题");

        // ---------- ④ 不存在的帖子 → 404（否则会变成一个空更新的 500） ----------
        Response missing = updatePost(author.token(), 99999999L, postUpdateBody("标题", "正文"));
        assertThat(missing.jsonPath().getInt("code"))
                .as("改一个不存在的帖子必须是 404：%s", missing.asString())
                .isEqualTo(404);
    }

    /**
     * 本任务新增用例：{@code M3_edit_resets_status_to_pending}
     * —— 对应 L1 裁决第 3 条与 §8.6 第 6 条（编辑重审的绕过闭环）。
     *
     * <p>三条断言各自防一种错法：</p>
     * <ol>
     *   <li>改了内容 → {@code status} 回 0（防"编辑后仍然直接可见"的绕过）；</li>
     *   <li><b>没改内容</b> → 不回 0（防"一律回 0"——那种实现会让每次点保存都把自己
     *       已放行的帖子打回待审队列，运营侧会收到大量无意义的待审项）；</li>
     *   <li>只改<b>网盘字段</b> → 也回 0（§6.5 明文把"网盘字段"列入内容变更）。</li>
     * </ol>
     */
    @Test
    void M3_edit_resets_status_to_pending() {
        TestUser author = createFreshUser();
        long boardId = createResourceBoard();
        long postId = createPostAndGetId(author.token(), withDisk(
                postCreateBody(boardId, "初始标题", "初始正文"),
                1, "https://pan.baidu.com/s/1AbCdEfGh?pwd=9k2m", null));

        // ---------- 反证：起始状态是"直接可见" ----------
        assertThat(postColumn(postId, "status", Integer.class))
                .as("前置条件：新帖未命中敏感词，必须是 status=1")
                .isEqualTo(1);

        // ---------- ① 改标题 → status 回 0 ----------
        // 注意：PUT 的语义是"用请求体覆盖内容"，因此资源版块的改帖请求**必须带上网盘字段**
        // （不传 = 清空网盘信息，而资源版块不允许没有链接 → 400）。这不是实现的额外要求，
        // 而是"省略即清空"这一 PUT 语义在资源版块上的必然结果，已在 PostUpdateRequest 的注释里写明
        Response edited = updatePost(author.token(), postId, withDisk(
                postUpdateBody("改过的标题", "初始正文"),
                1, "https://pan.baidu.com/s/1AbCdEfGh?pwd=9k2m", null));
        assertThat(edited.jsonPath().getInt("code")).as("%s", edited.asString()).isZero();
        assertThat(edited.jsonPath().getInt("data.status"))
                .as("内容变更后响应里的 status 必须已经是 0")
                .isZero();
        assertThat(postColumn(postId, "status", Integer.class))
                .as("内容变更后 status 一律回到 0（§8.6 第 6 条，防审核绕过）")
                .isZero();

        // ---------- ② 模拟管理员放行，然后提交"完全一致的内容" → 不回 0 ----------
        jdbcTemplate.update("UPDATE post SET status = 1 WHERE id = ?", postId);
        Response sameContent = updatePost(author.token(), postId, currentStateBody(postId));
        assertThat(sameContent.jsonPath().getInt("code")).as("%s", sameContent.asString()).isZero();
        assertThat(postColumn(postId, "status", Integer.class))
                .as("内容没有任何变更时不得回 0 —— 否则每次点保存都会把自己已放行的帖子打回待审")
                .isEqualTo(1);

        // ---------- ③ 只改网盘字段 → 也算内容变更，回 0 ----------
        Response diskChanged = updatePost(author.token(), postId, withDisk(
                postUpdateBody("改过的标题", "初始正文"),
                1, "https://pan.baidu.com/s/1AbCdEfGh", "new9"));
        assertThat(diskChanged.jsonPath().getInt("code")).as("%s", diskChanged.asString()).isZero();
        assertThat(postColumn(postId, "status", Integer.class))
                .as("网盘字段属于内容变更（§6.5 明文列举），必须回 0")
                .isZero();
        assertThat(postColumn(postId, "disk_code", String.class)).isEqualTo("new9");
    }

    /**
     * 本任务新增用例：{@code M3_delete_is_soft_and_author_only}（§6.5）。
     *
     * <p>删除必须是<b>逻辑删除</b>（{@code is_deleted=1}）：M4 的评论/点赞还挂在帖子 id 上，
     * 物理删除会让它们变成悬空数据。同时断言"删掉之后确实看不见了"——
     * 只置标记但列表照样返回，等于没删。</p>
     */
    @Test
    void M3_delete_is_soft_and_author_only() {
        TestUser author = createFreshUser();
        TestUser other = createFreshUser();
        long boardId = createNormalBoard();
        long postId = createPostAndGetId(author.token(), postCreateBody(boardId, "待删除的帖子", "正文"));

        // ---------- ① 越权：别人删不掉 ----------
        Response byOther = deletePost(other.token(), postId);
        assertThat(byOther.jsonPath().getInt("code"))
                .as("非作者不得删除（§6.5 仅作者或管理员）：%s", byOther.asString())
                .isEqualTo(403);
        assertThat(postColumn(postId, "is_deleted", Integer.class))
                .as("越权删除不得产生任何写入").isZero();

        // ---------- 反证：此刻帖子确实还在 ----------
        assertThat(getPostDetail(postId).jsonPath().getInt("code")).isZero();

        // ---------- ② 作者删除 → 逻辑删除 ----------
        Response byAuthor = deletePost(author.token(), postId);
        assertThat(byAuthor.jsonPath().getInt("code"))
                .as("作者必须能删除自己的帖子：%s", byAuthor.asString()).isZero();
        assertThat(postColumn(postId, "is_deleted", Integer.class))
                .as("必须是逻辑删除（行还在，标记置 1）")
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM post WHERE id = ?", Integer.class, postId))
                .as("逻辑删除：行不得被物理删掉（M4 的评论/点赞还挂在它身上）")
                .isEqualTo(1);

        // ---------- ③ 删掉之后前台就看不见了 ----------
        assertThat(getPostDetail(postId).statusCode()).as("已删帖的详情必须 404").isEqualTo(404);
        List<Object> ids = listPosts(Map.of("boardId", boardId)).jsonPath().getList("data.list.id");
        assertThat(ids).as("已删帖不得出现在列表里").isEmpty();

        // ---------- ④ 冗余计数回退（§8.2：同一事务内维护） ----------
        assertThat(jdbcTemplate.queryForObject(
                "SELECT post_count FROM board WHERE id = ?", Integer.class, boardId))
                .as("删帖必须让 board.post_count 减回去").isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT post_count FROM user WHERE id = ?", Integer.class, author.id()))
                .as("删帖必须让 user.post_count 减回去").isZero();

        // ---------- ⑤ 重复删除 → 404（不是 500） ----------
        Response again = deletePost(author.token(), postId);
        assertThat(again.jsonPath().getInt("code"))
                .as("重复删除必须是 404：%s", again.asString())
                .isEqualTo(404);
    }

    /**
     * 把某帖"当前状态"拼成一份 PUT 请求体。
     *
     * <p>用例 ② 要验证"内容没变就不回 0"，而"没变"必须由<b>被测实现的输出</b>回灌，
     * 不能在测试里手写一遍期望值 —— 否则测试与服务各写一套归一化规则，
     * 一旦归一化实现不同（例如 disk_code 首尾空格），用例就会红在一个与被测规则无关的地方。</p>
     */
    private Map<String, Object> currentStateBody(long postId) {
        Response detail = getPostDetail(postId);
        Object diskType = detail.jsonPath().get("data.diskType");
        Object diskUrl = detail.jsonPath().get("data.diskUrl");
        Object diskCode = detail.jsonPath().get("data.diskCode");
        List<String> imageUrls = detail.jsonPath().getList("data.images.url");
        Map<String, Object> body = postUpdateBody(
                detail.jsonPath().getString("data.title"), detail.jsonPath().getString("data.content"));
        withImages(body, imageUrls == null ? new ArrayList<>() : imageUrls);
        return withDisk(body,
                diskType == null ? null : ((Number) diskType).intValue(),
                diskUrl == null ? null : String.valueOf(diskUrl),
                diskCode == null ? null : String.valueOf(diskCode));
    }
}
