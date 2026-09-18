/**
 * 展示层归一化：**把契约的可选字段收敛成可直接渲染的值**。
 *
 * ==========================================================================
 * 为什么需要这一层
 * ==========================================================================
 * 契约里几乎所有 schema 都**没有 `required` 声明**，所以生成类型把每个字段都标为可选
 * （`title?: string`）。这是契约的真实形状，前端**不能**用 `as` 断言成必填（那是关掉检查）。
 * 但若在模板里逐处写 `post.title ?? ''`，就把"契约可选"这件事散落到几十个地方。
 *
 * 因此收敛规则只写一次：**页面拿到的都是已收敛的值**。
 * ⚠️ 这里只做"缺省值填充 + 展示格式化"，**不改字段名、不做业务重算**。
 */

import type {
  CommentItemVO,
  CommentReplyVO,
  PostDetailVO,
  PostImageVO,
  PostSummaryVO,
  UserBriefVO,
} from '@/api/types'

/* ---------------------------------------------------------------------------
 * 基础收敛
 * ------------------------------------------------------------------------- */

/** 字符串字段收敛。`null` / `undefined` → 空串 */
export function text(value: string | null | undefined): string {
  return value ?? ''
}

/** 数字字段收敛。`null` / `undefined` → 0 */
export function num(value: number | null | undefined): number {
  return value ?? 0
}

/** 布尔字段收敛。`null` / `undefined` → false */
export function bool(value: boolean | null | undefined): boolean {
  return value ?? false
}

/* ---------------------------------------------------------------------------
 * 数字格式化
 * ------------------------------------------------------------------------- */

/**
 * 大数值压缩（列表里的浏览/点赞数）。
 *
 * 边界：只处理非负；`>= 10000` 显示为 `1.2万`。
 * 不做四舍五入以外的处理 —— 列表里的计数只是参考量级。
 */
export function compactCount(value: number | null | undefined): string {
  const n = num(value)
  if (n < 0) return '0'
  if (n < 10000) return String(n)
  // toFixed(1) 后去掉末尾的 `.0`，避免出现 "1.0万"
  return `${(n / 10000).toFixed(1).replace(/\.0$/, '')}万`
}

/**
 * 话题热度格式化：输入单位是**万**，输出带单位的展示串。
 *
 * 为什么单独一个函数而不是复用 `compactCount`：后者的输入是"个数"，
 * 而话题榜的数据源本身就以"万"为单位（128.6 万）。两个单位混用极易出错 ——
 * 这也是假数据模块把字段命名为 `viewsTenThousand` 的原因（让单位写在名字里）。
 */
export function formatHeat(viewsTenThousand: number): string {
  if (!Number.isFinite(viewsTenThousand) || viewsTenThousand <= 0) return '0'
  // 整数万不显示小数点：98 万 而不是 98.0 万
  return `${viewsTenThousand.toFixed(1).replace(/\.0$/, '')}万`
}

/* ---------------------------------------------------------------------------
 * 时间
 * ------------------------------------------------------------------------- */

/**
 * 契约的 `date-time` 字符串 → 列表用的相对时间。
 *
 * 规则：1 分钟内「刚刚」/ 1 小时内「N 分钟前」/ 24 小时内「N 小时前」/
 * 7 天内「N 天前」/ 更早「YYYY-MM-DD」。
 *
 * ⚠️ 解析失败时返回空串而**不是** `Invalid Date` —— 后者会原样渲染到界面上，
 *    比空着更难看且暴露内部细节。
 */
