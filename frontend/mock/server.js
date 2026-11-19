// 模拟后端：不依赖 Java、MySQL、Redis，用内存数据实现与真实后端一致的 REST 接口。
//
// 用途：只想看前端效果、或后端环境还没装好时，执行 `npm run mock` 再执行 `npm run dev` 即可完整体验。
// 注意：这里的业务规则是对 Java 后端的简化复刻（单进程、无并发问题），仅用于演示；
//      真实的并发控制、事务、定时任务等以 backend/ 中的实现为准。数据保存在内存里，重启即重置。
//
// 可选环境变量：
//   PORT       监听端口，默认 8080
//   MOCK_NOW   固定“当前时间”的起点，例如 2026-11-17T14:20:00（便于截图和演示），默认使用真实的北京时间

import http from 'node:http'

const PORT = Number(process.env.PORT || 8080)
const RULES = {
  maxDaysAhead: 6, maxHoursPerReservation: 4, maxHoursPerDay: 8,
  checkinEarlyMinutes: 15, checkinLateMinutes: 15, awayMaxMinutes: 30, awayMaxTimes: 2,
  windowDays: 30, maxViolations: 3, banDays: 7
}
const MIN = 60000
const HOUR = 3600000
const DAY = 86400000

// ---------- 时间：全部使用“墙上时间”的毫秒数（按 UTC 方法读写），与前端 utils.js 的约定一致 ----------
const startedAt = Date.now()
const baseWall = process.env.MOCK_NOW ? parse(process.env.MOCK_NOW) : Date.now() + 8 * HOUR
const now = () => baseWall + (Date.now() - startedAt)
const pad = (n) => String(n).padStart(2, '0')
function parse(str) {
  const [d, t = '00:00:00'] = str.split('T')
  const [y, m, day] = d.split('-').map(Number)
  const [h, mi, s = 0] = t.split(':').map(Number)
  return Date.UTC(y, m - 1, day, h, mi, s)
}
const dateStr = (ms) => new Date(ms).toISOString().slice(0, 10)
const fmt = (ms) => (ms == null ? null : new Date(ms).toISOString().slice(0, 19))
const hm = (ms) => fmt(ms).slice(11, 16)
const startOf = (r) => parse(r.reserveDate) + r.startHour * HOUR
const endOf = (r) => parse(r.reserveDate) + r.endHour * HOUR
const deadlineOf = (r) => Math.max(startOf(r), r.createdAt) + RULES.checkinLateMinutes * MIN
const ACTIVE = ['RESERVED', 'IN_USE', 'AWAY']

// ---------- 内存数据 ----------
const db = { users: [], rooms: [], seats: [], reservations: [], violations: [], notices: [] }
const seq = { users: 0, rooms: 0, seats: 0, reservations: 0, violations: 0, notices: 0 }
const insert = (table, row) => {
  row.id = ++seq[table]
  db[table].push(row)
  return row
}

class ApiError extends Error {
  constructor(code, message) {
    super(message)
    this.code = code
  }
}
const fail = (code, message) => {
  throw new ApiError(code, message)
}

