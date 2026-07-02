/** Framework-agnostic formatting helpers for trace / manifest rendering. */

export function getString(value: unknown, fallback = 'Unknown'): string {
  if (typeof value === 'string' && value.trim()) return value
  if (typeof value === 'number' && Number.isFinite(value)) return String(value)
  if (typeof value === 'boolean') return value ? 'true' : 'false'
  return fallback
}

export function getNumber(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}

export function formatTime(ms: number): string {
  return new Intl.DateTimeFormat(undefined, {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).format(new Date(ms))
}

export function formatDuration(ms: unknown): string {
  const value = getNumber(ms)
  if (value === null) return 'Pending'
  if (value < 1000) return `${value} ms`
  return `${(value / 1000).toFixed(1)} s`
}
