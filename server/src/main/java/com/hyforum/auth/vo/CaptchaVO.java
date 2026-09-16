package com.hyforum.auth.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 图形验证码响应（<b>CR-005</b>，docs/技术方案.md §6.2）。
 *
 * <h2>为什么要有这个类</h2>
 * <p>在此之前 {@code GET /api/auth/captcha} 的 {@code data} 是 {@code Map<String, Object>}，
 * 导出到契约里就成了 {@code Record<string, any>} —— <b>字段名没有任何静态声明</b>。
 * 前端因此被迫加了一层「人工窄化类型 + 运行时校验」的临时层
 * （见 {@code web/src/api/auth.ts} 的注释：它自己写明"这是契约不足，正式做法是提 CR"）。</p>
 * <p>临时层的问题不是"多写了代码"，而是<b>它没有到期日</b>：契约永远缺这两块，
 * 前端永远要多写一层运行时校验，而校验本身一旦与后端漂移，表现是运行时才炸。
 * CR-005 于 2026-09-15 批准把这两个接口具名化（见 docs/agents/工作计划.md §3）。</p>
 *
 * @param uuid         验证码标识（提交注册时回传）
 * @param base64Image  Base64 编码的 PNG（<b>裸 base64，不含 {@code data:image/png;base64,} 前缀</b>，
 *                     前端自行拼接 —— 这一点在前端注释里已明确，故此处保持原样不改口径）
 * @param expireSeconds 有效期秒数（契约值 300，§6.2／§7）
 */
@Schema(name = "CaptchaVO", description = "图形验证码：uuid + 裸 base64 PNG")
public record CaptchaVO(String uuid, String base64Image, Integer expireSeconds) {
}
