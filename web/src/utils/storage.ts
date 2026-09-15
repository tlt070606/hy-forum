/**
 * 跨端本地存储封装。
 *
 * ==========================================================================
 * 为什么必须封装，不许直接调 uni.setStorageSync 或 localStorage
 * ==========================================================================
 * 1. **小程序端没有 `localStorage`**（无 DOM）。直接用它会在小程序端直接报错。
 * 2. 各端存储能力有差异（容量、同步/异步、是否支持复杂对象），
 *    把差异**集中在这一个文件**里，代码就不会到处分叉
 *    —— 这是 docs/adr/0011「平台差异必须集中封装」的直接落地。
 *
 * 目前三端都用 `uni.*StorageSync`（uni-app 已做跨端抽象，H5 端底层即 localStorage），
 * 因此暂时没有 `#ifdef`。**保留这层封装的价值在于**：将来某端需要特殊处理
 * （例如 H5 想换成带有过期时间的 sessionStorage 策略），只改这里。
 */

/** 存储键名集中定义，避免字符串散落各处导致拼写漂移 */
export const STORAGE_KEYS = {
  /** 前台登录 token（Sa-Token）。请求头 Authorization 的值 */
  TOKEN: 'hy:token',
  /** 缓存的当前用户信息，用于冷启动时先渲染再校准 */
  USER_INFO: 'hy:user',
  /** 用户已勾选的「同意协议」状态，减少重复勾选 */
  AGREED_PROTOCOL: 'hy:agreed-protocol',
} as const

/**
 * 读取字符串。键不存在时返回 `defaultValue`。
 *
 * 边界处理：历史数据可能不是字符串（例如早期版本存过对象），
 * 这里强制转字符串，避免调用方拿到 [object Object] 之后难以定位。
 */
export function getString(key: string, defaultValue = ''): string {
  try {
    const v = uni.getStorageSync(key)
    if (v === '' || v === null || v === undefined) return defaultValue
    return typeof v === 'string' ? v : String(v)
  } catch (e) {
    // 存储读取失败不应让页面崩掉（例如小程序隐私策略限制），降级为默认值
    console.warn(`[storage] 读取失败 key=${key}`, e)
    return defaultValue
  }
}

/**
 * 写入字符串。
 *
 * 注意：显式拒绝写入 `undefined`/`null`，因为 uni-app 各端对它们的处理不一致
 * （H5 会存成字符串 "undefined"），那是很难查的 bug。需要"清空"请用 `remove()`。
 */
export function setString(key: string, value: string): void {
  if (value === undefined || value === null) {
    console.warn(`[storage] 拒绝写入 ${value}，如需清空请调用 remove() key=${key}`)
    return
  }
  try {
    uni.setStorageSync(key, value)
  } catch (e) {
    console.warn(`[storage] 写入失败 key=${key}`, e)
  }
}

/**
 * 读取 JSON 对象。解析失败或不存在时返回 `defaultValue`。
 *
 * 为什么吞掉异常而不是抛出：存储里的脏数据（用户手工改过、旧版本遗留）
 * 不该导致整个页面白屏。解析失败时返回默认值并告警，行为可预期。
 */
export function getObject<T>(key: string, defaultValue: T): T {
  const raw = getString(key, '')
  if (!raw) return defaultValue
  try {
    const parsed = JSON.parse(raw)
    // null 是合法 JSON 但不是我们想要的"对象"，按缺失处理
    return parsed === null ? defaultValue : (parsed as T)
  } catch (e) {
    console.warn(`[storage] JSON 解析失败，按默认值处理 key=${key}`, e)
    return defaultValue
  }
}

/** 写入 JSON 对象 */
export function setObject(key: string, value: unknown): void {
  setString(key, JSON.stringify(value))
}

/** 删除单个键 */
export function remove(key: string): void {
  try {
    uni.removeStorageSync(key)
  } catch (e) {
    console.warn(`[storage] 删除失败 key=${key}`, e)
  }
}

/**
 * 清空本应用的所有存储。
 *
 * ⚠️ 不要用它做"退出登录"：那会连带清掉用户勾选的协议状态等无关数据。
 * 退出登录请用 `clearAuth()`。
 */
export function clearAll(): void {
  try {
    uni.clearStorageSync()
  } catch (e) {
    console.warn('[storage] 清空失败', e)
  }
}

/** 只清理鉴权相关的键（退出登录、token 失效时使用） */
export function clearAuth(): void {
  remove(STORAGE_KEYS.TOKEN)
  remove(STORAGE_KEYS.USER_INFO)
}
