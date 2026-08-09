<template>
  <div class="consult-page">
    <aside class="conversation-panel panel">
      <div class="conv-head">
        <div>
          <b>咨询会话</b><small>{{ conversations.length }} 个记录</small>
        </div>
        <button class="new-chat" @click="createConversation">＋</button>
      </div>
      <div class="conv-list">
        <button
          v-for="item in conversations"
          :key="item.id"
          :class="{ active: item.id === activeId }"
          @click="selectConversation(item.id)"
        >
          <span>{{ item.title }}</span
          ><small>{{ relative(item.lastMessageAt) }}</small>
        </button>
        <div v-if="!conversations.length" class="empty small">还没有会话</div>
      </div>
      <div v-if="current" class="conv-actions">
        <button @click="renameConversation">重命名</button
        ><button class="danger" @click="removeConversation">删除</button>
      </div>
    </aside>
    <section class="chat-panel panel">
      <header class="chat-head">
        <div>
          <span class="advisor-avatar">旅</span>
          <div>
            <b>心旅关系顾问</b
            ><small
              ><i></i>知识库与关系档案已连接 · {{ current?.model || 'qwen-plus' }}
              <router-link to="/profile">查看档案</router-link></small
            >
          </div>
        </div>
        <button
          class="btn"
          :disabled="!messages.length || reportGenerating || reportCooldown > 0"
          @click="makeReport"
        >
          {{
            reportGenerating
              ? '正在生成报告…'
              : reportCooldown > 0
                ? `${reportCooldown}s 后可再次生成`
                : '生成关系报告'
          }}
        </button>
      </header>
      <div ref="scrollEl" class="messages">
        <div v-if="!messages.length" class="chat-welcome">
          <span>◌</span>
          <h2>今天，想从哪件事说起？</h2>
          <p>你可以讲事情经过，也可以只说此刻的感受。我们会先一起把问题说清，再寻找下一步。</p>
          <div class="prompts">
            <button v-for="p in prompts" :key="p" @click="draft = p">{{ p }}</button>
          </div>
        </div>
        <article v-for="m in messages" :key="m.id" class="message" :class="m.role.toLowerCase()">
          <div class="message-avatar">
            {{ m.role === 'USER' ? authState.user?.nickname?.slice(0, 1) || '我' : '旅' }}
          </div>
          <div class="message-body">
            <div class="message-meta">
              <b>{{ m.role === 'USER' ? '你' : '心旅顾问' }}</b
              ><small
                >{{ time(m.createdAt) }} · {{ m.outputTokens || m.inputTokens || 0 }} tokens</small
              >
            </div>
            <div class="message-content">
              <StructuredText v-if="m.role === 'ASSISTANT'" :content="m.content" /><template
                v-else
                >{{ m.content }}</template
              ><span v-if="m.status === 'STREAMING'" class="cursor"></span>
            </div>
            <div v-if="sources(m).length" class="sources">
              <b>参考知识</b
              ><span v-for="s in sources(m)" :key="s.document + s.chunk"
                >《{{ s.document }}》· {{ s.section }}</span
              >
            </div>
            <button
              v-if="m.role === 'ASSISTANT' && m.status !== 'STREAMING'"
              class="regenerate"
              @click="regenerate(m)"
            >
              ↻ 重新生成
            </button>
          </div>
        </article>
        <div v-if="error" class="chat-error">{{ error }}</div>
      </div>
      <footer class="composer">
        <textarea
          v-model="draft"
          rows="1"
          placeholder="说说发生了什么…（Enter 发送，Shift + Enter 换行）"
          @keydown.enter.exact.prevent="send"
        ></textarea>
        <div class="composer-foot">
          <span>AI 可能出错，重要决定请结合现实信息判断</span
          ><button v-if="generating" class="stop" @click="stop">■ 停止</button
          ><button v-else class="send" :disabled="!draft.trim()" @click="send">↑</button>
        </div>
      </footer>
    </section>
  </div>
  <div v-if="toast" class="toast">{{ toast }}</div>
</template>
<script setup>
import { ref, onMounted, nextTick } from 'vue'
import { api, streamSSE } from '../api'
import { authState } from '../stores/auth'
import StructuredText from '../components/StructuredText.vue'
const conversations = ref([]),
  messages = ref([]),
  activeId = ref(null),
  current = ref(null),
  draft = ref(''),
  generating = ref(false),
  reportGenerating = ref(false),
  reportCooldown = ref(0),
  error = ref(''),
  toast = ref(''),
  scrollEl = ref(),
  streamController = ref()
