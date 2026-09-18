/**
 * 互动接口：帖子点赞/收藏、关注、评论点赞（`openapi.json` 的 M4 部分）。
 *
 * 本文件只负责"发请求"这一层。**状态的记忆**在 `stores/interaction.ts` ——
 * 因为契约**没有**告诉我"我有没有点过赞"，见该文件的说明。
 *
 * ==========================================================================
 * 两个必须知道的口径
 * ==========================================================================
 * 1. **这些端点全都返回 `ApiResponseVoid`**（没有 `data`）—— 也就是说
 *    **服务端不会把新的计数还给我**。所以调用方拿到成功之后，要么乐观地自己加减，
 *    要么**重新拉一次详情**拿权威计数（详情页选的是后者 + 乐观更新打底）。
 * 2. **全部是幂等的**（《技术方案》§8.1）：重复 POST 点赞不会报错、也不会重复计数；
 *    重复 DELETE 同理。所以"状态记错导致重复点击"**不会造成数据错误**，
 *    只会让本地的激活态短暂不对 —— 这是我可以接受乐观更新的前提。
 */

import { del, post } from '@/utils/request'
import { ENDPOINTS } from './contract'

/** 通用：POST/DELETE 一个无返回值的互动端点 */
function toggle(on: boolean, path: string): Promise<void> {
  return on ? post<void>(path, undefined, { withAuth: true }) : del<void>(path, { withAuth: true })
}

/**
 * 点赞 / 取消点赞（幂等）。
 * 未登录时后端返回 `401` → `request` 层抛 `ApiError`，页面据此引导登录。
 */
export function likePost(postId: number, on = true): Promise<void> {
  return toggle(on, ENDPOINTS.postLike.path.replace('{id}', String(postId)))
}

/** 收藏 / 取消收藏（幂等） */
export function collectPost(postId: number, on = true): Promise<void> {
  return toggle(on, ENDPOINTS.postCollect.path.replace('{id}', String(postId)))
}

/** 关注 / 取关某用户（幂等）。契约里禁止关注自己（后端会拒） */
export function followUser(userId: number, on = true): Promise<void> {
  return toggle(on, ENDPOINTS.follow.path.replace('{userId}', String(userId)))
}

/** 评论点赞 / 取消（幂等） */
export function likeComment(commentId: number, on = true): Promise<void> {
  return toggle(on, ENDPOINTS.commentLike.path.replace('{id}', String(commentId)))
}
