<template>
  <div v-if="detail">
    <div class="page-head">
      <div>
        <router-link to="/plans" class="back">← 返回行动规划</router-link>
        <h1>{{ detail.task.title }}</h1>
        <p>{{ detail.task.objective }}</p>
      </div>
      <div class="head-actions">
        <span class="badge" :class="statusClass(detail.task.status)">{{
          statusText(detail.task.status)
        }}</span
        ><button v-if="canCancel" class="btn danger" @click="cancel">取消任务</button
        ><button v-else class="btn danger" :disabled="deleting" @click="removeTask">
          {{ deleting ? '正在删除…' : '删除记录' }}
        </button>
      </div>
    </div>
    <section class="panel agent-observer">
      <header class="observer-head">
        <div>
          <span class="eyebrow">Agent 可观测执行</span>
          <h2>从检索到路线，每一步都有证据</h2>
          <p>页面每 1.2 秒同步任务轨迹；外部能力不可用时会明确显示降级，不会编造地点和距离。</p>
        </div>
        <div class="capability-badges">
          <span :class="['capability', evidence.sourceStatus === 'LIVE' ? 'online' : 'offline']"
            >地图 {{ evidence.sourceStatus === 'LIVE' ? '实时' : '暂无合格结果' }}</span
          ><span :class="['capability', reactState === '已参与' ? 'online' : 'offline']"
            >ReAct/MCP {{ reactState }}</span
          >
        </div>
      </header>
      <div class="phase-flow">
        <template v-for="(phase, index) in phaseDefinitions" :key="phase.code">
          <article :class="['phase-card', phaseStatus(phase.code)]">
            <span>{{ phaseStatus(phase.code) === 'done' ? '✓' : index + 1 }}</span>
            <div>
              <b>{{ phase.label }}</b
              ><small>{{ phaseHint(phase.code) }}</small>
            </div>
          </article>
          <i v-if="index < phaseDefinitions.length - 1">→</i>
        </template>
      </div>
      <div class="event-stream">
        <div class="stream-title">
          <b>实时执行轨迹</b
          ><small
            >{{ eventBranches.length }} 个规划分支 ·
            {{ executionEvents.length }} 条持久化事件</small
          >
        </div>
        <div v-if="!executionEvents.length" class="stream-empty">
          <span class="spinner"></span>任务启动后，这里会展示 Thought、Action、Observation 与
          Result。
        </div>
        <section v-for="branch in eventBranches" :key="branch.version" class="trace-branch">
          <button
            class="branch-row"
            type="button"
            :aria-expanded="isBranchExpanded(branch.version)"
            @click="toggleBranch(branch.version)"
          >
            <span class="branch-line"></span
            ><span class="branch-index">{{ branch.version + 1 }}</span>
            <span class="branch-main"
              ><b>{{ branch.title }}</b
              ><small>{{ branch.summary }}</small></span
            >
            <span class="branch-keywords">关键词：{{ branch.keywords }}</span
            ><span :class="['branch-status', branch.statusClass]">{{ branch.statusText }}</span>
            <span class="branch-toggle"
              >{{ isBranchExpanded(branch.version) ? '收起' : '展开' }}⌄</span
            >
          </button>
          <div v-if="isBranchExpanded(branch.version)" class="branch-events">
            <div v-if="!branch.events.length" class="stream-empty">
              <span class="spinner"></span>本轮轨迹正在同步…
            </div>
            <article
              v-for="event in branch.events"
              :key="event.id"
              :class="['event-card', event.eventType.toLowerCase()]"
            >
              <div class="event-icon">{{ eventIcon(event) }}</div>
              <div class="event-body">
                <div>
                  <b>{{ event.title }}</b
                  ><time>{{ time(event.createdAt) }}</time>
                </div>
                <ExpandableText :content="event.detail" :lines="4" />
                <footer>
                  <span v-if="event.provider">{{ event.provider }}</span
                  ><span v-if="event.toolName">工具：{{ event.toolName }}</span
                  ><span v-if="event.itemCount !== null && event.itemCount !== undefined"
                    >{{ event.itemCount }} 项</span
                  ><span v-if="event.durationMs !== null && event.durationMs !== undefined"
                    >{{ event.durationMs }} ms</span
                  ><a
                    v-if="event.sourceUrl"
                    :href="event.sourceUrl"
                    target="_blank"
                    rel="noreferrer"
                    >查看来源 ↗</a
                  >
                </footer>
              </div>
            </article>
          </div>
        </section>
      </div>
    </section>
    <div class="summary-grid">
      <section class="panel panel-pad">
        <h3>任务信息</h3>
        <dl class="task-info">
          <div>
            <dt>任务 ID</dt>
            <dd>#{{ detail.task.id }}</dd>
          </div>
          <div>
            <dt>城市</dt>
            <dd>{{ parameters.city || '—' }}</dd>
          </div>
          <div>
            <dt>预算</dt>
            <dd>{{ budgetText(parameters.budget) }}</dd>
          </div>
          <div>
            <dt>问题数量</dt>
            <dd>{{ (parameters.questions || []).length }} 个</dd>
          </div>
          <div>
            <dt>创建时间</dt>
            <dd>{{ date(detail.task.createdAt) }}</dd>
          </div>
          <div>
            <dt>工具调用</dt>
            <dd>{{ detail.toolCalls.length }} 次</dd>
          </div>
        </dl>
      </section>
      <section class="panel panel-pad tool-log">
        <h3>工具审计</h3>
        <div v-if="!detail.toolCalls.length" class="muted">尚未调用工具</div>
        <div class="tool-items">
          <article v-for="c in detail.toolCalls" :key="c.id">
            <span>↗</span>
            <div>
              <b>{{ c.toolName }}</b
              ><small>{{ c.status }} · {{ c.durationMs || 0 }}ms</small>
            </div>
          </article>
        </div>
      </section>
    </div>
    <div class="detail-grid">
      <section class="panel steps-panel">
        <header>
          <div>
            <b>执行过程</b><small>当前第 {{ detail.task.currentStep }} 步</small>
          </div>
          <span
            >{{ detail.steps.filter((x) => x.status === 'COMPLETED').length }}/{{
              detail.steps.length
            }}</span
          >
        </header>
        <div class="timeline">
          <article v-for="s in detail.steps" :key="s.id" :class="s.status.toLowerCase()">
            <div class="dot">{{ s.status === 'COMPLETED' ? '✓' : s.stepNo }}</div>
            <div>
              <b>{{ s.name }}</b
              ><ExpandableText :content="s.detail || stepHint(s)" :lines="6" /><small
                v-if="s.completedAt"
                >{{ time(s.completedAt) }}</small
              >
            </div>
          </article>
        </div>

        <div
          v-if="detail.task.status === 'RUNNING' || detail.task.status === 'WAITING'"
          class="running-box"
        >
          <span class="spinner"></span>
          <div>
            <b>{{ runningTitle }}</b>
            <p>{{ runningHint }}</p>
          </div>
        </div>

        <div v-if="detail.task.status === 'AWAITING_CONFIRMATION'" class="confirm-box">
          <span>等待你的确认</span>
          <h3>先核对当前方案，也可以直接修改约束后重新规划</h3>
          <div class="preview">
            <StructuredText :content="detail.task.planPreview || '方案仍在整理，请稍后刷新。'" />
          </div>
          <div class="plan-editor">
            <div class="editor-title">
              <b>确认前修改当前参数</b
              ><small
                >这里的地点、预算和问题清单以最后一次提交为准；应用修改后会新增一个规划分支，并重新提取对应关键词。</small
              >
            </div>
            <div class="grid-2">
              <div class="field">
                <label>省 / 直辖市</label>
                <select v-model="editor.province" class="select">
                  <option v-for="province in provinces" :key="province" :value="province">
                    {{ province }}
                  </option>
                </select>
              </div>
              <div class="field">
                <label>城市</label>
                <select
                  v-model="editor.city"
                  class="select"
                  :disabled="!editor.province || citiesLoading"
                >
                  <option value="" disabled>
                    {{ citiesLoading ? '加载城市中…' : '请选择城市' }}
                  </option>
                  <option v-for="city in cityOptions" :key="city" :value="city">{{ city }}</option>
                </select>
              </div>
            </div>
            <div class="grid-2">
              <div class="field">
                <label>预算（元）</label
                ><input
                  v-model.number="editor.budget"
                  class="input"
                  type="number"
                  min="0"
                  placeholder="500"
                />
              </div>
            </div>
            <div class="field">
              <label>需要方案逐项回答的问题 <small>每行一个，以当前完整清单为准</small></label
              ><textarea
                v-model="editor.questionsText"
                class="textarea questions"
                placeholder="哪家店适合安静聊天？&#10;两个地点之间怎么走？&#10;有哪些不去商场、有停车位且适合聊天的备选？"
              ></textarea>
            </div>
          </div>
          <div class="confirm-actions">
            <button class="btn" :disabled="submitting || !canRevise" @click="confirmTask(false)">
              {{ submitting ? '正在提交…' : '应用约束修改并从第一步重新规划' }}</button
            ><button
              class="btn coral"
              :disabled="submitting || canRevise"
              @click="confirmTask(true)"
            >
              {{
                submitting ? '正在刷新检索…' : canRevise ? '请先应用当前修改' : '确认并实时补充检索'
              }}
            </button>
          </div>
        </div>
      </section>
    </div>
    <section v-if="detail.task.evidenceUpdatedAt" class="panel evidence-panel">
      <div class="evidence-head">
        <div>
          <span class="eyebrow">真实地点与路线证据</span>
          <h2>{{ evidence.city }} · {{ evidence.topics }}</h2>
        </div>
        <small>更新时间：{{ evidence.searchedAt || '—' }}</small>
      </div>
      <div v-if="mapCards.length" class="place-grid">
        <article v-for="place in mapCards" :key="place.poiId || place.name" class="map-card">
          <img v-if="place.coverImageUrl" :src="place.coverImageUrl" :alt="place.name" />
          <span class="place-index">地图卡片</span>
          <h3>{{ place.name }}</h3>
          <p>{{ place.address }}</p>
          <small>{{ place.category || place.type }}</small>
          <div class="place-live-meta">
            <span :class="`business-${(place.businessStatus || 'UNKNOWN').toLowerCase()}`">
              {{ businessStatus(place.businessStatus) }}
            </span>
            <span v-if="place.rating">评分 {{ place.rating }}</span>
            <span v-if="place.businessHours">{{ place.businessHours }}</span>
          </div>
          <small v-if="place.statusCheckedAt">状态核验：{{ place.statusCheckedAt }}</small
          ><small v-if="place.businessStatusBasis === 'DERIVED_FROM_AMAP_OPENING_HOURS'"
            >营业状态按高德营业时间与当前时刻推算，请在出发前复核</small
          ><a v-if="place.mapUrl" :href="place.mapUrl" target="_blank" rel="noreferrer"
            >在高德地图中查看 ↗</a
          >
          <div v-if="place.routeFromPrevious" class="card-route">
            从上一站{{ routeModeText(place.routeFromPrevious) }}
            {{ formatDistance(place.routeFromPrevious.distanceMeters) }} · 约
            {{ place.routeFromPrevious.durationMinutes }} 分钟
          </div>
        </article>
      </div>
      <div v-else class="evidence-empty">
        {{ evidence.notice || '没有取得符合当前地点范围的可核验地图地点。' }}
      </div>
      <div v-if="evidence.routes?.length" class="route-list">
        <h3>地点间路线</h3>
        <div class="route-map-overview">
          <div class="route-map-title">
            <div><b>路线总览</b><small>A → B → C → D 按行程顺序连接</small></div>
            <small>轨迹与底图来自高德地图</small>
          </div>
          <img v-if="routeMapUrl" :src="routeMapUrl" alt="地点间路线总览图" />
          <div v-else-if="routeMapLoading" class="route-map-placeholder">正在生成路线图…</div>
          <div v-else class="route-map-placeholder">
            {{
              routeMapUnavailable ? '路线图片暂不可用，可继续使用下方导航链接。' : '等待路线数据。'
            }}
          </div>
        </div>
        <article
          v-for="(route, index) in evidence.routes"
          :key="`${route.originName}-${route.destinationName}-${index}`"
        >
          <div class="route-points">
            <b>{{ route.originName }}</b
            ><span>{{ routeModeText(route) }}</span
            ><b>{{ route.destinationName }}</b>
          </div>
          <div class="route-stats">
            <strong>{{ formatDistance(route.distanceMeters) }}</strong
            ><strong>约 {{ route.durationMinutes }} 分钟</strong
            ><small v-if="route.routeCheckedAt">实时刷新：{{ route.routeCheckedAt }}</small
            ><a :href="routeNavigationUrl(route)" target="_blank" rel="noreferrer">打开导航 ↗</a>
          </div>
        </article>
      </div>
      <p v-if="mapCards.length" class="evidence-notice">{{ evidence.notice }}</p>
    </section>
    <section v-if="detail.task.finalResult" class="panel result">
      <span class="eyebrow">行动报告已生成</span>
      <h2>你的可执行行动报告</h2>
      <StructuredText :content="detail.task.finalResult" />
      <div class="result-actions">
        <button
          v-if="!detail.pdfFile"
          class="btn coral"
          :disabled="pdfGenerating"
          @click="generatePdf"
        >
          {{ pdfGenerating ? '正在生成 PDF…' : '生成 PDF 文件' }}</button
        ><button v-else class="btn coral" :disabled="pdfDownloading" @click="downloadPdf">
          {{ pdfDownloading ? '正在下载…' : '下载 PDF 报告' }}</button
        ><span>{{
          detail.pdfFile
            ? `PDF 已生成（${fileSize(detail.pdfFile.sizeBytes)}），也已保存到个人中心。`
            : '请先确认上方报告内容，再按需生成 PDF；生成和下载是两个独立操作。'
        }}</span>
      </div>
    </section>
  </div>
  <div v-else class="panel empty">正在读取任务…</div>
  <transition name="toast"
    ><div v-if="error" class="toast">{{ error }}</div></transition
  >
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, streamSSE } from '../api'
import ExpandableText from '../components/ExpandableText.vue'
import StructuredText from '../components/StructuredText.vue'

