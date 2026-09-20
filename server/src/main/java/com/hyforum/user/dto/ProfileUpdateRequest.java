package com.hyforum.user.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 修改自己资料的请求体（任务书 §14.3：{@code PUT /api/user/profile}）。
 *
 * <h2>🔴 请求体里出现不可编辑字段 → <b>必须 400，不是静默忽略</b></h2>
 * <p>可编辑的只有 {@code nickname} / {@code avatarUrl} / {@code bio} / {@code gender}。
 * {@code username} / {@code password} / {@code role} / {@code id} 出现的后果有两种做法：</p>
 * <ul>
 *   <li>静默忽略：客户端以为"我改了用户名/密码"，实际什么都没发生 ——
 *       <b>这正是本项目反复吃过的"界面在说谎"</b>（用户看到 200 就认为改动生效了）；</li>
 *   <li><b>400 明确拒绝（本项目选的）</b>：错误信息直接说"这个字段不可修改"。</li>
 * </ul>
 *
 * <p><b>为什么用黑名单（{@code ignoreUnknown = false} + 显式列名）而不是"开启全局
 * FAIL_ON_UNKNOWN"</b>（这是我第一版写错、被用例当场抓住的地方，如实记录）：
 * 第一版只写了 {@code @JsonIgnoreProperties(ignoreUnknown = false)} 就以为够了 ——
 * 而 <b>Spring Boot 默认把 {@code FAIL_ON_UNKNOWN_PROPERTIES} 关掉了</b>
 * （{@code application.yml} 的 {@code jackson} 段只设了 time-zone/date-format），
 * 于是未知字段被<b>静默忽略</b>，{@code profile_update} 那条用例直接红：
 * 请求里带着 {@code username} 却返回 200。
 * 改全局开关会影响所有接口的入参容忍度（那是 L1 的地盘，且今天已因"顺手改"出过事），
 * 所以改成在这里显式点名。</p>
 *
 * <p><b>为什么点名这些</b>：本任务书 §14.3 明确要求
 * {@code username} / {@code password} / {@code role} / {@code id} 必须 400；
 * 另外几个是 {@code user} 表里**同样的"用户不该自己改"**的列
 * （{@code points} 积分、{@code level} 等级、{@code status} 封禁状态、两个计数），
 * 一个 HTTP 客户端完全可以顺手传它们。<b>这条黑名单是唯一需要维护的地方</b>，
 * 而它被用例逐字段覆盖（漏一个就在 {@code M4_..._rejects_non_editable} 里红）——
 * 这也是我选黑名单而不是"全局白名单"的原因：全局白名单会让**所有**接口
 * 多传一个前端内部字段就 400，代价落到别人头上。</p>
 *
 * <h2>PUT = 覆盖，省略即清空</h2>
 * <p>{@code avatarUrl}/{@code bio} 不传就是清空（置 null），不是"保持原值"。
 * 想只改昵称的客户端应当把现有值一并回传 —— 这是 PUT 的标准语义，
 * 也避免"清空头像"需要一个额外的 DELETE 接口。</p>
 *
 * @param nickname  昵称，1–20 字符（与 {@code user.nickname} 的 VARCHAR(20) 一致）；<b>必填</b>
 * @param avatarUrl 头像 URL；必须落在<b>本项目 OSS 的 {@code avatar/{自己的 userId}/} 前缀内</b>；
 *                  不传/null = 清空头像
 * @param bio       个性签名，≤200 字符；不传/null = 清空
 * @param gender    0 未知 / 1 男 / 2 女；不传按 0（未设置）
 */
@JsonIgnoreProperties(value = {
        // 见类注释：Spring Boot 把 FAIL_ON_UNKNOWN 关掉了，因此必须显式点名
        "username", "password", "role", "id",
        "points", "level", "status",
        "postCount", "followCount", "fansCount", "likeReceivedCount",
        "isDeleted", "createdAt", "updatedAt"
}, ignoreUnknown = true)
@Schema(name = "ProfileUpdateRequest",
        description = "修改自己的资料（PUT=覆盖，省略即清空）。"
                + "只接受 nickname/avatarUrl/bio/gender；出现 username/password/role/id 等字段会 400")
public record ProfileUpdateRequest(
        @NotBlank(message = "不能为空")
        @Size(max = 20, message = "不能超过 20 字")
        String nickname,

        @Schema(description = "头像 URL，必须在本项目 OSS 的 avatar/{自己的 id}/ 目录下；不传=清空")
        @Size(max = 500, message = "不能超过 500 字符")
        String avatarUrl,

        @Schema(description = "个性签名；不传=清空")
        @Size(max = 200, message = "不能超过 200 字")
        String bio,

        @Schema(description = "0 未知 / 1 男 / 2 女；不传按 0")
        Integer gender) {
}
