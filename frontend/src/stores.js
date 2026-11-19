// Pinia 状态：登录用户，以及与服务器同步的时钟和预约规则。
import { defineStore } from 'pinia'
import api from './api'
import { parseTime, dateStr } from './utils'

export const useUserStore = defineStore('user', {
  state: () => ({
    token: localStorage.getItem('token') || '',
    user: JSON.parse(localStorage.getItem('user') || 'null')
  }),
  getters: {
    isAdmin: (s) => s.user?.role === 'ADMIN',
    homePath: (s) => (s.user?.role === 'ADMIN' ? '/admin/dashboard' : '/home')
  },
  actions: {
    async login(form) {
      const { token, user } = await api.login(form)
      this.token = token
      this.setUser(user)
      localStorage.setItem('token', token)
    },
    /** 重新拉取用户信息（禁约状态、违约次数可能变化） */
    async refresh() {
      this.setUser(await api.me())
    },
    setUser(user) {
      this.user = user
      localStorage.setItem('user', JSON.stringify(user))
    },
    logout() {
      this.token = ''
      this.user = null
      localStorage.removeItem('token')
      localStorage.removeItem('user')
    }
  }
})

/**
 * 服务器时钟。前端不使用设备本地时间判断“今天是哪天”“签到还剩多久”，
 * 而是在加载规则时记下“服务器时间 - 本地时间”的差值，之后用本地时间加上差值推算服务器时间。
 */
export const useClockStore = defineStore('clock', {
  state: () => ({
    rules: null,
    offset: 0,
    /** 每秒更新一次的服务器当前时间（毫秒，墙上时间），供倒计时使用 */
    now: Date.now(),
    timer: null
  }),
  getters: {
    today: (s) => dateStr(s.now),
    hour: (s) => new Date(s.now).getUTCHours(),
    /** 可预约的日期列表：今天起 maxDaysAhead + 1 天 */
    days(s) {
      const count = (s.rules?.maxDaysAhead ?? 6) + 1
      const start = parseTime(dateStr(s.now))
      return Array.from({ length: count }, (_, i) => dateStr(start + i * 86400000))
    }
  },
  actions: {
    async load() {
      const rules = await api.rules()
      this.rules = rules
      this.offset = parseTime(rules.serverTime) - Date.now()
      this.now = Date.now() + this.offset
      if (!this.timer) {
        this.timer = setInterval(() => {
          this.now = Date.now() + this.offset
        }, 1000)
      }
    },
    async ensure() {
      if (!this.rules) await this.load()
    }
  }
})
