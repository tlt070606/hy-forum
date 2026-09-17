# W5-FE-M3 交付报告（前端接版块 / 列表 / 搜索 / 详情 / 发帖 / OSS 直传上传）

> 任务书：[`../tasks/W5-FE-M3-前端接版块与帖子与上传.md`](../tasks/W5-FE-M3-前端接版块与帖子与上传.md)
> 角色：**前端 L2 实现者** · 日期：2026-09-16
> 写权：`web/**` + 本文件（本文件是 `docs/` 下唯一被改动的文件）

> ## ⛔ 本报告描述的交付**已被撤回**（2026-09-16，需求方决定）
>
> 需求方验收后**不满意**：「整体太『骨架』、不像成品」，要求**重做**。
> 处置（已执行）：
> - **`web/` 下 M3 第一版的痕迹已清零** —— 新增的 11 个文件删除、我改过的 4 个文件回退到 `6583e15`；
>   实测 `git diff 6583e15 -- web/` **输出为空**（即 `web/` 与 M3 之前逐字节一致）。
> - 重做范围：**M3 前端全部推倒（含 api/utils 层）**，**E2E 一起重写**；
>   之前定的四条布局（版块宫格首页 / 悬浮「＋」/ 左文右单图 / 两列宫格）**全部作废**，
>   以需求方提供的新参考为准。
> - **旧版代码没有丢** —— 完整保留在提交 `a53074a`，`git show a53074a` 可查。
>
> **所以：本报告下面的内容只作为"第一版做了什么、撞到哪些契约问题"的记录阅读，
> 不要据此做验收** —— 它描述的代码此刻已不在工作区里。
> 其中仍然有效的部分：§5（CR-F 的时序与结论）、§6（**CR-G / CR-H 至今仍未裁决，会继续阻塞重做后的上传**）、
> §7（契约作为第一道验证手段暴露出的 4 处歧义）。
> 重做完成后，本报告会被改写为新一版的交付报告。

---

# 第 2 版（进行中）：按参考图重做三栏

> 需求方给了三张桌面端截图（三栏：左导航 + 信息流 + 右栏）与参考站 URL。
> **注意：我无出网，URL 打不开**（`curl https://example.com` → `http=000`），
> 所以参考依据**只有那三张截图**。
> 参考站是桌面形态，而我们交付形态是 uni-app 三端 —— 需求方裁定走
> **响应式：宽屏三栏 / 窄屏单栏**（不改 ADR-0011）。
> 节奏由需求方定为「**先做三栏外壳 + 首页，再往下铺**」。

## A. 本轮做完的（已自检）

| 内容 | 文件 |
|---|---|
| 设计令牌（紫、**px** 单位、三栏骨架尺寸与断点） | `web/src/styles/variables.scss`、`web/src/uni.scss` |
| 图标集（20+ 个纯 CSS 图标，含 M2 沿用的 8 个） | `web/src/components/HyIcon.vue`（**取代** 已删除的 `Icon.vue`） |
| 头像（真图 / 字母头像 / 游客图标 三态） | `web/src/components/Avatar.vue` |
| 顶栏（Logo + 搜索 + 通知 + 头像菜单） | `web/src/components/shell/TopBar.vue` |
| 左栏（导航 + 热门话题 + 今日数据） | `web/src/components/shell/LeftRail.vue` |
| 右栏（榜单 + 推荐关注 + 热门活动） | `web/src/components/shell/RightRail.vue` |
| 底部导航（仅窄屏，自绘） | `web/src/components/shell/MobileNav.vue` |
| 三栏外壳（响应式） | `web/src/components/shell/AppShell.vue` |
| 信息流卡片 | `web/src/components/PostCard.vue` |
| 占位页（共用内容 + 6 个稳定路由薄壳） | `web/src/components/PagePlaceholder.vue`、`web/src/pages/placeholder|topic|collect|search|board|post/*` |
| 首页 | `web/src/pages/index/index.vue` |
| 假数据（热点内容） | `web/src/mock/hotContent.ts` |
| 演示数据灌库脚本（幂等） | `web/scripts/seed-demo-data.sql` |
| 截图自检探针 | `web/scripts/shot.mjs` |
| api 层（重建） | `web/src/api/{types,posts,boards,shape}.ts`、`web/src/utils/postView.ts` |

## B. 本轮**没做**的（都是占位页，明确说明未做，不冒充）

话题 / 收藏 / 搜索 / 版块 / 帖子详情 / 发帖 / 设置。
其中**搜索、版块、详情、发帖的接口都已交付**，纯粹是没排上；
**话题、收藏、设置**则等契约（见 D 节）。
**图片上传没做** —— 需求方本轮明确「先不管上传，专心把界面重做」。

## C. 关键根因：第 1 版为什么"看起来像骨架"

**`rpx` + 没有 max-width 约束。**

`rpx` 是响应式像素、**随窗口宽度缩放**（750rpx = 屏幕宽）。旧版全程 rpx，
而 `web/index.html` 的 viewport 只声明 `width=device-width`、**没有任何 max-width**，
于是旧版在 1920px 的桌面浏览器里的表现是：**一个手机版式被拉满整屏、字按比例放大、中间大片空白**。
这正是"整体太骨架、不像成品"的直接来源，**不是配色或审美问题**。

第 2 版因此：**单位改 px**（尺寸不再随窗口漂移）+ **`max-width: 1280px` 居中容器**
+ **1000px 断点**（左 240 + 右 300 + 中栏最小可用 ~420 + 两道 20 间隙）。

## D. 真 / 假数据的边界

需求方口径：「用假数据搭 IA，但假数据要**找当下的热点**，**不是全假**」。
落实为**真接口优先，无表无契约才用假数据**：

| 数据 | 真/假 | 来源 |
|---|---|---|
| 信息流、排序、帖子总数 | **真** | `GET /api/posts`（`sort` 用契约的 `latest\|hot\|essence`） |
| 登录态、头像、昵称、退出登录 | **真** | `GET /api/user/me`、`POST /api/auth/logout` |
| 帖子内容（10 条演示帖） | **假数据、真链路** | 见下方说明 —— SQL 灌进开发库，经**真接口**读出 |
| 热门话题榜 / 推荐关注 / 热门活动 | **假** | `src/mock/hotContent.ts` |
| 今日数据的「活跃用户」 | **假** | 同上（"活跃"需行为数据，契约里没有任何此类接口） |
| 通知未读数 | **假** | 通知接口属 M5 |
| 点赞 / 收藏 / 举报 | **未交付** | 接口属 M4/M5 —— 卡片**只显示计数**，不做成可点的假按钮 |

