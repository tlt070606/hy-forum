import { createApp } from 'vue'
import ElementPlus, { ElMessage } from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import App from './App.vue'
import { router } from './router'
import { ApiError } from './api/client'

/**
 * 与前台 main.ts 同一条纪律：createApp 每次新建实例。
 * 管理端状态只有"登录态"一个（stores/auth.ts 的模块级单例），不需要引入状态库。
 */
const app = createApp(App)
app.use(router)
app.use(ElementPlus, { locale: zhCn })
app.mount('#app')

// 管理端大量动作是 fire-and-forget（按钮回调里 .then 不 .catch）：
// ApiError 的拒绝在这里兜底弹出，任何操作失败都不会静默。
window.addEventListener('unhandledrejection', (ev) => {
  if (ev.reason instanceof ApiError) {
    ev.preventDefault()
    ElMessage.error(ev.reason.message)
  }
})