const prompts = ['我们最近总因为小事争吵', '我不知道怎么表达自己的需要', '我想修复一次伤人的沟通']
onMounted(loadConversations)
async function loadConversations() {
  const page = await api.get('/conversations')
  conversations.value = page.content
  if (conversations.value.length) await selectConversation(conversations.value[0].id)
  else await createConversation()
}
async function createConversation() {
  const c = await api.post('/conversations', { title: '新的倾诉' })
  conversations.value.unshift(c)
  await selectConversation(c.id)
}
async function selectConversation(id) {
  activeId.value = id
  current.value = conversations.value.find((x) => x.id === id)
  const page = await api.get(`/conversations/${id}/messages`)
  messages.value = page.content
  scrollBottom()
}
async function send() {
  const content = draft.value.trim()
  if (!content || generating.value) return
  draft.value = ''
  error.value = ''
  messages.value.push({
    id: `local-${Date.now()}`,
    role: 'USER',
    content,
    status: 'COMPLETED',
    createdAt: new Date().toISOString()
  })
  const assistant = ref({
    id: `stream-${Date.now()}`,
    role: 'ASSISTANT',
    content: '',
    status: 'STREAMING',
    createdAt: new Date().toISOString()
  })
  messages.value.push(assistant.value)
  generating.value = true
  scrollBottom()
  try {
    streamController.value = await streamSSE(
      `/conversations/${activeId.value}/messages/stream`,
      { content },
      streamHandlers(assistant.value)
    )
  } catch (e) {
    generating.value = false
    error.value = e.message
  }
}
async function refreshCurrent() {
  const [messagePage, conversationPage] = await Promise.all([
    api.get(`/conversations/${activeId.value}/messages`),
    api.get('/conversations')
  ])
  messages.value = messagePage.content
  conversations.value = conversationPage.content
  current.value = conversations.value.find((x) => x.id === activeId.value)
  scrollBottom()
}
async function stop() {
  streamController.value?.abort()
  await api.post(`/conversations/${activeId.value}/stop`)
  generating.value = false
  await refreshCurrent()
}
async function regenerate(m) {
  if (generating.value) return
  error.value = ''
  generating.value = true
  const assistant = {
    id: `stream-${Date.now()}`,
    role: 'ASSISTANT',
    content: '',
    status: 'STREAMING',
    createdAt: new Date().toISOString()
  }
  messages.value.push(assistant)
  try {
    streamController.value = await streamSSE(
      `/conversations/${activeId.value}/messages/${m.id}/regenerate`,
      null,
      streamHandlers(assistant)
    )
  } catch (e) {
    generating.value = false
    error.value = e.message
  }
}
function streamHandlers(assistant) {
  return {
    delta: (d) => {
      assistant.content += d.content || ''
      assistant.id = d.messageId || assistant.id
      scrollBottom()
    },
    done: async () => {
      generating.value = false
      await refreshCurrent()
    },
    close: async (state) => {
      if (generating.value) {
        generating.value = false
        if (state.received) await refreshCurrent()
      }
    },
    error: (e) => {
      generating.value = false
      error.value = e.message || '生成失败'
    },
    transportError: async (_e, state) => {
      generating.value = false
      if (state.received || assistant.content) {
        await refreshCurrent()
        toast.value = '连接已结束，已保留生成内容'
        setTimeout(() => (toast.value = ''), 2200)
      } else error.value = '连接中断，请确认后端服务正常'
    }
  }
}
async function renameConversation() {
  const title = prompt('新的会话名称', current.value.title)
  if (!title) return
  await api.patch(`/conversations/${activeId.value}`, { title })
  await loadConversations()
}
async function removeConversation() {
  if (!confirm('删除该会话及全部消息？此操作不可恢复。')) return
  await api.delete(`/conversations/${activeId.value}`)
  activeId.value = null
  current.value = null
  messages.value = []
  await loadConversations()
}
async function makeReport() {
  if (reportGenerating.value || reportCooldown.value > 0 || !messages.value.length) return
  reportGenerating.value = true
  reportCooldown.value = 5
  toast.value = '正在整理本次咨询并生成结构化报告，请稍候…'
  const cooldownTimer = setInterval(() => {
    reportCooldown.value--
    if (reportCooldown.value <= 0) clearInterval(cooldownTimer)
  }, 1000)
  try {
    const r = await api.post('/reports', { conversationId: activeId.value })
    toast.value = `报告「${r.title}」已生成，可在关系报告中查看`
    setTimeout(() => (toast.value = ''), 3200)
  } catch (e) {
    toast.value = ''
    error.value = e.response?.data?.message || '报告生成失败'
  } finally {
    reportGenerating.value = false
  }
}
function sources(m) {
  try {
    return JSON.parse(m.sourcesJson || '[]')
  } catch {
    return []
  }
}
function time(v) {
  return v ? new Date(v).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }) : ''
}
function relative(v) {
  if (!v) return ''
  const d = (Date.now() - new Date(v)) / 864e5
  return d < 1 ? '今天' : d < 2 ? '昨天' : `${Math.floor(d)} 天前`
}
async function scrollBottom() {
  await nextTick()
  if (scrollEl.value) scrollEl.value.scrollTop = scrollEl.value.scrollHeight
}
</script>
<style scoped>
.consult-page {
  height: calc(100vh - 154px);
  min-height: 620px;
  display: grid;
  grid-template-columns: 250px 1fr;
  gap: 18px;
}
.conversation-panel {
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.conv-head {
  padding: 20px;
  display: flex;
  align-items: center;
  border-bottom: 1px solid var(--line);
}
.conv-head > div {
  display: grid;
}
.conv-head small {
  margin-top: 2px;
  color: var(--muted);
  font-size: 12px;
}
.new-chat {
  margin-left: auto;
  width: 34px;
  height: 34px;
  border: 0;
  border-radius: 9px;
  color: white;
  background: var(--ink);
  font-size: 19px;
}
.conv-list {
  padding: 10px;
  overflow: auto;
}
.conv-list button {
  width: 100%;
  padding: 12px;
  border: 0;
  border-radius: 10px;
  background: transparent;
  text-align: left;
}
.conv-list button span,
.conv-list button small {
  display: block;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.conv-list button span {
  font-size: 14px;
  font-weight: 600;
}
.conv-list button small {
  margin-top: 4px;
  color: #99968e;
  font-size: 11px;
}
.conv-list button.active {
  background: #efede7;
}
.conv-actions {
  margin-top: auto;
  padding: 12px;
  border-top: 1px solid var(--line);
  display: flex;
  gap: 8px;
}
.conv-actions button {
  flex: 1;
  border: 0;
  background: transparent;
  color: var(--muted);
  font-size: 13px;
}
.conv-actions .danger {
  color: #b14c3e;
}
.chat-panel {
  min-width: 0;
  display: grid;
  grid-template-rows: auto 1fr auto;
  overflow: hidden;
}
.chat-head {
  padding: 16px 20px;
  border-bottom: 1px solid var(--line);
  display: flex;
  align-items: center;
}
.chat-head > div {
  display: flex;
  align-items: center;
  gap: 10px;
}
.advisor-avatar {
  display: grid;
  place-items: center;
  width: 38px;
  height: 38px;
  border-radius: 12px;
  color: #843e32;
  background: var(--coral-soft);
  font-weight: 700;
}
.chat-head b,
.chat-head small {
  display: block;
}
.chat-head b {
  font-size: 15px;
}
.chat-head small {
  margin-top: 2px;
  color: var(--muted);
  font-size: 12px;
}
.chat-head small i {
  display: inline-block;
  width: 6px;
  height: 6px;
  margin-right: 4px;
  border-radius: 50%;
  background: #66a878;
}
.chat-head > .btn {
  margin-left: auto;
  padding: 9px 12px;
  font-size: 13px;
}
.messages {
  padding: 26px max(24px, 7%);
  overflow: auto;
  background: #faf9f5;
}
.chat-welcome {
  max-width: 620px;
  margin: 60px auto;
  text-align: center;
}
.chat-welcome > span {
  display: grid;
  place-items: center;
  width: 52px;
  height: 52px;
  margin: auto;
  border-radius: 16px;
  color: #a84b3c;
  background: var(--coral-soft);
  font-size: 22px;
}
.chat-welcome h2 {
  margin: 18px 0 8px;
}
.chat-welcome p {
  margin: 0 auto;
  color: var(--muted);
  font-size: 15px;
  line-height: 1.75;
}
.prompts {
  margin-top: 24px;
  display: flex;
  justify-content: center;
  gap: 8px;
  flex-wrap: wrap;
}
.prompts button {
  padding: 10px 13px;
  border: 1px solid var(--line);
  border-radius: 99px;
  background: white;
  color: #67655f;
  font-size: 13px;
}
.message {
  display: grid;
  grid-template-columns: 36px 1fr;
  gap: 13px;
  max-width: 820px;
  margin: 0 auto 30px;
}
.message-avatar {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border-radius: 10px;
  background: #e8e4d9;
  font-size: 13px;
  font-weight: 700;
}
.message.assistant .message-avatar {
  color: #9d4537;
  background: var(--coral-soft);
}
.message-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 27px;
}
.message-meta b {
  font-size: 14px;
}
.message-meta small {
  color: #aaa79f;
  font-size: 11px;
}
.message-content {
  white-space: pre-wrap;
  font-size: 15px;
  line-height: 1.85;
}
.message.user .message-content {
  display: inline-block;
  padding: 12px 15px;
  border-radius: 4px 14px 14px 14px;
  background: #ece9e1;
}
.cursor {
  display: inline-block;
  width: 6px;
  height: 16px;
  margin-left: 3px;
  vertical-align: middle;
  background: var(--coral);
  animation: blink 1s infinite;
}
.sources {
  margin-top: 12px;
  padding: 13px;
  border: 1px solid #e3ded2;
  border-radius: 10px;
  background: #fffdf8;
  display: flex;
  gap: 7px;
  flex-wrap: wrap;
}
.sources b {
  width: 100%;
  font-size: 12px;
}
.sources span {
  color: #77736a;
  font-size: 12px;
}
.regenerate {
  margin-top: 10px;
  border: 0;
  background: transparent;
  color: #88857d;
  font-size: 12px;
}
.chat-error {
  max-width: 820px;
  margin: 0 auto;
  padding: 11px 14px;
  border-radius: 9px;
  color: #a33f32;
  background: #f9e6e2;
  font-size: 13px;
}
.composer {
  padding: 15px 20px;
  border-top: 1px solid var(--line);
  background: white;
}
.composer textarea {
  width: 100%;
  max-height: 140px;
  padding: 4px 0;
  border: 0;
  outline: 0;
  resize: none;
  line-height: 1.65;
  font-size: 16px;
}
.composer-foot {
  display: flex;
  align-items: center;
  margin-top: 9px;
}
.composer-foot > span {
  color: #aaa79e;
  font-size: 11px;
}
.send,
.stop {
  margin-left: auto;
  border: 0;
}
.send {
  width: 35px;
  height: 35px;
  border-radius: 10px;
  color: white;
  background: var(--ink);
  font-size: 18px;
}
.stop {
  padding: 8px 11px;
  border-radius: 8px;
  color: #a84134;
  background: var(--coral-soft);
  font-size: 12px;
}
@keyframes blink {
  50% {
    opacity: 0;
  }
}
@media (min-width: 1500px) {
  .consult-page {
    grid-template-columns: 300px 1fr;
  }
  .message,
  .chat-error {
    max-width: 980px;
  }
  .chat-welcome {
    max-width: 720px;
  }
}
@media (max-width: 760px) {
  .consult-page {
    height: auto;
    min-height: calc(100vh - 120px);
    grid-template-columns: 1fr;
  }
  .conversation-panel {
    max-height: 180px;
  }
  .conv-list {
    display: flex;
  }
  .conv-list button {
    min-width: 170px;
  }
  .conv-actions {
    display: none;
  }
  .chat-panel {
    min-height: 650px;
  }
  .messages {
    padding-inline: 14px;
  }
}
.conv-head small,
.conv-list button small,
.chat-head small,
.message-meta small,
.composer-foot > span {
  font-size: 12px;
}
.conv-list button span {
  font-size: 15px;
}
.conv-actions button {
  font-size: 14px;
}
.chat-head b {
  font-size: 16px;
}
.chat-head > .btn {
  font-size: 14px;
}
.prompts button {
  font-size: 14px;
}
.message-meta b {
  font-size: 15px;
}
.message-content {
  font-size: 16px;
}
.sources b,
.sources span,
.regenerate {
  font-size: 13px;
}
.chat-error {
  font-size: 14px;
}
.stop {
  font-size: 13px;
}
</style>
