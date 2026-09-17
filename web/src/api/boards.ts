/**
 * 版块接口（`openapi.json` 的「版块」tag，1 个路径）。
 *
 *   GET /api/boards   版块列表（免登录）
 */

import { get } from '@/utils/request'
import { ENDPOINTS } from './contract'
import { expectArray } from './shape'
import type { BoardVO } from './types'

/**
 * 获取全部版块。
 *
 * - 免登录（`security` 为空），带 token 也无副作用
 * - 返回**全量数组**，契约里**没有分页**（`data` 直接是 `BoardVO[]`）。
 *   版块是管理员维护的少量数据（《技术方案》§4.3 默认 7 个），全量拉取合理
 * - 排序由后端的 `sort` 字段决定，**前端不重排** —— 自己重排会让后台调顺序后前台看不到效果
 */
export async function fetchBoards(): Promise<BoardVO[]> {
  const data = await get<unknown>(ENDPOINTS.boards.path, undefined, {
    withAuth: true,
    clearAuthOn401: false,
  })
  return expectArray<BoardVO>(data, '版块列表')
}

/**
 * 按 id 定位版块。
 *
 * 为什么不做 `GET /api/boards/{id}`：**契约里没有这个路径**，前端不得编造。
 * 版块数据本来就整体加载过一次，从这里查即可，也省一次请求。
 */
export function findBoard(boards: BoardVO[], boardId: number | null | undefined): BoardVO | null {
  if (boardId === null || boardId === undefined) return null
  return boards.find((b) => b.id === boardId) ?? null
}
