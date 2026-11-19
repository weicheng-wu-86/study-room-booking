<script setup>
// 选座页：按“日期 + 时段”显示每个座位是否可约，点选座位后确认预约。
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft } from '@element-plus/icons-vue'
import api from '../../api'
import { useClockStore, useUserStore } from '../../stores'
import { dayLabel, hourLabel } from '../../utils'
import DayTabs from '../../components/DayTabs.vue'

const route = useRoute()
const router = useRouter()
const clock = useClockStore()
const userStore = useUserStore()

const roomId = Number(route.params.id)
const date = ref('')
const data = ref(null) // { room, date, seats }
const loading = ref(true)
const startHour = ref(null)
const endHour = ref(null)
const selectedId = ref(null)
const onlySocket = ref(false)
const onlyWindow = ref(false)
const submitting = ref(false)
let timer = null

const room = computed(() => data.value?.room)
const maxHours = computed(() => clock.rules?.maxHoursPerReservation ?? 4)

/** 可选的开始时间：开放时间内，且今天不能早于当前这个小时 */
const startOptions = computed(() => {
  if (!room.value) return []
  const first = date.value === clock.today ? Math.max(room.value.openHour, clock.hour) : room.value.openHour
  const list = []
  for (let h = first; h < room.value.closeHour; h++) list.push(h)
  return list
})
/** 可选的结束时间：开始之后、不超过单次上限和关闭时间 */
const endOptions = computed(() => {
  if (!room.value || startHour.value == null) return []
  const last = Math.min(startHour.value + maxHours.value, room.value.closeHour)
  const list = []
  for (let h = startHour.value + 1; h <= last; h++) list.push(h)
  return list
})

// 日期或开放时间变化后，把时段修正到合法范围内
watch(startOptions, (opts) => {
  if (!opts.length) {
    startHour.value = null
    endHour.value = null
  } else if (!opts.includes(startHour.value)) {
    startHour.value = opts[0]
  }
})
watch([startHour, endOptions], () => {
  if (!endOptions.value.length) {
    endHour.value = null
  } else if (!endOptions.value.includes(endHour.value)) {
    endHour.value = endOptions.value[Math.min(1, endOptions.value.length - 1)] // 默认 2 小时
  }
})

const range = computed(() => {
  const list = []
  if (startHour.value != null && endHour.value != null) {
    for (let h = startHour.value; h < endHour.value; h++) list.push(h)
  }
  return list
})

/** 每个座位在所选时段内的状态：disabled 停用 / mine 我已约 / taken 已被占 / free 可约 */
function stateOf(seat) {
  if (!seat.enabled) return 'disabled'
  if (range.value.some((h) => seat.myHours.includes(h))) return 'mine'
  if (range.value.some((h) => seat.occupiedHours.includes(h))) return 'taken'
  return 'free'
}

const seats = computed(() =>
  (data.value?.seats ?? []).map((s) => ({
    ...s,
    state: stateOf(s),
    dim: (onlySocket.value && !s.hasSocket) || (onlyWindow.value && !s.nearWindow)
  }))
)
const freeCount = computed(() => seats.value.filter((s) => s.state === 'free').length)
const selected = computed(() => seats.value.find((s) => s.id === selectedId.value))

/** 所选座位当天每个小时的情况，用于右侧的时间条 */
const timeline = computed(() => {
  if (!room.value || !selected.value) return []
  const list = []
  for (let h = room.value.openHour; h < room.value.closeHour; h++) {
    let state = 'free'
    if (selected.value.myHours.includes(h)) state = 'mine'
    else if (selected.value.occupiedHours.includes(h)) state = 'taken'
    else if (date.value === clock.today && h < clock.hour) state = 'past'
    list.push({ hour: h, state, picked: range.value.includes(h) })
  }
  return list
})

