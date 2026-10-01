/** 后台 API：按域分组，路径全部登记在 contract.ts（没有登记的不许调）。 */

import { get, pageQuery, post, put, del } from './client'
import type {
  AdminBoardVO,
  AdminCommentVO,
  AdminInviteCodeVO,
  AdminLogVO,
  AdminLoginVO,
  AdminPostVO,
  AdminReportVO,
  AdminSensitiveWordVO,
  AdminUserVO,
  Page,
} from './types'

// ─────────────────────────── 认证 ───────────────────────────

export function adminLogin(username: string, password: string): Promise<AdminLoginVO> {
  return post<AdminLoginVO>('/api/admin/login', { username, password })
}

export function adminLogout(): Promise<void> {
  return post<void>('/api/admin/logout')
}

// ─────────────────────────── 帖子 ───────────────────────────

export function listAdminPosts(status: number | undefined, page: number, size: number): Promise<Page<AdminPostVO>> {
  return get<Page<AdminPostVO>>(`/api/admin/posts?${pageQuery(page, size, { status })}`)
}

export function reviewPost(id: number, status: 1 | 2, reason?: string): Promise<number> {
  return put<number>(`/api/admin/posts/${id}/status`, { status, reason })
}

export function setPostTop(id: number, top: boolean): Promise<number> {
  return put<number>(`/api/admin/posts/${id}/top`, { top })
}

export function setPostEssence(id: number, essence: boolean): Promise<number> {
  return put<number>(`/api/admin/posts/${id}/essence`, { essence })
}

export function deletePost(id: number, reason: string): Promise<number> {
  return del<number>(`/api/admin/posts/${id}`, { reason })
}

// ─────────────────────────── 评论 ───────────────────────────

export function listAdminComments(status: number | undefined, page: number, size: number): Promise<Page<AdminCommentVO>> {
  return get<Page<AdminCommentVO>>(`/api/admin/comments?${pageQuery(page, size, { status })}`)
}

export function reviewComment(id: number, status: 1 | 2, reason?: string): Promise<number> {
  return put<number>(`/api/admin/comments/${id}/status`, { status, reason })
}

// ─────────────────────────── 举报 ───────────────────────────

export function listReports(status: number | undefined, page: number, size: number): Promise<Page<AdminReportVO>> {
  return get<Page<AdminReportVO>>(`/api/admin/reports?${pageQuery(page, size, { status })}`)
}

export function disposeReport(id: number, outcome: 1 | 2, note: string): Promise<number> {
  return put<number>(`/api/admin/reports/${id}/dispose`, { outcome, note })
}

// ─────────────────────────── 用户 ───────────────────────────

export function listUsers(keyword: string | undefined, page: number, size: number): Promise<Page<AdminUserVO>> {
  return get<Page<AdminUserVO>>(`/api/admin/users?${pageQuery(page, size, { keyword })}`)
}

export function banUser(id: number, reason: string): Promise<number> {
  return put<number>(`/api/admin/users/${id}/ban`, { reason })
}

export function unbanUser(id: number, reason?: string): Promise<number> {
  return put<number>(`/api/admin/users/${id}/unban`, { reason })
}

// ─────────────────────────── 版块 ───────────────────────────

export function listBoards(): Promise<AdminBoardVO[]> {
  return get<AdminBoardVO[]>('/api/admin/boards')
}

export function createBoard(body: {
  name: string
  slug: string
  description?: string
  sort?: number
  isResource?: number
}): Promise<number> {
  return post<number>('/api/admin/boards', body)
}

export function updateBoard(
  id: number,
  body: { name?: string; description?: string; sort?: number; status?: number; isResource?: number },
): Promise<number> {
  return put<number>(`/api/admin/boards/${id}`, body)
}

// ─────────────────────────── 敏感词 ───────────────────────────

export function listSensitiveWords(page: number, size: number): Promise<Page<AdminSensitiveWordVO>> {
  return get<Page<AdminSensitiveWordVO>>(`/api/admin/sensitive-words?${pageQuery(page, size)}`)
}

export function addSensitiveWord(word: string): Promise<number> {
  return post<number>('/api/admin/sensitive-words', { word })
}

export function deleteSensitiveWord(id: number): Promise<number> {
  return del<number>(`/api/admin/sensitive-words/${id}`)
}

// ─────────────────────────── 系统 ───────────────────────────

export function switchRegisterMode(mode: 'open' | 'invite' | 'closed'): Promise<number> {
  return put<number>('/api/admin/configs/register-mode', { mode })
}

export function generateInviteCodes(count: number, expireDays?: number): Promise<string[]> {
  return post<string[]>('/api/admin/invite-codes', { count, expireDays })
}

export function listInviteCodes(status: number | undefined, page: number, size: number): Promise<Page<AdminInviteCodeVO>> {
  return get<Page<AdminInviteCodeVO>>(`/api/admin/invite-codes?${pageQuery(page, size, { status })}`)
}

export function listLogs(action: string | undefined, page: number, size: number): Promise<Page<AdminLogVO>> {
  return get<Page<AdminLogVO>>(`/api/admin/logs?${pageQuery(page, size, { action })}`)
}
