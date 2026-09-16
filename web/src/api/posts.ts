/**
 * 帖子接口（`openapi.json` 的「帖子」tag，4 个路径）。
 *
 *   GET    /api/posts           列表（`boardId` / `sort` / `page` / `size`）
 *   POST   /api/posts           发帖
 *   GET    /api/posts/{id}      详情
 *   PUT    /api/posts/{id}      改帖
 *   DELETE /api/posts/{id}      删帖
 *   GET    /api/posts/search    搜索（`keyword` 必填）
 *
 * ==========================================================================
 * 本文件承担的"口径"（任务书 §5，逐条对应）
 * ==========================================================================
 * - 口径 5：列表 `size` **后端硬上限 20**，传 100 也只给 20 → 前端不假设拿到 100，
 *   见 `PAGE_SIZE_MAX` 与 `normalizeSize()`。
 * - 口径 6：列表**只返回摘要**（无正文），列表图字段是 `coverUrl`。
 * - 口径 7：详情图片只展示后端返回的 `images`，**不自己拼 URL**。
 * - 口径 3：发帖限流 `2002` 走 HTTP 200 + 业务码，**按业务码处理**（由 request 层统一抛 ApiError）。
 * - 口径 11：`PUT` 是**覆盖**语义，没传的字段视为清空。
 */

import { del, get, post, put } from '@/utils/request'
import { PAGE_SIZE_MAX } from '@/utils/request'
import { ENDPOINTS } from './contract'
import { expectObject, expectPage } from './shape'
import type {
  PostCreateRequest,
  PostDetailVO,
  PostSort,
  PostSummaryVO,
  PostUpdateRequest,
} from './types'

/** 分页响应的收敛形状（`list` 保证是数组，见 `shape.ts` 的 `expectPage`） */
export interface PostPage {
  list: PostSummaryVO[]
  total: number
}

/**
 * 列表查询参数。
 *
 * `sort` 在契约里是裸 `string`（**没有 enum**），这里用前端联合类型收窄；
 * 见 `types.ts` 的 `PostSort` 注释（契约改进建议已登记进报告）。
 */
export interface PostListQuery {
  /** 版块 id。不传 = 全站（首页「最新帖子」流用） */
  boardId?: number
  sort?: PostSort
  /** 页码，**从 1 开始**（契约与 §6.1 的分页约定同口径） */
  page?: number
  size?: number
}

/**
 * 把 `size` 收敛到后端硬上限内。
 *
 * 为什么要在前端也截一刀（后端已截）：让"请求了 50 条"这种代码**在开发期就看得出来**
 * —— 否则后端静默只给 20 条，前端的分页/滚动逻辑会以为拿到了 50 条，
 * 表现为"列表滚到底部没有加载更多、也没有到底提示"这种难查的现象。
 */
function normalizeSize(size: number | undefined): number {
  if (!size || size <= 0) return PAGE_SIZE_MAX
  return Math.min(size, PAGE_SIZE_MAX)
}

/** 页码收敛：小于 1 一律按第 1 页（避免发出 `page=0` 被后端当参数错误） */
function normalizePage(page: number | undefined): number {
  if (!page || page < 1) return 1
  return page
}

/**
 * 帖子列表。
 *
 * - `boardId` 不传 = 全站最新（首页用）
 * - 返回的 `total` 由后端给出；`total` 缺失时退化成"本页条数"（见 `shape.ts`），
 *   因此**不能用它做精确的"共 N 条"文案判断**，只能用作"还有没有下一页"的参考
 *
 * @throws {ApiError} `400` 参数错误 / 网络失败 / 形状异常
 */
export async function fetchPosts(query: PostListQuery = {}): Promise<PostPage> {
  const data = await get<unknown>(
    ENDPOINTS.posts.path,
    {
      boardId: query.boardId,
      sort: query.sort,
      page: normalizePage(query.page),
      size: normalizeSize(query.size),
    },
    { withAuth: true, clearAuthOn401: false }
  )
  return expectPage<PostSummaryVO>(data, '帖子列表')
}

/**
 * 搜索帖子。
 *
 * ⚠️ 口径：`keyword` 在契约里是**必填**（`required: true`），缺了会返回 `400`。
 *    因此这里**在发出请求前就拦一道**：空关键词直接返回空结果，
 *    而不是让后端回一个 `400` 再弹"提交的内容有误"——
 *    对用户来说"没输入就点搜索"是正常操作，不该看到报错。
 *
 * 与列表一样受 `size` 上限 20 约束。
 */
