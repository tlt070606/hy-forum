# Hy论坛 · 前端（uni-app）

Hy论坛的**唯一前端**：一套代码编译三端 —— **H5 网站** / **安卓 App** / **微信小程序**。

> 微信小程序端**仅用于练习、不上线**。三条硬约束（个人主体不支持 `web-view`、
> `request` 只允许已备案 HTTPS 域名、个人主体类目不含论坛社区）决定了它难以正式发布，
> 需求方已知并接受。**不要把它当"将来能上线的产品"投入精力。**
> 详见后端仓库 `docs/adr/0011`。

---

## 1. 这个仓库是什么 / 不是什么

| | 说明 |
|---|---|
| **是** | 独立的 uni-app 前端工程（独立 git 仓库），从零创建 |
| **是** | 后端契约的**消费方**：接口类型由 `openapi.json` 快照生成 |
| **不是** | 后端代码的所在处 —— 后端在平级目录 `../Hy论坛`，本工程**不改后端一行** |
| **不是** | 管理后台 —— 管理后台是**另一个独立前端工程**（后端仓库 `admin/**`） |

**权威事实来源**（按优先级）：

1. 后端仓库 `docs/PLAN.md`、`docs/技术方案.md`、`docs/adr/**`
2. 后端仓库根 `openapi.json`（由后端注解导出、由契约所有者冻结，**任何人不手工编辑**）
3. 本工程 `src/api/contract.ts`（接口登记 + 契约快照元信息）

⚠️ 本文件（README）**不承载契约**。接口字段一律以 `openapi.json` 与生成类型为准，
不要在这里抄一遍 —— 抄了就会漂移。

---

## 2. 快速开始

```bash
# 1) 安装依赖
npm install

# 2) 起 H5 开发服务器（默认 http://localhost:5173）
npm run dev:h5

# 3) 本地调试小程序端 —— **只能用 dev**，用 build 产物会启动即报错
npm run dev:mp-weixin      # → dist/dev/mp-weixin，常驻 + 热更新
#    然后微信开发者工具打开 dist/dev/mp-weixin（**不要**打开 dist/build/mp-weixin）
#    原因见 小程序端人工验证.md §0.1

# 4) 构建产物（production 模式；H5 可直接用，小程序/App 只用于将来上线）
npm run build:h5           # → dist/build/h5
npm run build:mp-weixin    # ⚠️ production：若地址是 http 会启动即抛错
npm run build:app          #    本地调试请用上面的 dev:mp-weixin
```

**前置条件**：后端需运行在 `http://127.0.0.1:8080`（地址在 `.env.development` 配置）。
后端不在时页面能打开，但所有接口调用会明确失败 —— 这是预期行为，不会静默降级成假数据。

---

## 3. 必须知道的坑（不读会浪费很多时间）

### 3.1 ⚠️ 不要给 `package.json` 加 `"type": "module"`

加上之后 `npm run build:h5` 会失败，报：

```
failed to load config from vite.config.ts
uni is not a function
```

根因：`"type": "module"` 改变了 Vite 加载 `vite.config.ts` 时的 CJS/ESM 互操作方式，
`@dcloudio/vite-plugin-uni` 的默认导出被解析成模块命名空间对象而不是函数。
官方模板 `dcloudio/uni-preset-vue@vite` 同样不设该字段。

**需要 ESM 的脚本请用显式 `.mjs` 扩展名**（本工程 `scripts/*.mjs` 即如此）。

### 3.2 ⚠️ 版本必须锁死，不能"顺手升级"

| 包 | 锁定版本 | 为什么 |
|---|---|---|
| `vite` | **5.2.8** | `@dcloudio/vite-plugin-uni` 的 peerDependencies 写死该版本 |
| `rollup` | **4.14.3** | 与上面的 vite 配套 |
| 所有 `@dcloudio/*` | **同一个版本号** | 混用会出现「编译通过但运行时白屏」这类难查问题 |

注意 npm 上 `@dcloudio/vite-plugin-uni` 的 `latest` 标签指向 **2.0.2**，
其 peer 是 **Vite 2 + Vue 3.1-beta** —— 那是 **Vue2 线**，本工程不能用。
Vue3 线的版本号形如 `3.0.0-5020420260813003`。

### 3.3 npm 缓存被重定向到工作区内（`.npmrc`）

