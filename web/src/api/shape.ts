/**
 * 契约响应的**形状断言**工具。
 *
 * ==========================================================================
 * 为什么需要它（它不是"防御性编程"，是契约缺陷的可见化手段）
 * ==========================================================================
 * 本项目的统一响应体是 `{code, message, data}`（《技术方案》§6.1），
 * `utils/request.ts` 已经把 `data` 解包出来了。但**解包后 `data` 自身是什么形状，
 * 没有任何运行时保证**：
 *
 * - 契约里 `ApiResponseXxx.data` 全都声明为可选（`data?:`），
 *   所以 `code=0` 但缺 `data` 是**合法响应**，`request()` 会 resolve `undefined`；
 * - 契约里每个 schema 的字段也全都可选，字段改名/漏返回不会让请求失败。
 *
 * 若页面直接 `posts.map(...)`，一旦 `data` 是 `undefined`，报错会出现在
 * **模板渲染阶段**（`Cannot read properties of undefined`），堆栈指向 Vue 内部，
 * 根因（后端形状变了）被埋掉，排查成本极高。
 *
 * 因此：**在 API 层断言形状，失败时抛一个语义明确的 `ApiError`**。
 * 这与 `api/auth.ts` 里 `fetchCaptcha` 的运行时校验同源。
 *
 * ⚠️ 断言只做"最少必要"的一条：**是不是数组 / 是不是对象**。
 *    **不逐个字段校验** —— 字段缺失由 `utils/format.ts` 收敛成缺省值，
 *    那属于"能降级显示"的范畴；而"整个 data 不是数组"属于"根本没法渲染"。
 *    把两者都做成硬失败会让契约加字段时前端误报。
 */

import { ApiError } from '@/utils/request'

/**
 * 断言 `data` 是数组（列表类接口用）。
 *
 * @param data 已解包的 `data`
 * @param what 接口名，用于错误文案（例如 "版块列表"）
 */
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
 * `null` 也算失败：契约里"资源不存在"应该是 `404` + 业务码，
 * 而不是 `code=0, data=null`。把它当成功会让页面白屏。
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
 * 只修正 `list`：它缺失时退化为空数组（列表页显示"暂无内容"是合理降级），
 * 但 `data` **整体**不是对象时依然抛错 —— 那种情况多半是接口被反代/网关换了形状。
 *
 * 返回的 `list` 一定是数组，因此**不需要**给 `PageResultPostSummaryVO` 里
 * `list?:` 的可选性再补一次断言。
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
