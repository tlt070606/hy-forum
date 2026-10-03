/**
 * App 端产物体检脚本。
 *
 * ==========================================================================
 * 为什么需要它（K3 白屏事故的直接产物）
 * ==========================================================================
 * 2026-10-03 的 App 端全量白屏事故：`src/main.ts` 顶部**注释里**先于 import
 * 出现了字面量 `createSSRApp`，而 App 端编译器（`uniMainJsPlugin`）用
 * **朴素字符串替换**把入口里第一处该字面量改名为 App 专用启动器
 * （`createVueApp`，只替换第一处）。替换命中了注释，真正的 import 未被改名，
 * App 于是用普通 SSR 创建函数启动 —— service 生命周期永不接通，
 * 表现为**静默白屏**：编译成功、安装成功、无任何报错、零网络请求。
 *
 * 这次事故的教训是："编译成功"与"产物正确"是两回事。
 * uni 系编译器大量使用**文本替换型 transform**，注释/文案都可能成为它们的
 * 替换目标（同类前科：注释里写条件编译指令导致预处理失衡，见 `src/utils/env.ts`
 * 顶部说明）。所以产物必须**独立体检**，不能信任"build 绿了"。
 *
 * ==========================================================================
 * 检查项（全部来自真实事故，不是理论风险）
 * ==========================================================================
 * 1. **入口改写生效**：`app-service.js` 必须包含 `createVueApp(`。
 *    这是 K3 白屏的直接判据 —— 改写没生效 = service 不启动 = 白屏。
 * 2. **无空 tabBar 配置**：`app-config-service.js` 不得存在 `"list":[]` 的
 *    tabBar 块（pages.json 里写空 tabBar 曾让微信端 app.json 非法，
 *    也曾随 10-03 01:00 的云打包进入 App 产物）。
 * 3. **AppID 已回填**：`manifest.json` 的 `id` 必须非空（空 appid 曾阻断
 *    打包/同步，见 10-02 dd7156c）。
 * 4. **后端基地址已编入**：按 `.env.production.local` > `.env.production` >
 *    `.env` 的优先级解析出期望基地址，并断言它出现在 `app-service.js` 里
 *    （小程序包曾因构建顺序拿到空地址，见 K2）。
 * 5. **入口页文件存在**：`entryPagePath` 对应的样式文件必须产出
 *    （pages/ 目录与 pages.json 不同步时的快速哨兵）。
 *
 * ==========================================================================
 * 用法
 * ==========================================================================
 *   npm run check:app-artifact          # 体检默认产物 dist/build/app
 *   node scripts/check-app-artifact.mjs dist/dev/app   # 体检指定目录
 *
 * 建议把 `npm run package:app`（build + 体检）作为云打包前的标准动作：
 * HBuilderX 云打包吃的是 dist 现状，**隔夜 dist 不得直接打包**。
 */

import { readFileSync, existsSync, statSync } from 'node:fs'
import { join, resolve } from 'node:path'

const distDir = resolve(process.argv[2] ?? 'dist/build/app')

/** 解析期望的后端基地址（与 src/utils/env.ts 的取值优先级保持一致） */
function resolveExpectedBaseUrl() {
  // uni build 恒为 production 模式：加载 .env 与 .env.production（.local 优先级更高，
  // 由 Vite dotenv 的加载顺序决定，这里手工模拟同样的覆盖顺序）
  const files = ['.env', '.env.production', '.env.production.local']
  const env = {}
  for (const f of files) {
    const p = resolve(f)
    if (!existsSync(p)) continue
    for (const line of readFileSync(p, 'utf8').split('\n')) {
      const m = /^\s*(VITE_[A-Z_]+)\s*=\s*(.*?)\s*$/.exec(line)
      if (m) env[m[1]] = m[2]
    }
  }
  const configured = (env.VITE_API_BASE_URL ?? '').trim()
  if (configured.includes('://')) return configured
  return (env.VITE_DEV_SERVER_ORIGIN ?? '').trim()
}

const problems = []
const checks = []

