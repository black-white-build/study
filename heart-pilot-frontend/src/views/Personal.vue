<template>
  <div class="page-head">
    <div>
      <h1>个人中心</h1>
      <p>你的会话、报告、任务与生成文件集中在这里。</p>
    </div>
    <router-link to="/profile" class="btn">编辑资料</router-link>
  </div>
  <section class="identity panel">
    <span class="identity-avatar">{{ avatarText }}</span>
    <div>
      <h2>{{ authState.user?.nickname || authState.user?.username || '用户' }}</h2>
      <p>
        @{{ authState.user?.username || '—' }} ·
        {{ authState.user?.role === 'ADMIN' ? '管理员' : '普通用户' }}
      </p>
    </div>
    <span class="mood"
      >当前情绪 <b>{{ authState.user?.emotionStatus || '未设置' }}</b></span
    >
  </section>
  <p v-if="loadError" class="load-error" role="alert">{{ loadError }}</p>
  <div class="summary grid-3">
    <router-link to="/consult" class="panel"
      ><span>咨询会话</span><b>{{ loading ? '—' : totals.conversations }}</b
      ><small>查看全部 →</small></router-link
    ><router-link to="/reports" class="panel"
      ><span>关系报告</span><b>{{ loading ? '—' : totals.reports }}</b
      ><small>查看全部 →</small></router-link
    ><router-link to="/plans" class="panel"
      ><span>行动任务</span><b>{{ loading ? '—' : totals.tasks }}</b
      ><small>查看全部 →</small></router-link
    >
  </div>
  <div class="personal-grid">
    <section class="panel panel-pad">
      <header>
        <h2>最近会话</h2>
        <router-link to="/consult">全部 →</router-link>
      </header>
      <div v-if="!loading && !conversations.length" class="empty"><b>暂无会话</b></div>
      <router-link v-for="c in conversations.slice(0, 5)" :key="c.id" to="/consult" class="record"
        ><span>◌</span>
        <div>
          <b>{{ c.title }}</b
          ><small>{{ date(c.lastMessageAt) }} · {{ c.model }}</small>
        </div>
        <i>→</i></router-link
      >
    </section>
    <section class="panel panel-pad">
      <header>
        <h2>生成文件</h2>
        <span>{{ loading ? '—' : `${totals.files} 个` }}</span>
      </header>
      <div v-if="!loading && !files.length" class="empty">
        <b>暂无文件</b>报告和方案 PDF 会出现在这里。
      </div>
      <article v-for="f in files" :key="f.id" class="record">
        <span>PDF</span>
        <div>
          <b>{{ f.fileName }}</b
          ><small>{{ size(f.sizeBytes) }} · {{ date(f.createdAt) }}</small>
        </div>
        <button @click="download(f)">↓</button>
      </article>
    </section>
  </div>
</template>
<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { api } from '../api'
import { authState, refreshMe } from '../stores/auth'
const conversations = ref([]),
  reports = ref([]),
  tasks = ref([]),
  files = ref([])
const loading = ref(true)
const loadError = ref('')
const totals = reactive({ conversations: 0, reports: 0, tasks: 0, files: 0 })
const avatarText = computed(() =>
  (authState.user?.nickname || authState.user?.username || '用').slice(0, 1)
)

onMounted(loadPersonalData)

async function loadPersonalData() {
  loading.value = true
  loadError.value = ''
  try {
    await refreshMe()
    const resources = [
      { key: 'conversations', target: conversations, request: api.get('/conversations') },
      { key: 'reports', target: reports, request: api.get('/reports') },
      { key: 'tasks', target: tasks, request: api.get('/agent-tasks') },
      { key: 'files', target: files, request: api.get('/files') }
    ]
    const results = await Promise.allSettled(resources.map((resource) => resource.request))
    let failedCount = 0

    results.forEach((result, index) => {
      const resource = resources[index]
      if (result.status === 'fulfilled') {
        resource.target.value = pageContent(result.value)
        totals[resource.key] = pageTotal(result.value)
      } else {
        resource.target.value = []
        totals[resource.key] = 0
        failedCount += 1
      }
    })

    if (failedCount) {
      loadError.value = `有 ${failedCount} 项数据暂时加载失败，请刷新后重试。`
    }
  } catch (error) {
    if (error.response?.status !== 401) {
      loadError.value = '个人资料加载失败，请检查后端服务后重试。'
    }
  } finally {
    loading.value = false
  }
}

