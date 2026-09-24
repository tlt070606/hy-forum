/**
 * 通知的视图模型（M5）。
 *
 * ==========================================================================
 * 一条必须说清楚的事：**有些通知点不进去，不是我偷懒**
 * ==========================================================================
 * 契约 `NotificationVO` 给的是 `targetType`(1 帖子 / 2 评论) + `targetId`：
 * - `targetType = 1`（帖子）→ `targetId` 就是**帖子 id** → 能跳详情页 ✓
 * - **2026-09-24 起**：`NotificationVO` 补了 **`postId`** 与 **`commentId`**（CR-N 已落地）——
 *   所以"评论类通知点不进去"这个缺口**没有了**：有 `postId` 就跳帖子（回复类还能带上 `commentId` 定位）。
 *   帖子已删的情形不用前端特判：详情页读不到就是 404，会渲染友好页。 * - 关注类（type=4）没有目标内容，但**可以从 `fromUserId` 跳到对方主页** ✓
 */

import type { NotificationVO } from '@/api/types'
import { relativeTime, text, num, bool } from './postView'

/** 通知类型（契约 description 给出的取值，别人为改编号） */
export const NOTIFY_TYPE = {
  LIKE: 1,
  COMMENT: 2,
  REPLY: 3,
  FOLLOW: 4,
  SYSTEM: 5,
} as const

/** 目标类型 */
export const NOTIFY_TARGET = {
  POST: 1,
  COMMENT: 2,
} as const

/** 类型筛选条。`0` = 不传 `type`（全部） */
export const NOTIFY_FILTERS: Array<{ value: number; label: string }> = [
  { value: 0, label: '全部' },
  { value: NOTIFY_TYPE.LIKE, label: '点赞' },
  { value: NOTIFY_TYPE.COMMENT, label: '评论' },
  { value: NOTIFY_TYPE.REPLY, label: '回复' },
  { value: NOTIFY_TYPE.FOLLOW, label: '关注' },
  { value: NOTIFY_TYPE.SYSTEM, label: '系统' },
]

export interface NotificationView {
  id: number
  type: number
  typeLabel: string
  /** 复用 `HyIcon` 的既有图标名（不新增图标，保持全站一套线性图标） */
  iconType: 'heartFilled' | 'comment' | 'user' | 'bell'
  iconColor: string
  fromUserId: number
  fromName: string
  fromAvatarUrl: string
  /** 展示文案：**优先后端给的 `content`**，为空才兜底 */
  content: string
  isRead: boolean
  timeText: string
  /** 点击跳转地址；空串 = 不可跳（含原因，见 `unreachableReason`） */
  targetUrl: string
  /** 不可跳时的说明（空串 = 能跳） */
  unreachableReason: string
}

/** 各类型的图标与颜色 */
function iconOf(type: number): { iconType: NotificationView['iconType']; color: string } {
  switch (type) {
    case NOTIFY_TYPE.LIKE:
      return { iconType: 'heartFilled', color: '#f53f3f' }
    case NOTIFY_TYPE.COMMENT:
    case NOTIFY_TYPE.REPLY:
      return { iconType: 'comment', color: '#3b82f6' }
    case NOTIFY_TYPE.FOLLOW:
      return { iconType: 'user', color: '#6b4bc4' }
    default:
      return { iconType: 'bell', color: '#ff7d00' }
  }
}

/** 兜底文案（**只在后端 `content` 为空时用**）。按类型拼一句最保守的话，不编细节 */
function fallbackContent(type: number, name: string): string {
  switch (type) {
    case NOTIFY_TYPE.LIKE:
      return `${name} 赞了你的帖子`
    case NOTIFY_TYPE.COMMENT:
      return `${name} 评论了你的帖子`
    case NOTIFY_TYPE.REPLY:
      return `${name} 回复了你`
    case NOTIFY_TYPE.FOLLOW:
      return `${name} 关注了你`
    default:
      return '你有一条新消息'
  }
}

export function toNotificationView(n: NotificationVO): NotificationView {
  const type = num(n.type)
  const targetType = num(n.targetType)
  const targetId = num(n.targetId)
  const fromUserId = num(n.fromUserId)
  const name = text(n.fromNickname).trim() || '有人'
  const { iconType, color } = iconOf(type)

  let targetUrl = ''
  let unreachableReason = ''

  /*
   * 跳转（CR-N，2026-09-24 契约）：`NotificationVO` 现在给了 **`postId`** 与 **`commentId`**。
   * - 点赞 / 评论 → `postId` 有值（评论类 `commentId` 可能为空）
   * - **回复 → 两者都有** → 跳到帖子**并定位到那条评论**
   * - 关注 → 两者都空 → 跳对方主页
   *
   * ⚠️ 这条替换掉了原来那句"评论类跳不了、契约缺 postId"的兜底 —— 缺口已经补上了。
   * ⚠️ 帖子已被删的情形**不需要前端特判**：详情页读不到就是 404，它会渲染友好页
   *    （L1 已裁：不加"内容是否还在"的字段）。
   */
  const postId = num(n.postId)
  const commentId = num(n.commentId)

  if (postId > 0) {
    // `openComments=1` 让详情页直接展开评论区；有 commentId 时再带上它用于**定位高亮**
    targetUrl =
      `/pages/post/detail?id=${postId}&openComments=1` + (commentId > 0 ? `&commentId=${commentId}` : '')
  } else if (type === NOTIFY_TYPE.FOLLOW && fromUserId > 0) {
    // 关注类没有内容目标，但可以去看对方的主页
    targetUrl = `/pages/user/index?id=${fromUserId}`
  } else {
    unreachableReason = '这条通知没有可跳转的目标'
  }
  return {
    id: num(n.id),
    type,
    typeLabel: NOTIFY_FILTERS.find((f) => f.value === type)?.label ?? '通知',
    iconType,
    iconColor: color,
    fromUserId,
    fromName: name,
    fromAvatarUrl: text(n.fromAvatarUrl),
    content: text(n.content).trim() || fallbackContent(type, name),
    isRead: bool(n.isRead),
    timeText: relativeTime(n.createdAt),
    targetUrl,
    unreachableReason,
  }
}
