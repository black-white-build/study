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
          <label>省 / 直辖市</label>
          <select v-model="form.province" class="select" required>
            <option value="" disabled>请选择省级行政区</option>
            <option v-for="province in provinces" :key="province" :value="province">{{ province }}</option>
          </select>
        </div>
        <div class="field">
          <label>城市</label>
          <select v-model="form.city" class="select" required :disabled="!form.province || citiesLoading">
            <option value="" disabled>{{ citiesLoading ? '加载城市中…' : '请选择城市' }}</option>
            <option v-for="city in cityOptions" :key="city" :value="city">{{ city }}</option>
          </select>
        </div>
      </div>
      <div class="grid-2">
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
          required
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
import { ref, reactive, computed, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { api, streamSSE } from '../api'
const router = useRouter(),
  tasks = ref([]),
  filter = ref(''),
  showCreate = ref(false),
  creating = ref(false),
  createError = ref(''),
  cityOptions = ref([]),
  citiesLoading = ref(false),
  form = reactive({ title: '', objective: '', province: '', city: '', budget: null, questionsText: '' }),
  provinces = ['北京市','天津市','上海市','重庆市','河北省','山西省','辽宁省','吉林省','黑龙江省','江苏省','浙江省','安徽省','福建省','江西省','山东省','河南省','湖北省','湖南省','广东省','海南省','四川省','贵州省','云南省','陕西省','甘肃省','青海省','台湾省','内蒙古自治区','广西壮族自治区','西藏自治区','宁夏回族自治区','新疆维吾尔自治区','香港特别行政区','澳门特别行政区']
const filtered = computed(() =>
  filter.value ? tasks.value.filter((x) => x.status === filter.value) : tasks.value
)
onMounted(load)
watch(
  () => form.province,
  async (province) => {
    form.city = ''
    cityOptions.value = []
    if (!province) return
    citiesLoading.value = true
    try {
      cityOptions.value = await api.get('/agent-tasks/region-cities', { params: { province } })
      if (cityOptions.value.length === 1) form.city = cityOptions.value[0]
    } catch (e) {
      createError.value = e.response?.data?.message || '城市列表加载失败'
    } finally {
      citiesLoading.value = false
    }
  }
)
async function load() {
  const page = await api.get('/agent-tasks')
  tasks.value = page.content
}
function createIdempotencyKey() {
  if (typeof globalThis.crypto?.randomUUID === 'function') {
    return globalThis.crypto.randomUUID()
  }

  const bytes = new Uint8Array(16)
  if (typeof globalThis.crypto?.getRandomValues === 'function') {
    globalThis.crypto.getRandomValues(bytes)
  } else {
    for (let i = 0; i < bytes.length; i += 1) {
      bytes[i] = Math.floor(Math.random() * 256)
    }
  }
  bytes[6] = (bytes[6] & 0x0f) | 0x40
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}
async function create() {
  creating.value = true
  createError.value = ''
  try {
    const questions = form.questionsText
      .split(/\n/)
      .map((x) => x.trim())
      .filter(Boolean)
    const key = createIdempotencyKey()
    const t = await api.post(
      '/agent-tasks',
      {
        title: form.title,
        objective: form.objective,
        parameters: { province: form.province, city: form.city.trim(), budget: form.budget, questions }
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
  overflow: hidden;
  background: rgba(27, 27, 25, 0.42);
  backdrop-filter: blur(5px);
}
.modal {
  width: min(640px, 100%);
  max-height: calc(100dvh - 40px);
  padding: 32px;
  display: grid;
  gap: 19px;
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-color: #aaa69c #eeece5;
  scrollbar-width: thin;
  -webkit-overflow-scrolling: touch;
}
.modal::-webkit-scrollbar {
  width: 7px;
}
.modal::-webkit-scrollbar-track {
  border-radius: 10px;
  background: #eeece5;
}
.modal::-webkit-scrollbar-thumb {
  border-radius: 10px;
  background: #aaa69c;
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
    width: 100%;
    max-height: calc(100dvh - 24px);
    padding: 24px 18px;
    gap: 17px;
  }
  .modal-backdrop {
    place-items: center;
    padding: 12px;
  }
  .modal footer {
    display: grid;
    grid-template-columns: minmax(0, 1fr) minmax(0, 1.35fr);
    padding-bottom: max(2px, env(safe-area-inset-bottom));
  }
  .modal footer .btn {
    width: 100%;
    min-width: 0;
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