function pageContent(page) {
  if (Array.isArray(page)) return page
  return Array.isArray(page?.content) ? page.content : []
}

function pageTotal(page) {
  return Number.isFinite(page?.totalElements) ? page.totalElements : pageContent(page).length
}
function date(v) {
  return v ? new Date(v).toLocaleDateString('zh-CN') : '—'
}
function size(n) {
  return n > 1048576 ? (n / 1048576).toFixed(1) + ' MB' : Math.ceil(n / 1024) + ' KB'
}
function download(f) {
  api.download(`/files/${f.id}/download`, f.fileName)
}
</script>
<style scoped>
.load-error {
  margin: 0 0 18px;
  padding: 12px 16px;
  border: 1px solid #e8b8ae;
  border-radius: 12px;
  background: #fff4f1;
  color: #9c3d2e;
}
.identity {
  padding: 28px;
  display: flex;
  align-items: center;
}
.identity-avatar {
  display: grid;
  place-items: center;
  width: 66px;
  height: 66px;
  border-radius: 18px;
  background: #e8dfcf;
  font-size: 24px;
  font-weight: 700;
}
.identity h2 {
  margin: 0 0 6px;
  font-size: 22px;
}
.identity p {
  margin: 0;
  color: var(--muted);
  font-size: 14px;
}
.identity > div {
  margin-left: 16px;
}
.mood {
  margin-left: auto;
  color: var(--muted);
  font-size: 13px;
}
.mood b {
  margin-left: 8px;
  padding: 8px 12px;
  border-radius: 99px;
  color: #9a473a;
  background: var(--coral-soft);
}
.summary {
  margin-top: 20px;
}
.summary a {
  padding: 24px;
  display: grid;
}
.summary span {
  color: var(--muted);
  font-size: 13px;
}
.summary b {
  margin: 8px 0;
  font-size: 30px;
}
.summary small {
  color: var(--coral);
  font-size: 12px;
}
.personal-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 22px;
  margin-top: 22px;
}
.personal-grid section > header {
  display: flex;
  align-items: center;
  margin-bottom: 14px;
}
.personal-grid h2 {
  margin: 0;
  font-size: 18px;
}
.personal-grid header > a,
.personal-grid header > span {
  margin-left: auto;
  color: var(--muted);
  font-size: 12px;
}
.record {
  padding: 14px 0;
  display: grid;
  grid-template-columns: 42px 1fr auto;
  align-items: center;
  gap: 12px;
  border-top: 1px solid var(--line);
}
.record > span {
  display: grid;
  place-items: center;
  width: 40px;
  height: 40px;
  border-radius: 10px;
  color: #9c4739;
  background: var(--coral-soft);
  font-size: 11px;
  font-weight: 700;
}
.record b,
.record small {
  display: block;
}
.record b {
  font-size: 14px;
}
.record small {
  margin-top: 4px;
  color: var(--muted);
  font-size: 12px;
}
.record i {
  color: #aaa69e;
  font-style: normal;
}
.record button {
  min-width: 36px;
  min-height: 36px;
  border: 0;
  background: transparent;
  font-size: 18px;
}
@media (max-width: 720px) {
  .identity {
    align-items: flex-start;
    flex-wrap: wrap;
  }
  .mood {
    width: 100%;
    margin: 18px 0 0 82px;
  }
  .personal-grid {
    grid-template-columns: 1fr;
  }
}
</style>
