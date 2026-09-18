/**
 * 图片加载探针：打开一个页面，把每个 `<img>` 的**真实加载结果**与 OSS 响应的状态码打出来。
 *
 * 为什么不能只看"页面看起来有没有图"：`<image>` 加载失败在 uni-app 里
 * **不一定有可见报错**（就是一个空白灰框），所以必须读 `naturalWidth`。
 *
 * 用法：PROBE_ROUTE='#/pages/index/index' node scripts/img-probe.mjs
 */
import { chromium } from '@playwright/test'

const BASE = process.env.PROBE_BASE || 'http://127.0.0.1:5173'
const ROUTE = process.env.PROBE_ROUTE || '#/pages/index/index'

const browser = await chromium.launch({ channel: 'chrome' })
const page = await browser.newPage({ viewport: { width: 1280, height: 900 } })

const ossResponses = []
page.on('response', (r) => {
  if (r.url().includes('aliyuncs.com')) ossResponses.push(`${r.status()} ${r.url().slice(0, 150)}`)
})

await page.goto(`${BASE}/${ROUTE}`, { waitUntil: 'domcontentloaded' })
// 等接口 + 图片加载
await page.waitForTimeout(6000)

const imgs = await page.evaluate(() =>
  Array.from(document.querySelectorAll('img')).map((i) => ({
    src: String(i.currentSrc || i.src || ''),
    w: i.naturalWidth,
    h: i.naturalHeight,
  }))
)

console.log(`route = ${ROUTE}`)
console.log(`OSS 响应数 = ${ossResponses.length}`)
ossResponses.slice(0, 8).forEach((s) => console.log('  OSS ' + s))

console.log(`<img> 数 = ${imgs.length}`)
let broken = 0
for (const i of imgs) {
  const ok = i.w > 0 && i.h > 0
  if (!ok) broken++
  console.log(`  ${ok ? 'OK  ' : 'FAIL'} w=${i.w} h=${i.h}  ${i.src.slice(0, 120)}`)
}
console.log(broken === 0 ? '=> 全部加载成功' : `=> 有 ${broken} 张加载失败`)

await browser.close()