> **"假数据、真链路"是什么意思**：帖子流走的是真接口，所以帖子必须真的在库里，
> 否则首页看起来是好的、而**契约根本没被碰过**。
> 因此 10 条演示帖由 `web/scripts/seed-demo-data.sql` 灌进开发库 `hy_forum`：
> 6 个演示作者（`demo_*`，`password_hash` 写的是**非法哈希** `demo-account-no-login`，
> 所以这些账号**永远登不进去**，只当作者）+ 10 条帖子（含资源版块带网盘字段、
> 3 条加精、`created_at` 错开 3 小时~6 天，让相对时间有层次）。
> 脚本**幂等**（按 `demo_` 前缀先删后插），可重复执行。
> 脚本开头明确写了"不建表、不改表结构、不动契约"。
> ⚠️ 副作用：开发库 `hy_forum` 里多了一批 `demo_*` 数据；我同时清掉了自己上一轮 E2E 留下的
> 13 条测试帖（`e2e_m3_*` 作者的 `M3资源帖 …`），现状是 10 条帖子、0 条测试残留。

> ⚠️ **"当下热点"的诚实说明**：我**无出网**，查不到任何实时热搜榜。
> 那 10 个话题（AI 工具实测 / 国产新能源出海 / 带饭上班一周 / 小城慢生活 / 职场反内耗 /
> 手机摄影入门 / 一人食菜谱 / 旧物改造 / 夜跑打卡 / 读书笔记）是我按"近期大众普遍感兴趣的方向"
> 编的，风格与参考图那 7 个同类，**但不是某一日的真实榜单**。
> 要真实榜单 → 贴一份给我，替换 `mock/hotContent.ts` 即可（数据结构不变）。

## E. 本轮踩到的坑（都写进代码注释了）

1. **`pages.json` 里 `tabBar` 这个键不能删** ——
   删掉后 uni-h5 的 `useShowTabBar` 读 `__uniConfig.tabBar.height` 直接
   `TypeError: Cannot read properties of undefined`，**整页白屏**。
   正确做法：**保留键、`list: []`**。因为 `showTabBar = route.meta.isTabBar && shown`，
   没有 tabBar 页时它永不显示 —— 我们的底部导航是自绘的（`MobileNav`），
   这样窄屏才出现、宽屏不出现，而 uni-app 的 tabBar 在 H5 是固定在视口底部的独立层，
   桌面宽屏下会变成一条与三栏版式无关的横条。
2. **游客位不能传 `displayName`** —— 它的兜底值是「未登录」，
   被字母头像取首字后渲染出一个孤零零的**「未」**字。已加"游客图标"分支。
   （本轮截图自检时抓到。）
3. **`/favicon.ico` 404** 在控制台留了一条 "Failed to load resource"，
   看起来像应用坏了。已在内联 SVG favicon。
4. **演示数据必须灌库而不是前端写死** —— 理由见 D 节。

## F. 验证（本轮）

```
npx vue-tsc --noEmit -p tsconfig.json     → exit 0
node scripts/shot.mjs                     → 1920 / 1280 / 420 三档截图
  控制台：无 pageerror、无 4xx/5xx
  仅剩 "Hydration completed but contains mismatches"（uni-app H5 dev 模式固有提示，非本项目问题）
```

截图落在 `web/.tmp/ref/home-{desktop,laptop,mobile}.png`（`.tmp/` 已被 gitignore）。

⚠️ **E2E 现状**：按需求方决定，第 1 版的 `m3-forum.spec.ts` 已随重做删除，
**新的 E2E 尚未写**（等界面定稿后一并重写）。**这一条明确算"未验证"。**

## G. 仍然未裁决（继续阻塞上传）

**CR-G**（上传成功后前端从哪里拿到图片 URL）与 **CR-H**（`callbackUrl` 可达性）
**至今未裁**。`accessKeyId` 那一项已由 L1 重导契约解决（`5f4b0c3`）。

## H. 建议的下一轮顺序

1. **帖子详情页**（信息流的落点，最有价值；404 友好页 + 图片只信后端 + 一键复制）；
2. **搜索页 + 版块页**（接口都已交付，纯铺界面）；
3. **发帖页**（接口已交付，但**上传仍被 CR-G 卡住**，先做无图发帖）；
4. 话题 / 收藏 / 设置（等契约或按下不做）；
5. 界面定稿后重写 E2E。

---

## I. 第 2 段：帖子详情页（已完成）

口径由需求方 2026-09-17 定：「**如今后端有什么就做什么**」+ 六条选项（全部取推荐项）。

### 做了什么

| 内容 | 说明 |
|---|---|
| `web/src/pages/post/detail.vue` | 替换占位页。标题与置顶/精选角标、作者行、**待审横幅**、正文（纯文本 + `pre-wrap`，**不用 rich-text** —— 那会把后端字符串当标记语言解释，是一条 XSS 路径）、图片区、网盘卡片、互动栏、作者操作 |
| 图片区 | 1 张时按**契约给的 `width`/`height`** 算出真实宽高比当容器比例（这样 `aspectFill` 也不会裁掉内容，极端竖图夹到 120%）；≥2 张三列宫格；点击用 `previewImage` 看**原图**（不是缩略图） |
| 网盘卡片 | 显示条件 = `boardIsResource` **且** `diskUrl` 非空；一键复制按 §5.5 锁定格式；「在浏览器打开」按 `canOpenExternalLink()` 显隐（小程序端改为引导复制） |
| 互动栏 | 点赞/评论/收藏/举报**可点但只提示「将在 M4 交付」**（需求方选定）。**不做假成功** —— 点亮或 +1 就是拿假数据冒充已完成 |
| 作者操作 | 编辑按「仅作者 **+ 发布后 30 分钟内**」显隐；删除仅作者。前端的时间判断**只是 UX**，准入判定在后端（超时 403） |
| `LeftRail` | 「今日数据」改为**只在知道帖子总数时渲染**。详情页不请求列表 → 不显示，而不是显示 `—`（一个看着像坏掉的占位） |
| 演示数据 | **所有计数清零**（`view/like/comment/collect`） |

