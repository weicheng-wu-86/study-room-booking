import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import App from './App.vue'
import router from './router'
import './styles.css'

const app = createApp(App)

// 事件处理函数里未捕获的异常统一到这里：
// 接口错误已由 api.js 弹出提示（带 handled 标记）；表单校验未通过时 validate() 会以非 Error 对象 reject。
// 这两类属于正常流程，不再输出到控制台；其余的才是真正的程序错误。
app.config.errorHandler = (err) => {
  if (err?.handled || !(err instanceof Error)) return
  console.error(err)
}

app.use(createPinia()).use(router).use(ElementPlus, { locale: zhCn }).mount('#app')
