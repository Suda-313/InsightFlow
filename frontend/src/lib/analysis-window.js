/** ISO 日期字符串（YYYY-MM-DD）转 API query 片段；未选齐时不附加参数（走后端默认窗口）。 */
export function analysisQuery(from, to) {
  if (!from || !to) return ''
  return `?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`
}

/** OffsetDateTime / ISO 字符串 → date input 值。 */
export function toDateInput(iso) {
  return iso ? String(iso).slice(0, 10) : ''
}

/**
 * 默认分析窗口：数据截止日往前 7 天（与后端 AnalysisWindowResolver 一致）。
 */
export function defaultWindowFromCoverage(coverageStart, coverageEnd) {
  const to = toDateInput(coverageEnd)
  if (!to) return { from: '', to: '' }
  const endMs = Date.parse(to + 'T00:00:00Z')
  const fromMs = endMs - 7 * 24 * 60 * 60 * 1000
  let from = new Date(fromMs).toISOString().slice(0, 10)
  const start = toDateInput(coverageStart)
  if (start && from < start) from = start
  return { from, to }
}