async function load(silent = false) {
  if (!date.value) return
  if (!silent) loading.value = true
  try {
    data.value = await api.seatMap(roomId, date.value)
    // 选中的座位如果刚被别人约走，取消选中
    if (selected.value && selected.value.state !== 'free') selectedId.value = null
  } finally {
    loading.value = false
  }
}

function pick(seat) {
  if (seat.state === 'free') {
    selectedId.value = seat.id === selectedId.value ? null : seat.id
  } else if (seat.state === 'taken') {
    ElMessage.info(`座位 ${seat.seatNo} 在所选时段已被预约，可以换个时段试试`)
  } else if (seat.state === 'mine') {
    ElMessage.info('这个时段你已经预约了这个座位')
  }
}

async function submit() {
  submitting.value = true
  try {
    const r = await api.createReservation({
      seatId: selected.value.id,
      date: date.value,
      startHour: startHour.value,
      endHour: endHour.value
    })
    ElMessage.success(`预约成功：${r.roomName} ${r.seatNo}`)
    router.push('/reservations')
  } catch {
    // 最常见的失败是座位刚被别人约走，刷新座位图让用户重新选择
    selectedId.value = null
    load(true)
  } finally {
    submitting.value = false
  }
}

watch(date, () => {
  selectedId.value = null
  load()
})

onMounted(async () => {
  await clock.ensure()
  const q = route.query.date
  date.value = typeof q === 'string' && clock.days.includes(q) ? q : clock.today
  // 每 15 秒静默刷新一次，及时反映其他同学的预约
  timer = setInterval(() => load(true), 15000)
})
onBeforeUnmount(() => clearInterval(timer))
</script>

