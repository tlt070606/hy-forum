/**
 * 探针：确认 `data-testid` 是否真的出现在 uni-app H5 产物的 DOM 上。
 *
 * 为什么必须先验证这一点：E2E 全部用例都靠 `getByTestId` 定位元素。
 * 若 uni-app 不把自定义属性透传到宿主元素上，**所有用例都会失败**，
 * 而失败信息会是"元素找不到"，看起来像应用没渲染 —— 根因被误导。
 * 先独立确认机制成立，再相信 E2E 的结论。
 *
 * 用法：node scripts/probe-testids.ts
 * 前置：已 `npm run build:h5`，且 5173 端口有静态服务（或用 --url 指定）
 */

import { chromium } from 'playwright'

const URL_BASE = process.env.PROBE_URL || 'http://127.0.0.1:5173'

console.log('=== 探针：data-testid 透传情况 ===\n')

const browser = await chromium.launch({ channel: 'chrome' })
const page = await browser.newPage()

try {
  // ---- 登录页 ----
  await page.goto(`${URL_BASE}/#/pages/login/index`)
  await page.waitForTimeout(1500) // 等 uni-app 水合完成

  const loginInfo = await page.evaluate(() => {
    const ids = ['login-username', 'login-password', 'login-submit']
    return ids.map((id) => {
      const el = document.querySelector(`[data-testid="${id}"]`)
      return {
        id,
        found: !!el,
        tag: el?.tagName ?? null,
        // 对 input 来说，真正能填值的是内部的 <input>，不是 <uni-input> 外壳
        innerInput: !!el?.querySelector?.('input'),
      }
    })
  })
  console.log('登录页：')
  loginInfo.forEach((i) =>
    console.log(`  ${i.id.padEnd(18)} found=${i.found}  tag=${i.tag}  innerInput=${i.innerInput}`)
  )

  // 若外层命中但内部才是真 input，Playwright 的 fill 仍能工作（会穿透到可编辑元素）
  const fillTest = await page
    .getByTestId('login-username')
    .fill('probe_user')
    .then(() => page.getByTestId('login-username').inputValue())
    .catch((e) => `FAILED: ${e.message.split('\n')[0]}`)
  console.log(`  fill 实测结果 = ${JSON.stringify(fillTest)}`)

  // ---- 注册页 ----
  await page.goto(`${URL_BASE}/#/pages/register/index`)
  await page.waitForTimeout(1500)

  const regInfo = await page.evaluate(() => {
    const ids = [
      'reg-username',
      'reg-password',
      'reg-nickname',
      'reg-captcha-code',
      'reg-agree',
      'reg-submit',
    ]
    return ids.map((id) => {
      const el = document.querySelector(`[data-testid="${id}"]`)
      return { id, found: !!el, tag: el?.tagName ?? null }
    })
  })
  console.log('\n注册页：')
  regInfo.forEach((i) => console.log(`  ${i.id.padEnd(18)} found=${i.found}  tag=${i.tag}`))

  const allFound =
    loginInfo.every((i) => i.found) && regInfo.every((i) => i.found)
  console.log(`\n结论：data-testid ${allFound ? '✓ 全部透传成功' : '✗ 存在未透传的 testid'}`)

  if (!allFound) {
    // 输出页面结构片段，便于判断 uni-app 用了什么属性承载
    const snippet = await page.evaluate(() => document.body.innerHTML.slice(0, 1200))
    console.log('\n--- body 片段 ---')
    console.log(snippet)
    process.exitCode = 1
  }
} finally {
  await browser.close()
}
