package com.hyforum.user;

import com.hyforum.interaction.M4ApiTestSupport;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §14 头像 + 简介的用例（任务书 §14.3、§14.4；测试名由 L1 预登记，**不得改名**）。
 *
 * <h2>两个用例各自在防什么</h2>
 * <ul>
 *   <li>{@code M4_profile_update_overwrites_and_rejects_non_editable} ——
 *       <b>防"界面在说谎"</b>：PUT 是覆盖（省略即清空），
 *       且请求体里出现不可编辑字段必须 <b>400</b>（静默忽略会让用户以为改成功了）；</li>
 *   <li>{@code M4_avatar_url_must_be_in_own_directory} ——
 *       <b>防"前台去渲染别人服务器上的图"</b>（盗链 + 隐私泄露 + 审核面失控）。
 *       这条不许放宽（§14.3 第 4 条），而且**反证比正证更重要**：
 *       只验"自己的 URL 能被接受"是验不出校验存在的，
 *       必须验"别人的目录 / 外部域名被拒"。</li>
 * </ul>
 */
class M4ProfileUpdateTest extends M4ApiTestSupport {

    /** 本类用到的 OSS 前缀（与 application-test.yml 的 aliyun.oss 配置一致）。 */
    private static final String OSS_PUBLIC_PREFIX =
            "https://hy-forum-2026.oss-cn-beijing.aliyuncs.com/";

    private TestUser me;
    private TestUser other;

    /**
     * 造用户时 {@code TestFixtures.insertUser(nickname)} 写入的昵称前缀，
     * 之后再取"别人当前昵称"用于反证（{@code TestUser} 只带 id/username/token，不带 nickname）。
     */
    private String otherNicknameBefore;

    @BeforeEach
    void seed() {
        me = createUser("本人");
        other = createUser("别人");
        otherNicknameBefore = userColumn(other.id(), "nickname", String.class);
        assertThat(otherNicknameBefore).as("前提：别人的昵称必须能查出来，否则下面的反证没有意义")
                .isNotNull();
    }

    /** 本人头像目录下的一个合法对象地址。 */
    private String myAvatar() {
        return OSS_PUBLIC_PREFIX + "avatar/" + me.id() + "/a.jpg";
    }

    /** **别人**头像目录下的对象地址（我要用它证伪）。 */
    private String othersAvatar() {
        return OSS_PUBLIC_PREFIX + "avatar/" + other.id() + "/b.jpg";
    }

    // ==================================================================
    // ① 覆盖语义 + 非可编辑字段必须 400
    // ==================================================================

    @Test
    @DisplayName("M4_profile_update_overwrites_and_rejects_non_editable：覆盖（省略即清空）+ 非可编辑字段 400")
    void M4_profile_update_overwrites_and_rejects_non_editable() {
        // ---------- 覆盖语义：先设全，再只传 nickname，其余必须被**清空** ----------
        Response first = putProfile(me.token(), fields("新昵称", myAvatar(), "我的简介", 1));
        assertOk(first);
        assertThat(first.jsonPath().getString("data.nickname")).isEqualTo("新昵称");
        assertThat(first.jsonPath().getString("data.bio")).as("简介应当写入").isEqualTo("我的简介");
        assertThat(first.jsonPath().getInt("data.gender")).as("性别应当写入").isEqualTo(1);
        assertThat(first.jsonPath().getString("data.avatarUrl"))
                .as("头像 URL 应当写入。响应：%s", first.asString())
                .isNotNull();

        // 只传 nickname → avatarUrl/bio 清空、gender 回 0（PUT=覆盖，省略即清空）
        Response second = putProfile(me.token(), fields("只改昵称", null, null, null));
        assertOk(second);
        assertThat(second.jsonPath().getString("data.nickname")).isEqualTo("只改昵称");
        assertThat((Object) second.jsonPath().get("data.avatarUrl"))
                .as("**省略 avatarUrl 必须清空头像**（PUT=覆盖）—— 若实现成'不传就保持原值'，"
                        + "'清空头像'就变成了一个做不到的操作。响应：%s", second.asString())
                .isNull();
        assertThat((Object) second.jsonPath().get("data.bio"))
                .as("省略 bio 必须清空简介").isNull();
        assertThat(second.jsonPath().getInt("data.gender"))
                .as("省略 gender 按 0（未设置）").isZero();

        // 落库也要一致（接口返回值不能只是"看起来清空了"）
        assertThat(userColumn(me.id(), "avatar_url", String.class)).isNull();
        assertThat(userColumn(me.id(), "bio", String.class)).isNull();

        // ---------- 不可编辑字段必须 400（**不是静默忽略**）----------
        // 逐个验：只验一个的话，"漏了另一个"就永远发现不了
        String[][] forbidden = {
                {"username", "\"hacker\""},
                {"password", "\"newpass123\""},
                {"role", "\"SUPER_ADMIN\""},
                {"id", "999"},
        };
        for (String[] pair : forbidden) {
            String body = "{\"nickname\":\"改名尝试\",\"" + pair[0] + "\":" + pair[1] + "}";
            Response rejected = RestAssured.given()
                    .header("Authorization", me.token())
                    .contentType(ContentType.JSON)
                    .body(body)
                    .put("/api/user/profile");
            assertThat(rejected.jsonPath().getInt("code"))
                    .as("请求体里出现 `%s` 必须 400 —— **不是静默忽略**。"
                            + "静默忽略会让客户端以为改成功了（本项目反复吃过的'界面在说谎'）。"
                            + "响应：%s", pair[0], rejected.asString())
                    .isEqualTo(400);
        }

        // 关键反证：四次被拒之后，昵称必须**没变**（还是第二步那个值）——
        // 只看状态码不够：一个"先改后拒"的实现也能返回 400，而数据已经被改了
        assertThat(userColumn(me.id(), "nickname", String.class))
                .as("被 400 拒绝的请求**不得**改动任何字段").isEqualTo("只改昵称");

        // ---------- 其他基本约束 ----------
        // 昵称必填
        Response noNickname = RestAssured.given()
                .header("Authorization", me.token())
                .contentType(ContentType.JSON)
                .body("{\"bio\":\"只有简介\"}")
                .put("/api/user/profile");
        assertThat(noNickname.jsonPath().getInt("code")).as("昵称必填").isEqualTo(400);

        // 未登录 → 401（本端点没有"匿名改资料"的语义）
        Response anonymous = RestAssured.given()
                .contentType(ContentType.JSON)
                .body(fields("匿名改名", null, null, null))
                .put("/api/user/profile");
        assertThat(anonymous.statusCode()).as("匿名改资料必须 401").isEqualTo(401);

        // 反向自证：**只改自己** —— 我改完不能影响别人
        assertThat(userColumn(other.id(), "nickname", String.class))
                .as("改自己的资料绝不能动到别人（请求体里没有 userId，改的必须是登录态那个人）")
                .isEqualTo(otherNicknameBefore);
    }

