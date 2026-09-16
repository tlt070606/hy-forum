/**
 * 版块接口（`openapi.json` 的「版块」tag，1 个路径）。
 *
 *   GET /api/boards   版块列表（免登录）
 *
 * 路径统一取 `contract.ts` 的 `ENDPOINTS`，不写死字符串。
 */

import { get } from '@/utils/request'
import { ENDPOINTS } from './contract'
import { expectArray } from './shape'
import type { BoardVO } from './types'

/**
 * 获取全部版块。
 *
 * 契约要点：
 * - 免登录（`security` 为空），但仍带 token 无副作用 —— 后端可据此做个性化，且不会报错
 * - 返回**全量数组**，**没有分页**（契约里 `data` 直接是 `BoardVO[]`）。
 *   版块是管理员维护的少量数据（《技术方案》§4.3 默认 7 个），全量拉取是合理的
 * - 排序由后端按 `sort` 字段决定（`BoardVO.sort`），**前端不重排** ——
 *   自己重排会让后台调整顺序后前台看不到效果
 *
 * @throws {ApiError} 网络失败 / 业务码非 0 / 返回的不是数组
 */
export async function fetchBoards(): Promise<BoardVO[]> {
  const data = await get<unknown>(ENDPOINTS.boards.path, undefined, {
    // 免登录接口：显式声明意图，也让"未登录访客"这条路径不被 401 逻辑误伤
    withAuth: true,
    clearAuthOn401: false,
  })
  return expectArray<BoardVO>(data, '版块列表')
}

/**
 * 按 id 找版块（发帖页/版块页从列表数据里定位用）。
 *
 * 为什么不做单独的 `GET /api/boards/{id}`：**契约里没有这个路径**，
 * 前端不得编造。版块列表本来就要整体加载（首页宫格），
 * 从这里查即可，也少一次请求。
 */
export function findBoard(boards: BoardVO[], boardId: number | null | undefined): BoardVO | null {
  if (boardId === null || boardId === undefined) return null
  return boards.find((b) => b.id === boardId) ?? null
}
