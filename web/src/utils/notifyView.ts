/**
 * 通知的视图模型（M5）。
 *
 * ==========================================================================
 * 一条必须说清楚的事：**有些通知点不进去，不是我偷懒**
 * ==========================================================================
 * 契约 `NotificationVO` 给的是 `targetType`(1 帖子 / 2 评论) + `targetId`：
 * - `targetType = 1`（帖子）→ `targetId` 就是**帖子 id** → 能跳详情页 ✓
 * - `targetType = 2`（评论）→ `targetId` 是**评论 id**，而**契约里没有 postId**，
 *   也没有"由评论 id 查帖子"的接口 → **前端无法定位到那条评论所在的帖子**。
 *   所以这类通知**不做假跳转**（跳到一个猜出来的地址比不跳更糟），
 *   点击只标记已读并明确告知原因。**已作为 CR 登记**（建议 `NotificationVO` 补 `postId`）。
 * - 关注类（type=4）没有目标内容，但**可以从 `fromUserId` 跳到对方主页** ✓
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

  if (targetType === NOTIFY_TARGET.POST && targetId > 0) {
    targetUrl = `/pages/post/detail?id=${targetId}`
  } else if (targetType === NOTIFY_TARGET.COMMENT) {
    /*
     * ⚠️ 评论通知**跳不了**：契约只给评论 id，没有 postId，也没有反查接口。
     * 不去猜地址 —— 猜错会把用户带到一篇无关的帖子，比"点了没反应"更糟。
     * 这里把原因带给页面，由页面明确告知（而不是静默无响应）。
     */
    unreachableReason = '这条通知针对一条评论，但接口没给帖子 id（契约缺字段），暂时无法跳转'
  } else if (type === NOTIFY_TYPE.FOLLOW && fromUserId > 0) {
    // 关注类没有"内容目标"，但可以去看对方的主页
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
