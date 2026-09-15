/**
 * 契约漂移检测脚本。
 *
 * ==========================================================================
 * 为什么需要它（这是本工程最关键的一道防线）
 * ==========================================================================
 * 本前端是**独立仓库**，无法在构建时读取后端仓库的 `openapi.json`，
 * 因此工程内保存了一份**快照**（`src/api/generated/openapi.snapshot.json`）
 * 并据此生成 TypeScript 类型。
 *
 * 风险很明确：**后端接口变了，前端快照没更新**，于是前端基于过期契约编译通过、
 * 运行时对接失败。这类问题在联调时才暴露，代价最高。
 *
 * 本脚本把"快照是否过期"变成一条**可执行的命令**，而不是靠人记得去比对。
 *
 * ==========================================================================
 * 用法
 * ==========================================================================
 *   node scripts/check-contract.mjs                    # CLI：对比本机后端
 *   node scripts/check-contract.mjs http://host:8080   # CLI：对比指定后端
 *
 * 也可作为模块导入（**变异测试用这种方式，避免 spawn 子进程**）：
 *   import { checkContract, fingerprint } from './check-contract.mjs'
 *   const problems = await checkContract({ snapshotPath })
 *
 * ==========================================================================
 * 比对口径：为什么比"结构"而不比"字节"
 * ==========================================================================
 * 实测发现：后端导出的 JSON 与仓库里的快照存在 **1 字节差异**（行尾 LF/CRLF），
 * 语义完全相同。若按字节比对会永远报漂移，是典型的"狼来了"——
 * 报警一旦不可信就会被人忽略。
 * 因此这里比对的是**归一化后的契约结构**：路径集合、HTTP 方法、schema 名称集合、
 * info.version。这足以发现"新增/删除了接口或字段类型名"这类真实漂移，
 * 又不会因格式化差异误报。
 */

import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

const __dirname = dirname(fileURLToPath(import.meta.url))
const DEFAULT_SNAPSHOT = resolve(__dirname, '../src/api/generated/openapi.snapshot.json')

/** 计算文本的 SHA256（小写），用于把快照钉死在某个具体版本上 */
export function sha256(text) {
  return createHash('sha256').update(text, 'utf8').digest('hex')
}

/**
 * 把 OpenAPI 文档归一化成可比较的指纹。
 * 只取**有语义**的部分：路径、方法、schema 名、版本。
 */
export function fingerprint(doc) {
  const paths = {}
  for (const [p, item] of Object.entries(doc.paths ?? {})) {
    paths[p] = Object.keys(item)
      .filter((k) => ['get', 'post', 'put', 'delete', 'patch'].includes(k))
      .map((k) => k.toUpperCase())
      .sort()
  }
  return {
    version: doc.info?.version ?? '(none)',
    pathCount: Object.keys(paths).length,
    paths,
    schemas: Object.keys(doc.components?.schemas ?? {}).sort(),
  }
}

/**
 * 比对快照与在线契约。
 *
 * @param {object}  opts
 * @param {string}  opts.target        后端基地址
 * @param {string}  opts.snapshotPath  快照路径
 * @param {boolean} opts.throwOnError  true 时把错误抛出而不是打印+exit（测试用）
 * @returns {Promise<{ok:boolean, problems:string[], snapshotHash:string,
 *                    snapshotFingerprint:object, liveFingerprint:object|null, messages:string[]}>}
 *
 * 设计说明：这里**不调用 process.exit**，把退出码决策留给 CLI 包装层。
 * 若在库函数里直接 exit，就无法被测试复用 —— 这正是第一版变异测试误报"规则失效"的教训。
 */
