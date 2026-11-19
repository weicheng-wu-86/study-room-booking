<script setup>
// 一条预约的卡片：展示状态、提示与倒计时，并按当前状态给出可用操作。
// 首页的“当前预约”和“我的预约”列表都使用这个组件。
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Location, Clock } from '@element-plus/icons-vue'
import api from '../api'
import { confirm } from '../ui'
import { useClockStore } from '../stores'
import { STATUS, parseTime, hm, countdown, dayLabel, slotLabel, mdhm } from '../utils'

const props = defineProps({ reservation: { type: Object, required: true } })
const emit = defineEmits(['changed'])

const clock = useClockStore()
const r = computed(() => props.reservation)
const status = computed(() => STATUS[r.value.status])
const day = computed(() => dayLabel(r.value.reserveDate))
const busy = ref(false)

const checkinVisible = ref(false)
const code = ref('')

/** 是否已到可签到时间 */
const canCheckin = computed(() => r.value.status === 'RESERVED' && clock.now >= parseTime(r.value.checkinOpenTime))

/** 状态下方的提示文字；urgent 为 true 时用醒目颜色 */
const hint = computed(() => {
  const x = r.value
  if (x.status === 'RESERVED') {
    if (!canCheckin.value) {
      return { text: `${x.reserveDate === clock.today ? '今天' : day.value.md} ${hm(x.checkinOpenTime)} 起可签到` }
    }
    const left = parseTime(x.checkinDeadline) - clock.now
    return { text: `请在 ${hm(x.checkinDeadline)} 前到场签到，剩余 ${countdown(left)}`, urgent: true }
  }
  if (x.status === 'IN_USE') {
    return { text: `${hm(x.checkinTime)} 已签到，可使用至 ${String(x.endHour).padStart(2, '0')}:00` }
  }
  if (x.status === 'AWAY') {
    const left = parseTime(x.awayDeadline) - clock.now
    return { text: `暂离中，请在 ${hm(x.awayDeadline)} 前返回，剩余 ${countdown(left)}`, urgent: true }
  }
  if (x.status === 'COMPLETED') return { text: `${mdhm(x.checkinTime)} 签到，${hm(x.checkoutTime)} 签退` }
  if (x.status === 'CANCELLED') return { text: x.remark || '已取消' }
  if (x.status === 'NO_SHOW') return { text: '超时未签到，座位已释放，记违约一次' }
  return { text: '暂离超时未返回，座位已释放，记违约一次' }
})

/** 统一的操作包装：防止重复点击，成功后提示并通知父组件刷新 */
async function run(action, message) {
  if (busy.value) return
  busy.value = true
  try {
    await action()
    ElMessage.success(message)
    emit('changed')
  } catch {
    // 错误提示已由接口层统一弹出；状态可能已变化，同样刷新一次
    emit('changed')
  } finally {
    busy.value = false
  }
}

async function cancel() {
  const ok = await confirm('取消后座位会立即释放给其他同学，确定取消这次预约吗？', '取消预约', {
    confirmButtonText: '确定取消',
    cancelButtonText: '再想想'
  })
  if (ok) run(() => api.cancelReservation(r.value.id), '预约已取消')
}

async function checkout() {
  const ok = await confirm('签退后剩余时段会释放给其他同学，确定现在签退吗？', '签退', {
    confirmButtonText: '确定签退',
    cancelButtonText: '继续学习',
    type: 'info'
  })
  if (ok) run(() => api.checkout(r.value.id), '已签退，辛苦了')
}

function submitCheckin() {
  if (!code.value.trim()) {
    ElMessage.warning('请输入座位上的签到码')
    return
  }
  run(async () => {
    await api.checkin(code.value)
    checkinVisible.value = false
    code.value = ''
  }, '签到成功')
}
</script>

<template>
  <div class="rcard" :class="{ active: ['RESERVED', 'IN_USE', 'AWAY'].includes(r.status) }">
    <div class="rcard-main">
      <div class="seat">
        <span class="seat-no num">{{ r.seatNo }}</span>
        <span class="seat-cap">座位</span>
      </div>
      <div class="info">
        <div class="line1">
          <span class="room">{{ r.roomName }}</span>
          <el-tag :type="status.type" size="small" effect="light" round>{{ status.label }}</el-tag>
        </div>
        <div class="line2">
          <span><el-icon><Clock /></el-icon>{{ day.md }} {{ day.week }} · <b class="num">{{ slotLabel(r) }}</b></span>
          <span><el-icon><Location /></el-icon>{{ r.roomLocation }}</span>
        </div>
        <div class="hint" :class="{ urgent: hint.urgent }">{{ hint.text }}</div>
      </div>
    </div>

    <div v-if="['RESERVED', 'IN_USE', 'AWAY'].includes(r.status)" class="actions">
      <template v-if="r.status === 'RESERVED'">
        <el-button type="primary" :disabled="!canCheckin" @click="checkinVisible = true">签到</el-button>
        <el-button :loading="busy" @click="cancel">取消预约</el-button>
      </template>
      <template v-else-if="r.status === 'IN_USE'">
        <el-button :loading="busy" @click="run(() => api.away(r.id), '已暂离，请按时返回')">暂离</el-button>
        <el-button type="primary" plain :loading="busy" @click="checkout">签退</el-button>
      </template>
      <template v-else>
        <el-button type="primary" :loading="busy" @click="run(() => api.back(r.id), '欢迎回来')">我回来了</el-button>
        <el-button :loading="busy" @click="checkout">签退</el-button>
      </template>
    </div>

    <el-dialog v-model="checkinVisible" title="到场签到" width="360px" append-to-body>
      <p class="muted dialog-tip">
        用手机扫描座位 <b>{{ r.seatNo }}</b> 上的二维码可直接签到；也可以在下方输入二维码下面印的签到码。
      </p>
      <el-input v-model="code" placeholder="请输入 10 位签到码" maxlength="10" size="large" clearable
                @keyup.enter="submitCheckin" />
      <template #footer>
        <el-button @click="checkinVisible = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="submitCheckin">确认签到</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.rcard {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 16px 18px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: #fff;
}
.rcard.active {
  border-color: var(--el-color-primary-light-5);
  background: linear-gradient(180deg, #fbfdfc, #fff);
}
.rcard-main {
  display: flex;
  align-items: center;
  gap: 16px;
  min-width: 0;
}
.seat {
  width: 64px;
  height: 64px;
  flex-shrink: 0;
  border-radius: 12px;
  background: var(--brand-soft);
  color: var(--brand);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
}
.rcard:not(.active) .seat {
  background: #f3f1ec;
  color: var(--ink-2);
}
.seat-no {
  font-size: 19px;
  font-weight: 700;
  line-height: 1.2;
}
.seat-cap {
  font-size: 11px;
  opacity: 0.75;
}
.info {
  min-width: 0;
}
.line1 {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 6px;
}
.room {
  font-size: 16px;
  font-weight: 600;
}
.line2 {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 18px;
  font-size: 13px;
  color: var(--ink-2);
}
.line2 span {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.line2 b {
  color: var(--ink);
}
.hint {
  margin-top: 6px;
  font-size: 13px;
  color: var(--ink-3);
}
.hint.urgent {
  color: var(--accent);
  font-weight: 600;
}
.actions {
  display: flex;
  flex-shrink: 0;
  gap: 4px;
}
.dialog-tip {
  margin: 0 0 14px;
  font-size: 13px;
  line-height: 1.7;
}

@media (max-width: 640px) {
  .rcard {
    flex-direction: column;
    align-items: stretch;
  }
  .actions .el-button {
    flex: 1;
  }
}
</style>
