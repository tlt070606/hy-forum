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
 * 本文件承担的口径（任务书 §5，逐条对应）
 * ==========================================================================
 * - 口径 5：列表 `size` **后端硬上限 20** → `normalizeSize()` 也截一刀。
 *   为什么前端也截：让"请求了 50 条"这种代码在开发期就看得出来。否则后端静默只给 20 条，
 *   而滚动加载逻辑以为拿到了 50 条，表现为"滚到底没有加载更多、也没有到底提示"。
 * - 口径 6：列表**只返回摘要**（无正文），列表图字段是 `coverUrl`。
 * - 口径 7：详情图片只展示后端返回的 `images`，**前端不自己拼 URL**。
 * - 口径 3：发帖限流 `2002` 走 HTTP 200 + 业务码，由 `request` 层统一抛 `ApiError`。
 * - 口径 11：`PUT` 是**覆盖**语义，没传的字段视为清空。
 */

import { del, get, post, put, PAGE_SIZE_MAX } from '@/utils/request'
import { ENDPOINTS } from './contract'
import { expectObject, expectPage } from './shape'
import type {
  PostCreateRequest,
  PostDetailVO,
  PostSort,
  PostSummaryVO,
  PostUpdateRequest,
} from './types'

/** 分页响应的收敛形状（`list` 保证是数组，见 `shape.ts`） */
export interface PostPage {
  list: PostSummaryVO[]
  total: number
}

export interface PostListQuery {
  /** 版块 id。不传 = 全站（首页信息流用） */
  boardId?: number
  sort?: PostSort
  /** 页码，**从 1 开始** */
  page?: number
  size?: number
}

/** 收敛到后端硬上限内（口径 5） */
function normalizeSize(size: number | undefined): number {
  if (!size || size <= 0) return PAGE_SIZE_MAX
  return Math.min(size, PAGE_SIZE_MAX)
}

/** 页码收敛：< 1 一律按第 1 页（避免发出 `page=0` 被后端当参数错误） */
function normalizePage(page: number | undefined): number {
  if (!page || page < 1) return 1
  return page
}

/**
 * 帖子列表。
 *
 * - `boardId` 不传 = 全站（首页用）
 * - 返回的 `total` 由后端给出；`total` 缺失时退化成"本页条数"（见 `shape.ts`），
 *   因此**不能用它做精确的"共 N 条"判断**，只能当参考
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
 * ⚠️ `keyword` 在契约里**必填**，缺了返回 `400`。这里在发请求前就拦一道：
 * 空关键词直接返回空结果 —— 对用户来说"没输入就点搜索"是正常操作，
 * 不该看到"提交的内容有误"这种报错。
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
    { keyword: kw, page: normalizePage(page), size: normalizeSize(size) },
    { withAuth: true, clearAuthOn401: false }
  )
  return expectPage<PostSummaryVO>(data, '搜索结果')
}

/**
 * 帖子详情。
 *
 * ⚠️ 三条口径：
 * 1. **可选鉴权** —— 作者能看到自己的待审帖。因此**未登录也要发这个请求**，
 *    不要在前端先拦成"请登录"。
 * 2. `404` 时页面要渲染**友好页**，不能白屏。
 * 3. 每次调用后端都会 `viewCount +1`（走 Redis，§8.3），**不要为了取字段重复调用**。
 */
export async function fetchPostDetail(id: number): Promise<PostDetailVO> {
  const path = ENDPOINTS.postDetail.path.replace('{id}', String(id))
  const data = await get<unknown>(path, undefined, { withAuth: true, clearAuthOn401: false })
  return expectObject<PostDetailVO>(data, '帖子详情')
}

/** 发帖。资源版块需 `diskType` + `diskUrl`（口径 10），`images` ≤ 9 张 */
export function createPost(payload: PostCreateRequest): Promise<PostDetailVO> {
  // 401 说明登录态确实失效 → 清本地态并让页面跳登录（口径 4）
  return post<PostDetailVO>(ENDPOINTS.posts.path, payload, { withAuth: true, clearAuthOn401: true })
}

/**
 * 改帖。
 *
 * ⚠️ **覆盖语义**：没传的字段视为清空（含 `images` 不传 = 清空图片）。
 *    调用方必须提交**完整**表单。
 * ⚠️ 改完 `status` **回到 0**（重新进入审核，§8.6 第 6 条），页面必须提示。
 * ⚠️ 仅作者、且**仅发布后 30 分钟内**（超时 403）。
 */
export function updatePost(id: number, payload: PostUpdateRequest): Promise<PostDetailVO> {
  const path = ENDPOINTS.postDetail.path.replace('{id}', String(id))
  return put<PostDetailVO>(path, payload, { withAuth: true, clearAuthOn401: true })
}

/** 删帖（逻辑删除，仅作者或管理员）。响应是 `ApiResponseVoid`，不解析返回值 */
export function deletePost(id: number): Promise<void> {
  const path = ENDPOINTS.postDetail.path.replace('{id}', String(id))
  return del<void>(path, { withAuth: true, clearAuthOn401: true })
}