export async function searchPosts(
  keyword: string,
  page?: number,
  size?: number
): Promise<PostPage> {
  const kw = keyword.trim()
  if (!kw) return { list: [], total: 0 }

  const data = await get<unknown>(
    ENDPOINTS.postSearch.path,
    {
      keyword: kw,
      page: normalizePage(page),
      size: normalizeSize(size),
    },
    { withAuth: true, clearAuthOn401: false }
  )
  return expectPage<PostSummaryVO>(data, '搜索结果')
}

/**
 * 帖子详情。
 *
 * ⚠️ 两条口径：
 * 1. **可选鉴权**：作者可以看到自己的待审帖（`status=0`），游客/他人看不到。
 *    因此**未登录也要发这个请求**（不带 token），不要在前端先拦成"请登录"。
 * 2. `404` 时 `request` 层会抛 `ApiError(code=404)`；页面据此渲染**友好页**，
 *    不能白屏（任务书 §6.2 第 4 页的明确要求）。
 * 3. 每次调用都会让后端 `viewCount +1`（走 Redis，见 §8.3），
 *    所以**不要为了取字段重复调用**。
 */
export async function fetchPostDetail(id: number): Promise<PostDetailVO> {
  const data = await get<unknown>(`${ENDPOINTS.postDetail.path.replace('{id}', String(id))}`, undefined, {
    // 可选鉴权：带上 token（若已登录）以便作者看到自己的待审帖
    withAuth: true,
    // 详情接口返回 401 属于异常（它是可选鉴权），不该把用户会话清掉
    clearAuthOn401: false,
  })
  return expectObject<PostDetailVO>(data, '帖子详情')
}

/**
 * 发帖。
 *
 * 契约要点（`PostCreateRequest` 的 required）：`boardId`、`title` 必填。
 * 资源版块另需 `diskType` + `diskUrl`（口径 10，由页面的 `isResource` 判断驱动）。
 *
 * `images` 是**图片 URL 列表**（≤9 张），后端会校验 URL 必须位于本项目 OSS 目录前缀内
 * （《技术方案》§8.4 第 4 条）。
 *
 * ⚠️ 限流：`2002` 走 HTTP 200 + 业务码（口径 3），由 `request` 层抛成 `ApiError`，
 *    页面按 `code===2002` 分支给出"稍后重试"文案（后端 `message` 里带重试秒数）。
 *
 * @throws {ApiError} `400` 校验失败 / `401` 未登录 / `2001` 敏感词 / `2002` 限流
 */
export function createPost(payload: PostCreateRequest): Promise<PostDetailVO> {
  return post<PostDetailVO>(ENDPOINTS.posts.path, payload, {
    withAuth: true,
    // 发帖返回 401 说明登录态确实失效 → 清本地态并让页面跳登录（口径 4）
    clearAuthOn401: true,
  })
}

/**
 * 改帖。
 *
 * ⚠️ **覆盖语义（口径 11）**：没传的字段视为清空。
 *     所以调用方必须提交**完整**的表单（含未改动项），
 *     绝不能只提交"用户改了的那一项" —— 那会把其它字段全部清掉。
 *     契约里 `PostUpdateRequest.images` 的说明也明确写了"不传 = 清空图片"。
 *
 * ⚠️ **改完 `status` 回 0（重新进入审核）**（《技术方案》§8.6 第 6 条），
 *     页面必须提示"重新进入审核"，否则作者会以为帖子消失了。
 *
 * ⚠️ 仅作者、且**仅发布后 30 分钟内**（契约描述）。超时返回 `403`。
 *
 * @throws {ApiError} `400` / `401` / `403`（非作者或超 30 分钟）/ `404`
 */
export function updatePost(id: number, payload: PostUpdateRequest): Promise<PostDetailVO> {
  return put<PostDetailVO>(
    ENDPOINTS.postDetail.path.replace('{id}', String(id)),
    payload,
    { withAuth: true, clearAuthOn401: true }
  )
}

/**
 * 删帖（逻辑删除，仅作者或管理员）。
 *
 * 契约响应是 `ApiResponseVoid`，成功时 `data` 为空 —— 因此**不解析返回值**。
 *
 * @throws {ApiError} `401` / `403`（非作者）/ `404`（已删除）
 */
export function deletePost(id: number): Promise<void> {
  return del<void>(ENDPOINTS.postDetail.path.replace('{id}', String(id)), {
    withAuth: true,
    clearAuthOn401: true,
  })
}
