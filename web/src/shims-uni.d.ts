/**
 * uni-app 的类型补充声明。
 *
 * 作用：让 Vue 的 `ComponentCustomOptions` 合并 uni-app 的应用/页面生命周期。
 * 有了它，在 `App.vue` / 页面组件里写 `onLoad` / `onShow` 等 uni-app 生命周期
 * 才能获得类型提示，否则 TS 会认为它们是未知选项。
 */
export {}

declare module 'vue' {
  type Hooks = App.AppInstance & Page.PageInstance

  // eslint-disable-next-line @typescript-eslint/no-empty-interface
  interface ComponentCustomOptions extends Hooks {}
}

/**
 * `.vue` 单文件组件的模块声明。
 *
 * Vite + vue-tsc 通常能自己处理 `.vue` 导入，但 `vite.config.ts` 与 `e2e/` 下
 * 的 TS 文件不在 vue 插件的处理链上时，会报 "Cannot find module '*.vue'"。
 * 显式声明一次，消除这类误报。
 */
declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<{}, {}, any>
  export default component
}
