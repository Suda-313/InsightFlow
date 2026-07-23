import { createRouter, createWebHashHistory } from 'vue-router'

export default createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', name: 'Dashboard', component: () => import('../views/Dashboard.vue') },
    { path: '/issues', name: 'Issues', component: () => import('../views/Issues.vue') },
    { path: '/issues/:key', name: 'IssueDetail', component: () => import('../views/IssueDetail.vue') },
    { path: '/import', name: 'Import', component: () => import('../views/Import.vue') },
    { path: '/reports', name: 'Reports', component: () => import('../views/Reports.vue') },
    { path: '/reports/:id', name: 'ReportDetail', component: () => import('../views/ReportDetail.vue') },
  ]
})
