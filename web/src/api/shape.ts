/**
 * 契约响应的**形状断言**。
 *
 * ==========================================================================
 * 为什么需要它
 * ==========================================================================
 * 契约里 `ApiResponseXxx.data` **全都是可选的**（`data?:`），而且每个 schema 的字段也全都可选。
 * 于是「`code=0` 但缺 `data`」是**合法响应**，`request()` 会 resolve `undefined`；
 * 页面若直接 `.map()`，报错会出现在**模板渲染阶段**，堆栈指向框架内部，
 * 根因（后端形状变了）被埋掉。
 *
 * 所以这里在 API 层断言**最少必要的一条**：整个 `data` 是不是数组 / 是不是对象。
 * **字段级缺失不在这里报错** —— 那由 `utils/postView.ts` 收敛成缺省值。
 * 两者分开的理由：整个 `data` 不是数组 = 根本没法渲染；某个字段没返回 = 能降级显示。
 * 把两者都做成硬失败，会让契约**加字段**时前端误报。
 */

import { ApiError } from '@/utils/request'

/** 断言 `data` 是数组（列表类接口用） */
export function expectArray<T>(data: unknown, what: string): T[] {
  if (Array.isArray(data)) return data as T[]

  throw new ApiError({
    kind: 'http',
    code: -1,
    message: `${what}返回的数据格式无法识别，请稍后重试`,
  })
}

/**
 * 断言 `data` 是对象（详情类接口用）。
 *
 * `null` 也算失败：契约里"资源不存在"应该是 `404` + 业务码，而不是 `code=0, data=null`。
 * 把它当成功会让页面白屏。
 */
export function expectObject<T>(data: unknown, what: string): T {
  if (data !== null && typeof data === 'object' && !Array.isArray(data)) return data as T

  throw new ApiError({
    kind: 'http',
    code: -1,
    message: `${what}返回的数据格式无法识别，请稍后重试`,
  })
}

/**
 * 断言分页结果形状（`{list, total, page, size}`）。
 *
 * 只修正 `list`：它缺失时退化为空数组（显示"暂无内容"是合理降级）；
 * `data` **整体**不是对象时依然抛错。返回的 `list` 一定是数组。
 */
export function expectPage<T>(data: unknown, what: string): { list: T[]; total: number } {
  const obj = expectObject<{ list?: unknown; total?: unknown }>(data, what)

  if (obj.list !== undefined && obj.list !== null && !Array.isArray(obj.list)) {
    throw new ApiError({
      kind: 'http',
      code: -1,
      message: `${what}返回的列表格式无法识别，请稍后重试`,
    })
  }

  const list = (obj.list ?? []) as T[]
  const total = typeof obj.total === 'number' ? obj.total : list.length
  return { list, total }
}