// 固定种子的伪随机数，保证每次启动生成的演示数据相同
let seed = 20261117
const rand = (n) => {
  seed = (seed + 0x6d2b79f5) | 0
  let t = Math.imul(seed ^ (seed >>> 15), 1 | seed)
  t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
  return Math.floor((((t ^ (t >>> 14)) >>> 0) / 4294967296) * n)
}
const code = () => Array.from({ length: 10 }, () => 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'[rand(32)]).join('')

function createRoom(body) {
  const room = insert('rooms', { ...body, enabled: true, createdAt: now() })
  for (let r = 1; r <= body.rowCount; r++) {
    for (let c = 1; c <= body.colCount; c++) {
      insert('seats', {
        roomId: room.id, seatNo: String.fromCharCode(64 + r) + pad(c), rowNo: r, colNo: c,
        hasSocket: false, nearWindow: false, enabled: true, checkinCode: code()
      })
    }
  }
  return room
}

/** 座位或用户在某天被占用的小时（只看仍有效的预约；签退、超时的预约只保留到结束时所在的小时） */
function occupiedHours(filter, date) {
  const hours = []
  for (const r of db.reservations) {
    if (r.reserveDate !== date || !filter(r)) continue
    let end = ACTIVE.includes(r.status) ? r.endHour : null
    if (r.releasedAfter != null) end = Math.min(r.endHour, r.releasedAfter + 1)
    if (end == null) continue
    for (let h = r.startHour; h < end; h++) hours.push({ hour: h, userId: r.userId, seatId: r.seatId })
  }
  return hours
}

function addViolation(r, type, remark) {
  insert('violations', { userId: r.userId, reservationId: r.id, type, remark, createdAt: now() })
  const user = db.users.find((u) => u.id === r.userId)
  if (recentViolations(user.id) >= RULES.maxViolations) user.banUntil = now() + RULES.banDays * DAY
}
const recentViolations = (userId) =>
  db.violations.filter((v) => v.userId === userId && v.createdAt >= now() - RULES.windowDays * DAY).length

/** 相当于后端的定时扫描；模拟服务在每次请求前执行一遍 */
function scan() {
  const t = now()
  for (const r of db.reservations) {
    if (r.status === 'RESERVED' && t > deadlineOf(r)) {
      r.status = 'NO_SHOW'
      addViolation(r, 'NO_SHOW', `预约后超过 ${RULES.checkinLateMinutes} 分钟未签到`)
    } else if (r.status === 'AWAY' && t > r.awayStartTime + RULES.awayMaxMinutes * MIN) {
      awayTimeout(r)
    } else if ((r.status === 'IN_USE' || r.status === 'AWAY') && t >= endOf(r)) {
      Object.assign(r, { status: 'COMPLETED', checkoutTime: endOf(r), awayStartTime: null })
    }
  }
}
function awayTimeout(r) {
  Object.assign(r, { status: 'AWAY_TIMEOUT', checkoutTime: now(), awayStartTime: null, releasedAfter: new Date(now()).getUTCHours() })
  addViolation(r, 'AWAY_TIMEOUT', `暂离超过 ${RULES.awayMaxMinutes} 分钟未返回`)
}

// ---------- 演示数据（与后端 DemoDataInitializer 的内容对应） ----------
function seedData() {
  const t = now()
  const today = dateStr(t)
  insert('users', { username: 'admin', password: 'admin123', realName: '系统管理员', studentNo: null, phone: null, role: 'ADMIN', enabled: true, banUntil: null, createdAt: t })
  const surnames = '王李张刘陈杨黄赵吴周徐孙马朱胡郭何林高罗'
  const given = ['子涵', '欣怡', '浩然', '雨桐', '梓轩', '思琪', '俊杰', '佳怡', '宇航', '诗涵', '博文', '梦瑶', '明轩', '晓彤', '志强', '婉婷', '天宇', '雅琪', '文博', '静怡']
  for (let i = 1; i <= 120; i++) {
    insert('users', {
      username: `stu${String(i).padStart(3, '0')}`, password: '123456', realName: surnames[i % 20] + given[(i + 3 * Math.floor(i / 20)) % 20],
      studentNo: `2023${String(1000 + i).padStart(4, '0')}`, phone: null, role: 'STUDENT', enabled: true, banUntil: null, createdAt: t - 30 * DAY
    })
  }
  createRoom({ name: '图书馆一楼综合自习室', location: '图书馆 1 层东侧', description: '空间开阔，靠窗座位采光好，适合日常自习。', openHour: 8, closeHour: 22, rowCount: 6, colCount: 8 })
  createRoom({ name: '图书馆三楼考研自习室', location: '图书馆 3 层 301', description: '安静区，禁止讨论，座位均配有电源插座。', openHour: 7, closeHour: 23, rowCount: 5, colCount: 10 })
  createRoom({ name: '教学楼 A 座晚自习室', location: '教学楼 A 座 201', description: '离宿舍区近，适合晚间自习。', openHour: 8, closeHour: 22, rowCount: 4, colCount: 6 })
  for (const s of db.seats) {
    const room = db.rooms.find((r) => r.id === s.roomId)
    s.nearWindow = s.colNo === 1 || s.colNo === room.colCount
    s.hasSocket = room.name.includes('考研') || s.rowNo % 2 === 1
    s.enabled = !(s.rowNo === 2 && s.colNo === 3)
  }
  const notices = [
    ['自习室预约系统上线通知', '即日起，校内自习室统一通过本系统预约使用。请同学们提前预约、按时签到，离开时主动签退，把座位留给有需要的同学。', true],
    ['关于预约违约处理的说明', '预约后超过 15 分钟未签到，或暂离超过 30 分钟未返回，系统将自动释放座位并记一次违约。30 天内累计违约 3 次，将暂停预约资格 7 天。', false],
    ['期末复习周开放时间调整', '期末复习周期间，图书馆三楼考研自习室开放时间延长为 7:00-23:00，请合理安排学习时间，注意休息。', false]
  ]
  notices.forEach(([title, content, pinned], i) =>
    insert('notices', { title, content, pinned, publisherId: 1, createdAt: t - (notices.length - i) * DAY, updatedAt: t }))

  const seats = db.seats.filter((s) => s.enabled)
  const students = db.users.filter((u) => u.role === 'STUDENT')
  const taken = new Set()
  const generate = (date, count) => {
    for (let i = 0; i < count; i++) {
      const seat = seats[rand(seats.length)]
      const room = db.rooms.find((r) => r.id === seat.roomId)
      const user = students[rand(students.length)]
      const length = 1 + rand(4)
      const start = room.openHour + rand(room.closeHour - room.openHour - length + 1)
      const keys = []
      for (let h = start; h < start + length; h++) keys.push(`s${seat.id}:${date}:${h}`, `u${user.id}:${date}:${h}`)
      if (keys.some((k) => taken.has(k))) continue
      keys.forEach((k) => taken.add(k))
      const r = { userId: user.id, seatId: seat.id, roomId: room.id, reserveDate: date, startHour: start, endHour: start + length, awayCount: 0, remark: null }
      const [s, e] = [startOf(r), endOf(r)]
      r.createdAt = Math.min(s - (2 + rand(20)) * HOUR, t - (1 + rand(120)) * MIN)
      if (e <= t) {
        const dice = rand(100)
        if (dice < 87 || (dice >= 96 && user === students[0])) Object.assign(r, { status: 'COMPLETED', checkinTime: s - 10 * MIN + rand(20) * MIN, checkoutTime: dice < 20 ? e - (10 + rand(40)) * MIN : e })
        else if (dice < 96) Object.assign(r, { status: 'CANCELLED', remark: '用户取消' })
        else r.status = 'NO_SHOW'
      } else if (s <= t) Object.assign(r, { status: 'IN_USE', checkinTime: s - 5 * MIN })
      else r.status = 'RESERVED'
      insert('reservations', r)
      if (r.status === 'NO_SHOW') {
        // 与后端一致：以违约发生的时刻为准统计 30 天内的次数，满 3 次从那一刻起禁约 7 天
        const at = s + 15 * MIN
        insert('violations', { userId: user.id, reservationId: r.id, type: 'NO_SHOW', remark: '预约后超过 15 分钟未签到', createdAt: at })
        const recent = db.violations.filter((v) => v.userId === user.id && v.createdAt >= at - RULES.windowDays * DAY).length
        if (recent >= RULES.maxViolations) user.banUntil = at + RULES.banDays * DAY
      }
    }
  }
  for (let d = 14; d >= 1; d--) generate(dateStr(t - d * DAY), 180)
  generate(today, 150)
  generate(dateStr(t + DAY), 80)
  generate(dateStr(t + 2 * DAY), 30)
}

// ---------- 视图组装 ----------
const userInfo = (u) => ({
  id: u.id, username: u.username, realName: u.realName, studentNo: u.studentNo, phone: u.phone, role: u.role,
  enabled: u.enabled, banUntil: fmt(u.banUntil), banned: u.banUntil != null && u.banUntil > now(),
  recentViolations: recentViolations(u.id), createdAt: fmt(u.createdAt)
})
function reservationVO(r) {
  const seat = db.seats.find((s) => s.id === r.seatId)
  const room = db.rooms.find((x) => x.id === r.roomId)
  const user = db.users.find((u) => u.id === r.userId)
  return {
    id: r.id, userId: r.userId, seatId: r.seatId, roomId: r.roomId, reserveDate: r.reserveDate,
    startHour: r.startHour, endHour: r.endHour, status: r.status, checkinTime: fmt(r.checkinTime),
    checkoutTime: fmt(r.checkoutTime), awayStartTime: fmt(r.awayStartTime), awayCount: r.awayCount, remark: r.remark,
    createdAt: fmt(r.createdAt), seatNo: seat.seatNo, roomName: room.name, roomLocation: room.location,
    username: user.username, realName: user.realName, studentNo: user.studentNo,
    checkinOpenTime: fmt(startOf(r) - RULES.checkinEarlyMinutes * MIN), checkinDeadline: fmt(deadlineOf(r)),
    awayDeadline: r.status === 'AWAY' ? fmt(r.awayStartTime + RULES.awayMaxMinutes * MIN) : null
  }
}
function roomView(room, date) {
  const seatIds = db.seats.filter((s) => s.roomId === room.id && s.enabled).map((s) => s.id)
  const occupied = occupiedHours((r) => r.roomId === room.id, date).length
  return { ...room, createdAt: undefined, seatCount: seatIds.length, occupiedSeatHours: occupied, totalSeatHours: seatIds.length * (room.closeHour - room.openHour) }
}
function violationVO(v) {
  const u = db.users.find((x) => x.id === v.userId)
  const r = db.reservations.find((x) => x.id === v.reservationId)
  const vo = r ? reservationVO(r) : {}
  return {
    id: v.id, userId: v.userId, reservationId: v.reservationId, type: v.type, remark: v.remark, createdAt: fmt(v.createdAt),
    username: u.username, realName: u.realName, studentNo: u.studentNo,
    roomName: vo.roomName, seatNo: vo.seatNo, reserveDate: vo.reserveDate, startHour: vo.startHour, endHour: vo.endHour
  }
}
const page = (list, q) => {
  const p = Math.max(1, Number(q.page || 1))
  const size = Math.min(100, Number(q.size || 10))
  return { total: list.length, records: list.slice((p - 1) * size, p * size) }
}
const matchUser = (u, kw) => !kw || [u.username, u.realName, u.studentNo].some((x) => x && x.includes(kw))
const byIdDesc = (a, b) => b.id - a.id

// ---------- 业务 ----------
function createReservation(user, body) {
  const t = now()
  const today = dateStr(t)
  if (!user.enabled) fail(403, '账号不可用')
  if (user.banUntil && user.banUntil > t) fail(403, `因多次违约，您在 ${fmt(user.banUntil).slice(5, 16).replace('T', ' ')} 前无法预约`)
  const seat = db.seats.find((s) => s.id === body.seatId)
  if (!seat || !seat.enabled) fail(400, '座位不存在或已暂停使用')
  const room = db.rooms.find((r) => r.id === seat.roomId)
  if (!room.enabled) fail(400, '该自习室暂未开放')
  const { date, startHour: start, endHour: end } = body
  if (!date || date < today || date > dateStr(parse(today) + RULES.maxDaysAhead * DAY)) fail(400, `只能预约今天起 ${RULES.maxDaysAhead + 1} 天内的座位`)
  if (!(start < end)) fail(400, '结束时间必须晚于开始时间')
  if (start < room.openHour || end > room.closeHour) fail(400, `该自习室开放时间为 ${room.openHour}:00-${room.closeHour}:00`)
  if (end - start > RULES.maxHoursPerReservation) fail(400, `单次预约最长 ${RULES.maxHoursPerReservation} 小时`)
  if (date === today && start < new Date(t).getUTCHours()) fail(400, '不能预约已经过去的时段')
  const inRange = (o) => o.hour >= start && o.hour < end
  if (occupiedHours((r) => r.seatId === seat.id, date).some(inRange)) fail(409, '该座位在所选时段已被预约，请选择其他座位或时段')
  if (occupiedHours((r) => r.userId === user.id, date).some(inRange)) fail(409, '您在所选时段已有其他预约')
  const used = db.reservations.filter((r) => r.userId === user.id && r.reserveDate === date && !['CANCELLED', 'NO_SHOW'].includes(r.status))
    .reduce((sum, r) => sum + r.endHour - r.startHour, 0)
  if (used + end - start > RULES.maxHoursPerDay) fail(400, `每人每天最多预约 ${RULES.maxHoursPerDay} 小时，您当天已预约 ${used} 小时`)
  return insert('reservations', { userId: user.id, seatId: seat.id, roomId: room.id, reserveDate: date, startHour: start, endHour: end, status: 'RESERVED', awayCount: 0, remark: null, createdAt: t })
}
function owned(user, id) {
  const r = db.reservations.find((x) => x.id === Number(id))
  if (!r || r.userId !== user.id) fail(404, '预约不存在')
  return r
}
function checkin(user, rawCode) {
  const t = now()
  const seat = db.seats.find((s) => s.checkinCode === String(rawCode || '').trim().toUpperCase())
  if (!seat) fail(400, '签到码无效')
  const list = db.reservations.filter((r) => r.userId === user.id && r.seatId === seat.id && r.reserveDate === dateStr(t) && r.status === 'RESERVED')
    .sort((a, b) => a.startHour - b.startHour)
  if (!list.length) fail(400, `您今天在座位 ${seat.seatNo} 没有待签到的预约`)
  for (const r of list) {
    const open = startOf(r) - RULES.checkinEarlyMinutes * MIN
    if (t < open) fail(400, `还未到签到时间，请在 ${hm(open)} 之后签到`)
    if (t <= deadlineOf(r)) return Object.assign(r, { status: 'IN_USE', checkinTime: t })
  }
  fail(400, '已超过签到截止时间，本次预约将按爽约处理')
}
const releaseAfterNow = (r) => {
  if (r.reserveDate === dateStr(now())) r.releasedAfter = new Date(now()).getUTCHours()
  else if (r.reserveDate > dateStr(now())) r.releasedAfter = -1
}

function stats(days) {
  const t = now()
  const from = dateStr(parse(dateStr(t)) - (days - 1) * DAY)
  const inRange = db.reservations.filter((r) => r.reserveDate >= from && r.reserveDate <= dateStr(t))
  const valid = inRange.filter((r) => !['CANCELLED', 'NO_SHOW'].includes(r.status))
  const trend = []
  for (let i = 0; i < days; i++) {
    const date = dateStr(parse(from) + i * DAY)
    const list = inRange.filter((r) => r.reserveDate === date)
    const count = (s) => list.filter((r) => r.status === s).length
    trend.push({ date, total: list.length, completed: count('COMPLETED'), noShow: count('NO_SHOW'), cancelled: count('CANCELLED') })
  }
  const open = Math.min(...db.rooms.map((r) => r.openHour))
  const close = Math.max(...db.rooms.map((r) => r.closeHour))
  const hourly = []
  for (let h = open; h < close; h++) {
    hourly.push({ hour: h, avgSeats: Math.round((valid.filter((r) => r.startHour <= h && h < r.endHour).length * 10) / days) / 10 })
  }
  const rate = (a, b) => (b === 0 ? 0 : Math.round((a * 10000) / b) / 10000)
  const rooms = db.rooms.map((room) => {
    const usedHours = valid.filter((r) => r.roomId === room.id).reduce((s, r) => s + r.endHour - r.startHour, 0)
    const capacityHours = db.seats.filter((s) => s.roomId === room.id && s.enabled).length * (room.closeHour - room.openHour) * days
    return { roomId: room.id, roomName: room.name, usedHours, capacityHours, rate: rate(usedHours, capacityHours) }
  })
  return { trend, hourly, rooms, rate }
}

// ---------- 路由表：[方法, 路径正则, 处理函数(ctx)] ----------
const routes = [
  ['POST', /^\/api\/auth\/login$/, ({ body }) => {
    const u = db.users.find((x) => x.username === body.username)
    if (!u || u.password !== body.password) fail(400, '账号或密码错误')
    if (!u.enabled) fail(403, '账号已被停用，请联系管理员')
    return { token: `mock.${u.id}`, user: userInfo(u) }
  }],
  ['POST', /^\/api\/auth\/register$/, ({ body }) => {
    if (!/^[A-Za-z0-9_]{4,20}$/.test(body.username || '')) fail(400, '账号需为 4-20 位字母、数字或下划线')
    if (!body.password || body.password.length < 6) fail(400, '密码长度需为 6-32 位')
    if (!body.realName) fail(400, '请输入姓名')
    if (db.users.some((u) => u.username === body.username)) fail(409, '该账号已被注册')
    return userInfo(insert('users', { username: body.username, password: body.password, realName: body.realName, studentNo: body.studentNo || null, phone: body.phone || null, role: 'STUDENT', enabled: true, banUntil: null, createdAt: now() }))
  }],
  ['GET', /^\/api\/auth\/me$/, ({ user }) => userInfo(user)],
  ['PUT', /^\/api\/auth\/profile$/, ({ user, body }) => userInfo(Object.assign(user, { realName: body.realName, phone: body.phone || null }))],
  ['PUT', /^\/api\/auth\/password$/, ({ user, body }) => {
    if (user.password !== body.oldPassword) fail(400, '原密码不正确')
    user.password = body.newPassword
  }],
  ['GET', /^\/api\/rules$/, () => ({ ...RULES, serverTime: fmt(now()) })],
  ['GET', /^\/api\/notices$/, () => [...db.notices].sort((a, b) => b.pinned - a.pinned || b.createdAt - a.createdAt)
    .map((n) => ({ ...n, createdAt: fmt(n.createdAt), updatedAt: fmt(n.updatedAt) }))],
  ['GET', /^\/api\/rooms$/, ({ query }) => db.rooms.filter((r) => r.enabled).map((r) => roomView(r, query.date || dateStr(now())))],
  ['GET', /^\/api\/rooms\/(\d+)\/seats$/, ({ user, query, params }) => {
    const room = db.rooms.find((r) => r.id === Number(params[0])) || fail(404, '自习室不存在')
    const date = query.date || dateStr(now())
    const seats = db.seats.filter((s) => s.roomId === room.id).map((s) => {
      const hours = occupiedHours((r) => r.seatId === s.id, date)
      const pick = (mine) => hours.filter((o) => (o.userId === user.id) === mine).map((o) => o.hour).sort((a, b) => a - b)
      return { id: s.id, seatNo: s.seatNo, rowNo: s.rowNo, colNo: s.colNo, hasSocket: s.hasSocket, nearWindow: s.nearWindow, enabled: s.enabled, occupiedHours: pick(false), myHours: pick(true) }
    })
    return { room: roomView(room, date), date, seats }
  }],
  ['POST', /^\/api\/reservations$/, ({ user, body }) => reservationVO(createReservation(user, body))],
  ['GET', /^\/api\/reservations\/active$/, ({ user }) => db.reservations.filter((r) => r.userId === user.id && ACTIVE.includes(r.status))
    .sort((a, b) => startOf(a) - startOf(b)).map(reservationVO)],
  ['GET', /^\/api\/reservations\/mine$/, ({ user, query }) => page(db.reservations
    .filter((r) => r.userId === user.id && (!query.status || r.status === query.status))
    .sort((a, b) => startOf(b) - startOf(a) || b.id - a.id).map(reservationVO), query)],
  ['POST', /^\/api\/reservations\/checkin$/, ({ user, body }) => reservationVO(checkin(user, body.code))],
  ['POST', /^\/api\/reservations\/(\d+)\/cancel$/, ({ user, params }) => {
    const r = owned(user, params[0])
    if (r.status !== 'RESERVED') fail(400, '只有待签到的预约可以取消')
    Object.assign(r, { status: 'CANCELLED', remark: '用户取消' })
  }],
  ['POST', /^\/api\/reservations\/(\d+)\/away$/, ({ user, params }) => {
    const r = owned(user, params[0])
    if (r.status !== 'IN_USE') fail(400, '只有使用中的预约可以暂离')
    if (r.awayCount >= RULES.awayMaxTimes) fail(400, `每次预约最多暂离 ${RULES.awayMaxTimes} 次`)
    return reservationVO(Object.assign(r, { status: 'AWAY', awayStartTime: now(), awayCount: r.awayCount + 1 }))
  }],
  ['POST', /^\/api\/reservations\/(\d+)\/back$/, ({ user, params }) => {
    const r = owned(user, params[0])
    if (r.status !== 'AWAY') fail(400, '当前预约不在暂离状态')
    return reservationVO(Object.assign(r, { status: 'IN_USE', awayStartTime: null }))
  }],
  ['POST', /^\/api\/reservations\/(\d+)\/checkout$/, ({ user, params }) => {
    const r = owned(user, params[0])
    if (r.status !== 'IN_USE' && r.status !== 'AWAY') fail(400, '只有使用中的预约可以签退')
    Object.assign(r, { status: 'COMPLETED', checkoutTime: now(), awayStartTime: null })
    releaseAfterNow(r)
    return reservationVO(r)
  }],
  ['GET', /^\/api\/violations\/mine$/, ({ user, query }) => page(db.violations.filter((v) => v.userId === user.id).sort(byIdDesc).map(violationVO), query)],

  // ----- 管理端 -----
  ['GET', /^\/api\/admin\/stats\/overview$/, () => {
    const today = dateStr(now())
    const s = stats(1)
    const used = s.rooms.reduce((a, r) => a + r.usedHours, 0)
    const capacity = s.rooms.reduce((a, r) => a + r.capacityHours, 0)
    return {
      todayReservations: db.reservations.filter((r) => r.reserveDate === today && r.status !== 'CANCELLED').length,
      inUseNow: db.reservations.filter((r) => r.status === 'IN_USE' || r.status === 'AWAY').length,
      seatCount: db.seats.filter((x) => x.enabled).length, todayUsageRate: s.rate(used, capacity),
      weekViolations: db.violations.filter((v) => v.createdAt >= now() - 7 * DAY).length,
      studentCount: db.users.filter((u) => u.role === 'STUDENT').length
    }
  }],
  ['GET', /^\/api\/admin\/stats\/trend$/, ({ query }) => stats(Number(query.days || 7)).trend],
  ['GET', /^\/api\/admin\/stats\/hourly$/, ({ query }) => stats(Number(query.days || 7)).hourly],
  ['GET', /^\/api\/admin\/stats\/rooms$/, ({ query }) => stats(Number(query.days || 7)).rooms],
  ['GET', /^\/api\/admin\/rooms$/, () => db.rooms.map((r) => roomView(r, dateStr(now())))],
  ['POST', /^\/api\/admin\/rooms$/, ({ body }) => {
    if (body.openHour >= body.closeHour) fail(400, '关闭时间必须晚于开放时间')
    return createRoom(body)
  }],
  ['PUT', /^\/api\/admin\/rooms\/(\d+)$/, ({ params, body }) => {
    const room = db.rooms.find((r) => r.id === Number(params[0])) || fail(404, '自习室不存在')
    if (body.openHour >= body.closeHour) fail(400, '关闭时间必须晚于开放时间')
    return Object.assign(room, { name: body.name, location: body.location, description: body.description, openHour: body.openHour, closeHour: body.closeHour })
  }],
  ['PUT', /^\/api\/admin\/rooms\/(\d+)\/enabled$/, ({ params, body }) => {
    (db.rooms.find((r) => r.id === Number(params[0])) || fail(404, '自习室不存在')).enabled = !!body.enabled
  }],
  ['DELETE', /^\/api\/admin\/rooms\/(\d+)$/, ({ params }) => {
    const id = Number(params[0])
    if (db.reservations.some((r) => r.roomId === id)) fail(400, '该自习室已有预约记录，不能删除，可以改为停用')
    db.seats = db.seats.filter((s) => s.roomId !== id)
    db.rooms = db.rooms.filter((r) => r.id !== id)
  }],
  ['GET', /^\/api\/admin\/rooms\/(\d+)\/seats$/, ({ params }) => db.seats.filter((s) => s.roomId === Number(params[0]))],
  ['PUT', /^\/api\/admin\/seats\/(\d+)$/, ({ params, body }) => {
    const seat = db.seats.find((s) => s.id === Number(params[0])) || fail(404, '座位不存在')
    for (const k of ['enabled', 'hasSocket', 'nearWindow']) if (body[k] != null) seat[k] = body[k]
    return seat
  }],
  ['POST', /^\/api\/admin\/seats\/(\d+)\/reset-code$/, ({ params }) => {
    const seat = db.seats.find((s) => s.id === Number(params[0])) || fail(404, '座位不存在')
    seat.checkinCode = code()
    return seat
  }],
  ['GET', /^\/api\/admin\/reservations$/, ({ query }) => page(db.reservations.filter((r) =>
    (!query.date || r.reserveDate === query.date) && (!query.roomId || r.roomId === Number(query.roomId)) &&
    (!query.status || r.status === query.status) && matchUser(db.users.find((u) => u.id === r.userId), query.keyword))
    .sort(byIdDesc).map(reservationVO), query)],
  ['POST', /^\/api\/admin\/reservations\/(\d+)\/cancel$/, ({ params, body }) => {
    const r = db.reservations.find((x) => x.id === Number(params[0])) || fail(404, '预约不存在')
    if (!ACTIVE.includes(r.status)) fail(400, '该预约已结束，无需取消')
    if (r.status !== 'RESERVED') releaseAfterNow(r)
    Object.assign(r, { status: 'CANCELLED', remark: '管理员取消' + (body.reason ? '：' + body.reason : ''), awayStartTime: null })
  }],
  ['GET', /^\/api\/admin\/users$/, ({ query }) => page(db.users.filter((u) => matchUser(u, query.keyword)).sort(byIdDesc).map(userInfo), query)],
  ['PUT', /^\/api\/admin\/users\/(\d+)\/enabled$/, ({ user, params, body }) => {
    if (user.id === Number(params[0])) fail(400, '不能停用自己的账号')
    ;(db.users.find((u) => u.id === Number(params[0])) || fail(404, '用户不存在')).enabled = !!body.enabled
  }],
  ['POST', /^\/api\/admin\/users\/(\d+)\/unban$/, ({ params }) => {
    (db.users.find((u) => u.id === Number(params[0])) || fail(404, '用户不存在')).banUntil = null
  }],
  ['POST', /^\/api\/admin\/users\/(\d+)\/reset-password$/, ({ params }) => {
    const u = db.users.find((x) => x.id === Number(params[0])) || fail(404, '用户不存在')
    u.password = Array.from({ length: 8 }, () => 'abcdefghjkmnpqrstuvwxyz23456789'[Math.floor(Math.random() * 31)]).join('')
    return { password: u.password }
  }],
  ['GET', /^\/api\/admin\/violations$/, ({ query }) => page(db.violations
    .filter((v) => matchUser(db.users.find((u) => u.id === v.userId), query.keyword)).sort(byIdDesc).map(violationVO), query)],
  ['DELETE', /^\/api\/admin\/violations\/(\d+)$/, ({ params }) => {
    const before = db.violations.length
    db.violations = db.violations.filter((v) => v.id !== Number(params[0]))
    if (db.violations.length === before) fail(404, '违约记录不存在')
  }],
  ['POST', /^\/api\/admin\/notices$/, ({ user, body }) => insert('notices', { title: body.title, content: body.content, pinned: !!body.pinned, publisherId: user.id, createdAt: now(), updatedAt: now() })],
  ['PUT', /^\/api\/admin\/notices\/(\d+)$/, ({ params, body }) => Object.assign(
    db.notices.find((n) => n.id === Number(params[0])) || fail(404, '公告不存在'), { title: body.title, content: body.content, pinned: !!body.pinned, updatedAt: now() })],
  ['DELETE', /^\/api\/admin\/notices\/(\d+)$/, ({ params }) => {
    db.notices = db.notices.filter((n) => n.id !== Number(params[0]))
  }]
]

// ---------- HTTP 服务 ----------
function readBody(req) {
  return new Promise((resolve) => {
    let raw = ''
    req.on('data', (chunk) => (raw += chunk))
    req.on('end', () => {
      try {
        resolve(raw ? JSON.parse(raw) : {})
      } catch {
        resolve({})
      }
    })
  })
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost')
  const send = (status, payload) => {
    res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': '*', 'Access-Control-Allow-Methods': '*' })
    res.end(JSON.stringify(payload))
  }
  if (req.method === 'OPTIONS') return send(200, {})
  try {
    scan()
    const route = routes.find(([method, pattern]) => method === req.method && pattern.test(url.pathname))
    if (!route) fail(404, '接口不存在')
    const isPublic = /^\/api\/auth\/(login|register)$/.test(url.pathname)
    let user = null
    if (!isPublic) {
      const id = Number((req.headers.authorization || '').replace('Bearer mock.', ''))
      user = db.users.find((u) => u.id === id)
      if (!user) fail(401, '登录已失效，请重新登录')
      if (url.pathname.startsWith('/api/admin/') && user.role !== 'ADMIN') fail(403, '没有访问该功能的权限')
    }
    const ctx = { user, body: await readBody(req), query: Object.fromEntries(url.searchParams), params: url.pathname.match(route[1]).slice(1) }
    const data = route[2](ctx)
    send(200, { code: 200, message: 'ok', data: data === undefined ? null : data })
  } catch (e) {
    if (e instanceof ApiError) return send(e.code, { code: e.code, message: e.message })
    console.error(e)
    send(500, { code: 500, message: '服务器内部错误' })
  }
})

seedData()
server.listen(PORT, () => {
  console.log(`模拟后端已启动：http://localhost:${PORT}  当前业务时间 ${fmt(now()).replace('T', ' ')}`)
  console.log('演示账号：学生 stu001 / 123456，管理员 admin / admin123')
})
