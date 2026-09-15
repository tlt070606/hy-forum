# lw 长期记忆 · Hy论坛前端（uni-app）

> 本文件记录**理解偏差、踩坑、项目理解、约定与命令**，供后续会话快速恢复上下文。
> 只写"下次会用到"的东西，不重复 `README.md` 已写清的常规说明。
> 最后更新：2026-09-15

---

## 一、理解偏差（动手前我差点搞错的事）

| # | 我原本以为 | 实际是 | 怎么发现的 |
|---|---|---|---|
| 1 | 前端要写进后端仓库 `D:\demo\Hy论坛\web\**`（看板 §2 这么规定） | 需求方给了**平级独立目录** `D:\demo\Hy论坛_uni-app`，且我的文件写权只覆盖后者 → 按独立仓库做 | 起手探测工作区发现是空目录且非 git 仓库，故提问澄清 |
| 2 | `@dcloudio/vite-plugin-uni` 的 `latest`（2.0.2）就是 Vue3 版本 | **`latest` 是 Vue2 线**（peer: Vite 2 + Vue 3.1-beta）。Vue3 线版本号形如 `3.0.0-5020420260813003` | `npm view` 查 peerDependencies 时发现 vite ^2.3.0，明显不对 |
| 3 | 装了 `openapi-typescript` 就能直接生成类型 | 能，但契约里 `captcha` / `register-mode` 的 `data` 是 `Record<string, any>`，**没有静态字段名** → 生成物拿不到字段，必须人工窄化 | 读 openapi.json 发现两者都是 `ApiResponseMapStringObject` |
| 4 | 后端应该有 CORS 配置（前后端分离的常规做法） | **没有任何 CORS 头，预检 OPTIONS 返回 403** | E2E 全线失败后查响应头定位到 |
| 5 | `uni.showModal` 的按钮文案由我传的 `confirmText` 决定 | H5 端**不读该参数**，按浏览器 locale 生成（本机英文 → `Cancel`/`OK`） | E2E 快照里看到英文按钮 |
| 6 | 既然是"前端任务"，改动范围只在工程内 | 对，但**契约不足时必须提 CR 而不是自己编**（铁律）；后端 8 个接口之外的页面只能做骨架 | 逐个探测 M3–M5 端点，全部 404 |

---

## 二、踩坑清单（按"坑的隐蔽程度"排序）

### 2.1 三个"看起来像别的问题"的沙箱/环境坑

1. **npm EPERM 写缓存**
   现象：`npm install` 报 `EPERM: operation not permitted, open 'C:\Users\...\npm-cache\_cacache\tmp\***'`
   看起来像：盘满 / 杀毒软件锁文件 / 权限不足
   实际是：**agent 文件沙箱只允许写工作区**，而 npm 缓存目录在用户目录下。
   解法：`.npmrc` 里 `cache=.npm-cache`（重定向到工作区内）。

2. **`spawn EPERM`（npm install 与所有构建）**
   现象：`npm error spawn EPERM` / `failed to load config from vite.config.ts ... spawn EPERM`
   实际是：受限沙箱下子进程不能用管道 stdio（npm postinstall、esbuild、rollup、Playwright 都需要）。
   解法：这些命令需**提权执行**。已提权后一切正常。

3. **Node 自带子进程同样被拒**
   现象：`spawnSync D:\develop\NODEJS\node.exe EPERM`，`e.stdout` 是 `undefined`
   后果：会**误判**成"被测规则失效"。我第一次写的变异测试就是这样假报的。
   解法：测试脚本改为**同进程 import 调用**，不 spawn。

### 2.2 一个把"构建通过"变成"构建失败"的自伤

4. **不要给 `package.json` 加 `"type": "module"`**
   ```txt
   failed to load config from vite.config.ts
   uni is not a function
   ```
   根因：`"type": "module"` 改变 Vite 加载 `vite.config.ts` 时的 CJS/ESM 互操作方式，
   `@dcloudio/vite-plugin-uni` 的默认导出被解析成模块命名空间对象而非函数。
   官方模板也不设该字段。需要 ESM 就用显式 `.mjs`。

