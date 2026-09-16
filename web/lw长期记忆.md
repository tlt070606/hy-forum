# lw 长期记忆 · Hy论坛前端（uni-app）

> ## ⚠️ 本文件的写法定死了（2026-09-15 需求方决定）
>
> **只允许记两类东西：① 实测踩过的坑；② 本工程自己的约定与命令。**
> **不得写任何"项目事实"** —— 接口形状、表结构、里程碑、环境事实、契约版本，
> 一律**给指针**，不要在这里复述。
>
> **为什么**：`docs/` 是项目的唯一事实来源（`AGENTS.md` 铁律 1）。
> 本文件曾经复述过项目事实，结果**两处都错了**：
> ① 小程序 appid 写的值**与实际构建产物里的不一致**；
> ② "常用命令"一节里**没有 `type-check`**，于是它从来没被跑过 ——
> 12 个类型错误活了整整一天，还带走两个用户可见的 bug（"关闭注册"功能完全失效）。
> **副本必然漂移，而漂移的副本比没有副本更危险。**
> 现在这类事实一律**去源头查**（见下面第一节），本文件不再复述。
>
> 最后更新：2026-09-15

---

## 一、事实去哪查（不要在这里找）

| 要找什么 | 去哪 |
|---|---|
| 接口契约（**唯一事实来源**） | 仓库根 [`../openapi.json`](../openapi.json) |
| 接口的字段级说明、错误码表 | [`../docs/技术方案.md`](../docs/技术方案.md) §6／§6.1 |
| 表结构 | [`../docs/db/schema.sql`](../docs/db/schema.sql) |
| 里程碑与验收标准 | [`../docs/PLAN.md`](../docs/PLAN.md) §4 |
| **协作规则、广播区、谁在改什么** | [`../docs/agents/工作计划.md`](../docs/agents/工作计划.md) §4 |
| 环境事实（JDK/MySQL/Redis 等） | [`../docs/agents/README.md`](../docs/agents/README.md) §7.2 |
| 前端任务的派发方式 | [`../docs/agents/前端Agent开工说明.md`](../docs/agents/前端Agent开工说明.md) |

---

## 二、理解偏差（动手前差点搞错的事）

| # | 原本以为 | 实际是 | 怎么发现的 |
|---|---|---|---|
| 1 | `@dcloudio/vite-plugin-uni` 的 `latest`（2.0.2）就是 Vue3 版本 | **`latest` 是 Vue2 线**（peer: Vite 2 + Vue 3.1-beta）。Vue3 线版本号形如 `3.0.0-5020420260813003` | `npm view` 查 peerDependencies 时看到 vite ^2.3.0，明显不对 |
| 2 | 装了 `openapi-typescript` 就能直接生成类型 | 能，但契约里 `captcha` / `register-mode` 的 `data` 是 `Record<string, any>`，**没有静态字段名** → 生成物拿不到字段，必须人工窄化 | 读 `openapi.json` 发现两者都是 `ApiResponseMapStringObject` |
| 3 | 后端应该有 CORS 配置（前后端分离的常规做法） | **没有任何 CORS 头，预检 OPTIONS 返回 403** | E2E 全线失败后查响应头定位到 |
| 4 | `uni.showModal` 的按钮文案由传入的 `confirmText` 决定 | H5 端**不读该参数**，按浏览器 locale 生成（本机英文 → `Cancel`/`OK`） | E2E 快照里看到英文按钮 |
| 5 | "前端任务"的改动范围只在工程内 | 对，但**契约不足时必须提 CR 而不是自己编** | 逐个探测未交付端点，全部 404 |

---

## 三、踩坑清单（按"坑的隐蔽程度"排序）

### 3.1 三个"看起来像别的问题"的沙箱/环境坑

