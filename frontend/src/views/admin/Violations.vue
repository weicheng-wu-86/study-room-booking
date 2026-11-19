<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../../api'
import { confirm } from '../../ui'
import { mdhm, hourLabel, VIOLATION_TYPE } from '../../utils'

const query = reactive({ keyword: '', page: 1, size: 10 })
const list = ref([])
const total = ref(0)
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const res = await api.admin.violations({ ...query, keyword: query.keyword || undefined })
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

async function revoke(row) {
  if (!(await confirm(`确定撤销 ${row.realName} 的这条违约记录吗？撤销后不再计入违约次数；若该用户已被禁约，需在“用户管理”中另行解除。`, '撤销违约'))) return
  await api.admin.revokeViolation(row.id)
  ElMessage.success('已撤销')
  load()
}

onMounted(load)
</script>

<template>
  <div>
    <h1 class="page-title">违约记录</h1>
    <p class="page-desc">系统自动记录的爽约与暂离超时。学生申诉成立时，可以撤销对应记录。</p>

    <div class="card">
      <div class="toolbar">
        <el-input v-model="query.keyword" placeholder="账号 / 姓名 / 学号" clearable style="width: 220px" @keyup.enter="search" />
        <el-button type="primary" @click="search">查询</el-button>
      </div>

      <el-table v-loading="loading" :data="list">
        <el-table-column label="违约时间" width="130">
          <template #default="{ row }"><span class="num">{{ mdhm(row.createdAt) }}</span></template>
        </el-table-column>
        <el-table-column label="学生" min-width="130">
          <template #default="{ row }">
            <div>{{ row.realName }}</div>
            <div class="faint small num">{{ row.studentNo || row.username }}</div>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="120">
          <template #default="{ row }">
            <el-tag type="danger" size="small" effect="light" round>{{ VIOLATION_TYPE[row.type] }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="对应预约" min-width="260">
          <template #default="{ row }">
            <template v-if="row.roomName">
              {{ row.roomName }} · <b class="num">{{ row.seatNo }}</b>
              <span class="num muted">　{{ row.reserveDate }} {{ hourLabel(row.startHour) }}-{{ hourLabel(row.endHour) }}</span>
            </template>
            <span v-else class="faint">--</span>
          </template>
        </el-table-column>
        <el-table-column label="说明" prop="remark" min-width="200" show-overflow-tooltip />
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }"><el-button link type="primary" @click="revoke(row)">撤销</el-button></template>
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
