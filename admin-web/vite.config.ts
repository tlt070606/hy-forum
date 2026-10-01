import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// ⚠️ 两个实测坑（沿袭 web/vite.config.ts 的教训，写在这里防止"顺手简化"回去）：
// 1. `base: '/admin/'` 是给**生产构建**用的（Nginx 按 /admin/ 前缀直出本产物）；
//    dev 模式下资源都从根路径起，base 改了 dev 的资源路径也跟着变 —— 所以 dev 不设 base。
// 2. Vite 的 mode 与"是否生产"无关：`vite build` 恒为 production mode，
//    `vite dev` 恒为 development。不要试图用 mode 区分档位（零代码分叉，差异只在 env）。
export default defineConfig(({ mode }) => ({
  base: mode === 'production' ? '/admin/' : '/',
  plugins: [vue()],
  server: {
    port: 5174,
    proxy: {
      // 与前台 H5 同一条规则：后端没有 CORS 头，开发期必须同源反代
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
      },
    },
  },
}))
