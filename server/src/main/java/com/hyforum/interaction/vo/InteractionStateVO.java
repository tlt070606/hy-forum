package com.hyforum.interaction.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 点赞 / 收藏操作后的**互动状态**（CR-R，2026-09-20 L1 裁决）。
 *
 * <h2>为什么要有这个 VO</h2>
 * <p>点完赞/收藏后，前端必须知道三件事才能把按钮画对：</p>
 * <ol>
 *   <li><b>我现在的状态是什么</b>（{@code liked} / {@code collected}）——
 *       "点成功了"不等于"现在是已点赞"（取消后再点、重复点都要能推导出正确状态）；</li>
 *   <li><b>最新的计数</b>（{@code likeCount} / {@code collectCount}）——
 *       否则前端只能自己 +1，于是"重复点赞"会显示成 +2（而服务端只加了一次）；</li>
 *   <li>将来若加了"取消"按钮，同一套字段也能复用。</li>
 * </ol>
 * <p>此前这四个端点只返回 {@code {"code":0,"message":"ok"}} —— 前端<b>拿不到任何状态</b>，
 * 按钮只能靠猜或再发一次查询。CR-R 就是补这个。</p>
 *
 * <h2>为什么计数从库里读、不在服务层里"算"</h2>
 * <p>服务层确实知道"这次有没有真的插入关系行"，但它<b>不知道并发下最终值</b>：
 * 另一个请求可能在同一瞬间也点了赞。因此计数一律<b>从库里读当前值</b> ——
 * 与列表接口的口径完全一致，前端两个来源不会有差异。</p>
 *
 * @param liked        当前请求者操作后是否已点赞
 * @param collected    当前请求者操作后是否已收藏
 * @param likeCount    帖子当前点赞数（库里的值，不是"我这次 +1"）
 * @param collectCount 帖子当前收藏数
 */
@Schema(name = "InteractionStateVO", description = "点赞/收藏操作后的互动状态（供前端直接更新按钮与计数）")
public record InteractionStateVO(
        @Schema(description = "操作后，当前用户是否已点赞")
        Boolean liked,
        @Schema(description = "操作后，当前用户是否已收藏")
        Boolean collected,
        @Schema(description = "帖子当前点赞数（库里读，非本地推算）")
        Integer likeCount,
        @Schema(description = "帖子当前收藏数（库里读，非本地推算）")
        Integer collectCount) {
}
