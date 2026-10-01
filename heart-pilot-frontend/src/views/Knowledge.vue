<template>
  <div class="page-head">
    <div>
      <h1>知识库管理</h1>
      <p>上传可信资料，经过解析、清洗、切片和向量化后用于 RAG 检索。</p>
    </div>
    <label class="btn coral upload"
      >{{ uploading ? '处理中…' : '↑ 上传文档'
      }}<input type="file" accept=".md,.txt,.pdf,.doc,.docx" :disabled="uploading" @change="upload"
    /></label>
  </div>
  <section class="panel metadata-form">
    <div class="field">
      <label>分类</label
      ><select v-model="metadata.category" class="select">
        <option v-for="item in categories" :key="item">{{ item }}</option>
      </select>
    </div>
    <div class="field">
      <label>来源（可选）</label
      ><input
        v-model="metadata.sourceName"
        class="input"
        placeholder="例如：马歇尔·卢森堡《非暴力沟通》"
      />
    </div>
  </section>
  <section class="pipeline panel">
    <span v-for="(s, i) in flow" :key="s"
      ><b>{{ i + 1 }}</b
      >{{ s }}<i v-if="i < flow.length - 1">→</i></span
    >
  </section>
  <section class="panel docs">
    <header>
      <div>
        <h2>知识文档</h2>
        <p>仅管理员可见；用户回答只展示实际采用的来源。</p>
      </div>
      <span class="badge green"
        >{{
          documents.filter((x) => x.status === 'READY' && x.reviewStatus === 'APPROVED').length
        }}
        个可检索</span
      >
    </header>
    <div v-if="!documents.length" class="empty">
      <b>还没有知识文档</b>支持 Markdown、TXT、PDF 和 Word，单文件不超过 30 MB。
    </div>
    <div class="doc-table" v-else>
      <div class="table-head">
        <span>文档</span><span>分类与来源</span><span>切片</span><span>审核 / 状态</span
        ><span>更新时间</span><span></span>
      </div>
      <article v-for="d in documents" :key="d.id">
        <span class="doc-name"
          ><i>{{ extension(d.originalName) }}</i
          ><b>{{ d.originalName }}</b
          ><small>{{ size(d.sizeBytes) }}</small></span
        ><span
          ><b>{{ d.category }}</b
          ><small class="meta-line"
            >{{ d.sourceName || '来源未标注' }} · v{{ d.contentVersion }}</small
          ></span
        ><span>{{ d.chunkCount }}</span
        ><span
          ><em
            class="badge"
            :class="
              d.status === 'READY' && d.reviewStatus === 'APPROVED'
                ? 'green'
                : d.status === 'FAILED' || d.reviewStatus === 'REJECTED'
                  ? 'coral'
                  : ''
            "
            >{{ reviewStatus(d.reviewStatus) }} / {{ status(d.status) }}</em
          ></span
        ><span>{{ date(d.updatedAt) }}</span
        ><span class="doc-actions"
          ><button @click="viewContent(d)">查看内容</button
          ><button
            v-if="d.reviewStatus !== 'APPROVED'"
            class="approve"
            :disabled="d.status !== 'READY' || approvingId === d.id"
            @click="approve(d)"
          >
            {{ approvingId === d.id ? '通过中…' : '通过' }}</button
          ><button class="remove" @click="remove(d)">删除</button></span
        >
      </article>
    </div>
  </section>
  <aside class="knowledge-note">
    <span>关于内容安全</span>
    <p>
      建议只上传来源清晰、允许使用的材料；不要上传包含真实个人身份、医疗记录或未经授权的私密对话。
    </p>
  </aside>
  <div v-if="contentDialog.open" class="content-modal" @click.self="closeContent">
    <section class="content-dialog" role="dialog" aria-modal="true" aria-label="文档内容">
      <header>
        <div>
          <h2>{{ contentDialog.name }}</h2>
          <p>按切片顺序拼接的纯文本，共 {{ contentDialog.chunkCount }} 个切片。</p>
        </div>
        <button aria-label="关闭" @click="closeContent">×</button>
      </header>
      <div v-if="contentDialog.loading" class="content-loading">内容加载中…</div>
      <pre v-else>{{ contentDialog.content || '暂无可查看的切片内容。' }}</pre>
    </section>
  </div>
  <div v-if="toast" class="toast">{{ toast }}</div>
