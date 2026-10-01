/**
 * 后台契约登记（对齐 web/src/api/contract.ts 的纪律）。
 *
 * ## 契约变更公告栏（后端改契约 → L1 在这里登记 → 前端照着接）
 *
 * | 日期 | 变更 | 来源 |
 * |---|---|---|
 * | 2026-10-01 | M6 批次一：posts 审核队列/审核、users 封禁/解封/列表、configs/register-mode、invite-codes 生成/列表、logs 查询 | openapi SHA F8D2EE27… |
 * | 2026-10-01 | M6 批次二：reports 列表/dispose、boards 列表/新建/更新、sensitive-words 增删、posts top/essence/delete | openapi SHA BFE31E81… |
 *
 * ## 本文件的防护边界（自我声明，与前台同款）
 *
 * - ENDPOINTS 表没有机器校验；判定以仓库根 openapi.json 为准。
 * - 响应形状断言（shape.ts 式的运行时校验）本版未做：管理端是自己人用的工具，
 *   错误形状会在第一次渲染时暴露。若将来接自动化测试，再补形状层。
 */

export const OPENAPI_SHA256 = 'BFE31E8180FC0AC081578AE6976F1447081F5B80F65FE5E93600D2D7A74EAA3D'

/** 后台端点登记：路径 → 说明。新增端点必须在这里登记（没有登记的端点不许调）。 */
export const ENDPOINTS = {
  // ── 认证（M1） ──
  'POST /api/admin/login': '管理员登录（IP 限流 10 次/分钟）',
  'POST /api/admin/logout': '管理员注销',
  // ── 帖子（M6 批次一/二） ──
  'GET /api/admin/posts': '帖子队列（status 筛选，时间升序）',
  'PUT /api/admin/posts/{id}/status': '帖子审核：1 放行 / 2 屏蔽（屏蔽必填 reason）',
  'PUT /api/admin/posts/{id}/top': '置顶/取消',
  'PUT /api/admin/posts/{id}/essence': '加精/取消',
  'DELETE /api/admin/posts/{id}': '管理端删除（reason 必填）',
  // ── 评论（M5） ──
  'GET /api/admin/comments': '评论队列',
  'PUT /api/admin/comments/{id}/status': '评论审核',
  // ── 举报（M6 批次二） ──
  'GET /api/admin/reports': '举报列表（带对象摘要）',
  'PUT /api/admin/reports/{id}/dispose': '处置登记（outcome 1/2 + note 必填）',
  // ── 用户（M6 批次一） ──
  'GET /api/admin/users': '用户搜索',
  'PUT /api/admin/users/{id}/ban': '封禁（reason 必填，踢全部登录态）',
  'PUT /api/admin/users/{id}/unban': '解封',
  // ── 版块（M6 批次二） ──
  'GET /api/admin/boards': '版块列表（含停用）',
  'POST /api/admin/boards': '新建版块',
  'PUT /api/admin/boards/{id}': '更新版块（部分更新，slug 不可改）',
  // ── 敏感词（M6 批次二） ──
  'GET /api/admin/sensitive-words': '词库列表',
  'POST /api/admin/sensitive-words': '加词（立即生效）',
  'DELETE /api/admin/sensitive-words/{id}': '删词（立即生效）',
  // ── 系统（M6 批次一） ──
  'PUT /api/admin/configs/register-mode': '切换注册模式（立即生效）',
  'POST /api/admin/invite-codes': '生成邀请码（1–50）',
  'GET /api/admin/invite-codes': '邀请码列表',
  'GET /api/admin/logs': '操作留痕查询',
} as const

export type EndpointKey = keyof typeof ENDPOINTS
