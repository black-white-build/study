<template>
  <div class="page-head">
    <div>
      <h1>消费成本</h1>
      <p>按消息追踪模型 Token、缓存命中与估算费用。</p>
    </div>
    <select v-model="days" class="period" @change="load">
      <option :value="7">近 7 天</option>
      <option :value="30">近 30 天</option>
      <option :value="90">近 90 天</option>
    </select>
  </div>
  <div v-if="dashboard" class="cost-page">
    <section class="metric-grid">
      <article class="panel">
        <span>估算费用</span><strong>¥ {{ cny(dashboard.totalCostMicros) }}</strong
        ><small>{{ dashboard.totalRequests }} 次模型结果</small>
      </article>
      <article class="panel">
        <span>缓存命中率</span><strong>{{ percent(dashboard.cacheHitRate) }}</strong
        ><small>{{ dashboard.cacheHits }} 次命中</small>
      </article>
      <article class="panel">
        <span>缓存节省</span><strong>¥ {{ cny(dashboard.cacheSavedCostMicros) }}</strong
        ><small>按同等 Token 价格估算</small>
      </article>
      <article class="panel">
        <span>平均响应耗时</span><strong>{{ dashboard.averageProviderLatencyMs }} ms</strong
        ><small>含 Redis 命中结果</small>
      </article>
    </section>
    <section class="panel chart-panel">
      <header>
        <div>
          <span class="eyebrow">每日消费</span>
          <h2>费用趋势</h2>
        </div>
        <small
          >输入 {{ number(dashboard.inputTokens) }} · 输出
          {{ number(dashboard.outputTokens) }} Tokens</small
        >
      </header>
      <div v-if="dashboard.daily.length" class="bars">
        <article v-for="item in dashboard.daily" :key="item.date">
          <div class="bar-track"><i :style="{ height: bar(item.costMicros) }"></i></div>
          <b>¥{{ cny(item.costMicros) }}</b
          ><small>{{ shortDate(item.date) }}</small>
        </article>
      </div>
      <div v-else class="empty">当前周期还没有模型消费记录。</div>
    </section>
    <section class="panel model-panel">
      <header>
        <div>
          <span class="eyebrow">模型明细</span>
          <h2>按模型汇总</h2>
        </div>
      </header>
      <div class="model-row head">
        <span>模型</span><span>请求</span><span>Token</span><span>缓存</span><span>费用</span>
      </div>
      <div v-for="model in dashboard.models" :key="model.model" class="model-row">
        <b>{{ model.model }}</b
        ><span>{{ model.requests }}</span
        ><span>{{ number(model.inputTokens + model.outputTokens) }}</span
        ><span>{{ model.cacheHits }}</span
        ><strong>¥ {{ cny(model.costMicros) }}</strong>
      </div>
    </section>
    <p class="cost-note">{{ dashboard.estimationNote }}</p>
  </div>
  <div v-else class="panel empty">正在读取成本数据…</div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { api } from '../api'
const days = ref(30)
const dashboard = ref()
const maxCost = computed(() =>
  Math.max(1, ...(dashboard.value?.daily || []).map((x) => x.costMicros))
)
onMounted(load)
async function load() {
  dashboard.value = await api.get('/usage/cost-dashboard', { params: { days: days.value } })
}
function cny(micros) {
  return ((micros || 0) / 1_000_000).toFixed(6)
}
function percent(value) {
  return `${((value || 0) * 100).toFixed(1)}%`
}
function number(value) {
  return new Intl.NumberFormat('zh-CN').format(value || 0)
}
function shortDate(value) {
  return value.slice(5)
}
function bar(value) {
  return `${Math.max(4, Math.round(((value || 0) / maxCost.value) * 130))}px`
}
</script>

<style scoped>
.period {
  min-height: 40px;
  padding: 0 14px;
  border: 1px solid var(--line);
  border-radius: 9px;
  background: #fffefa;
}
.metric-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
}
.metric-grid article {
  padding: 21px;
}
.metric-grid span,
.metric-grid small {
  color: var(--muted);
  font-size: 12px;
}
.metric-grid strong {
  display: block;
  margin: 9px 0 5px;
  color: #3f5547;
  font-size: 24px;
}
.chart-panel,
.model-panel {
  padding: 26px;
  margin-top: 18px;
}
.chart-panel header,
.model-panel header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
}
h2 {
  margin: 5px 0 0;
  font-size: 19px;
}
header small {
  color: var(--muted);
  font-size: 11px;
}
.bars {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  min-height: 190px;
  margin-top: 20px;
  overflow-x: auto;
}
.bars article {
  display: grid;
  justify-items: center;
  gap: 5px;
  min-width: 42px;
}
.bar-track {
  display: flex;
  align-items: flex-end;
  width: 22px;
  height: 135px;
  border-radius: 7px;
  background: #f2f0ea;
  overflow: hidden;
}
.bar-track i {
  width: 100%;
  border-radius: 7px;
  background: linear-gradient(#dc8978, #a95445);
}
.bars b {
  color: #665f56;
  font-size: 9px;
}
.bars small {
  color: var(--muted);
  font-size: 10px;
}
.model-row {
  display: grid;
  grid-template-columns: 2fr repeat(4, 1fr);
  gap: 12px;
  padding: 13px 5px;
  border-top: 1px solid var(--line);
  font-size: 12px;
}
.model-row.head {
  margin-top: 15px;
  color: var(--muted);
  font-size: 10px;
}
.model-row strong {
  color: var(--coral);
}
.cost-note {
  padding: 13px 16px;
  color: #6c685f;
  background: #f5f3ed;
  font-size: 11px;
  line-height: 1.6;
}
@media (max-width: 900px) {
  .metric-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}
@media (max-width: 560px) {
  .metric-grid {
    grid-template-columns: 1fr;
  }
  .model-row {
    min-width: 600px;
  }
  .model-panel {
    overflow-x: auto;
  }
}
</style>
