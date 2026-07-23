import { createRouter, createWebHashHistory } from 'vue-router'

export default createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', name: 'Home', component: () => import('../views/Home.vue') },
    { path: '/data', name: 'Data', component: () => import('../views/Data.vue') },
    { path: '/reports', name: 'Reports', component: () => import('../views/Reports.vue') },
  ]
})