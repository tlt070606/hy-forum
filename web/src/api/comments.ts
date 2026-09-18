/**
 * 评论接口（`openapi.json` 的 M4 部分）。
 *
 *   GET    /api/posts/{id}/comments     主楼分页（每条带**前若干条**楼中楼预览）
 *   GET    /api/comments/{rootId}/replies  某主楼下的全部楼中楼（分页）
 *   POST   /api/comments                发评论 / 回复
 *   DELETE /api/comments/{id}           删自己的评论
 *
 * ==========================================================================
 * 两层结构的口径（P1-3 归并语义，写错会造出"三层")
 * ==========================================================================
 * 契约里 `CommentCreateRequest` 只有 `postId` + `parentId` + `content`：
 * - **发主楼**：只传 `postId`（`parentId` 不传或 0）
 * - **回复主楼 / 回复某条楼中楼**：`parentId` **一律填那条「主楼」的 id**
 *   —— 楼中楼的 `parent_id` 与 `root_id` **恒等于所属主楼的 id**。
 *   所以"回复楼中楼里的某人"在数据上也仍然是挂在主楼下的，
 *   **不要在 parentId 里填楼中楼的 id**，那会被后端的唯一写入口校验拒掉
 *   （《技术方案》§6.6 第 2 条：必须校验目标行确实是主楼）。
 *
 * ⚠️ 契约里**没有** `replyToUserId`（技术方案 §6.6 的文字里提到过，但契约没声明），
 *    所以"回复了谁"由后端决定并体现在响应/列表的 `replyToNickname` 里，
 *    前端**不要**自己传一个契约里没有的字段。
 */

import { del, get, post } from '@/utils/request'
import { ENDPOINTS } from './contract'
import { expectObject, expectPage } from './shape'
import type {
  CommentCreateRequest,
  CommentItemVO,
  CommentReplyVO,
  PageResultCommentItemVO,
} from './types'

/** 主楼列表（分页）的收敛形状 */
export interface CommentPage {
  list: CommentItemVO[]
  total: number
}

/** 楼中楼列表（分页）的收敛形状 */
export interface ReplyPage {
  list: CommentReplyVO[]
  total: number
}

/** 列表 `size` 的收敛。与帖子列表同口径（契约 §6.1：每页上限 20） */
const PAGE_SIZE_MAX = 20

function normalizeSize(size?: number): number {
  if (!size || size <= 0) return PAGE_SIZE_MAX
  return Math.min(size, PAGE_SIZE_MAX)
}

function normalizePage(page?: number): number {
  if (!page || page < 1) return 1
  return page
}

/**
 * 取某帖的主楼评论（分页）。
 *
 * 注意响应里的 `replies` 是**预览**（契约 `CommentItemVO.replies`），
 * 要展开看全部楼中楼得调 `fetchReplies(rootId)` —— 不要把预览当成全量。
 */
export async function fetchComments(
  postId: number,
  page?: number,
  size?: number
): Promise<CommentPage> {
  const path = ENDPOINTS.postComments.path.replace('{id}', String(postId))
  const data = await get<unknown>(
    path,
    { page: normalizePage(page), size: normalizeSize(size) },
    { withAuth: true, clearAuthOn401: false }
  )
  const res = expectPage<CommentItemVO>(data, '评论列表')
  return { list: res.list, total: res.total }
}

/**
 * 取某主楼下的**全部**楼中楼（分页）。
 * `rootId` 是**主楼评论的 id**（也就是楼中楼行的 `root_id` 值）。
 */
export async function fetchReplies(
  rootId: number,
  page?: number,
  size?: number
): Promise<ReplyPage> {
  const path = ENDPOINTS.commentReplies.path.replace('{rootId}', String(rootId))
  const data = await get<unknown>(
    path,
    { page: normalizePage(page), size: normalizeSize(size) },
    { withAuth: true, clearAuthOn401: false }
  )
  const res = expectPage<CommentReplyVO>(data, '楼中楼列表')
  return { list: res.list, total: res.total }
}

/**
 * 发表评论（主楼或楼中楼）。
 *
 * `parentId` 的填法见文件头 —— **回复任何一层都填主楼的 id**。
 * 成功返回新建的 `CommentReplyVO`（用它可以把新评论直接插到界面上，不必整页重拉）。
 *
 * ⚠️ 可能返回 `2001 内容包含敏感词`；限流走 `2002`（与发帖同一套口径）。
 */
export async function createComment(payload: CommentCreateRequest): Promise<CommentReplyVO> {
  const data = await post<unknown>(ENDPOINTS.comments.path, payload, {
    withAuth: true,
    clearAuthOn401: true,
  })
  return expectObject<CommentReplyVO>(data, '发表评论')
}

/** 删除评论（仅作者或管理员；契约响应是 `ApiResponseVoid`，不解析返回值） */
export function deleteComment(commentId: number): Promise<void> {
  const path = ENDPOINTS.commentDelete.path.replace('{id}', String(commentId))
  return del<void>(path, { withAuth: true, clearAuthOn401: true })
}

/** 供类型检查用（避免 `PageResultCommentItemVO` 变成未使用导入） */
export type { PageResultCommentItemVO }
