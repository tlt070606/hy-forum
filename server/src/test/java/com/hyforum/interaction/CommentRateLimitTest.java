package com.hyforum.interaction;

import io.restassured.response.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 发表评论的用户限流（2026-10-01 安全整改的验收用例；§8.7 原文「评论 20/h」）。
 *
 * <h2>这条用例存在的理由</h2>
 * <p>整改前的状态是"发帖有限流、发评论没有"：发帖要过验证码 + 双重限流
 * （新用户 3/24h、老用户 10/h），评论却能被登录用户脚本无限刷 ——
 * 而每条评论/回复都会写一条通知，刷评论的直接受害者是通知表与被刷的用户。</p>
 *
 * <h2>口径：入口维度 → HTTP 429 + Retry-After</h2>
 * <p>与登录/管理员登录同一分层（§5 裁决 #6/#8），与发帖的"业务动作维度"（HTTP 200 + 2002）
 * <b>不同层</b>。用例同时断言状态码与 {@code Retry-After} 头。</p>
 *
 * <h2>为什么把限额压到 3</h2>
 * <p>生产值是 20 条/小时（{@code hy.rate-limit.comment-per-hour}）。
 * 本类用 {@code @TestPropertySource} 显式压到 3：断言测的是<b>行为</b>。
 * 压小还有一层理由：本类的桶是 {@code comment-user-1h:{userId}}，而清表会重置自增 id
 * —— 新用例的用户可能复用旧 id 并继承其配额（M5 举报限流的同类教训），
 * 用例里清自己的键 + 压小限额，两头都把这种"跨用例串状态"的暴露面收到最小。</p>
 */
@TestPropertySource(properties = {
        "hy.rate-limit.comment-per-hour=3"
})
class CommentRateLimitTest extends M4ApiTestSupport {

    private TestUser commenter;
    private long postId;

    @Override
    protected String[] tablesToClean() {
        return new String[]{
                "notification", "comment_like", "comment", "post_like", "post_collect", "follow",
                "post_image", "post", "board", "user"
        };
    }

    @BeforeEach
    void seed() {
        TestUser author = createUser("帖子作者");
        commenter = createUser("评论者");
        long boardId = createBoard();
        postId = createNormalPost(boardId, author.id(), "评论限流用例帖");
        stringRedisTemplate.delete(rateLimitKey());
    }

    @AfterEach
    void clearOwnBucket() {
        // 清本类自己的桶（action + 本用例用户的 id）—— 绝不通配符
        stringRedisTemplate.delete(rateLimitKey());
    }

    private String rateLimitKey() {
        return "hy:rl:comment-user-1h:" + commenter.id();
    }

    @Test
    @DisplayName("M4_comment_rate_limited：超过每小时限额 → HTTP 429 + Retry-After")
    void M4_comment_rate_limited() {
        // 限额内的 3 条必须成功
        for (int i = 1; i <= 3; i++) {
            Response ok = createComment(commenter.token(), postId, 0, "限流用例评论 " + i);
            assertThat(ok.jsonPath().getInt("code"))
                    .as("限额内第 %d 条评论必须成功。响应：%s", i, ok.asString())
                    .isZero();
        }

        // 第 4 条：HTTP 429 + Retry-After 头
        Response blocked = createComment(commenter.token(), postId, 0, "超限评论");
        assertThat(blocked.statusCode())
                .as("超限必须 HTTP 429（入口维度分层，与发帖的 200+2002 不同层）。响应：%s",
                        blocked.asString())
                .isEqualTo(429);
        assertThat(blocked.getHeader("Retry-After"))
                .as("429 必须带 Retry-After 头（§8.7）。响应：%s", blocked.asString())
                .isNotNull();

        // 被拒的请求不得落库
        assertThat(countRows("comment", "post_id", postId))
                .as("被限流拒绝的评论不得落库").isEqualTo(3);

        // 反向自证：清掉自己的桶后必须恢复可评论 —— 否则上面的 429 可能只是接口坏了
        stringRedisTemplate.delete(rateLimitKey());
        Response recovered = createComment(commenter.token(), postId, 0, "清桶后恢复");
        assertThat(recovered.jsonPath().getInt("code"))
                .as("清理限流桶后必须恢复可评论。响应：%s", recovered.asString())
                .isZero();
    }
}
