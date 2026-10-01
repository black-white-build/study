<template>
  <div class="page-head">
    <div>
      <h1>个人设置</h1>
      <p>管理账号展示信息。答疑与行动规划分别使用各自会话中明确提供的事实。</p>
    </div>
    <button class="btn primary" :disabled="saving" @click="save">
      {{ saving ? '保存中…' : '保存设置' }}
    </button>
  </div>
  <section class="panel settings-card">
    <header>
      <span class="big-avatar">{{ user.nickname?.slice(0, 1) || '你' }}</span>
      <div>
        <span class="eyebrow">账户资料</span>
        <h2>基础信息</h2>
        <p>这些设置只用于界面称呼和展示，不会被当作对他人心理状态的判断依据。</p>
      </div>
    </header>
    <div class="field"><label>称呼</label><input v-model="user.nickname" class="input" /></div>
    <div class="field">
      <label>当前状态</label
      ><select v-model="user.emotionStatus" class="select">
        <option v-for="emotion in emotions" :key="emotion">{{ emotion }}</option>
      </select>
    </div>
  </section>
  <div v-if="toast" class="toast">{{ toast }}</div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { api } from '../api'
import { refreshMe } from '../stores/auth'

const emotions = ['平静', '开心', '期待', '困惑', '难过', '焦虑', '生气']
const user = reactive({ nickname: '', emotionStatus: '平静' })
const saving = ref(false)
const toast = ref('')

onMounted(async () => Object.assign(user, await api.get('/users/me')))
async function save() {
  saving.value = true
  try {
    await api.patch('/users/me', user)
    await refreshMe()
    toast.value = '个人设置已保存'
    setTimeout(() => (toast.value = ''), 2600)
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.settings-card {
  max-width: 680px;
  padding: 30px;
}
.settings-card header {
  display: flex;
  align-items: center;
  gap: 18px;
  margin-bottom: 26px;
}
.settings-card h2 {
  margin: 5px 0;
}
.settings-card p {
  margin: 0;
  color: var(--muted);
  line-height: 1.6;
}
.big-avatar {
  display: grid;
  place-items: center;
  width: 64px;
  height: 64px;
  flex: 0 0 auto;
  border-radius: 18px;
  background: var(--coral-soft);
  color: var(--coral);
  font-size: 24px;
  font-weight: 700;
}
.field + .field {
  margin-top: 18px;
}
</style>
