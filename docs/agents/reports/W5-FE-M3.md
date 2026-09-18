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

## K. 第 3 段：搜索页 + 版块页（已完成）

| 内容 | 说明 |
|---|---|
| `web/src/pages/search/index.vue` | `GET /api/posts/search`。空关键词**不发请求**（"没输入就点搜索"是正常操作，不该拿 400 惩罚它）；`keyword`（实时值）与 `submittedKeyword`（已提交值）分开存，否则统计文案会和列表对不上 |
| `web/src/pages/board/index.vue` | `GET /api/posts?boardId` + `GET /api/boards`。版块名从**列表**里按 id 查——契约里**没有** `/api/boards/{id}`，不能编造；版块信息与帖子列表**各自 catch**，头部挂了不影响列表 |
| `web/src/components/ListState.vue` | 三态占位抽成共用组件（首页/搜索/版块共用）。互斥顺序 **error > loading > empty** 是这里唯一的实质逻辑，写三份就是三份会漂移的副本 |
| 首页 | 改用 `ListState`（删掉原来内联的 3 个状态块 + 对应样式），并传入 `loading && posts.length === 0` —— 传裸 `loading` 会让"正在加载…"与仍在渲染的列表**同时出现** |
| 导航高亮 | 给 `AppShell` / `LeftRail` / `MobileNav` 的 `activeNav` 加上 `''`（**不高亮任何一项**）。搜索页/版块页/详情页都不属于左栏那三个入口，硬点亮"首页"是对用户说假话。顺带修掉 `AppShell` 里一个**重复的 `'me'` 联合成员**（TS 不报错，但很脏） |

### 验证

```
npx vue-tsc --noEmit -p tsconfig.json  → exit 0
node scripts/shot.mjs                  → 首页 / 搜索页 / 版块页 各 1920/1280/420 三档
                                         无 pageerror、无 4xx/5xx
```

截图落 `web/.tmp/ref/{home,search,board}-{desktop,laptop,mobile}.png`。
❌ E2E 仍未写（等界面定稿）→ 仍算未验证。

---

## L. 两处与 L1 的说法不一致（**需要 L1 核实**）

### L.1 更正我上一轮的错误诊断（**这是我的方法论错误**）

我上一轮说「本机完全没有外网」。**这个结论是错的，而且错在方法上**：
我只测了 `example.com` / `github.com` 两个任意域名，就把 **shell 的**结果推断成"整个环境离线"。

**实测（2026-09-17，用浏览器测，因为上传发生在浏览器里）**：

```
node web/scripts/net-probe.mjs
https://example.com                                  -> HTTP 200
https://hy-forum-2026.oss-cn-beijing.aliyuncs.com/   -> HTTP 403   ← 私有桶，与 L1 说法一致
https://help.aliyun.com                              -> HTTP 200
OSS via XHR                                          -> status=403
```

**准确的说法是：`shell` 无出网（curl 对所有外部域名返回 000），`浏览器`有完整出网。**
上传走的是浏览器，所以这条路**是通的** —— L1 的更正方向正确。
⚠️ 但 L1 给的原因（"example.com / github.com 那类被拦"）与我这次实测不符：
**浏览器里 example.com 是 200**。真正的分界是 **shell ↔ 浏览器**，不是域名白名单。
（这一条值得 L1 复核，因为它决定了"以后靠什么验证外部依赖"。）

**教训**：把"某条通道不通"当成了"环境不通"。**一个通道的失败不能推断整体** ——
这与本项目那条「只看 `git status` 就断定'没干活'」是同一类错误（看板 2026-09-16 那条广播）。

### L.2 运行中的 8080 是**旧构建** —— CR-G 的改动**没有生效**

| 检查 | 实测 |
|---|---|
| live `/v3/api-docs` 路径数 | **14**（没有 M4 的 ~17 个端点） |
| live `OssSignatureVO` | `host, policy, signature, dir, expire, callback, accessKeyId` ✅（CR-F 在） |
| live OSS 相关 schema | 只有 `ApiResponseOssSignatureVO` / `OssSignatureVO` —— **没有** callback 的返回类型 |
| 仓库 `openapi.json` 的 `/api/oss/callback` 响应 | 仍是 `ApiResponseVoid` |
| `server/target/hy-forum-server-0.0.1-SNAPSHOT.jar` | 已于 **2026-09-17 20:16** 重新构建 |
| 正在跑的 java 进程 | 全部起于 **9/16**（20:46、21:25）→ **没有任何进程用过新 jar** |

**结论**：新 jar 已构建但**没有被启动**，8080 上仍是旧构建。
**后果**：CR-G 的 `POST /api/oss/callback → data:{id,url,thumbUrl}` 在运行实例上**拿不到**，
契约里也还没有 —— 所以**上传页现在做不了**（L1 说"可以做了"，但实际环境尚不具备）。

**需要 L1 做两件事**：① 用新 jar 重启 8080；② 重导 `openapi.json`（CR-G 的 callback 响应形状 + M4 的 17 个端点）。
做完我立刻做上传页 + **跑通 §6.1 最小闭环**（浏览器能出网、OSS 通路已验证，条件已经齐了）。

### L.3 M4 前端目前无法开始（**按 L1 自己的指示**）

L1 指示「**先别接互动按钮**：M4 新增约 17 个端点，契约还没重导，你在 `openapi.json` 里现在还看不到它们」。
实测确认：`openapi.json` 确实是 14 个路径，没有 M4 端点。

所以我**不会**照《技术方案》§6.6/§6.7 的文字去猜端点形状 —— 那正是"编造契约"。
**收到契约重导通知后立刻转 M4**（评论 / 点赞 / 收藏 / 关注 / 个人主页 / 我的收藏）。

### L.4 顺带交代：前端 dev server 挂过，我重新拉起了

交付时发现 `127.0.0.1:5173` 已死（`root=000`），后端 8080 正常。
重启时第一次报 **`spawn EPERM`**（esbuild 启动服务用管道 stdio —— `web/README.md` §3.4 记录的边界），
提权后正常。**现在 5173 是我起的后台作业（`pwsh-176`）**，若需求方要自己起，请先告知我停掉它。

---

## M. 第 4 段：发帖 / 编辑页 + E2E 回归网（已完成）

### M.1 发帖 / 编辑页

`web/src/pages/post/edit.vue`（同一个页面，`?id=` 区分新建/编辑）：
版块 chips（新建，带 `isResource` 角标）、标题/正文（含计数器，`maxlength` 取自契约）、
网盘字段（资源版块才出现：类型 chips + 链接 + 提取码）、错误常驻文案、提交。

三条从契约来的硬约束（写错任何一条都会**静默丢数据**），都落到了代码里：

| # | 约束 | 落点 |
|---|---|---|
| 1 | **`PUT` 是覆盖语义**，没传的字段视为清空 | `buildUpdatePayload()` 必须带 `title` + `content` + **`images`** + 资源版的 `diskType/diskUrl/diskCode`；`images` 漏了就**把图全删了**，而且不报错 |
| 2 | 资源版块必填 `diskType` + `diskUrl`，`diskCode` **可空** | `validate()`。不能顺手把提取码也要求上 —— 阿里云盘/夸克没有提取码机制 |
| 3 | 改帖后 `status` 回 0（重新进入审核） | 提交按钮下方常驻提示"保存后重新进入审核" |

**图片上传仍未做**，原因见 §L.2（CR-G 在运行中的 8080 上没生效）。这块**没有**放"看起来能用"的假上传器，
也没有放很扎眼的大警告条，只用一行小字说明状态。

### M.2 E2E 回归网（**红 → 绿 两次输出**）

```
第一次（红）：11 条里 9 passed / 2 failed
第二次（绿）：11 passed (15.7s)   ← 无重试
```

覆盖（`web/e2e/m3-forum.spec.ts`，6 条 + M2 的 5 条）：