function check(name, ok, detail = '') {
  checks.push({ name, ok, detail })
  if (!ok) problems.push(`${name}${detail ? ` —— ${detail}` : ''}`)
}

// ---------- 0. 产物存在 ----------
for (const f of ['app-service.js', 'app-config-service.js', 'manifest.json']) {
  const p = join(distDir, f)
  check(`产物文件存在: ${f}`, existsSync(p) && statSync(p).isFile(), p)
}
if (problems.length > 0) {
  console.error(`\n❌ App 产物体检不通过（${distDir}）\n`)
  for (const p of problems) console.error(`  ✗ ${p}`)
  console.error(`\n请先 npm run build:app 重新构建，再执行本体检。\n`)
  process.exit(1)
}

const appService = readFileSync(join(distDir, 'app-service.js'), 'utf8')
const appConfig = readFileSync(join(distDir, 'app-config-service.js'), 'utf8')
const manifest = JSON.parse(readFileSync(join(distDir, 'manifest.json'), 'utf8'))

// ---------- 1. 入口改写生效（K3 白屏判据） ----------
const hasCreateVueApp = appService.includes('createVueApp(')
const createSSRCallSites = (appService.match(/createSSRApp\(/g) ?? []).length
check(
  '入口改写生效（createVueApp）',
  hasCreateVueApp,
  '未命中说明 uniMainJsPlugin 的改写没有应用到入口 —— App 端将静默白屏。' +
    '最常见原因：src/main.ts 在 import 之前出现了入口创建函数的字面量（注释里也不行）。',
)
if (createSSRCallSites > 0) {
  check(
    '无裸 createSSRApp 调用残留',
    false,
    `发现 ${createSSRCallSites} 处 —— 入口被改写后不应再有裸调用，请检查 main.ts。`,
  )
}

// ---------- 2. 无空 tabBar 配置 ----------
const emptyTabBar = /"tabBar":\{[^}]*"list":\[\]/.test(appConfig)
check('无空 list 的 tabBar 配置', !emptyTabBar, 'pages.json 里写了空 tabBar 块会产出非法配置')

// ---------- 3. AppID 已回填 ----------
check(
  'manifest AppID 非空',
  typeof manifest.id === 'string' && manifest.id.startsWith('__UNI') && manifest.id !== '__UNI__HYFORUM01',
  `当前 id=${JSON.stringify(manifest.id)}；占位 id（__UNI__HYFORUM01）不得进入发布产物`,
)

// ---------- 4. 后端基地址已编入 ----------
const expectedBase = resolveExpectedBaseUrl()
if (!expectedBase) {
  check('后端基地址已配置', false, '.env 系文件里既无 VITE_API_BASE_URL 也无 VITE_DEV_SERVER_ORIGIN')
} else {
  const baseHost = expectedBase.replace(/\/+$/, '')
  check(
    `后端基地址已编入产物（${baseHost}）`,
    appService.includes(baseHost),
    '打包后 App 连不上后端的第一排查点；K2（小程序空地址）的同型缺陷',
  )
}

// ---------- 5. 入口页文件存在 ----------
const entry = /"entryPagePath":"([^"]+)"/.exec(appConfig)?.[1]
if (entry) {
  const entryCss = join(distDir, `${entry}.css`)
  check('入口页样式文件已产出', existsSync(entryCss), entryCss)
} else {
  check('entryPagePath 存在于配置', false, 'app-config-service.js 里没有 entryPagePath')
}

// ---------- 报告 ----------
console.log(`\n📋 App 产物体检：${distDir}\n`)
for (const c of checks) console.log(`  ${c.ok ? '✓' : '✗'} ${c.name}${c.detail && !c.ok ? `\n      ↳ ${c.detail}` : ''}`)

if (problems.length > 0) {
  console.error(`\n❌ 体检不通过：${problems.length} 项。带着这个产物去云打包 = 复现 K3。\n`)
  process.exit(1)
}
console.log(`\n✅ 体检全部通过（${checks.length} 项）。可以拿去云打包了。\n`)
