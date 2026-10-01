<template>
  <div class="app-shell" :class="{ 'nav-open': navOpen }">
    <aside class="sidebar">
      <router-link class="logo" to="/"
        ><span class="logo-mark">心</span
        ><span><b>心旅</b><small>HeartPilot</small></span></router-link
      >
      <nav>
        <p>沟通与行动</p>
        <router-link to="/consult"><span>◌</span>AI 答疑</router-link>
        <router-link to="/planning"><span>↗</span>行动规划</router-link>
        <router-link to="/plans"><span>✓</span>我的计划</router-link>
        <p>账户与资源</p>
        <router-link to="/costs"><span>◫</span>AI 用量</router-link>
        <router-link to="/settings"><span>◇</span>个人设置</router-link>
        <router-link v-if="authState.user?.role === 'ADMIN'" to="/admin/knowledge"
          ><span>◈</span>知识库管理</router-link
        >
      </nav>
      <div class="sidebar-foot">
        <router-link to="/settings" class="user-card"
          ><span class="avatar">{{ initial }}</span
          ><span
            ><b>{{ authState.user?.nickname }}</b
            ><small>{{ authState.user?.emotionStatus || '状态未设置' }}</small></span
          ></router-link
        >
        <button class="icon-button" title="退出登录" @click="signOut">↪</button>
      </div>
    </aside>
    <div class="mobile-backdrop" @click="navOpen = false"></div>
    <main class="workspace">
      <header class="topbar">
        <button class="mobile-menu" @click="navOpen = !navOpen">☰</button>
        <div>
          <span class="eyebrow">{{ greeting }}</span
          ><strong>{{ $route.meta.title }}</strong>
        </div>
        <router-link :to="topAction.to" class="top-action"
          >{{ topAction.label }} <span>→</span></router-link
        >
      </header>
      <div class="page-wrap"><slot /></div>
    </main>
  </div>
</template>
<script setup>
import { ref, computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { authState, logout } from '../stores/auth'
const router = useRouter(),
  route = useRoute(),
  navOpen = ref(false)
const initial = computed(() => authState.user?.nickname?.slice(0, 1) || '你')
const greeting = computed(() => (new Date().getHours() < 12 ? '上午好' : '欢迎回来'))
const topAction = computed(() =>
  route.path.startsWith('/consult')
    ? { to: '/planning', label: '开始规划' }
    : route.path.startsWith('/planning')
      ? { to: '/plans', label: '查看我的计划' }
      : route.path.startsWith('/plans')
        ? { to: '/planning', label: '新建规划' }
        : { to: '/consult', label: '开始倾诉' }
)
function signOut() {
  logout()
  router.push('/')
}
</script>
