<script setup>
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { DataAnalysis, OfficeBuilding, Tickets, User, Warning, Bell, SwitchButton } from '@element-plus/icons-vue'
import { useUserStore, useClockStore } from '../stores'

const router = useRouter()
const userStore = useUserStore()
const clock = useClockStore()

const menus = [
  { path: '/admin/dashboard', label: '数据概览', icon: DataAnalysis },
  { path: '/admin/rooms', label: '自习室管理', icon: OfficeBuilding },
  { path: '/admin/reservations', label: '预约管理', icon: Tickets },
  { path: '/admin/users', label: '用户管理', icon: User },
  { path: '/admin/violations', label: '违约记录', icon: Warning },
  { path: '/admin/notices', label: '公告管理', icon: Bell }
]

onMounted(() => clock.ensure())

function logout() {
  userStore.logout()
  router.replace('/login')
}
</script>

<template>
  <div class="admin">
    <aside class="side">
      <div class="side-brand">
        <span class="brand-mark">习</span>
        <div>
          <div class="brand-name">自习室预约</div>
          <div class="brand-sub">管理后台</div>
        </div>
      </div>
      <nav class="menu">
        <router-link v-for="m in menus" :key="m.path" :to="m.path" class="menu-item" active-class="active">
          <el-icon><component :is="m.icon" /></el-icon>
          <span>{{ m.label }}</span>
        </router-link>
      </nav>
      <div class="side-foot">
        <div class="who">{{ userStore.user?.realName }}</div>
        <el-button text :icon="SwitchButton" @click="logout">退出</el-button>
      </div>
    </aside>
    <main class="main">
      <router-view />
    </main>
  </div>
</template>

<style scoped>
.admin {
  display: flex;
  min-height: 100%;
}
.side {
  position: sticky;
  top: 0;
  height: 100vh;
  width: 216px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  background: #1f2a26;
  color: #cfd8d4;
}
.side-brand {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 22px 20px 18px;
}
.brand-mark {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  background: var(--brand);
  color: #fff;
  display: grid;
  place-items: center;
  font-size: 17px;
  font-weight: 600;
}
.brand-name {
  color: #fff;
  font-weight: 600;
  font-size: 15px;
}
.brand-sub {
  font-size: 12px;
  color: #8b9993;
  margin-top: 2px;
}
.menu {
  flex: 1;
  padding: 8px 12px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.menu-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 11px 12px;
  border-radius: 8px;
  color: #b9c4bf;
  font-size: 14px;
}
.menu-item:hover {
  background: rgba(255, 255, 255, 0.06);
  color: #fff;
}
.menu-item.active {
  background: var(--brand);
  color: #fff;
}
.side-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 16px 18px 20px;
  border-top: 1px solid rgba(255, 255, 255, 0.08);
  font-size: 13px;
}
.side-foot .el-button {
  color: #b9c4bf;
}
.main {
  flex: 1;
  min-width: 0;
  padding: 28px 32px 48px;
}

@media (max-width: 900px) {
  .side {
    width: 64px;
  }
  .side-brand > div,
  .menu-item span,
  .who {
    display: none;
  }
  .main {
    padding: 20px 16px 40px;
  }
}
</style>