export function relativeTime(input: string | null | undefined): string {
  const raw = (input ?? '').trim()
  if (!raw) return ''

  const ts = Date.parse(raw)
  if (Number.isNaN(ts)) return ''

  const diff = Date.now() - ts
  // 后端与浏览器时钟可能有极小偏差 → 未来时间按「刚刚」处理，不显示负数
  if (diff < 60_000) return '刚刚'

  const minutes = Math.floor(diff / 60_000)
  if (minutes < 60) return `${minutes} 分钟前`

  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours} 小时前`

  const days = Math.floor(hours / 24)
  if (days < 7) return `${days} 天前`

  /*
   * 超过 7 天用绝对日期。刻意**手拼**而不是 `toLocaleDateString`：
   * 各端（H5 / 小程序 / App）对 locale 与补零的实现不一致，手拼能保证三端逐字相同。
   * 注意 `getMonth()` 从 0 开始。
   */
  const d = new Date(ts)
  const mm = String(d.getMonth() + 1).padStart(2, '0')
  const dd = String(d.getDate()).padStart(2, '0')
  return `${d.getFullYear()}-${mm}-${dd}`
}

/* ---------------------------------------------------------------------------
 * 作者
 * ------------------------------------------------------------------------- */

/**
 * 作者展示名。
 *
 * 契约里 `UserBriefVO` 只有 `nickname`（没有 `username`），所以回退链只有两级。
 * **不要**在这里编造"用户123"之类的名字 —— 那会让空昵称看起来像真实数据。
 */
export function authorName(author: UserBriefVO | null | undefined): string {
  const nickname = author?.nickname?.trim()
  return nickname ? nickname : '匿名用户'
}

/**
 * 字母头像的取字规则：取昵称**首字符**。
 *
 * 用 `Array.from` 而不是 `nickname[0]`：昵称可能以 emoji 或其它**代理对**字符开头，
 * `[0]` 会截出半个字符（渲染成"口"），这是中文/emoji 场景下的经典 bug。
 */
export function avatarInitial(nickname: string): string {
  const chars = Array.from(nickname.trim())
  return chars.length ? chars[0] : '?'
}

/** 字母头像的底色候选（取自设计令牌的语义色，不额外引入颜色） */
const AVATAR_BG = ['#6b4bc4', '#3b82f6', '#07c160', '#ff7d00', '#f53f3f', '#8b5cf6']

/**
 * 由昵称**确定性地**挑一个底色。
 *
 * 为什么不用随机：同一用户在列表里刷新一次就换一次颜色，看起来像 bug。
 * 这里用字符码求和取模，保证同一个昵称永远同一个颜色。
 */
export function avatarColor(nickname: string): string {
  let sum = 0
  for (const ch of nickname) sum += ch.codePointAt(0) ?? 0
  return AVATAR_BG[sum % AVATAR_BG.length]
}

/* ---------------------------------------------------------------------------
 * 网盘
 * ------------------------------------------------------------------------- */

/**
 * 网盘类型码 → 名称。
 *
 * 依据：契约 `PostCreateRequest.diskType` 的字段描述 ——
 * `1 百度 / 2 阿里 / 3 夸克 / 4 天翼 / 5 迅雷 / 6 其他`。
 *
 * ⚠️ 契约里 `diskType` 是**整数且没有 enum**，所以这份映射只能来自字段描述。
 *    后端新增类型码时，前端在拿到新契约前会退化为「其他网盘」——
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
  return DISK_TYPES.find((item) => item.value === t)?.label ?? '其他网盘'
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
 * 1. `diskCode` 为空（阿里云盘/夸克无提取码机制）时**整行省略**，
 *    不能输出「提取码：」后跟空白 —— 用户会以为漏复制了。
 * 2. 没有 `diskUrl` 时返回空串，调用方据此不渲染复制按钮。
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
 * 帖子
 * ------------------------------------------------------------------------- */

/** 信息流卡片的视图模型（字段均已收敛，模板可直接用） */
export interface PostCardView {
  id: number
  boardId: number
  boardName: string
  title: string
  /** 封面地址（空串 = 无图）。**契约里列表字段是 `coverUrl`，不是 `thumbUrl`** */
  coverUrl: string
  imageCount: number
  viewCount: number
  likeCount: number
  commentCount: number
  collectCount: number
  authorName: string
  authorAvatarUrl: string
  timeText: string
  isTop: boolean
  isEssence: boolean
  /** 点击卡片跳详情 */
  detailUrl: string
  /** 点击版块标签跳版块页 */
  boardUrl: string
}

/**
 * 列表项归一化。
 *
 * ⚠️ **列表图字段：用 `coverUrl`，不是 `thumbUrl`（一处口径差，已登记）**
 *    任务书 §5 第 6 条写「列表图用 `thumbUrl`（缩略图），不要用原图」，
 *    但**契约里 `PostSummaryVO` 只有 `coverUrl`，没有 `thumbUrl`**
 *    （`thumbUrl` 只存在于详情用的 `PostImageVO` 上）。
 *    因此列表只能用 `coverUrl`，且**前端不自己拼缩略图 URL** ——
 *    拼 URL 等于把 OSS 图片处理参数变成前端合约（与口径 7 同源被禁）。
 *    若后端希望列表走缩略图，应由后端在 `coverUrl` 里给缩略图地址，或补 `thumbUrl` 字段。
 */
export function toPostCard(post: PostSummaryVO): PostCardView {
  const id = num(post.id)
  const boardId = num(post.boardId)
  const name = authorName(post.author)

  return {
    id,
    boardId,
    boardName: text(post.boardName),
    title: text(post.title),
    coverUrl: text(post.coverUrl),
    imageCount: num(post.imageCount),
    viewCount: num(post.viewCount),
    likeCount: num(post.likeCount),
    commentCount: num(post.commentCount),
    collectCount: num(post.collectCount),
    authorName: name,
    authorAvatarUrl: text(post.author?.avatarUrl),
    timeText: relativeTime(post.createdAt),
    isTop: bool(post.isTop),
    isEssence: bool(post.isEssence),
    detailUrl: `/pages/post/detail?id=${id}`,
    boardUrl: `/pages/board/index?id=${boardId}`,
  }
}

/** 详情页图片的视图模型 */
export interface PostImageView {
  id: number
  /** 点击预览用的大图地址（原图） */
  url: string
  /** 九宫格用的缩略图地址；为空时回退原图（这不是拼 URL，只是缺省值） */
  thumbUrl: string
}

/**
 * 详情图片归一化。
 *
 * 依据口径 7：**只展示后端返回的 `images`**（后端已过滤 `audit_status=2`），
 * 前端**不得自己拼图片 URL、不得过滤、不得重排**。
 */
export function toImageView(image: PostImageVO): PostImageView {
  const url = text(image.url)
  return {
    id: num(image.id),
    url,
    // trim：空白字符串不能当有效地址用
    thumbUrl: text(image.thumbUrl).trim() || url,
  }
}

/**
 * 详情页是否展示网盘卡片。
 *
 * 依据口径 9 的同源判断：版块的 `isResource` 决定网盘字段是否出现。
 * 详情页用 `PostDetailVO.boardIsResource`，但同时要求 `diskUrl` 非空 ——
 * 资源版块的历史帖可能没有网盘链接，此时不该渲染一张空卡片。
 */
export function shouldShowDiskCard(post: PostDetailVO | null): boolean {
  if (!post) return false
  return bool(post.boardIsResource) && text(post.diskUrl).trim().length > 0
}

/* ---------------------------------------------------------------------------
 * 评论（M4）
 * ------------------------------------------------------------------------- */

/** 楼中楼的视图模型 */
export interface ReplyView {
  id: number
  content: string
  likeCount: number
  authorId: number
  authorName: string
  authorAvatarUrl: string
  timeText: string
  /** 被回复者昵称。为空 = 直接回复主楼 */
  replyToNickname: string
}

/** 主楼评论的视图模型 */
export interface CommentView {
  id: number
  content: string
  likeCount: number
  /** 该主楼下的楼中楼**总数**（用于"查看全部 N 条回复"） */
  replyCount: number
  authorId: number
  authorName: string
  authorAvatarUrl: string
  timeText: string
  /** 契约给的**预览**（前若干条），不是全部 —— 要看全部得调 `fetchReplies` */
  replies: ReplyView[]
}

/** 楼中楼归一化 */
export function toReplyView(r: CommentReplyVO): ReplyView {
  const name = text(r.author?.nickname).trim() || '匿名用户'
  return {
    id: num(r.id),
    content: text(r.content),
    likeCount: num(r.likeCount),
    authorId: num(r.author?.id),
    authorName: name,
    authorAvatarUrl: text(r.author?.avatarUrl),
    timeText: relativeTime(r.createdAt),
    replyToNickname: text(r.replyToNickname).trim(),
  }
}

/** 主楼归一化。`replies` 是预览，**不过滤不重排**（口径 7 的同源理由：后端给什么就显示什么） */
export function toCommentView(c: CommentItemVO): CommentView {
  const name = text(c.author?.nickname).trim() || '匿名用户'
  return {
    id: num(c.id),
    content: text(c.content),
    likeCount: num(c.likeCount),
    replyCount: num(c.replyCount),
    authorId: num(c.author?.id),
    authorName: name,
    authorAvatarUrl: text(c.author?.avatarUrl),
    timeText: relativeTime(c.createdAt),
    replies: (c.replies ?? []).map(toReplyView),
  }
}