> ⚠️ **关于计数清零，我超出了需求方选项的字面**：选项写的是「只剩浏览量是真的」，
> 但 `view_count`（12860、9420…）**同样是我编的**，不只是点赞/评论/收藏。
> 所以我把它也清零了 —— **清零后页面上的每个数字都是真的**：
> 浏览量随真实访问由后端（Redis）累加，其余保持 0 直到 M4 接口到位。
> 种子里所有计数列已改为 `0`，并加了校验（`sum of fake counts = 0`）。

### 自检抓到的两个问题（都是我自己造的）

1. **「编辑于 1 分钟前」出现在从没编辑过的演示帖上。**
   根因：种子只显式给了 `created_at`，`updated_at` 走了列默认值 `CURRENT_TIMESTAMP`
   （= 灌库那一刻），于是两者差 1 天，被前端的 `updatedAt - createdAt > 60s`
   判成"改过"。**一个纯种子数据缺陷被渲染成了业务事实。**
   已在种子里加 `UPDATE ... SET updated_at = created_at`，并复核
   `TIMESTAMPDIFF(...) <> 0` 的行数为 **0**。
2. **截图脚本把详情页的图存成了 `home-*.png`**，**覆盖了首页截图** ——
   一个「看起来成功了」的假证据。已改为按路由推导文件名。

### 实测过的后端行为（2026-09-17 探针，不是猜的）

| 探测 | 结果 |
|---|---|
| `status=0`（待审）匿名读详情 | **404**，且**不出现在列表**里 |
| `status=2`（已屏蔽）匿名读 | **404**，列表也不含 |
| `PUT` 之后数据库里的 `status` | **确实落成 0**（"编辑重审"闭环成立，DB 与响应都核对过） |
| 已删除帖子 | 404 |
| 发帖限流 | `code=2002`，message 带重试秒数（实测「请 **8734 秒**后重试」）→ **实测确认口径 3** |

> ⚠️ **一条必须交代的「未复现观察」**：第一轮探针里我**看到过一次**
> 「匿名读到了 `status=0` 的帖子」。随后用受控条件复现**两次都失败**
> —— DB 同步核对、SQL 建帖与 API 建帖都试过、立即与延时（3s / 35s）各测一次，**全部 404**。
> **结论：那是我自己探针的产物**（当次控制台被 GBK 转码打乱、那批输出不可靠），
> **不是后端缺陷。此处只作记录，不作指控。**
> 这个项目被一次错误指控坑过（见看板 §3.1），所以我不把不可复现的现象写成 bug。
> 若 L1 有余力可自行复核 —— 我按规矩**不读后端源码**，所以只能到这为止。

### 验证

```
npx vue-tsc --noEmit -p tsconfig.json   → exit 0
node scripts/shot.mjs                   → 首页 + 详情页各 1920/1280/420 三档
                                          无 pageerror、无 4xx/5xx
```

❌ **E2E 仍未写**（按需求方决定，待界面定稿后重写）→ **明确算未验证**。

---

## J. 提交给 L1 的 CR（**需要裁决，不是前端能改的**）

### CR-I：详情页的写操作全缺接口 —— 建议确认 M4 排期

详情页按"后端有什么就做什么"做出来之后，**写操作只剩下编辑与删除**，以下全部缺：

| 能力 | 技术方案里的位置 | 契约现状 |
|---|---|---|
| 点赞 / 取消点赞 | §6.5 | ❌ 不在 14 个路径里 |
| 收藏 / 取消收藏 | §6.5 | ❌ |
| 评论列表 / 发评论 | §6.6 | ❌ |
| 关注 / 取关 | §6.7 | ❌ |
| 举报 | §6.9 | ❌ |

**影响**：详情页的互动栏只能"可点 + 提示未交付"（需求方 2026-09-17 选定）。
**论坛的核心动作一个都做不了** —— 严格按"后端有什么就做什么"，详情页是一篇**文章页**，不是论坛详情页。
**建议**：请 L1 确认 M4 排期；接口一到位，前端把 `notDelivered()` 换成真调用即可，改动面很小。

### CR-J：本机看不到 OSS 图片 —— **会影响 M3 验收**

本机**完全没有外网**（实测 shell 与 Chrome 都打不开 `example.com`，且无任何代理变量），
因此 **OSS 上的图片在这台机器上一律加载不出来**。
而 M3 的验收标准里有一条是「**带图**资源帖在详情页正确展示」。

**影响**：图片区（单图比例 / 九宫格 / 预览大图）的代码写了，但**在本环境无法呈现**，
并且演示数据本来也没有图（上传被 CR-G/CR-H 卡住）。这一块**当前是"未验证"**。

**建议（三选一，都超出前端写权）**：
- **A（推荐）** 给一个**本地可访问**的图片路径 —— 例如让 OSS 走 nginx 反代，或起一个本地 MinIO。
  前端代码一行不用改（图片地址本来就只来自后端），验收能真跑通。
- **B** 明确接受「M3 的图片展示只能在有外网的机器上验收」，并在验收清单里注明 —— 诚实但留缺口。
- **C** 临时把演示图放进 `src/static/`。**我不推荐**：那是拿假数据冒充上传结果，
  破坏本项目「不冒充已完成」的约定（也正是这一条让我们揪出了 §D 里那些编造的计数）。

---

## 0. 一句话结论

**§6.2 的 6 个页面里，第 1–4 页与第 6 页已完成并验证；第 5 页（发帖）完成「文字帖 + 资源帖（网盘字段）」，
但其中的「图片上传」没有做 —— 原因是本轮开工时契约缺 `accessKeyId`（见 §5），
而在我做完第 1–4 页之后 L1 已把该字段重导进契约（`5f4b0c3`）。**
**§6.1 的最小闭环（选图 → 直传 → 回调落库 → 发帖 → 详情看到图）因此尚未跑通，属 BLOCKED，
且现在有两个具体的阻塞点需要 L1 裁决（§6）。**

> ⚠️ 「验证过」与「没验证」在本报告里严格分开写。凡是没有命令输出的，一律写「未验证」。

---

