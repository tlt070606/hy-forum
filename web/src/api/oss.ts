/**
 * OSS 直传签名接口（`openapi.json` 的「OSS 直传」tag）。
 *
 *   GET  /api/oss/signature   取直传签名（**需登录**）
 *   POST /api/oss/callback    ⚠️ **由 OSS 主动调用，前端不调**
 *
 * 本文件只封装前者。后者登记在 `contract.ts` 里，只为让"契约有哪些路径"这件事完整。
 */

import { get } from '@/utils/request'
import { ENDPOINTS } from './contract'
import { expectObject } from './shape'
import type { OssSignatureVO } from './types'

/**
 * 获取直传签名。
 *
 * ⚠️ **需要登录**（契约里该路径有鉴权）。未登录时后端返回 `401`，
 * `request` 层会抛 `ApiError` 并清掉本地失效 token —— **这个行为是对的**：
 * 拿不到签名就没法上传，页面据此引导去登录。
 *
 * 返回值里 `accessKeyId` 是**请求时临时收到的标识**，不是配置项 ——
 * **不要把它写进 `.env` 或任何常量**（任务书 §3.2）。
 */
export async function fetchSignature(): Promise<OssSignatureVO> {
  const data = await get<unknown>(ENDPOINTS.ossSignature.path, undefined, {
    withAuth: true,
    // 401 说明确实没登录/登录态失效 → 清本地态并让页面跳登录
    clearAuthOn401: true,
  })
  return expectObject<OssSignatureVO>(data, '上传签名')
}
