<template>
  <div class="page-head">
    <div>
      <h1>行动规划</h1>
      <p>基于真实城市地点，生成可确认、可修改、可导出的行动方案。</p>
    </div>
    <button class="btn coral" @click="showCreate = true">＋ 创建任务</button>
  </div>
  <section class="agent-brief panel">
    <div>
      <span class="badge green">城市严格匹配</span>
      <h2>每一步都看得见，关键决定由你确认。</h2>
      <p>
        Agent
        会按你填写的城市检索餐厅、景点与公开地点信息，整理地址、路线、预算和备选方案。你确认后才生成最终计划。
      </p>
    </div>
    <div class="mini-flow">
      <span>分析</span><i>→</i><span>真实地点</span><i>→</i><span>预算</span><i>→</i
      ><span class="confirm">你确认</span><i>→</i><span>方案 / PDF</span>
    </div>
  </section>
  <div class="task-toolbar">
    <h3>我的任务</h3>
    <select v-model="filter" class="select">
      <option value="">全部状态</option>
      <option value="RUNNING">运行中</option>
      <option value="AWAITING_CONFIRMATION">等待确认</option>
      <option value="SUCCEEDED">已完成</option>
    </select>
  </div>
  <div v-if="!filtered.length" class="panel empty">
    <b>还没有行动任务</b>创建一个真实目标，让 Agent 为你拆解和执行。
  </div>
  <div class="task-list">
    <div
      v-for="t in filtered"
      :key="t.id"
      class="task-row panel"
      role="link"
      tabindex="0"
      @click="router.push(`/plans/${t.id}`)"
      @keydown.enter="router.push(`/plans/${t.id}`)"
    >
      <span class="task-icon">{{ statusIcon(t.status) }}</span>
      <div>
        <b>{{ t.title }}</b>
        <p>{{ t.objective }}</p>
      </div>
      <div class="progress">
        <span>{{ t.currentStep }}/{{ Math.min(t.maxSteps, 7) }} 步</span
        ><i><em :style="{ width: `${(t.currentStep / 7) * 100}%` }"></em></i>
      </div>
      <span class="badge" :class="statusClass(t.status)">{{ statusText(t.status) }}</span
      ><button
        v-if="!['RUNNING', 'WAITING'].includes(t.status)"
        class="row-delete"
        title="删除行程记录"
        @click.stop="removeTask(t)"
      >
        删除</button
      ><strong>→</strong>
    </div>
  </div>
  <div v-if="showCreate" class="modal-backdrop" @click.self="showCreate = false">
    <form class="modal panel" @submit.prevent="create">
      <header>
        <div>
          <span class="eyebrow">新的 Agent 任务</span>
          <h2>你想完成什么？</h2>
        </div>
        <button type="button" @click="showCreate = false">×</button>
      </header>
      <div class="field">
        <label>任务名称</label
        ><input
          v-model="form.title"
          class="input"
          maxlength="140"
          placeholder="例如：南宁周末约会"
        />
      </div>
      <div class="field">
        <label>目标与约束</label
        ><textarea
          v-model="form.objective"
          class="textarea"
          required
          placeholder="例如：周六下午约会，喜欢广西菜和安静散步，不去太吵的商场"
        ></textarea>
      </div>
      <div class="grid-2">
        <div class="field">
          <label>城市（用于严格限制搜索范围）</label
          ><input v-model="form.city" class="input" required placeholder="例如：南宁" />
        </div>
        <div class="field">
          <label>预算（元）</label
          ><input
            v-model.number="form.budget"
            class="input"
            type="number"
            min="0"
            placeholder="500"
          />
        </div>
      </div>
      <div class="field">
        <label>希望方案回答的问题 <small>每行一个，可填写多个</small></label
        ><textarea
          v-model="form.questionsText"
          class="textarea questions"
          placeholder="哪家店适合安静聊天？&#10;两个地点之间怎么走？&#10;下雨时有什么室内备选？"
        ></textarea>
      </div>
      <p v-if="createError" class="form-error">{{ createError }}</p>
      <footer>
        <button type="button" class="btn" @click="showCreate = false">取消</button
        ><button class="btn primary" :disabled="creating">
          {{ creating ? '创建中…' : '创建并开始' }}
        </button>
      </footer>
    </form>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { api, streamSSE } from '../api'
const router = useRouter(),
  tasks = ref([]),
  filter = ref(''),
  showCreate = ref(false),
  creating = ref(false),
  createError = ref(''),
  form = reactive({ title: '', objective: '', city: '', budget: null, questionsText: '' })
