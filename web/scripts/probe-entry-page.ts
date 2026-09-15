/**
 * 探针：验证「打开应用直接落在登录页」以及登录页的几个关键状态。
 *
 * 为什么需要：`pages.json` 把 auth 放到 `pages` 第一位即"启动页"，
 * 这个行为**只能靠真实打开验证**（读配置看不出 uni-app 是否真的用它当入口）。
 * 而且我在这轮改动里犯过一个错：把启动页改了但没验证，
 * 属于"改了就当完成"。这里补上可执行的证据。
 *
 * 用法：
 *   npm run build:h5
 *   node scripts/probe-entry-page.ts        # 需 5173 有静态服务
 */

import { chromium } from 'playwright'

const URL_BASE = process.env.PROBE_URL || 'http://127.0.0.1:5173'

console.log('=== 探针：启动页与登录页状态 ===\n')

const browser = await chromium.launch({ channel: 'chrome' })
const page = await browser.newPage()

let failed = false

function check(label: string, ok: boolean, detail = '') {
  console.log(`  ${ok ? '✓' : '✗'} ${label}${detail ? `  ${detail}` : ''}`)
  if (!ok) failed = true
}

try {
  /* ---- 1. 只打开根路径，看落在哪一页 ---- */
  await page.goto(URL_BASE)
  await page.waitForTimeout(1500)

  const url = page.url()
  const hash = url.includes('#') ? url.slice(url.indexOf('#')) : '(无 hash)'
  console.log(`打开 ${URL_BASE} 后：`)
  console.log(`  实际 URL hash = ${hash}\n`)

  /*
   * ⚠️ 不要断言 hash 等于 `#/pages/auth/index`。
   * uni-app 把 `pages.json` 的第一项作为启动页时，H5 的初始 hash 就是 **`#/`**
   * （它不会把第一项的路径写进 URL）。我第一次就是这么断言的，结果误报失败。
   * 正确的判据是**页面实际渲染了什么**：登录表单在不在。
   */
  const loginVisible = await page.getByTestId('login-username').isVisible()
  check('启动页是登录/注册页（登录表单已渲染）', loginVisible, `hash=${hash}`)

  /* ---- 3. 注册表单默认不显示（默认在登录 Tab） ---- */
  const regHidden = await page.locator('[data-testid="reg-submit"]').isHidden()
  check('默认停在登录 Tab（注册表单未渲染）', regHidden)

  /* ---- 4. Tab 存在且可切换 ---- */
  const tabs = await page.locator('[data-testid^="auth-tab-"]').count()
  check('两个 Tab 都在', tabs === 2, `找到 ${tabs} 个`)

  /* ---- 5. 「先逛逛」出口存在（小程序端靠它离开登录页）---- */
  const skipVisible = await page.getByTestId('auth-skip').isVisible()
  check('「先随便逛逛」出口可见', skipVisible)

  /* ---- 6. 注册可用性：后端 register-mode 是 open，应该显示"立即注册" ---- */
  const goRegister = await page.getByTestId('login-go-register').isVisible()
  check('显示「立即注册」（说明 register-mode 查询成功且为 open）', goRegister)

  if (!goRegister) {
    // 查失败原因，避免把"后端没起"误判成"注册关闭"
    const errText = await page
      .getByTestId('register-mode-error')
      .textContent()
      .catch(() => null)
    const closed = await page.getByText('当前暂未开放注册').isVisible().catch(() => false)
    if (errText) console.log(`      → 页面报错文案：${errText}`)
    else if (closed) console.log('      → 页面显示"当前暂未开放注册"（后端确实关闭了？还是查询失败被误判）')
  }

  /* ---- 7. 点「先逛逛」应离开登录页 ---- */
  if (skipVisible) {
    await page.getByTestId('auth-skip').click()
    await page.waitForTimeout(1200)
    const after = page.url().includes('#') ? page.url().slice(page.url().indexOf('#')) : '(无 hash)'
    check('点「先逛逛」后离开登录页', after.includes('/pages/index/index'), `hash=${after}`)
  }
} finally {
  await browser.close()
}

console.log(`\n${failed ? '✗ 存在未通过项' : '✓ 全部通过'}`)
process.exitCode = failed ? 1 : 0
