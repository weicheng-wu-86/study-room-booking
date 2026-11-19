// 时间与展示相关的工具函数。
//
// 后端返回的时间是不带时区的“业务时间”（如 2026-11-17T15:00:00）。
// 前端一律把它当作“墙上时间”处理：解析成按 UTC 计算的毫秒数，再用 UTC 方法格式化。
// 这样无论用户设备在哪个时区，看到的都是服务器所在地的时间，不会出现偏差。

/** '2026-11-17T15:00:00' 或 '2026-11-17' → 毫秒（墙上时间） */
export function parseTime(str) {
  if (!str) return null
  const [d, t = '00:00:00'] = str.split('T')
  const [y, m, day] = d.split('-').map(Number)
  const [h, mi, s = 0] = t.split(':').map(Number)
  return Date.UTC(y, m - 1, day, h, mi, Math.floor(s))
}

const pad = (n) => String(n).padStart(2, '0')
const WEEK = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']

/** 毫秒 → 'YYYY-MM-DD' */
export function dateStr(ms) {
  const d = new Date(ms)
  return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}`
}

/** 毫秒或时间字符串 → 'HH:mm' */
export function hm(value) {
  const ms = typeof value === 'string' ? parseTime(value) : value
  if (ms == null) return '--'
  const d = new Date(ms)
  return `${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}`
}

/** 时间字符串 → 'MM-DD HH:mm' */
export function mdhm(str) {
  const ms = parseTime(str)
  if (ms == null) return '--'
  const d = new Date(ms)
  return `${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())} ${hm(ms)}`
}

/** 'YYYY-MM-DD' → { md: '11月17日', week: '周二' } */
export function dayLabel(date) {
  const d = new Date(parseTime(date))
  return { md: `${d.getUTCMonth() + 1}月${d.getUTCDate()}日`, week: WEEK[d.getUTCDay()] }
}

/** 9 → '09:00' */
export const hourLabel = (h) => `${pad(h)}:00`

/** 预约时段文字：'09:00-12:00' */
export const slotLabel = (r) => `${hourLabel(r.startHour)}-${hourLabel(r.endHour)}`

/** 剩余毫秒 → 'mm:ss'（小于 0 时显示 00:00） */
export function countdown(ms) {
  const total = Math.max(0, Math.floor(ms / 1000))
  return `${pad(Math.floor(total / 60))}:${pad(total % 60)}`
}

export const percent = (rate) => `${Math.round((rate || 0) * 1000) / 10}%`

/** 预约状态的中文名称与标签颜色 */
export const STATUS = {
  RESERVED: { label: '待签到', type: 'warning' },
  IN_USE: { label: '使用中', type: 'success' },
  AWAY: { label: '暂离中', type: 'primary' },
  COMPLETED: { label: '已完成', type: 'info' },
  CANCELLED: { label: '已取消', type: 'info' },
  NO_SHOW: { label: '爽约', type: 'danger' },
  AWAY_TIMEOUT: { label: '暂离超时', type: 'danger' }
}

export const VIOLATION_TYPE = {
  NO_SHOW: '超时未签到',
  AWAY_TIMEOUT: '暂离超时'
}
