import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import { installAuthenticatedFetch } from './lib/auth'
import './style.css'

const app = createApp(App)
// 所有业务 API 统一继承会话 Token；权限仍由服务端按实时成员关系决定。
installAuthenticatedFetch()
app.use(createPinia())
app.use(router)
app.mount('#app')
