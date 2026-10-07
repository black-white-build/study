import { createRouter, createWebHistory, RouteRecordRaw } from 'vue-router'
import { authState } from '../stores/auth'

const routes: RouteRecordRaw[] = [
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
    meta: { title: 'AI 答疑' }
  },
  {
    path: '/planning',
    component: () => import('../views/Plans.vue'),
    meta: { title: '行动规划', planning: true }
  },
  { path: '/plans', component: () => import('../views/Plans.vue'), meta: { title: '我的计划' } },
  {
    path: '/plans/:id',
    component: () => import('../views/TaskDetail.vue'),
    meta: { title: '任务详情' }
  },
  {
    path: '/settings',
    component: () => import('../views/Settings.vue'),
    meta: { title: '个人设置' }
  },
  {
    path: '/costs',
    component: () => import('../views/CostDashboard.vue'),
    meta: { title: 'AI 用量' }
  },
  {
    path: '/admin/knowledge',
    component: () => import('../views/Knowledge.vue'),
    meta: { admin: true, title: '知识库管理' }
  },
  { path: '/:pathMatch(.*)*', redirect: '/' }
]
const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 })
})
router.beforeEach((to) => {
  document.title = String(to.meta.title || '心旅 HeartPilot')
  if (!to.meta.public && !authState.token)
    return { path: '/login', query: { redirect: to.fullPath } }
  if (to.meta.guestOnly && authState.token) return '/consult'
  if (to.meta.admin && authState.user?.role !== 'ADMIN') return '/consult'
})
export default router
