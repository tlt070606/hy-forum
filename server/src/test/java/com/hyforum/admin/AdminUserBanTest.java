package com.hyforum.admin;

import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用户封禁/解封（M6 批次一；PLAN M6 验收"封禁用户……留痕可追溯"）。
 *
 * <h2>这条用例要证明的核心一件事：封禁<b>即时</b>生效</h2>
 * <p>PLAN 的语义是管理员点了封禁，用户下一个请求就被拒 —— 而不是"等 2 天 token 过期"。
 * 实现是双保险（状态检查 + 踢全部 token），用例验证最终行为：
 * 被封用户的既有 token 在封禁后立刻不可用（401 或 1004，取决于请求到达时
 * token 是否已被踢干净 —— 两个状态都算"被拒"，用例两个都接受并说明原因）。</p>
 */
class AdminUserBanTest extends AdminApiTestSupport {

    private TestUser victim;
    private TestUser bystander;

    @org.junit.jupiter.api.BeforeEach
    void seed() {
        victim = createUser("被封用户");
        bystander = createUser("无关用户");
    }

    @Test
    @DisplayName("M6_user_ban_and_unban：封禁即时生效 + reason 必填 + 解封恢复 + 双向留痕")
    void M6_user_ban_and_unban() {
        // ① 封禁不填 reason 必须被拒（合规 C9）
        Response noReason = adminPut("/api/admin/users/" + victim.id() + "/ban",
                java.util.Map.of());
        assertThat(noReason.jsonPath().getInt("code"))
                .as("封禁不填 reason 必须被拒。响应：%s", noReason.asString()).isEqualTo(400);

        // ② 封禁成功：DB 状态 0，留痕 USER_BAN / status:1->0
        Response ban = adminPut("/api/admin/users/" + victim.id() + "/ban",
                java.util.Map.of("reason", "刷屏灌水（审核用例）"));
        assertThat(ban.jsonPath().getInt("code"))
                .as("封禁必须成功。响应：%s", ban.asString()).isZero();
        assertThat(userColumn(victim.id(), "status", Integer.class))
                .as("封禁后 user.status 必须为 0").isZero();

        // ③ 即时生效：既有 token 立刻不可用。
        //    401 = token 已被踢掉（logout 生效）；HTTP 200 + code 1004 = token 还在但状态检查拦截。
        //    两条路都是"被拒"，区别只取决于踢 token 与请求到达的先后 —— 不接受的是"能用"。
        Response afterBan = RestAssured.given()
                .header("Authorization", victim.token())
                .get("/api/user/me");
        assertThat(afterBan.statusCode() == 401 || afterBan.jsonPath().getInt("code") == 1004)
                .as("被封用户的请求必须被拒（401 或 1004）。响应：%s", afterBan.asString())
                .isTrue();

        // ④ 无关用户不受影响（封禁的粒度是单个用户）
        Response bystanderMe = RestAssured.given()
                .header("Authorization", bystander.token())
                .get("/api/user/me");
        assertThat(bystanderMe.jsonPath().getInt("code"))
                .as("封禁不得殃及他人。响应：%s", bystanderMe.asString()).isZero();

        // ⑤ 留痕
        Response logs = adminGet("/api/admin/logs?action=USER_BAN&size=20");
        assertThat(logs.jsonPath().<java.util.List<Integer>>get("data.list.targetId"))
                .as("USER_BAN 留痕必须指向被封用户").contains((int) victim.id());
        assertThat(logs.jsonPath().<java.util.List<String>>get("data.list.reason"))
                .as("封禁留痕必须带 reason（C9）").contains("刷屏灌水（审核用例）");

        // ⑥ 解封：状态回 1，用户重新可用（重新登录 + 正常访问）
        Response unban = adminPut("/api/admin/users/" + victim.id() + "/unban",
                java.util.Map.of("reason", "申诉通过"));
        assertThat(unban.jsonPath().getInt("code")).isZero();
        assertThat(userColumn(victim.id(), "status", Integer.class)).isEqualTo(1);
        String newToken = login(victim.username());
        Response recovered = RestAssured.given()
                .header("Authorization", newToken)
                .get("/api/user/me");
        assertThat(recovered.jsonPath().getInt("code"))
                .as("解封后用户必须恢复正常。响应：%s", recovered.asString()).isZero();
        assertThat(adminGet("/api/admin/logs?action=USER_UNBAN&size=20")
                .jsonPath().<java.util.List<Integer>>get("data.list.targetId"))
                .as("USER_UNBAN 也要留痕").contains((int) victim.id());
    }

    @Test
    @DisplayName("M6_user_ban_edge_cases：不存在的用户 404；关键词搜索能定位目标用户")
    void M6_user_ban_edge_cases() {
        assertThat(adminPut("/api/admin/users/99999999/ban",
                java.util.Map.of("reason", "不存在")).jsonPath().getInt("code")).isEqualTo(404);

        // 搜索：完整用户名作为关键词（LIKE 查询，含 % 也不该报错 —— 转义生效）
        Response list = adminGet("/api/admin/users?keyword=" + victim.username() + "&size=20");
        assertThat(list.jsonPath().getInt("code")).isZero();
        assertThat(list.jsonPath().<java.util.List<Integer>>get("data.list.id"))
                .as("按用户名搜索必须能定位目标").contains((int) victim.id());

        // 搜索含 LIKE 通配符的关键词不炸（转义路径）
        Response wildcard = adminGet("/api/admin/users?keyword=%25%5F&size=20");
        assertThat(wildcard.jsonPath().getInt("code"))
                .as("含通配符的关键词必须被安全转义。响应：%s", wildcard.asString()).isZero();
    }
}
