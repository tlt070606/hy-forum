/**
 * 图片直传 OSS（PostObject 表单直传）。
 *
 * ==========================================================================
 * 铁律 8：字节流**由前端直传 OSS，不经后端**。铁律 5：前端**绝不持有 AccessKey Secret**。
 * ==========================================================================
 * 所以做法只有一条：**向服务端要签名 → 原样填表 → POST 到 OSS**。
 * `policy` / `signature` / `OSSAccessKeyId` / `callback` **全部取自 `GET /api/oss/signature`
 * 的响应**，前端一个字节都不自己编（任务书 §3.1 明令）。
 *
 * ==========================================================================
 * 表单长什么样（字段名与顺序都不是我定的，是 OSS PostObject 规范 + 契约共同决定的）
 * ==========================================================================
 * | 表单字段 | 值从哪来 | 注 |
 * |---|---|---|
 * | `key` | `dir` + 文件名 | `dir` 带尾斜杠（CR-009 已定），所以直接字符串相接 |
 * | `policy` | 响应原样 | |
 * | `signature` | 响应原样 | |
 * | `OSSAccessKeyId` | 响应的 `accessKeyId` | **契约 description 原文就写了这个表单字段名**，不是猜的 |
 * | `callback` | 响应原样 | Base64 的回调配置，**不要自己拼 JSON** |
 * | `Content-Type` | 见下（类型解析） | policy 里有一条 `starts-with $Content-Type image/` |
 * | `file` | 文件本身 | **必须是最后一个字段**，否则 OSS 报错 |
 *
 * ⚠️ **字段顺序**：`uni.uploadFile` 在 H5 端的实现是"先把 `formData` 全部 append，最后 append 文件"
 * （`@dcloudio/uni-h5` 的 `uploadFile`：先 `Object.keys(formData).forEach(append)`，再 `form.append(name, file)`）——
 * 正好满足 OSS 的要求，所以不需要自己写 XHR。
 *
 * ⚠️ **为什么必须显式给 `Content-Type` 表单字段**：policy 里有条件
 * `["starts-with", "$Content-Type", "image/"]`。OSS 校验的是**表单字段** `Content-Type`，
 * 不是 multipart 里文件分片自带的那个。不给它 → 策略校验不通过 → 上传被拒。
 *
 * ==========================================================================
 * ⚠️ 类型判定为什么不能只看扩展名（本机实测的坑）
 * ==========================================================================
 * H5 的 `uni.chooseImage` 返回的 `tempFilePaths` 是 **`blob:` URL**（形如
 * `blob:http://127.0.0.1:5173/<uuid>`）——**它没有扩展名**。
 * 第一版按扩展名判类型，于是**每一张图都被自己的预检拦下**，界面显示
 * 「只支持 .jpg / .jpeg / .png / .webp / .gif 格式的图片」，而用户选的明明就是 PNG。
 *
 * 正确做法（顺序不能反）：
 * 1. **先看 `tempFiles[i].type`** —— H5 端 `tempFiles` 里是**真正的 `File` 对象**
 *    （`@dcloudio/uni-h5` 的 chooseImage 把 `eventTarget.files[i]` 直接 push 进去，
 *    只是给它加了一个 `path` getter 指向 blob URL），所以 `type` 就是浏览器认定的 MIME；
 * 2. 再退回**扩展名**（取 `tempFiles[i].name` 或路径的扩展名）—— 小程序端的 `tempFiles`
 *    只有 `{path,size}`，没有 `type`，但**它的 path 是带扩展名的**（`wxfile://tmp_xxx.jpg`）。
 * 两条合起来才覆盖三端。
 *
 * ==========================================================================
 * 上传成功后**怎么拿到图片 URL**
 * ==========================================================================
 * OSS 回调后端 `/api/oss/callback`，后端验签落库后返回 `{code:0, data:{id, url, thumbUrl}}`
 * （**CR-G 裁决 A**），而 OSS 会把**那个响应体**原样作为本次 POST 的响应返回给前端。
 * 所以前端直接从上传响应里读 `data.url` —— **不需要自己拼 `host + dir + 文件名`**。
 */

