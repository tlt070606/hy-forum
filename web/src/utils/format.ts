/**
 * 展示层格式化工具（**可选字段的收敛点**）。
 *
 * ==========================================================================
 * 为什么需要这一层
 * ==========================================================================
 * 契约里除个别字段外**没有 `required` 声明**，所以生成类型把每个字段都标成可选
 * （`title?: string` / `viewCount?: number`）。这是契约的真实形状，
 * 前端**不能**用 `as` 断言成必填（那是"关掉检查"，违反任务书 §3.7）。
 *
 * 但若在模板里逐处写 `post.title ?? ''`，会把"契约可选"这件事散落到几十个地方。
 * 因此把它收敛到这里：**页面拿到的都是已收敛的值**，收敛规则只有一处。
 *
 * ⚠️ 只做"缺省值填充"，**不做字段改名、不做业务重算**。
 *    数值一律以契约字段为准（例如 `viewCount` 不在这里做 `万` 单位换算以外的处理）。
 */

import type { PostDetailVO, PostImageVO, PostSummaryVO, UserBriefVO } from '@/api/types'

/* ---------------------------------------------------------------------------
 * 文本与数字
 * ------------------------------------------------------------------------- */

/** 字符串字段的缺省收敛。`null` / `undefined` → 空串 */
export function text(value: string | null | undefined): string {
  return value ?? ''
}

/** 数字字段的缺省收敛。`null` / `undefined` → 0 */
export function num(value: number | null | undefined): number {
  return value ?? 0
}

/** 布尔字段的缺省收敛。`null` / `undefined` → false */
export function bool(value: boolean | null | undefined): boolean {
  return value ?? false
}

/**
 * 作者展示名。
 *
 * 契约里 `UserBriefVO` 只有 `nickname`（没有 `username`），
 * 因此回退链只有两级：昵称 → 「匿名用户」。
 * **不要**在这里编造"用户123"之类的名字 —— 那会让空昵称看起来像真实数据。
 */
export function authorName(author: UserBriefVO | null | undefined): string {
  const nickname = author?.nickname?.trim()
  return nickname ? nickname : '匿名用户'
}

/**
 * 大数值压缩显示（列表里的浏览量/点赞数用）。
 *
 * 边界：只处理正数；`>= 10000` 显示为 `1.2万`。
 * 不做四舍五入到整数位以外的事 —— 列表里的计数只是参考量级。
 */
export function compactCount(value: number | null | undefined): string {
  const n = num(value)
  if (n < 10000) return String(n)
  // toFixed(1) 后去掉末尾的 `.0`，避免出现 "1.0万"
  return `${(n / 10000).toFixed(1).replace(/\.0$/, '')}万`
}

/* ---------------------------------------------------------------------------
 * 时间
 * ------------------------------------------------------------------------- */

/**
 * 把契约的 `date-time` 字符串格式化成列表用的相对时间。
 *
 * 规则（论坛类产品的常见口径）：
 * - 1 分钟内 → 「刚刚」
 * - 1 小时内 → 「N 分钟前」
 * - 24 小时内 → 「N 小时前」
 * - 7 天内 → 「N 天前」
 * - 超过 7 天 → 「YYYY-MM-DD」
 *
 * ⚠️ 解析失败（字段缺失、格式变化）时返回空串而**不是** `Invalid Date`：
 *    后者会原样渲染到界面上，比空着更难看且暴露内部细节。
 */