本机 agent 环境的文件沙箱只允许写工作区，而 npm 默认缓存目录在
`C:\Users\<user>\AppData\Local\npm-cache`，于是 `npm install` 会报：

```
EPERM: operation not permitted, open 'C:\Users\...\npm-cache\_cacache\tmp\***'
```

该报错**看起来**像盘满/杀毒软件锁文件，实际根因是**写权范围**。
`.npmrc` 里 `cache=.npm-cache` 解决它。这是本机环境适配，`.npm-cache/` 已在 `.gitignore` 中。

### 3.4 受限沙箱下 `npm install` 与构建会 `spawn EPERM`

需要提权执行（`spawn` 在受限模式下无法使用管道 stdio）。
现象分别是 `npm error spawn EPERM` 与 `failed to load config ... spawn EPERM`。

### 3.5 后端没有 CORS，H5 端靠 Vite 代理联通（**必须理解，否则接口全废**）

实测：后端响应**没有任何 CORS 头**，且预检 `OPTIONS` 返回 **403**。
H5 开发服务器在 `5173`、后端在 `8080`，属跨源 → 浏览器会拦掉全部 `/api` 请求。

本项目**不让后端加 CORS**（后端禁止改动），而是：

- H5 端的基地址是**同源相对路径**（空字符串），由
  `vite.config.ts` 的 `server.proxy` / `preview.proxy` 把 `/api` 转发到后端
- 生产环境由 **Nginx** 承担同样的反代职责，因此与生产同构

失败时的典型现象很隐蔽：页面显示「当前暂未开放注册」——
其实根因是 `register-mode` 请求被 CORS 拦掉后走了兜底分支，
看起来却像业务逻辑写错。**排查接口全挂时先看 Network 面板的 CORS 报错。**

### 3.6 小程序端本地调试要开「不校验合法域名」

小程序 `request` 只允许已备案 HTTPS 域名，本地 `http://127.0.0.1:8080` 靠开发者工具的
**「不校验合法域名」**开关连通（`src/manifest.json` 已设 `mp-weixin.setting.urlCheck: false`）。
这不是可以绕过的限制，是平台规则。

---

## 4. 目录结构

```
├── src/
│   ├── api/
│   │   ├── generated/            ← 由 openapi.json 快照生成，【禁止手改】
│   │   │   ├── openapi.snapshot.json   契约快照（带 SHA256 溯源）
│   │   │   └── schema.d.ts             生成的 TypeScript 类型
│   │   ├── contract.ts           接口登记表 + 契约元信息
│   │   ├── types.ts              类型出口（含两个"形状缺口"的人工窄化）
│   │   └── auth.ts               认证接口
│   ├── components/
│   │   └── DocPage.vue           静态文档页共用组件
│   ├── pages/                    页面（路由在 pages.json 注册）
│   ├── stores/
│   │   └── auth.ts               登录态（Pinia）
│   ├── styles/
│   │   └── variables.scss        设计变量
│   ├── utils/                    ★ 平台差异集中封装处
│   │   ├── request.ts            uni.request 封装（鉴权头/统一响应体/错误归一化）
│   │   ├── error-code.ts         契约 §6.1 错误码 → 用户文案
│   │   ├── storage.ts            本地存储封装（禁直接 localStorage）
│   │   ├── platform.ts           剪贴板 / 外部链接（#ifdef 集中在此）
│   │   └── env.ts                环境变量读取与校验（快速失败）
│   ├── App.vue   main.ts   pages.json   manifest.json   uni.scss
├── e2e/                          Playwright（黄金路径）
├── scripts/                      契约校验与变异测试
└── playwright.config.ts
```

**设计原则**：平台差异（存储、请求、剪贴板、外链）**只允许**出现在 `src/utils/`，
页面里不得出现 `#ifdef`。这是为了不让代码在三端之间分叉。

---

## 5. 契约一致性（本工程最重要的一道防线）

前端是独立仓库，无法在构建时读取后端仓库的 `openapi.json`，
因此工程内保存**快照**并据此生成类型。风险是"后端改了、快照没更新"。

```bash
npm run check:contract          # 对比本机后端 http://127.0.0.1:8080
node scripts/check-contract.mjs http://other:8080   # 对比指定后端
```

校验通过时 exit 0；发现漂移时 exit 1 并列出具体差异。
比对的是**结构**（路径/方法/schema/版本），不是字节 ——
因为后端导出与仓库快照存在行尾差异，按字节比对会永远误报。