const route = useRoute()
const router = useRouter()
const detail = ref()
const error = ref('')
const timer = ref()
const errorTimer = ref()
const pdfGenerating = ref(false)
const pdfDownloading = ref(false)
const submitting = ref(false)
const deleting = ref(false)
const hydratedKey = ref('')
const expandedBranches = ref(new Set())
const routeMapUrl = ref('')
const routeMapKey = ref('')
const routeMapLoading = ref(false)
const routeMapUnavailable = ref(false)
const editor = reactive({ province: '', city: '', budget: null, questionsText: '' })
const cityOptions = ref([])
const citiesLoading = ref(false)
const provinces = [
  '北京市',
  '天津市',
  '上海市',
  '重庆市',
  '河北省',
  '山西省',
  '辽宁省',
  '吉林省',
  '黑龙江省',
  '江苏省',
  '浙江省',
  '安徽省',
  '福建省',
  '江西省',
  '山东省',
  '河南省',
  '湖北省',
  '湖南省',
  '广东省',
  '海南省',
  '四川省',
  '贵州省',
  '云南省',
  '陕西省',
  '甘肃省',
  '青海省',
  '台湾省',
  '内蒙古自治区',
  '广西壮族自治区',
  '西藏自治区',
  '宁夏回族自治区',
  '新疆维吾尔自治区',
  '香港特别行政区',
  '澳门特别行政区'
]
watch(
  () => editor.province,
  async (province, previousProvince) => {
    if (!province) return
    citiesLoading.value = true
    try {
      cityOptions.value = await api.get('/agent-tasks/region-cities', { params: { province } })
      if (previousProvince && previousProvince !== province) editor.city = ''
      if (cityOptions.value.length === 1) editor.city = cityOptions.value[0]
    } catch (requestError) {
      showError(requestError.response?.data?.message || '城市列表加载失败')
    } finally {
      citiesLoading.value = false
    }
  }
)

