// 日期一律用 YYYY-MM-DD 字符串传递（与接口一致），界面上显示成 2026.11.15（原型 dot）
const pad = (n: number) => String(n).padStart(2, '0')

export const isoOf = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
export const today = () => isoOf(new Date())

export function shiftDay(iso: string, days: number) {
  const d = new Date(iso + 'T00:00:00')
  d.setDate(d.getDate() + days)
  return isoOf(d)
}

export const dot = (iso: string) => iso.replace(/-/g, '.')

/** 把 2026.11.15（也认 - 和 /）解析成 ISO；不是一个真实存在的日期返回 null（如 2026.02.30） */
export function parseDot(text: string) {
  const m = /^(\d{4})[.\-/](\d{1,2})[.\-/](\d{1,2})$/.exec(text.trim())
  if (!m) return null
  const iso = `${m[1]}-${pad(+m[2])}-${pad(+m[3])}`
  return isoOf(new Date(iso + 'T00:00:00')) === iso ? iso : null
}
