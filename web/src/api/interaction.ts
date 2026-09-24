/**
 * 互动接口：帖子点赞/收藏、关注、评论点赞（`openapi.json` 的 M4/M5 部分）。
 *
 * ==========================================================================
 * 2026-09-24 契约变更：**四个写端点现在会返回状态了**（CR-R）
 * ==========================================================================
 * 契约原文：`POST/DELETE /api/posts/{id}/like`、`POST/DELETE /api/posts/{id}/collect`
 * 的响应是 **`InteractionStateVO`**：
 *
 * ```
 * { liked: boolean, collected: boolean, likeCount: number, collectCount: number }
 * ```
 *
 * 所以**不要再自己推算计数**（并发下本地推算会偏）：
 * 调用方拿到返回值后**用它覆盖界面**，失败才回滚。
 * `liked`/`collected` 都是**相对于当前登录者**（未登录一律 false）。
 *
 * 其余两条没变的性质仍然成立：
 * 1. 全部**幂等**（《技术方案》§8.1）：重复 POST/DELETE 不会造成数据错误；
 * 2. 未登录时后端返回 `401` → `request` 层抛 `ApiError`，页面据此引导登录。
 */

import { del, post } from '@/utils/request'
import { ENDPOINTS } from './contract'
import { expectObject } from './shape'
import type { InteractionStateVO } from './types'

/** 点赞/收藏这类"切换"端点的公共实现（POST = 打开，DELETE = 关闭） */
async function toggleInteraction(on: boolean, path: string): Promise<InteractionStateVO> {
  const data = on
    ? await post<unknown>(path, undefined, { withAuth: true })
    : await del<unknown>(path, { withAuth: true })
  // ⚠️ 用 `expectObject` 收敛：契约保证有 body，形状不对要显式报错，
  //    而不是让调用方读到 `undefined.likeCount` 才发现（那样根因会被埋掉）
  return expectObject<InteractionStateVO>(data, on ? '点赞/收藏' : '取消点赞/收藏')
}

/**
 * 点赞 / 取消点赞（幂等）。**返回服务端的新状态与计数** —— 调用方应以它为准。
 * 未登录时后端返回 `401` → `ApiError`，页面据此引导登录。
 */
export function likePost(postId: number, on = true): Promise<InteractionStateVO> {
  return toggleInteraction(on, ENDPOINTS.postLike.path.replace('{id}', String(postId)))
}

/** 收藏 / 取消收藏（幂等）。**返回服务端的新状态与计数** */
export function collectPost(postId: number, on = true): Promise<InteractionStateVO> {
  return toggleInteraction(on, ENDPOINTS.postCollect.path.replace('{id}', String(postId)))
}

/**
 * 关注 / 取关某用户（幂等）。契约里禁止关注自己（后端会拒）。
 *
 * ⚠️ 这个端点**仍然没有返回值**（`ApiResponseVoid`）—— 契约只给帖子点赞/收藏加了返回体。
 * 所以关注态的更新方式与它们不同：以 `UserProfileVO.isFollowing` 为准（读接口），
 * 点击后本地翻转、失败回滚。
 */
export function followUser(userId: number, on = true): Promise<void> {
  const path = ENDPOINTS.follow.path.replace('{userId}', String(userId))
  return on ? post<void>(path, undefined, { withAuth: true }) : del<void>(path, { withAuth: true })
}

/**
 * 评论点赞 / 取消（幂等）。
 *
 * ⚠️ 同样**没有返回值**，而且契约里**没有评论的 `liked` 字段**
 * （`CommentItemVO`/`CommentReplyVO` 都没有）→ 所以评论的点赞态只能"会话内记住"，
 * 刷新即丢。这是契约缺口（与帖子点赞已有服务端状态不是一回事），已在报告里登记。
 */
export function likeComment(commentId: number, on = true): Promise<void> {
  const path = ENDPOINTS.commentLike.path.replace('{id}', String(commentId))
  return on ? post<void>(path, undefined, { withAuth: true }) : del<void>(path, { withAuth: true })
}
