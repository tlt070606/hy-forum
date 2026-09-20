/**
 * 通知接口（M5）。
 *
 *   GET /api/notifications?type&page&size   我的消息列表
 *   GET /api/notifications/unread-count     未读条数（顶栏红点）
 *   PUT /api/notifications/read             标记已读（传 id 列表，或 all=true）
 *
 * 依据 `docs/技术方案.md` §6.10 与 `openapi.json`（35 路径 / 63 schema）。
 * 三个接口**都要登录**，所以 `withAuth: true`；但**不清本地 token**
 * （取不到通知只说明没登录，不该把用户踢下线 —— 与收藏同一个口径）。
 */

import { get, put } from '@/utils/request'
import { ENDPOINTS } from './contract'
import { expectNumber, expectPage } from './shape'
import type { MarkReadRequest, NotificationVO } from './types'

const PAGE_SIZE_MAX = 20

function normalizeSize(size?: number): number {
  if (!size || size <= 0) return PAGE_SIZE_MAX
  return Math.min(size, PAGE_SIZE_MAX)
}

function normalizePage(page?: number): number {
  if (!page || page < 1) return 1
  return page
}

/** 通知列表（分页）。`type` 不传 = 全部；取值 1点赞/2评论/3回复/4关注/5系统 */
export async function fetchNotifications(
  type?: number | null,
  page?: number,
  size?: number
): Promise<{ list: NotificationVO[]; total: number }> {
  const query: Record<string, number> = { page: normalizePage(page), size: normalizeSize(size) }
  // `type` 只有真的要筛选时才带上：传 0/null 会被后端当成"筛 type=0"，那种类型不存在，会得到空列表
  if (typeof type === 'number' && type > 0) query.type = type

  const data = await get<unknown>(ENDPOINTS.notifications.path, query, {
    withAuth: true,
    clearAuthOn401: false,
  })
  const res = expectPage<NotificationVO>(data, '通知列表')
  return { list: res.list, total: res.total }
}

/**
 * 未读条数。
 *
 * ⚠️ 这个接口的 `data` 是**一个裸数字**（`ApiResponseLong`），不是对象 ——
 * 所以要走 `expectNumber`，用 `expectObject` 会直接抛"形状不对"。
 * 传 `0` 也要能正确显示（"没有未读"与"取不到"是两件事，见通知页的处理）。
 */
export async function fetchUnreadCount(): Promise<number> {
  const data = await get<unknown>(ENDPOINTS.notificationsUnreadCount.path, {}, {
    withAuth: true,
    clearAuthOn401: false,
  })
  return expectNumber(data, '未读数')
}

/**
 * 标记已读。返回**被标记的条数**。
 *
 * 契约 `MarkReadRequest = { ids?: number[]; all?: boolean }`：
 * - 单条/多条 → `{ ids: [...] }`
 * - 全部已读 → `{ all: true }`
 * 两者都传时以后端语义为准（这里只按调用方的意图传一种，不制造歧义）。
 */
export async function markNotificationsRead(payload: MarkReadRequest): Promise<number> {
  const data = await put<unknown>(ENDPOINTS.notificationsRead.path, payload, {
    withAuth: true,
    clearAuthOn401: false,
  })
  return expectNumber(data, '标记已读结果')
}