    // ==================================================================
    // ② 头像归属校验（不许放宽）
    // ==================================================================

    @Test
    @DisplayName("M4_avatar_url_must_be_in_own_directory：只接受本人 avatar/{自己id}/ 目录下的对象")
    void M4_avatar_url_must_be_in_own_directory() {
        // ---------- 正证：自己目录下的对象必须被接受 ----------
        Response ok = putProfile(me.token(), fields("本人", myAvatar(), null, null));
        assertOk(ok);
        assertThat(ok.jsonPath().getString("data.avatarUrl"))
                .as("自己目录下的头像必须被接受。响应：%s", ok.asString())
                .isNotNull();

        // ---------- 反证 1：**别人的**头像目录 → 必须拒 ----------
        // 这是"按用户分目录"存在的**唯一理由**：不带 userId 的话，
        // A 可以把头像设成 B 上传的任意对象
        Response othersDir = putProfile(me.token(), fields("本人", othersAvatar(), null, null));
        assertThat(othersDir.jsonPath().getInt("code"))
                .as("**别人头像目录下的对象必须被拒**（§14.2 ①：否则 A 能用 B 的对象当头像）。"
                        + "响应：%s", othersDir.asString())
                .isEqualTo(400);

        // ---------- 反证 2：外部域名 → 必须拒 ----------
        Response external = putProfile(me.token(),
                fields("本人", "https://evil.example.com/steal.jpg", null, null));
        assertThat(external.jsonPath().getInt("code"))
                .as("**外部 URL 必须被拒** —— 否则前台会去渲染别人服务器上的图"
                        + "（盗链 + 隐私泄露 + 图片审核面失控），而日志里一个错都没有。"
                        + "响应：%s", external.asString())
                .isEqualTo(400);

        // ---------- 反证 3：本项目 OSS，但**帖子目录** ----------
        // 这条防"目录校验写成了'只要在本项目 OSS 下就行'"这种放宽 ——
        // 那等于把头像与帖子图混成同一个池子
        Response postDir = putProfile(me.token(),
                fields("本人", OSS_PUBLIC_PREFIX + "post/other-users-post.jpg", null, null));
        assertThat(postDir.jsonPath().getInt("code"))
                .as("本项目 OSS 的**帖子目录**下的对象也不能当头像（目录校验被放宽成"
                        + "'在本项目 OSS 下就行'时，这条会红）。响应：%s", postDir.asString())
                .isEqualTo(400);

        // ---------- 反证 4：空串/空白 → 视为清空，不是非法值 ----------
        Response blank = putProfile(me.token(), fields("本人", "   ", null, null));
        assertOk(blank);
        assertThat((Object) blank.jsonPath().get("data.avatarUrl"))
                .as("空串应当被当成'清空'而不是'非法地址'（前端清空头像时最自然的传法）").isNull();

        // 关键：三次被拒之后库里应当还是正证那一次写入的值（或已被最后的清空置 null），
        // 但绝不能是别人目录/外部域名那个值
        String stored = userColumn(me.id(), "avatar_url", String.class);
        assertThat(stored)
                .as("库里绝不能出现被拒的那些地址（先写后拒的实现会在这里露出来）")
                .isNull();
    }

    // ==================================================================
    // 小工具
    // ==================================================================

    /** 组装请求体；null 值**不放进 map**（= 省略该字段，用于验"省略即清空"）。 */
    private static Map<String, Object> fields(String nickname, String avatarUrl,
                                              String bio, Integer gender) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (nickname != null) {
            body.put("nickname", nickname);
        }
        if (avatarUrl != null) {
            body.put("avatarUrl", avatarUrl);
        }
        if (bio != null) {
            body.put("bio", bio);
        }
        if (gender != null) {
            body.put("gender", gender);
        }
        return body;
    }

    private Response putProfile(String token, Map<String, Object> body) {
        return RestAssured.given()
                .header("Authorization", token)
                .contentType(ContentType.JSON)
                .body(body)
                .put("/api/user/profile");
    }
}
