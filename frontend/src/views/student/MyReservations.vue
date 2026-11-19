<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import api from '../../api'
import { useClockStore } from '../../stores'
import ReservationCard from '../../components/ReservationCard.vue'

const router = useRouter()
const clock = useClockStore()

const filters = [
  { value: '', label: '全部' },
  { value: 'RESERVED', label: '待签到' },
  { value: 'IN_USE', label: '使用中' },
  { value: 'COMPLETED', label: '已完成' },
  { value: 'CANCELLED', label: '已取消' },
  { value: 'NO_SHOW', label: '爽约' }
]
const query = reactive({ status: '', page: 1, size: 8 })
const list = ref([])
const total = ref(0)
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const res = await api.myReservations({ ...query, status: query.status || undefined })
    list.value = res.records
    total.value = res.total
  } finally {
    loading.value = false
  }
}

function changeStatus(value) {
  query.status = value
  query.page = 1
  load()
}

onMounted(async () => {
  await clock.ensure()
  load()
})
</script>

<template>
  <div>
    <h1 class="page-title">我的预约</h1>
    <p class="page-desc">在这里签到、暂离、签退或取消预约，也可以查看历史记录。</p>

    <div class="filters">
      <button v-for="f in filters" :key="f.value" type="button" class="chip" :class="{ on: f.value === query.status }"
              @click="changeStatus(f.value)">
        {{ f.label }}
      </button>
    </div>

    <div v-loading="loading" class="list">
      <ReservationCard v-for="r in list" :key="r.id" :reservation="r" @changed="load" />
      <div v-if="!loading && !list.length" class="card empty">
        没有符合条件的预约记录，<a @click="router.push('/rooms')">去预约座位</a>
      </div>
    </div>

    <div class="pager">
      <el-pagination v-model:current-page="query.page" :page-size="query.size" :total="total" layout="total, prev, pager, next"
                     hide-on-single-page background @current-change="load" />
    </div>
  </div>
</template>

<style scoped>
.filters {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 16px;
}
.chip {
  padding: 6px 16px;
  border-radius: 999px;
  border: 1px solid var(--line);
  background: #fff;
  color: var(--ink-2);
  font-size: 14px;
  font-family: inherit;
  cursor: pointer;
}
.chip.on {
  background: var(--brand);
  border-color: var(--brand);
  color: #fff;
}
.list {
  display: grid;
  gap: 12px;
  min-height: 120px;
}
.empty a {
  cursor: pointer;
  font-weight: 600;
}
</style>
