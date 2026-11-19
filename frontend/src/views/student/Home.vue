<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import api from '../../api'
import { useUserStore, useClockStore } from '../../stores'
import { dayLabel, hourLabel, mdhm, percent } from '../../utils'
import ReservationCard from '../../components/ReservationCard.vue'

const router = useRouter()
const userStore = useUserStore()
const clock = useClockStore()

const active = ref([])
const rooms = ref([])
const notices = ref([])
const loading = ref(true)
const openNotice = ref()

const greeting = computed(() => {
  const h = clock.hour
  if (h < 6) return '夜深了'
  if (h < 12) return '上午好'
  if (h < 14) return '中午好'
  if (h < 18) return '下午好'
  return '晚上好'
})
const today = computed(() => dayLabel(clock.today))

async function loadActive() {
  active.value = await api.activeReservations()
  userStore.refresh().catch(() => {})
}

onMounted(async () => {
  await clock.ensure()
  try {
    ;[active.value, rooms.value, notices.value] = await Promise.all([
      api.activeReservations(),
      api.rooms(clock.today),
      api.notices()
    ])
    openNotice.value = notices.value.find((n) => n.pinned)?.id
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div v-loading="loading">
    <div class="hello">
      <div>
        <h1 class="page-title">{{ greeting }}，{{ userStore.user?.realName }}</h1>
        <p class="page-desc">今天是 {{ today.md }} {{ today.week }}，祝你学习顺利。</p>
      </div>
      <el-button type="primary" size="large" @click="router.push('/rooms')">预约座位</el-button>
    </div>

    <el-alert v-if="userStore.user?.banned" type="error" :closable="false" show-icon class="ban"
              :title="`你在 ${mdhm(userStore.user.banUntil)} 前无法预约`"
              :description="`原因：最近 ${clock.rules?.windowDays} 天内累计违约 ${userStore.user.recentViolations} 次。已有的预约不受影响。`" />

    <section class="block">
      <div class="block-title">当前预约</div>
      <div v-if="active.length" class="stack">
        <ReservationCard v-for="r in active" :key="r.id" :reservation="r" @changed="loadActive" />
      </div>
      <div v-else class="card empty">
        目前没有进行中的预约，<a @click="router.push('/rooms')">去选一个座位</a>
      </div>
    </section>

    <div class="grid">
      <section class="card">
        <div class="card-title">
          今日自习室
          <router-link to="/rooms" class="more">全部 ›</router-link>
        </div>
        <div v-for="room in rooms" :key="room.id" class="room" @click="router.push(`/rooms/${room.id}`)">
          <div class="room-head">
            <span class="room-name">{{ room.name }}</span>
            <span class="faint num">{{ hourLabel(room.openHour) }}-{{ hourLabel(room.closeHour) }}</span>
          </div>
          <div class="room-meta">
            <span class="muted">{{ room.location }} · {{ room.seatCount }} 座</span>
            <span class="num muted">今日已约 {{ percent(room.occupiedSeatHours / (room.totalSeatHours || 1)) }}</span>
          </div>
          <el-progress :percentage="Math.round((room.occupiedSeatHours / (room.totalSeatHours || 1)) * 100)"
                       :show-text="false" :stroke-width="6" />
        </div>
        <div v-if="!rooms.length" class="empty">暂无开放的自习室</div>
      </section>

      <section class="card">
        <div class="card-title">公告</div>
        <el-collapse v-model="openNotice" accordion>
          <el-collapse-item v-for="n in notices" :key="n.id" :name="n.id">
            <template #title>
              <el-tag v-if="n.pinned" size="small" type="warning" effect="plain" class="pin">置顶</el-tag>
              <span class="notice-title">{{ n.title }}</span>
            </template>
            <p class="notice-body">{{ n.content }}</p>
            <p class="faint notice-time">{{ mdhm(n.createdAt) }} 发布</p>
          </el-collapse-item>
        </el-collapse>
        <div v-if="!notices.length" class="empty">暂无公告</div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.hello {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
.ban {
  margin-bottom: 20px;
}
.block {
  margin-bottom: 24px;
}
.block-title {
  font-size: 16px;
  font-weight: 600;
  margin-bottom: 12px;
}
.stack {
  display: grid;
  gap: 12px;
}
.empty a {
  cursor: pointer;
  font-weight: 600;
}
.grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 20px;
  align-items: start;
}
.more {
  font-size: 13px;
  font-weight: 400;
}
.room {
  padding: 12px 0;
  border-top: 1px solid var(--line);
  cursor: pointer;
}
.room:first-of-type {
  border-top: none;
  padding-top: 0;
}
.room:hover .room-name {
  color: var(--brand);
}
.room-head,
.room-meta {
  display: flex;
  justify-content: space-between;
  gap: 12px;
}
.room-name {
  font-weight: 600;
}
.room-meta {
  font-size: 13px;
  margin: 4px 0 8px;
}
.pin {
  margin-right: 8px;
}
.notice-title {
  font-size: 14px;
}
.notice-body {
  margin: 0 0 6px;
  line-height: 1.8;
  color: var(--ink-2);
}
.notice-time {
  margin: 0;
  font-size: 12px;
}
:deep(.el-collapse) {
  border-top: none;
  border-bottom: none;
}

@media (max-width: 820px) {
  .grid {
    grid-template-columns: 1fr;
  }
  .hello .el-button {
    display: none;
  }
}
</style>
