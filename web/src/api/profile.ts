/**
 * 个人资料写接口（M5 交付）。
 *
 *   PUT /api/user/profile   修改自己的资料
 *
 * ==========================================================================
 * ⚠️⚠️ 这是**覆盖语义**，不是"只改传了的字段"
 * ==========================================================================
 * 契约里写得很清楚：「**PUT = 覆盖（省略即清空）**；只接受
 * `nickname`/`avatarUrl`/`bio`/`gender`，出现 `username`/`password`/`role`/`id`
 * 等字段返回 400（**不是静默忽略**）」。
 *
 * 也就是说：**只想改简介而只传 `{bio}`，会把昵称和头像一起清空** ——
 * 这是这个接口最容易出事的地方。所以本文件的类型**把四个字段都设为必填**，
 * 让调用方**在编译期就没法漏传**（漏传 = 用户资料被清空，属于数据损失）。
 *
 * 另外一条硬约束：**`avatarUrl` 必须在本项目 OSS 的 `avatar/{自己id}/` 目录下**。
 * 所以头像上传必须用 `fetchSignature('avatar')` 取签名（`dir = avatar/{id}/`），
 * 用 `post/` 目录传上去的地址会被后端拒掉。
 */

import { put } from '@/utils/request'
import { ENDPOINTS } from './contract'
import { expectObject } from './shape'
import type { UserProfileVO } from './types'

/**
 * 要提交的完整资料。
 *
 * ⚠️ 四个字段**全是必填**（不是偷懒，是防"漏传即清空"）：
 * 想只改一项时，调用方要把**其余几项的当前值**一起带上。
 * 页面的做法是：从 `auth.user` 取当前值，只覆盖用户改的那一项。
 */
export interface ProfileUpdatePayload {
  nickname: string
  avatarUrl: string
  bio: string
  /** 0 未知 / 1 男 / 2 女（契约有语义）。当前界面不提供修改入口，回传原值即可 */
  gender: number
}

/**
 * 提交资料修改，返回更新后的资料（`UserProfileVO`）。
 *
 * 常见失败（都由 `request` 层映射成可读文案，页面直接显示）：
 * - `400`：传了不允许的字段（`username`/`password`/`role`/`id`），或 `avatarUrl` 不在自己的目录下；
 * - `401`：未登录/token 失效。
 */
export async function updateProfile(payload: ProfileUpdatePayload): Promise<UserProfileVO> {
  const data = await put<unknown>(ENDPOINTS.userProfileUpdate.path, payload, {
    withAuth: true,
    clearAuthOn401: true,
  })
  return expectObject<UserProfileVO>(data, '资料更新')
}
