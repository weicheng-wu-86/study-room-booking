<script setup>
import { computed, onMounted, ref } from 'vue'
import api from '../../api'
import { percent } from '../../utils'
import BaseChart from '../../components/BaseChart.vue'

const days = ref(7)
const overview = ref(null)
const trend = ref([])
const hourly = ref([])
const roomUsage = ref([])
const loading = ref(true)

const INK = '#5d6b65'
const GRID = '#ece8de'
const axisStyle = {
  axisLine: { lineStyle: { color: GRID } },
  axisTick: { show: false },
  axisLabel: { color: INK },
  splitLine: { lineStyle: { color: GRID } }
}

const kpis = computed(() => {
  const o = overview.value
  if (!o) return []
  return [
    { label: '今日预约', value: o.todayReservations, unit: '次' },
    { label: '当前在座', value: o.inUseNow, unit: '人' },
    { label: '今日座位使用率', value: percent(o.todayUsageRate), unit: '' },
    { label: '可用座位', value: o.seatCount, unit: '个' },
    { label: '近 7 天违约', value: o.weekViolations, unit: '次', warn: o.weekViolations > 0 },
    { label: '学生用户', value: o.studentCount, unit: '人' }
  ]
})

// 每日预约量及其结果构成：完成 / 爽约 / 取消 / 其他（待签到、使用中等尚未结束的）
const trendOption = computed(() => {
  const x = trend.value.map((p) => p.date.slice(5))
  const series = [
    { name: '已完成', color: '#2f6b58', data: trend.value.map((p) => p.completed) },
    { name: '进行中/待签到', color: '#97b5ab', data: trend.value.map((p) => p.total - p.completed - p.noShow - p.cancelled) },
    { name: '已取消', color: '#cfcabd', data: trend.value.map((p) => p.cancelled) },
    { name: '爽约', color: '#c0483c', data: trend.value.map((p) => p.noShow) }
  ]
  return {
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    legend: { bottom: 0, textStyle: { color: INK }, itemWidth: 12, itemHeight: 12 },
    grid: { left: 8, right: 8, top: 16, bottom: 36, containLabel: true },
    xAxis: { type: 'category', data: x, ...axisStyle },
    yAxis: { type: 'value', minInterval: 1, ...axisStyle, axisLine: { show: false } },
    series: series.map((s) => ({
      name: s.name,
      type: 'bar',
      stack: 'all',
      barMaxWidth: 32,
      itemStyle: { color: s.color },
      data: s.data
    }))
  }
})

const hourlyOption = computed(() => ({
  tooltip: { trigger: 'axis', valueFormatter: (v) => `${v} 个座位` },
  grid: { left: 8, right: 12, top: 16, bottom: 8, containLabel: true },
  xAxis: { type: 'category', data: hourly.value.map((p) => `${p.hour}时`), boundaryGap: false, ...axisStyle },
  yAxis: { type: 'value', ...axisStyle, axisLine: { show: false } },
  series: [
    {
      type: 'line',
      smooth: true,
      symbol: 'circle',
      symbolSize: 6,
      lineStyle: { color: '#2f6b58', width: 2.5 },
      itemStyle: { color: '#2f6b58' },
      areaStyle: { color: 'rgba(47, 107, 88, 0.12)' },
      data: hourly.value.map((p) => p.avgSeats)
    }
  ]
}))

const usageOption = computed(() => ({
  tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' }, valueFormatter: (v) => `${v}%` },
  grid: { left: 8, right: 48, top: 8, bottom: 8, containLabel: true },
  xAxis: { type: 'value', max: 100, ...axisStyle, axisLabel: { color: INK, formatter: '{value}%' }, axisLine: { show: false } },
  yAxis: { type: 'category', inverse: true, data: roomUsage.value.map((r) => r.roomName), ...axisStyle, splitLine: { show: false } },
  series: [
    {
      type: 'bar',
      barMaxWidth: 22,
      itemStyle: { color: '#2f6b58', borderRadius: [0, 6, 6, 0] },
      label: { show: true, position: 'right', color: INK, formatter: '{c}%' },
      data: roomUsage.value.map((r) => Math.round(r.rate * 1000) / 10)
    }
  ]
}))

async function load() {
  loading.value = true
  try {
    ;[overview.value, trend.value, hourly.value, roomUsage.value] = await Promise.all([
      api.admin.overview(),
      api.admin.trend(days.value),
      api.admin.hourly(days.value),
      api.admin.roomUsage(days.value)
    ])
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div v-loading="loading">
    <div class="head">
      <div>
        <h1 class="page-title">数据概览</h1>
        <p class="page-desc">自习室使用情况一览，帮助判断座位是否够用、开放时间是否合理。</p>
      </div>
      <el-radio-group v-model="days" @change="load">
        <el-radio-button :value="7">近 7 天</el-radio-button>
        <el-radio-button :value="14">近 14 天</el-radio-button>
        <el-radio-button :value="30">近 30 天</el-radio-button>
      </el-radio-group>
    </div>

    <div class="kpis">
      <div v-for="k in kpis" :key="k.label" class="card kpi">
        <div class="kpi-label">{{ k.label }}</div>
        <div class="kpi-value num" :class="{ warn: k.warn }">{{ k.value }}<small>{{ k.unit }}</small></div>
      </div>
    </div>

    <div class="charts">
      <section class="card wide">
        <div class="card-title">每日预约量与结果构成</div>
        <BaseChart :option="trendOption" height="300px" />
      </section>
      <section class="card">
        <div class="card-title">各时段平均被预约座位数</div>
        <BaseChart :option="hourlyOption" height="260px" />
      </section>
      <section class="card">
        <div class="card-title">各自习室座位使用率</div>
        <BaseChart :option="usageOption" height="260px" />
      </section>
    </div>
  </div>
</template>

<style scoped>
.head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  flex-wrap: wrap;
}
.kpis {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: 16px;
  margin-bottom: 20px;
}
.kpi {
  padding: 16px 18px;
}
.kpi-label {
  font-size: 13px;
  color: var(--ink-2);
}
.kpi-value {
  margin-top: 8px;
  font-size: 26px;
  font-weight: 700;
}
.kpi-value small {
  margin-left: 4px;
  font-size: 13px;
  font-weight: 400;
  color: var(--ink-3);
}
.kpi-value.warn {
  color: var(--danger);
}
.charts {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 20px;
}
.wide {
  grid-column: 1 / -1;
}

@media (max-width: 1200px) {
  .kpis {
    grid-template-columns: repeat(3, 1fr);
  }
}
@media (max-width: 900px) {
  .charts {
    grid-template-columns: 1fr;
  }
  .kpis {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