1. 首页信息流**来自真接口**（断言响应 `code=0`、`list` 非空、左栏总数 = 响应 `total`）+ 未登录点「＋」跳登录
2. 搜索：**空关键词不发请求**（用 request 监听计数断言为 0）+ 有关键词才发并显示结果数
3. 版块页：**`isResource` 正反两面**（资源版块有标记、普通版块没有）
4. 详情：正文/网盘/作者正确 + **一键复制的剪贴板内容逐字校验**（§5.5）+ 404 友好页
5. 发帖页：资源版块出现网盘字段、切到普通版块即消失（**正反两面**）
6. **发帖 → 详情 → 编辑 → 删除**全链路，并断言「**覆盖语义下网盘链接不能被清空**」与
   「改帖后 `status` 必须回 0」

**每个要发帖的用例都现注册一个全新账号** —— 因为发帖限流是**按账号**的
（实测过一次「请 8734 秒后重试」≈2.4 小时）。复用账号会让第二次运行必然变红，
而**红的原因与代码无关** —— 那是最坏的一类失败。

### M.3 这一轮抓到的两个问题（一个是我的 bug，一个是框架行为）

#### (1) 「编辑于 …」是**假阳性** —— 已把该提示删掉（我的 bug）

界面快照里出现「编辑于 10 分钟前」，而那是**从没被编辑过**的演示帖。查库：

```
id  title                    view_count  updated_at - created_at
45  整理了一份 Java 后端转 A        4       94501 秒  ≈ 26 小时
43  AI 工具实测：这 5 个真的能       0           0
```

**根因**：`post.updated_at` 这一列带 `ON UPDATE CURRENT_TIMESTAMP` —— **任何对该行的写**都会顶它。
那篇帖只是 `view_count` 从 0 涨到 4（浏览量回写），`updated_at` 就被推后了 26 小时。
我原来用 `updatedAt - createdAt > 60s` 推断"编辑过"，于是它**长期挂着一句假的业务事实**。

**处置**：契约里**没有**可靠的编辑信号（`PostDetailVO` 只有 `createdAt`/`updatedAt` 两个时间字段），
所以正确的做法是**不显示**，而不是拿一个含义不符的字段去猜。代码里留了说明。
> 若确实要这个功能，应由后端给明确语义的字段（如 `edit_count` / `last_edit_at`）—— **那是契约变更**，前端不自己造。
> 这一条也顺带说明：**"某个字段存在"不等于"它能回答你想问的问题"**。

#### (2) 同路由的 hash 变更会**复用页面实例**（框架行为，非应用路径）

E2E 第一次运行时两条失败，快照显示 `page.goto('#/pages/post/detail?id=999999')` 之后
**页面仍显示上一篇帖子** —— 因为 `page.goto` 只改 hash 时，uni-app H5 复用同一个页面实例，
`onLoad` **不会重跑**。

**这不是应用内的可达路径**：应用内从版块 A 到版块 B、从帖子 5 到帖子 9 都必须经过
`uni.navigateTo` / `redirectTo`（会创建**新实例**）—— 版块页没有切换器，详情页也没有"下一篇"。
所以 E2E 改用 `page.reload()`（= "在新页里打开链接"的等价写法），并在注释里写清了原因。

⚠️ **但它对"深链接"是一个真实的小限制**：如果用户把另一条帖子的链接粘进**同一个标签页**的地址栏，
旧内容不会刷新。**我没有加代码去堵它** —— 那需要绕开框架的页面生命周期。
若需求方认为这值得修，请明确（它属于"要不要为 SPA 地址栏导航做健壮性"的取舍）。

### M.4 顺带注意：开发库里现在有 L1 的探针帖

首页信息流里会出现 `读时签名证据帖 201917` / `读时签名最终证据帖 210143` 两条 ——
那是 **L1 验证"读时签名"时创建的**，不是我造的，我**没有删**（那是别人的证据）。
若要演示效果干净，需要 L1 决定是否清理。

### M.5 验证

```
npx vue-tsc --noEmit -p tsconfig.json   → exit 0
npx playwright test                     → 11 passed（M2 的 5 条仍全绿）
node scripts/shot.mjs                   → 首页/搜索/版块/详情/发帖 各三档，无 pageerror、无 4xx/5xx
```

**E2E 从"未验证"变成"已验证"** —— 报告里此前那条"E2E 仍未写"的口径就此关闭。

---

## N. 第 5 段：图片上传（**前端已就位，但最小闭环卡在服务端配置**）

### N.1 做了什么

| 文件 | 内容 |
|---|---|
| `web/src/api/oss.ts` | `fetchSignature()` → `GET /api/oss/signature`（需登录） |
| `web/src/utils/upload.ts` | PostObject 直传：预检 → 填表 → POST 到 `host` → 解析回调结果 |
| `web/src/pages/post/edit.vue` | 选图 / 逐张上传 / 缩略图预览 / 单张可删 / 9 张上限 / **上传中禁止提交** / 失败原因常驻 |

**表单字段与顺序**（不是我自己定的，是 OSS PostObject 规范 + 契约共同决定的）：

```
key              ← dir + 文件名（唯一，不覆盖已有对象；dir 带尾斜杠，CR-009）
policy           ← 响应原样
signature        ← 响应原样
OSSAccessKeyId   ← 响应的 accessKeyId（契约 description 原文就写了这个字段名）
callback         ← 响应原样（Base64 回调配置，不自己拼 JSON）
Content-Type     ← 由文件类型解析（policy 里有 starts-with $Content-Type image/）
file             ← 必须是**最后一个**字段
```

⚠️ `uni.uploadFile` 的 H5 实现是「先 append 全部 `formData`，最后 append 文件」
（`@dcloudio/uni-h5`：L21326 循环 append → L21330 append file）——**正好满足 OSS 的要求**，
所以不需要自己写 XHR。

**上传成功后从哪拿 URL**：OSS 把后端回调的响应体原样作为本次 POST 的响应返回 →
前端读 `data.{id,url,thumbUrl}`（**CR-G 裁决 A**）。**不用自己拼 `host+dir+文件名`**。

### N.2 实测抓到的三个问题

#### (1) 我的 bug：H5 的 `tempFilePaths` 是 `blob:` URL，**没有扩展名**

第一版按扩展名判类型 → **每一张图都被自己的预检拦下**，界面显示
「只支持 .jpg / .jpeg / .png / .webp / .gif 格式的图片」，而用户选的明明是 PNG。

根因（读 `@dcloudio/uni-h5` 的 chooseImage 得到）：H5 端 `tempFiles` 里放的是
**真正的 `File` 对象**（只是给它加了一个 `path` getter 指向 blob URL），
所以 `type` / `name` 都在，而 `tempFilePaths` 是那个 blob URL。
小程序端相反：`tempFiles` 只有 `{path,size}`，但 **path 带扩展名**。

**修法**：类型解析改成「**先看 `File.type`（H5），再退回扩展名（小程序/兜底）**」，两条合起来覆盖三端。
> 教训：**"路径"与"文件"是两回事** —— 能拿到 `File` 对象时不要从路径去猜它的属性。

#### (2) dev server 反复死亡的根因：写入工具的临时目录被 Vite watcher 盯上

```
Error: EBUSY: resource busy or locked, watch
  'web\src\pages\post\.edit.vue.<pid>.<uuid>.tmpdir\edit.vue.tmp'
  Emitted 'error' event on FSWatcher instance   ← 未被捕获，直接终止进程
```

文件写入工具用「写临时文件 → 原地替换」落地，临时目录建在**源码目录里面**；
Vite 的 FSWatcher 去 watch 它、而文件正被占用 → `EBUSY` → watcher 抛 error → **整个 dev server 退出**。
现象是"前端改到一半就没服务了"，**与业务代码毫无关系**（本机为此白排查了两轮）。

**修法**：`web/vite.config.ts` 的 `server.watch.ignored` 忽略 `**/.*.tmpdir/**` 与 `**/*.tmpdir/**`。
**任何人（含 L1）用同一套写入工具改前端代码都会踩这个坑** —— 这条修好之后，dev server 已经连续扛过多次写入。

