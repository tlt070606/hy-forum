package com.hyforum.media.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 回调落库结果（<b>CR-G</b>，2026-09-17 L1 裁决 A）。
 *
 * <h2>为什么回调响应必须"带回结果"</h2>
 * <p>OSS 会把回调的<b>响应体透传给上传方（前端）</b>。在 CR-G 之前这里只回
 * {@code {code:0,message:"ok"}}、没有 {@code data} —— 于是前端<b>拿不到刚上传图片的 URL</b>，
 * 只能自己拼 {@code ${host}${dir}${文件名}}，而那与"前端不许自己拼图片 URL"的既有口径打架；
 * 同时前端也<b>无从知道回调是否成功</b>，只能靠"发帖时后端报 URL 不存在"间接发现。</p>
 *
 * <h2>为什么返回**裸 URL**，而不是签名 URL</h2>
 * <p>三个理由，都不是风格问题：</p>
 * <ol>
 *   <li>{@code url} 的用途是<b>发帖时作为 {@code images} 的取值</b>，而那个值最终要写进
 *       {@code post_image.url} —— 库里必须存裸 URL（签名 URL 会过期）；</li>
 *   <li>发帖时的"认领"是按 <b>URL 精确匹配</b>把 {@code post_id=0} 的行绑到新帖上：
 *       前端若提交签名 URL，就匹配不到回调写下的那一行 → 会多出一行孤儿图；</li>
 *   <li>对外展示用的签名 URL 由<b>读接口</b>在组装响应时现签（{@code OssReadUrlSigner}），
 *       一次签发、处处新鲜，不需要前端参与。</li>
 * </ol>
 *
 * @param id       刚写入的 {@code post_image} 行 id
 * @param url      裸对象 URL（发帖时直接放进 {@code images}）
 * @param thumbUrl 缩略图 URL（列表封面用；规则见 {@code common.oss.OssThumbnailUrls}）
 */
@Schema(name = "OssCallbackResultVO", description = "OSS 回调落库结果（裸 URL，可直接用于发帖）")
public record OssCallbackResultVO(Long id, String url, String thumbUrl) {
}