const phaseDefinitions = [
  { code: 'ANALYZE', label: '分析行程需求' },
  { code: 'SEARCH', label: '检索展览 / 餐厅' },
  { code: 'FILTER', label: '筛选真实地点' },
  { code: 'ROUTE', label: '计算地点路线' },
  { code: 'GENERATE', label: '生成最终计划' }
]

const parameters = computed(() => parseJson(detail.value?.task.parametersJson, {}))
const evidence = computed(() =>
  parseJson(detail.value?.task.journeyEvidenceJson, {
    places: [],
    routes: [],
    sourceStatus: 'DEGRADED',
    notice: ''
  })
)
const mapCards = computed(() =>
  evidence.value.mapCards?.length ? evidence.value.mapCards : evidence.value.places || []
)
const executionEvents = computed(() => detail.value?.executionEvents || [])
const currentVersionEvents = computed(() =>
  executionEvents.value.filter((item) => eventVersion(item) === (detail.value?.task.versionNo || 0))
)
const eventBranches = computed(() => {
  const grouped = new Map()
  executionEvents.value.forEach((event) => {
    const version = eventVersion(event)
    if (!grouped.has(version)) grouped.set(version, [])
    grouped.get(version).push(event)
  })
  const currentVersion = detail.value?.task.versionNo || 0
  if (!grouped.has(currentVersion)) grouped.set(currentVersion, [])
  return [...grouped.entries()]
    .sort(([left], [right]) => left - right)
    .map(([version, events]) => {
      const analyzeMetadata =
        [...events]
          .reverse()
          .map(eventMetadata)
          .find((meta) => meta.city !== undefined && meta.budget !== undefined) || {}
      const topicMetadata =
        [...events]
          .reverse()
          .map(eventMetadata)
          .find((meta) => meta.topics) || {}
      const isCurrent = version === currentVersion
      const city = analyzeMetadata.city ?? (isCurrent ? parameters.value.city : '') ?? ''
      const budget = analyzeMetadata.budget ?? (isCurrent ? parameters.value.budget : null)
      const questionCount =
        analyzeMetadata.questionCount ??
        analyzeMetadata.questions?.length ??
        (isCurrent ? originalQuestions.value.length : 0)
      const keywords =
        topicMetadata.topics || (isCurrent ? evidence.value.topics : '') || '等待提取'
      return {
        version,
        events,
        title: version === 0 ? '首次规划' : `第 ${version} 次修改重规划`,
        summary: `${city || '地点待确认'} · ${budgetText(budget)} · ${questionCount} 个问题 · ${events.length} 条轨迹`,
        keywords,
        ...branchStatus(version, events, isCurrent)
      }
    })
})
const reactState = computed(() => {
  if (
    currentVersionEvents.value.some(
      (item) => item.provider?.includes('MCP') && item.status === 'SUCCEEDED'
    )
  )
    return '已参与'
  if (currentVersionEvents.value.some((item) => item.title.includes('未启用'))) return '未启用'
  return '待调用'
})
const canCancel = computed(
  () => detail.value && !['SUCCEEDED', 'CANCELLED', 'FAILED'].includes(detail.value.task.status)
)
const originalQuestions = computed(() =>
  Array.isArray(parameters.value.questions) ? parameters.value.questions : []
)
const enteredQuestions = computed(() =>
  editor.questionsText
    .split(/\n/)
    .map((value) => value.trim())
    .filter(Boolean)
)
const canRevise = computed(
  () =>
    Boolean(editor.province) &&
    Boolean(editor.city.trim()) &&
    (editor.province !== (parameters.value.province || '') ||
      editor.city.trim() !== (parameters.value.city || '') ||
      normalizeBudgetValue(editor.budget) !== normalizeBudgetValue(parameters.value.budget) ||
      JSON.stringify(enteredQuestions.value) !== JSON.stringify(originalQuestions.value))
)
const runningTitle = computed(() =>
  detail.value?.task.currentStep >= 6 ? '正在补充检索并生成报告' : '正在合并修改并重新规划'
)
const runningHint = computed(() =>
  detail.value?.task.currentStep >= 6
    ? '确认阶段新增问题已合并，系统正在重新提取类别、刷新实时来源并逐项回答。'
    : '地点、预算、最初问题和历次建议已合并，正在重新搜索真实地点。'
)

