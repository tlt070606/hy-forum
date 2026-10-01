/** 后台契约的响应类型（与后端 VO record 一一对应；来源 openapi.json 2026-10-01）。 */

export interface Envelope<T> {
  code: number
  message: string
  data: T
}

export interface Page<T> {
  list: T[]
  total: number
  page: number
  size: number
}

export interface AdminLoginVO {
  token: string
  adminId: number
  nickname: string
  role: string
}

export interface AdminPostVO {
  id: number
  title: string
  authorId: number
  authorNickname: string
  status: number // 0 待审核 / 1 正常 / 2 已屏蔽
  isTop: number // 1 置顶
  isEssence: number // 1 加精
  createdAt: string
}

export interface AdminCommentVO {
  id: number
  postId: number
  postTitle: string
  userId: number
  authorNickname: string
  parentId: number
  content: string
  status: number
  createdAt: string
}

export interface AdminReportVO {
  id: number
  targetType: number // 1 帖子 / 2 评论 / 3 用户
  targetId: number
  targetSummary: string
  reporterId: number
  reporterNickname: string
  reasonType: number // 1 违法违规 2 色情低俗 3 广告垃圾 4 侵权 5 其他
  reasonDetail: string | null
  status: number // 0 待处理 / 1 已处理 / 2 已驳回
  handleNote: string | null
  handledAt: string | null
  createdAt: string
}

export interface AdminUserVO {
  id: number
  username: string
  nickname: string
  status: number // 1 正常 / 0 封禁
  postCount: number
  createdAt: string
}

export interface AdminBoardVO {
  id: number
  name: string
  slug: string
  description: string | null
  isResource: number
  sort: number
  status: number // 1 启用 / 0 停用
  postCount: number
  createdAt: string
}

export interface AdminSensitiveWordVO {
  id: number
  word: string
  createdAt: string
}

export interface AdminInviteCodeVO {
  id: number
  code: string
  status: number // 0 未使用 / 1 已使用 / 2 已失效
  expireAt: string | null
  usedByUserId: number | null
  usedAt: string | null
  createdAt: string
}

export interface AdminLogVO {
  id: number
  adminId: number
  adminUsername: string
  action: string
  targetType: number | null
  targetId: number | null
  reason: string | null
  detail: string | null
  ip: string | null
  createdAt: string
}
