/**
 * 探针：验证「图形验证码真的能显示出来」。
 *
 * ==========================================================================
 * 为什么需要它（这就是最初用户报的 bug）
 * ==========================================================================
 * 用户反馈"验证码看不到"。根因：后端返回的 `base64Image` 是**裸 base64**，
 * 不带 `data:image/png;base64,` 前缀，直接塞进 `<image src>` 会被当成
 * **相对 URL**，于是图片永不渲染 —— 而且**控制台不报错**，极难定位。
 *
 * 修好之后必须**用真实渲染结果验证**，而不是"我改了代码所以它应该好了"：
 * 注意 E2E 的注册用例**无法发现这个问题**（它只等接口响应，不等图片渲染），
 * 这就是为什么需要这个独立探针。
 *
 * 用法：
 *   npm run build:h5
 *   node scripts/probe-captcha-render.ts        # 需 5173 有静态服务
 */

import { chromium } from 'playwright'

const URL_BASE = process.env.PROBE_URL || 'http://127.0.0.1:5173'
const API_BASE = process.env.E2E_API_BASE || 'http://127.0.0.1:8080'

console.log('=== 探针：验证码渲染 ===\n')

// ---- 第 0 步：先确认后端返回的确实是裸 base64（把根因钉死） ----
const apiRes = await fetch(`${API_BASE}/api/auth/captcha`)
const apiBody = await apiRes.json()
const rawPayload = apiBody?.data?.base64Image ?? ''
console.log(`后端原始载荷前 40 字符 : ${rawPayload.slice(0, 40)}`)
console.log(`后端是否带 data: 前缀  : ${rawPayload.startsWith('data:')}  （false = 裸 base64，就是 bug 根因）`)

const browser = await chromium.launch({ channel: 'chrome' })
const page = await browser.newPage()

try {
  await page.goto(`${URL_BASE}/#/pages/auth/index?mode=register`)
  // 等验证码请求完成
  await page.waitForResponse((r) => r.url().includes('/api/auth/captcha') && r.status() === 200, {
    timeout: 15_000,
  })
  await page.waitForTimeout(800)

  // ---- 1. 找到验证码图块里的 <img>，检查 src 是否是合法 data URI ----
  const info = await page.evaluate(() => {
    const box = document.querySelector('[data-testid="reg-captcha-box"]')
    if (!box) return { error: '找不到 reg-captcha-box' }
    const img = box.querySelector('img')
    if (!img) {
      // 没有 img 说明走了 fallback 分支（captchaImage 为空）
      return { error: '图块内没有 <img>，说明 captchaImage 为空走了兜底分支', html: box.innerHTML.slice(0, 300) }
    }
    return {
      srcPrefix: img.getAttribute('src')?.slice(0, 34) ?? '(空)',
      srcLength: img.getAttribute('src')?.length ?? 0,
      naturalWidth: img.naturalWidth,
      naturalHeight: img.naturalHeight,
      complete: img.complete,
      rect: { w: Math.round(img.getBoundingClientRect().width), h: Math.round(img.getBoundingClientRect().height) },
    }
  })

  console.log('\n--- 页面内 <img> 实测 ---')
  console.log(JSON.stringify(info, null, 2))

  // ---- 2. 判定 ----
  const ok =
    !info.error &&
    info.srcPrefix?.startsWith('data:image/') &&
    (info.naturalWidth ?? 0) > 0 &&
    (info.rect?.w ?? 0) > 0

  console.log('')
  if (ok) {
    console.log('✓ 验证码已正常渲染：')
    console.log(`    src 是 data URI、图片解码成功（原始尺寸 ${info.naturalWidth}x${info.naturalHeight}）`)
    console.log(`    页面上实际占位 ${info.rect?.w}x${info.rect?.h} px（已放大）`)
  } else {
    console.error('✗ 验证码仍未正常渲染，需要继续排查：')
    if (info.error) console.error(`    ${info.error}`)
    if (info.srcPrefix && !info.srcPrefix.startsWith('data:image/')) {
      console.error(`    src 不是 data URI，前缀为：${info.srcPrefix}`)
    }
    if ((info.naturalWidth ?? 0) === 0) console.error('    img.naturalWidth = 0，说明图片解码失败（src 无效）')
    process.exitCode = 1
  }
} finally {
  await browser.close()
}