## 1. 环境与前提（本轮实测）

| 项 | 实测值 |
|---|---|
| 后端 | `http://127.0.0.1:8080`，运行中（`GET /api/boards` → `code:0`，7 个版块） |
| 前端 | `http://127.0.0.1:5173`（`npm run dev:h5`），`/api` 由 Vite 代理转 8080，**同源** |
| 契约 | 开工时 `openapi.json` = 14 路径 / SHA `910f99c8…`；**21:25 被 L1 重导为 `922c206b…`**（见 §5） |
| Redis | `192.168.100.128:6380` 可达（E2E 读验证码用） |
| MySQL | `hy_forum` 可查（`mysql -u root -p1234`） |
| **外网** | ⚠️ **本会话 shell 无出网**：`curl https://example.com` → `http=000`（DNS 能解析）。因此**无法由我做真实 OSS 上传**（见 §6） |

---

## 2. 逐文件改动清单（对照任务书 §2 写权表）

### 2.1 新增

| 文件 | 用途 |
|---|---|
| `web/src/api/boards.ts` | `GET /api/boards` 封装 + 按 id 定位版块（契约里**没有** `/api/boards/{id}`，故复用列表） |
| `web/src/api/posts.ts` | 帖子 5 个端点：列表 / 搜索 / 详情 / 发帖 / 改帖 / 删帖；`size` 按契约硬上限 20 收口 |
| `web/src/api/shape.ts` | **契约响应的形状断言**（见 §3.1） |
| `web/src/utils/format.ts` | 可选字段收敛 + 时间格式化 + 网盘类型表 + **一键复制文案**（§5.5 锁定格式） |
| `web/src/components/PostListItem.vue` | 帖子列表项（首页/版块页/搜索页共用） |
| `web/src/components/HyState.vue` | 加载 / 出错 / 空 三态占位（互斥顺序 error > loading > empty） |
| `web/src/pages/board/index.vue` | 第 2 页 帖子列表（`boardId` + `sort` + 分页） |
| `web/src/pages/search/index.vue` | 第 3 页 搜索 |
| `web/src/pages/post/detail.vue` | 第 4 页 详情（九宫格 / 网盘卡片 / 一键复制 / 404 友好页 / 作者操作） |
| `web/src/pages/post/edit.vue` | 第 5+6 页 发帖与编辑（同页，`?id=` 区分） |
| `web/e2e/m3-forum.spec.ts` | M3 的 7 条 Playwright 用例 |

### 2.2 修改

| 文件 | 改了什么 | 为什么 |
|---|---|---|
| `web/src/api/types.ts` | 追加 M3 具名类型出口（`BoardVO`/`PostSummaryVO`/`PostDetailVO`/`PostImageVO`/`UserBriefVO`/`PostCreateRequest`/`PostUpdateRequest`/`PageResultPostSummaryVO`/`PostSort`） | 口径：具名类型，禁止 `Record<string, any>`（任务书 §3.5）。**全部直接取自生成物，无人工成分** |
| `web/src/pages.json` | 注册 4 个新页面；**把 `pages/index/index` 提到 `pages[0]`**（启动页由登录页改为首页） | 需求方 2026-09-16 定；M3 之后内容页已可浏览 |
| `web/src/pages/index/index.vue` | 整页重写为「搜索入口 + 版块宫格 + 全站最新帖子流 + 悬浮「＋」」 | 同上 |
| `web/src/utils/platform.ts` | **新增 `canOpenExternalLink()`** | 平台差异必须集中封装（任务书 §3.4）—— 见 §3.3 |

### 2.3 明确**没有**改动的

`openapi.json`、`web/src/api/generated/**`、`server/**`、`docs/agents/工作计划.md`、`docs/testing/**`、
`web/README.md`、`web/vite.config.ts`、`web/package.json`（**未新增任何依赖**）、
`web/e2e/golden-path.spec.ts`（M2 已交付用例，一行未动）。

> `web/package.json` **零改动**：`vue` / `pinia` / `wot-design-uni` / `@playwright/test` 都已就位，
> 未安装任何新依赖（任务书 §3.6）。

---

## 3. 我采用的读法 + 理由（§8.1：不停下等人，事后由 L1 审）

### 3.1 为什么加 `api/shape.ts` 做"形状断言"

契约里 `ApiResponseXxx.data` **全都是可选的**（`data?:`），而且每个 schema 的字段也全都可选。
于是 `code=0` 但缺 `data` 是**合法响应**，`request()` 会 resolve `undefined`，
页面直接 `.map()` 就炸在 **Vue 模板渲染阶段**，堆栈指向框架内部，根因（后端形状变了）被埋掉。

因此 API 层只断言**最少必要的一条**（是不是数组 / 是不是对象），
失败时抛语义明确的 `ApiError`；**字段级缺失不报错**，由 `utils/format.ts` 收敛成缺省值。
两者分开的理由：整个 `data` 不是数组 = 根本没法渲染；某个字段没返回 = 能降级显示。
把两者都做成硬失败，会让契约**加字段**时前端误报。

### 3.2 列表图字段：用 `coverUrl`，不是 `thumbUrl`（**口径 6 的一处口径差**）

任务书 §5 第 6 条写「列表图用 `thumbUrl`（缩略图），**不要用原图**」，
但**契约里 `PostSummaryVO` 只有 `coverUrl`，没有 `thumbUrl`**
（`thumbUrl` 只存在于详情用的 `PostImageVO` 上）。

**我的读法**：列表用 `coverUrl`，**前端不自己拼缩略图 URL** ——
拼 URL 等于把 OSS 图片处理参数变成前端合约，与口径 7 同源被禁。
若后端希望列表走缩略图，应由后端在 `coverUrl` 里给缩略图地址，或补 `thumbUrl` 字段。
**已登记为待 L1 裁决的措辞差异（不阻塞）。**

### 3.3 `canOpenExternalLink()`：为什么不能拿 `openExternalLink()` 的返回值去探测

写详情页时我第一版在页面里写了 `// #ifdef H5 || APP-PLUS` —— **这是错的**，两条独立原因：
1. **违反任务书 §3.4**（`#ifdef` 只允许出现在 `utils/` 的集中封装处）；
2. 它会**直接让 type-check 变红**：`vue-tsc` 看到的是**条件编译前的原文**，
   两个分支里的 `const IS_H5_OR_APP` 同时存在 → 重复声明。