<template>
  <div v-loading="loading">
    <a class="back" @click="router.push('/rooms')"><el-icon><ArrowLeft /></el-icon>返回自习室列表</a>
    <template v-if="room">
      <h1 class="page-title">{{ room.name }}</h1>
      <p class="page-desc">
        {{ room.location }} · 开放时间 {{ hourLabel(room.openHour) }}-{{ hourLabel(room.closeHour) }} · {{ room.seatCount }} 个座位
      </p>

      <DayTabs v-model="date" />

      <div class="layout">
        <section class="card map-card">
          <div class="controls">
            <div class="time">
              <span class="label">时段</span>
              <el-select v-model="startHour" placeholder="开始" style="width: 104px" :disabled="!startOptions.length">
                <el-option v-for="h in startOptions" :key="h" :value="h" :label="hourLabel(h)" />
              </el-select>
              <span class="faint">至</span>
              <el-select v-model="endHour" placeholder="结束" style="width: 104px" :disabled="!endOptions.length">
                <el-option v-for="h in endOptions" :key="h" :value="h" :label="hourLabel(h)" />
              </el-select>
            </div>
            <div class="filters">
              <el-checkbox v-model="onlySocket">有插座</el-checkbox>
              <el-checkbox v-model="onlyWindow">靠窗</el-checkbox>
            </div>
          </div>

          <div v-if="!startOptions.length" class="empty">今天的开放时间已结束，请选择其他日期</div>
          <template v-else>
            <div class="front">前方 · 入口</div>
            <div class="seat-scroll">
              <div class="seat-grid" :style="{ gridTemplateColumns: `repeat(${room.colCount}, var(--seat))` }">
                <button v-for="s in seats" :key="s.id" type="button" class="seat"
                        :class="[s.state, { picked: s.id === selectedId, dim: s.dim }]"
                        :style="{ gridRow: s.rowNo, gridColumn: s.colNo }"
                        :title="s.seatNo + (s.hasSocket ? ' · 有插座' : '') + (s.nearWindow ? ' · 靠窗' : '')"
                        @click="pick(s)">
                  <span class="no num">{{ s.seatNo }}</span>
                  <span class="marks">
                    <i v-if="s.hasSocket" class="mark socket" />
                    <i v-if="s.nearWindow" class="mark window" />
                  </span>
                </button>
              </div>
            </div>
            <div class="legend">
              <span><i class="swatch free" />可预约（{{ freeCount }}）</span>
              <span><i class="swatch taken" />已被预约</span>
              <span><i class="swatch mine" />我的预约</span>
              <span><i class="swatch disabled" />暂停使用</span>
              <span><i class="mark socket" />插座</span>
              <span><i class="mark window" />靠窗</span>
            </div>
          </template>
        </section>

        <aside class="card side">
          <template v-if="selected">
            <div class="side-seat">
              <span class="big num">{{ selected.seatNo }}</span>
              <div class="tags">
                <el-tag v-if="selected.hasSocket" size="small" effect="plain">有插座</el-tag>
                <el-tag v-if="selected.nearWindow" size="small" effect="plain" type="warning">靠窗</el-tag>
              </div>
            </div>
            <div class="sub">当天占用情况</div>
            <div class="timeline">
              <div v-for="t in timeline" :key="t.hour" class="tl" :class="[t.state, { picked: t.picked }]"
                   :title="hourLabel(t.hour)">
                <span class="num">{{ t.hour }}</span>
              </div>
            </div>
            <dl class="summary">
              <div><dt>日期</dt><dd>{{ dayLabel(date).md }} {{ dayLabel(date).week }}</dd></div>
              <div><dt>时段</dt><dd class="num">{{ hourLabel(startHour) }} - {{ hourLabel(endHour) }}（{{ endHour - startHour }} 小时）</dd></div>
            </dl>
            <el-button type="primary" size="large" class="confirm" :loading="submitting"
                       :disabled="userStore.user?.banned" @click="submit">
              {{ userStore.user?.banned ? '禁约期间无法预约' : '确认预约' }}
            </el-button>
          </template>
          <div v-else class="pick-tip">
            <div class="pick-title">请在座位图上选择一个座位</div>
            <p>白色座位表示在所选时段内可以预约。</p>
          </div>
          <ul v-if="clock.rules" class="rules">
            <li>单次最长 {{ clock.rules.maxHoursPerReservation }} 小时，每天累计不超过 {{ clock.rules.maxHoursPerDay }} 小时</li>
            <li>开始前 {{ clock.rules.checkinEarlyMinutes }} 分钟起可签到，开始后 {{ clock.rules.checkinLateMinutes }} 分钟内未签到记爽约</li>
            <li>{{ clock.rules.windowDays }} 天内违约 {{ clock.rules.maxViolations }} 次，暂停预约 {{ clock.rules.banDays }} 天</li>
          </ul>
        </aside>
      </div>
    </template>
  </div>
</template>

