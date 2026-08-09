import { createRouter, createWebHistory } from 'vue-router'
import { authState } from '../stores/auth'

const routes = [
  {
    path: '/',
    component: () => import('../views/Home.vue'),
    meta: { public: true, title: '心旅 HeartPilot｜让关系改善真正发生' }
  },
  {
    path: '/login',
    component: () => import('../views/Auth.vue'),
    meta: { public: true, guestOnly: true, title: '登录｜心旅' }
  },
  {
    path: '/register',
    component: () => import('../views/Auth.vue'),
    meta: { public: true, guestOnly: true, title: '注册｜心旅' }
  },
  {
    path: '/consult',
    component: () => import('../views/Consult.vue'),
    meta: { title: 'AI 情感咨询' }
  },
  {
    path: '/reports',
    component: () => import('../views/Reports.vue'),
    meta: { title: '关系报告' }
  },
  { path: '/plans', component: () => import('../views/Plans.vue'), meta: { title: '行动规划' } },
  {
    path: '/plans/:id',
    component: () => import('../views/TaskDetail.vue'),
    meta: { title: '任务详情' }
  },
  {
    path: '/profile',
    component: () => import('../views/Profile.vue'),
    meta: { title: '关系档案' }
  },
  { path: '/growth', component: () => import('../views/Growth.vue'), meta: { title: '成长计划' } },
  {
    path: '/costs',
    component: () => import('../views/CostDashboard.vue'),
    meta: { title: '消费成本' }
  },
  {
    path: '/knowledge',
    component: () => import('../views/Knowledge.vue'),
    meta: { title: '知识库管理', admin: true }
  },
  { path: '/me', component: () => import('../views/Personal.vue'), meta: { title: '个人中心' } },
  { path: '/:pathMatch(.*)*', redirect: '/' }
]
const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 })
})
router.beforeEach((to) => {
  document.title = to.meta.title || '心旅 HeartPilot'
  if (!to.meta.public && !authState.token)
    return { path: '/login', query: { redirect: to.fullPath } }
  if (to.meta.guestOnly && authState.token) return '/consult'
  if (to.meta.admin && authState.user?.role !== 'ADMIN') return '/consult'
})
export default router