import type { OssCallbackResultVO, OssSignatureVO } from '@/api/types'
import { ApiError } from '@/utils/request'

/** 单图大小上限（契约 policy 里是 `content-length-range 0..5242880`，《技术方案》§8.4 也写 5MB） */
export const MAX_IMAGE_BYTES = 5 * 1024 * 1024

/** 允许的图片类型（《技术方案》§8.4 的白名单）。键 = 扩展名 */
const MIME_BY_EXT: Record<string, string> = {
  jpg: 'image/jpeg',
  jpeg: 'image/jpeg',
  png: 'image/png',
  webp: 'image/webp',
  gif: 'image/gif',
}

/** 白名单里允许的 MIME 集合（用于校验 `File.type`） */
const ALLOWED_MIME = new Set(Object.values(MIME_BY_EXT))

/** `uni.chooseImage` 选出来的一项文件（已归一化，屏蔽三端差异） */
export interface LocalImage {
  /** 本地临时路径（H5 是 blob URL，小程序是 wxfile:// 之类） */
  path: string
  /** 字节数。H5 与小程序都提供；拿不到时为 undefined */
  size?: number
  /** **H5 有**：浏览器认定的 MIME（`File.type`）。小程序端没有 */
  mime?: string
  /** **H5 有**：原始文件名（`File.name`）。用于取扩展名 */
  name?: string
}

/** 上传成功的结果 */
export interface UploadedImage {
  /** `post_image.id`（后端落库后给的） */
  id: number
  /** **原图**地址，用于详情页点开看大图 */
  url: string
  /** **缩略图**地址（后端用 OSS 图片处理参数生成），列表/九宫格用它 */
  thumbUrl: string
}

/**
 * 取扩展名（小写，不含点）。
 *
 * ⚠️ **优先用 `name`**：H5 的 `path` 是 blob URL，取不到扩展名（见文件头说明）。
 */
export function extOf(img: LocalImage): string {
  const source = img.name || img.path || ''
  // 去掉 query/hash（blob URL 可能带参数），再取最后一段的扩展名
  const clean = source.split('?')[0].split('#')[0]
  const base = clean.substring(clean.lastIndexOf('/') + 1)
  const dot = base.lastIndexOf('.')
  return dot < 0 ? '' : base.substring(dot + 1).toLowerCase()
}

/**
 * 解析出**要填给 OSS 的 `Content-Type`**。解析不出来时返回 `null`（调用方据此拒绝）。
 *
 * 顺序：`File.type`（H5）→ 扩展名（小程序 / 兜底）。理由见文件头。
 */
export function resolveImageMime(img: LocalImage): string | null {
  const byBrowser = (img.mime ?? '').trim().toLowerCase()
  if (byBrowser && ALLOWED_MIME.has(byBrowser)) return byBrowser

  const byExt = MIME_BY_EXT[extOf(img)]
  if (byExt) return byExt

  return null
}

/**
 * 上传前自检：类型与大小。
 *
 * ==========================================================================
 * 为什么要在前端拦（OSS 也会拒）
 * ==========================================================================
 * OSS 的拒绝方式对用户毫无信息量：policy 校验失败只会回一个
 * `Invalid according to Policy: Policy Condition failed` 之类的 XML。
 * 用户看到的是"上传失败"，**而真正的原因（选了个 8MB 的 PNG）被埋掉**。
 * 所以这里先给出人能看懂的话。
 *
 * ⚠️ 这只是**体验优化**，不是安全边界 —— 真正的边界在 OSS 的 policy 与后端的 URL 归属校验。
 *
 * @returns 错误文案；空串表示通过
 */
export function precheckImage(img: LocalImage): string {
  if (!resolveImageMime(img)) {
    const allowed = Object.keys(MIME_BY_EXT)
      .map((e) => `.${e}`)
      .join(' / ')
    return `只支持 ${allowed} 格式的图片`
  }
  /*
   * `size` 未知时**放行**（`undefined`）而不是拦下：
   * 某些来源拿不到 size，因为拿不到就挡住用户是更坏的结果 ——
   * 让 OSS 去拒（它会依据 policy 的 content-length-range 拒），前端再把它翻译成人话。
   */
  if (typeof img.size === 'number' && img.size > MAX_IMAGE_BYTES) {
    return `图片不能超过 ${Math.round(MAX_IMAGE_BYTES / 1024 / 1024)}MB`
  }
  return ''
}