#### (3) 🔴 **阻塞（不是前端的问题）**：签名里的回调地址是**环回地址**

E2E 跑到真实上传时，OSS 返回：

```xml
<Error>
  <Code>InvalidArgument</Code>
  <Message>Private address is forbidden to callback.</Message>
  <ArgumentName>callbackUrl</ArgumentName>
  <ArgumentValue>http://127.0.0.1:8080/api/oss/callback</ArgumentValue>
</Error>
（HTTP 400）
```

三条独立证据指向同一件事：

1. **解开签名里的 `callback`**（我直接把 base64 解了）：
   ```json
   {"callbackUrl":"http://127.0.0.1:8080/api/oss/callback", ...}
   ```
2. **OSS 的响应**：如上，400 + `Private address is forbidden to callback`。
3. **后端自己的日志**（`.tmp/app-8080.out.log`）—— L1 设计的那条 WARN 确实打了：
   ```
   22:00:33 WARN OssCallbackUrlResolver : 未显式配置 OSS 回调地址，将按请求推导…
   22:06:41 WARN OssCallbackUrlResolver : 从请求推导出的回调地址是环回地址 [http://127.0.0.1:…
   ```

**结论**：**8080 这次启动没带 `OSS_CALLBACK_URL`**，回调地址回退成了 `127.0.0.1`，
OSS 在**存对象之前**就拒掉了整个上传（所以桶里没留下垃圾对象）。

**前端侧无一处需要改**：O 前端把签名里的字段**原样**填了表，是签名本身带着一个 OSS 不接受的回调地址 ——
这恰好证明"前端只做原样透传"这条设计是对的：**服务端的配置错误在客户端被一眼看穿，而不是被前端悄悄兜掉**。

我顺手在 `parseUploadBody` 里**把这个故障单独认了出来**，文案指向服务端配置
（否则它会显示成"上传失败，请稍后重试"，让运维一直往前端找）。

**需要 L1 做一件事**：用带公网回调地址的环境变量重启 8080，例如
`$env:OSS_CALLBACK_URL='http://8.138.237.212/api/oss/callback'`（L1 给的公网入口），再叠加两个 AccessKey 变量。

### N.3 ✅ §6.1 最小闭环：**已跑通**（2026-09-17 22:22）

修好服务端配置（见 N.5）后，整条链一次通过：

```
npx playwright test e2e/m3-upload.spec.ts --reporter=list
Running 1 test using 1 worker
[最小闭环] post=57 url=https://hy-forum-2026.oss-cn-beijing.aliyuncs.com/post/1789654433209-n7ce7n.png?OSSAccessKeyId=…&Expires=…&Signature=…
[最小闭环] thumbUrl=https://hy-forum-2026.oss-cn-beijing.aliyuncs.com/post/1789654433209-n7ce7n.png?x-oss-process=image/resize,m_fill,w_360,h_360/quality,q_80&OSSAccessKeyId=…&Expires=…&Signature=…
ok 1 [h5-chrome] › 最小闭环：选图 → 直传 OSS → 回调落库 → 发帖 → 详情页看到图 (4.2s)
1 passed (5.0s)
```

| 环 | 状态 |
|---|---|
| ① 选图 | ✅ |
| ② 取签名 `GET /api/oss/signature` | ✅ 7 个字段齐全（`accessKeyId` 长度 24） |
| ③ 直传 OSS（浏览器 → OSS） | ✅ HTTP **200** |
| ④ 回调落库（OSS → 后端 `/api/oss/callback` → `post_image`） | ✅ 见下方 SQL |
| ⑤ 发帖带图 → 详情页看到图 | ✅ 详情页渲染出 `images[0]` |

**§6.3 证据① 的 SQL 输出**（`mysql -u root -p1234 -D hy_forum -e "SELECT ... FROM post_image"`）：

```
id  post_id  url                                                              thumb_url                                                         width height sort audit_status
3   57       .../post/1789654433209-n7ce7n.png                                .../post/1789654433209-n7ce7n.png?x-oss-process=image/resize,m_fill,w_360,h_360/quality,q_80   0     0      0    0
```

四条**顺带被验证到**的事实（都不是我推的，是查出来的）：

1. **`post_image.url` 存的是裸地址、不带签名** → 不会过期。而 `GET /api/posts/{id}` 返回的是
   **读时签名**的临时地址（带 `OSSAccessKeyId`/`Expires`/`Signature`，桶是私有的，这是对的）。
   两条合起来说明：**前端既不能自己拼 URL，也不能把拿到的 URL 缓存下来**（它会过期）——
   我现在正是"渲染接口给什么就用什么"，与这个结论一致。
   > 上传回调返回的 `data.url`/`data.thumbUrl` 也是**裸地址**（实测），
   > 所以前端把它交给 `POST /api/posts` 之后，库里存下来的就是干净的裸地址。
2. **`post.cover_url` 用的是缩略图**（带 `x-oss-process`）→ 列表图不拉原图，符合口径 6 的意图。
3. **`audit_status = 0`（尚未人工判定）时图片照常展示** —— 与 **CR-006** 定的
   「前台可见性规则是**隐藏 `=2`**，不是必须为 1 才显示」完全一致。这条语义**端到端验证到了**。
4. ⚠️ **`post_image.width` / `height` 后端填的是 `0`**（OSS 回调体里没有图片尺寸，
   后端也就没去探测）。后果：我在详情页写的"**单图按契约的真实宽高比展示**"
   （用 `width/height` 算 `padding-top` 比例）**永远走 16:9 兜底** —— 竖图会被按横图的比例框住。
   已按"拿不到就用兜底"处理，所以不会坏，但那个特性实际上**没生效**。
   **若要它生效，需要后端在读回调时探测一次图片尺寸并落库**（那是后端的事，已登记）。

### N.4 本轮验证

```
npx vue-tsc --noEmit -p tsconfig.json                        → exit 0
npx playwright test e2e/m3-upload.spec.ts                    → 1 passed（最小闭环）
npx playwright test                                          → 见 §O（全量回归）
```

证据文件：`.tmp/fe-upload-evidence.json`（签名摘要、上传响应、落库后的 `images`）。
⚠️ 该文件里**不含** `accessKeyId` / `policy` / `signature` 的**值**，只留长度 ——
铁律 5 同源：它们是请求时临时收到的标识与签名，不该被复制进任何文件。

### N.5 服务端配置的修复（我做的，需 L1 知悉）
需求方裁定由我重启 8080。过程与结论：

| 步骤 | 结果 |
|---|---|
| 停掉旧实例（PID 48132） | ✅ 端口释放 |
| 用 `Start-Process` 起（带 3 个变量） | ⚠️ 应用**正常启动**（日志有 `Started HyForumApplication in 5.397 seconds`、`Tomcat started on port 8080`），但 **进程随我的命令结束被回收** → 等于没起 |
| 改用 WMI（`Win32_Process.Create`）做真正脱离 | ❌ **被沙箱拒绝**：`Access denied`（`Cannot convert value "Win32_Process" to type ManagementClass`） |
| 改用**受管后台作业** | ✅ 成功。`OSS_CALLBACK_URL` 生效：签名里的 `callbackUrl` 从 `http://127.0.0.1:8080/...` 变成 **`http://8.138.237.212/api/oss/callback`** |

⚠️ **两件要 L1 知道的事**：
1. **8080 现在跑在我的会话的后台作业里**（作业 `pwsh-178`）——**会话结束它可能就没了**。
   要一个长期稳定的实例，建议 L1 用自己的方式起（带那三个变量）。
2. **"某个进程被启动过然后又消失"这种坑值得记**：`Start-Process` 在这里**不足以脱离**调用方，
   判断"起没起来"**不能只看启动日志**（日志会显示成功），**要查端口是否仍在监听**。

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

---

# O. M4 前端第一段：评论 + 点赞/收藏 + 两处版式调整（已完成）

## O.1 做了什么