onMounted(async () => {
  await load()
  startPolling()
})
onBeforeUnmount(() => {
  clearInterval(timer.value)
  clearTimeout(errorTimer.value)
  if (routeMapUrl.value) URL.revokeObjectURL(routeMapUrl.value)
})

function parseJson(value, fallback) {
  try {
    return JSON.parse(value || '')
  } catch {
    return fallback
  }
}
function eventVersion(event) {
  return Number.isInteger(event.taskVersion) ? event.taskVersion : 0
}
function eventMetadata(event) {
  return parseJson(event?.metadataJson, {})
}
function normalizeBudgetValue(value) {
  if (value === null || value === undefined || value === '') return ''
  const amount = Number(value)
  return Number.isFinite(amount) ? String(amount) : String(value).trim()
}
function budgetText(value) {
  const normalized = normalizeBudgetValue(value)
  if (!normalized) return '未限定'
  const amount = Number(normalized)
  return `${Number.isFinite(amount) ? new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 20 }).format(amount) : normalized} 元`
}
function branchStatus(version, events, isCurrent) {
  if (!isCurrent) return { statusText: '已产生后续修改', statusClass: 'revised' }
  const status = detail.value?.task.status
  if (['RUNNING', 'WAITING', 'RETRY_WAIT'].includes(status))
    return { statusText: '同步执行中', statusClass: 'active' }
  if (status === 'AWAITING_CONFIRMATION') return { statusText: '等待确认', statusClass: 'waiting' }
  if (status === 'SUCCEEDED') return { statusText: '已完成', statusClass: 'done' }
  if (status === 'FAILED' || events.at(-1)?.eventType === 'ERROR')
    return { statusText: '执行异常', statusClass: 'failed' }
  return { statusText: statusText(status), statusClass: '' }
}
function isBranchExpanded(version) {
  return expandedBranches.value.has(version)
}
function toggleBranch(version) {
  const next = new Set(expandedBranches.value)
  next.has(version) ? next.delete(version) : next.add(version)
  expandedBranches.value = next
}
function startPolling() {
  clearInterval(timer.value)
  if (['RUNNING', 'WAITING'].includes(detail.value?.task.status))
    timer.value = setInterval(load, 1200)
}
function hydrateEditor() {
  const key = `${detail.value.task.id}:${detail.value.task.versionNo}`
  if (hydratedKey.value === key) return
  editor.province = parameters.value.province || ''
  editor.city = parameters.value.city || ''
  editor.budget = parameters.value.budget ?? null
  editor.questionsText = originalQuestions.value.join('\n')
  hydratedKey.value = key
}
async function load() {
  try {
    const next = await api.get(`/agent-tasks/${route.params.id}`)
    detail.value = next
    loadRouteMap()
    if (next.task.status === 'AWAITING_CONFIRMATION') hydrateEditor()
    if (!['RUNNING', 'WAITING'].includes(next.task.status)) clearInterval(timer.value)
  } catch {
    showError('任务读取失败')
  }
}
async function loadRouteMap() {
  const routes = evidence.value.routes || []
  const key = `${detail.value?.task.id || ''}:${detail.value?.task.evidenceUpdatedAt || ''}:${routes.length}`
  if (!routes.length || routeMapKey.value === key) return
  routeMapKey.value = key
  routeMapLoading.value = true
  routeMapUnavailable.value = false
  try {
    const blob = await api.blob(`/agent-tasks/${route.params.id}/route-map`)
    if (routeMapUrl.value) URL.revokeObjectURL(routeMapUrl.value)
    routeMapUrl.value = URL.createObjectURL(blob)
  } catch {
    routeMapUnavailable.value = true
  } finally {
    routeMapLoading.value = false
  }
}
function optimisticRun(approved) {
  detail.value.task.status = 'RUNNING'
  detail.value.task.currentStep = approved ? 6 : 1
  detail.value.steps.forEach((step) => {
    if (approved) {
      if (step.stepNo === 5)
        Object.assign(step, {
          status: 'COMPLETED',
          detail: '已确认问题清单，正在实时刷新动态检索类别。'
        })
      if (step.stepNo === 6)
        Object.assign(step, {
          status: 'RUNNING',
          detail: '正在提取补充问题关键词、刷新公开信息并逐项回答。'
        })
    } else {
      step.status = step.stepNo === 1 ? 'RUNNING' : 'PENDING'
      step.detail = step.stepNo === 1 ? '正在合并本轮修改与最初要求。' : null
      step.completedAt = null
    }
  })
}
async function confirmTask(approved) {
  if (submitting.value || (approved && canRevise.value) || (!approved && !canRevise.value)) return
  submitting.value = true
  error.value = ''
  const payload = {
    approved,
    note: '',
    province: approved ? null : editor.province,
    city: approved ? null : editor.city.trim(),
    budget: approved ? null : editor.budget === '' ? null : editor.budget,
    questions: enteredQuestions.value
  }
  optimisticRun(approved)
  startPolling()
  try {
    const stream = await streamSSE(`/agent-tasks/${route.params.id}/confirm`, payload, {
      step: load,
      revision: load,
      confirmation: load,
      done: load,
      error: (event) => showError(event.message || '任务执行失败'),
      close: async () => {
        await load()
        startPolling()
      },
      transportError: async () => {
        await load()
        startPolling()
      }
    })
    await stream.completed
  } catch (requestError) {
    await load()
    if (['RUNNING', 'SUCCEEDED'].includes(detail.value?.task.status)) return
    const message = requestError.response?.data?.message || requestError.message || '提交失败'
    if (!message.includes('任务当前不等待确认') && !message.includes('任务正在运行'))
      showError(message)
  } finally {
    submitting.value = false
  }
}
function phaseStatus(code) {
  const events = currentVersionEvents.value.filter((item) => item.phase === code)
  if (!events.length) return 'pending'
  const latest = events.at(-1)
  if (latest.eventType === 'ERROR') return 'failed'
  if (latest.status === 'RUNNING') return 'active'
  return 'done'
}
function phaseHint(code) {
  return (
    currentVersionEvents.value.filter((item) => item.phase === code).at(-1)?.title ||
    '等待前一步完成'
  )
}
function eventIcon(event) {
  return (
    { THOUGHT: '想', ACTION: '行', OBSERVATION: '观', RESULT: '果', WARNING: '!', ERROR: '×' }[
      event.eventType
    ] || '·'
  )
}
function formatDistance(meters) {
  return meters >= 1000 ? `${(meters / 1000).toFixed(1)} 公里` : `${meters} 米`
}
function effectiveRouteMode(route) {
  if (route.mode && route.mode !== 'WALKING') return route.mode
  if (route.distanceMeters <= 1800) return 'WALKING'
  if (route.distanceMeters <= 6000) return 'BICYCLING'
  return 'DRIVING'
}
function routeModeText(route) {
  return (
    { WALKING: '步行', BICYCLING: '骑行', TRANSIT: '地铁/公交', DRIVING: '驾车' }[
      effectiveRouteMode(route)
    ] || '出行'
  )
}
function routeNavigationUrl(route) {
  const mode = { WALKING: 'walk', BICYCLING: 'ride', TRANSIT: 'bus', DRIVING: 'car' }[
    effectiveRouteMode(route)
  ]
  return route.navigationUrl?.replace(/([?&]mode=)[^&]*/, `$1${mode}`) || '#'
}
function businessStatus(status) {
  return { OPEN: '营业中', CLOSED: '已打烊', UNKNOWN: '营业状态待核验' }[status] || '营业状态待核验'
}
function showError(message) {
  clearTimeout(errorTimer.value)
  error.value = message
  errorTimer.value = setTimeout(() => {
    error.value = ''
  }, 3200)
}
async function generatePdf() {
  pdfGenerating.value = true
  try {
    detail.value.pdfFile = await api.post(`/agent-tasks/${route.params.id}/pdf`)
  } catch (requestError) {
    showError(requestError.response?.data?.message || 'PDF 生成失败，请稍后重试')
  } finally {
    pdfGenerating.value = false
  }
}
async function downloadPdf() {
  pdfDownloading.value = true
  try {
    await api.download(
      `/agent-tasks/${route.params.id}/pdf`,
      `行动报告-${detail.value.task.title}.pdf`
    )
  } catch (requestError) {
    showError(requestError.response?.data?.message || 'PDF 下载失败，请稍后重试')
  } finally {
    pdfDownloading.value = false
  }
}
async function cancel() {
  if (!window.confirm('确认取消这个任务？')) return
  await api.post(`/agent-tasks/${route.params.id}/cancel`)
  await load()
}
async function removeTask() {
  if (!window.confirm('删除该行程记录、执行步骤和已生成的 PDF？此操作不可恢复。')) return
  deleting.value = true
  try {
    await api.delete(`/agent-tasks/${route.params.id}`)
    router.push('/plans')
  } catch (requestError) {
    showError(requestError.response?.data?.message || '删除失败')
  } finally {
    deleting.value = false
  }
}
function fileSize(bytes) {
  return bytes >= 1048576
    ? `${(bytes / 1048576).toFixed(1)} MB`
    : `${Math.max(1, Math.ceil(bytes / 1024))} KB`
}
function statusText(status) {
  return (
    {
      WAITING: '准备执行',
      RUNNING: '执行中',
      RETRY_WAIT: '等待重试',
      AWAITING_CONFIRMATION: '等待确认',
      SUCCEEDED: '已完成',
      FAILED: '执行失败',
      CANCELLED: '已取消'
    }[status] || status
  )
}
function statusClass(status) {
  return status === 'SUCCEEDED' ? 'green' : status === 'AWAITING_CONFIRMATION' ? 'coral' : ''
}
function stepHint(step) {
  return step.status === 'RUNNING'
    ? '正在执行…'
    : step.status === 'WAITING_CONFIRMATION'
      ? '等待你的确认'
      : '等待前一步完成'
}
function date(value) {
  return new Date(value).toLocaleString('zh-CN')
}
function time(value) {
  return new Date(value).toLocaleTimeString('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit'
  })
}
</script>