1. **npm EPERM 写缓存**
   现象：`npm install` 报 `EPERM: operation not permitted, open 'C:\Users\...\npm-cache\_cacache\tmp\***'`
   看起来像：盘满 / 杀毒锁文件 / 权限不足
   实际是：**受限沙箱只允许写工作区**，而 npm 缓存目录在用户目录下。
   解法：`.npmrc` 里 `cache=.npm-cache`（重定向到工作区内）。

2. **`spawn EPERM`（npm install 与所有构建）**
   现象：`npm error spawn EPERM` / `failed to load config from vite.config.ts ... spawn EPERM`
   实际是：受限沙箱下子进程不能用**管道 stdio**（npm postinstall、esbuild、rollup、Playwright 都需要）。
   解法：这些命令需**提权执行**；`stdio: 'inherit'` 的 spawn 则不受影响。

3. **Node 同进程 spawnSync 同样被拒**
   现象：`spawnSync ... EPERM`，且 `e.stdout` 是 `undefined`
   后果：会**误判**成"被测规则失效"。第一次写的变异测试就是这样假报的。
   解法：测试脚本改为**同进程 import 调用**，不 spawn。

### 3.2 把"构建通过"变成"构建失败"的自伤

4. **不要给 `package.json` 加 `"type": "module"`**
   ```txt
   failed to load config from vite.config.ts
   uni is not a function
   ```
   根因：它改变 Vite 加载 `vite.config.ts` 时的 CJS/ESM 互操作方式，
   `@dcloudio/vite-plugin-uni` 的默认导出被解析成模块命名空间对象而非函数。
   官方模板也不设该字段。需要 ESM 就用显式 `.mjs`。

5. **`package.json` 不能写注释**（纯 JSON，解析会失败）。

6. **PowerShell 的 `Set-Content -Encoding UTF8` 会写 BOM**
   批量替换 `@import` → `@use` 时中招，污染了 3 个文件。
   **结论：本工程改文件一律用文件编辑工具，不用 PowerShell 写文件。**
   （**注意方向相反**：后端仓库的 `.ps1` **必须**带 BOM、`.md`/`.sql` **不要** —— 别混。）

7. **`fetch` 留下 keep-alive socket 时调 `process.exit()` 会崩**
   ```txt
   Assertion failed: !(handle->flags & UV_HANDLE_CLOSING), file src\win\async.c, line 94
   ```
   退出码变成崩溃码（`-1073740791`），把"校验通过"变成"看起来崩了"。
   解法：用 `process.exitCode = N`，让事件循环自然收尾。

### 3.3 前端 / uni-app 特有的坑

8. **Playwright 开 `video` 会导致"所有用例全挂"**
   缺 ffmpeg 时每个用例都在 `newPage` 阶段失败。截图够用，**不开录像**。

9. **`getByTestId` 命中的是 `<uni-input>` 外壳，不是真 `<input>`**
   ```txt
   Error: Element is not an <input>, <textarea>, <select> or [contenteditable]
   ```
   极易被误读成"testid 没透传"或"页面没渲染"，其实两者都正常。
   解法：`page.getByTestId(id).locator('input')` 下钻（已封装为 `uniInput()`）。

10. **H5 端 `uni.request` 用绝对地址 = 撞 CORS；相对地址才对**
    正确形态：H5 基地址为**空字符串（同源相对）**，由 Vite 代理或 Nginx 转发；
    小程序 / App **必须绝对地址**（不支持相对路径）。用 `#ifdef H5` 区分。

11. **`#ifdef H5` 分支后必须有 `return`**
    条件编译是**文本替换**，不是运行时判断 —— 与普通 `if` 完全不同，容易写错。

12. **`vite preview` 是独立的 server 实例，`server.proxy` 不作用于它**
    必须单独配 `preview.proxy`，否则 E2E（跑在产物上）会因跨源全线失败。
    失败现象很隐蔽：页面显示「当前暂未开放注册」——那是 `register-mode` 请求
    被拦后走了兜底分支，**看起来像业务逻辑错**。