5. **`package.json` 不能写注释**（它是纯 JSON）。我一开始想加说明注释，工具会解析失败。

6. **PowerShell 的 `Set-Content -Encoding UTF8` 会写 BOM**
   我批量替换 `@import` → `@use` 时中招，污染了 3 个文件（`App.vue`、`DocPage.vue`、E2E spec）。
   **结论：本项目里改文件一律用文件编辑工具，不用 PowerShell 写文件。**
   （后端仓库的同类坑：`.ps1` 必须带 BOM，`.md`/`.sql` 不要带 → 需求相反，别混。）

7. **`fetch` 留下 keep-alive socket 时调 `process.exit()` 会崩**
   ```txt
   Assertion failed: !(handle->flags & UV_HANDLE_CLOSING), file src\win\async.c, line 94
   ```
   退出码变成崩溃码（`-1073740791`），把"校验通过"变成"看起来崩了"。
   解法：用 `process.exitCode = N`，让事件循环自然收尾。

### 2.3 前端/uni-app 特有的坑

8. **Playwright 开 `video` 会导致"所有用例全挂"**
   缺 ffmpeg 时每个用例都在 `newPage` 阶段失败，报 `Executable doesn't exist ... ffmpeg`
   —— 看起来像应用坏了，实际与业务无关。截图够用，不开录像。

9. **`getByTestId` 命中的是 `<uni-input>` 外壳，不是真 `<input>`**
   ```txt
   Error: Element is not an <input>, <textarea>, <select> or [contenteditable]
   ```
   这个报错极易被误读成"testid 没透传"或"页面没渲染"，其实两者都正常。
   解法：`page.getByTestId(id).locator('input')` 下钻（已封装为 `uniInput()`）。

10. **H5 端 `uni.request` 用绝对地址 = 撞 CORS；相对地址才是对的**
    正确形态：H5 基地址为**空字符串（同源相对）**，由 Vite 代理或 Nginx 转发；
    小程序/App **必须绝对地址**（不支持相对路径）。用 `#ifdef H5` 区分。

11. **H5 的 `#ifdef H5` 分支后必须有 `return`，否则代码会继续往下走**
    条件编译是**文本替换**，不是运行时判断 —— 这点和普通 `if` 完全不同，容易写错。

12. **`vite preview` 是独立的 server 实例，`server.proxy` 不作用于它**
    必须单独配 `preview.proxy`，否则 E2E（跑在产物上）会因跨源全线失败。
    失败现象很隐蔽：页面显示「当前暂未开放注册」——那是 `register-mode` 请求
    被拦后走了兜底分支，看起来像业务逻辑错。

13. **E2E 上报的失败可能指向"另一个用例"的根因**
    我遇到的现象：注册用例超时（`waitForResponse` 60s），但真实原因是 CORS 拦掉了
    `register-mode`，导致页面渲染了"暂未开放注册"遮罩 → 验证码请求根本没发出。
    **教训：先看 Page snapshot，再看错误行。**

---

## 三、项目理解（关键事实，避免重复探查）

### 3.1 契约面（本前端唯一能真跑的范围）

- 后端**只有 8 个路径**：`register` / `login` / `logout` / `captcha` / `register-mode`
  / `user/me` / `admin/login` / `admin/logout`
- `openapi.json` SHA256 `F641BBF5…5370`（前端快照与后端**逐字一致**）
- M3–M5 的端点**实测全部 404**：`/api/boards`、`/api/posts`、`/api/notifications`、
  `/api/user/collections`、`/api/users/{id}`
- 契约里两个**形状缺口**：`captcha` 与 `register-mode` 的 `data` 是 `Record<string, any>`
  → 必须人工窄化 + **运行时校验**（前端拿到 undefined 时小程序端甚至不报错，只是不显示）

### 3.2 关键业务约定

- **注册成功不返回 token** → 前端必须引导去登录，不能"注册即登录"
- **验证码是一次性的**（Redis TTL 300s，提交即消费）→ 注册失败后**必须刷新验证码**，
  否则用户怎么输都报"验证码错误"
