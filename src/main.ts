/**
 * uni-app 应用入口。
 *
 * 注意两点，都不是可选的写法：
 * 1. **必须用 `createSSRApp` 而不是 `createApp`** —— 这是 uni-app 的约定，
 *    H5 端 SSR/预渲染与小程序端都依赖它，换成 `createApp` 会在部分平台报错。
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
