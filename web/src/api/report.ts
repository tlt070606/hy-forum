/**
 * 举报接口（M5）。
 *
 *   POST /api/report  body = ReportCreateRequest
 *
 * 契约里的取值（`openapi.json` 的 description 写得很清楚，所以这里**敢按它写**）：
 * - `targetType`：1 帖子 / 2 评论 / 3 用户
 * - `reasonType`：1 违法违规 / 2 色情低俗 / 3 广告垃圾 / 4 侵权 / 5 其他
 * - `reasonDetail`：可选补充说明
 *
 * ⚠️ 限流：举报属**业务动作维度**，超限走 **HTTP 200 + 业务码**（不是 429）——
 *   见《技术方案》§8.7 的分层与 M5 任务书 §5 第 8 条。
 *   所以这里**不要**按"429 才算限流"去判断，交给 `request` 层按错误码表映射文案。
 */

import { post } from '@/utils/request'
import { ENDPOINTS } from './contract'
import { expectNumber } from './shape'
import type { ReportCreateRequest } from './types'

/** 举报目标类型（契约枚举，别自己编号） */
export const REPORT_TARGET = {
  POST: 1,
  COMMENT: 2,
  USER: 3,
} as const

/**
 * 举报原因。**逐条对应契约的 `reasonType` 取值**，前端只负责显示与回传编号。
 * 顺序按"最常见/最严重"排：违法违规 → 色情低俗 → 广告垃圾 → 侵权 → 其他。
 */
export const REPORT_REASONS: Array<{ value: number; label: string }> = [
  { value: 1, label: '违法违规' },
  { value: 2, label: '色情低俗' },
  { value: 3, label: '广告垃圾' },
  { value: 4, label: '侵权' },
  { value: 5, label: '其他' },
]

/**
 * 提交举报。返回举报记录 id。
 *
 * ⚠️ 不做"假成功"：后端返回非 0 业务码（含限流）时由 `request` 层抛出，
 *    页面据此给出可读文案；**不要**在成功之前就把按钮变成"已举报"。
 */
export async function submitReport(payload: ReportCreateRequest): Promise<number> {
  const data = await post<unknown>(ENDPOINTS.report.path, payload, {
    withAuth: true,
    clearAuthOn401: true,
  })
  return expectNumber(data, '举报结果')
}
