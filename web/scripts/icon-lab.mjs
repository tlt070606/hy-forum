/**
 * 图标试验台：把候选的图形画法**放大到 120px** 并排渲染再截图，用来挑形状与线宽。
 *
 * 为什么需要它：在列表卡片上看 28px 的图标根本判断不出形状对不对。
 * 本机已经因此栽过两次：
 * - 第一次把"两个圆 + 旋转方块"当心形，实际是**盾形**；
 * - 第二次改完看的是 1920 宽、整页 6000+ 像素高的截图，28px 图标被缩到看不清，
 *   又误判成"没生效"。
 * 唯一可靠的办法：**单独放大、并排对比**。要看应用里真实渲染的某个控件，
 * 用 `shot.mjs` 的 `SHOT_SELECTOR` + `SHOT_SCALE`（元素级 5 倍截图）。
 *
 * 用法：node scripts/icon-lab.mjs
 */
import { chromium } from '@playwright/test'
import { mkdirSync } from 'node:fs'

const OUT = '.tmp/iconlab'
mkdirSync(OUT, { recursive: true })

/**
 * 线框爱心 = 实心（描边色）+ 按**心尖**缩小 s 倍的同形状（底色）挖空。
 *
 * 两个参数决定观感，必须在放大图上比：
 * - `s`（缩放）：决定轮廓**粗细**。(1-s)/2 大致就是单边厚度占比；
 * - `mt`（内层上移）：缩放是绕心尖做的，不补的话**上半部分（两个圆瓣）会明显偏厚**，
 *   补多了则心尖会多出一小截"尖刺"（本机踩过：-9% 时尖端有刺）。
 */
const outline = (s, mt) => `
    <i style="left:50%;top:0;width:50%;height:80%;border-radius:50% 50% 0 0;transform-origin:0% 100%;transform:rotate(-45deg);background:currentColor"></i>
    <i style="left:0;top:0;width:50%;height:80%;border-radius:50% 50% 0 0;transform-origin:100% 100%;transform:rotate(45deg);background:currentColor"></i>
    <i style="left:50%;top:0;width:50%;height:80%;border-radius:50% 50% 0 0;transform-origin:0% 100%;transform:rotate(-45deg) scale(${s});background:#ffffff;margin-top:${mt}%"></i>
    <i style="left:0;top:0;width:50%;height:80%;border-radius:50% 50% 0 0;transform-origin:100% 100%;transform:rotate(45deg) scale(${s});background:#ffffff;margin-top:${mt}%"></i>`

const filled = `
    <i style="left:50%;top:0;width:50%;height:80%;border-radius:50% 50% 0 0;transform-origin:0% 100%;transform:rotate(-45deg);background:currentColor"></i>
    <i style="left:0;top:0;width:50%;height:80%;border-radius:50% 50% 0 0;transform-origin:100% 100%;transform:rotate(45deg);background:currentColor"></i>`

const CANDIDATES = {
  'H 现用 s=.70 mt=-9（太粗、尖端有刺）': outline(0.7, -9),
  'I s=.84 mt=-3': outline(0.84, -3),
  'J s=.84 mt=-6': outline(0.84, -6),
  'K s=.88 mt=-2': outline(0.88, -2),
  'L s=.86 mt=-4': outline(0.86, -4),
  'M 实心（对照）': filled,
}

const html = `<!doctype html><html><body style="margin:0;background:#fff;font:12px sans-serif">
<div style="display:flex;flex-wrap:wrap;gap:20px;padding:20px">
${Object.entries(CANDIDATES)
  .map(
    ([name, shapes]) => `
  <div style="width:150px">
    <div style="position:relative;width:120px;height:120px;color:#4e5969">${shapes}</div>
    <div style="margin-top:8px;color:#333">${name}</div>
  </div>`
  )
  .join('')}
</div>
<style>i{position:absolute;display:block;box-sizing:border-box}</style>
</body></html>`

const browser = await chromium.launch({ channel: 'chrome' })
const page = await browser.newPage({ viewport: { width: 1100, height: 380 } })
await page.setContent(html)
await page.waitForTimeout(300)
await page.screenshot({ path: `${OUT}/hearts.png`, fullPage: true })
console.log(`shot: ${OUT}/hearts.png`)
await browser.close()
