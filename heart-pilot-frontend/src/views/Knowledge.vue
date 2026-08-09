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
        >{{ documents.filter((x) => x.status === 'READY').length }} 个可检索</span
      >
    </header>
    <div v-if="!documents.length" class="empty">
      <b>还没有知识文档</b>支持 Markdown、TXT、PDF 和 Word，单文件不超过 30 MB。
    </div>
    <div class="doc-table" v-else>
      <div class="table-head">
        <span>文档</span><span>分类</span><span>切片</span><span>状态</span><span>上传时间</span
        ><span></span>
      </div>
      <article v-for="d in documents" :key="d.id">
        <span class="doc-name"
          ><i>{{ extension(d.originalName) }}</i
          ><b>{{ d.originalName }}</b
          ><small>{{ size(d.sizeBytes) }}</small></span
        ><span>{{ d.category }}</span
        ><span>{{ d.chunkCount }}</span
        ><span
          ><em
            class="badge"
            :class="d.status === 'READY' ? 'green' : d.status === 'FAILED' ? 'coral' : ''"
            >{{ status(d.status) }}</em
          ></span
        ><span>{{ date(d.createdAt) }}</span
        ><button @click="remove(d)">删除</button>
      </article>
    </div>
  </section>
  <aside class="knowledge-note">
    <span>关于内容安全</span>
    <p>
      建议只上传来源清晰、允许使用的材料；不要上传包含真实个人身份、医疗记录或未经授权的私密对话。
    </p>
  </aside>
  <div v-if="toast" class="toast">{{ toast }}</div>
</template>
<script setup>
import { ref, onMounted } from 'vue'
import { api } from '../api'
const documents = ref([]),
  uploading = ref(false),
  toast = ref(''),
  flow = ['文本解析', '内容清洗', '分段切片', '关键词补充', 'Embedding', 'PGVector']
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
    await api.post('/admin/knowledge/documents', form)
    toast.value = '文档处理完成'
    await load()
  } catch (err) {
    toast.value = err.response?.data?.message || '上传失败'
  } finally {
    uploading.value = false
    e.target.value = ''
    setTimeout(() => (toast.value = ''), 2600)
  }
}
async function remove(d) {
  if (!confirm(`删除《${d.originalName}》及其全部切片？`)) return
  await api.delete(`/admin/knowledge/documents/${d.id}`)
  load()
}
function extension(n) {
  return n.split('.').pop().toUpperCase()
}
function size(n) {
  return n > 1048576 ? (n / 1048576).toFixed(1) + ' MB' : Math.ceil(n / 1024) + ' KB'
}
function status(s) {
  return { UPLOADED: '待处理', PROCESSING: '处理中', READY: '可检索', FAILED: '失败' }[s] || s
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
  min-width: 860px;
  display: grid;
  grid-template-columns: 2.4fr 0.8fr 0.5fr 0.7fr 0.8fr 0.4fr;
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
.doc-table article > button {
  min-height: 34px;
  border: 0;
  background: transparent;
  color: #af4b3e;
  font-size: 13px;
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
    min-width: 780px;
  }
}
</style>
