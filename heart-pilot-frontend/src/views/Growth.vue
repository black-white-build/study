<template>
  <div class="page-head">
    <div>
      <h1>成长计划</h1>
      <p>档案定方向，咨询与事件提供事实，行动打卡验证变化，AI 每周复盘。</p>
    </div>
    <div>
      <button class="btn" :disabled="reviewing" @click="review">
        {{ reviewing ? '正在汇总本周数据…' : '生成本周复盘' }}</button
      ><button class="btn coral" @click="showPlan = true">＋ 7 天计划</button>
    </div>
  </div>

  <section class="loop panel">
    <div>
      <span class="eyebrow">本周成长焦点</span>
      <h2>{{ dashboard.focus || '先从一次真实记录开始' }}</h2>
      <p>复盘会综合关系档案、近 7 天咨询、事件记录和行动打卡，不再是孤立报告。</p>
    </div>
    <router-link class="btn" to="/profile">完善关系档案</router-link>
  </section>

  <div class="growth-stats grid-3">
    <article class="panel">
      <span>本周打卡</span><b>{{ dashboard.weeklyCompleted ?? completed }}<small> 次</small></b
      ><i
        ><em
          :style="{
            width: `${Math.min(((dashboard.weeklyCompleted ?? completed) / 7) * 100, 100)}%`
          }"
        ></em
      ></i>
    </article>
    <article class="panel">
      <span>本周关系事件</span><b>{{ dashboard.weeklyEvents ?? 0 }}<small> 条</small></b>
      <p>事实、情绪与需要都可被复盘</p>
    </article>
    <article class="panel mood-card">
      <span>最近情绪</span><b>{{ events[0]?.emotion || '尚未记录' }}</b>
      <p>{{ events[0]?.title || '完成今天的关系脉搏吧' }}</p>
    </article>
  </div>

  <section class="pulse panel panel-pad">
    <div :key="dashboard.dailyTopic?.updatedAt" class="pulse-copy">
      <div class="topic-head">
        <span class="eyebrow">每日 2 分钟连接</span
        ><button class="btn topic-refresh" :disabled="topicRefreshing" @click="refreshTopic">
          {{ topicRefreshing ? '正在同步更新…' : '↻ 更新话题' }}
        </button>
      </div>
      <h2>
        {{
          dashboard.dailyTopic?.question ||
          dashboard.dailyQuestion ||
          '今天哪一个瞬间让你感到被理解？'
        }}
      </h2>
      <p>{{ dashboard.dailyTopic?.context || '这条回答会作为关系事件进入本周复盘。' }}</p>
      <div v-if="dashboard.dailyTopic?.sources?.length" class="topic-sources">
        <span>实时话题来源 · {{ topicUpdatedAt }}</span
        ><a
          v-for="source in dashboard.dailyTopic.sources"
          :key="`${dashboard.dailyTopic.updatedAt}-${source.url}`"
          :href="source.url"
          target="_blank"
          rel="noopener noreferrer"
          >{{ source.title }} ↗</a
        >
      </div>
      <small v-else class="topic-offline">{{
        dashboard.dailyTopic?.live === false
          ? '暂未取得实时来源，问题已切换；稍后可再次更新链接。'
          : ''
      }}</small>
    </div>
    <form @submit.prevent="savePulse">
      <div class="score">
        <label
          >亲密感 <b>{{ pulseForm.closeness }}/5</b
          ><input v-model.number="pulseForm.closeness" type="range" min="1" max="5" /></label
        ><label
          >压力 <b>{{ pulseForm.stress }}/5</b
          ><input v-model.number="pulseForm.stress" type="range" min="1" max="5" /></label
        ><label
          >情绪<select v-model="pulseForm.emotion" class="select">
            <option v-for="e in emotions" :key="e">{{ e }}</option>
          </select></label
        >
      </div>
      <textarea
        v-model="pulseForm.answer"
        class="textarea"
        placeholder="写下你对当前问题的真实回答（可选）"
      ></textarea
      ><button class="btn primary" :disabled="pulseSaving">
        {{ pulseSaving ? '保存中…' : '记录今日脉搏' }}
      </button>
    </form>
  </section>

  <section v-if="latestReview" class="weekly-review panel panel-pad">
    <header>
      <div>
        <span class="eyebrow">最近一次 AI 周复盘</span>
        <h2>{{ latestReview.title }}</h2>
      </div>
      <router-link to="/reports" class="btn">查看全部报告</router-link>
    </header>
    <p>{{ latestReview.problemSummary }}</p>
    <div>{{ latestReview.analysis }}</div>
    <ul>
      <li v-for="action in reportActions(latestReview)" :key="action">{{ action }}</li>
    </ul>
  </section>

  <div class="growth-grid">
    <section class="panel panel-pad">
      <header class="card-head">
        <div>
          <h2>7 天行动计划</h2>
          <p>报告行动项也可以一键转入这里，每天完成一个足够小的行动。</p>
        </div>
      </header>
      <div v-if="!plans.length" class="empty">
        <b>还没有成长计划</b>从档案关注点或一份关系报告开始。
      </div>
      <article v-for="view in plans" :key="view.plan.id" class="plan-card">
        <div class="plan-title">
          <span class="badge green">{{ view.plan.status }}</span
          ><b>{{ view.plan.title }}</b
          ><small>{{ view.plan.startDate }} — {{ view.plan.endDate }}</small>
        </div>
        <p>{{ view.plan.goal }}</p>
        <div class="seven-days">
          <button
            v-for="day in days(view.plan)"
            :key="day.date"
            :title="`${day.date} · ${actionFor(view.plan, day.index)}`"
            :class="{ done: checkinFor(view, day.date)?.completed, today: day.date === today }"
            @click="openCheckin(view, day)"
          >
            <span>{{ day.label }}</span
            ><b>{{ day.dateLabel }}</b
            ><i v-if="checkinFor(view, day.date)?.completed" aria-label="已完成">✓</i>
          </button>
        </div>
      </article>
    </section>
    <section class="panel panel-pad">
      <header class="card-head">
        <div>
          <h2>关系事件</h2>
          <p>用事实、情绪和需要记录真实变化。</p>
        </div>
        <button class="btn" @click="showEvent = true">＋ 记录</button>
      </header>
      <div v-if="!events.length" class="empty">
        <b>还没有事件记录</b>记录一次让你有情绪波动的互动。
      </div>
      <div class="event-list">
        <article v-for="e in events" :key="e.id">
          <span>{{ emotionIcon(e.emotion) }}</span>
          <div>
            <b>{{ e.title }}</b>
            <p>{{ e.description }}</p>
            <small>{{ new Date(e.happenedAt).toLocaleString('zh-CN') }}</small>
          </div>
        </article>
      </div>
    </section>
  </div>

  <div v-if="showPlan" class="modal-backdrop" @click.self="showPlan = false">
    <form class="modal panel" @submit.prevent="createPlan">
      <h2>创建 7 天行动计划</h2>
      <div class="field">
        <label>计划名称</label
        ><input
          v-model="planForm.title"
          class="input"
          required
          placeholder="例如：减少防御式沟通"
        />
      </div>
      <div class="field">
        <label>希望看到的改变</label
        ><textarea
          v-model="planForm.goal"
          class="textarea"
          :placeholder="dashboard.focus || '7 天后，希望哪件具体事情有所不同？'"
        ></textarea>
      </div>
      <footer>
        <button type="button" class="btn" @click="showPlan = false">取消</button
        ><button class="btn primary">创建计划</button>
      </footer>
    </form>
  </div>
  <div v-if="showEvent" class="modal-backdrop" @click.self="showEvent = false">
    <form class="modal panel" @submit.prevent="createEvent">
      <h2>记录关系事件</h2>
      <div class="field">
        <label>发生了什么</label><input v-model="eventForm.title" class="input" required />
      </div>
      <div class="field">
        <label>只描述可观察的事实</label
        ><textarea v-model="eventForm.description" class="textarea"></textarea>
      </div>
      <div class="field">
        <label>你的情绪</label
        ><select v-model="eventForm.emotion" class="select">
          <option v-for="e in emotions" :key="e">{{ e }}</option>
        </select>
      </div>
      <footer>
        <button type="button" class="btn" @click="showEvent = false">取消</button
        ><button class="btn primary">保存记录</button>
      </footer>
    </form>
  </div>
  <div v-if="checkinTarget" class="modal-backdrop" @click.self="checkinTarget = null">
    <form class="modal panel" @submit.prevent="saveCheckin">
      <h2>{{ checkinTarget.day.date }} 打卡</h2>
      <div class="today-action">
        <span>今天的小行动</span
        ><b>{{ actionFor(checkinTarget.view.plan, checkinTarget.day.index) }}</b>
      </div>
      <label class="check-toggle"
        ><input v-model="checkForm.completed" type="checkbox" /> 今天的行动已经完成</label
      >
      <div class="field">
        <label>完成后的情绪</label
        ><select v-model="checkForm.emotion" class="select">
          <option v-for="e in emotions" :key="e">{{ e }}</option>
        </select>
      </div>
      <div class="field">
        <label>一句复盘</label><textarea v-model="checkForm.note" class="textarea"></textarea>
      </div>
      <footer><button class="btn primary">保存打卡</button></footer>
    </form>
  </div>
  <div v-if="toast" class="toast">{{ toast }}</div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { api } from '../api'
