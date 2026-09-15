/**
 * 探针脚本：验证「E2E 能读到图形验证码答案」这一前提是否成立。
 *
 * 为什么单独写这个探针：E2E 的注册用例依赖它。若这条前提不成立，
 * 注册用例会以"验证码错误"这种**误导性**的方式失败，
 * 把真正的问题（Redis 连不上 / key 名不对）藏起来。
 * 先独立验证前提，再让 E2E 依赖它 —— 这是本项目 testing §5.1 的思路。
 *
 * 用法：node e2e/probe-captcha.ts
 */

import { readCaptchaAnswer, readCaptchaTtl } from './captcha-redis.ts'

const BASE = process.env.E2E_API_BASE || 'http://127.0.0.1:8080'

console.log('=== 探针：验证码答案可达性 ===\n')

// 1) 取一个验证码
const res = await fetch(`${BASE}/api/auth/captcha`)
if (!res.ok) {
  console.error(`✗ GET /api/auth/captcha 返回 HTTP ${res.status}`)
  process.exitCode = 1
} else {
  const body = await res.json()
  const uuid = body?.data?.uuid
  const img = body?.data?.base64Image

  console.log(`uuid        : ${uuid}`)
  console.log(`base64 前缀 : ${String(img).slice(0, 30)}...`)
  console.log(`base64 长度 : ${String(img).length}`)

  if (!uuid) {
    console.error('✗ 响应里没有 uuid —— 契约形状可能变了')
    process.exitCode = 1
  } else {
    // 2) 直读 Redis
    try {
      const ttl = await readCaptchaTtl(uuid)
      const answer = await readCaptchaAnswer(uuid)
      console.log(`\nRedis TTL   : ${ttl} 秒`)
      console.log(`Redis 答案  : ${JSON.stringify(answer)}`)

      if (answer === null) {
        console.error('\n✗ 键不存在或已过期 —— 无法用直读 Redis 的方式驱动 E2E 注册')
        process.exitCode = 1
      } else {
        console.log('\n✓ 前提成立：可以通过直读 Redis 拿到验证码答案。')
        console.log('  E2E 的注册用例可以依赖此机制，且**后端零改动**。')
      }
    } catch (e) {
      console.error(`\n✗ 读 Redis 失败：${e instanceof Error ? e.message : e}`)
      process.exitCode = 1
    }
  }
}
