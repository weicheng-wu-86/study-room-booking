<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import QRCode from 'qrcode'
import api from '../../api'
import { confirm } from '../../ui'
import { hourLabel } from '../../utils'

const rooms = ref([])
const loading = ref(false)

// ---------- 自习室表单 ----------
const formVisible = ref(false)
const formRef = ref()
const editingId = ref(null)
const form = reactive({ name: '', location: '', description: '', openHour: 8, closeHour: 22, rowCount: 5, colCount: 8 })
const rules = {
  name: [{ required: true, message: '请输入自习室名称', trigger: 'blur' }],
  location: [{ required: true, message: '请输入位置', trigger: 'blur' }]
}
const saving = ref(false)

async function load() {
  loading.value = true
  try {
    rooms.value = await api.admin.rooms()
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingId.value = null
  Object.assign(form, { name: '', location: '', description: '', openHour: 8, closeHour: 22, rowCount: 5, colCount: 8 })
  formVisible.value = true
}

function openEdit(room) {
  editingId.value = room.id
  Object.assign(form, room)
  formVisible.value = true
}

async function save() {
  await formRef.value.validate()
  if (form.openHour >= form.closeHour) {
    ElMessage.warning('关闭时间必须晚于开放时间')
    return
  }
  saving.value = true
  try {
    const { name, location, description, openHour, closeHour, rowCount, colCount } = form
    const body = { name, location, description, openHour, closeHour, rowCount, colCount }
    if (editingId.value) {
      await api.admin.updateRoom(editingId.value, body)
    } else {
      await api.admin.createRoom(body)
    }
    ElMessage.success('已保存')
    formVisible.value = false
    load()
  } finally {
    saving.value = false
  }
}

async function toggle(room, enabled) {
  await api.admin.setRoomEnabled(room.id, enabled).catch(() => {})
  load()
}

async function remove(room) {
  if (!(await confirm(`确定删除“${room.name}”及其全部座位吗？已有预约记录的自习室无法删除，只能停用。`, '删除自习室'))) return
  await api.admin.deleteRoom(room.id)
  ElMessage.success('已删除')
  load()
}

// ---------- 座位管理 ----------
const seatVisible = ref(false)
const seatRoom = ref(null)
const seats = ref([])
const current = ref(null)
const tab = ref('layout')
const qrList = ref([])

const checkinUrl = (code) => `${location.origin}${location.pathname}#/checkin?code=${code}`

async function openSeats(room) {
  seatRoom.value = room
  current.value = null
  tab.value = 'layout'
  qrList.value = []
  seats.value = await api.admin.seats(room.id)
  seatVisible.value = true
}

async function updateSeat(patch) {
  const updated = await api.admin.updateSeat(current.value.id, patch)
  Object.assign(current.value, updated)
  load()
}

async function resetCode() {
  if (!(await confirm('重置后旧二维码立即失效，需要重新打印并张贴。确定重置吗？', '重置签到码'))) return
  const updated = await api.admin.resetSeatCode(current.value.id)
  Object.assign(current.value, updated)
  qrList.value = []
  ElMessage.success('签到码已重置')
}

/** 切到二维码页时才生成图片，避免打开弹窗就做几十次绘制 */
async function onTab(name) {
  if (name === 'qr' && !qrList.value.length) {
    qrList.value = await Promise.all(
      seats.value
        .filter((s) => s.enabled)
        .map(async (s) => ({
          seatNo: s.seatNo,
          code: s.checkinCode,
          img: await QRCode.toDataURL(checkinUrl(s.checkinCode), { margin: 1, width: 180 })
        }))
    )
  }
}

const enabledCount = computed(() => seats.value.filter((s) => s.enabled).length)
const print = () => window.print()

onMounted(load)
</script>

<template>
  <div>
    <div class="head">
      <div>
        <h1 class="page-title">自习室管理</h1>
        <p class="page-desc">维护自习室、开放时间和座位；座位二维码用于学生到场签到。</p>
      </div>
      <el-button type="primary" :icon="Plus" @click="openCreate">新建自习室</el-button>
    </div>

    <div class="card">
      <el-table v-loading="loading" :data="rooms">
        <el-table-column label="自习室" min-width="200">
          <template #default="{ row }">
            <div class="name">{{ row.name }}</div>
            <div class="faint small">{{ row.location }}</div>
          </template>
        </el-table-column>
        <el-table-column label="开放时间" width="140">
          <template #default="{ row }"><span class="num">{{ hourLabel(row.openHour) }} - {{ hourLabel(row.closeHour) }}</span></template>
        </el-table-column>
        <el-table-column label="座位" width="160">
          <template #default="{ row }">
            <span class="num">{{ row.rowCount }} 行 × {{ row.colCount }} 列</span>
            <div class="faint small">可用 {{ row.seatCount }} 个</div>
          </template>
        </el-table-column>
        <el-table-column label="开放" width="90">
          <template #default="{ row }">
            <el-switch :model-value="row.enabled" @change="(v) => toggle(row, v)" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openSeats(row)">座位与二维码</el-button>
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="formVisible" :title="editingId ? '编辑自习室' : '新建自习室'" width="520px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="84px">
        <el-form-item label="名称" prop="name"><el-input v-model="form.name" maxlength="50" /></el-form-item>
        <el-form-item label="位置" prop="location"><el-input v-model="form.location" maxlength="100" /></el-form-item>
        <el-form-item label="简介"><el-input v-model="form.description" type="textarea" :rows="2" maxlength="255" /></el-form-item>
        <el-form-item label="开放时间">
          <el-select v-model="form.openHour" style="width: 110px">
            <el-option v-for="h in 24" :key="h - 1" :value="h - 1" :label="hourLabel(h - 1)" />
          </el-select>
          <span class="sep">至</span>
          <el-select v-model="form.closeHour" style="width: 110px">
            <el-option v-for="h in 24" :key="h" :value="h" :label="hourLabel(h)" />
          </el-select>
        </el-form-item>
        <el-form-item label="座位布局">
          <el-input-number v-model="form.rowCount" :min="1" :max="20" :disabled="!!editingId" />
          <span class="sep">行 ×</span>
          <el-input-number v-model="form.colCount" :min="1" :max="20" :disabled="!!editingId" />
          <span class="sep">列</span>
        </el-form-item>
        <p v-if="editingId" class="faint small tip">座位布局在创建后不可修改；个别位置不需要座位时，可在“座位与二维码”中停用。</p>
        <p v-else class="faint small tip">保存后将自动生成 {{ form.rowCount * form.colCount }} 个座位及其签到码。</p>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="seatVisible" :title="seatRoom?.name" width="860px" top="6vh">
      <el-tabs v-model="tab" @tab-change="onTab">
        <el-tab-pane label="座位布局" name="layout">
          <div class="seat-layout">
            <div class="grid-wrap">
              <div class="seat-grid" :style="{ gridTemplateColumns: `repeat(${seatRoom?.colCount}, 44px)` }">
                <button v-for="s in seats" :key="s.id" type="button" class="seat"
                        :class="{ off: !s.enabled, on: current?.id === s.id }"
                        :style="{ gridRow: s.rowNo, gridColumn: s.colNo }" @click="current = s">
                  {{ s.seatNo }}
                </button>
              </div>
              <p class="faint small">共 {{ seats.length }} 个位置，可预约 {{ enabledCount }} 个。点击座位进行设置。</p>
            </div>
            <div class="seat-form">
              <template v-if="current">
                <div class="seat-title num">{{ current.seatNo }}</div>
                <div class="row"><span>可预约</span><el-switch :model-value="current.enabled" @change="(v) => updateSeat({ enabled: v })" /></div>
                <div class="row"><span>有电源插座</span><el-switch :model-value="current.hasSocket" @change="(v) => updateSeat({ hasSocket: v })" /></div>
                <div class="row"><span>靠窗</span><el-switch :model-value="current.nearWindow" @change="(v) => updateSeat({ nearWindow: v })" /></div>
                <div class="row"><span>签到码</span><code class="num">{{ current.checkinCode }}</code></div>
                <el-button class="reset" @click="resetCode">重置签到码</el-button>
              </template>
              <p v-else class="faint">未选择座位</p>
            </div>
          </div>
        </el-tab-pane>
        <el-tab-pane label="签到二维码" name="qr">
          <div class="qr-head">
            <span class="muted small">学生用手机扫描座位上的二维码即可签到。打印后裁开，贴在对应座位上。</span>
            <el-button type="primary" @click="print">打印</el-button>
          </div>
          <div class="qr-sheet">
            <div v-for="q in qrList" :key="q.seatNo" class="qr">
              <img :src="q.img" :alt="q.seatNo" />
              <div class="qr-no num">{{ q.seatNo }}</div>
              <div class="qr-code num">{{ q.code }}</div>
            </div>
          </div>
        </el-tab-pane>
      </el-tabs>
    </el-dialog>
  </div>
</template>

<style scoped>
.head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
}
.name {
  font-weight: 600;
}
.small {
  font-size: 12px;
}
.sep {
  margin: 0 10px;
  color: var(--ink-2);
}
.tip {
  margin: 0 0 0 84px;
}
.seat-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 240px;
  gap: 24px;
}
.grid-wrap {
  overflow-x: auto;
}
.seat-grid {
  display: grid;
  gap: 8px;
  width: max-content;
  margin-bottom: 12px;
}
.seat {
  width: 44px;
  height: 40px;
  border-radius: 8px;
  border: 1.5px solid var(--el-color-primary-light-5);
  background: #fff;
  color: var(--brand);
  font-size: 12px;
  font-weight: 600;
  font-family: inherit;
  cursor: pointer;
}
.seat.off {
  border-style: dashed;
  border-color: #d9d5ca;
  color: #c2beb3;
}
.seat.on {
  background: var(--brand);
  border-color: var(--brand);
  color: #fff;
}
.seat-form {
  border-left: 1px solid var(--line);
  padding-left: 24px;
}
.seat-title {
  font-size: 24px;
  font-weight: 700;
  color: var(--brand);
  margin-bottom: 12px;
}
.row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 0;
  font-size: 14px;
}
.reset {
  margin-top: 12px;
  width: 100%;
}
.qr-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  margin-bottom: 16px;
}
.qr-sheet {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
  gap: 14px;
  max-height: 56vh;
  overflow-y: auto;
}
.qr {
  border: 1px dashed #cfcabd;
  border-radius: 8px;
  padding: 10px;
  text-align: center;
  break-inside: avoid;
}
.qr img {
  width: 100%;
  display: block;
}
.qr-no {
  font-size: 18px;
  font-weight: 700;
}
.qr-code {
  font-size: 11px;
  color: var(--ink-2);
  letter-spacing: 0.08em;
}
</style>

<!-- 打印时只输出二维码区域 -->
<style>
@media print {
  body * {
    visibility: hidden;
  }
  .qr-sheet,
  .qr-sheet * {
    visibility: visible;
  }
  .qr-sheet {
    position: fixed;
    inset: 0;
    max-height: none !important;
    overflow: visible !important;
    grid-template-columns: repeat(4, 1fr) !important;
  }
}
</style>