const plans = ref([]),
  events = ref([]),
  dashboard = ref({}),
  latestReview = ref(null),
  showPlan = ref(false),
  showEvent = ref(false),
  checkinTarget = ref(null),
  toast = ref(''),
  reviewing = ref(false),
  pulseSaving = ref(false),
  topicRefreshing = ref(false),
  today = localDateValue(new Date()),
  emotions = ['平静', '开心', '期待', '困惑', '难过', '焦虑', '生气'],
  planForm = reactive({ title: '', goal: '' }),
  eventForm = reactive({ title: '', description: '', emotion: '平静' }),
  checkForm = reactive({ completed: true, emotion: '平静', note: '' }),
  pulseForm = reactive({ closeness: 3, stress: 3, emotion: '平静', answer: '' })
const completed = computed(
  () => plans.value.flatMap((x) => x.checkins).filter((x) => x.completed).length
)
const topicUpdatedAt = computed(() =>
  dashboard.value.dailyTopic?.updatedAt
    ? new Date(dashboard.value.dailyTopic.updatedAt).toLocaleTimeString('zh-CN', {
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit'
      })
    : ''
)
onMounted(load)
async function load() {
  const result = await Promise.all([
    api.get('/growth/plans'),
    api.get('/growth/events'),
    api.get('/growth/dashboard')
  ])
  plans.value = result[0]
  events.value = result[1].content
  dashboard.value = result[2]
  latestReview.value = result[2].latestWeeklyReview
}
async function createPlan() {
  await api.post('/growth/plans', planForm)
  showPlan.value = false
  Object.assign(planForm, { title: '', goal: '' })
  await load()
  show('7 天计划已创建')
}
async function createEvent() {
  await api.post('/growth/events', eventForm)
  showEvent.value = false
  Object.assign(eventForm, { title: '', description: '', emotion: '平静' })
  await load()
  show('关系事件已记录')
}
async function savePulse() {
  pulseSaving.value = true
  try {
    await api.post('/growth/pulse', pulseForm)
    pulseForm.answer = ''
    await load()
    show('今天的关系脉搏已进入本周复盘')
  } finally {
    pulseSaving.value = false
  }
}
async function refreshTopic() {
  topicRefreshing.value = true
  try {
    const topic = await api.post('/growth/daily-topic/refresh')
    dashboard.value = { ...dashboard.value, dailyTopic: topic, dailyQuestion: topic.question }
    show(topic.live ? '问题、话题说明和来源链接已同步更新' : '问题已更新，实时来源暂不可用')
  } catch (e) {
    show(e.response?.data?.message || '话题更新失败')
  } finally {
    topicRefreshing.value = false
  }
}
function localDateValue(date) {
  const pad = (value) => String(value).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}
