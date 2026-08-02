import { createRouter, createWebHashHistory } from 'vue-router'
import { getAccessToken } from '../lib/auth'

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/login', name: 'Login', component: () => import('../views/Login.vue'), meta: { public: true } },
    { path: '/', name: 'Home', component: () => import('../views/Home.vue') },
    { path: '/import', name: 'Import', component: () => import('../views/Import.vue') },
    { path: '/data', name: 'Data', component: () => import('../views/Data.vue') },
    { path: '/dashboard', name: 'Dashboard', component: () => import('../views/Dashboard.vue') },
    { path: '/reports', name: 'Reports', component: () => import('../views/Reports.vue') },
    { path: '/knowledge', name: 'Knowledge', component: () => import('../views/Knowledge.vue') },
    { path: '/investigations', name: 'Investigations', component: () => import('../views/Investigations.vue') },
  ]
})

// 只保护业务路由；无 Token 时不尝试请求 Workspace，避免把 401 渲染为“暂无数据”。
router.beforeEach(to => {
  if (to.meta.public) return true
  return getAccessToken() ? true : { name: 'Login' }
})

export default router
