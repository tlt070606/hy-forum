/**
 * uni-app 应用入口。
 *
 * ⚠️⚠️⚠️ 本文件注释里**严禁出现下方 import 中那个「SSR 创建函数」的字面标识符**（2026-10-03 白屏事故根因）：
 * App 端编译器把入口里**第一处**该标识符做**朴素字符串替换**，改名成 App 专用启动器
 * （`uniMainJsPlugin`：`code.replace(首个匹配, 'createVueApp as ...')`，只替换第一处）。
 * 这里注释里若先写了它，替换就命中注释，真正的 import 不会被改名，
 * App 端于是用普通 SSR 创建函数启动 —— service 生命周期永远不接通，
 * 表现为**静默白屏**（无任何报错），而 H5 / 小程序端完全正常。本文注释因此改写。
 *
 * 注意两点，都不是可选的写法：
 * 1. **必须从 vue 导入那个 SSR 创建函数（而不是普通 createApp）** —— 这是 uni-app 的
 *    约定，H5 端 SSR/预渲染与小程序端都依赖它，换成普通 createApp 会在部分平台报错。
 *    （App 端编译器会自动把它改名为 App 专用启动器，见上。）
 * 2. **必须导出 `createApp` 函数并返回 `{ app }`** —— uni-app 的运行时自己调用它，
 *    不能在这里自己做 `app.mount()`（挂载由框架接管）。
 *
 * Pinia 的接入方式：在 `createApp()` 里创建实例并 `app.use()`。
 * 这样每个应用实例（H5 每次刷新、小程序每次启动）都拿到**全新**的 store，
 * 避免多实例场景下状态串味。
 */
import { createSSRApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'

export function createApp() {
  const app = createSSRApp(App)

  // Pinia：状态管理（本项目只用于登录态与当前用户，不滥用）
  app.use(createPinia())

  return { app }
}