<style scoped>
.agent-observer {
  padding: 28px;
  margin-bottom: 20px;
  background: linear-gradient(145deg, #fffefa 0%, #f5f8f4 100%);
}
.observer-head {
  display: flex;
  justify-content: space-between;
  gap: 24px;
  align-items: flex-start;
}
.observer-head h2 {
  margin: 7px 0 6px;
  font-size: 22px;
}
.observer-head p {
  max-width: 680px;
  margin: 0;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.65;
}
.capability-badges {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  justify-content: flex-end;
}
.capability {
  padding: 7px 10px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 700;
  white-space: nowrap;
}
.capability.online {
  color: #276143;
  background: #e3f1e8;
}
.capability.offline {
  color: #8a6541;
  background: #f5ead9;
}
.phase-flow {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 25px 0;
}
.phase-flow > i {
  color: #bbb7ae;
  font-style: normal;
}
.phase-card {
  display: flex;
  align-items: center;
  gap: 9px;
  min-width: 0;
  flex: 1;
  padding: 12px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: #fff;
}
.phase-card > span {
  display: grid;
  place-items: center;
  flex: 0 0 27px;
  height: 27px;
  border-radius: 50%;
  background: #eeece6;
  color: #8f8b82;
  font-size: 11px;
  font-weight: 700;
}
.phase-card div {
  display: grid;
  min-width: 0;
}
.phase-card b {
  font-size: 12px;
  white-space: nowrap;
}
.phase-card small {
  margin-top: 3px;
  overflow: hidden;
  color: var(--muted);
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.phase-card.done {
  border-color: #cfe2d5;
  background: #f5faf6;
}
.phase-card.done > span {
  color: #fff;
  background: var(--green);
}
.phase-card.active {
  border-color: #efb8ad;
  background: #fff6f3;
}
.phase-card.active > span {
  color: #fff;
  background: var(--coral);
  animation: pulse 1.3s infinite;
}
.phase-card.failed {
  border-color: #e6b5ad;
  background: #fff3f1;
}
.phase-card.failed > span {
  color: #fff;
  background: #b44c3f;
}
.event-stream {
  padding: 18px;
  border: 1px solid #e5e2da;
  border-radius: 15px;
  background: rgba(255, 255, 255, 0.72);
}
.stream-title {
  display: flex;
  align-items: center;
  margin-bottom: 10px;
}
.stream-title b {
  font-size: 14px;
}
.stream-title small {
  margin-left: auto;
  color: var(--muted);
  font-size: 11px;
}
.stream-empty {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px;
  color: var(--muted);
  font-size: 12px;
}
.trace-branch {
  position: relative;
  border-top: 1px solid var(--line);
}
.branch-row {
  position: relative;
  width: 100%;
  display: grid;
  grid-template-columns: 4px 30px minmax(180px, 1fr) minmax(150px, 0.8fr) auto 46px;
  align-items: center;
  gap: 10px;
  padding: 13px 0;
  border: 0;
  background: transparent;
  color: inherit;
  text-align: left;
  cursor: pointer;
}
.branch-row:hover .branch-main b {
  color: var(--coral);
}
.branch-line {
  align-self: stretch;
  border-radius: 3px;
  background: #d8ddd9;
}
.trace-branch:last-child .branch-line {
  background: var(--green);
}
.branch-index {
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  border-radius: 9px;
  color: #fff;
  background: #69746c;
  font-size: 11px;
  font-weight: 800;
}
.branch-main {
  display: grid;
  min-width: 0;
}
.branch-main b {
  font-size: 13px;
  transition: 0.2s;
}
.branch-main small,
.branch-keywords {
  margin-top: 3px;
  overflow: hidden;
  color: var(--muted);
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.branch-status {
  padding: 4px 8px;
  border-radius: 999px;
  color: #5c665f;
  background: #edf0ee;
  font-size: 10px;
  font-weight: 700;
  white-space: nowrap;
}
.branch-status.active {
  color: #9b493d;
  background: #fff0ec;
}
.branch-status.waiting {
  color: #8b6134;
  background: #f8ecd8;
}
.branch-status.done {
  color: #2f6646;
  background: #e7f2ea;
}
.branch-status.failed {
  color: #9f4034;
  background: #f9e6e2;
}
.branch-status.revised {
  color: #716e67;
  background: #f0eee9;
}
.branch-toggle {
  color: #8d8981;
  font-size: 10px;
  white-space: nowrap;
}
.branch-events {
  padding: 0 0 6px 44px;
}
.event-card {
  display: grid;
  grid-template-columns: 31px 1fr;
  gap: 11px;
  padding: 13px 0;
  border-top: 1px solid var(--line);
}
.event-icon {
  display: grid;
  place-items: center;
  width: 29px;
  height: 29px;
  border-radius: 9px;
  color: #fff;
  background: #6d766f;
  font-size: 11px;
  font-weight: 800;
}
.event-card.action .event-icon {
  background: var(--coral);
}
.event-card.observation .event-icon {
  background: #52806a;
}
.event-card.result .event-icon {
  background: var(--green);
}
.event-card.warning .event-icon {
  color: #7b582e;
  background: #f1d9ab;
}
.event-card.error .event-icon {
  background: #ad4639;
}
.event-body > div {
  display: flex;
  gap: 12px;
  align-items: center;
}
.event-body b {
  font-size: 13px;
}
.event-body time {
  margin-left: auto;
  color: #aaa69d;
  font-size: 10px;
}
.event-body p {
  margin: 5px 0;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.55;
  white-space: pre-wrap;
}
.event-body footer {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}
.event-body footer span,
.event-body footer a {
  padding: 3px 7px;
  border-radius: 6px;
  color: #77736b;
  background: #f2f0ea;
  font-size: 10px;
}
.event-body footer a {
  color: var(--coral);
}
.evidence-panel {
  padding: 30px;
  margin-top: 20px;
}
.evidence-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
}
.evidence-head h2 {
  margin: 7px 0 0;
}
.evidence-head > small {
  color: var(--muted);
  font-size: 11px;
}
.evidence-empty {
  padding: 18px;
  margin-top: 18px;
  border: 1px dashed #d9c6a7;
  border-radius: 12px;
  color: #806a48;
  background: #fff9ee;
  font-size: 12px;
  line-height: 1.6;
}
.evidence-empty code {
  padding: 2px 5px;
  border-radius: 4px;
  background: #f3eadb;
}
.place-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(210px, 1fr));
  gap: 12px;
  margin-top: 20px;
}
.place-grid article {
  position: relative;
  padding: 18px;
  border: 1px solid var(--line);
  border-radius: 14px;
  background: #fffefa;
}
.map-card > img {
  width: calc(100% + 36px);
  height: 130px;
  margin: -18px -18px 14px;
  border-radius: 14px 14px 0 0;
  object-fit: cover;
}
.place-live-meta {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  margin: 9px 0;
}
.place-live-meta span {
  padding: 3px 7px;
  border-radius: 999px;
  background: #f2f0ea;
  color: #6f6b63;
  font-size: 10px;
}
.place-live-meta .business-open {
  color: #356247;
  background: #e7f3ea;
}
.place-live-meta .business-closed {
  color: #9c483a;
  background: var(--coral-soft);
}
.card-route {
  padding: 8px 10px;
  margin-top: 11px;
  border-radius: 8px;
  color: #4c6856;
  background: #edf4ef;
  font-size: 10px;
}
.route-map-overview {
  overflow: hidden;
  margin: 14px 0 8px;
  border: 1px solid var(--line);
  border-radius: 14px;
  background: #f6f5f0;
}
.route-map-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 13px 16px;
  background: #fffefa;
}
.route-map-title > div {
  display: grid;
  gap: 3px;
}
.route-map-title b {
  font-size: 13px;
}
.route-map-title small {
  color: var(--muted);
  font-size: 10px;
}
.route-map-overview > img {
  display: block;
  width: 100%;
  max-height: 460px;
  object-fit: contain;
}
.route-map-placeholder {
  display: grid;
  place-items: center;
  min-height: 220px;
  color: var(--muted);
  font-size: 12px;
}
.place-index {
  display: inline-block;
  padding: 3px 7px;
  border-radius: 5px;
  color: #366247;
  background: #e9f3ec;
  font-size: 10px;
  font-weight: 700;
}
.place-grid h3 {
  margin: 10px 0 6px;
  font-size: 15px;
}
.place-grid p {
  min-height: 36px;
  margin: 0;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.5;
}
.place-grid small {
  display: block;
  margin: 8px 0;
  color: #9b978e;
  font-size: 10px;
}
.place-grid a,
.route-list a {
  color: var(--coral);
  font-size: 11px;
}
.route-list {
  margin-top: 24px;
}
.route-list > h3 {
  font-size: 16px;
}
.route-list article {
  display: flex;
  align-items: center;
  gap: 20px;
  padding: 16px 0;
  border-top: 1px solid var(--line);
}
.route-points {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  flex: 1;
}
.route-points b {
  font-size: 13px;
}
.route-points span {
  padding: 4px 8px;
  border-radius: 999px;
  color: #51705e;
  background: #edf4ef;
  font-size: 10px;
}
.route-stats {
  display: flex;
  align-items: center;
  gap: 14px;
}
.route-stats strong {
  font-size: 12px;
}
.evidence-notice {
  padding: 12px 14px;
  margin: 18px 0 0;
  border-radius: 10px;
  color: #716b60;
  background: #f4f1e9;
  font-size: 11px;
  line-height: 1.5;
}
.back {
  display: block;
  margin-bottom: 12px;
  color: var(--muted);
  font-size: 13px;
}
.head-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}
.detail-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 20px;
}
.summary-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 20px;
  margin-bottom: 20px;
}
.summary-grid h3 {
  margin: 0 0 17px;
  font-size: 16px;
}
.tool-items {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  column-gap: 24px;
}
.steps-panel {
  padding: 28px;
}
.steps-panel > header {
  display: flex;
  align-items: center;
  padding-bottom: 20px;
  border-bottom: 1px solid var(--line);
}
.steps-panel > header div {
  display: grid;
}
.steps-panel > header b {
  font-size: 17px;
}
.steps-panel > header small {
  margin-top: 3px;
  color: var(--muted);
  font-size: 12px;
}
.steps-panel > header > span {
  margin-left: auto;
  font-size: 14px;
}
.timeline {
  padding: 20px 0;
}
.timeline article {
  position: relative;
  display: grid;
  grid-template-columns: 36px 1fr;
  gap: 14px;
  min-height: 84px;
}
.timeline article:not(:last-child):before {
  content: '';
  position: absolute;
  left: 17px;
  top: 34px;
  bottom: 0;
  width: 1px;
  background: var(--line);
}
.dot {
  position: relative;
  z-index: 1;
  display: grid;
  place-items: center;
  width: 35px;
  height: 35px;
  border: 1px solid var(--line);
  border-radius: 50%;
  background: #f8f6f1;
  color: #98958d;
  font-size: 12px;
}
.completed .dot {
  color: white;
  background: var(--green);
  border-color: var(--green);
}
.running .dot {
  color: white;
  background: var(--coral);
  border-color: var(--coral);
  animation: pulse 1.3s infinite;
}
.waiting_confirmation .dot {
  color: #a74738;
  background: var(--coral-soft);
  border-color: #f1c8bf;
}
.timeline b {
  font-size: 14px;
}
.timeline p {
  margin: 6px 0;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
}
.timeline small {
  color: #aaa79f;
  font-size: 11px;
}
.running-box {
  padding: 18px 20px;
  margin-top: 2px;
  border: 1px solid #d9e6dd;
  border-radius: 14px;
  background: #f1f7f3;
  display: flex;
  align-items: center;
  gap: 14px;
}
.running-box b {
  font-size: 15px;
}
.running-box p {
  margin: 4px 0 0;
  color: var(--muted);
  font-size: 12px;
}
.spinner {
  width: 24px;
  height: 24px;
  border: 3px solid #c8ddce;
  border-top-color: var(--green);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}
