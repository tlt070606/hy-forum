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
  CollectionItemVO,
  CommentItemVO,
  CommentReplyVO,
  FeedItemVO,
  FollowUserVO,
  PostDetailVO,
  PostImageVO,
  PostSummaryVO,
  UserBriefVO,
  UserCommentVO,
  UserProfileVO,
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
 * 一键复制的文案。
 *
 * ==========================================================================
 * ⚠️ 2026-09-18 口径变更（**与《技术方案》§5.5 的原文不一致，需 L1 同步文档**）
 * ==========================================================================
 * §5.5 原本锁定的是三行格式：
 * ```
 * 链接：{disk_url}
 * 提取码：{disk_code}
 * 来自 Hy论坛
 * ```
 * 需求方 2026-09-18 明确要求：「**直接带上链接就行了，不要在前面加什么东西**」，
 * 并在被告知下面那条代价后**仍然选择**"无条件只有链接"。所以现在只复制链接本身。
 *
 * ⚠️ **代价（需求方已知情并接受）**：后端保存时会把链接里的 `?pwd=` **拆走**、
 *    把提取码存进 `diskCode`（§5.5 第 2 条），因此**链接本身已不含提取码** ——
 *    对**百度网盘**这类带提取码的帖子，"只复制链接"等于用户拿到的链接点不开。
 *    缓解手段：详情页仍然**把提取码显示在页面上**，用户可以自己选中复制
 *    （复制按钮不再包含它，但它没有消失）。
 *
 * ⚠️ 任务书口径 8 说该格式"由后端 `DiskCopyText` 锁定"。前端这一改会让
 *    **前端拼的文案与后端那个类的约定不一致** —— 契约里没有任何字段暴露这段文案，
 *    所以只有 L1 能把它对齐（改 §5.5 原文 + 后端类）。已登记进交付报告。
 *
 * 返回空串表示"没有可复制的链接"，调用方据此不渲染复制按钮。
 */
