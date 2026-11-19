<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../../api'
import { confirm } from '../../ui'
import { useUserStore, useClockStore } from '../../stores'
import { mdhm } from '../../utils'

const userStore = useUserStore()
const clock = useClockStore()

const query = reactive({ keyword: '', page: 1, size: 10 })
const list = ref([])
const total = ref(0)
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const res = await api.admin.users({ ...query, keyword: query.keyword || undefined })
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

async function setEnabled(row, enabled) {
  const action = enabled ? '启用' : '停用'
  if (!(await confirm(`确定${action}账号 ${row.username}（${row.realName}）吗？${enabled ? '' : '停用后该用户将无法登录。'}`, `${action}账号`))) return
  await api.admin.setUserEnabled(row.id, enabled)
  ElMessage.success(`已${action}`)
  load()
}

async function unban(row) {
  if (!(await confirm(`确定解除 ${row.realName} 的禁约吗？解除后可立即预约。`, '解除禁约'))) return
  await api.admin.unban(row.id)
  ElMessage.success('已解除禁约')
  load()
}

async function resetPassword(row) {
  if (!(await confirm(`确定重置 ${row.realName} 的密码吗？系统会生成一个新的随机密码。`, '重置密码'))) return
  const { password } = await api.admin.resetPassword(row.id)
  ElMessageBox.alert(`新密码：${password}\n请转告该用户，并提醒其登录后尽快修改。`, '密码已重置', { confirmButtonText: '知道了' })
}

onMounted(async () => {
  await clock.ensure()
  load()
})
</script>

<template>
  <div>
    <h1 class="page-title">用户管理</h1>
    <p class="page-desc">查看用户的信用状态，处理账号停用、解除禁约和密码重置。</p>

    <div class="card">
      <div class="toolbar">
        <el-input v-model="query.keyword" placeholder="账号 / 姓名 / 学号" clearable style="width: 220px" @keyup.enter="search" />
        <el-button type="primary" @click="search">查询</el-button>
      </div>

      <el-table v-loading="loading" :data="list">
        <el-table-column label="账号" prop="username" min-width="110" />
        <el-table-column label="姓名" prop="realName" min-width="100" />
        <el-table-column label="学号" min-width="110">
          <template #default="{ row }"><span class="num">{{ row.studentNo || '--' }}</span></template>
        </el-table-column>
        <el-table-column label="角色" width="90">
          <template #default="{ row }">{{ row.role === 'ADMIN' ? '管理员' : '学生' }}</template>
        </el-table-column>
        <el-table-column :label="`近 ${clock.rules?.windowDays ?? 30} 天违约`" width="120">
          <template #default="{ row }">
            <span class="num" :class="{ danger: row.recentViolations >= (clock.rules?.maxViolations ?? 3) }">{{ row.recentViolations }} 次</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" min-width="170">
          <template #default="{ row }">
            <el-tag v-if="!row.enabled" type="info" size="small" round>已停用</el-tag>
            <el-tag v-else-if="row.banned" type="danger" size="small" effect="light" round>禁约至 {{ mdhm(row.banUntil) }}</el-tag>
            <el-tag v-else type="success" size="small" effect="light" round>正常</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.banned" link type="primary" @click="unban(row)">解除禁约</el-button>
            <el-button link type="primary" @click="resetPassword(row)">重置密码</el-button>
            <template v-if="row.id !== userStore.user?.id">
              <el-button v-if="row.enabled" link type="danger" @click="setEnabled(row, false)">停用</el-button>
              <el-button v-else link type="primary" @click="setEnabled(row, true)">启用</el-button>
            </template>
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
.danger {
  color: var(--danger);
  font-weight: 600;
}
</style>