export async function checkContract({ target = 'http://127.0.0.1:8080', snapshotPath = DEFAULT_SNAPSHOT } = {}) {
  const messages = []
  const problems = []

  // ---- 1. 读快照 ----
  let snapshotText
  try {
    snapshotText = readFileSync(snapshotPath, 'utf8')
  } catch {
    return {
      ok: false,
      problems: ['读不到契约快照（文件缺失）'],
      snapshotHash: '',
      snapshotFingerprint: null,
      liveFingerprint: null,
      messages: [`✗ 读不到契约快照：${snapshotPath}`, '  该文件是生成类型的输入，缺失说明工程不完整。'],
    }
  }

  const snapshotHash = sha256(snapshotText)
  messages.push(`契约快照 SHA256 : ${snapshotHash}`)

  let snapshot
  try {
    snapshot = JSON.parse(snapshotText)
  } catch (e) {
    return {
      ok: false,
      problems: [`契约快照不是合法 JSON：${e.message}`],
      snapshotHash,
      snapshotFingerprint: null,
      liveFingerprint: null,
      messages: [
        ...messages,
        `✗ 契约快照不是合法 JSON：${e.message}`,
        '  该文件由后端导出，不允许手工编辑（否则会破坏契约权威性）。',
      ],
    }
  }

  // ---- 2. 拉取在线契约 ----
  const liveUrl = `${target.replace(/\/+$/, '')}/v3/api-docs`
  messages.push(`正在比对在线契约: ${liveUrl}`)

  let live
  try {
    const res = await fetch(liveUrl, { signal: AbortSignal.timeout(10000) })
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    live = await res.json()
  } catch (e) {
    // 后端没起是"无法判定"，不是"漂移"。提示保持可操作，避免误判为契约不一致。
    return {
      ok: false,
      problems: [`无法获取在线契约：${e.message}`],
      snapshotHash,
      snapshotFingerprint: fingerprint(snapshot),
      liveFingerprint: null,
      messages: [
        ...messages,
        `\n✗ 无法获取在线契约：${e.message}`,
        '  后端未启动或地址不对。请先运行后端：',
        "    java '-Dstdout.encoding=UTF-8' -jar server/target/hy-forum-server-0.0.1-SNAPSHOT.jar",
      ],
    }
  }

  // ---- 3. 比对 ----
  const a = fingerprint(snapshot)
  const b = fingerprint(live)

  if (a.version !== b.version) {
    problems.push(`版本不一致：快照 ${a.version} ≠ 在线 ${b.version}`)
  }

  const allPaths = [...new Set([...Object.keys(a.paths), ...Object.keys(b.paths)])].sort()
  for (const p of allPaths) {
    const inA = a.paths[p]
    const inB = b.paths[p]
    if (!inA) {
      problems.push(`在线新增路径（快照缺失）：${(inB ?? []).join('/')} ${p}`)
      continue
    }
    if (!inB) {
      problems.push(`在线已移除路径（快照仍保留）：${inA.join('/')} ${p}`)
      continue
    }
    if (inA.join(',') !== inB.join(',')) {
      problems.push(`方法不一致：${p}  快照[${inA.join(',')}] ≠ 在线[${inB.join(',')}]`)
    }
  }

  const onlyInSnapshot = a.schemas.filter((s) => !b.schemas.includes(s))
  const onlyInLive = b.schemas.filter((s) => !a.schemas.includes(s))
  if (onlyInLive.length) problems.push(`在线新增 schema：${onlyInLive.join(', ')}`)
  if (onlyInSnapshot.length) problems.push(`在线已移除 schema：${onlyInSnapshot.join(', ')}`)

  messages.push(`\n路径数：快照 ${a.pathCount} / 在线 ${b.pathCount}`)
  messages.push(`schema 数：快照 ${a.schemas.length} / 在线 ${b.schemas.length}`)

  return { ok: problems.length === 0, problems, snapshotHash, snapshotFingerprint: a, liveFingerprint: b, messages }
}

/* ---------------------------------------------------------------------------
 * CLI 包装层（只有直接运行时才执行）
 * ------------------------------------------------------------------------- */

const isDirectRun = process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))

if (isDirectRun) {
  const target = process.argv[2] || 'http://127.0.0.1:8080'
  const result = await checkContract({ target })
  result.messages.forEach((m) => console.log(m))

  if (result.ok) {
    console.log('\n✓ 契约一致：前端快照与后端在线接口相同。')
    console.log('  注意：本脚本比对的是**结构**（路径/方法/schema/版本）。')
    console.log('  字段级改动（如某字段从必填改可选）需人工复核，本脚本不覆盖。')
    /*
     * ⚠️ 这里用 `process.exitCode = 0` 而**不是** `process.exit(0)`。
     *
     * 原因（本机实测踩到）：`fetch` 复用全局 keep-alive 连接池，脚本结束时
     * 池里还留着未关闭的 socket。此时立刻调 `process.exit()` 硬杀进程，
     * Node/libuv 在 Windows 上会断言失败并打印：
     *   Assertion failed: !(handle->flags & UV_HANDLE_CLOSING), file src\win\async.c, line 94
     * 且退出码变成一个崩溃码（实测 -1073740791），把"校验通过"变成了"看起来崩了"。
     * 设 `exitCode` 后让事件循环自然收尾，socket 会被正常关闭，退出码也正确。
     */
    process.exitCode = 0
  } else {
    console.error(`\n✗ 发现 ${result.problems.length} 处问题：`)
    result.problems.forEach((p, i) => console.error(`  ${i + 1}. ${p}`))
    console.error('\n处理方式（不要手工改快照，那会让生成类型与真实契约脱节）：')
    console.error('  1. 确认后端改动是有意的')
    console.error('  2. 用后端仓库的 openapi.json 覆盖 src/api/generated/openapi.snapshot.json')
    console.error('  3. 运行 npm run gen:api 重新生成类型')
    console.error('  4. 更新 src/api/contract.ts 里的 SHA256 常量')
    console.error('  若契约不足（缺前端需要的字段），按 AGENTS.md 提 CR，不要自行编造。')
    // 同上：不用 process.exit(1)
    process.exitCode = 1
  }
}
