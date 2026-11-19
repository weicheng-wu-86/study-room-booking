<script setup>
// 扫码签到页。座位二维码的内容是本页面的地址加签到码，例如：
//   https://<站点>/#/checkin?code=AB12CD34EF
// 学生用手机扫码后打开本页（未登录会先跳到登录页，登录后自动回来），页面自动完成签到。
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { CircleCheckFilled, WarningFilled } from '@element-plus/icons-vue'
import api from '../../api'
import { hm, hourLabel } from '../../utils'

const route = useRoute()
const router = useRouter()

const state = ref('loading') // loading | success | fail
const reservation = ref(null)
const message = ref('')

onMounted(async () => {
  const code = route.query.code
  if (typeof code !== 'string' || !code) {
    state.value = 'fail'
    message.value = '二维码无效：缺少签到码'
    return
  }
  try {
    reservation.value = await api.checkin(code, { silent: true })
    state.value = 'success'
  } catch (e) {
    state.value = 'fail'
    message.value = e.message
  }
})
</script>

<template>
  <div class="wrap">
    <div class="card box" v-loading="state === 'loading'">
      <template v-if="state === 'success'">
        <el-icon class="icon ok"><CircleCheckFilled /></el-icon>
        <h2>签到成功</h2>
        <p class="muted">
          {{ reservation.roomName }} · 座位 <b>{{ reservation.seatNo }}</b><br />
          {{ hm(reservation.checkinTime) }} 签到，可使用至 {{ hourLabel(reservation.endHour) }}
        </p>
        <p class="faint tip">临时离开请点“暂离”，提前结束请点“签退”。</p>
      </template>
      <template v-else-if="state === 'fail'">
        <el-icon class="icon bad"><WarningFilled /></el-icon>
        <h2>签到未成功</h2>
        <p class="muted">{{ message }}</p>
      </template>
      <template v-else>
        <h2>正在签到……</h2>
      </template>
      <el-button v-if="state !== 'loading'" type="primary" size="large" @click="router.replace('/reservations')">
        查看我的预约
      </el-button>
    </div>
  </div>
</template>

<style scoped>
.wrap {
  display: flex;
  justify-content: center;
  padding-top: 40px;
}
.box {
  width: 100%;
  max-width: 420px;
  min-height: 260px;
  text-align: center;
  padding: 36px 28px;
}
.icon {
  font-size: 56px;
  margin-bottom: 12px;
}
.icon.ok {
  color: var(--brand);
}
.icon.bad {
  color: var(--accent);
}
h2 {
  font-size: 20px;
  margin-bottom: 12px;
}
p {
  margin: 0 0 14px;
  line-height: 1.8;
}
.tip {
  font-size: 13px;
  margin-bottom: 22px;
}
</style>
