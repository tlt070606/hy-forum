/** el-table 的 formatter 行参数没有静态类型，统一收窄成这个（值为后端 ISO 字符串或空）。 */
export type TimeRow = Record<string, string | null | undefined>

/** 后端 LocalDateTime 的 JSON 序列化是 ISO 形态（2026-09-12T18:47:31），列表展示去掉 T。 */
export function fmtTime(value?: string | null): string {
  return value ? value.replace('T', ' ') : '—'
}
