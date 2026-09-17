/**
 * 网络可达性探针（**从浏览器里测**，不是从 shell 测）。
 *
 * 为什么必须在浏览器里测：
 *   图片上传是**浏览器直传 OSS**（铁律 8），所以"OSS 能不能访问"这件事
 *   只取决于**浏览器的网络**。本会话里 shell 的 curl 对所有外部域名都返回 000，
 *   但这**不能**推断浏览器也不行 —— 两者可能是不同的网络策略。
 *   （上一轮我就是拿 shell 的 curl 结论当成了全局结论，被 L1 更正过。）
 *
 * 用法：node web/scripts/net-probe.mjs
 */
import { chromium } from '@playwright/test'

const TARGETS = [
  'https://example.com',
  'https://hy-forum-2026.oss-cn-beijing.aliyuncs.com/',
  'https://help.aliyun.com',
]

const browser = await chromium.launch({ channel: 'chrome' })
const page = await browser.newPage()

for (const url of TARGETS) {
  let line = `${url} -> `
  try {
    const res = await page.goto(url, { waitUntil: 'commit', timeout: 20000 })
    line += `HTTP ${res ? res.status() : '(no response)'}`
  } catch (e) {
    line += `FAILED: ${String(e.message).split('\n')[0]}`
  }
  console.log(line)
}

/* 顺带测一次"浏览器里发 XMLHttpRequest 到 OSS"（上传走的就是这条通道），只看能不能连上 */
const xhrResult = await page.evaluate(async () => {
  return await new Promise((resolve) => {
    const x = new XMLHttpRequest()
    x.open('GET', 'https://hy-forum-2026.oss-cn-beijing.aliyuncs.com/', true)
    x.onload = () => resolve(`status=${x.status}`)
    x.onerror = () => resolve('xhr error（通常是连通性问题或 CORS 预检失败）')
    x.ontimeout = () => resolve('timeout')
    x.send()
  })
})
console.log('OSS via XHR ->', xhrResult)

await browser.close()
