// 接口封装：全站只有这一个文件直接使用 axios。
// 响应拦截器把后端统一格式 {code, message, data} 拆开，页面拿到的直接是 data；
// 出错时统一弹出后端返回的 message，页面里不用到处写错误提示。
import axios from 'axios'
import { ElMessage } from 'element-plus'

const http = axios.create({ baseURL: '/api', timeout: 15000 })

let onUnauthorized = () => {}
/** 由 main 流程注入：登录失效时如何处理（清除登录态并跳转登录页） */
export function setUnauthorizedHandler(fn) {
  onUnauthorized = fn
}

http.interceptors.request.use((config) => {
  const token = localStorage.getItem('token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

http.interceptors.response.use(
  (res) => res.data.data,
  (err) => {
    const status = err.response?.status
    const message = err.response?.data?.message || (status ? `请求失败（${status}）` : '网络异常，请稍后重试')
    if (status === 401) {
      onUnauthorized()
    }
    // 调用方传 { silent: true } 时不自动弹提示，由页面自己展示错误
    if (!err.config?.silent) {
      ElMessage.error(message)
    }
    const error = new Error(message)
    error.status = status
    error.handled = true // 已提示过，全局错误处理不必再报
    return Promise.reject(error)
  }
)

export default {
  // 认证
  login: (data) => http.post('/auth/login', data),
  register: (data) => http.post('/auth/register', data),
  me: () => http.get('/auth/me'),
  updateProfile: (data) => http.put('/auth/profile', data),
  changePassword: (data) => http.put('/auth/password', data),

  // 学生端查询
  rules: () => http.get('/rules'),
  notices: () => http.get('/notices'),
  rooms: (date) => http.get('/rooms', { params: { date } }),
  seatMap: (roomId, date) => http.get(`/rooms/${roomId}/seats`, { params: { date } }),

  // 预约
  createReservation: (data) => http.post('/reservations', data),
  activeReservations: () => http.get('/reservations/active'),
  myReservations: (params) => http.get('/reservations/mine', { params }),
  cancelReservation: (id) => http.post(`/reservations/${id}/cancel`),
  checkin: (code, config) => http.post('/reservations/checkin', { code }, config),
  away: (id) => http.post(`/reservations/${id}/away`),
  back: (id) => http.post(`/reservations/${id}/back`),
  checkout: (id) => http.post(`/reservations/${id}/checkout`),
  myViolations: (params) => http.get('/violations/mine', { params }),

  // 管理端
  admin: {
    overview: () => http.get('/admin/stats/overview'),
    trend: (days) => http.get('/admin/stats/trend', { params: { days } }),
    hourly: (days) => http.get('/admin/stats/hourly', { params: { days } }),
    roomUsage: (days) => http.get('/admin/stats/rooms', { params: { days } }),

    rooms: () => http.get('/admin/rooms'),
    createRoom: (data) => http.post('/admin/rooms', data),
    updateRoom: (id, data) => http.put(`/admin/rooms/${id}`, data),
    setRoomEnabled: (id, enabled) => http.put(`/admin/rooms/${id}/enabled`, { enabled }),
    deleteRoom: (id) => http.delete(`/admin/rooms/${id}`),
    seats: (roomId) => http.get(`/admin/rooms/${roomId}/seats`),
    updateSeat: (id, data) => http.put(`/admin/seats/${id}`, data),
    resetSeatCode: (id) => http.post(`/admin/seats/${id}/reset-code`),

    reservations: (params) => http.get('/admin/reservations', { params }),
    cancelReservation: (id, reason) => http.post(`/admin/reservations/${id}/cancel`, { reason }),

    users: (params) => http.get('/admin/users', { params }),
    setUserEnabled: (id, enabled) => http.put(`/admin/users/${id}/enabled`, { enabled }),
    unban: (id) => http.post(`/admin/users/${id}/unban`),
    resetPassword: (id) => http.post(`/admin/users/${id}/reset-password`),

    violations: (params) => http.get('/admin/violations', { params }),
    revokeViolation: (id) => http.delete(`/admin/violations/${id}`),

    createNotice: (data) => http.post('/admin/notices', data),
    updateNotice: (id, data) => http.put(`/admin/notices/${id}`, data),
    deleteNotice: (id) => http.delete(`/admin/notices/${id}`)
  }
}