| 文件 | 内容 |
|---|---|
| `web/src/api/types.ts` | 补齐 M4 具名类型（`CommentItemVO` / `CommentReplyVO` / `CommentCreateRequest` / `UserProfileVO` / `FeedItemVO` / `FollowUserVO` / `UserCommentVO` / `CollectionItemVO` / 5 种分页），**全部直接来自契约** |
| `web/src/api/contract.ts` | 登记 M4 的 15 个端点（方法逐个核对 `openapi.json`，不是抄《技术方案》）；`collections` / `userProfile` 由 `todo` 转 `ready` |
| `web/src/api/interaction.ts` | 帖子点赞/收藏、关注、评论点赞（POST/DELETE，幂等） |
| `web/src/api/comments.ts` | 评论列表 / 楼中楼列表 / 发评论 / 删评论 |
| `web/src/stores/interaction.ts` | **会话内**的点赞/收藏态（原因见 O.2 的 CR-K —— 契约缺字段） |
| `web/src/components/CommentSection.vue` | 评论区（默认收起；发表/回复/点赞/删除/查看全部楼中楼/分页） |
| `web/src/pages/post/detail.vue` | 互动栏接真接口（乐观更新 + 失败双回滚）；插入评论区；「评论」按钮展开评论区 |
| `web/scripts/seed-demo-data.sql` | 加入**演示评论**（两层结构），并修掉一个会误导人的校验标签 |

## O.2 🔴 CR-K（**需要契约补充**）：`PostDetailVO` 缺 `isLiked` / `isCollected`

**现象**：`POST /api/posts/{id}/like` 这类端点返回的都是 `ApiResponseVoid`（**没有 data**），
而 `PostDetailVO` / `CommentItemVO` / `CommentReplyVO` **都没有**"我是否已点赞/已收藏"的字段。

**后果**：**服务端从不告诉前端"我有没有点过赞"** → 刷新一次页面，点赞按钮就回到未点赞的样子
（而计数是真 +1 的）。用户会以为自己没点过，再点一次；虽然端点幂等（§8.1）不会算错，
但**界面在说谎**。

**对比**：`UserProfileVO` **有 `isFollowing` / `isFollowedBy`** —— 说明这套设计本身是考虑过这个问题的，
只是点赞/收藏这两处漏了。

**我的处置（临时层，已写进代码注释）**：
- 计数以**服务端**为准（进入页面那份），操作后本地 ±1；
- 激活态放 `stores/interaction.ts`（**会话内存**），刷新即丢；
- 界面**不谎称**它是权威状态，代码里明确写了"这是契约缺口，补上 `isLiked` 之后本层即退役"。

**建议的变更**（三选一）：① `PostDetailVO` 补 `isLiked` / `isCollected`（信息流项同理）；
② 或让点赞/收藏端点返回新计数 + 状态（而不是 `Void`）；③ 或提供批量接口（按 postId 列表查我的点赞/收藏）。
**我倾向前两者**：③ 会让详情页多一次请求，而详情页本来就是"打开就要看状态"的场景。

## O.3 另一处口径缺口（不阻塞）：`CommentCreateRequest` 没有 `replyToUserId`

《技术方案》§6.6 的文字里提到"参数：postId、parentId、content、**replyToUserId**"，
但**契约里没有这个字段**。所以"回复某条楼中楼里的某人"时，前端**无法指定被回复者**。

**我的处置**：严格按契约 —— `parentId` **一律填主楼的 id**（P1-3 归并语义，填楼中楼的 id 会被
后端唯一写入口的"目标必须是主楼"校验拒掉），被回复者由后端在 `replyToNickname` 里体现。
E2E 里对 `parentId === rootId === 主楼 id` 做了断言。

**建议**：若希望支持"回复楼中楼里的某人"，契约需要补 `replyToUserId`（否则后端只能自己猜）。

## O.4 两处版式调整（需求方 2026-09-17 提的）

1. **列表卡片的互动行铺到卡片底部一整行**。
   之前是「版块标签在左、点赞/评论/收藏挤在**右下角**」，看过去像把操作塞进角落。
   现在：版块标签单独一行 → 分隔线 → 互动行（点赞/评论/收藏从左排开，**浏览量靠右**）。
2. **评论区默认收起、点击才展开**。
   收起态显示「查看 N 条评论 / 写评论」，**且不发请求** —— 要显示的条数由详情响应给，
   所以"只看一眼帖子"不会白拉一次评论列表。展开状态由详情页持有，
   这样上面互动栏的「评论」也能把它打开（并滚过去）。

## O.5 本轮踩到的坑（写给后来写 E2E 的人）

**`uni-textarea` 对程序化 `fill()` 的同步是竞态**。
Playwright 的 `fill()`（直接设 DOM 值 + 派发一次 `input`）**偶尔**不会被 uni 的组件同步进
`modelValue` —— 现象是 **DOM 里有字、组件里是空**，提交只得到一句"说点什么再发表"，
而看失败快照会以为"我明明填了"（极误导）。

**处置**：给计数器（由 `draft` 驱动）加 `data-testid`，把它当**组件状态的可观察代理**，
填完等计数器变化、没变就重填，三次还不行就抛出**写明原因**的错误 ——
把一个"必然超时"变成"确定性失败 + 自解释"。
⚠️ 这是**测试侧**适配，不是产品缺陷：真实键盘输入走 uni 的按键路径，不会这样。

## O.6 顺带修掉的一个"会误导人的检查"

种子的末尾校验原先叫 `sum of fake counts` 并期望 `0`，但它把**所有**帖子的
view/like/comment/collect 加起来 —— 而库里还有别的真实帖子，于是它必然非 0，
**看起来像种子出了问题**。而真正的不变量只有两条：
`like+collect` 必须为 0（我们从没给它们编过数字）、`comment_count` **等于真实评论数**（现在不是 0 才对）。
已改准并把标签写清楚。

## O.7 演示数据：加入了两层结构的演示评论

6 条评论（2 个主楼 + 3 条楼中楼 + 1 个主楼），覆盖"主楼/楼中楼/多条楼中楼"三种形态。
`parent_id = root_id = 主楼 id`（`schema.sql` 的 `chk_comment_two_levels` 会强制校验，插得进去即为合规），
并回写了主楼的 `reply_count` 与帖子的 `comment_count`。

## O.8 验证

```
npx vue-tsc --noEmit -p tsconfig.json   → exit 0
npx playwright test                     → 17 passed (29.2s)
   M2 认证 5 条 + M3 六个页面 6 条 + 最小闭环 1 条 + M4 5 条
```

M4 的 5 条里，有两条是**只有真后端才验证得到**的：
- `楼中楼的 parentId/rootId 必须等于主楼 id`（归并语义的不变量，用接口复核）；
- `replies 是预览、replyCount 是总数`（发 3 条楼中楼后"查看全部 N 条回复"必须出现且点了能看到全部）。

## O.9 还没做的 M4 部分

**个人主页**（`/api/users/{id}` + 帖子/评论/关注/粉丝 四个 Tab）、**关注按钮**、**我的收藏**、
**首页双流**（`/api/feed?type=follow|all` —— 现在接口有了，可以把 §4.1 的"关注/全部"补上）。
按 L1 给的顺序，这些是下一段。

## O.10 ⚠️ 请 L1 注意：开发库里的测试帖在累积

首页信息流现在混着三类非演示内容：
1. **L1 的探针帖**（`读时签名证据帖` / `读时签名最终证据帖`、`分享一套 Java 学习资料` ×2，
   部分是 lorem ipsum 文本与噪声图）；
2. **我闭环 E2E 建的带图帖**（`带图帖（E2E 直传 OSS）…`）—— **每跑一次闭环 E2E 就会新增一条**；
3. 早先 M3 E2E 留下的若干条。
这些**都不是我该单方面删的**（1 是 L1 的证据）。但演示观感会受影响，
所以请 L1 定一个清理策略（例如：给探针帖约定一个标题前缀，或让 E2E 跑完自删）。

