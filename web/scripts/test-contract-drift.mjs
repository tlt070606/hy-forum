/**
 * 契约漂移检测的**变异测试**（证明检测规则真的在跑，而不是永远返回"通过"）。
 *
 * ==========================================================================
 * 为什么必须有这个测试
 * ==========================================================================
 * 一条永远返回"通过"的校验规则毫无价值，而且比没有更危险 ——
 * 它会让人以为契约被守护着。本项目已有同思路的先例：
 * `docs/agents/工作计划.md` §6.5.1「ARCH_no_cross_module_dependency 有防假绿前置断言」。
 *
 * ==========================================================================
 * 两个踩过的坑（都写在这里，避免后人重犯）
 * ==========================================================================
 * 1. **不要用 PowerShell 改快照做实验**：`Set-Content -Encoding UTF8` 会写入 BOM，
 *    于是 check-contract 先报"JSON 非法"，测到的是编码问题而不是漂移检测 ——
 *    实验结论会是错的。本脚本一律用 Node 读写文件。
 * 2. **不要用 execFileSync 调子进程**：在受限沙箱下 `spawnSync ... EPERM`，
 *    子进程根本没跑，`e.stdout` 是 undefined，于是会**误报"规则失效"**。
 *    本脚本改为**同进程 import 调用** `checkContract()`，不 spawn，
 *    既避开了权限限制，也让断言真正作用在被测函数上。
 *
 * 用法：node scripts/test-contract-drift.mjs
 * 退出码：0 = 变异被成功捕获（规则有效）；1 = 未捕获（规则是假绿，不可信）
 *
 * ==========================================================================
 * 2026-09-16 起它会连带报出两条「contract.ts 常量不符」的问题 —— 这是**预期**的
 * ==========================================================================
 * 快照被注入假路径后，它的 SHA256 与路径数自然不再等于 `src/api/contract.ts` 里
 * 手工记录的那两个常量，于是 `checkDeclaredConstants` 也会报漂移。
 * **不要**因此把它们当成干扰去掉：这恰好证明那条检查在跑。
 * 断言口径没变 —— 仍然是「`ok=false` 且 problems 里点名了假路径」。
 */

import { readFileSync, unlinkSync, writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

import { checkContract } from './check-contract.mjs'

const __dirname = dirname(fileURLToPath(import.meta.url))
const SNAPSHOT = resolve(__dirname, '../src/api/generated/openapi.snapshot.json')
const BACKUP = resolve(__dirname, '../.contract-snapshot.bak')

/** 注入的假路径：后端当前**没有**这个接口（`/api/__drift_probe__` 不在契约的 12 个路径里） */
const FAKE_PATH = '/api/__drift_probe__'

console.log('=== 契约漂移检测 · 变异测试 ===\n')

const original = readFileSync(SNAPSHOT, 'utf8')
writeFileSync(BACKUP, original, 'utf8')

let caught = false
try {
  /* ---- 基线：未变异时应判定为一致（否则说明环境有问题，非规则问题） ---- */
  const baseline = await checkContract({})
  console.log(`基线（未变异）ok = ${baseline.ok}（期望 true）`)
  if (!baseline.ok) {
    console.error('✗ 基线就失败了，说明后端未启动或契约本就不一致 —— 本次变异测试无法得出结论。')
    console.error(baseline.problems.join('\n'))
    process.exit(1)
  }

  /* ---- 注入变异 ---- */
  const marker = '"paths":{'
  const idx = original.indexOf(marker)
  if (idx < 0) {
    console.error('✗ 快照里找不到 "paths":{ ，无法注入，测试无效')
    process.exit(1)
  }
  const mutated =
    original.slice(0, idx + marker.length) +
    `"${FAKE_PATH}":{"get":{"operationId":"driftProbe"}},` +
    original.slice(idx + marker.length)

  writeFileSync(SNAPSHOT, mutated, 'utf8')
  console.log(`\n已注入假路径: ${FAKE_PATH}`)

  /* ---- 断言：必须报出漂移，且点名该假路径 ---- */
  const result = await checkContract({})
  const namedInProblems = result.problems.some((p) => p.includes(FAKE_PATH))
  caught = !result.ok && namedInProblems

  console.log(`变异后 ok = ${result.ok}（期望 false）`)
  console.log(`problems 里点名该假路径 = ${namedInProblems}（期望 true）`)
  if (result.problems.length) {
    console.log('problems 内容：')
    result.problems.forEach((p) => console.log(`  - ${p}`))
  }

  console.log('')
  if (caught) {
    console.log('✓ 变异被捕获：契约漂移检测规则**真的在检查东西**。')
  } else {
    console.error('✗ 变异未被捕获 —— 该检测规则是假绿，不可信！')
  }
} finally {
  // 无条件还原，保证无论断言成败都不留污染
  writeFileSync(SNAPSHOT, original, 'utf8')
  try {
    unlinkSync(BACKUP)
  } catch {
    /* 备份可能不存在，忽略 */
  }
  const restored = readFileSync(SNAPSHOT, 'utf8')
  console.log(`\n快照已还原且逐字一致: ${restored === original}`)
}

/*
 * ⚠️ 用 `process.exitCode` 而不是 `process.exit()`。
 * `checkContract` 内部用了 `fetch`，会留下未关闭的 keep-alive socket；
 * 硬杀进程会在 Windows 上触发 libuv 断言
 * （Assertion failed: !(handle->flags & UV_HANDLE_CLOSING)）并给出崩溃码，
 * 让"变异被捕获"这个正确结论配上一个看起来像失败的退出码。
 */
process.exitCode = caught ? 0 : 1