/**
 * 生成对象 key：`dir` + 文件名。
 *
 * `dir` 由服务端给（契约 CR-009：**带尾斜杠**），所以这里直接相接，**不加斜杠也不去斜杠** ——
 * 自己"顺手规范化"会在服务端改 `dir` 形态时静默拼出错误的 key。
 *
 * 文件名用「时间戳 + 随机串 + 扩展名」：
 * - 时间戳保证可读的顺序；
 * - 随机串防同毫秒并发重名（重名会**覆盖**已有对象，而不是报错 —— 静默数据丢失）；
 * - 保留扩展名，便于 OSS 侧按后缀处理图片。
 */
export function buildObjectKey(sign: OssSignatureVO, img: LocalImage): string {
  const ext = extOf(img) || 'jpg'
  const dir = sign.dir ?? ''
  return `${dir}${Date.now()}-${Math.random().toString(36).slice(2, 8)}.${ext}`
}

/**
 * 把 `uni.uploadFile` 的响应体解析成落库结果。
 *
 * 上传**成功**时响应体是后端回调的返回值（统一响应体）；**失败**时是 OSS 的错误体
 * （XML 或 JSON，形如 `<Error><Code>AccessDenied</Code>...`）。
 * 两类必须分开处理，否则会把 OSS 的错误当成"我们的 data"去读，读到 undefined 还不知道为什么。
 */
function parseUploadBody(raw: unknown, statusCode: number): UploadedImage {
  const bodyText = typeof raw === 'string' ? raw : JSON.stringify(raw ?? '')

  let parsed: { code?: number; message?: string; data?: OssCallbackResultVO } | null = null
  try {
    parsed = JSON.parse(bodyText)
  } catch {
    parsed = null
  }

  // 情况一：拿到了项目统一响应体且 code=0 → 成功
  if (parsed && typeof parsed.code === 'number') {
    if (parsed.code === 0 && parsed.data) {
      const d = parsed.data
      if (!d.url) {
        // 形状不对要显式报错：否则后面发帖时 images 里是个空串，根因被埋掉
        throw new ApiError({
          kind: 'business',
          code: -1,
          message: '上传回调没有返回图片地址，请稍后重试',
        })
      }
      return { id: d.id ?? 0, url: d.url, thumbUrl: d.thumbUrl || d.url }
    }
    throw new ApiError({
      kind: 'business',
      code: parsed.code,
      message: parsed.message || '上传失败，请稍后重试',
    })
  }

  // 情况二：OSS 的错误体（不是统一响应体）
  const ossCode = /<Code>([^<]+)<\/Code>/.exec(bodyText)?.[1]
  console.warn('[upload] OSS 返回了非统一响应体', statusCode, bodyText.slice(0, 500))

  /*
   * 给用户的文案**不包含 OSS 原文**（可能很长、含内部信息），只按 HTTP 状态与错误码
   * 给可行动的提示；原文留在控制台供排查。
   */
  /*
   * ⚠️ **这个分支是本机实测真实撞到的，必须单独认出来**。
   *
   * OSS 拒绝回调到**私有地址**时返回 `400 InvalidArgument` +
   * 「Private address is forbidden to callback」，并在 `<ArgumentValue>` 里点出那个地址。
   * 它**不是前端的问题**：后端启动时没配公网回调地址（`OSS_CALLBACK_URL`），
   * 签名里的 `callbackUrl` 于是回退成 `http://127.0.0.1:8080/api/oss/callback`，
   * OSS 在**存对象之前**就拒掉了整个上传（所以桶里不会留下垃圾对象）。
   *
   * 若把这种情况笼统显示成"上传失败，请稍后重试"，运维会一直往前端/用户身上找原因 ——
   * 而后端日志里其实已经有一条同样意思的 WARN 了（`OssCallbackUrlResolver`）。
   */
  const privateCallbackRejected = /Private address is forbidden to callback/i.test(bodyText)

  let message = '上传失败，请稍后重试'
  if (privateCallbackRejected) {
    message =
      '上传失败：后端未配置公网回调地址，OSS 拒绝回调到 127.0.0.1。' +
      '这是服务端配置问题（后端需带 OSS_CALLBACK_URL 启动，指向公网入口），请联系管理员'
  } else if (statusCode === 403 || ossCode === 'AccessDenied' || ossCode === 'SignatureDoesNotMatch') {
    // 403 常见于：policy/signature 不一致、key 前缀不符合 policy、Content-Type 不满足条件、签名过期
    message = '上传被 OSS 拒绝（可能是签名已过期或文件类型不符），请刷新后重试'
  } else if (ossCode === 'EntityTooLarge') {
    message = `图片不能超过 ${Math.round(MAX_IMAGE_BYTES / 1024 / 1024)}MB`
  } else if (ossCode) {
    message = `上传失败（${ossCode}）`
  }

  throw new ApiError({ kind: 'http', code: statusCode, httpStatus: statusCode, message })
}