---

# P. 需求方实测反馈两条（2026-09-18）

## P.1 ✅ 已修：资源版块发帖失败 —— 根因是**后端的链接校验**，前端改为"整段粘贴"

**复现**（直接打后端，两条对照）：

| 请求 | 结果 |
|---|---|
| `diskUrl` = 夸克**整段分享文案** | **`HTTP 400 {"code":400,"message":"diskUrl 必须是 http/https 链接"}`** |
| `diskUrl` = 只贴 URL | **`HTTP 200`** ✓ |

需求方的原话：「分享链接，**不要做校验**啊。我是专门搞这个的，直接复制过去就行了。
没有什么提取码，人家现在都内嵌好了的。」

**处置（前端侧，不动后端）**：新增 `web/src/utils/netdisk.ts`

1. **接受整段粘贴**：用户从网盘 App 复制出来的通常是一整段文案（含"我用夸克网盘给你分享了…"），
   前端**全部接受**，在**提交前**把其中那条 `http(s)` 链接抽出来。
   抽不到链接才提示（因为那种情况后端必然 400，而它的报文对用户毫无信息量）。
2. **不静默改写**：抽出来的链接与粘贴内容不同时，输入框下方明确显示「**将保存为：…**」。
3. **阿里云盘 / 夸克网盘的提取码输入框直接不显示**（这两种没有提取码机制，§5.5 的表格也写着），
   改为一行说明「夸克网盘的提取码就在链接里，不用单独填」。
   并且**即使输入框里还留着旧字符，提交时也不带 `diskCode`** ——
   否则会把一个与链接无关的值写进库，详情页就显示一个假的提取码。
4. 输入框的 `maxlength` 从 500 放到 2000（只为挡住异常长的粘贴）；
   真正的 500 限制仍然只作用于**抽出来的链接**（契约 `diskUrl.maxLength = 500`）。

**E2E**：`m3-forum.spec.ts` 新增一条用例 —— 整段粘贴 → 提交成功 → **断言库里存的是链接本身**
（`diskUrl === 'https://pan.quark.cn/s/…'`）→ 夸克时 `diskCode` 为 `null` → 详情页渲染正常。

> **给 L1 的观察（不是 CR）**：后端要求 `diskUrl` 必须是 http/https 链接这件事**本身可以保留**
> （它是防御），但真实世界里"复制整段文案"是**最主流的粘贴方式**（夸克/百度 App 的分享按钮给的就是整段）。
> 将来小程序端、App 端、以及任何第三方客户端都会遇到同一件事。
> **建议后端也做一次"从文本里抽链接"**（一处兜住所有客户端），前端这一层仍保留（存进去的必须是能用的链接）。
> 这属于 `server/**`，我不动。

## P.2 ⚠️ 未复现：图片加载不出来 —— **我这边测是正常的，需要你指认**

需求方反馈"图片也加载不出来"。我用探针实测（读 `naturalWidth`，因为 uni-app 的 `<image>`
加载失败**不一定有可见报错**，只是个空白灰框）：

```
node scripts/img-probe.mjs   （PROBE_ROUTE=#/pages/index/index）
  OSS 响应数 = 4（全部 200）
  <img> 数 = 4 → OK w=320 h=200 ×3、OK w=360 h=360 ×1   => 全部加载成功

PROBE_ROUTE=#/pages/post/detail?id=83
  OSS 响应数 = 1（200）
  <img> 数 = 1 → OK w=320 h=200                          => 全部加载成功
```

**结论：在我能测到的范围内，图片是能加载的**。所以这条我**不能凭猜去改**。
两个可能的方向（需要指认）：
1. **签名 URL 过期**：详情/列表接口返回的是**读时签名**的临时地址（桶是私有的），
   `Expires` 大约 1 小时。如果页面**长时间开着不刷新**，DOM 里那些地址会过期 → 图变成灰框。
   Hmm 这个我能通过"图片 onerror 时重新取一次详情（拿新签名）"来兜，但会多一次请求。
2. **某些帖子本身没有可用封面**（早期探针帖的图源已失效）—— 那属于数据不属代码。

**所以我在这一轮不做任何修改**，请需求方指认：哪个页面、哪条帖子、是**空白灰框**还是**裂图**。
（探针已留在 `web/scripts/img-probe.mjs`，下次一条命令就能复核。）

## P.3 验证

```
npx vue-tsc --noEmit -p tsconfig.json   → exit 0
npx playwright test                     → 18 passed (30.4s)
```

---

## P.4 ✅ 已修（第二条）：发帖时"选了图、缩略图不出来"

需求方指认了这个具体现象（**不是**页面上的图坏掉，而是**发帖选图后没有反馈**）。
我用 `web/scripts/upload-probe.mjs` 实测了两个路径：

| 场景 | 实测结果 |
|---|---|
| 真实文件 273KB | OSS **200**、回调成功、缩略图出现 —— **上传本身没问题** |
| 合成 6MB（超 5MB 上限） | **不发 OSS 请求**，页面显示「图片不能超过 5MB」✓ |

**所以根因是 UX，不是上传功能**：旧实现**只有上传成功之后**才把缩略图放上去，
而从选完图到上传完成之间（真实照片 + 外网，可能几秒）**界面什么都没发生** ——
看起来就像"选了没用"。

**改法**（`pages/post/edit.vue`）：
1. **选完立刻用本地路径预览**（H5 的 `tempFiles[i].path` 是 blob URL，小程序是临时路径），
   叠一层「上传中」遮罩 —— 图**从第一秒就在屏幕上**；
2. 每张图**独立状态**：`uploading / done / failed`（用 `data-status` 暴露，E2E 可断言）；
   失败的那张**画成红底「失败 · 点击重试」**，点它只重试那一张（不用重新选文件）；
3. **失败原因逐条列在网格下方**（哪张、为什么）—— 不再是一行容易被忽略的小字；
4. 旧的 `break`（一张失败后面全不传、也不报）改成**每张独立处理**；
5. 顺带把顺序理顺：**先本地预检（类型/大小）、再取签名** ——
   旧实现先取签名再逐张预检，等于让服务端为一张注定被拒的图白签一次。

**E2E 用一条"会失败"的断言钉住它**（`m3-upload.spec.ts`）：
把 `/api/oss/signature` 人为拖慢 3 秒 → 选完图后立刻断言
`data-status === 'uploading'` **且** `<img src>` 是 `blob:` 路径。
两条都标明"此刻上传还没开始"，所以"选完就有预览"与"传完才有预览"能被区分开 ——
**否则这条断言就是假绿**。

> 这条测试我改错了两次，两次都记在代码注释里：
> ① 第一次把延迟加在 **OSS 的 POST** 上，而 `page.route` 对那次上传**不生效**
> （函数匹配与 glob 都试过，读到的状态始终是 `done`）→ 改用**签名接口**（同源 XHR，拦截稳定）；
> ② 第二次把断言写在 `await uploadResponse` **之后** —— 那时上传早结束了，测的不再是"立刻"。
> **"测一个时间窗"的断言，位置和拦截点都是它的一部分**，写错就变成假绿。

## P.5 本轮验证

```
npx vue-tsc --noEmit -p tsconfig.json   → exit 0
npx playwright test                     → 18 passed (33.0s)
```

---

# Q. 需求方 2026-09-18 第二轮反馈（版式 + 一键复制口径）

## Q.1 参考站能不能访问：**浏览器可以，shell 不行**；登录被滑块挡住，我没绕

- `https://topic-bridge-network.nocode.host/#/` 用 Playwright（Chrome）**能打开**，
  无 console 报错 —— 这再次印证 §P 的结论：**分界是"通道"而不是"环境"**
  （shell 的 curl 对所有外部域名都是 000，浏览器有完整出网）。
- 用需求方给的账号（`adm123`）走到 **「请先完成人机验证」** 就被挡住：
  登录页有**滑块拼图验证**。
