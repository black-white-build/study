<template>
  <div class="app-shell" :class="{ 'nav-open': navOpen }">
    <aside class="sidebar">
      <router-link class="logo" to="/"
        ><span class="logo-mark">心</span
        ><span><b>心旅</b><small>HeartPilot</small></span></router-link
      >
      <nav>
        <p>理解与行动</p>
        <router-link to="/consult"><span>◌</span>AI 情感咨询</router-link>
        <router-link to="/reports"><span>▤</span>关系报告</router-link>
        <router-link to="/plans"><span>↗</span>行动规划</router-link>
        <p>持续成长</p>
        <router-link to="/profile"><span>◇</span>关系档案</router-link>
        <router-link to="/growth"><span>✓</span>成长计划</router-link>
        <router-link to="/costs"><span>¥</span>消费成本</router-link>
        <router-link v-if="isAdmin" to="/knowledge"><span>▦</span>知识库管理</router-link>
      </nav>
      <div class="sidebar-foot">
        <router-link to="/me" class="user-card"
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
import { authState, isAdmin, logout } from '../stores/auth'
const router = useRouter(),
  route = useRoute(),
  navOpen = ref(false)
const initial = computed(() => authState.user?.nickname?.slice(0, 1) || '你')
const greeting = computed(() => (new Date().getHours() < 12 ? '上午好' : '欢迎回来'))
const topAction = computed(() =>
  route.path.startsWith('/consult')
    ? { to: '/plans', label: '开始行动' }
    : route.path.startsWith('/plans')
      ? { to: '/growth', label: '记录成长' }
      : { to: '/consult', label: '开始倾诉' }
)
function signOut() {
  logout()
  router.push('/')
}
</script>
