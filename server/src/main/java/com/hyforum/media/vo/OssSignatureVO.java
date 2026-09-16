package com.hyforum.media.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * OSS 直传签名（docs/技术方案.md §6.8：{@code GET /api/oss/signature}）。
 *
 * <p>字段与顺序<b>逐字对应契约</b>：{@code {host, policy, signature, dir, expire, callback}} ——
 * 一个不多、一个不少。</p>
 *
 * <h2>各字段的形态（2026-09-16 L1 裁决 CR-B / CR-C，勿再自行更改）</h2>
 * <ul>
 *   <li>{@code host}：{@code https://{bucket}.{endpoint}}，<b>不带结尾斜杠</b>（前端拼
 *       {@code host + "/" + key} 或用它作表单 action）；</li>
 *   <li>{@code policy}：Base64 的 policy JSON（含 {@code expiration} 与
 *       {@code starts-with $key} 目录约束）；</li>
 *   <li>{@code signature}：{@code Base64(HMAC-SHA1(accessKeySecret, policy))}；</li>
 *   <li>{@code dir}：对象 key 的目录前缀，<b>带结尾斜杠</b>（如 {@code post/}），
 *       与后端发帖时校验的图片前缀<b>同源</b>；</li>
 *   <li>{@code expire}：policy 到期时刻的 <b>epoch 秒</b>（绝对时刻，前端 {@code new Date(expire*1000)}）；</li>
 *   <li>{@code callback}：Base64 的回调配置 JSON（含 {@code callbackUrl} / {@code callbackBody} /
 *       {@code callbackBodyType}），前端把它<b>原样</b>作为 {@code callback} 表单字段传给 OSS ——
 *       这样"回调打到哪、回调体长什么样"由后端单点决定；</li>
 *   <li>{@code accessKeyId}：<b>CR-F（2026-09-16 批准）新增</b>。PostObject 表单<b>必须</b>带
 *       {@code OSSAccessKeyId}，否则前端根本无法完成一次直传（这不是可选字段）。
 *       它是<b>标识不是密钥</b>：只有 Secret 才能签名，而 Secret 全程只在服务端。
 *       取值只来自环境变量（{@code OSS_ACCESS_KEY_ID}），<b>代码与本类都不给它任何默认值</b>；
 *       缺失时应用启动即失败（{@code OssCredentialProperties} 的 {@code @NotBlank}）。</li>
 * </ul>
 *
 * <p><b>本类不含、也绝不允许含 AccessKey Secret。</b> 这条由用例守着：
 * {@code M3_oss_signature_requires_login} 会断言响应体里<b>不存在 Secret 的任何 6 字符以上子串</b>。</p>
 *
 * <p><b>字段顺序说明</b>：{@code accessKeyId} 刻意追加在末尾（而不是插在 {@code signature} 旁边），
 * 这样原有 6 个字段的顺序在契约里<b>逐字不变</b> —— 前端已按旧顺序生成过类型与快照，
 * 顺序稳定可以让这次契约变更的 diff 最小、前端重生成后的差异只聚焦在"多了一个字段"上。</p>
 *
 * @param host      直传的表单 action 域名（{@code https://{bucket}.{endpoint}}）
 * @param policy    Base64 的 policy
 * @param signature Base64 的签名
 * @param dir       对象 key 目录前缀（带尾斜杠）
 * @param expire    policy 到期时刻（epoch 秒）
 * @param callback  Base64 的回调配置 JSON
 * @param accessKeyId 直传表单的 {@code OSSAccessKeyId} 字段值（**CR-F**，2026-09-16 批准加入）
 */
@Schema(name = "OssSignatureVO", description = "OSS 直传签名（PostObject 表单直传）")
public record OssSignatureVO(
        @Schema(description = "直传目标域名：https://{bucket}.{endpoint}（无尾斜杠）")
        String host,
        @Schema(description = "Base64 编码的 policy（含有效期与 key 目录约束）")
        String policy,
        @Schema(description = "Base64 编码的签名：HMAC-SHA1(policy, accessKeySecret)")
        String signature,
        @Schema(description = "对象 key 的目录前缀，带尾斜杠（如 post/）")
        String dir,
        @Schema(description = "policy 到期时刻（epoch 秒）")
        Long expire,
        @Schema(description = "Base64 编码的回调配置 JSON（前端原样作为 callback 表单字段传给 OSS）")
        String callback,
        @Schema(description = "**直传表单必需的 OSSAccessKeyId**：AccessKey 的标识，不是密钥；"
                + "Secret 只在服务端使用，绝不会出现在本响应里")
        String accessKeyId) {
}
