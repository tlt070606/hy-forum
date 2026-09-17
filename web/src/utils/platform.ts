/**
 * 剪贴板与外部链接的跨端封装。
 *
 * ==========================================================================
 * 为什么单独成模块
 * ==========================================================================
 * 这是三端**真实存在差异**的地方，ADR-0011 要求「平台差异必须集中封装，不能散落各处」。
 *
 * 差异清单（M3 资源版块的网盘复制/跳转会用到，M2 先建好封装避免届时分叉）：
 * | 能力 | H5 | 小程序 | App |
 * |---|---|---|---|
 * | 复制文本 | navigator.clipboard / 降级 execCommand | uni.setClipboardData | uni.setClipboardData |
 * | 打开外部链接 | window.open 或 location.href | **不能用 web-view（C1），只能提示用户复制** | plus.runtime.openURL |
 *
 * 关键约束（ADR-0011 C1）：**个人主体小程序不支持 `web-view`**，
 * 所以小程序端打开网盘链接的**唯一可行方式是把链接复制到剪贴板，让用户自行去浏览器打开**。
 * 这个限制在这里被表达成 `openExternalLink` 的返回值，让调用方能给出正确提示文案。
 */

/** 复制结果，供调用方决定提示文案 */
export interface CopyResult {
  ok: boolean
  /** 失败原因（仅用于日志/调试，不要直接展示给用户） */
  reason?: string
}

/**
 * 复制文本到剪贴板。
 *
 * 统一用 `uni.setClipboardData`：uni-app 在 H5 端已做了降级处理
 * （优先 `navigator.clipboard`，不支持时用隐藏 textarea + execCommand），
 * 因此这里不需要自己写 `#ifdef H5` 分支。
 *
 * ⚠️ 小程序的 `setClipboardData` 会**自动弹出系统提示**「内容已复制」。
 * 因此调用方在小程序端**不要再追加自定义 toast**，否则会出现两个提示。
 * 用 `shouldShowCustomToast()` 判断。
 */
export function copyText(text: string): Promise<CopyResult> {
  return new Promise((resolve) => {
    if (!text) {
      resolve({ ok: false, reason: 'empty-text' })
      return
    }
    uni.setClipboardData({
      data: text,
      success: () => resolve({ ok: true }),
      fail: (err) => {
        console.warn('[clipboard] 复制失败', err)
        resolve({ ok: false, reason: String(err?.errMsg ?? err) })
      },
    })
  })
}

/**
 * 本端复制后是否需要应用自己再弹一次提示。
 *
 * - 小程序：系统自带提示 → 返回 false
 * - H5 / App：无自带提示 → 返回 true
 */
export function shouldShowCustomToastAfterCopy(): boolean {
  // #ifdef MP-WEIXIN
  return false
  // #endif
  // eslint-disable-next-line no-unreachable
  return true
}

/**
 * 打开外部链接（例如网盘地址）。
 *
 * @returns `true` 表示已交给系统打开；`false` 表示本端**无法打开**，
 *          调用方应改为提示用户「链接已复制，请到浏览器打开」。
 *
 * 这是本项目最容易写错的地方之一：在小程序端如果直接 `window.open`，
 * 会报 `window is not defined`；如果侥幸不报错，也**打不开**（无 web-view 权限）。
 * 因此这里显式返回 false 让调用方处理，而不是抛异常。
 */
export function openExternalLink(url: string): boolean {
  if (!url) return false

  // #ifdef H5
  // H5 端：新窗口打开。noopener 防止目标页通过 window.opener 反向控制本页（安全）
  window.open(url, '_blank', 'noopener,noreferrer')
  return true
  // #endif

  // #ifdef APP-PLUS
  // App 端：交给系统浏览器/对应 App 处理
  plus.runtime.openURL(url)
  return true
  // #endif

  // #ifdef MP-WEIXIN
  // 小程序端：个人主体不支持 web-view（ADR-0011 C1），无法打开外部链接。
  // 调用方应改为「复制链接 + 提示用户自行打开」。
  return false
  // #endif

  // 兜底（其它小程序平台同样不支持直接打开外链）
  return false
}
