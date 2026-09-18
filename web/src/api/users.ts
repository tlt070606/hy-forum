/**
 * 用户相关接口（个人主页 / 关注粉丝 / 我的收藏 / 信息流）。
 *
 * 全部依据重导后的 `openapi.json`（29 路径 / 52 schema），字段名逐字对应。
 *
 *   GET /api/users/{id}            资料（**带 `isFollowing` / `isFollowedBy`**）
 *   GET /api/users/{id}/posts      他发的帖（FeedItemVO 分页）
 *   GET /api/users/{id}/comments   他发的评论（带 postTitle，可跳回原帖）
 *   GET /api/users/{id}/follows    他关注的人
 *   GET /api/users/{id}/fans       他的粉丝
 *   GET /api/user/collections      我的收藏（**需登录**）
 *   GET /api/feed?type=follow|all  信息流（关注流 / 全部流）
 *
 * ⚠️ 除了 `/api/user/collections` 必须登录，其余都是免登录可看的（未登录也能看别人的主页）。
 *    所以这些请求用 `clearAuthOn401: false`：**一个 401 不该把用户踢下线**。
 */

import { get } from '@/utils/request'
import { ENDPOINTS } from './contract'
import { expectObject, expectPage } from './shape'
import type {
  CollectionItemVO,
  FeedItemVO,
  FeedSort,
  FeedType,
  FollowUserVO,
  UserCommentVO,
  UserProfileVO,
} from './types'

/** 契约里列表每页上限 20（§6.1），这里再兜一层，避免调用方传大 */
const PAGE_SIZE_MAX = 20

function normalizeSize(size?: number): number {
  if (!size || size <= 0) return PAGE_SIZE_MAX
  return Math.min(size, PAGE_SIZE_MAX)
}

function normalizePage(page?: number): number {
  if (!page || page < 1) return 1
  return page
}

/** 统一的"分页响应 → {list,total}"收敛（`size`/`page` 前端不用） */
async function getPage<T>(
  path: string,
  query: Record<string, string | number | boolean | null | undefined>,
  label: string,
  withAuth: boolean
): Promise<{ list: T[]; total: number }> {
  const data = await get<unknown>(path, query, { withAuth, clearAuthOn401: false })
  const res = expectPage<T>(data, label)
  return { list: res.list, total: res.total }
}

/** 个人主页资料 */
export async function fetchUserProfile(userId: number): Promise<UserProfileVO> {
  const path = ENDPOINTS.userProfile.path.replace('{id}', String(userId))
  const data = await get<unknown>(path, {}, { clearAuthOn401: false })
  return expectObject<UserProfileVO>(data, '用户资料')
}

/** 他发的帖子 */
export function fetchUserPosts(userId: number, page?: number, size?: number) {
  const path = ENDPOINTS.userPosts.path.replace('{id}', String(userId))
  return getPage<FeedItemVO>(path, { page: normalizePage(page), size: normalizeSize(size) }, '用户帖子', false)
}

/** 他发的评论（带上 `postTitle`，用于跳回原帖） */
export function fetchUserComments(userId: number, page?: number, size?: number) {
  const path = ENDPOINTS.userComments.path.replace('{id}', String(userId))
  return getPage<UserCommentVO>(path, { page: normalizePage(page), size: normalizeSize(size) }, '用户评论', false)
}

/** 他关注的人 */
export function fetchUserFollows(userId: number, page?: number, size?: number) {
  const path = ENDPOINTS.userFollows.path.replace('{id}', String(userId))
  return getPage<FollowUserVO>(path, { page: normalizePage(page), size: normalizeSize(size) }, '关注列表', false)
}

/** 他的粉丝 */
export function fetchUserFans(userId: number, page?: number, size?: number) {
  const path = ENDPOINTS.userFans.path.replace('{id}', String(userId))
  return getPage<FollowUserVO>(path, { page: normalizePage(page), size: normalizeSize(size) }, '粉丝列表', false)
}

/**
 * 我的收藏（**需登录**）。
 * ⚠️ 这里是 `withAuth: true` + `clearAuthOn401: false`：
 *    取不到收藏只说明没登录，**不该顺手把本地 token 清掉**
 *    （那会让"未登录点收藏"变成"把用户已登录的状态也清掉"）。
 */
export function fetchMyCollections(page?: number, size?: number) {
  return getPage<CollectionItemVO>(
    ENDPOINTS.collections.path,
    { page: normalizePage(page), size: normalizeSize(size) },
    '我的收藏',
    true
  )
}

/**
 * 信息流。
 *
 * `type`：`follow` = 关注流（需登录）、`all` = 全部流（免登录）。
 * `sort`：与 `GET /api/posts` 同口径（latest / hot）。
 *
 * ⚠️ 契约里 `type` 与 `sort` 都是**裸 string，没有 enum**（见 `types.ts` 的说明），
 *    取值来自《技术方案》§8.5，已登记为契约改进建议。
 */
export function fetchFeed(
  type: FeedType,
  sort: FeedSort,
  page?: number,
  size?: number,
  withAuth = false
) {
  return getPage<FeedItemVO>(
    ENDPOINTS.feed.path,
    { type, sort, page: normalizePage(page), size: normalizeSize(size) },
    '信息流',
    withAuth
  )
}
