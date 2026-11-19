<script setup>
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowDown } from '@element-plus/icons-vue'
import { useUserStore, useClockStore } from '../stores'

const router = useRouter()
const userStore = useUserStore()
const clock = useClockStore()

const links = [
  { path: '/home', label: '首页' },
  { path: '/rooms', label: '预约座位' },
  { path: '/reservations', label: '我的预约' },
  { path: '/profile', label: '个人中心' }
]

onMounted(() => {
  clock.ensure()
  userStore.refresh().catch(() => {})
})

function onCommand(cmd) {
  if (cmd === 'logout') {
    userStore.logout()
    router.replace('/login')
  } else {
    router.push(cmd)
  }
}
</script>

<template>
  <div class="shell">
    <header class="topbar">
      <div class="topbar-inner">
        <router-link to="/home" class="brand">
          <span class="brand-mark">习</span>
          <span class="brand-name">自习室预约</span>
        </router-link>
        <nav class="nav">
          <router-link v-for="l in links" :key="l.path" :to="l.path" class="nav-link" active-class="active">
            {{ l.label }}
          </router-link>
        </nav>
        <el-dropdown trigger="click" @command="onCommand">
          <span class="user">
            {{ userStore.user?.realName }}
            <el-icon><ArrowDown /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="/profile">个人中心</el-dropdown-item>
              <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </header>

    <main class="content">
      <router-view />
    </main>

    <!-- 手机端底部导航 -->
    <nav class="tabbar">
      <router-link v-for="l in links" :key="l.path" :to="l.path" class="tab" active-class="active">
        {{ l.label }}
      </router-link>
    </nav>
  </div>
</template>

<style scoped>
.shell {
  min-height: 100%;
  display: flex;
  flex-direction: column;
}
.topbar {
  position: sticky;
  top: 0;
  z-index: 20;
  background: rgba(255, 255, 255, 0.92);
  backdrop-filter: blur(8px);
  border-bottom: 1px solid var(--line);
}
.topbar-inner {
  max-width: 1120px;
  margin: 0 auto;
  height: 60px;
  padding: 0 20px;
  display: flex;
  align-items: center;
  gap: 32px;
}
.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--ink);
  font-weight: 600;
  font-size: 17px;
}
.brand-mark {
  width: 32px;
  height: 32px;
  border-radius: 9px;
  background: var(--brand);
  color: #fff;
  display: grid;
  place-items: center;
  font-size: 16px;
}
.nav {
  display: flex;
  gap: 4px;
  flex: 1;
}
.nav-link {
  padding: 8px 14px;
  border-radius: 8px;
  color: var(--ink-2);
  font-size: 15px;
}
.nav-link:hover {
  background: var(--brand-soft);
}
.nav-link.active {
  color: var(--brand);
  background: var(--brand-soft);
  font-weight: 600;
}
.user {
  display: flex;
  align-items: center;
  gap: 4px;
  cursor: pointer;
  color: var(--ink);
  font-size: 14px;
}
.content {
  flex: 1;
  width: 100%;
  max-width: 1120px;
  margin: 0 auto;
  padding: 28px 20px 48px;
}
.tabbar {
  display: none;
}

@media (max-width: 640px) {
  .nav {
    display: none;
  }
  .topbar-inner {
    justify-content: space-between;
    height: 52px;
  }
  .content {
    padding: 16px 14px 84px;
  }
  .tabbar {
    position: fixed;
    bottom: 0;
    left: 0;
    right: 0;
    z-index: 20;
    display: grid;
    grid-template-columns: repeat(4, 1fr);
    background: #fff;
    border-top: 1px solid var(--line);
    padding-bottom: env(safe-area-inset-bottom);
  }
  .tab {
    padding: 14px 0;
    text-align: center;
    font-size: 13px;
    color: var(--ink-2);
  }
  .tab.active {
    color: var(--brand);
    font-weight: 600;
  }
}
</style>
