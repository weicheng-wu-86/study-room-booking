<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../../api'
import { STATUS, slotLabel, hm, mdhm } from '../../utils'

const query = reactive({ date: '', roomId: '', status: '', keyword: '', page: 1, size: 10 })
const rooms = ref([])
const list = ref([])
const total = ref(0)
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    // 空字符串的条件不传给后端
    const params = Object.fromEntries(Object.entries(query).filter(([, v]) => v !== '' && v != null))
    const res = await api.admin.reservations(params)
    list.value = res.records
    total.value = res.total
  } finally {
    loading.value = false
  }
}

function search() {
  query.page = 1
  load()
}

function reset() {
  Object.assign(query, { date: '', roomId: '', status: '', keyword: '', page: 1 })
  load()
}

async function cancel(row) {
  let reason
  try {
    const res = await ElMessageBox.prompt(
      `将取消 ${row.realName} 在 ${row.roomName} ${row.seatNo} 的预约（${row.reserveDate} ${slotLabel(row)}），不记违约。`,
      '取消预约',
      { confirmButtonText: '确定取消', cancelButtonText: '返回', inputPlaceholder: '取消原因（选填），会显示给学生', inputValidator: (v) => !v || v.length <= 100 || '原因不能超过 100 个字' }
    )
    reason = res.value
  } catch {
    return
  }
  await api.admin.cancelReservation(row.id, reason || '')
  ElMessage.success('已取消')
  load()
}

onMounted(async () => {
  rooms.value = await api.admin.rooms()
  load()
})
</script>

<template>
  <div>
    <h1 class="page-title">预约管理</h1>
    <p class="page-desc">查询全部预约记录；座位维修或临时闭馆时，可以代学生取消预约。</p>

    <div class="card">
      <div class="toolbar">
        <el-date-picker v-model="query.date" type="date" value-format="YYYY-MM-DD" placeholder="使用日期" style="width: 150px" />
        <el-select v-model="query.roomId" placeholder="自习室" clearable style="width: 200px">
          <el-option v-for="r in rooms" :key="r.id" :value="r.id" :label="r.name" />
        </el-select>
        <el-select v-model="query.status" placeholder="状态" clearable style="width: 120px">
          <el-option v-for="(s, key) in STATUS" :key="key" :value="key" :label="s.label" />
        </el-select>
        <el-input v-model="query.keyword" placeholder="账号 / 姓名 / 学号" clearable style="width: 180px" @keyup.enter="search" />
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </div>

      <el-table v-loading="loading" :data="list">
        <el-table-column label="预约人" min-width="130">
          <template #default="{ row }">
            <div>{{ row.realName }}</div>
            <div class="faint small num">{{ row.studentNo || row.username }}</div>
          </template>
        </el-table-column>
        <el-table-column label="自习室 / 座位" min-width="190">
          <template #default="{ row }">{{ row.roomName }} · <b class="num">{{ row.seatNo }}</b></template>
        </el-table-column>
        <el-table-column label="使用时间" width="190">
          <template #default="{ row }"><span class="num">{{ row.reserveDate }} {{ slotLabel(row) }}</span></template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="STATUS[row.status].type" size="small" effect="light" round>{{ STATUS[row.status].label }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="签到 / 签退" width="130">
          <template #default="{ row }">
            <span class="num">{{ row.checkinTime ? hm(row.checkinTime) : '--' }} / {{ row.checkoutTime ? hm(row.checkoutTime) : '--' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="提交时间" width="120">
          <template #default="{ row }"><span class="num faint">{{ mdhm(row.createdAt) }}</span></template>
        </el-table-column>
        <el-table-column label="备注" min-width="140" prop="remark" show-overflow-tooltip />
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button v-if="['RESERVED', 'IN_USE', 'AWAY'].includes(row.status)" link type="danger" @click="cancel(row)">取消</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pager">
        <el-pagination v-model:current-page="query.page" :page-size="query.size" :total="total"
                       layout="total, prev, pager, next" background @current-change="load" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.small {
  font-size: 12px;
}
</style>
