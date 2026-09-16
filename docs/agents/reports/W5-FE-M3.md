# W5-FE-M3 交付报告（前端接版块 / 列表 / 搜索 / 详情 / 发帖 / OSS 直传上传）

> 任务书：[`../tasks/W5-FE-M3-前端接版块与帖子与上传.md`](../tasks/W5-FE-M3-前端接版块与帖子与上传.md)
> 角色：**前端 L2 实现者** · 日期：2026-09-16
> 写权：`web/**` + 本文件（本文件是 `docs/` 下唯一被改动的文件）

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
openapi.json                     = 14 路径 / 26 schema / SHA256 910F99C8…
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

## 10. 变更记录

| 版本 | 日期 | 说明 |
|---|---|---|
| v1.0 | 2026-09-16 | 首版。§6.2 的 1–4、6 页完成并验证；第 5 页缺图片上传（CR-F 于本轮中途由 L1 解除，CR-G/CR-H 待裁）；§6.1 最小闭环 **未达成**，逐条写明原因 |