function days(p) {
  const labels = ['一', '二', '三', '四', '五', '六', '日']
  return Array.from({ length: 7 }, (_, i) => {
    const d = new Date(`${p.startDate}T00:00:00`)
    d.setDate(d.getDate() + i)
    return {
      date: localDateValue(d),
      dateLabel: `${d.getMonth() + 1}/${d.getDate()}`,
      index: i,
      label: `周${labels[d.getDay() ? d.getDay() - 1 : 6]}`
    }
  })
}
function planActions(plan) {
  try {
    return JSON.parse(plan.dailyActionsJson || '[]')
  } catch {
    return []
  }
}
function actionFor(plan, index) {
  return planActions(plan)[index] || '完成一个与目标相关的小行动'
}
function checkinFor(v, date) {
  return v.checkins.find((x) => x.checkinDate === date)
}
function openCheckin(view, day) {
  checkinTarget.value = { view, day }
  const c = checkinFor(view, day.date)
  Object.assign(checkForm, c || { completed: true, emotion: '平静', note: '' })
}
async function saveCheckin() {
  await api.put(`/growth/plans/${checkinTarget.value.view.plan.id}/checkins`, {
    ...checkForm,
    date: checkinTarget.value.day.date
  })
  checkinTarget.value = null
  await load()
  show('打卡已保存，会进入本周复盘')
}
async function review() {
  if (reviewing.value) return
  reviewing.value = true
  show('正在结合关系档案、咨询、事件与打卡生成复盘…', 90000)
  try {
    const report = await api.post('/growth/weekly-review')
    latestReview.value = report
    await load()
    show(`「${report.title}」已生成并保存到关系报告`)
  } catch (e) {
    show(e.response?.data?.message || '周复盘生成失败')
  } finally {
    reviewing.value = false
  }
}
function reportActions(report) {
  try {
    return JSON.parse(report?.actionsJson || '[]')
  } catch {
    return []
  }
}
function emotionIcon(e) {
  return { 平静: '—', 开心: '◡', 期待: '✦', 困惑: '?', 难过: '⌒', 焦虑: '≈', 生气: '!' }[e] || '·'
}
function show(message, duration = 3000) {
  toast.value = message
  setTimeout(() => {
    if (toast.value === message) toast.value = ''
  }, duration)
}
</script>