正确做法是 `utils/platform.ts` 里新增一个**纯能力判断函数**（无副作用）。
不能用 `openExternalLink()` 探测，因为它在 H5 端**会真的弹出一个窗口**。

### 3.4 编辑的 30 分钟窗口在前端也判一次

契约说 `PUT` 仅作者且**仅发布后 30 分钟内**。前端据此隐藏「编辑」按钮，
理由是"点进去填完表单才被拒"是最差的体验。
**但前端这个判断只是 UX，不是准入判定** —— 真正的判定在后端（超时返回 403）。
`createdAt` 缺失/解析失败时前端**放手让用户试**（返回可编辑）：
解析失败时挡住用户是更坏的结果。已在代码里写明。

### 3.5 首页为什么不是《技术方案》§4.1 的"关注/全部双流"

§4.1 的双流依赖 `GET /api/feed?type=follow|all`，而**该路径不在契约里**（14 个路径没有 `/api/feed`），
「关注流」还额外依赖 `/api/follow/{userId}`（也不在）。
**我的读法**：「全部流」用 `GET /api/posts`（不传 `boardId`）等价实现；
「关注流」**不做**（契约没有的接口不得编造，任务书 §2/§3）。已登记为待 L1 判归属的事项。

### 3.6 契约缺口：`sort` 与 `diskType` 都没有 `enum`

- `GET /api/posts` 的 `sort` 声明为裸 `type: string`，取值 `latest|hot|essence` 只能来自《技术方案》§6.5；
- `PostCreateRequest.diskType` 是 `integer`，取值 `1..6` 只能来自字段 `description`。

前端因此各自写了一份联合类型 / 映射表并注明依据。
**建议（契约改进，非阻塞）**：补上 `enum`，前端就能由生成物得到联合类型，
后端改取值时自动变红，而不是靠文档与注释对齐。

---

## 4. 必交证据（任务书 §6.3）

### 证据 2：`npm run type-check` exit 0 ✅

```
> hy-forum-web@0.1.0 type-check
> vue-tsc --noEmit -p tsconfig.json && tsc --noEmit -p tsconfig.node.json

TYPECHECK_EXIT=0
```

补充证据（证明新文件**真的在被检查**，而不是"没被 include 所以不报错"）：

```
$ npx vue-tsc --noEmit -p tsconfig.json --listFiles
D:/demo/Hy论坛/web/src/api/boards.ts
D:/demo/Hy论坛/web/src/utils/format.ts
D:/demo/Hy论坛/web/src/components/HyState.vue
D:/demo/Hy论坛/web/src/components/PostListItem.vue
D:/demo/Hy论坛/web/src/pages/board/index.vue
D:/demo/Hy论坛/web/src/pages/index/index.vue
D:/demo/Hy论坛/web/src/pages/post/detail.vue
D:/demo/Hy论坛/web/src/pages/search/index.vue
```

### 证据 3：H5 端到端「红 → 绿」两次输出 ✅

**红（首跑，`npx playwright test e2e/m3-forum.spec.ts`）：4 passed / 3 failed** → 定位到 3 个真问题：

| # | 现象 | 根因 | 修法 |
|---|---|---|---|
| 1 | `getByRole('button', {name:'回到首页'})` 找不到元素 | `wd-button`（wot-design-uni）在 H5 端渲染成普通 `div`，**没有 button 角色** | 给它 `data-testid`，按 testid 定位 |
| 2 | 剪贴板断言失败，diff 里两段文本**看起来一模一样** | Windows **系统剪贴板**把 `\n` 规范成 `\r\n`（平台行为，粘贴结果正确） | 比较前 `\r\n → \n` 归一（只归一行尾符，三行内容与顺序仍逐字校验） |
| 3 | 保存后页面落到**首页**，而不是详情 | `uni.navigateBack()` 依赖"历史栈里确实有上一页"；编辑页可从分享链接/刷新/E2E 的 `page.goto` 直接进入，此时 `history.back()` 会退到与"上一页"无关的位置，**且不报错** | 改为 `redirectTo` 详情页：结果确定，且用户能立刻看到修改结果与待审横幅 |

**绿（第二次，继续修完后又发现第 4 个问题）**：编辑用例的落库复核拿到 `404` ——
因为改帖后 `status` 回到 0（待审），而 `GET /api/posts/{id}` 是**可选鉴权**，
**不带 token 的游客读不到待审帖**。这是**正确的后端行为**，是我的辅助函数漏传了作者 token。

**绿（最终，全量 `npx playwright test`，含 M2 已交付用例）：12 passed ✅**

```
Running 12 tests using 1 worker
  ok  1 [h5-chrome] › e2e\golden-path.spec.ts:121:5 › 注册：填写表单含图形验证码，成功后跳转登录页
  ok  2 [h5-chrome] › e2e\golden-path.spec.ts:194:5 › 登录：登录成功写入 token，可进入「我的」页并在刷新后保持登录
  ok  3 [h5-chrome] › e2e\golden-path.spec.ts:240:5 › 登录失败：密码错误时展示可读文案而非技术信息
  ok  4 [h5-chrome] › e2e\golden-path.spec.ts:273:5 › 未登录：访问「我的」页展示登录引导而非错误
  ok  5 [h5-chrome] › e2e\golden-path.spec.ts:287:5 › 退出登录：token 被清除且回到未登录态
  ok  6 [h5-chrome] › e2e\m3-forum.spec.ts:172:5 › 未登录：首页显示版块宫格，资源版块带「资源」角标
  ok  7 [h5-chrome] › e2e\m3-forum.spec.ts:203:5 › 未登录：点首页「＋」发帖入口会跳到登录页
  ok  8 [h5-chrome] › e2e\m3-forum.spec.ts:223:5 › 详情：不存在的帖子显示友好页，且提供回首页出口
  ok  9 [h5-chrome] › e2e\m3-forum.spec.ts:241:5 › 发帖：资源版块显示网盘字段 → 发布 → 详情页正确展示 → 一键复制格式正确
  ok 10 [h5-chrome] › e2e\m3-forum.spec.ts:343:5 › 发帖：非资源版块不显示网盘字段
  ok 11 [h5-chrome] › e2e\m3-forum.spec.ts:364:5 › 编辑：改标题后网盘信息仍在，且帖子回到待审
  ok 12 [h5-chrome] › e2e\m3-forum.spec.ts:448:5 › 删除：作者删除后详情变友好页，版块列表里也不再有它

  12 passed (14.0s)
```

