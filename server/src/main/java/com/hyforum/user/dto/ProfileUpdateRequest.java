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
 * <p><b>为什么用 {@code @JsonIgnoreProperties(ignoreUnknown = false)} 而不是在方法体里逐个检查</b>：
 * 逐个检查要求"穷举所有不可编辑字段"，而 {@code user} 表有 20 列 ——
 * 漏掉任何一列（例如 {@code points}、{@code level}、{@code status}）就是一个静默忽略的缺口。
 * 反过来做（默认拒绝一切未声明字段）则由**结构**保证：DTO 里只有那 4 个字段，
 * 其余任何 key 都会被 Jackson 拒绝。<b>白名单比黑名单可靠。</b></p>
 *
 * <p>代价（如实记录）：客户端若"顺手"多传一个前端内部字段（例如 {@code _dirty}），
 * 也会被 400。这是刻意的取舍 —— 宁可让调用方改掉多余的 key，
 * 也不要让"改了但没生效"这种事悄悄发生。</p>
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
@JsonIgnoreProperties(ignoreUnknown = false)
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