export function relativeTime(input: string | null | undefined): string {
  const raw = (input ?? '').trim()
  if (!raw) return ''

  const ts = Date.parse(raw)
  if (Number.isNaN(ts)) return ''

  const diffMs = Date.now() - ts
  // 后端与浏览器时钟可能有极小偏差 → 未来时间按「刚刚」处理，不显示负数
  if (diffMs < 60_000) return '刚刚'

  const minutes = Math.floor(diffMs / 60_000)
  if (minutes < 60) return `${minutes} 分钟前`

  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours} 小时前`

  const days = Math.floor(hours / 24)
  if (days < 7) return `${days} 天前`

  /*
   * 超过 7 天用绝对日期。
   * 刻意**手拼**而不是用 `toLocaleDateString`：各端（H5 / 小程序 / App）
   * 对 locale 与补零的实现不一致，手拼能保证三端输出逐字相同。
   * 注意 `getMonth()` 从 0 开始。
   */
  const d = new Date(ts)
  const mm = String(d.getMonth() + 1).padStart(2, '0')
  const dd = String(d.getDate()).padStart(2, '0')
  return `${d.getFullYear()}-${mm}-${dd}`
}

/* ---------------------------------------------------------------------------
 * 网盘
 * ------------------------------------------------------------------------- */

/**
 * 网盘类型码 → 名称。
 *
 * 依据：`openapi.json` 的 `PostCreateRequest.diskType` 描述 ——
 * `1 百度 / 2 阿里 / 3 夸克 / 4 天翼 / 5 迅雷 / 6 其他`。
 *
 * ⚠️ 契约里 `diskType` 是**整数且没有 enum**，所以这份映射表只能来自字段描述。
 *    若后端新增类型码，前端在拿到新契约前会退化为「其他」——
 *    这是刻意的降级：**显示错名字比显示「其他」更危险**。
 */
export const DISK_TYPES: ReadonlyArray<{ value: number; label: string }> = [
  { value: 1, label: '百度网盘' },
  { value: 2, label: '阿里云盘' },
  { value: 3, label: '夸克网盘' },
  { value: 4, label: '天翼云盘' },
  { value: 5, label: '迅雷网盘' },
  { value: 6, label: '其他网盘' },
]

/** 网盘类型码 → 展示名。未知码统一显示「其他网盘」 */
export function diskTypeLabel(diskType: number | null | undefined): string {
  const t = num(diskType)
  const hit = DISK_TYPES.find((item) => item.value === t)
  return hit ? hit.label : '其他网盘'
}

/**
 * 一键复制的文案（**格式由《技术方案》§5.5 锁定，前端只负责复制**）。
 *
 * 逐字格式：
 * ```
 * 链接：{disk_url}
 * 提取码：{disk_code}
 * 来自 Hy论坛
 * ```
 *
 * ⚠️ 两条边界：
 * 1. `disk_code` 为空（阿里云盘/夸克无提取码机制）时**整行省略**，
 *    不能输出「提取码：」后跟空白 —— 用户会以为漏复制了。
 * 2. 没有 `diskUrl` 时返回空串，调用方据此不渲染复制按钮。
 *
 * 注意这里**不加任何额外说明文字**（如"请用浏览器打开"）：那是 UI 层的事，
 * 文案格式一旦被复制内容污染，就与 §5.5 的锁定格式不一致了。
 */
export function buildDiskCopyText(
  diskUrl: string | null | undefined,
  diskCode: string | null | undefined
): string {
  const url = (diskUrl ?? '').trim()
  if (!url) return ''

  const code = (diskCode ?? '').trim()
  const lines = [`链接：${url}`]
  if (code) lines.push(`提取码：${code}`)
  lines.push('来自 Hy论坛')
  return lines.join('\n')
}

/* ---------------------------------------------------------------------------
 * 帖子字段归一化（页面直接用，避免模板里散落 `?? `）
 * ------------------------------------------------------------------------- */

/** 列表项视图：把可选字段收敛成可直接渲染的值 */
export interface PostListItemView {
  id: number
  boardId: number
  boardName: string
  title: string
  /** 封面地址（可为空串表示无图）。**契约里列表字段是 `coverUrl`，不是 `thumbUrl`** */
  coverUrl: string
  imageCount: number
  viewCount: number
  likeCount: number
  commentCount: number
  authorName: string
  createdAtText: string
  isTop: boolean
  isEssence: boolean
  /** 列表项点击后跳详情用的地址 */
  detailUrl: string
}

/**
 * 列表项归一化。
 *
 * ⚠️ **关于列表图字段（口径 6 的一处口径差，已在报告里登记）**：
 *    任务书 §5 第 6 条写「列表图用 `thumbUrl`（缩略图），不要用原图」，
 *    但**契约里 `PostSummaryVO` 只有 `coverUrl`，没有 `thumbUrl`**
 *    （`thumbUrl` 只存在于详情用的 `PostImageVO` 上）。
 *    因此列表只能用 `coverUrl`。**前端不自己拼缩略图 URL**（口径 7 同源理由：
 *    拼 URL 等于把 OSS 图片处理参数变成前端合约）。
 *    若后端希望列表走缩略图，应由后端在 `coverUrl` 里给出缩略图地址，或补 `thumbUrl` 字段。
 */
export function toListItem(post: PostSummaryVO): PostListItemView {
  const id = num(post.id)
  return {
    id,
    boardId: num(post.boardId),
    boardName: text(post.boardName),
    title: text(post.title),
    coverUrl: text(post.coverUrl),
    imageCount: num(post.imageCount),
    viewCount: num(post.viewCount),
    likeCount: num(post.likeCount),
    commentCount: num(post.commentCount),
    authorName: authorName(post.author),
    createdAtText: relativeTime(post.createdAt),
    isTop: bool(post.isTop),
    isEssence: bool(post.isEssence),
    detailUrl: `/pages/post/detail?id=${id}`,
  }
}

/** 详情页图片视图 */
export interface PostImageView {
  id: number
  /** 点击预览用的大图地址（原图） */
  url: string
  /** 九宫格用的缩略图地址；为空时回退到原图 */
  thumbUrl: string
}

/**
 * 详情图片归一化。
 *
 * 依据口径 7：**只展示后端返回的 `images`**（后端已过滤 `audit_status=2`），
 * 前端**不得自己拼图片 URL**，也不得过滤/重排。
 * 唯一的处理是"缩略图为空时回退原图"—— 这不是拼 URL，只是缺省值。
 */
export function toImageView(image: PostImageVO): PostImageView {
  const url = text(image.url)
  return {
    id: num(image.id),
    url,
    // 注意 trim：空白字符串不能当有效地址用
    thumbUrl: text(image.thumbUrl).trim() || url,
  }
}

/**
 * 详情页是否展示网盘卡片。
 *
 * 依据口径 9 的同源判断：**版块的 `isResource` 决定网盘字段是否出现**。
 * 详情页用 `PostDetailVO.boardIsResource`；但同时要求 `diskUrl` 非空 ——
 * 资源版块的历史帖可能没有网盘链接，此时不该渲染一张空卡片。
 */
export function shouldShowDiskCard(post: PostDetailVO | null): boolean {
  if (!post) return false
  return bool(post.boardIsResource) && text(post.diskUrl).trim().length > 0
}