- **我不过这个验证** —— 它本来就是拦自动化的机制，绕过它既不合规也不值得。
  需要更多页面版式时由需求方登录后截图给我（此前两张就很有用）。
- 探针留在 `web/scripts/refsite-probe.mjs`，顺带记了一个坑：
  **点「我已阅读并同意」会先弹协议弹窗**，第一次漏了这步，登录按钮被弹窗遮罩挡住，
  报的却是 `div.fixed.inset-0.z-50 拦截了指针事件` —— 一个与登录毫无关系的误导性报错。

## Q.2 版式：互动栏做大 + 实心爱心变红；图片统一按九宫格尺寸

| 需求方原话 | 做法 |
|---|---|
| 「点赞、收藏能不能做大一点？」 | 详情页互动栏图标 **20px → 28px**（`size="xl"`）、计数 12px → 14px、整条 padding 加大 |
| 「点赞做成爱心的那种，点亮的爱心要变红」 | 点赞：未点亮是**线框爱心**、点亮是**实心爱心 + 红色**；收藏同理用**实心书签 + 主色紫**（两个都红会分不清谁是谁）。⚠️ 只改颜色不够 —— 灰色线框爱心变红在浅色背景上仍然"不像点过" |
| 「图片不要一下子加载全部，跟九宫格一样，有多少个就加载多少个」 | 列表卡片封面从**通栏大图**（100% 宽 / 180px 高，占掉半屏）改成**九宫格里一格**（1/3 宽 / 92px）+「N 张」角标；详情页图片**一律三列九宫格**，单图也占一格 |

**顺手删掉一段死代码**：详情页原来"单图按契约 `width`/`height` 算真实比例"的
`singleRatio` —— 实测后端那两个字段**恒为 0**（OSS 回调体里没有图片尺寸、后端也没探测），
它永远走 16:9 兜底，等于从未生效（§N.3 已登记过）。现在单图也走九宫格，这段自然不需要了。

## Q.3 ⚠️ 口径变更（**与《技术方案》§5.5 不一致，需 L1 同步文档与后端**）：一键复制只留链接

**需求方原话**：「你复制那个链接时不要带上其他东西啊，直接带上链接就行了……
改成 `https://pan.quark.cn/s/8cb810550990` 就是只有链接」。

**已按此改**：`buildDiskCopyText()` 现在只返回链接本身
（原来是 §5.5 锁定的三行式：`链接：…` / `提取码：…` / `来自 Hy论坛`）。

**变更前我把代价讲清楚了，需求方知情并选择"无条件只有链接"**：

- 后端保存时会把链接里的 `?pwd=` **拆走**、把提取码存进 `diskCode`（§5.5 第 2 条），
  因此**链接本身已不含提取码**；
- 于是对**百度网盘**这类带提取码的帖子，"只复制链接"= 用户拿到的链接**点不开**；
- **缓解**：详情页**仍然把提取码显示在页面上**（复制按钮不再包含它，但它没消失），
  用户可以自己选中复制。

⚠️ **两条要 L1 处理的**：
1. **文档**：§5.5 的"一键复制内容格式"需要同步改成"只复制链接"，否则文档与实现漂移；
2. **后端**：任务书口径 8 说该格式"由后端 `DiskCopyText` 锁定"。契约里**没有任何字段**
   暴露这段文案，所以前端与那个类的约定现在**不一致** —— 只有 L1 能对齐（改文档 + 后端类）。
   **我没有编造任何契约字段**，只是按需求方授权改了前端拼串。

E2E 里的剪贴板断言已同步（原来逐字校验三行，现在校验"只有链接"）。

## Q.4 🔴 CR-L（**需要契约补充**）：列表卡片做不出真九宫格

需求方要"列表也像九宫格那样，有多少张就画多少张"。但契约里
**`PostSummaryVO` 只有 `coverUrl`（一张图）+ `imageCount`**，**没有图片列表** ——
所以列表页最多画一格，其余几张的地址前端根本拿不到。

**我现在的处置**：如实画一格 + 右下角标「N 张」说明不止一张，
**不去拼 URL**（口径 7 同源：不自己拼图片地址）。

**建议的变更**：`PostSummaryVO` 增加图片列表，例如
`images?: { url: string; thumbUrl: string }[]`（**只给前 3~9 张的缩略图**即可，
列表不需要原图）。有了它，列表卡片就能按张数画 1 格 / 4 格 / 9 格。
**代价**：列表响应变大（每张一个 thumbUrl）；若担心，可只给前 3 张
（列表上一行三列够用），第 4 张起用「+N 角标」表示 —— 这也是参考站的做法。

## Q.5 本轮验证

```
npx vue-tsc --noEmit -p tsconfig.json   → exit 0
npx playwright test                     → 18 passed (33.2s)
```

提交：`6d24e7f`（版式）+ 本轮的复制口径变更。

---

# R. 需求方 2026-09-18 第三轮反馈

## R.1 ✅ 已做：爱心换成**线框**、且在**列表卡片**就放大（不只是详情页）

需求方原话：「我要的爱心变大是要在**我现在这个页面**啊，不要点进去再变大呀。
你这个爱心是啥呀，我要我的图片这种啊」——指的是**首页列表卡片**上那一行图标。

| 项 | 改前 | 改后 |
|---|---|---|
| 列表卡片图标 | `size="sm"`（14px） | **`size="xl"`（28px）** |
| 列表卡片计数 | 12px | 14px（浏览量那档保持 13px、灰色，不抢注意力） |
| 图标间距 | 32px | 44px（放大后间距不同步会挤成一团） |
| 爱心形状 | `heart`（"两个圆 + 旋转方块"**各自描边**）| **`heartOutline`（线框爱心）** |

**关于爱心形状（这是个实打实的实现问题，不是审美偏好）**：
爱心是"两个圆 + 一个旋转方块"的**并集**。如果只给这三个形状**各自描边**，
它们相交处的边框会**露在轮廓内部** —— 看起来像三个图形叠在一起，而不是一颗心。
所以新的 `heartOutline` 换成**"先画实心、再用卡片底色按比例内缩挖空"**，得到一条连续轮廓。
⚠️ 代价：挖空用的是白色，**只能放在白色底上**（当前两处用法都是白色卡片）。
将来若要用在非白底上，得把内层颜色改成所在容器的底色 —— 已写进 `HyIcon.vue` 的注释。

## R.2 🔴 列表「显示全部图片」：**契约拿不到，我做不到**（CR-L 升级为阻塞项）

需求方原话：「不要这种啊，我要你九宫格，就是要你**全部都展示出来**呀」。

**我复核了契约（2026-09-18，`openapi.json` SHA `1C60566B…`）**：

```
PostSummaryVO = [id, boardId, boardName, title, coverUrl, imageCount,
                 viewCount, likeCount, commentCount, collectCount, author, createdAt, isTop, isEssence]
FeedItemVO    = [id, boardId, boardName, title, coverUrl, imageCount, ... 同样没有图片列表]
```

**只有 `coverUrl`（一张）**。第 2、3 张图的地址前端**根本拿不到**，
而我**不能自己拼 OSS 地址**（铁律 5/8、口径 7：前端不拼图片 URL）。

**为什么不能用"每篇再请求一次详情"绕过**（我考虑过，明确否定）：
`GET /api/posts/{id}` 每次都会把**浏览量 +1**（§8.3 走 Redis）。
列表里一屏 20 条、每条都补一次详情 → **把所有帖子的浏览量都刷爆**。
用一个真实的副作用去换一个视觉效果，是绝对不能接受的。

**所以这条卡在契约上，需要 L1 加字段。给 L1 的一句话**：

> `PostSummaryVO` 与 `FeedItemVO` 请补一个图片列表字段（例如 `imageThumbs: string[]`，
> 给**前 3~9 张的缩略图 URL** 即可，列表不需要原图）。列表卡片要按张数画九宫格，
> 而现在只有 `coverUrl`，第 2 张起前端没有地址可用。

**现状**：如实画一格 + 右下角「N 张」角标，**不拼地址、不假装有九宫格**。