/**
 * 上传一张图片到 OSS（直传），返回后端落库后的 `{id, url, thumbUrl}`。
 *
 * @param img  选中的文件（`{path, size?, mime?, name?}`）
 * @param sign `GET /api/oss/signature` 的响应（**全部字段原样使用**）
 *
 * @throws {ApiError} 预检失败 / 网络失败 / OSS 拒绝 / 回调未返回 URL
 */
export function uploadImage(img: LocalImage, sign: OssSignatureVO): Promise<UploadedImage> {
  const invalid = precheckImage(img)
  if (invalid) {
    return Promise.reject(new ApiError({ kind: 'business', code: -1, message: invalid }))
  }

  // 预检通过 ⇒ MIME 一定解析得出来（precheckImage 用的就是同一个函数）
  const mime = resolveImageMime(img) as string

  // 签名过期自检：契约里 `expire` 是**绝对时刻（epoch 秒）**（CR-009），所以直接比时间戳。
  // 提前 10 秒就判过期，避免"传的过程中刚好过期"这种边界失败。
  const expire = Number(sign.expire ?? 0)
  if (expire > 0 && Date.now() / 1000 > expire - 10) {
    return Promise.reject(
      new ApiError({ kind: 'business', code: -1, message: '上传签名已过期，请重试' })
    )
  }

  const formData: Record<string, string> = {
    // ① 对象 key：dir + 文件名（唯一，不覆盖已有对象）
    key: buildObjectKey(sign, img),
    // ②③④ 服务端给的，一个字节都不改
    policy: String(sign.policy ?? ''),
    signature: String(sign.signature ?? ''),
    // 契约 description 原文：accessKeyId 就是表单里的 OSSAccessKeyId
    OSSAccessKeyId: String(sign.accessKeyId ?? ''),
    // ⑤ 回调配置也是原样透传（不要自己拼 JSON）
    callback: String(sign.callback ?? ''),
    // ⑥ policy 里有 `starts-with $Content-Type image/`，必须显式给这个表单字段
    'Content-Type': mime,
  }

  return new Promise<UploadedImage>((resolve, reject) => {
    uni.uploadFile({
      // `host` 无尾斜杠（CR-009），直接作为 POST 目标
      url: String(sign.host ?? ''),
      filePath: img.path,
      // 文件名必须是 file（OSS PostObject 约定；uni 默认也是 file，显式写出来更清楚）
      name: 'file',
      formData,
      // 不设 header：让浏览器自己给 multipart/form-data 带 boundary
      // （手写 `Content-Type: multipart/form-data` 会因为缺 boundary 而解析失败）
      success: (res) => {
        try {
          resolve(parseUploadBody(res.data, res.statusCode ?? 0))
        } catch (e) {
          reject(e)
        }
      },
      fail: (err) => {
        const raw = String(err?.errMsg ?? '')
        console.warn('[upload] 直传失败', raw)
        reject(
          new ApiError({
            kind: 'network',
            code: -1,
            message: raw.includes('timeout') ? '上传超时，请重试' : '上传失败，请检查网络后重试',
          })
        )
      },
    })
  })
}
