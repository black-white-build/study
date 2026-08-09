<template>
  <div class="page-head">
    <div>
      <h1>关系报告</h1>
      <p>咨询分析与每周成长复盘集中保存，并可继续转为 7 天行动。</p>
    </div>
    <router-link to="/consult" class="btn primary">从咨询生成报告</router-link>
  </div>
  <div v-if="loading" class="panel empty">正在读取报告…</div>
  <div v-else-if="!reports.length" class="panel empty">
    <b>还没有关系报告</b>在一段咨询后生成结构化分析，或在成长计划中生成本周复盘。<br /><router-link
      to="/consult"
      class="btn"
      style="margin-top: 18px"
      >去咨询</router-link
    >
  </div>
  <div v-else class="reports-grid">
    <article v-for="r in reports" :key="r.id" class="report-card panel">
      <header>
        <span class="badge" :class="riskClass(r.riskLevel)">{{
          r.reportType === 'WEEKLY_REVIEW' ? '每周复盘' : `${r.riskLevel}风险`
        }}</span
        ><small>{{ date(r.createdAt) }}</small>
      </header>
      <h2>{{ r.title }}</h2>
      <p>{{ r.problemSummary }}</p>
      <dl>
        <div>
          <dt>关系状态</dt>
          <dd>{{ r.relationshipStatus || '待分析' }}</dd>
        </div>
        <div>
          <dt>分析类型</dt>
          <dd>{{ r.conflictType || '成长复盘' }}</dd>
        </div>
      </dl>
      <div class="analysis">{{ r.analysis }}</div>
      <footer>
        <span>建议复盘：{{ date(r.reviewAt) }}</span>
        <div class="actions">
          <button class="text-btn" :disabled="working === r.id" @click="toPlan(r)">
            转为 7 天计划</button
          ><button class="btn" @click="download(r)">下载 PDF ↓</button
          ><button
            class="icon-delete"
            title="删除报告"
            :disabled="working === r.id"
            @click="remove(r)"
          >
            删除
          </button>
        </div>
      </footer>
    </article>
  </div>
  <div v-if="toast" class="toast">{{ toast }}</div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { api } from '../api'
const reports = ref([]),
  loading = ref(true),
  toast = ref(''),
  working = ref(null)
onMounted(load)
async function load() {
  try {
    const page = await api.get('/reports')
    reports.value = page.content
  } catch (e) {
    show(e.response?.data?.message || '读取失败')
  } finally {
    loading.value = false
  }
}
function date(v) {
  return v
    ? new Date(v).toLocaleDateString('zh-CN', { year: 'numeric', month: 'short', day: 'numeric' })
    : '—'
}
function riskClass(v) {
  return v === '高' || v === '紧急' ? 'coral' : v === '低' ? 'green' : ''
}
async function download(r) {
  try {
    await api.download(`/reports/${r.id}/pdf`, `关系报告-${r.id}.pdf`)
  } catch {
    show('PDF 下载失败')
  }
}
async function toPlan(r) {
  working.value = r.id
  try {
    await api.post(`/growth/plans/from-report/${r.id}`)
    show('已把报告行动项转为 7 天计划，可前往成长计划打卡')
  } catch (e) {
    show(e.response?.data?.message || '创建计划失败')
  } finally {
    working.value = null
  }
}
async function remove(r) {
  if (!confirm(`确定删除「${r.title}」及其 PDF 文件吗？此操作不可恢复。`)) return
  working.value = r.id
  try {
    await api.delete(`/reports/${r.id}`)
    reports.value = reports.value.filter((item) => item.id !== r.id)
    show('报告已删除')
  } catch (e) {
    show(e.response?.data?.message || '删除失败')
  } finally {
    working.value = null
  }
}
function show(message) {
  toast.value = message
  setTimeout(() => (toast.value = ''), 3000)
}
</script>

<style scoped>
.reports-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 22px;
}
.report-card {
  padding: 30px;
}
.report-card header,
.report-card footer {
  display: flex;
  align-items: center;
}
.report-card header small {
  margin-left: auto;
  color: var(--muted);
  font-size: 12px;
}
.report-card h2 {
  margin: 22px 0 10px;
  font-size: 22px;
}
.report-card > p {
  min-height: 48px;
  margin: 0;
  color: var(--muted);
  font-size: 15px;
  line-height: 1.75;
}
.report-card dl {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  margin: 22px 0;
}
.report-card dl div {
  padding: 14px;
  border-radius: 10px;
  background: #f5f3ed;
}
.report-card dt {
  color: #87847c;
  font-size: 12px;
}
.report-card dd {
  margin: 5px 0 0;
  font-size: 14px;
  font-weight: 600;
}
.analysis {
  height: 102px;
  overflow: hidden;
  font-size: 15px;
  line-height: 1.8;
}
.report-card footer {
  gap: 14px;
  margin-top: 24px;
  padding-top: 18px;
  border-top: 1px solid var(--line);
}
.report-card footer > span {
  color: var(--muted);
  font-size: 12px;
}
.actions {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 8px;
}
.actions .btn {
  padding: 8px 12px;
  font-size: 13px;
}
.text-btn,
.icon-delete {
  min-height: 36px;
  border: 0;
  background: transparent;
  font-size: 13px;
}
.text-btn {
  color: #9d493b;
}
.icon-delete {
  color: #a55a4f;
}
.text-btn:disabled,
.icon-delete:disabled {
  opacity: 0.4;
}
@media (max-width: 980px) {
  .report-card footer {
    align-items: flex-start;
    flex-direction: column;
  }
  .actions {
    margin-left: 0;
    flex-wrap: wrap;
  }
}
@media (max-width: 700px) {
  .reports-grid {
    grid-template-columns: 1fr;
  }
  .report-card {
    padding: 22px;
  }
}
</style>
