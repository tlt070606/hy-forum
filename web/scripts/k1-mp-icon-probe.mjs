/**
 * K1 复现探针：小程序端 HyIcon 形状的运行时 computed style。
 *
 * 背景：帖子卡片爱心/收藏图标在 MP 端渲染为「深色实心」，H5 为「浅色描边」。
 * HyIcon 是纯 CSS 图标 —— heartOutline 靠「白色内层形状盖在实心上挖空」，
 * bookmark 靠 clip-path。哪个声明在 MP 运行时丢了，读 computed style 一目了然。
 *
 * 用法（两步，Node ≥20 禁止脚本内直接 spawn .bat，故工具要自己先起）：
 *   1) cmd 里执行： "D:\Program Files\微信web开发者工具\cli.bat" auto
 *        --project <dist/build/mp-weixin 绝对路径> --auto-port 9420
 *   2) node scripts/k1-mp-icon-probe.mjs
 */
import automator from 'miniprogram-automator'

const miniProgram = await automator.connect({ wsEndpoint: 'ws://127.0.0.1:9420' })

try {
  let page = await miniProgram.currentPage()
  console.log('当前页:', page?.path)
  await miniProgram.reLaunch('/pages/index/index')
  await new Promise((r) => setTimeout(r, 6000)) // 等首页双流拉数据
  page = await miniProgram.currentPage()

  async function probeIcon(cls) {
    const icons = await page.$$(cls)
    console.log(`\n=== ${cls} 数量: ${icons.length}`)
    if (!icons.length) return
    const shapes = await icons[0].$$('.hy-icon__s')
    console.log(`形状数: ${shapes.length}`)
    for (let i = 0; i < shapes.length; i++) {
      const s = shapes[i]
      const bg = await s.style('background-color')
      const tf = await s.style('transform')
      const h = await s.style('height')
      const mt = await s.style('margin-top')
      const cp = await s.style('clip-path')
      console.log(
        `  shape[${i}] background=${bg} | transform=${tf} | height=${h} | margin-top=${mt} | clip-path=${cp}`,
      )
    }
  }

  await probeIcon('.hy-icon--heartOutline')
  await probeIcon('.hy-icon--bookmark')
  await probeIcon('.hy-icon--heartFilled')

  await miniProgram.screenshot({ path: 'C:/Users/tlt20/AppData/Local/Temp/k1_mp.png' })
  console.log('\n截图: C:/Users/tlt20/AppData/Local/Temp/k1_mp.png')
} finally {
  // 不关工具，保留现场给人工看；断开自动化即可
  await miniProgram.disconnect?.()
  process.exit(0)
}