**覆盖了 §6.3 要求的两条**：
① 列表 → 详情 →（登录后）发帖成功 ✅（用例 9，且落库用 API 独立复核）
② 未登录访问受保护动作 → 跳登录 ✅（用例 7）

**额外覆盖**：404 友好页、搜索命中、编辑、删除、`isResource` 正反两面、一键复制文案逐字校验。

#### 执行说明（环境陷阱，写给 L1 复核时用）

- `npx playwright test` 在受限沙箱下报 **`spawn EPERM`**（Playwright 用管道 stdio fork worker）
  —— 这是 `web/README.md` §3.4 已记录的边界，**必须提权执行**。
- **另外清掉了一个与本任务无关的既存故障**：`web/test-results/` 带有一条
  **上一轮沙箱会话的 AppContainer SID（`S-1-4-188903159-…`）的 ACE 且禁用了继承**，
  导致当前身份没有 DELETE 权限 → Playwright 启动时清理 outputDir 直接 `EPERM` 失败。
  该目录已删除，之后正常。**若 L1 复核时再遇到同类 EPERM，先看该目录的 ACL**。

### 证据 4：反向验证 2 条（证明检查会失败）✅

**① 把 API 基址改成错误端口**（`web/vite.config.ts` 代理目标 `8080 → 8099`）：

```
=== proxy check (expect NOT 200) ===
500
=== E2E with wrong API port (expect RED) ===
  ok 3 … 未登录：点首页「＋」发帖入口会跳到登录页 (635ms)
  1) … 未登录：首页显示版块宫格，资源版块带「资源」角标
  2) … 详情：不存在的帖子显示友好页，且提供回首页出口
  3) … 发帖：资源版块显示网盘字段 → 发布 → 详情页正确展示 → 一键复制格式正确
  4) … 发帖：非资源版块不显示网盘字段
  5) … 编辑：改标题后网盘信息仍在，且帖子回到待审
  6) … 删除：作者删除后详情变友好页，版块列表里也不再有它
  6 failed
  1 passed (57.3s)
```

> 仍通过的第 7 条是**纯导航用例**（点「＋」→ 跳登录），它**不依赖任何后端数据**，
> 所以后端全挂时它依然通过 —— 这正是它该有的行为，也说明"红色数量"是可信的。

**② 把 `isResource` 判断写反**（`index/index.vue` 的角标 + `post/edit.vue` 的 `showDiskFields` 同时取反）：

```
  1) … 未登录：首页显示版块宫格，资源版块带「资源」角标
  2) … 发帖：资源版块显示网盘字段 → 发布 → 详情页正确展示 → 一键复制格式正确
  3) … 发帖：非资源版块不显示网盘字段
  4) … 编辑：改标题后网盘信息仍在，且帖子回到待审
  4 failed
  3 passed (1.6m)
```

变红的**恰好是 4 条依赖 `isResource` 的用例**，其余 3 条照旧通过。
两次验证后**均已复原**，并核对：
`web/vite.config.ts` 与 `HEAD` **逐字节一致**、源码里不存在 `!bool(...isResource)`。

### 证据 5：`git status --short` + 逐文件说明 ✅

```
 M web/src/api/types.ts
 M web/src/pages.json
 M web/src/pages/index/index.vue
 M web/src/utils/platform.ts
?? web/e2e/m3-forum.spec.ts
?? web/src/api/boards.ts
?? web/src/api/posts.ts
?? web/src/api/shape.ts
?? web/src/components/HyState.vue
?? web/src/components/PostListItem.vue
?? web/src/pages/board/
?? web/src/pages/post/
?? web/src/pages/search/
?? web/src/utils/format.ts
?? tests/
```

- **14 项全部落在任务书 §2 的写权清单内**（`web/**`）。
- ⚠️ `?? tests/` **不是我的** —— 它在我开工的第一次 `git status` 时就已存在（属 L1 的 `tests/verify/**`、
  `tests/golden-path/**`），**我没有碰过，也没有把它纳入提交**。
- 本报告文件按授权单独落在 `docs/agents/reports/W5-FE-M3.md`（`docs/` 下唯一改动）。

### 证据 1：最小闭环的实跑证据 ❌ **未达成**（见 §5、§6）

---

## 5. §6.1 最小闭环：为什么没跑通（**逐条说清，不掩饰**）

### 5.1 阻塞点 A（**已在本轮中途被 L1 解除**）：契约缺 `accessKeyId`

开工时我实测：

```
openapi.json                     = 14 路径 / 27 schema / SHA256 910F99C8…
OssSignatureVO.properties        = host, policy, signature, dir, expire, callback      ← 没有 accessKeyId
running 8080 /v3/api-docs        = 同样没有
```

按任务书 §7（"还没有 → 先做 §6.2 的 1–4 页，上传等你看到字段后再做，或直接提 CR。
**不要自己编一个字段名**"），我**没有**自己给字段起名，改为先做第 1–4 页。

**结果：L1 已于 21:25 重导契约**（`5f4b0c3 feat(contract): 重导契约纳入 CR-F 的 accessKeyId（14 路径 / 7 字段）`）。
我复测确认：

```
openapi.json 与 web/src/api/generated/openapi.snapshot.json
  → 双双 = SHA256 922C206B5DF3F6E866571C3D1CF4C0B4302DAA2A51BE37F246F5A665490D17C0（逐字节一致）
repo openapi.json 的 OssSignatureVO.properties
  → host, policy, signature, dir, expire, callback, accessKeyId      ✓ 已有
live 8080 /v3/api-docs 的 OssSignatureVO.properties
  → host, policy, signature, dir, expire, callback, accessKeyId      ✓ 与契约一致
$ npm run check:contract
  → 路径数：快照 14 / 在线 14；schema 数：快照 27 / 在线 27；✓ 契约一致；exit 0
```

**所以 CR-F 对前端已不再是阻塞** —— 上传页**可以**开工了，只差没做（见 §6.1）。