13. **E2E 上报的失败可能指向"另一个用例"的根因**
    现象：注册用例超时（`waitForResponse` 60s），真实原因是 CORS 拦掉了
    `register-mode` → 页面渲染"暂未开放注册"遮罩 → **验证码请求根本没发出**。
    **教训：先看 Page snapshot，再看错误行。**

14. **`tsconfig` 的 `types` 是"替代"默认引入，不是"追加"**
    写了 `types: ["@dcloudio/types"]` 之后，`@types/node` 与 `vite/client` **都不会进来**
    → `process` 报 `TS2591`、`import.meta.env` 报 `TS2339`。
    正确做法：**拆两个 tsconfig**（app 侧 / node 侧），两侧都跑。
    ⚠️ **不许把文件从 `include` 里删掉来"消错"** —— 那是把检查关掉，不是修好。
    拆成两个是"文件仍被检查，只是用合适的类型环境"，两者区别就是
    「检查在跑」与「检查看起来在跑」。

15. **小程序端本地调试必须用 `dev` 产物；`build` 产物必然启动即报错**
    现象：界面出来了，但一行红字
    `无法获取注册状态：[env] 生产构建要求 https，当前为 "http:"`，
    而且**一个网络请求都没发出**（Network 面板是空的）。
    看起来像：微信的"不在 request 合法域名"校验 → **不是**。
    实际是：**我们自己的守卫**（`src/utils/env.ts` 第 167 行 `IS_PROD && protocol !== 'https:'`）。
    `uni build` **对任何平台都按 production 运行** → `import.meta.env.PROD === true` →
    本地 `http://127.0.0.1:8080` 被拒。
    **怎么区分是谁报的**：微信的提示里有"合法域名"字样；这条里有 `ADR-0011 C2`（本项目文档编号）。
    解法：**用 dev 模式**
    ```bash
    npm run dev:mp-weixin     # → dist/dev/mp-weixin，常驻 + 热更新
    ```
    然后开发者工具打开 `dist/dev/mp-weixin`，**不要打开 `dist/build/mp-weixin`**。
    ⛔ **不要"把那个判断注释掉"**（网上会给这个建议）：那条守卫拦的正是
    "打包出指向 http 的正式产物"这个真实事故 —— 小程序正式版连不上、安卓拒明文。
    注释掉它 = 把响亮的启动失败换成**静默不能用的应用**。
    （完整说明与验收步骤见 [`小程序端人工验证.md`](小程序端人工验证.md) §0.1。）

---

## 四、本前端的实现约定（这些是工程决策，不是项目事实）

- **注册成功不返回 token** → 前端必须引导去登录，**不能"注册即登录"**。
- **验证码是一次性的**（提交即消费）→ 注册失败后**必须刷新验证码**，
  否则用户怎么输都报"验证码错误"。
- **登录不需要验证码**（只有注册需要）。
- **401 才清登录态；403 不清** —— 403 是"已登录但无权限"，清了会把用户无故踢下线。
- **退出登录无论服务端成败都必须清本地** —— 否则用户会处于"看着已登录、请求全 401"的坏状态。
- **查询失败 ≠ 业务状态**：后端查询类接口失败时**不许猜一个默认值当成业务结果**
  （见 `api/auth.ts` 的 `RegisterModeResult`：`{ok:true,mode}` 与 `{ok:false,error}` 必须分开处理）。
  这条是 2026-09-15 修 bug 时定的 —— 曾经把失败静默当成 `closed`，界面显示
  "当前暂未开放注册"，**把环境故障伪装成了业务状态**，排查成本极高。

### 代码风格

- **注释写"为什么"**：注释密度高是有意的 —— 每条反直觉的写法都注明根因与实测现象，
  避免后人"顺手改回去"。写新代码请沿用。