.confirm-box {
  padding: 22px;
  border: 1px solid #efc8bf;
  border-radius: 15px;
  background: #fdf0ed;
}
.confirm-box > span {
  color: #a84939;
  font-size: 12px;
  font-weight: 700;
}
.confirm-box h3 {
  margin: 8px 0 14px;
  font-size: 18px;
}
.preview {
  max-height: 420px;
  padding: 18px;
  overflow: auto;
  border: 1px solid #f0d8d1;
  border-radius: 12px;
  background: #fffefa;
}
.plan-editor {
  margin-top: 14px;
  padding: 18px;
  border: 1px solid #f0d8d1;
  border-radius: 12px;
  background: #fffaf7;
  display: grid;
  gap: 14px;
}
.editor-title {
  display: grid;
}
.editor-title small {
  margin-top: 3px;
  color: var(--muted);
  font-size: 12px;
}
.plan-editor .textarea {
  background: white;
}
.questions {
  min-height: 100px;
}
.field label small {
  margin-left: 5px;
  color: var(--muted);
  font-weight: 400;
}
.confirm-actions {
  margin-top: 14px;
  display: flex;
  justify-content: flex-end;
  gap: 9px;
  flex-wrap: wrap;
}
aside {
  display: grid;
  align-content: start;
  gap: 18px;
}
aside h3 {
  margin: 0 0 17px;
  font-size: 16px;
}
.task-info {
  display: grid;
  gap: 13px;
}
.task-info div {
  display: flex;
}
.task-info dt {
  color: var(--muted);
  font-size: 12px;
}
.task-info dd {
  margin-left: auto;
  font-size: 12px;
  font-weight: 600;
}
.tool-log article {
  padding: 11px 0;
  display: flex;
  gap: 10px;
  border-top: 1px solid var(--line);
}
.tool-log article > span {
  color: var(--coral);
}
.tool-log b,
.tool-log small {
  display: block;
  font-size: 12px;
}
.tool-log small,
.muted {
  margin-top: 3px;
  color: var(--muted);
  font-size: 11px;
}
.result {
  margin-top: 20px;
  padding: 30px;
}
.result h2 {
  margin: 9px 0 20px;
}
.result-actions {
  margin-top: 24px;
  display: flex;
  align-items: center;
  gap: 14px;
}
.result-actions span {
  color: var(--muted);
  font-size: 12px;
}
.toast-enter-active,
.toast-leave-active {
  transition: 0.22s;
}
.toast-enter-from,
.toast-leave-to {
  opacity: 0;
  transform: translateY(8px);
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
@keyframes pulse {
  50% {
    box-shadow: 0 0 0 6px rgba(239, 106, 84, 0.12);
  }
}
@media (max-width: 850px) {
  .detail-grid {
    grid-template-columns: 1fr;
  }
  aside {
    grid-template-columns: 1fr 1fr;
  }
}
@media (max-width: 550px) {
  aside {
    grid-template-columns: 1fr;
  }
  .result-actions {
    align-items: flex-start;
    flex-direction: column;
  }
  .grid-2 {
    grid-template-columns: 1fr;
  }
}
@media (max-width: 900px) {
  .phase-flow {
    display: grid;
    grid-template-columns: 1fr 1fr;
  }
  .phase-flow > i {
    display: none;
  }
  .observer-head {
    flex-direction: column;
  }
  .capability-badges {
    justify-content: flex-start;
  }
  .route-list article {
    align-items: flex-start;
    flex-direction: column;
  }
  .route-stats {
    flex-wrap: wrap;
  }
}
@media (max-width: 700px) {
  .branch-row {
    grid-template-columns: 4px 30px 1fr auto;
  }
  .branch-keywords {
    grid-column: 3;
  }
  .branch-status {
    grid-row: 1;
    grid-column: 4;
  }
  .branch-toggle {
    grid-row: 2;
    grid-column: 4;
  }
  .branch-events {
    padding-left: 20px;
  }
}
@media (max-width: 550px) {
  .phase-flow {
    grid-template-columns: 1fr;
  }
  .agent-observer,
  .evidence-panel {
    padding: 20px;
  }
  .stream-title {
    align-items: flex-start;
    flex-direction: column;
    gap: 4px;
  }
  .stream-title small {
    margin-left: 0;
  }
  .event-body > div {
    align-items: flex-start;
    flex-direction: column;
    gap: 3px;
  }
  .event-body time {
    margin-left: 0;
  }
  .evidence-head {
    align-items: flex-start;
    flex-direction: column;
  }
  .route-points {
    align-items: flex-start;
    flex-direction: column;
  }
  .place-grid {
    grid-template-columns: 1fr;
  }
}
/* Task detail carries dense operational data, so captions never drop below 12px. */
.agent-observer {
  padding: 32px;
}
.observer-head h2 {
  font-size: 25px;
}
.observer-head p {
  max-width: 820px;
  font-size: 15px;
  line-height: 1.75;
}
.capability {
  padding: 8px 11px;
  font-size: 12px;
}
.phase-card {
  padding: 14px;
}
.phase-card > span {
  flex-basis: 30px;
  height: 30px;
  font-size: 12px;
}
.phase-card b {
  font-size: 14px;
}
.phase-card small {
  font-size: 12px;
}
.event-stream {
  padding: 22px;
}
.stream-title b {
  font-size: 16px;
}
.stream-title small,
.stream-empty {
  font-size: 13px;
}
.branch-row {
  grid-template-columns: 4px 34px minmax(220px, 1.2fr) minmax(180px, 0.8fr) auto 52px;
  padding: 15px 0;
}
.branch-index {
  width: 31px;
  height: 31px;
  font-size: 12px;
}
.branch-main b {
  font-size: 15px;
}
.branch-main small,
.branch-keywords {
  font-size: 12px;
}
.branch-status,
.branch-toggle {
  font-size: 12px;
}
.event-card {
  grid-template-columns: 35px 1fr;
  gap: 12px;
  padding: 15px 0;
}
.event-icon {
  width: 33px;
  height: 33px;
  font-size: 12px;
}
.event-body b {
  font-size: 14px;
}
.event-body time {
  font-size: 12px;
}
.event-body p {
  font-size: 13px;
  line-height: 1.7;
  overflow-wrap: anywhere;
  word-break: break-word;
}
.event-body footer span,
.event-body footer a {
  padding: 4px 8px;
  font-size: 12px;
}
.evidence-panel {
  padding: 32px;
}
.evidence-head > small {
  font-size: 12px;
}
.evidence-empty {
  font-size: 14px;
}
.place-grid {
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 14px;
}
.place-grid article {
  padding: 20px;
}
.place-index {
  font-size: 12px;
}
.place-grid h3 {
  font-size: 17px;
}
.place-grid p {
  font-size: 14px;
}
.place-grid small {
  font-size: 12px;
}
.place-grid a,
.route-list a {
  font-size: 13px;
}
.route-list > h3 {
  font-size: 18px;
}
.route-points b {
  font-size: 14px;
}
.route-points span {
  font-size: 12px;
}
.route-stats strong {
  font-size: 14px;
}
.evidence-notice {
  font-size: 13px;
}
.back {
  font-size: 14px;
}
.detail-grid {
  grid-template-columns: minmax(0, 1fr);
  gap: 22px;
}
.steps-panel {
  padding: 30px;
}
.steps-panel > header b {
  font-size: 19px;
}
.steps-panel > header small {
  font-size: 13px;
}
.timeline b {
  font-size: 15px;
}
.timeline p {
  font-size: 14px;
  line-height: 1.7;
}
.timeline small {
  font-size: 12px;
}
.running-box b {
  font-size: 16px;
}
.running-box p {
  font-size: 14px;
}
.confirm-box > span {
  font-size: 13px;
}
.editor-title small {
  font-size: 13px;
}
.summary-grid h3 {
  font-size: 18px;
}
.task-info {
  gap: 15px;
}
.task-info dt,
.task-info dd {
  font-size: 13px;
}
.tool-log b,
.tool-log small {
  font-size: 13px;
}
.tool-log small,
.muted {
  font-size: 12px;
}
.result-actions span {
  font-size: 13px;
}
.timeline .expandable-text {
  margin: 6px 0;
  color: var(--muted);
  font-size: 14px;
  line-height: 1.7;
}
.event-body .expandable-text {
  margin: 6px 0;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.7;
}
.event-body footer {
  margin-top: 8px;
}
@media (max-width: 850px) {
  .detail-grid {
    grid-template-columns: 1fr;
  }
  .summary-grid {
    grid-template-columns: 1fr;
  }
  .agent-observer,
  .evidence-panel,
  .steps-panel {
    padding: 24px;
  }
}
@media (max-width: 550px) {
  .tool-items {
    grid-template-columns: 1fr;
  }
}
/* Keep the final responsive overrides after the dense desktop typography rules above. */
@media (max-width: 700px) {
  :deep(*) {
    min-width: 0;
  }
  .observer-head,
  .evidence-head,
  .route-map-title,
  .stream-title,
  .running-box {
    align-items: flex-start;
    flex-direction: column;
  }
  .agent-observer,
  .evidence-panel,
  .steps-panel {
    padding: 18px;
  }
  .event-stream,
  .confirm-box,
  .plan-editor,
  .preview {
    padding: 14px;
  }
  .phase-flow,
  .summary-grid,
  .tool-items,
  .place-grid {
    grid-template-columns: minmax(0, 1fr);
  }
  .branch-row {
    grid-template-columns: 4px 31px minmax(0, 1fr) auto;
    gap: 8px;
  }
  .branch-keywords {
    grid-column: 3 / -1;
    white-space: normal;
    overflow-wrap: anywhere;
  }
  .branch-status {
    grid-row: 1;
    grid-column: 4;
    max-width: 96px;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .branch-toggle {
    grid-row: 2;
    grid-column: 4;
  }
  .branch-events {
    padding-left: 8px;
  }
  .event-card {
    grid-template-columns: 33px minmax(0, 1fr);
  }
  .event-body > div,
  .route-list article,
  .route-points,
  .route-stats,
  .result-actions,
  .confirm-actions {
    align-items: stretch;
    flex-direction: column;
  }
  .event-body time,
  .stream-title small {
    margin-left: 0;
  }
  .event-body p,
  .event-body footer,
  .timeline p,
  .route-points b,
  .route-stats small,
  .task-info dd {
    overflow-wrap: anywhere;
    word-break: break-word;
  }
  .confirm-actions .btn,
  .result-actions .btn {
    width: 100%;
    white-space: normal;
  }
  .route-map-overview > img {
    height: auto;
  }
  .toast {
    right: 14px;
    bottom: 14px;
    left: 14px;
  }
}
@media (max-width: 420px) {
  .agent-observer,
  .evidence-panel,
  .steps-panel,
  .summary-grid .panel-pad,
  .result {
    padding: 15px;
  }
  .phase-card b,
  .phase-card small {
    white-space: normal;
  }
  .task-info div {
    align-items: flex-start;
    gap: 12px;
  }
  .task-info dd {
    text-align: right;
  }
}
</style>