- **登录不需要验证码**（只有注册需要）
- 401 才清登录态；**403 不清**（它是"已登录但无权限"，清了会把用户无故踢下线）
- **退出登录无论服务端成败都必须清本地**，否则用户会处于"看着已登录、请求全 401"的坏状态

### 3.3 环境事实

| 项 | 值 |
|---|---|
| 后端 | `http://127.0.0.1:8080`（live，无 CORS） |
| MySQL | `127.0.0.1:3306` 可用 |
| Redis | `192.168.100.128:6380`（VM 内 `hy-redis`）—— E2E 直读验证码答案靠它 |
| Node / npm | v26.4.0 / 11.17.0（**Node 26 与 uni-app 工具链实测兼容**） |
| 微信开发者工具 | `D:\Program Files\微信web开发者工具\`（`cli.bat` 存在） |
| 本机浏览器 | Chrome 与 Edge 均已安装 → Playwright 用 `channel: 'chrome'`，**不下载 Chromium** |
| 小程序产物 | `appid: "touristappid"`（游客模式，**无需 AppID**）、`urlCheck: false` |

---

## 四、常用命令（本项目）

```bash
# 依赖（本工程 .npmrc 已把缓存重定向到工作区内）
npm install

# 开发 / 构建
npm run dev:h5
npm run build:h5          # → dist/build/h5
npm run build:mp-weixin   # → dist/build/mp-weixin
npm run build:app

# 契约一致性（最重要的一道防线）
npm run check:contract                  # 对比 http://127.0.0.1:8080/v3/api-docs
node scripts/test-contract-drift.mjs    # 变异测试：证明检测规则真的在跑

# E2E（跑构建产物，不是 dev server）
npm run build:h5 && npm run e2e
node e2e/probe-captcha.ts               # 单独验证"能读到验证码答案"这一前提

# 类型生成（改契约后）
npm run gen:api
```

⚠️ 除 `node` 脚本外，`npm run build:*` 与 `npx playwright test` 在本机沙箱下**需要提权**。

---

## 五、代码风格与约定（本项目）

- **注释写"为什么"**：本仓库注释密度高是有意的 —— 每条反直觉的写法都注明根因与实测现象，
  避免后人"顺手改回去"。写新代码请沿用这个习惯。
- **平台差异只允许出现在 `src/utils/`**，页面里不得出现 `#ifdef`（ADR-0011 要求）。
- **不引 Vant**（小程序无 DOM）；UI 用 `wot-design-uni`，走 `easycom` 自动引入。
- **契约生成的类型禁止手改**（`src/api/generated/**`）；业务代码从 `src/api/types.ts` 导入类型。
- **契约不足时提 CR**，不自己编接口形状；未交付的接口对应页面只做骨架，
  点按明确提示"接口未交付"，**不用假数据冒充完成**。
- **测试要能证明自己不是假绿**：新增校验规则时，配一条变异测试（照抄
  `scripts/test-contract-drift.mjs` 的思路）。
- 提交信息用 Conventional Commits；`main` 保持可发布。

---

## 六、待办 / 已知问题（交给后续）

1. **上线前必须回填 `.env.production` 的 `VITE_API_BASE_URL`**（已备案 https 域名）。
   生产构建下非 H5 端若未配置，`getApiBaseUrl()` 会**在启动时抛出明确错误**（有意的快速失败）。
2. **`uni.showModal` 的英文按钮**（英文 locale 下显示 `Cancel`/`OK`）。
   要修需改用自绘弹窗（`wot-design-uni` 的 `wd-message-box`）。属体验改进。
3. **三份法律文本是结构模板**，需需求方确认后替换（页面已用醒目横幅标明"尚未定稿"）。
4. **小程序端界面未做人工验证**（需在微信开发者工具中打开
   `dist/build/mp-weixin`）。这是任务书 §5.5 的手工项，**不能靠"命令跑通"顶替**。
5. **M3–M6 页面**（版块/帖子/评论/通知/个人主页）待后端交付接口后实现。
6. `src/api/contract.ts` 的 `OPENAPI_SNAPSHOT_SHA256` 目前是**信息性常量**，
   未参与自动化校验（实际比对以在线后端为准，见 `scripts/check-contract.mjs`）。
   若要让它成为硬约束，需接入 CI。
