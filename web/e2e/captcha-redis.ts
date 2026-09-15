/**
 * 验证码答案读取（E2E 专用辅助）。
 *
 * ==========================================================================
 * 为什么需要它：图形验证码是 E2E 的天然障碍
 * ==========================================================================
 * 图形验证码的设计目的就是**阻止自动化**。E2E 要跑通"注册"这条真实链路，
 * 就必须拿到验证码答案。
 *
 * 可选方案与取舍：
 * ① 让后端加"测试模式跳过验证码" —— **不行**。任务书 §3 明确「禁止修改 server/**」，
 *    且后端已交付并通过复核，为前端测试改后端是本末倒置。
 * ② 图像 OCR 识别 —— 不稳定。验证码含干扰线与扭曲，OCR 准确率不足以支撑可靠的 E2E，
 *    会变成一个时不时变红的"抖动测试"，比没有更糟。
 * ③ **直读 Redis 答案**（本方案）—— 答案就存在 Redis 里
 *    （《技术方案》§6.2：`hy:captcha:{uuid}`，TTL 300s），
 *    直接读取即可，**后端零改动**，且 100% 确定。
 *
 * 方案 ③ 的边界必须说清：它读取的是**测试环境**的 Redis，仅用于本地 E2E；
 * 生产环境的 Redis 不应允许这样访问，也不会有这个脚本跑在生产上。
 *
 * ⚠️ 前提：后端配置的 Redis 必须是本辅助能连上的那个实例。
 *    本机后端连的是 VMware 里的 `hy-redis`（`192.168.100.128:6380`），
 *    见 `docs/agents/工作计划.md` 的 D6 决策与 `docs/ops/deployment.md` §1.1。
 *    连不上时本辅助会**明确报错并给出排查指引**，不会静默返回空答案
 *    （静默失败会让 E2E 报"验证码错误"，把根因藏起来）。
 */

import { createConnection } from 'node:net'

/** Redis 连接参数。可用环境变量覆盖，默认对齐本机后端配置 */
const REDIS_HOST = process.env.E2E_REDIS_HOST || '192.168.100.128'
const REDIS_PORT = Number(process.env.E2E_REDIS_PORT || 6380)

/**
 * 极简 RESP 客户端：只实现 `GET` 与 `TTL`。
 *
 * 为什么不引 `ioredis` / `redis`：只需两条命令，引一个完整客户端属过度依赖；
 * 且 RESP 协议本身极其简单，手写几十行比多一个依赖更可控。
 */

/** 把命令按 RESP 数组格式编码（`*N\r\n$len\r\narg\r\n...`） */
function encodeCommand(args: string[]): Buffer {
  const parts: string[] = [`*${args.length}\r\n`]
  for (const a of args) {
    // 用 Buffer.byteLength 而不是 a.length：中文/多字节字符的字节数才是 RESP 要的长度
    parts.push(`$${Buffer.byteLength(a, 'utf8')}\r\n${a}\r\n`)
  }
  return Buffer.from(parts.join(''), 'utf8')
}

/** 发送一条命令并解析回复（支持简单字符串、错误、整数、批量字符串） */
function sendCommand(socket: import('node:net').Socket, args: string[]): Promise<string | null> {
  return new Promise((resolve, reject) => {
    let buffer = Buffer.alloc(0)

    const onData = (chunk: Buffer) => {
      buffer = Buffer.concat([buffer, chunk])
      const text = buffer.toString('utf8')

      // 回复至少要有一个完整行
      const lineEnd = text.indexOf('\r\n')
      if (lineEnd < 0) return

      const line = text.slice(0, lineEnd)
      const type = line[0]
      const payload = line.slice(1)

      if (type === '-') {
        // 错误回复，例如 -ERR unknown command
        cleanup()
        reject(new Error(`Redis 返回错误：${payload}`))
        return
      }
      if (type === '+' || type === ':') {
        cleanup()
        resolve(payload)
        return
      }
      if (type === '$') {
        const len = Number(payload)
        if (len === -1) {
          // $-1 表示 nil（键不存在）
          cleanup()
          resolve(null)
          return
        }
        // 批量字符串：还需等 len 字节 + 结尾 CRLF
        const bodyStart = lineEnd + 2
        if (buffer.length < bodyStart + len + 2) return
        const value = buffer.subarray(bodyStart, bodyStart + len).toString('utf8')
        cleanup()
        resolve(value)
        return
      }
      cleanup()
      reject(new Error(`无法解析的 Redis 回复：${line}`))
    }

    const onError = (err: Error) => {
      cleanup()
      reject(err)
    }

    function cleanup() {
      socket.off('data', onData)
      socket.off('error', onError)
    }

    socket.on('data', onData)
    socket.on('error', onError)
    socket.write(encodeCommand(args))
  })
}

/** 建立连接（带超时，避免连不上时测试挂死） */
function connect(): Promise<import('node:net').Socket> {
  return new Promise((resolve, reject) => {
    const socket = createConnection({ host: REDIS_HOST, port: REDIS_PORT })
    const timer = setTimeout(() => {
      socket.destroy()
      reject(
        new Error(
          `连接 Redis 超时（${REDIS_HOST}:${REDIS_PORT}）。\n` +
            '  排查：① 该实例是否在运行；② 后端是否连的是同一实例；\n' +
            '  ③ 可用 E2E_REDIS_HOST / E2E_REDIS_PORT 环境变量覆盖。\n' +
            '  参考 docs/agents/工作计划.md 的 D6 决策。'
        )
      )
    }, 5000)

    socket.once('connect', () => {
      clearTimeout(timer)
      resolve(socket)
    })
    socket.once('error', (err) => {
      clearTimeout(timer)
      reject(
        new Error(
          `连接 Redis 失败（${REDIS_HOST}:${REDIS_PORT}）：${err.message}\n` +
            '  该实例应为本机后端使用的 Redis（见 docs/ops/deployment.md §1.1）。'
        )
      )
    })
  })
}

/**
 * 按 uuid 读取图形验证码的答案。
 *
 * @param uuid 由 `GET /api/auth/captcha` 返回的 uuid
 * @returns 验证码答案；键不存在（已过期或被消费）时返回 null
 */
export async function readCaptchaAnswer(uuid: string): Promise<string | null> {
  const socket = await connect()
  try {
    // key 名依据《技术方案》§6.2：hy:captcha:{uuid}
    return await sendCommand(socket, ['GET', `hy:captcha:${uuid}`])
  } finally {
    socket.destroy()
  }
}

/**
 * 读取验证码键的剩余 TTL（秒）。
 * 用于验证"答案确实存在于 Redis 且 TTL 合理"这一前提，
 * 以及在调试 E2E 时判断是否只是过期了。
 */
export async function readCaptchaTtl(uuid: string): Promise<number | null> {
  const socket = await connect()
  try {
    const raw = await sendCommand(socket, ['TTL', `hy:captcha:${uuid}`])
    return raw === null ? null : Number(raw)
  } finally {
    socket.destroy()
  }
}
