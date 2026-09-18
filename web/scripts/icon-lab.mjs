/**
 * 图标试验台：把候选的图形画法**放大到 120px** 并排渲染再截图，用来挑形状与线宽。
 *
 * 为什么需要它：在列表卡片上看 28px 的图标根本判断不出形状对不对。
 * 本机已经因此栽过三次：
 * - 把"两个圆 + 旋转方块"当心形，实际是**盾形**；
 * - 改完看的是整页缩略图，28px 图标被缩到看不清，误判成"没生效"；
 * - 轮廓调细之后**心尖留了一小截锯齿**（当时判断"实尺寸看不出来"就留下了，
 *   结果需求方还是在原尺寸下看出来了 —— 所以现在连这一像素也要消掉）。
 *
 * 唯一可靠的办法：**单独放大、并排对比**。要看应用里真实渲染的控件，
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
 * 三个参数，都必须在放大图上比：
 * - `s`  缩放：决定轮廓**粗细**（(1-s)/2 ≈ 单边厚度占比）。`.7` 太粗、`.88` 接近参考图；
 * - `mt` 内层上移（%）：缩放绕心尖做，不补则上半部分（两个圆瓣）偏厚；补过头心尖会多出尖刺；
 * - `ih` **内层高度**（%）：**这次新加的参数，用来消掉心尖的锯齿**。
 *        内层与外层的高度都是 80% 时，两个"V"的顶点都在心尖上，
 *        两层在尖端附近交错 → 放大看是一小截**锯齿**（Z 字）。
 *        把内层高度减到 74~76%，它的顶点就抬到心尖之上，
 *        尖端变成**一小块实心**（本来就是心尖该有的样子），锯齿随之消失。
 */
const outline = (s, mt, ih) => `
    <i style="left:50%;top:0;width:50%;height:80%;border-radius:50% 50% 0 0;transform-origin:0% 100%;transform:rotate(-45deg);background:currentColor"></i>
    <i style="left:0;top:0;width:50%;height:80%;border-radius:50% 50% 0 0;transform-origin:100% 100%;transform:rotate(45deg);background:currentColor"></i>
    <i style="left:50%;top:0;width:50%;height:${ih}%;border-radius:50% 50% 0 0;transform-origin:0% 100%;transform:rotate(-45deg) scale(${s});background:#ffffff;margin-top:${mt}%"></i>
    <i style="left:0;top:0;width:50%;height:${ih}%;border-radius:50% 50% 0 0;transform-origin:100% 100%;transform:rotate(45deg) scale(${s});background:#ffffff;margin-top:${mt}%"></i>`

const filled = `
    <i style="left:50%;top:0;width:50%;height:80%;border-radius:50% 50% 0 0;transform-origin:0% 100%;transform:rotate(-45deg);background:currentColor"></i>
    <i style="left:0;top:0;width:50%;height:80%;border-radius:50% 50% 0 0;transform-origin:100% 100%;transform:rotate(45deg);background:currentColor"></i>`

const CANDIDATES = {
  'N 现用 s=.88 mt=-2 ih=80（心尖有锯齿）': outline(0.88, -2, 80),
  'O s=.88 mt=-2 ih=74': outline(0.88, -2, 74),
  'P s=.88 mt=-2 ih=76': outline(0.88, -2, 76),
  'Q s=.86 mt=-3 ih=74': outline(0.86, -3, 74),
  'R 实心（对照，无锯齿）': filled,
}

const html = `<!doctype html><html><body style="margin:0;background:#fff;font:12px sans-serif">
<div style="display:flex;flex-wrap:wrap;gap:18px;padding:18px">
${Object.entries(CANDIDATES)
  .map(
    ([name, shapes]) => `
  <div style="width:132px">
    <div style="position:relative;width:120px;height:120px;color:#4e5969">${shapes}</div>
    <div style="margin-top:6px;color:#333">${name}</div>
  </div>`
  )
  .join('')}
</div>
<style>i{position:absolute;display:block;box-sizing:border-box}</style>
</body></html>`

const browser = await chromium.launch({ channel: 'chrome' })
const page = await browser.newPage({ viewport: { width: 900, height: 330 } })
await page.setContent(html)
await page.waitForTimeout(300)
await page.screenshot({ path: `${OUT}/hearts.png`, fullPage: true })
console.log(`shot: ${OUT}/hearts.png`)
await browser.close()