**契约漂移时怎么办**（不要手工改快照，那会让生成类型与真实契约脱节）：

1. 确认后端改动是有意的
2. 用后端仓库的 `openapi.json` 覆盖 `src/api/generated/openapi.snapshot.json`
3. `npm run gen:api` 重新生成类型
4. 更新 `src/api/contract.ts` 里的 SHA256 常量
5. **若契约不足以支撑前端需求（缺字段/缺接口）**：按后端仓库 `AGENTS.md` **提 CR**，
   不要自己编造接口形状或"先这么写、回头对齐"

### 5.1 检测规则自身的可信度

```bash
node scripts/test-contract-drift.mjs
```

这是一条**变异测试**：往快照注入一个后端没有的路径，期望校验脚本报出漂移。
用途是防止校验脚本变成"永远返回通过"的假绿。
（思路来自后端仓库 `docs/agents/工作计划.md` §6.5.1「防假绿前置断言」。）

---

## 6. E2E（黄金路径，对真后端）

```bash
npm run build:h5      # E2E 对 dist/build/h5 产物跑，不是 dev server
npm run e2e
npm run e2e:report    # 看 HTML 报告
```

覆盖：注册（走完整 UI，含图形验证码）→ 登录 → 进入需登录页 → 刷新保持登录 →
错误码文案 → 未登录引导 → 退出登录。

**图形验证码怎么过**：直读 Redis 的 `hy:captcha:{uuid}` 取答案（`e2e/captcha-redis.ts`）。
不改后端一行（后端已交付且禁止改动），也不用 OCR（不稳定会产生抖动测试）。
前提可用 `node e2e/probe-captcha.ts` 独立验证。

**写 E2E 时的两个坑**：

1. **不要开 `video` 录制**。Playwright 录像需要额外下载 ffmpeg，
   缺失时**每个用例都会在 `newPage` 阶段失败**，表现为"全线挂掉"，
   看起来像应用坏了，实际与业务无关。截图排查价值相当且无额外依赖。
2. **`getByTestId` 拿到的是 `<uni-input>` 外壳，不是真 `<input>`**。
   `data-testid` 加在外壳上，但 `.fill()` 必须作用于内部原生 input，
   否则报 `Element is not an <input>...`（这个报错容易被误读成"testid 没透传"）。
   统一用 `uniInput(page, id)` 下钻（见 `e2e/golden-path.spec.ts`）。

**前置**：后端运行中；Redis 可达（默认 `192.168.100.128:6380`，
可用 `E2E_REDIS_HOST` / `E2E_REDIS_PORT` 覆盖）；本机装有 Chrome（`channel: 'chrome'`，
**不下载 Chromium**）。

---

## 7. 当前进度与契约缺口

后端 M1 已交付**认证 8 个接口**。本前端已实现：

| 页面 | 状态 | 依赖接口 |
|---|---|---|
| 登录 | ✅ 已联调 | `POST /api/auth/login` |
| 注册（含验证码、邀请制动态渲染） | ✅ 已联调 | `POST /api/auth/register`、`GET /api/auth/captcha`、`GET /api/auth/register-mode` |
| 我的 | ✅ 已联调 | `GET /api/user/me`、`POST /api/auth/logout` |
| 首页 | ⚠️ 骨架 | 双流列表依赖 M3 的 `/api/posts`（**后端尚未交付**） |
| 用户协议 / 隐私政策 / 免责声明 | ✅ 静态页 | 无 |

**尚未交付的接口**（实测返回 404，属于 M3–M5 范围）：
`/api/boards`、`/api/posts`、`/api/posts/{id}`、`/api/notifications`、
`/api/user/collections`、`/api/users/{id}`。

这些接口对应的页面**只做骨架**，点击会明确提示"接口未交付"，
**不会用假数据冒充已完成** —— 编造数据会让人误判进度。

---

## 8. 三条硬红线

1. **禁止硬编码域名与密钥**。基地址走 `.env`（`src/utils/env.ts` 启动时校验并快速失败）。
   本前端**不持有任何 OSS AccessKey / AppSecret**；图片上传将来走**服务端签名直传**。
2. **不引入 Vant 4**。Vant 是 Web 组件库，小程序端无 DOM，用不了。UI 库为 `wot-design-uni`。
3. **不改 `server/**` 与后端仓库的 `docs/**`**。发现契约不足要提 CR，不要自己改。
