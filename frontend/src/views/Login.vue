<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import api from '../api'
import { useUserStore } from '../stores'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const mode = ref('login')
const loading = ref(false)
const loginRef = ref()
const registerRef = ref()

const loginForm = reactive({ username: '', password: '' })
const registerForm = reactive({ username: '', password: '', confirm: '', realName: '', studentNo: '', phone: '' })

const loginRules = {
  username: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}
// 与后端校验规则保持一致，尽量在提交前就把问题提示出来
const registerRules = {
  username: [
    { required: true, message: '请输入账号', trigger: 'blur' },
    { pattern: /^[A-Za-z0-9_]{4,20}$/, message: '4-20 位字母、数字或下划线', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 32, message: '密码长度需为 6-32 位', trigger: 'blur' }
  ],
  confirm: [
    {
      validator: (_, value, cb) => (value === registerForm.password ? cb() : cb(new Error('两次输入的密码不一致'))),
      trigger: 'blur'
    }
  ],
  realName: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
  phone: [{ pattern: /^$|^1\d{10}$/, message: '手机号格式不正确', trigger: 'blur' }]
}

async function submitLogin() {
  await loginRef.value.validate()
  loading.value = true
  try {
    await userStore.login(loginForm)
    // 登录前若是从某个页面（如扫码签到链接）跳过来的，登录后回到那里
    const redirect = route.query.redirect
    router.replace(typeof redirect === 'string' && !userStore.isAdmin ? redirect : userStore.homePath)
  } finally {
    loading.value = false
  }
}

async function submitRegister() {
  await registerRef.value.validate()
  loading.value = true
  try {
    const { confirm, ...data } = registerForm
    await api.register(data)
    ElMessage.success('注册成功，请登录')
    loginForm.username = registerForm.username
    loginForm.password = ''
    mode.value = 'login'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <section class="intro">
      <div class="intro-inner">
        <div class="logo"><span class="brand-mark">习</span>自习室预约系统</div>
        <h1>先约座，再出门</h1>
        <p>查看每间自习室的实时座位，选好时段一键预约；到场扫码签到，离开顺手签退，把座位留给下一位同学。</p>
        <ul>
          <li><b>看得见</b>　座位图实时显示每个座位的占用时段</li>
          <li><b>约得上</b>　同一座位同一时段只会被一人预约成功</li>
          <li><b>不浪费</b>　超时未签到自动释放，杜绝占座不来</li>
        </ul>
      </div>
    </section>

    <section class="panel">
      <div class="form-card">
        <el-tabs v-model="mode" stretch>
          <el-tab-pane label="登录" name="login">
            <el-form ref="loginRef" :model="loginForm" :rules="loginRules" label-position="top" size="large"
                     @keyup.enter="submitLogin">
              <el-form-item label="账号" prop="username">
                <el-input v-model="loginForm.username" placeholder="请输入账号" autocomplete="username" />
              </el-form-item>
              <el-form-item label="密码" prop="password">
                <el-input v-model="loginForm.password" type="password" show-password placeholder="请输入密码"
                          autocomplete="current-password" />
              </el-form-item>
              <el-button type="primary" class="submit" :loading="loading" @click="submitLogin">登录</el-button>
            </el-form>
            <p class="hint">演示账号：学生 stu001 / 123456，管理员 admin / admin123</p>
          </el-tab-pane>

          <el-tab-pane label="注册" name="register">
            <el-form ref="registerRef" :model="registerForm" :rules="registerRules" label-position="top">
              <el-form-item label="账号" prop="username">
                <el-input v-model="registerForm.username" placeholder="4-20 位字母、数字或下划线" />
              </el-form-item>
              <div class="two">
                <el-form-item label="密码" prop="password">
                  <el-input v-model="registerForm.password" type="password" show-password placeholder="6-32 位" />
                </el-form-item>
                <el-form-item label="确认密码" prop="confirm">
                  <el-input v-model="registerForm.confirm" type="password" show-password placeholder="再次输入密码" />
                </el-form-item>
              </div>
              <div class="two">
                <el-form-item label="姓名" prop="realName">
                  <el-input v-model="registerForm.realName" placeholder="真实姓名" />
                </el-form-item>
                <el-form-item label="学号" prop="studentNo">
                  <el-input v-model="registerForm.studentNo" placeholder="选填" />
                </el-form-item>
              </div>
              <el-form-item label="手机号" prop="phone">
                <el-input v-model="registerForm.phone" placeholder="选填" />
              </el-form-item>
              <el-button type="primary" class="submit" size="large" :loading="loading" @click="submitRegister">
                注册
              </el-button>
            </el-form>
          </el-tab-pane>
        </el-tabs>
      </div>
    </section>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100%;
  display: grid;
  grid-template-columns: 1.1fr 1fr;
}
.intro {
  background: linear-gradient(160deg, #24523f 0%, #2f6b58 55%, #3d7d68 100%);
  color: #fff;
  display: flex;
  align-items: center;
  padding: 48px;
}
.intro-inner {
  max-width: 460px;
  margin: 0 auto;
}
.logo {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 17px;
  font-weight: 600;
  margin-bottom: 56px;
}
.brand-mark {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  background: #fff;
  color: var(--brand);
  display: grid;
  place-items: center;
}
.intro h1 {
  font-size: 40px;
  line-height: 1.25;
  margin-bottom: 18px;
}
.intro p {
  font-size: 15px;
  line-height: 1.9;
  color: rgba(255, 255, 255, 0.82);
  margin: 0 0 32px;
}
.intro ul {
  list-style: none;
  padding: 0;
  margin: 0;
  display: grid;
  gap: 14px;
  font-size: 14px;
  color: rgba(255, 255, 255, 0.86);
}
.intro li b {
  color: #fff;
}
.panel {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 32px 20px;
}
.form-card {
  width: 100%;
  max-width: 400px;
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 16px;
  box-shadow: var(--shadow);
  padding: 20px 28px 26px;
}
.submit {
  width: 100%;
  margin-top: 6px;
}
.two {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}
.hint {
  margin: 16px 0 0;
  font-size: 12px;
  color: var(--ink-3);
  text-align: center;
}

@media (max-width: 820px) {
  .login-page {
    grid-template-columns: 1fr;
  }
  .intro {
    padding: 28px 24px;
  }
  .logo {
    margin-bottom: 20px;
  }
  .intro h1 {
    font-size: 26px;
  }
  .intro ul {
    display: none;
  }
  .intro p {
    margin-bottom: 0;
  }
}
</style>
