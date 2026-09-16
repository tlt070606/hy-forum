package com.hyforum.post;

import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 发帖频率限制（docs/技术方案.md §8.7）—— 这是交接事项 <b>H1</b> 要求补的那条真实触发用例。
 *
 * <ul>
 *   <li>验收项/方法名：{@code M3_post_rate_limit_triggers_2002}（H1 点名，<b>目前不在</b>
 *       {@code docs/testing/验收项-测试映射.md} 里，由 L1 登记）；</li>
 *   <li>契约阈值（§8.7）：<b>新注册用户 24 小时内 ≤ 3 帖；普通用户 ≤ 10 帖/小时</b>。</li>
 * </ul>
 *
 * <h2>为什么必须真实触发（H1 的由来）</h2>
 * <p>M1 的 {@code M1_error_codes_match_contract} 只断言了"枚举里有 2002 这个码"——
 * 那是<b>契约完整性</b>，不是"限流真的生效"。一个把 2002 写在枚举里、
 * 却在发帖路径上根本不调用限流器的实现，同样能让 M1 全绿。
 * 所以本用例必须：真的发出第 4 篇帖子，并断言它被拒、且<b>没有落库</b>。</p>
 *
 * <h2>两个必须遵守的细节</h2>
 * <ol>
 *   <li><b>请求体必须是契约允许的合法请求体</b>（docs/testing/README.md §5.1 坑二）：
 *       {@code @Valid} 校验发生在 Controller 方法体之前，非法请求体会先命中 400，
 *       于是限流计数压根没被消耗 —— 那样刷多少次都触发不了，用例会以"看起来是限流没实现"
 *       的方式变红，把真 bug 掩盖掉；</li>
 *   <li><b>必须做反证</b>：证明被拒的确实是"这个新用户的额度"，
 *       而不是"发帖接口坏了"或"全站限流"。因此用例里换一个老用户接着发，
 *       并断言他能连发 10 帖（§8.7 普通用户 ≤ 10 帖/小时）。</li>
 * </ol>
 */
@DisplayName("M3 · 发帖频率限制（H1）")
class M3PostRateLimitTest extends M3ApiTestSupport {

    /** 响应体里的业务码，取自 §6.1（ErrorCode.POST_TOO_FREQUENT）。 */
    private static final int CODE_POST_TOO_FREQUENT = 2002;

    @Test
    void M3_post_rate_limit_triggers_2002() {
        TestUser freshUser = createFreshUser();
        long boardId = createNormalBoard();

        // ---------- ① 新用户的前 3 帖必须成功（否则下面的拒绝断言没有意义） ----------
        for (int i = 1; i <= 3; i++) {
            Response ok = createPost(freshUser.token(), postCreateBody(boardId, "新用户第 " + i + " 帖", "正文"));
            assertThat(ok.jsonPath().getInt("code"))
                    .as("新注册用户 24 小时内允许 3 帖，第 %d 帖必须成功：%s", i, ok.asString())
                    .isZero();
        }
        assertThat(countPosts(boardId)).as("前 3 帖必须落库").isEqualTo(3);

        // ---------- ② 第 4 帖 → 2002 发帖过于频繁 ----------
        Response fourth = createPost(freshUser.token(), postCreateBody(boardId, "新用户第 4 帖", "正文"));
        assertThat(fourth.jsonPath().getInt("code"))
                .as("H1：第 4 帖必须真实触发 2002（§8.7 新用户 24h ≤ 3 帖）。"
                        + "HTTP %d，响应：%s", fourth.statusCode(), fourth.asString())
                .isEqualTo(CODE_POST_TOO_FREQUENT);
        assertThat(countPosts(boardId))
                .as("被限流拒绝的帖子不得落库（只回错误码但照样写库，等于限流没生效）")
                .isEqualTo(3);

        // ---------- ③ 反证：另一个老用户此刻不受影响 ----------
        // 老用户（created_at 已超过 24h）走的是 §8.7 的"普通用户 ≤ 10 帖/小时"这条线，
        // 因此他能连发超过 3 帖 —— 这证明被拒的是"新用户额度"，不是"全局限流"或"发帖坏了"
        TestUser establishedUser = createEstablishedUser();
        for (int i = 1; i <= 10; i++) {
            Response ok = createPost(establishedUser.token(), postCreateBody(boardId, "老用户第 " + i + " 帖", "正文"));
            assertThat(ok.jsonPath().getInt("code"))
                    .as("老用户 1 小时内允许 10 帖，第 %d 帖必须成功（反证：限流是分用户的）：%s",
                            i, ok.asString())
                    .isZero();
        }

        // ---------- ④ 老用户的第 11 帖 → 同样 2002（10 帖/小时这条线也真的生效） ----------
        Response eleventh = createPost(establishedUser.token(), postCreateBody(boardId, "老用户第 11 帖", "正文"));
        assertThat(eleventh.jsonPath().getInt("code"))
                .as("老用户 1 小时内的第 11 帖必须触发 2002：HTTP %d，响应：%s",
                        eleventh.statusCode(), eleventh.asString())
                .isEqualTo(CODE_POST_TOO_FREQUENT);
        assertThat(countPosts(boardId))
                .as("库里应是新用户 3 帖 + 老用户 10 帖 = 13 帖")
                .isEqualTo(13);

        // ---------- ⑤ 错误提示必须能指导用户（不是干巴巴的 2002） ----------
        assertThat(eleventh.jsonPath().getString("message"))
                .as("§8.7 要求给出友好提示，M1 的做法是把重试秒数写进 message")
                .contains("频繁");
    }

    /** 某个版块下的帖子行数。 */
    private int countPosts(long boardId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM post WHERE board_id = ? AND is_deleted = 0", Integer.class, boardId);
        return count == null ? 0 : count;
    }
}
