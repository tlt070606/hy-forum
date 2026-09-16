/**
 * 全局类型声明与环境变量类型定义。
 *
 * `/// <reference types='@dcloudio/types' />` 是必需的：
 * uni-app 的全局对象（`uni`、`UniApp`、`getApp()` 等）声明在该包里，
 * 不引用则所有 `uni.xxx` 调用都会报 "Cannot find name 'uni'"。
 */
/// <reference types='@dcloudio/types' />

export {}

declare module 'vue' {
  type Hooks = App.AppInstance & Page.PageInstance
  // eslint-disable-next-line @typescript-eslint/no-empty-interface
  interface ComponentCustomOptions extends Hooks {}
}

/**
 * Vite 注入的环境变量类型。
 *
 * 约定（铁律 5：禁止硬编码域名与密钥）：
 * - 域名一律从 `import.meta.env.VITE_API_BASE_URL` 读取，**绝不写死在代码里**
 * - 本前端**不持有任何 OSS AccessKey / AppSecret**，图片上传走服务端签名直传（铁律 8）
 *
 * ⚠️ 为什么整块包在 `declare global { }` 里（H10 修的，之前是坏的）：
 *   本文件顶部有 `export {}`（**必需** —— 没有它，下面的 `declare module 'vue'`
 *   会从"模块增强"退化成"重新声明整个 vue 模块"，把 Vue 的类型全弄丢）。
 *   但 `export {}` 同时也把这个文件变成了**模块**，模块里的 `interface ImportMetaEnv`
 *   / `interface ImportMeta` 只是**模块内的局部声明**，根本不会挂到全局 ——
 *   于是 `src/utils/env.ts` 报了 4 个
 *   `TS2339: Property 'env' does not exist on type 'ImportMeta'`。
 *   包进 `declare global` 后它们才真正参与全局接口合并（与 `vite/client` 的
 *   `ImportMetaEnv` 合并），使本项目自定义的 `VITE_*` 变量拿到**显式类型**，
 *   而不是退化到 vite 的 `[key: string]: any` 索引签名上（那种"能编过"是假的，
 *   变量名写错也不会报错）。
 */
declare global {
  interface ImportMetaEnv {
    /**
     * 后端接口基地址。
     *
     * ⚠️ 形态随平台不同，见 `src/utils/env.ts` 的说明：
     * - H5：**同源相对路径**（开发期用 `/api`），走 Vite 代理 / Nginx 反代
     * - 小程序 / App：**绝对地址**（`uni.request` 不支持相对路径）
     */
    readonly VITE_API_BASE_URL: string

    /**
     * 本机后端绝对地址，供**小程序 / App 端**与 **Vite 代理转发目标**使用。
     * 例：`http://127.0.0.1:8080`
     */
    readonly VITE_DEV_SERVER_ORIGIN: string

    /** 当前构建目标标识，仅用于调试展示，不参与业务逻辑 */
    readonly VITE_APP_ENV: string
  }

  interface ImportMeta {
    readonly env: ImportMetaEnv
  }
}