</template>
<script setup>
import { reactive, ref, onMounted } from 'vue'
import { api } from '../api'
const documents = ref([]),
  uploading = ref(false),
  approvingId = ref(null),
  toast = ref(''),
  flow = ['文本解析', '内容清洗', '分段切片', '关键词补充', 'Embedding', 'PGVector']
const categories = [
  '沟通基础',
  '冲突与修复',
  '边界与同意',
  '关系阶段',
  '分手与结束关系',
  '数字沟通',
  '行动设计',
  '风险与安全'
]
// 表单项只暴露"分类"和"来源"两项；其余字段由前端写死默认值随 FormData 传给后端，
// 新上传文档固定进入待审核状态，管理员确认切片内容后再点“通过”进入 RAG 检索池。
const metadata = reactive({
  category: '沟通基础',
  applicableScenario: '通用沟通',
  relationshipStage: '通用',
  sourceName: '',
  sourceUrl: '',
  contentVersion: '1.0',
  reviewStatus: 'IN_REVIEW',
  riskTags: '',
  evidenceLevel: 'UNVERIFIED'
})
// 内容弹窗只展示后端返回的切片纯文本，不做富文本解析与文件下载。
const contentDialog = reactive({
  open: false,
  loading: false,
  name: '',
  chunkCount: 0,
  content: ''
})
onMounted(load)
async function load() {
  const page = await api.get('/admin/knowledge')
  documents.value = page.content
}
async function upload(e) {
  const file = e.target.files?.[0]
  if (!file) return
  uploading.value = true
  try {
    const form = new FormData()
    form.append('file', file)
    Object.entries(metadata).forEach(([key, value]) => form.append(key, value))
    await api.post('/admin/knowledge/documents', form)
    toast.value = '文档处理完成，等待审核'
    await load()
  } catch (err) {
    toast.value = err.response?.data?.message || '上传失败'
  } finally {
    uploading.value = false
    e.target.value = ''
    setTimeout(() => (toast.value = ''), 2600)
  }
}
async function viewContent(d) {
  contentDialog.open = true
  contentDialog.loading = true
  contentDialog.name = d.originalName
  contentDialog.chunkCount = d.chunkCount
  contentDialog.content = ''
  try {
    const result = await api.get(`/admin/knowledge/documents/${d.id}/content`)
    contentDialog.name = result.originalName
    contentDialog.chunkCount = result.chunkCount
    contentDialog.content = result.content
  } catch (err) {
    contentDialog.open = false
    showToast(err.response?.data?.message || '内容加载失败')
  } finally {
    contentDialog.loading = false
  }
}
function closeContent() {
  contentDialog.open = false
}
async function approve(d) {
  approvingId.value = d.id
  try {
    await api.patch(`/admin/knowledge/documents/${d.id}/approve`)
    showToast('审核已通过，文档现已进入检索池')
    await load()
  } catch (err) {
    showToast(err.response?.data?.message || '审核通过失败')
  } finally {
    approvingId.value = null
  }
}
async function remove(d) {
  if (!confirm(`删除《${d.originalName}》及其全部切片？`)) return
  try {
    await api.delete(`/admin/knowledge/documents/${d.id}`)
    showToast('文档已删除')
    await load()
  } catch (err) {
    showToast(err.response?.data?.message || '删除失败')
  }
}
function showToast(message) {
  toast.value = message
  setTimeout(() => (toast.value = ''), 2600)
}
function extension(n) {
  return n.split('.').pop().toUpperCase()
}
function size(n) {
  return n > 1048576 ? (n / 1048576).toFixed(1) + ' MB' : Math.ceil(n / 1024) + ' KB'
}
function status(s) {
  return { UPLOADED: '待处理', PROCESSING: '处理中', READY: '处理完成', FAILED: '失败' }[s] || s
}
function reviewStatus(s) {
  return { DRAFT: '草稿', IN_REVIEW: '待审核', APPROVED: '已审核', REJECTED: '已驳回' }[s] || s
}
function date(v) {
  return new Date(v).toLocaleDateString('zh-CN')
}
</script>
<style scoped>
.upload input {
  display: none;
}
.pipeline {
  padding: 20px 24px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  overflow: auto;
}
.metadata-form {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
  padding: 22px;
}
.metadata-form .wide {
  grid-column: span 2;
}
.meta-line {
  display: block;
  margin-top: 4px;
  color: var(--muted);
}
.pipeline span {
  display: flex;
  align-items: center;
  gap: 7px;
  white-space: nowrap;
  color: var(--muted);
  font-size: 13px;
}
.pipeline b {
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  border-radius: 7px;
  color: #9f4a3b;
  background: var(--coral-soft);
  font-size: 11px;
}
.pipeline i {
  margin-left: 5px;
  color: #bbb8af;
  font-style: normal;
}
.docs {
  margin-top: 22px;
  overflow: hidden;
}
.docs > header {
  padding: 24px 28px;
  display: flex;
  align-items: center;
  border-bottom: 1px solid var(--line);
}
.docs h2 {
  margin: 0;
  font-size: 19px;
}
.docs header p {
  margin: 5px 0 0;
  color: var(--muted);
  font-size: 13px;
}
.docs header > .badge {
  margin-left: auto;
}
.doc-table {
  overflow: auto;
}
.table-head,
.doc-table article {
  min-width: 1060px;
  display: grid;
  grid-template-columns: 2.2fr 0.9fr 0.45fr 0.75fr 0.75fr 1.2fr;
  align-items: center;
  gap: 14px;
}
.table-head {
  padding: 12px 28px;
  color: #858279;
  background: #f5f3ed;
  font-size: 12px;
  font-weight: 600;
}
.doc-table article {
  padding: 17px 28px;
  border-top: 1px solid var(--line);
  font-size: 13px;
}
.doc-name {
  display: grid;
  grid-template-columns: 44px 1fr;
  column-gap: 12px;
}
.doc-name i {
  grid-row: 1/3;
  display: grid;
  place-items: center;
  width: 42px;
  height: 44px;
  border-radius: 9px;
  color: #9c483a;
  background: var(--coral-soft);
  font-size: 11px;
  font-style: normal;
  font-weight: 700;
}
.doc-name b {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 14px;
}
.doc-name small {
  color: var(--muted);
  font-size: 12px;
}
.doc-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  white-space: nowrap;
}
.doc-actions button {
  min-height: 34px;
  border: 0;
  background: transparent;
  color: #5f655f;
  font-size: 13px;
  cursor: pointer;
}
.doc-actions .approve {
  color: #397257;
  font-weight: 700;
}
.doc-actions .remove {
  color: #af4b3e;
}
.doc-actions button:disabled {
  cursor: not-allowed;
  opacity: 0.45;
}
.content-modal {
  position: fixed;
  z-index: 50;
  inset: 0;
  display: grid;
  place-items: center;
  padding: 24px;
  background: rgba(31, 32, 29, 0.48);
}
.content-dialog {
  width: min(860px, 100%);
  max-height: min(760px, 90vh);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: 18px;
  background: #fff;
  box-shadow: 0 22px 60px rgba(24, 25, 22, 0.2);
}
.content-dialog header {
  display: flex;
  align-items: flex-start;
  gap: 20px;
  padding: 20px 24px;
  border-bottom: 1px solid var(--line);
}
.content-dialog h2 {
  margin: 0;
  font-size: 18px;
}
.content-dialog p {
  margin: 6px 0 0;
  color: var(--muted);
  font-size: 12px;
}
.content-dialog header button {
  margin-left: auto;
  border: 0;
  background: transparent;
  color: var(--muted);
  font-size: 25px;
  line-height: 1;
  cursor: pointer;
}
.content-dialog pre,
.content-loading {
  margin: 0;
  padding: 24px;
  overflow: auto;
  color: #343630;
  font:
    14px/1.85 ui-monospace,
    SFMono-Regular,
    Consolas,
    monospace;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.content-loading {
  color: var(--muted);
}
.knowledge-note {
  margin-top: 20px;
  padding: 18px 22px;
  border-left: 3px solid #8aa795;
  background: #edf2ec;
}
.knowledge-note span {
  font-size: 14px;
  font-weight: 700;
}
.knowledge-note p {
  margin: 6px 0 0;
  color: #686b65;
  font-size: 13px;
  line-height: 1.65;
}
@media (max-width: 620px) {
  .metadata-form {
    grid-template-columns: 1fr;
  }
  .metadata-form .wide {
    grid-column: auto;
  }
  .pipeline {
    justify-content: flex-start;
  }
  .docs > header {
    align-items: flex-start;
    gap: 12px;
    padding: 20px;
  }
  .docs header > .badge {
    margin-left: auto;
  }
  .table-head,
  .doc-table article {
    min-width: 1000px;
  }
}
</style>