const filtered = computed(() =>
  filter.value ? tasks.value.filter((x) => x.status === filter.value) : tasks.value
)
onMounted(load)
async function load() {
  const page = await api.get('/agent-tasks')
  tasks.value = page.content
}
async function create() {
  creating.value = true
  createError.value = ''
  try {
    const questions = form.questionsText
      .split(/\n/)
      .map((x) => x.trim())
      .filter(Boolean)
    const key = crypto.randomUUID()
    const t = await api.post(
      '/agent-tasks',
      {
        title: form.title,
        objective: form.objective,
        parameters: { city: form.city.trim(), budget: form.budget, questions }
      },
      { headers: { 'Idempotency-Key': key } }
    )
    showCreate.value = false
    await streamSSE(`/agent-tasks/${t.id}/run`, null, {})
    router.push(`/plans/${t.id}`)
  } catch (e) {
    createError.value = e.response?.data?.message || e.message || '任务创建失败'
  } finally {
    creating.value = false
  }
}
async function removeTask(t) {
  if (!confirm(`删除「${t.title}」、执行记录和已生成的 PDF？`)) return
  await api.delete(`/agent-tasks/${t.id}`)
  tasks.value = tasks.value.filter((item) => item.id !== t.id)
}
function statusText(s) {
  return (
    {
      WAITING: '待启动',
      RUNNING: '运行中',
      AWAITING_CONFIRMATION: '等待确认',
      RETRY_WAIT: '等待重试',
      SUCCEEDED: '已完成',
      FAILED: '失败',
      CANCELLED: '已取消'
    }[s] || s
  )
}
function statusIcon(s) {
  return s === 'SUCCEEDED'
    ? '✓'
    : s === 'AWAITING_CONFIRMATION'
      ? '!'
      : s === 'RUNNING'
        ? '↻'
        : '↗'
}
function statusClass(s) {
  return s === 'SUCCEEDED' ? 'green' : s === 'AWAITING_CONFIRMATION' ? 'coral' : ''
}
</script>

<style scoped>
.agent-brief {
  padding: 36px;
  display: flex;
  align-items: center;
  gap: 44px;
  background: linear-gradient(120deg, #fffefa, #f2f0e9);
}
.agent-brief > div:first-child {
  max-width: 760px;
}
.agent-brief h2 {
  margin: 14px 0 9px;
  font-size: 26px;
}
.agent-brief p {
  margin: 0;
  color: var(--muted);
  font-size: 15px;
  line-height: 1.8;
}
.mini-flow {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 9px;
}
.mini-flow span {
  padding: 10px 12px;
  border-radius: 8px;
  background: white;
  border: 1px solid var(--line);
  font-size: 13px;
  font-weight: 600;
}
.mini-flow .confirm {
  color: #a94b3d;
  background: var(--coral-soft);
}
.mini-flow i {
  color: #aaa69c;
  font-style: normal;
}
.task-toolbar {
  margin: 34px 0 16px;
  display: flex;
  align-items: center;
}
.task-toolbar h3 {
  margin: 0;
  font-size: 19px;
}
.task-toolbar .select {
  width: 170px;
  margin-left: auto;
  padding: 8px 10px;
}
.task-list {
  display: grid;
  gap: 12px;
}
.task-row {
  padding: 20px 22px;
  display: grid;
  grid-template-columns: 46px 1fr 150px auto auto 22px;
  align-items: center;
  gap: 16px;
  cursor: pointer;
  transition: 0.2s;
}
.task-row:hover {
  transform: translateY(-1px);
  box-shadow: 0 10px 25px rgba(30, 30, 28, 0.06);
}
.task-icon {
  display: grid;
  place-items: center;
  width: 44px;
  height: 44px;
  border-radius: 12px;
  background: #eeece5;
  color: #6a685f;
}
.task-row b {
  font-size: 16px;
}
.task-row p {
  max-width: 760px;
  margin: 5px 0 0;
  color: var(--muted);
  font-size: 14px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.progress span {
  color: var(--muted);
  font-size: 12px;
}
.progress i {
  display: block;
  height: 5px;
  margin-top: 6px;
  border-radius: 4px;
  background: #ece9e1;
  overflow: hidden;
}
.progress em {
  display: block;
  height: 100%;
  background: var(--coral);
}
.row-delete {
  min-height: 34px;
  padding: 6px;
  border: 0;
  background: transparent;
  color: #a45548;
  font-size: 13px;
}
.task-row > strong {
  color: #aaa79e;
}
.modal-backdrop {
  position: fixed;
  inset: 0;
  z-index: 100;
  display: grid;
  place-items: center;
  padding: 20px;
  background: rgba(27, 27, 25, 0.42);
  backdrop-filter: blur(5px);
}
.modal {
  width: min(640px, 100%);
  padding: 32px;
  display: grid;
  gap: 19px;
}
.modal header {
  display: flex;
}
.modal header h2 {
  margin: 7px 0 22px;
}
.modal header button {
  margin-left: auto;
  align-self: start;
  border: 0;
  background: transparent;
  font-size: 24px;
}
.modal footer {
  display: flex;
  justify-content: flex-end;
  gap: 9px;
  margin-top: 6px;
}
.form-error {
  margin: 0;
  color: #b23f31;
  font-size: 14px;
}
@media (max-width: 850px) {
  .agent-brief {
    align-items: flex-start;
    flex-direction: column;
  }
  .mini-flow {
    margin: 0;
    flex-wrap: wrap;
  }
  .task-row {
    grid-template-columns: 44px 1fr auto;
  }
  .task-row .progress {
    display: none;
  }
  .task-row > .badge {
    grid-column: 2;
  }
  .row-delete {
    grid-column: 2;
    justify-self: start;
  }
  .task-row > strong {
    grid-row: 1;
    grid-column: 3;
  }
}
@media (max-width: 500px) {
  .agent-brief {
    padding: 24px;
  }
  .mini-flow i {
    display: none;
  }
  .modal {
    padding: 24px;
  }
}
.questions {
  min-height: 115px;
}
.field label small {
  margin-left: 6px;
  color: var(--muted);
  font-weight: 400;
}
</style>