- **平台差异只允许出现在 `src/utils/`**，页面里不得出现 `#ifdef`。
- **UI 用 `wot-design-uni`**（走 `easycom` 自动引入）；**不引 Vant**（小程序无 DOM）。
- **契约生成的类型禁止手改**（`src/api/generated/**`）；业务代码从 `src/api/types.ts` 导入类型。
- **契约不足时提 CR**，不自己编接口形状；未交付接口对应的页面只做骨架，
  明示"接口未交付"，**不用假数据冒充完成**。
- **新增校验规则时配一条变异测试**（照抄 `scripts/test-contract-drift.mjs` 的思路）：
  **一个从未失败过的检查，不能证明它在检查任何东西。**

---

## 五、常用命令

```bash
npm install                                     # .npmrc 已把缓存重定向到工程内

npm run dev:h5
npm run dev:mp-weixin     # 小程序**本地调试只能用这个** → dist/dev/mp-weixin
npm run build:h5          # → dist/build/h5
npm run build:mp-weixin   # ⚠️ production 模式，产物本地跑不起来（见 §3.3 第 15 条）
npm run build:app         # 同上

# 类型检查（两侧一起；改任何类型相关的东西都必须跑）
npm run type-check
#   ⚠️ 它用 && 串联两侧 → app 侧一失败，node 侧整段不跑（已登记 H11）。
#   需要单独看某一侧时：
npx vue-tsc --noEmit -p tsconfig.json          # app 侧（src/**、global.d.ts）
npx tsc     --noEmit -p tsconfig.node.json     # node 侧（vite/playwright/e2e）

# 契约一致性（重要防线之一）
npm run check:contract                  # 对比仓库根 openapi.json 与前端快照
node scripts/test-contract-drift.mjs    # 变异测试：证明检测规则真的在跑

# E2E（跑构建产物，不是 dev server）
npm run build:h5 && npm run e2e
node e2e/probe-captcha.ts               # 单独验证"能读到验证码答案"这一前提

# 类型生成（契约变更后）
npm run gen:api
```

⚠️ 除 `node` 脚本外，`npm run build:*` 与 `npx playwright test` 在本机沙箱下**需要提权**。

---

## 六、待办 / 已知问题（只记还没解决的）

| # | 事项 | 说明 |
|---|---|---|
| 1 | **上线前必须回填 `.env.production` 的 `VITE_API_BASE_URL`** | 非 H5 端未配置时 `getApiBaseUrl()` **在启动时抛明确错误**（有意的快速失败）。域名未备案前不填 —— 填假地址会让"看起来能上线"的错觉留到打包那天 |
| 2 | **`uni.showModal` 在英文 locale 下按钮是英文** | 要修需改用自绘弹窗（`wot-design-uni` 的 `wd-message-box`）。属体验改进 |
| 3 | **三份法律文本是结构模板** | 需需求方确认后替换（页面已用醒目横幅标明"尚未定稿"） |
| 4 | **小程序端界面未做人工验证** | 需在微信开发者工具里逐条点验。**清单在 [`小程序端人工验证.md`](小程序端人工验证.md)** —— "命令跑通"不能顶替它。当前**暂挂**（需求方决定先打通网站端） |
| 5 | **契约快照该退役了** | `src/api/generated/openapi.snapshot.json` 是**合并进同一仓库之前**为跨仓库而留的副本。现在契约只有仓库根一份，它属于重复。退役方案见 [`../docs/agents/M3-计划与前置.md`](../docs/agents/M3-计划与前置.md) §5.1 |
| 6 | **`H11`：`type-check` 的 `&&` 短路** | 见 [`../docs/agents/工作计划.md`](../docs/agents/工作计划.md) §6.5。它不会假绿，但一次运行看不到全部错误 |
| 7 | **`captcha` / `register-mode` 的形状缺口** | 契约里是 `Record<string, any>`。已登记 **CR-005**，并入 M3 修。修完本工程的人工窄化 + 运行时校验那层临时设施才能拆 |