export function buildDiskCopyText(
  diskUrl: string | null | undefined,
  _diskCode?: string | null | undefined
): string {
  // 只返回链接本身（口径变更见函数头）。第二个参数保留，是为了不改动调用方签名，
  // 也为了将来若改回"带提取码"时不必再去改所有调用点。
  return (diskUrl ?? '').trim()
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
export function toPostCard(post: PostSummaryVO | FeedItemVO): PostCardView {
  const id = num(post.id)
  const boardId = num(post.boardId)
  const name = authorName(post.author)

  /*
   * ==========================================================================
   * 封面：**优先用带签名的那个地址**
   * ==========================================================================
   * 实测（2026-09-20）：**同一个概念的字段在不同接口里签名行为不一致** ——
   * - `GET /api/posts`（首页全部流）的 `coverUrl` **带签名** ✓ 能显示；
   * - `GET /api/feed`（关注流）的 `coverUrl` **是裸地址** ✗ 私有桶下必然 403 → 图显示不出来；
   *   但同一个响应里 `imageThumbs[]` **是带签名的** ✓。
   *
   * 所以在**前端这一层**做兜底：`coverUrl` 没签名、而 `imageThumbs` 里有带签名的，
   * 就用 `imageThumbs[0]`。这不违反"前端不自己拼图片地址"（铁律 5/8、口径 7）——
   * 两个值**都是接口给的**，我们只是挑了一个能用的。
   * ⚠️ 根因仍在后端（读时签名没覆盖 `/api/feed`），已作为 CR-R 登记：
   *    等后端补上，这段兜底会自然退化成"永远走 coverUrl"，可以删。
   */
  const rawCover = text(post.coverUrl)
  const thumbs = (post as { imageThumbs?: unknown }).imageThumbs
  const signedThumb =
    Array.isArray(thumbs)
      ? (thumbs.find((t) => typeof t === 'string' && t.includes('Signature=')) as string | undefined)
      : undefined
  const coverUrl = rawCover.includes('Signature=') ? rawCover : signedThumb || rawCover

  return {
    id,
    boardId,
    boardName: text(post.boardName),
    title: text(post.title),
    coverUrl,
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

/* ---------------------------------------------------------------------------
 * 个人主页 / 关注 / 收藏（M4 第二批）
 * ------------------------------------------------------------------------- */

/** 个人主页资料（已归一化：数字一定是 number，字符串一定不是 undefined） */
export interface ProfileView {
  id: number
  nickname: string
  avatarUrl: string
  /** 简介。空串 = 未填（界面按"这个人还没写简介"处理，不显示空行） */
  bio: string
  postCount: number
  followCount: number
  fansCount: number
  /** 获赞总数（他收到的赞，不是他给出的） */
  likeReceivedCount: number
  /** 我是否关注了他。**契约真有的字段**（`UserProfileVO.isFollowing`） */
  isFollowing: boolean
  /** 他是否关注了我（用于"互相关注/回关"的文案） */
  isFollowedBy: boolean
  joinedText: string
  /*
   * ⚠️ 契约里 `gender` 与 `level` 是**裸 integer，没有 enum、没有取值说明**，
   *    前端猜不出编码（0 是"未知"还是"男"？）——所以**刻意不在这里建模、界面也不显示**。
   *    已作为 CR 登记（报告 §T）。契约补上值域后再加。
   */
}

export function toProfileView(p: UserProfileVO): ProfileView {
  return {
    id: num(p.id),
    nickname: text(p.nickname).trim() || '匿名用户',
    avatarUrl: text(p.avatarUrl),
    bio: text(p.bio).trim(),
    postCount: num(p.postCount),
    followCount: num(p.followCount),
    fansCount: num(p.fansCount),
    likeReceivedCount: num(p.likeReceivedCount),
    isFollowing: bool(p.isFollowing),
    isFollowedBy: bool(p.isFollowedBy),
    joinedText: relativeTime(p.createdAt),
  }
}

/** 关注/粉丝列表项 */
export interface FollowUserView {
  userId: number
  nickname: string
  avatarUrl: string
  bio: string
  followedAtText: string
}

export function toFollowUserView(u: FollowUserVO): FollowUserView {
  return {
    userId: num(u.userId),
    nickname: text(u.nickname).trim() || '匿名用户',
    avatarUrl: text(u.avatarUrl),
    bio: text(u.bio).trim(),
    followedAtText: relativeTime(u.followedAt),
  }
}

/**
 * 我的收藏项。
 *
 * ⚠️ `CollectionItemVO` **没有作者字段**（收藏记录本身就不存作者）——
 *    所以收藏卡片**不能带作者行**，这是契约事实，不是我省略的。
 *    已在报告里说明；若将来要显示作者，需要后端补字段。
 */
export interface CollectionView {
  postId: number
  boardId: number
  title: string
  coverUrl: string
  imageCount: number
  likeCount: number
  commentCount: number
  collectCount: number
  /** 收藏时间（`createdAt` 在这个 VO 里表示"何时收藏的"，不是发帖时间） */
  collectedText: string
  detailUrl: string
}

export function toCollectionView(c: CollectionItemVO): CollectionView {
  const postId = num(c.postId)
  return {
    postId,
    boardId: num(c.boardId),
    title: text(c.title),
    coverUrl: text(c.coverUrl),
    imageCount: num(c.imageCount),
    likeCount: num(c.likeCount),
    commentCount: num(c.commentCount),
    collectCount: num(c.collectCount),
    collectedText: relativeTime(c.createdAt),
    detailUrl: `/pages/post/detail?id=${postId}`,
  }
}

/** 他发的评论（带 `postTitle`，整条可点回原帖） */
export interface UserCommentView {
  id: number
  postId: number
  postTitle: string
  content: string
  likeCount: number
  replyCount: number
  timeText: string
  /** 原帖地址（**不做评论定位** —— 契约没有锚点机制，详情页也不支持跳转到某条评论） */
  postUrl: string
}

export function toUserCommentView(c: UserCommentVO): UserCommentView {
  const postId = num(c.postId)
  return {
    id: num(c.id),
    postId,
    postTitle: text(c.postTitle).trim() || '（无标题）',
    content: text(c.content),
    likeCount: num(c.likeCount),
    replyCount: num(c.replyCount),
    timeText: relativeTime(c.createdAt),
    postUrl: `/pages/post/detail?id=${postId}`,
  }
}
