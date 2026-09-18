/**
 * 图标试验台：把候选的图形画法**放大到 120px** 并截图，用来挑形状。
 *
 * 为什么需要它：在列表卡片上看 28px 的图标根本判断不出形状对不对
 * （本机踩过：我把"两个圆 + 旋转方块"当心形，方块过大 → 实际画出来是个**盾形**，
 * 而 28px 的截图里我以为"差不多"）。放大对比是唯一靠谱的办法。
 *
 * 用法：node scripts/icon-lab.mjs
 */
import { chromium } from '@playwright/test'
import { mkdirSync } from 'node:fs'

const OUT = '.tmp/iconlab'
mkdirSync(OUT, { recursive: true })

/** 每个候选 = 一段"形状" HTML（与 HyIcon 的内部结构一致：绝对定位的小盒子） */
const CANDIDATES = {
  'A-两个圆+旋转方块（旧画法，盾形）': `
    <i style="left:8%;top:26%;width:50%;height:50%;border-radius:50%;background:currentColor"></i>
    <i style="left:42%;top:26%;width:50%;height:50%;border-radius:50%;background:currentColor"></i>
    <i style="left:50%;top:50%;width:60%;height:60%;margin-left:-30%;transform:rotate(45deg);background:currentColor"></i>`,

  'B-经典墓碑×2（实心）': `
    <i style="left:50%;top:0;width:50%;height:80%;border-radius:50% 50% 0 0;transform-origin:0% 100%;transform:rotate(-45deg);background:currentColor"></i>
    <i style="left:0;top:0;width:50%;height:80%;border-radius:50% 50% 0 0;transform-origin:100% 100%;transform:rotate(45deg);background:currentColor"></i>`,

  'C-经典墓碑×2（描边）': `
    <i style="left:50%;top:0;width:50%;height:80%;border-radius:50% 50% 0 0;transform-origin:0% 100%;transform:rotate(-45deg);border:2px solid currentColor"></i>
    <i style="left:0;top:0;width:50%;height:80%;border-radius:50% 50% 0 0;transform-origin:100% 100%;transform:rotate(45deg);border:2px solid currentColor"></i>`,

  'D-实心+白心内缩 scale .78（挖空）': `
    <i style="left:50%;top:0;width:50%;height:80%;border-radius:50% 50% 0 0;transform-origin:0% 100%;transform:rotate(-45deg);background:currentColor"></i>
    <i style="left:0;top:0;width:50%;height:80%;border-radius:50% 50% 0 0;transform-origin:100% 100%;transform:rotate(45deg);background:currentColor"></i>
    <i style="left:50%;top:0;width:50%;height:80%;border-radius:50% 50% 0 0;transform-origin:0% 100%;transform:rotate(-45deg) scale(.78);background:#ffffff"></i>
    <i style="left:0;top:0;width:50%;height:80%;border-radius:50% 50% 0 0;transform-origin:100% 100%;transform:rotate(45deg) scale(.78);background:#ffffff"></i>`,

  'F-同 D 但 scale .62': `
    <i style="left:50%;top:0;width:50%;height:80%;border-radius:50% 50% 0 0;transform-origin:0% 100%;transform:rotate(-45deg);background:currentColor"></i>
    <i style="left:0;top:0;width:50%;height:80%;border-radius:50% 50% 0 0;transform-origin:100% 100%;transform:rotate(45deg);background:currentColor"></i>
    <i style="left:50%;top:0;width:50%;height:80%;border-radius:50% 50% 0 0;transform-origin:0% 100%;transform:rotate(-45deg) scale(.62);background:#ffffff"></i>
    <i style="left:0;top:0;width:50%;height:80%;border-radius:50% 50% 0 0;transform-origin:100% 100%;transform:rotate(45deg) scale(.62);background:#ffffff"></i>`,

  'G-同 D 但先上移再缩放（补内缩偏心）': `
    <i style="left:50%;top:0;width:50%;height:80%;border-radius:50% 50% 0 0;transform-origin:0% 100%;transform:rotate(-45deg);background:currentColor"></i>
    <i style="left:0;top:0;width:50%;height:80%;border-radius:50% 50% 0 0;transform-origin:100% 100%;transform:rotate(45deg);background:currentColor"></i>
    <i style="left:50%;top:0;width:50%;height:80%;border-radius:50% 50% 0 0;transform-origin:0% 100%;transform:rotate(-45deg) scale(.7);background:#ffffff;margin-top:-9%"></i>
    <i style="left:0;top:0;width:50%;height:80%;border-radius:50% 50% 0 0;transform-origin:100% 100%;transform:rotate(45deg) scale(.7);background:#ffffff;margin-top:-9%"></i>`,

  'E-两圆+方块（挖空，方块缩小）': `
    <i style="left:12%;top:30%;width:38%;height:38%;border-radius:50%;background:currentColor"></i>
    <i style="left:50%;top:30%;width:38%;height:38%;border-radius:50%;background:currentColor"></i>
    <i style="left:50%;top:52%;width:44%;height:44%;margin-left:-22%;transform:rotate(45deg);background:currentColor"></i>`,
}

const html = `<!doctype html><html><body style="margin:0;background:#fff;font:12px sans-serif">
<div style="display:flex;flex-wrap:wrap;gap:24px;padding:24px">
${Object.entries(CANDIDATES)
  .map(
    ([name, shapes]) => `
  <div style="width:180px">
    <div style="position:relative;width:120px;height:120px;color:#86909c">
      ${shapes}
    </div>
    <div style="margin-top:8px;color:#333">${name}</div>
  </div>`
  )
  .join('')}
</div>
<style>i{position:absolute;display:block;box-sizing:border-box}</style>
</body></html>`

const browser = await chromium.launch({ channel: 'chrome' })
const page = await browser.newPage({ viewport: { width: 1100, height: 420 } })
await page.setContent(html)
await page.waitForTimeout(400)
await page.screenshot({ path: `${OUT}/hearts.png`, fullPage: true })
console.log(`shot: ${OUT}/hearts.png`)
await browser.close()
