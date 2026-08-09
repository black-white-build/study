<template>
  <div class="auth-page">
    <router-link class="auth-logo" to="/"><span>心</span><b>心旅 HeartPilot</b></router-link>
    <section class="auth-card">
      <span class="eyebrow">{{ registering ? '创建你的成长空间' : '欢迎回来' }}</span>
      <h1>{{ registering ? '从一次真诚记录开始' : '继续你的关系旅程' }}</h1>
      <p>
        {{ registering ? '你的会话、报告与计划只属于你。' : '登录后继续上次的倾诉、计划和复盘。' }}
      </p>
      <form @submit.prevent="submit">
        <div class="field">
          <label>用户名</label
          ><input
            v-model="form.username"
            class="input"
            autocomplete="username"
            minlength="3"
            required
            placeholder="至少 3 个字符"
          />
        </div>
        <div v-if="registering" class="field">
          <label>怎么称呼你</label
          ><input v-model="form.nickname" class="input" maxlength="64" placeholder="昵称" />
        </div>
        <div class="field">
          <label>密码</label
          ><input
            v-model="form.password"
            class="input"
            type="password"
            :autocomplete="registering ? 'new-password' : 'current-password'"
            minlength="8"
            required
            placeholder="至少 8 个字符"
          />
        </div>
        <p v-if="error" class="form-error">{{ error }}</p>
        <button class="btn primary submit" :disabled="loading">
          {{ loading ? '请稍候…' : registering ? '创建账户' : '登录' }}
        </button>
      </form>
      <div class="switch">
        {{ registering ? '已有账户？' : '第一次使用？' }}
        <router-link :to="registering ? '/login' : '/register'">{{
          registering ? '直接登录' : '创建账户'
        }}</router-link>
      </div>
    </section>
    <small class="auth-note">继续即表示你理解：AI 建议不替代专业服务。</small>
  </div>
</template>
<script setup>
import { reactive, ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api'
import { setSession } from '../stores/auth'
const route = useRoute(),
  router = useRouter(),
  registering = computed(() => route.path === '/register'),
  form = reactive({ username: '', password: '', nickname: '' }),
  loading = ref(false),
  error = ref('')
async function submit() {
  loading.value = true
  error.value = ''
  try {
    const data = await api.post(registering.value ? '/auth/register' : '/auth/login', form)
    setSession(data)
    router.push(route.query.redirect || '/consult')
  } catch (e) {
    error.value = e.response?.data?.message || e.message || '操作失败'
  } finally {
    loading.value = false
  }
}
</script>
<style scoped>
.auth-page {
  min-height: 100vh;
  display: grid;
  place-items: center;
  padding: 100px 24px;
  background:
    radial-gradient(circle at 12% 10%, #f8e9df, transparent 34%),
    radial-gradient(circle at 88% 85%, #e6ece5, transparent 35%), #f8f7f3;
}
.auth-logo {
  position: absolute;
  top: 30px;
  left: 38px;
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 16px;
}
.auth-logo span {
  display: grid;
  place-items: center;
  width: 38px;
  height: 38px;
  border-radius: 12px;
  color: white;
  background: var(--coral);
}
.auth-card {
  width: min(480px, 100%);
  padding: 46px;
  border: 1px solid var(--line);
  border-radius: 24px;
  background: rgba(255, 254, 250, 0.95);
  box-shadow: var(--shadow);
}
h1 {
  margin: 10px 0 10px;
  font-size: 34px;
  line-height: 1.25;
  letter-spacing: -1px;
}
.auth-card > p {
  margin: 0 0 30px;
  color: var(--muted);
  font-size: 15px;
  line-height: 1.7;
}
.auth-card form {
  display: grid;
  gap: 19px;
}
.submit {
  width: 100%;
  margin-top: 6px;
  padding: 13px;
}
.switch {
  margin-top: 26px;
  text-align: center;
  color: var(--muted);
  font-size: 14px;
}
.switch a {
  color: var(--coral);
  font-weight: 700;
}
.form-error {
  margin: 0 !important;
  color: #b44435 !important;
}
.auth-note {
  position: absolute;
  bottom: 24px;
  color: #89877f;
  font-size: 12px;
}
@media (max-width: 520px) {
  .auth-page {
    padding-inline: 18px;
  }
  .auth-card {
    padding: 34px 24px;
  }
  .auth-logo {
    left: 20px;
  }
  .auth-note {
    position: static;
    margin-top: 20px;
    text-align: center;
  }
}
</style>
