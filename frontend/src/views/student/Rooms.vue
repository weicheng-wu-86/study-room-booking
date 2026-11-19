<script setup>
import { onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import api from '../../api'
import { useClockStore } from '../../stores'
import { hourLabel } from '../../utils'
import DayTabs from '../../components/DayTabs.vue'

const router = useRouter()
const clock = useClockStore()

const date = ref('')
const rooms = ref([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    rooms.value = await api.rooms(date.value)
  } finally {
    loading.value = false
  }
}

const ratio = (room) => Math.round((room.occupiedSeatHours / (room.totalSeatHours || 1)) * 100)

onMounted(async () => {
  await clock.ensure()
  date.value = clock.today
})
watch(date, load)
</script>

<template>
  <div>
    <h1 class="page-title">预约座位</h1>
    <p class="page-desc">先选日期，再选自习室，进入座位图挑选座位和时段。</p>

    <DayTabs v-model="date" />

    <div v-loading="loading" class="rooms">
      <article v-for="room in rooms" :key="room.id" class="card room" @click="router.push({ path: `/rooms/${room.id}`, query: { date } })">
        <div class="room-top">
          <h3>{{ room.name }}</h3>
          <span class="hours num">{{ hourLabel(room.openHour) }} - {{ hourLabel(room.closeHour) }}</span>
        </div>
        <p class="muted loc">{{ room.location }}</p>
        <p class="desc">{{ room.description }}</p>
        <div class="usage">
          <div class="usage-text">
            <span>共 <b class="num">{{ room.seatCount }}</b> 个座位</span>
            <span class="muted num">当日已约 {{ ratio(room) }}%</span>
          </div>
          <el-progress :percentage="ratio(room)" :show-text="false" :stroke-width="6"
                       :color="ratio(room) >= 80 ? '#c9822b' : '#2f6b58'" />
        </div>
        <el-button type="primary" plain class="go">查看座位</el-button>
      </article>
      <div v-if="!loading && !rooms.length" class="card empty">暂无开放的自习室</div>
    </div>
  </div>
</template>

<style scoped>
.rooms {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 20px;
  margin-top: 20px;
  min-height: 160px;
}
.room {
  cursor: pointer;
  display: flex;
  flex-direction: column;
  transition: transform 0.15s ease, border-color 0.15s ease;
}
.room:hover {
  transform: translateY(-2px);
  border-color: var(--el-color-primary-light-5);
}
.room-top {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: 12px;
}
.room-top h3 {
  font-size: 17px;
}
.hours {
  flex-shrink: 0;
  font-size: 13px;
  color: var(--brand);
  background: var(--brand-soft);
  padding: 2px 8px;
  border-radius: 6px;
}
.loc {
  margin: 6px 0 10px;
  font-size: 13px;
}
.desc {
  margin: 0 0 16px;
  font-size: 14px;
  line-height: 1.7;
  color: var(--ink-2);
  flex: 1;
}
.usage-text {
  display: flex;
  justify-content: space-between;
  font-size: 13px;
  margin-bottom: 8px;
}
.go {
  margin-top: 16px;
}
</style>
