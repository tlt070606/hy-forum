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
 *       这样"回调打到哪、回调体长什么样"由后端单点决定。</li>
 * </ul>
 *
 * <p><b>本类不含、也绝不允许含 AccessKey Secret。</b> 表单里需要的 {@code OSSAccessKeyId}
 * 属另一件事（它是标识、不是密钥，但仍<b>不在本契约的字段里</b>）——
 * 本段已把这一处契约缺口作为 CR 提给 L1，见交付报告（CR-F）。</p>
 *
 * @param host      直传的表单 action 域名（{@code https://{bucket}.{endpoint}}）
 * @param policy    Base64 的 policy
 * @param signature Base64 的签名
 * @param dir       对象 key 目录前缀（带尾斜杠）
 * @param expire    policy 到期时刻（epoch 秒）
 * @param callback  Base64 的回调配置 JSON
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
        String callback) {
}
