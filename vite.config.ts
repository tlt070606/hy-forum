import { defineConfig } from 'vite'
import uni from '@dcloudio/vite-plugin-uni'

/**
 * Vite 配置。
 *
 * 本项目是 uni-app 工程，**唯一前端**（一套代码编译 H5 / 安卓 App / 微信小程序），
 * 因此 vite 只承担 H5 与小程序两端的构建，不做 Web 专属配置（如 PWA、SSR）。
 *
 * 版本约束（不要随意"顺手升级"，这是从 dcloudio/uni-preset-vue@vite 官方模板核对的）：
 * - `vite` 必须锁 `5.2.8`   —— `@dcloudio/vite-plugin-uni` 的 peerDependencies 写死该版本
 * - `rollup` 必须锁 `4.14.3` —— 与上面的 vite 配套，rollup 4 的大版本差异会出编译错
 * - 所有 `@dcloudio/*` 包必须**同一版本号**（3.0.0-5020420260813003），
 *   混用版本会出现「编译通过但运行时白屏」这类难查的问题
 * - **不要给 package.json 加 `"type": "module"`**，那会让构建报 `uni is not a function`
 *
 * ==========================================================================
 * 为什么要配 proxy（这是 H5 端能联通后端的关键，不是可选优化）
 * ==========================================================================
 * 后端**没有任何 CORS 响应头**，且预检 `OPTIONS` 返回 **403**（本机实测）。
 * 而 H5 页面跑在 `127.0.0.1:5173`、后端在 `127.0.0.1:8080` —— 属于**跨源**，
 * 于是浏览器会把所有 `/api` 请求拦掉，前端表现为"接口全部失败"。
 *
 * 三种解法与取舍：
 * ① 让后端加 CORS 配置 → **排除**。任务书 §3 禁止改 `server/**`，后端已交付并复核。
 * ② 前端硬编码跨源绝对地址 → 等于把 CORS 问题留在浏览器里，**根本没解决**。
 * ③ **Vite 代理**（本方案）→ 浏览器只发同源请求给 5173，由 Vite 服务端转发给 8080。
 *    服务端之间没有同源策略，问题从根上消失；且**不需要后端任何配合**。
 *
 * ⚠️ 与「铁律 5：禁止硬编码域名」的关系：
 *    真实域名的唯一来源仍是 `.env`，代理目标**从环境变量读取**，代码里没有第二份事实。
 *    生产环境由 Nginx 承担同样的同源职责（`/api` 反代到后端），
 *    因此这不是"仅开发可用"的权宜之计，而是**与生产同构**的做法。
 */
export default defineConfig(() => {
  /*
   * 代理目标：优先用环境变量，未设置时回退到本地后端。
   * 这里不 import `src/utils/env.ts` —— 那是运行时代码（含 import.meta.env 的
   * 启动期校验与 H5/小程序分支），在 vite 配置阶段加载会引入不必要的耦合。
   */
  const apiTarget = process.env.VITE_API_BASE_URL || 'http://127.0.0.1:8080'

  /** 开发服务器与预览服务器共用的代理配置 */
  const proxy = {
    '/api': {
      target: apiTarget,
      // 后端接口路径本身就带 /api 前缀，因此不做 rewrite
      changeOrigin: true,
    },
  }

  return {
    plugins: [uni()],

    server: {
      port: 5173,
      // 允许局域网访问，便于用手机浏览器验证 H5 端真实表现
      host: true,
      proxy,
    },

    /*
     * `vite preview` 也必须代理。
     * E2E 跑的是**构建产物**（见 playwright.config.ts 的说明），
     * 而 preview 是另一个 server 实例，不配的话 E2E 会因为跨源全线失败 ——
     * 这正是本机实测踩到的坑：现象是"页面显示『当前暂未开放注册』"，
     * 因为 `register-mode` 请求被 CORS 拦截后走了兜底分支，根因却看起来像业务逻辑错。
     */
    preview: {
      port: 5173,
      proxy,
    },
  }
})
