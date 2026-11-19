<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../../api'
import { useUserStore, useClockStore } from '../../stores'
import { mdhm, hourLabel, VIOLATION_TYPE } from '../../utils'

const userStore = useUserStore()
const clock = useClockStore()

const profile = reactive({ realName: '', phone: '' })
const password = reactive({ oldPassword: '', newPassword: '', confirm: '' })
const profileRef = ref()
const passwordRef = ref()
const saving = ref(false)

const violations = ref([])
const total = ref(0)
const page = ref(1)

const profileRules = {
  realName: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
  phone: [{ pattern: /^$|^1\d{10}$/, message: '手机号格式不正确', trigger: 'blur' }]
}
const passwordRules = {
  oldPassword: [{ required: true, message: '请输入原密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, max: 32, message: '密码长度需为 6-32 位', trigger: 'blur' }
  ],
  confirm: [
    {
      validator: (_, value, cb) => (value === password.newPassword ? cb() : cb(new Error('两次输入的密码不一致'))),
      trigger: 'blur'
    }
  ]
}

async function saveProfile() {
  await profileRef.value.validate()
  saving.value = true
  try {
    userStore.setUser(await api.updateProfile({ realName: profile.realName, phone: profile.phone || '' }))
    ElMessage.success('资料已保存')
  } finally {
    saving.value = false
  }
}

async function savePassword() {
  await passwordRef.value.validate()
  saving.value = true
  try {
    await api.changePassword({ oldPassword: password.oldPassword, newPassword: password.newPassword })
    ElMessage.success('密码已修改')
    passwordRef.value.resetFields()
  } finally {
    saving.value = false
  }
}

async function loadViolations() {
  const res = await api.myViolations({ page: page.value, size: 5 })
  violations.value = res.records
  total.value = res.total
}

onMounted(async () => {
  await clock.ensure()
  await userStore.refresh()
  profile.realName = userStore.user.realName
  profile.phone = userStore.user.phone || ''
  loadViolations()
})
</script>

<template>
  <div>
    <h1 class="page-title">个人中心</h1>
    <p class="page-desc">管理个人资料，查看信用状态与违约记录。</p>

    <div class="grid">
      <div class="col">
        <section class="card">
          <div class="card-title">基本资料</div>
          <el-form ref="profileRef" :model="profile" :rules="profileRules" label-width="64px">
            <el-form-item label="账号"><span class="num">{{ userStore.user?.username }}</span></el-form-item>
            <el-form-item label="学号"><span class="num">{{ userStore.user?.studentNo || '未填写' }}</span></el-form-item>
            <el-form-item label="姓名" prop="realName"><el-input v-model="profile.realName" maxlength="20" /></el-form-item>
            <el-form-item label="手机号" prop="phone"><el-input v-model="profile.phone" placeholder="选填" /></el-form-item>
            <el-form-item><el-button type="primary" :loading="saving" @click="saveProfile">保存</el-button></el-form-item>
          </el-form>
        </section>

        <section class="card">
          <div class="card-title">修改密码</div>
          <el-form ref="passwordRef" :model="password" :rules="passwordRules" label-width="78px">
            <el-form-item label="原密码" prop="oldPassword">
              <el-input v-model="password.oldPassword" type="password" show-password />
            </el-form-item>
            <el-form-item label="新密码" prop="newPassword">
              <el-input v-model="password.newPassword" type="password" show-password placeholder="6-32 位" />
            </el-form-item>
            <el-form-item label="确认密码" prop="confirm">
              <el-input v-model="password.confirm" type="password" show-password />
            </el-form-item>
            <el-form-item><el-button :loading="saving" @click="savePassword">修改密码</el-button></el-form-item>
          </el-form>
        </section>
      </div>

      <section class="card">
        <div class="card-title">信用状态</div>
        <div class="credit" :class="{ bad: userStore.user?.banned }">
          <div class="credit-state">{{ userStore.user?.banned ? '暂停预约' : '正常' }}</div>
          <div class="credit-desc">
            <template v-if="userStore.user?.banned">{{ mdhm(userStore.user.banUntil) }} 后恢复预约资格</template>
            <template v-else>
              最近 {{ clock.rules?.windowDays }} 天违约
              <b class="num">{{ userStore.user?.recentViolations ?? 0 }}</b> / {{ clock.rules?.maxViolations }} 次
            </template>
          </div>
        </div>
        <p class="faint rule">
          预约后超过 {{ clock.rules?.checkinLateMinutes }} 分钟未签到，或暂离超过 {{ clock.rules?.awayMaxMinutes }} 分钟未返回，记违约一次；
          {{ clock.rules?.windowDays }} 天内累计 {{ clock.rules?.maxViolations }} 次，暂停预约 {{ clock.rules?.banDays }} 天。
        </p>

        <div class="card-title sub">违约记录</div>
        <el-table :data="violations" size="small" empty-text="没有违约记录，继续保持">
          <el-table-column label="时间" width="110"><template #default="{ row }">{{ mdhm(row.createdAt) }}</template></el-table-column>
          <el-table-column label="类型" width="100"><template #default="{ row }">{{ VIOLATION_TYPE[row.type] }}</template></el-table-column>
          <el-table-column label="对应预约">
            <template #default="{ row }">
              {{ row.roomName }} {{ row.seatNo }}，{{ hourLabel(row.startHour) }}-{{ hourLabel(row.endHour) }}
            </template>
          </el-table-column>
        </el-table>
        <div class="pager">
          <el-pagination v-model:current-page="page" :page-size="5" :total="total" layout="prev, pager, next" small
                         hide-on-single-page @current-change="loadViolations" />
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.grid {
  display: grid;
  grid-template-columns: 1fr 1.2fr;
  gap: 20px;
  align-items: start;
}
.col {
  display: grid;
  gap: 20px;
}
.credit {
  padding: 16px 18px;
  border-radius: 10px;
  background: var(--brand-soft);
  color: var(--brand);
}
.credit.bad {
  background: #fbecea;
  color: var(--danger);
}
.credit-state {
  font-size: 20px;
  font-weight: 700;
}
.credit-desc {
  margin-top: 4px;
  font-size: 14px;
}
.rule {
  font-size: 12px;
  line-height: 1.8;
  margin: 12px 0 0;
}
.sub {
  margin-top: 22px;
}

@media (max-width: 820px) {
  .grid {
    grid-template-columns: 1fr;
  }
}
</style>