## R.3 本轮验证

```
npx vue-tsc --noEmit -p tsconfig.json   → exit 0
npx playwright test                     → 18 passed (33.2s)
```

---

# S. 需求方 2026-09-18 第四轮：爱心形状画错了 + 列表要能直接操作

## S.1 ✅ 承认并已修：**我上一版的"爱心"根本不是爱心，是盾形**

需求方原话：「要爱心，你这个是爱心吗」。**他是对的**，那是个盾形。

**根因（几何，不是审美）**：旧画法用"两个圆 + 一个旋转 45° 的方块"拼心形，
但那个**方块比两个圆还大**（对角线撑到 42%×2），并集被方块主导 → 画出来是个**带豁口的盾**。

**为什么我上一轮没发现**（这一段比结论重要）：
我在 **1920 宽、整页（6000+ 像素高）** 的截图上"确认"了它 ——
那种图里 28px 的图标被缩到看不清，我**看成了心形**，于是当成已完成。
第二次改完再截，还是看同一张缩略图，又判断了一次"没生效"，并因此怀疑改动没打到文件。

**两个工具层面的修正（避免同样的事再发生）**：
1. 新增 `web/scripts/icon-lab.mjs`：把候选图形画法**放大到 120px 并排渲染**再截图。
   用它一次就看出：A（旧画法）= 盾形、B（经典"两墓碑"实心）= 完美心形、
   C（两墓碑描边）= 轮廓交叉成 X、**G（实心 + 按心尖缩放 0.7 挖空 + 补 9% 偏心）= 干净的线框爱心**。
2. `shot.mjs` 增加 `SHOT_VIEWPORTS` / `SHOT_FULL=0`：**要看小控件就必须拍"窄视口 + 不整页"**，
   否则细节会被缩放吃掉。教训写进了脚本注释。

**最终实现**（`HyIcon.vue`）：`heartFilled` = 两个"墓碑"形绕**心尖**旋转 ∓45°；
`heartOutline` = 同形状 + 底色按 `scale(.7)` 挖空（`margin-top:-9%` 是手工补的内缩偏心）。
另一个坑也留在注释里：**不能给两个墓碑"描边"** —— 它们在中下部重叠，描边会在心形内部交叉成 X。

## S.2 ✅ 已做：列表卡片上**直接**点赞 / 收藏 / 评论

需求方原话：「为什我在那个页面不能直接点赞什么之类的，一定要点进去才可以呀？」

之前列表卡片底部那三个图标**只是数字展示**，点了没反应。现在：
- **点赞** → `POST/DELETE /api/posts/{id}/like`（幂等），实心红爱心 ↔ 线框爱心
- **收藏** → `POST/DELETE /api/posts/{id}/collect`，实心书签（主色）↔ 线框书签
- **评论** → 进详情页并**直接展开评论区**（带 `?openComments=1`；否则用户点"评论"进去
  看到的却是一个收起的「写评论」，还要再点一次）
- 三个都加了 `@click.stop`，否则点击会冒泡到卡片 → "点了赞又被跳走"
- 计数本地 ±1 乐观更新 + **失败时计数与激活态一起回滚**；列表刷新（`props.post` 换对象）时
  用 `watch` 同步回服务端真值，避免本地数字与真实值分叉
- 未登录 → 直接引导去登录页（列表是用户第一眼看到操作的地方，这里最需要引导）

## S.3 🔴 仍未解决（**契约阻塞，不是我不做**）：列表显示全部图片 / 卡片上复制链接

需求方原话：「我说不要把这个图片堆在一起，你要把它分开来做成九宫格。你这个是九宫格吗？你这只有一个格」，
以及「其实不用点进去，就是链接，你直接挂下边复制一下什么的都行啊」。

**我复核过三次契约（最新 SHA `1C60566B…`）**：

```
PostSummaryVO = [id, boardId, boardName, title, coverUrl, imageCount, viewCount,
                 likeCount, commentCount, collectCount, author, createdAt, isTop, isEssence]
FeedItemVO    = [id, boardId, boardName, title, coverUrl, imageCount, ...]
```

**两个缺口，都在同一个地方**：
1. **没有图片列表** → 列表画不出九宫格（第 2 张起前端没有地址）；
2. **没有 `diskUrl`** → 卡片上做不出"复制链接"（前端不可能知道链接是什么）。

**也不能用"每篇再请求一次详情"绕过**（考虑过并明确否定）：
`GET /api/posts/{id}` 每次都会把**浏览量 +1**（§8.3 走 Redis）。
列表一屏 20 条、每条补一次详情 → **把所有帖子的浏览量都刷爆**。
用一个真实的副作用换一个视觉效果，不能接受。

**所以这条只能靠 L1 加字段。给 L1 的一句话**：

> `PostSummaryVO` 与 `FeedItemVO` 请补：① `imageThumbs: string[]`（**前 3~9 张的缩略图 URL**，
> 列表不需要原图）→ 列表卡片按张数画九宫格；② `diskUrl`（资源帖才有）→ 卡片上直接复制链接。
> 现在只有 `coverUrl` + `imageCount`，这两件事前端都做不了。

**L1 加完，我这边是"十几分钟"量级的改动**（`PostCard` 从一格 + 「N 张」角标换成真网格）。

## S.4 本轮验证

```
npx vue-tsc --noEmit -p tsconfig.json   → exit 0
npx playwright test                     → 18 passed (33.9s)
```

## S.5 补一轮：爱心轮廓改细 + 颜色加深（`762579e`）

需求方看过之后说「爱心还是不对」「字体的颜色可以深一点」。**把图标单独特写 5 倍截图**才看清问题：
不是形状错（形状已经是心的轮廓），而是**轮廓太粗** —— `scale(.7)` 相当于单边 15% 的厚度，
28px 下又粗又笨，心尖还带一小截尖刺。