> **一条工具教训（我自己踩的、值得写下来）**：我第一次判断"live 有没有 `accessKeyId`"用的是
> `(curl …) -match 'accessKeyId' | Out-Null; if($Matches){…}` —— 它给出了**否**。
> 而实际上**是**。那是 PowerShell 里 `$Matches` 用法的假阴性，**一个从不失败的检查查不出任何东西**。
> 后来改用 `ConvertFrom-Json` 后逐字段打印才拿到真相。**判定契约差异不要用文本 `-match`，要解析结构。**

### 5.2 阻塞点 B（**仍未解除，需要 L1 裁决**）：真实上传的两个前提

1. **本会话 shell 无出网** → 我无法由命令行做真实 OSS 上传：
   ```
   curl.exe -s -o NUL -w "http=%{http_code}\n" https://hy-forum-2026.oss-cn-beijing.aliyuncs.com/   → http=000
   curl.exe -s -o NUL -w "http=%{http_code}\n" https://example.com                                   → http=000
   DNS 解析正常（59.110.190.22）
   ```
   （浏览器侧是否也受限**未验证**；Playwright 是被提权拉起的，网络路径可能与 shell 不同。）

2. **签名的 `callbackUrl` 指向 `http://127.0.0.1:8080/api/oss/callback`** ——
   OSS 的服务器**不可能**访问我的 localhost。本轮实测抓到的签名响应里，
   `callback` 字段 base64 解出正是 `{"callbackUrl":"http://127.0.0.1:8080/api/oss/callback", …}`。
   这属 §M3-计划与前置 的方案 A（`ssh -R` 反向隧道 + ECS nginx）范畴，
   **是 L1 独占的基础设施，我不动**。见 §6 的问题 1。

---

## 6. 待 L1 裁决（BLOCKED 与 CR）

### CR-G（阻塞最小闭环）：上传成功后**前端怎么拿到图片 URL**？

**缺什么**：契约里没有任何字段告诉前端"刚上传的那张图的 URL 是什么"。
- `POST /api/oss/callback` 的响应体按 **CR-007** 裁决是 `{code:0,message:"ok"}`，**没有 `data`**；
- 而 `POST /api/posts` 需要的 `images` 是 **URL 列表**（`string[]`）。

**为什么不够**：前端要发帖就必须先知道 URL，但现在**唯一的来源只能是前端自己拼**
`${host}/${dir}${fileName}`。而"前端不自己拼图片 URL"是口径 7 的明确要求
（口径 7 针对详情页，但同一理由在这里同样成立：拼 URL 会把 OSS 的 key 规则变成前端合约）。

**我建议的变更（三选一，请 L1 裁）**：
- **A（推荐）**：`POST /api/oss/callback` 的响应体带上落库结果，例如
  `data: { id, url, thumbUrl }`（`thumbUrl` 同时把"列表用缩略图"这件事交回后端定）。
  代价：要改 CR-007 的裁决（那是一次措辞裁决，不是硬约束）。
- **B**：不改回调，**承认"前端拼 URL"是既定设计**，并在契约里把 `host` / `dir` 的拼接规则
  明确写成契约的一部分（`key = dir + 文件名`，且 `dir` 带尾斜杠 —— CR-009 已定后者）。
  代价：前端仍要持有一份 key 规则。
- **C**：新增一个"确认上传"接口，前端传 key、后端返回 URL。代价：多一次往返，且与回调职责重叠。

**影响哪些已完成代码**：只影响尚未实现的 `utils/upload.ts`；§2.2 的已完成页面**不受影响**。

### CR-H（阻塞最小闭环的运行验证）：`callbackUrl` 的可达性

签名的 `callbackUrl` 是 `http://127.0.0.1:8080/api/oss/callback`。
**问题**：这是**后端自己配的值**（`application.yml` 是 L1 独占），OSS 够不到它。
`ssh -R` 隧道 + ECS nginx（`127.0.0.1:18080`）那套是否**当前仍在生效**、
以及 `application.yml` 里的回调地址是否应指向公网入口，**都只有 L1 能确认**。
（旁证：`post_image` 表里此刻只有 **1 行**，`id=1, post_id=0`，
是 M3b 后端实现者 20:45 那次真实上传留下的 —— 说明那条链路**当时**是通的。）

**请 L1 给的**：① 隧道现在是否活着；② 回调地址的正确取值；③ 我能否用 8080 做一次真实上传验证。

### 待裁决（不阻塞）：三处口径措辞

| # | 事项 | 我的读法 |
|---|---|---|
| 1 | 口径 6 说列表用 `thumbUrl`，但契约 `PostSummaryVO` 只有 `coverUrl` | 用 `coverUrl`，**不自己拼**缩略图 URL（§3.2） |
| 2 | `GET /api/posts` 的 `sort`、`PostCreateRequest.diskType` 都没有 `enum` | 依据《技术方案》§6.5 与字段 `description` 各写一份映射，并建议契约补 `enum`（§3.6） |
| 3 | §4.1 的首页"双流"依赖不在契约里的 `/api/feed`、`/api/follow/{userId}` | 「全部流」用 `GET /api/posts` 等价实现，「关注流」不做（§3.5） |

### 另一处需要 L1 决策的：`pages[0]` 的改动

我把**应用启动页从 `pages/auth/index` 改成 `pages/index/index`**（需求方 2026-09-16 在选项式问询中选定）。
M2 的 `golden-path.spec.ts` 用例全部按 hash 显式导航，**实测 5/5 仍绿**（见证据 3），故无回归。

---

## 7. 契约作为"第一道验证手段"：本轮从这里暴露出的东西

按任务书 §0 的要求，我不是复述已定口径，而是记录**只有消费契约才会撞到**的歧义：

1. **`accessKeyId` 的双份事实**（§5.1）：Java 里 21:19 就有了（`39dec86`），
   但 `openapi.json` 到 21:25 才重导（`5f4b0c3`）。中间那 6 分钟里，
   **"后端能返回"与"契约声明了"是两件事** —— 前端只能消费后者。
   （我最初也正是因为读契约而不是读代码，才没能发现 live 其实早就有了。）
2. **上传后拿不到图片 URL**（CR-G）：这是**契约层面**的缺口，
   读后端实现能"猜到"答案（去看它怎么拼 key），但那正是任务书 §0 禁止的事 ——
   一旦去读实现，这个缺口就永远不会被提出来了。
