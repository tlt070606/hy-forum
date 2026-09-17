/**
 * 热门内容假数据（**设计占位，不是已交付功能**）。
 *
 * ==========================================================================
 * 为什么这里必须有假数据（而不是"偷懒"）
 * ==========================================================================
 * 参考图右侧栏与左侧栏里有四类内容：**热门话题、推荐关注、热门活动、活跃用户**。
 * 这四类在本项目里**既没有数据表、也没有接口**：
 *
 * - `docs/db/schema.sql` 的 16 张表里**没有 `topic` / `tag` / hashtag 任何一张表**
 *   （实测：`user` `board` `post` `post_image` `comment` `post_like` `comment_like`
 *   `follow` `report` `notification` `admin` `sensitive_word` `sys_config`
 *   `invite_code` `post_collect` `admin_operation_log`）；
 * - 契约（`openapi.json`，14 个路径）里也没有任何相关端点。
 *
 * 所以它们**只能**是假数据。要做成真的，先得加表 + 加接口 —— 那是契约变更（L1 的事）。
 * 已作为提案登记进交付报告，**不要**照这里的字段去写后端。
 *
 * ==========================================================================
 * 与"帖子流"的分界（这一条很重要，决定了哪些是真、哪些是假）
 * ==========================================================================
 * - **帖子流走真接口**（`GET /api/posts`），内容用 SQL 灌进开发库；
 *   所以首页信息流里看到的每一个帖子都是**真的从后端读出来的**。
 * - **本文件里的内容走假数据**（前端常量），因为后端根本无处可查。
 *
 * 需求方 2026-09-16 的要求是「用假数据搭 IA，但假数据要找当下的热点，不是全假」——
 * 本文件就是那一半"假"，另一半（帖子/版块）是真的。
 *
 * ⚠️ **关于"当下热点"的诚实说明**：本会话**完全没有出网**
 * （实测 `curl https://example.com` → `http=000`），因此我**查不到任何实时热搜榜**。
 * 下面这些话题是我按"近期大众普遍感兴趣的方向"编的（AI 工具、新能源、带饭、
 * 反内耗、手机摄影…），风格与参考图里那 7 个同类，**但不是某一日的真实榜单**。
 * 需要真实榜单 → 由需求方贴一份，替换本文件即可（数据结构不变）。
 */

/* ---------------------------------------------------------------------------
 * 类型
 * ------------------------------------------------------------------------- */

/** 话题热度角标。取值与参考图一致：沸 > 热 > 新 */
export type TopicBadge = 'boil' | 'hot' | 'new'

export interface HotTopic {
  /** 话题名（**不带 `#`**，`#` 由组件渲染，避免两处各写一个） */
  name: string
  /** 阅读量（人肉数字，单位「万」；组件负责格式化） */
  viewsTenThousand: number
  /** 讨论数 */
  discussCount: number
  /** 角标；`null` = 不显示 */
  badge: TopicBadge | null
}

export interface RecommendPerson {
  nickname: string
  /** 一句话简介 */
  bio: string
  /** 是否认证（对应参考图名字后的蓝色 V） */
  verified: boolean
  /** 头像底色。没有真实头像图，用「昵称首字 + 底色」的字母头像兜底 */
  avatarBg: string
}

export interface HotActivity {
  title: string
  desc: string
  /** 参与人数（人肉数字） */
  joinCount: number
}

/* ---------------------------------------------------------------------------
 * 热门话题
 * ---------------------------------------------------------------------------
 * 排列顺序 = 榜单顺序（热度从高到低），**组件不重排** ——
 * 重排会让"榜单"这个语义失效（真实场景下名次由后端算）。
 */
export const HOT_TOPICS: readonly HotTopic[] = [
  { name: 'AI工具实测', viewsTenThousand: 128.6, discussCount: 3421, badge: 'boil' },
  { name: '国产新能源出海', viewsTenThousand: 96.4, discussCount: 2180, badge: 'hot' },
  { name: '带饭上班一周', viewsTenThousand: 78.2, discussCount: 1642, badge: 'hot' },
  { name: '小城慢生活', viewsTenThousand: 63.5, discussCount: 1204, badge: 'new' },
  { name: '职场反内耗', viewsTenThousand: 57.9, discussCount: 986, badge: null },
  { name: '手机摄影入门', viewsTenThousand: 46.3, discussCount: 812, badge: null },
  { name: '一人食菜谱', viewsTenThousand: 39.8, discussCount: 655, badge: null },
  { name: '旧物改造', viewsTenThousand: 28.1, discussCount: 470, badge: null },
  { name: '夜跑打卡', viewsTenThousand: 21.7, discussCount: 388, badge: null },
  { name: '读书笔记', viewsTenThousand: 17.4, discussCount: 302, badge: null },
] as const

/** 左侧栏只展示前 N 个（参考图是 3 个） */
export const SIDEBAR_TOPIC_COUNT = 3

/** 右侧栏展示的榜单长度（参考图是 7 个） */
export const RANKING_TOPIC_COUNT = 7

/* ---------------------------------------------------------------------------
 * 推荐关注
 * ------------------------------------------------------------------------- */
export const RECOMMEND_PEOPLE: readonly RecommendPerson[] = [
  { nickname: '阿哲聊科技', bio: '每天一个 AI 实用技巧', verified: true, avatarBg: '#6b4bc4' },
  { nickname: '厨房里的老王', bio: '家常菜十年，专治不会做饭', verified: true, avatarBg: '#ff7d00' },
  { nickname: '程序媛小满', bio: '后端开发 / 分享搬砖日常', verified: false, avatarBg: '#3b82f6' },
  { nickname: '山野笔记', bio: '徒步与露营装备实测', verified: false, avatarBg: '#07c160' },
  { nickname: '一只阿柴', bio: '养宠八年，踩过的坑都在这', verified: false, avatarBg: '#f53f3f' },
] as const

/** 「换一批」一次换掉几个（参考图右侧有这个入口） */
export const RECOMMEND_BATCH_SIZE = 3

/* ---------------------------------------------------------------------------
 * 热门活动
 * ------------------------------------------------------------------------- */
export const HOT_ACTIVITIES: readonly HotActivity[] = [
  { title: '九月读书打卡', desc: '连续 7 天，读完一本就好', joinCount: 12384 },
  { title: '秋日随手拍', desc: '用手机记录你窗外的秋天', joinCount: 8643 },
  { title: '下班后的一小时', desc: '分享你的下班解压方式', joinCount: 5201 },
  { title: '一周不点外卖', desc: '自己做饭，省钱又安心', joinCount: 3176 },
] as const

/* ---------------------------------------------------------------------------
 * 站点头部数据（参考图左栏底部的「今日数据」卡片）
 * ------------------------------------------------------------------------- */

/**
 * 活跃用户数。
 *
 * ⚠️ **这个数字是编的**，而且**必须是编的**：`user` 表里能查到的是"注册总数"，
 *    而"活跃"需要行为数据（登录时间、发帖、评论）—— 契约里没有任何此类接口，
 *    前端也拿不到（`GET /api/user/me` 只给当前用户自己的信息）。
 *
 * 帖子总数**不在这里** —— 它走真接口（`GET /api/posts` 的 `total`），
 * 因为那个数据是真的、且免费。见 `pages/index/index.vue`。
 */
export const MOCK_ACTIVE_USER_COUNT = 8

/** 未读通知数。通知接口属 M5，契约（14 路径）里没有 → 假数据 */
export const MOCK_UNREAD_NOTIFICATION_COUNT = 3