<style scoped>
.page-head > div:last-child {
  display: flex;
  gap: 8px;
}
.loop {
  padding: 24px;
  margin-bottom: 20px;
  display: flex;
  align-items: center;
  gap: 18px;
  background: linear-gradient(120deg, #fffefa, #edf3ee);
}
.loop h2 {
  margin: 7px 0;
  font-size: 20px;
}
.loop p {
  margin: 0;
  color: var(--muted);
  font-size: 12px;
}
.loop .btn {
  margin-left: auto;
  white-space: nowrap;
}
.growth-stats article {
  padding: 20px;
}
.growth-stats span {
  color: var(--muted);
  font-size: 11px;
}
.growth-stats b {
  display: block;
  margin-top: 9px;
  font-size: 27px;
}
.growth-stats b small {
  font-size: 10px;
}
.growth-stats i {
  display: block;
  height: 5px;
  margin-top: 16px;
  background: #ebe8df;
  border-radius: 5px;
}
.growth-stats em {
  display: block;
  height: 100%;
  background: var(--coral);
  border-radius: 5px;
}
.growth-stats p {
  margin: 9px 0 0;
  color: var(--muted);
  font-size: 11px;
}
.mood-card {
  background: var(--sage);
}
.pulse {
  margin-top: 20px;
  display: grid;
  grid-template-columns: 0.8fr 1.2fr;
  gap: 28px;
}
.topic-head {
  display: flex;
  align-items: center;
  gap: 10px;
}
.topic-refresh {
  margin-left: auto;
  padding: 6px 9px;
  font-size: 11px;
}
.pulse-copy h2 {
  margin: 9px 0;
  font-size: 20px;
  line-height: 1.5;
}
.pulse-copy p {
  color: var(--muted);
  font-size: 12px;
  line-height: 1.7;
}
.topic-sources {
  display: grid;
  gap: 6px;
  margin-top: 15px;
}
.topic-sources span,
.topic-offline {
  color: var(--muted);
  font-size: 10px;
}
.topic-sources a {
  display: block;
  padding: 7px 9px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: #fff;
  font-size: 11px;
  line-height: 1.45;
}
.pulse form {
  display: grid;
  gap: 10px;
}
.score {
  display: grid;
  grid-template-columns: 1fr 1fr 1fr;
  gap: 10px;
}
.score label {
  display: grid;
  gap: 5px;
  color: var(--muted);
  font-size: 11px;
}
.score label b {
  color: var(--ink);
}
.weekly-review {
  margin-top: 20px;
  border-left: 4px solid var(--green);
}
.weekly-review header {
  display: flex;
  align-items: center;
}
.weekly-review header .btn {
  margin-left: auto;
}
.weekly-review h2 {
  margin: 6px 0;
}
.weekly-review > p {
  color: var(--muted);
  font-size: 13px;
}
.weekly-review > div {
  font-size: 14px;
  line-height: 1.75;
}
.weekly-review li {
  margin: 5px 0;
  font-size: 13px;
}
.growth-grid {
  display: grid;
  grid-template-columns: 1.15fr 0.85fr;
  gap: 20px;
  margin-top: 20px;
}
.card-head {
  display: flex;
  align-items: center;
  margin-bottom: 20px;
}
.card-head h2 {
  margin: 0;
  font-size: 17px;
}
.card-head p {
  margin: 4px 0 0;
  color: var(--muted);
  font-size: 11px;
}
.card-head .btn {
  margin-left: auto;
  padding: 7px 10px;
}
.plan-card {
  padding: 16px 0;
  border-top: 1px solid var(--line);
}
.plan-title {
  display: flex;
  align-items: center;
  gap: 9px;
}
.plan-title b {
  font-size: 13px;
}
.plan-title small {
  margin-left: auto;
  color: var(--muted);
  font-size: 9px;
}
.plan-card > p {
  margin: 8px 0 14px;
  color: var(--muted);
  font-size: 11px;
}
.seven-days {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 5px;
}
.seven-days button {
  position: relative;
  padding: 7px 2px;
  border: 1px solid var(--line);
  border-radius: 9px;
  background: #faf9f5;
}
.seven-days span,
.seven-days b {
  display: block;
}
.seven-days span {
  color: var(--muted);
  font-size: 8px;
}
.seven-days b {
  margin-top: 4px;
  font-size: 11px;
}
.seven-days i {
  position: absolute;
  top: 5px;
  right: 6px;
  display: grid;
  place-items: center;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.22);
  font-size: 10px;
  font-style: normal;
  font-weight: 800;
}
.seven-days .today {
  border-color: #e89687;
}
.seven-days .done {
  color: white;
  background: var(--green);
  border-color: var(--green);
}
.event-list article {
  display: grid;
  grid-template-columns: 34px 1fr;
  gap: 11px;
  padding: 14px 0;
  border-top: 1px solid var(--line);
}
.event-list article > span {
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  border-radius: 9px;
  background: #edeae2;
}
.event-list b {
  font-size: 12px;
}
.event-list p {
  margin: 4px 0;
  color: var(--muted);
  font-size: 10px;
  white-space: pre-wrap;
}
.event-list small {
  color: #aaa79f;
  font-size: 9px;
}
.modal-backdrop {
  position: fixed;
  inset: 0;
  z-index: 100;
  display: grid;
  place-items: center;
  padding: 20px;
  background: rgba(27, 27, 25, 0.42);
}
.modal {
  width: min(500px, 100%);
  padding: 27px;
  display: grid;
  gap: 15px;
}
.modal h2 {
  margin: 0 0 5px;
}
.modal footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
.check-toggle,
.today-action {
  padding: 14px;
  border-radius: 10px;
  background: var(--sage);
  font-size: 12px;
}
.today-action {
  display: grid;
  gap: 5px;
}
.today-action span {
  color: var(--muted);
  font-size: 10px;
}
@media (max-width: 850px) {
  .growth-grid,
  .pulse {
    grid-template-columns: 1fr;
  }
  .score {
    grid-template-columns: 1fr;
  }
  .loop {
    align-items: flex-start;
    flex-direction: column;
  }
  .loop .btn {
    margin-left: 0;
  }
}
@media (max-width: 500px) {
  .seven-days {
    grid-template-columns: repeat(4, 1fr);
  }
  .page-head > div:last-child {
    flex-wrap: wrap;
  }
}
/* Unified readable type scale */
.loop {
  padding: 28px;
}
.loop h2 {
  font-size: 23px;
}
.loop p {
  font-size: 15px;
  line-height: 1.7;
}
.growth-stats article {
  padding: 24px;
}
.growth-stats span {
  font-size: 13px;
}
.growth-stats b {
  font-size: 31px;
}
.growth-stats b small {
  font-size: 13px;
}
.growth-stats p {
  font-size: 13px;
  line-height: 1.65;
}
.pulse {
  gap: 32px;
}
.topic-refresh {
  font-size: 13px;
}
.pulse-copy h2 {
  font-size: 23px;
}
.pulse-copy p {
  font-size: 14px;
}
.topic-sources span,
.topic-offline {
  font-size: 12px;
}
.topic-sources a {
  padding: 9px 11px;
  font-size: 13px;
}
.score label {
  font-size: 13px;
}
.weekly-review > p {
  font-size: 14px;
}
.weekly-review > div {
  font-size: 15px;
}
.weekly-review li {
  font-size: 14px;
}
.card-head h2 {
  font-size: 19px;
}
.card-head p {
  font-size: 13px;
}
.plan-card {
  padding: 18px 0;
}
.plan-title b {
  font-size: 15px;
}
.plan-title small {
  font-size: 12px;
}
.plan-card > p {
  font-size: 13px;
}
.seven-days {
  gap: 7px;
}
.seven-days button {
  min-height: 60px;
}
.seven-days span {
  font-size: 11px;
}
.seven-days b {
  font-size: 14px;
}
.event-list article {
  grid-template-columns: 40px 1fr;
  gap: 13px;
  padding: 16px 0;
}
.event-list article > span {
  width: 38px;
  height: 38px;
}
.event-list b {
  font-size: 14px;
}
.event-list p {
  font-size: 13px;
  line-height: 1.6;
}
.event-list small {
  font-size: 12px;
}
.check-toggle,
.today-action {
  font-size: 14px;
}
.today-action span {
  font-size: 12px;
}
@media (max-width: 500px) {
  .loop {
    padding: 22px;
  }
  .growth-stats article {
    padding: 20px;
  }
  .seven-days button {
    min-height: 56px;
  }
  .plan-title {
    align-items: flex-start;
    flex-wrap: wrap;
  }
  .plan-title small {
    width: 100%;
    margin-left: 0;
  }
}
</style>
