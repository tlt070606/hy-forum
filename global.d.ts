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
 */
interface ImportMetaEnv {
  /** 后端接口基地址。例：`http://127.0.0.1:8080` */
  readonly VITE_API_BASE_URL: string
  /** 当前构建目标标识，仅用于调试展示，不参与业务逻辑 */
  readonly VITE_APP_ENV: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
