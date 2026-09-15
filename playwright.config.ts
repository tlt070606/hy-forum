/**
 * Playwright 配置。
 *
 * ==========================================================================
 * 两个关键决策
 * ==========================================================================
 * 1. **`channel: 'chrome'` 而不是下载 Chromium**
 *    本机已安装 Chrome（`C:\Program Files\Google\Chrome\Application\chrome.exe`），
 *    用 channel 直接复用，省下数百 MB 下载与磁盘占用。
 *    代价：依赖本机装有 Chrome；若换机器需 `npx playwright install chromium` 并改回默认。
 *
 * 2. **对 `dist/build/h5` 的产物做静态服务，而不是跑 dev server**
 *    任务书 §5 要求「对着真后端跑通注册→登录」。用**构建产物**验证比用 dev server
 *    更接近交付形态（H5 产物就是最终要部署的东西），也避免了 HMR 与首次编译带来的抖动。
 *    因此先 `npm run build:h5`，本配置只负责把它服务起来。
 *
 * 后端必须是**真实运行**的：`http://127.0.0.1:8080`（见 `.env.development`）。
 * 本配置**不会**帮你启动后端 —— 后端没起时测试会失败，这是正确的信号，
 * 不应该被"自动 mock"掩盖掉。
 */

import { defineConfig, devices } from '@playwright/test'

/** 静态服务端口。与 Vite 默认 5173 保持一致，避免多记一个数字 */
const PORT = 5173

export default defineConfig({
  testDir: './e2e',
  /* 只跑 *.spec.ts。probe-*.ts 是探针脚本，不是测试用例 */
  testMatch: /.*\.spec\.ts$/,

  /* 注册用例要跑完整链路（含后端往返），给足时间 */
  timeout: 60_000,
  expect: { timeout: 10_000 },

  /* E2E 依赖共享后端状态（注册会写库），并行跑会互相干扰账号与限流，故串行 */
  fullyParallel: false,
  workers: 1,

  /* 失败重试 1 次：网络抖动不该让结论翻转；但重试次数要少，避免掩盖真实不稳定 */
  retries: 1,

  reporter: [['list'], ['html', { open: 'never' }]],

  use: {
    baseURL: `http://127.0.0.1:${PORT}`,
    /* 失败时留证据：截图 + trace，便于定位而不必重跑 */
    screenshot: 'only-on-failure',
    /*
     * ⚠️ 不开 `video`。
     * Playwright 录制视频需要额外的 ffmpeg 二进制（默认不随库安装），
     * 缺失时**每一个测试都会在 `browserContext.newPage` 阶段直接失败**，
     * 报 "Executable doesn't exist ... ffmpeg-win64.exe" ——
     * 表现为"所有用例都挂了"，看起来像应用坏了，实际与业务代码无关（本机实测踩到）。
     * 截图的排查价值与视频相当且无额外依赖，因此不引入 ffmpeg。
     * 若确实需要录像：`npx playwright install ffmpeg` 后再把下面改成 'retain-on-failure'。
     */
    video: 'off',
    trace: 'retain-on-failure',
    /* 桌面视口即可：uni-app H5 是响应式的，表单在窄屏也能正常操作 */
    viewport: { width: 420, height: 900 },
  },

  projects: [
    {
      name: 'h5-chrome',
      use: {
        ...devices['Desktop Chrome'],
        channel: 'chrome',
        viewport: { width: 420, height: 900 },
      },
    },
  ],

  /*
   * 静态服务 H5 产物。
   * `reuseExistingServer` 在本机为 true：调试时可自己先起一个服务复用，
   * CI 下（CI 环境变量存在）不重用，保证构建产物是新鲜的。
   */
  webServer: {
    command: `npx vite preview --outDir dist/build/h5 --port ${PORT} --strictPort`,
    url: `http://127.0.0.1:${PORT}/`,
    reuseExistingServer: !process.env.CI,
    timeout: 60_000,
  },
})