<style scoped>
.back {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  color: var(--ink-2);
  cursor: pointer;
  margin-bottom: 10px;
}
.layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 320px;
  gap: 20px;
  margin-top: 20px;
  align-items: start;
}
.controls {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 20px;
}
.time {
  display: flex;
  align-items: center;
  gap: 8px;
}
.label {
  font-size: 14px;
  color: var(--ink-2);
}
.front {
  margin: 0 auto 18px;
  max-width: 420px;
  text-align: center;
  font-size: 12px;
  color: var(--ink-3);
  letter-spacing: 0.2em;
  padding: 6px 0;
  border-radius: 6px;
  background: #f3f1ec;
}
.seat-scroll {
  overflow-x: auto;
  padding-bottom: 6px;
}
.seat-grid {
  --seat: 48px;
  display: grid;
  gap: 10px;
  justify-content: center;
  width: max-content;
  margin: 0 auto;
}
.seat {
  position: relative;
  width: var(--seat);
  height: var(--seat);
  border-radius: 10px;
  border: 1.5px solid var(--el-color-primary-light-5);
  background: #fff;
  color: var(--brand);
  font-family: inherit;
  cursor: pointer;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 3px;
  transition: transform 0.12s ease, box-shadow 0.12s ease, opacity 0.12s ease;
}
.seat .no {
  font-size: 13px;
  font-weight: 600;
  line-height: 1;
}
.seat.free:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 10px rgba(47, 107, 88, 0.18);
}
.seat.picked {
  background: var(--brand);
  border-color: var(--brand);
  color: #fff;
}
.seat.taken {
  background: #eceae4;
  border-color: #e0ddd5;
  color: #a9a79f;
  cursor: not-allowed;
}
.seat.mine {
  background: var(--accent-soft);
  border-color: var(--accent);
  color: var(--accent);
}
.seat.disabled {
  background: transparent;
  border: 1.5px dashed #d9d5ca;
  color: #c9c5ba;
  cursor: not-allowed;
}
.seat.dim {
  opacity: 0.28;
}
.marks {
  display: flex;
  gap: 3px;
  height: 5px;
}
.mark {
  display: inline-block;
  width: 5px;
  height: 5px;
  border-radius: 50%;
}
.mark.socket {
  background: #3a86c8;
}
.mark.window {
  background: #e0a030;
}
.seat.picked .mark {
  background: #fff;
}
.legend {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 8px 18px;
  margin-top: 22px;
  font-size: 12px;
  color: var(--ink-2);
}
.legend span {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.swatch {
  width: 14px;
  height: 14px;
  border-radius: 4px;
  border: 1.5px solid var(--el-color-primary-light-5);
  background: #fff;
}
.swatch.taken {
  background: #eceae4;
  border-color: #e0ddd5;
}
.swatch.mine {
  background: var(--accent-soft);
  border-color: var(--accent);
}
.swatch.disabled {
  background: transparent;
  border: 1.5px dashed #d9d5ca;
}
.side {
  position: sticky;
  top: 80px;
}
.side-seat {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}
.big {
  font-size: 30px;
  font-weight: 700;
  color: var(--brand);
}
.tags {
  display: flex;
  gap: 6px;
}
.sub {
  font-size: 13px;
  color: var(--ink-2);
  margin-bottom: 8px;
}
.timeline {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(30px, 1fr));
  gap: 4px;
}
.tl {
  height: 30px;
  border-radius: 6px;
  background: var(--brand-soft);
  color: var(--brand);
  display: grid;
  place-items: center;
  font-size: 12px;
}
.tl.taken {
  background: #eceae4;
  color: #a9a79f;
}
.tl.past {
  background: #f6f4ef;
  color: #cfcbc0;
}
.tl.mine {
  background: var(--accent-soft);
  color: var(--accent);
}
.tl.picked {
  background: var(--brand);
  color: #fff;
  font-weight: 600;
}
.summary {
  margin: 18px 0;
  display: grid;
  gap: 8px;
  font-size: 14px;
}
.summary div {
  display: flex;
  justify-content: space-between;
  gap: 12px;
}
.summary dt {
  color: var(--ink-2);
}
.summary dd {
  margin: 0;
  font-weight: 600;
}
.confirm {
  width: 100%;
}
.pick-tip {
  padding: 8px 0 4px;
}
.pick-title {
  font-weight: 600;
  margin-bottom: 6px;
}
.pick-tip p {
  margin: 0;
  font-size: 13px;
  color: var(--ink-2);
}
.rules {
  margin: 18px 0 0;
  padding: 14px 0 0 18px;
  border-top: 1px solid var(--line);
  font-size: 12px;
  line-height: 1.9;
  color: var(--ink-3);
}

@media (max-width: 900px) {
  .layout {
    /* minmax(0, 1fr) 允许内容区收缩，座位图过宽时在自己的容器内横向滚动，而不是把整页撑宽 */
    grid-template-columns: minmax(0, 1fr);
  }
  .side {
    position: static;
  }
}

/* 手机上缩小座位，8 列以内的自习室可以一屏看全 */
@media (max-width: 640px) {
  .seat-grid {
    --seat: 34px;
    gap: 6px;
  }
  .seat {
    border-radius: 8px;
  }
  .seat .no {
    font-size: 11px;
  }
  .marks {
    height: 4px;
  }
  .mark {
    width: 4px;
    height: 4px;
  }
}
</style>