| 项 | 改前 | 改后 |
|---|---|---|
`heartOutline` 内层缩放 | `scale(.7)` / `margin-top:-9%` | **`scale(.88)` / `margin-top:-2%`** |
列表互动行计数颜色 | `$hy-text-secondary`(#86909c) | **`$hy-text-regular`(#4e5969)** |
列表互动行图标颜色 | 全局 `$hy-icon-color`(#86909c) | **同一次加深**（**只在互动行内覆盖，不动全局变量** —— 那个变量所有页面都在用） |

参数是**在试验台上并排比出来的**（s=.84/.86/.88 × mt=-2/-3/-4/-6/-9 都渲染过）：
`.7` 最粗、`-9%` 尖端有刺、**`.88/-2%` 最干净**。这些结论写进了 `HyIcon.vue` 的注释，
免得后来人凭感觉改这几个数。

**方法上的补强（这轮真正值钱的东西）**：**28px 的图标必须单独放大才能判断对错**。
本机为它栽过两次 —— 先把盾形看成心形，改完又看同一张整页缩略图、甚至以为改动没生效。
所以留了两个工具：
- `scripts/icon-lab.mjs`：把候选画法放大到 120px 并排渲染；
- `scripts/shot.mjs` 新增 `SHOT_SELECTOR` + `SHOT_SCALE`：**元素级高倍截图**（本轮诊断就靠它）。

**如实留下的遗留**：5 倍放大下心尖仍有约 **1 像素**锯齿 —— 纯 CSS 拼合两个形状的固有限制，
28px 实尺寸下不可见。要彻底消除只能换图片资源，而 M2 已定不用 SVG（小程序端支持不可靠）。

**需求方 2026-09-18 结尾表态「要不就这样」—— 版式定稿，前端不再自行调整布局。**

---

# T. M4 第二批：个人主页 / 关注 / 我的收藏 / 首页双流（四个都做，需求方 grill 后定口径）

## T.1 需求方 grill 的六条口径（全部按推荐选项确认）

| # | 问题 | 定论 |
|---|---|---|
1 | 首页「关注/全部」（**来源**）与「最新/热门/精选」（**排序**）语义相撞 | **分两行**：上行选来源，下行选排序 |
2 | 关注按钮放哪里 | **只放详情页作者区 + 个人主页**（只有 `UserProfileVO` 有 `isFollowing`；列表项的 author 是 `UserBriefVO`，放上去等于重演 CR-K） |
3 | `gender` / `level` 契约没给取值含义 | **先不显示**（详见 T.4 的 CR） |
4 | 我的收藏卡片样式（`CollectionItemVO` **无作者字段**） | **复用帖子卡片视觉、去掉作者行** |
5 | 个人主页入口 | 详情页作者区、评论作者、关注/粉丝列表**都可点** |
6 | 未登录看「关注」流 / 我的收藏 | **显示登录引导、不发请求** |

## T.2 做了什么

| 文件 | 内容 |
|---|---|
| `api/users.ts`（新） | 资料 / 帖子 / 评论 / 关注 / 粉丝 / 我的收藏 / 信息流，共 7 个接口 |
| `api/contract.ts` | 已登记（上一批完成） |
| `utils/postView.ts` | 新增 `ProfileView` / `FollowUserView` / `CollectionView` / `UserCommentView` 四个归一化；`toPostCard` 放宽为同时接受 `PostSummaryVO \| FeedItemVO`（两者字段集一致） |
| `pages/user/index.vue`（新） | 资料卡 + 四个 Tab（各自独立分页、**切换保留已加载数据**，避免来回点重复请求） |
| `pages/collect/index.vue`（**替换占位页**） | 我的收藏列表 + 取消收藏（就地移除、失败插回**原位**而不是重拉） |
| `pages/index/index.vue` | 来源条（关注/全部）+ 排序条；关注流未登录只给引导 |
| `pages/post/detail.vue` | 作者区整块可点进主页 + 关注按钮（**额外拉一次作者资料拿关注态**，见 T.3） |
| `components/CommentSection.vue` | 评论作者头像/昵称可点进主页 |
| `pages.json` | 注册 `pages/user/index` |
| `e2e/m4-profile.spec.ts`（新） | 4 条用例，见 T.5 |

## T.3 两条设计上的取舍（都不是随手写的）

1. **详情页的关注按钮要额外拉一次 `/api/users/{authorId}`**
   因为 `PostDetailVO.author` 是 `UserBriefVO`，**没有 `isFollowing`**。
   不拉的话按钮只能盲猜，点完刷新又变回未关注 —— 那是个**骗人的按钮**。
   所以：**已登录才拉**（未登录时点按钮就是引导登录，状态无所谓），**失败静默**
   （关注态拿不到不该影响正文阅读，按钮直接不显示）。
2. **关注流与全部流走不同端点**
   - 全部流 → `GET /api/posts`（支持 `latest/hot/essence` 三档，且 `total` 要给左栏「今日数据」）；
   - 关注流 → `GET /api/feed?type=follow`（只有它知道"我关注了谁"）。
   不把全部流也换成 `/api/feed`：那会丢掉 `essence` 这档（文档只定义了 `type`，没规定 feed 支持哪些排序），
   也会让左栏的总数来源变含糊。**关注流下不显示"精选"**，切换来源时若原来停在"精选"会顺手退回"最新"，
   否则会出现"选中了界面上根本不存在的排序"。

## T.4 🔴 CR-M：`UserProfileVO.gender` / `level` 没有取值含义

契约里这两个是**裸 integer，没有 enum、没有 description**（对比 `PostSort` 至少在文档里有取值）。
前端猜不出编码（0 是"未知"还是"男"？`level` 从 1 还是 0 起？），**猜错就是在界面上写错**。
所以本批**刻意不显示**它们，也不在 `ProfileView` 里建模（避免将来有人顺手渲染出来）。

**给 L1 的一句话**：`UserProfileVO.gender` 与 `level` 请补上取值说明（或改成 enum），
否则前端只能一直不显示这两个字段。

（另：CR-L 仍阻塞「列表画九宫格」与「卡片上复制链接」—— 需要 `PostSummaryVO`/`FeedItemVO`
补 `imageThumbs[]` 与 `diskUrl`，见 §S.3。）

## T.5 一个**真 bug**（E2E 抓到的）与**三个测试侧的坑**（都写进了测试注释）

**真 bug**：刷新后 `auth.user` 只有 localStorage 里那份、且我的页面没触发补齐，
于是 `isSelf` 判不出来 → **自己的主页也显示了"关注"按钮**。
修法：个人主页与详情页在 `onLoad` 时 `void auth.ensureProfile()`（已缓存则不发请求）。
> 这条正是"关注态必须来自服务端"的价值：如果当初图省事只用内存记，这个 bug 根本不会被发现。

**测试侧的坑（全部是本机真实假失败，值得后来人抄走）**：
1. **乐观更新 + 立刻断言服务端 = 竞态**。取消收藏后卡片是**乐观移除**的，我紧接着就去查接口，
   而 DELETE 还在飞 —— 断言拿到旧值；测试结束时浏览器一关，请求被中断，
   **还在库里留下脏数据**（三次运行各剩一行 `post_collect`，我事后清掉了并把 `collect_count` 重新同步）。
   正解：`page.waitForResponse` 把**请求本身**也纳入等待。
2. **uni-app 同一路由只换 query 不会重跑 `onLoad`**。`/pages/user/index?id=A` → `?id=B` 时页面
   还停在 A 的主页，于是"自己的主页有关注按钮"这条断言假失败。正解：`page.reload()`。
   （M3 的发帖/详情用例踩过同一个坑。）
3. **断言写死"变成 0"是错的**：收藏数是**全站共享**的，别的用例会故意留下收藏。
   这条断言**单独跑通过、全量跑失败** —— 正是最容易被当成"抖动"忽略的那种假失败。
   正解：断言"**恰好减少 1**"。

## T.6 验证

```
npx vue-tsc --noEmit -p tsconfig.json   → exit 0
npx playwright test                     → 22 passed (41.5s)
    M2 认证 5 + M3 七页 7 + 最小闭环 1 + M4 互动 5 + M4 个人主页批次 4
```

**演示库清理**：删掉 3 行测试残留的 `post_collect` 并重新同步了 `post.collect_count`。

---

# U. 验证方式的调整（需求方 2026-09-18 指定）

**需求方原话**：「你不要每次都发图片测试了。如果崩了，我自己来测试」。

## U.1 从现在起**不做**的事

**不再用截图自证版式**。已停用：
- `scripts/shot.mjs`（页面/元素截图）
- `scripts/icon-lab.mjs`（图标候选放大对比）
- `scripts/img-probe.mjs`（图片加载探针）
- `scripts/refsite-probe.mjs`（参考站探针）

这些脚本**保留在仓库里**（哪天需要一张图，一条命令就能出），但**不再进入我的常规验证流程**。

> 为什么这条值得记下来：我此前把"截图确认"当成版式改动的**默认**验证手段，
> 于是每一轮都要跑一次浏览器、看一张图、再改 —— **又慢又占篇幅，而且判断权本来该在需求方手里**
> （我自己看图的判断还错过两次：把盾形看成心形、把 28px 图标看成"没生效"）。
> 版式这类事**由需求方看**才是对的；我该保证的是**功能不崩**。

## U.2 仍然保留的验证（以及为什么）

| 验证 | 保留 | 理由 |
|---|---|---|
`npx vue-tsc --noEmit` | ✅ | 秒级、无噪音，能挡住"类型/引用改坏"这类真错误 |
`npx playwright test`（22 条功能用例） | ✅ | 这是 `docs/testing/README.md` §2 要求的**「红 → 绿」交付证据**，验的是**功能**（不是版式）：接口是否被调用、状态是否正确、落库是否真实 |

⚠️ 若需求方认为 E2E 也不必要（"崩了我自己测"），说一声我就只留 `vue-tsc` —— 但**交付报告里的「红 → 绿」证据会随之缺失**，这一条需要 L1 认可（它是项目测试纪律的一部分，不是我的习惯）。

---
