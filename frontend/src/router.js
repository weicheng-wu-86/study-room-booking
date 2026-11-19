import { createRouter, createWebHashHistory } from 'vue-router'
import { useUserStore } from './stores'
import { setUnauthorizedHandler } from './api'

// 页面组件全部按需加载，学生不会下载到管理端的代码
const routes = [
  { path: '/login', component: () => import('./views/Login.vue'), meta: { public: true } },
  {
    path: '/',
    component: () => import('./layouts/StudentLayout.vue'),
    meta: { role: 'STUDENT' },
    children: [
      { path: '', redirect: '/home' },
      { path: 'home', component: () => import('./views/student/Home.vue') },
      { path: 'rooms', component: () => import('./views/student/Rooms.vue') },
      { path: 'rooms/:id', component: () => import('./views/student/SeatMap.vue') },
      { path: 'reservations', component: () => import('./views/student/MyReservations.vue') },
      { path: 'checkin', component: () => import('./views/student/Checkin.vue') },
      { path: 'profile', component: () => import('./views/student/Profile.vue') }
    ]
  },
  {
    path: '/admin',
    component: () => import('./layouts/AdminLayout.vue'),
    meta: { role: 'ADMIN' },
    children: [
      { path: '', redirect: '/admin/dashboard' },
      { path: 'dashboard', component: () => import('./views/admin/Dashboard.vue') },
      { path: 'rooms', component: () => import('./views/admin/Rooms.vue') },
      { path: 'reservations', component: () => import('./views/admin/Reservations.vue') },
      { path: 'users', component: () => import('./views/admin/Users.vue') },
      { path: 'violations', component: () => import('./views/admin/Violations.vue') },
      { path: 'notices', component: () => import('./views/admin/Notices.vue') }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/' }
]

const router = createRouter({
  // 使用 hash 路由，部署时不需要服务器做地址重写
  history: createWebHashHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 })
})

// 路由守卫：未登录跳登录页（并记住原本要去的地址，例如扫码签到链接）；角色不匹配跳回各自首页。
// 这里只是为了界面体验，真正的权限校验在后端拦截器中完成。
router.beforeEach((to) => {
  const store = useUserStore()
  if (to.meta.public) {
    return store.token && to.path === '/login' ? store.homePath : true
  }
  if (!store.token) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  const role = to.matched.find((r) => r.meta.role)?.meta.role
  if (role && role !== store.user?.role) {
    return store.homePath
  }
  return true
})

setUnauthorizedHandler(() => {
  const store = useUserStore()
  store.logout()
  if (router.currentRoute.value.path !== '/login') {
    router.replace({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } })
  }
})

export default router