3. **`sort` / `diskType` 没有 `enum`**（§3.6）：契约能表达"是什么"，但没表达"允许什么"，
   于是取值只能靠文档与注释对齐，无法被任何检查守护。
4. **`data?` 全字段可选**（§3.1）：契约的形状本身把"缺字段"变成合法响应，
   前端必须自己补一层形状断言，否则根因会被埋进框架堆栈。

---

## 8. §6.2 六个页面逐条状态（§6.3 第 6 条）

| # | 页面 | 状态 | 说明 |
|---|---|---|---|
| 1 | 版块列表（首页） | ✅ **已完成并验证** | `GET /api/boards`；两列宫格；`isResource` 角标；E2E 用例 6 正反两面 |
| 2 | 帖子列表 | ✅ **已完成并验证** | `GET /api/posts`；`sort=latest\|hot\|essence` 切换；摘要 + `coverUrl`；分页（满页判定，不看 `total`） |
| 3 | 帖子搜索 | ✅ **已完成并验证** | `GET /api/posts/search`；空 `keyword` 在 API 层与页面层各拦一道（不发必然 400 的请求） |
| 4 | 帖子详情 | ✅ **已完成并验证** | 九宫格（点击 `previewImage`）、网盘卡片、一键复制（文案逐字校验）、404 友好页、待审横幅、作者操作 |
| 5 | 发帖 | ⚠️ **部分完成** | **文字帖 / 资源帖（网盘字段）已完成并验证**；**图片上传未做**，原因见 §5、§6 |
| 6 | 编辑 / 删除 | ✅ **已完成并验证** | 覆盖语义（`images` 原样回传，E2E 断言网盘信息不被清空）、保存后 `status→0` 与"重新进入审核"提示、删除后 404 + 列表消失 |

**没做且明确不算完成的两件事**（不写"基本上做完了"）：
1. **图片上传**（`GET /api/oss/signature` 的消费、PostObject 直传、≤9 张、进度与失败重试）；
2. **§6.1 的最小闭环实跑证据**（HTTP 请求/响应 + `post_image` 的 SQL 输出）。

`post_image` 的当前状态（这也是"没做成"的直接证据）：

```
$ mysql -u root -p1234 -D hy_forum -e "SELECT id,post_id,url,thumb_url,audit_status FROM post_image ORDER BY id DESC LIMIT 5;"
id  post_id  url                                                                          thumb_url  audit_status
1   0        https://hy-forum-2026.oss-cn-beijing.aliyuncs.com/post/2026/09/16/m3b-real-*.png   NULL   0
```

**只有 1 行，且是后端实现者 M3b 那次真实上传留下的（`post_id=0` = 尚未被任何帖子认领）。
我通过前端发的帖子 0 张图。** —— 这就是"上传没接上"最直接的证据。

---

## 9. 下一轮（若要我做）的建议顺序

1. 等 L1 裁决 **CR-G**（图片 URL 从哪来）与 **CR-H**（`callbackUrl` 可达性）；
2. 实现 `web/src/api/oss.ts`（`GET /api/oss/signature`，直接用契约的 `accessKeyId`）
   + `web/src/utils/upload.ts`（`uni.uploadFile` 到 `host`，表单一律取自签名响应）；
3. 接进 `pages/post/edit.vue`：≤9 张、上传中/失败态、提交前必须全部上传成功；
4. 跑通 §6.1 最小闭环，产出 **HTTP 请求/响应 + `post_image` SQL** 两份证据；
5. 补 E2E 用例（含"上传失败不阻塞其它字段"这类边界）。

---

## 11. 我自己造的三处瑕疵（一并交代，避免 L1 复核时以为是别人干的）

### 11.1 本次交付的**两个提交信息都不可用**（内容是对的，只有 message 受损）

| 提交 | 主题行的实际内容 | 根因 |
|---|---|---|
| `a53074a` | `<U+FEFF>feat(web): 接入 M3 版块/列表/…` —— 主题行以 **BOM** 开头 | PS 5.1 的 `Out-File -Encoding UTF8` **会写 BOM** |
| `6cbd627` | `docs(agents): ?? W5-FE-M3 ???schema ???????????` —— **中文全变成了 `?`** | 我为"避开 BOM"改用 `Set-Content -Encoding ascii`，**ascii 编码把非 ASCII 字符全部替换成 `?`** |

**第二处比第一处更糟，而且是我为了修第一处才造出来的**（这条值得记：修一个问题时引入一个更坏的问题，
比不修还糟）。核实命令与输出：

```
$ git cat-file commit 6cbd627
tree a4e7ddd784a365b15daa6cd750e38b9545efece3
parent a53074a12c9943a9a4156ea259ac96bdc83ab2e7
…
docs(agents): ?? W5-FE-M3 ???schema ???????????
```

⚠️ **任务书 §8.4 禁止 `git commit --amend`，所以两个提交我都没有改**，
留给 L1 决定推送前如何处理。**两个提交的树内容都正确**（`git show --stat` 可核对）。

**正确做法（本报告这次提交起已采用，可作后续参照）**：用**写文件的工具**产出
**UTF-8 无 BOM** 的消息文件，再 `git commit -F <file>`；
**不要**用 `Out-File -Encoding UTF8`（加 BOM）、也**不要**用 `-Encoding ascii`（吃中文）。

### 11.2 本报告第一版把 schema 数写成了 26（实际 27）

已改正。根因是我**手数**而不是解析 JSON —— 与 §5.1 那条"别用文本匹配判定契约差异"是同一类错误：
在表示的边缘偷懒，就会得到一份"看起来对、其实不对"的产物。

> 这三处是同一个主题的三个实例，也是我这一轮最该记住的东西：
> **凡是"喂给别的程序读"的文本，都要先想清楚编码/表示会不会成为内容的一部分。**

---

## 12. 变更记录

| 版本 | 日期 | 说明 |
|---|---|---|
| v1.0 | 2026-09-16 | 首版。§6.2 的 1–4、6 页完成并验证；第 5 页缺图片上传（CR-F 于本轮中途由 L1 解除，CR-G/CR-H 待裁）；§6.1 最小闭环 **未达成**，逐条写明原因 |
