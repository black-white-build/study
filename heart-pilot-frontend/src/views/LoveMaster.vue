<template>
  <div class="love-master-container">
    <div class="header">
      <div class="back-button" @click="goBack">返回</div>
      <h1 class="title">关系成长顾问</h1>
      <div class="chat-id">会话ID: {{ chatId }}</div>
    </div>

    <div class="content-wrapper">
      <section v-if="report || lastUserMessage" class="report-panel">
        <div class="report-heading">
          <div>
            <strong>关系分析报告</strong>
            <span>根据最近一次描述生成结构化建议</span>
          </div>
          <button :disabled="reportLoading || !lastUserMessage" @click="createReport">
            {{ reportLoading ? '生成中…' : report ? '重新生成' : '生成报告' }}
          </button>
        </div>
        <div v-if="report" class="report-content">
          <h3>{{ report.title }}</h3>
          <p>{{ report.problemSummary }}</p>
          <div class="risk">风险等级：{{ report.riskLevel }}</div>
          <ul>
            <li v-for="item in report.suggestions" :key="item">{{ item }}</li>
          </ul>
          <div class="next-action"><b>今天的下一步：</b>{{ report.nextAction }}</div>
        </div>
      </section>
      <div class="chat-area">
        <ChatRoom
          :messages="messages"
          :connection-status="connectionStatus"
          ai-type="love"
          @send-message="sendMessage"
        />
      </div>
    </div>

    <div class="footer-container">
      <AppFooter />
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { useHead } from '@vueuse/head'
import ChatRoom from '../components/ChatRoom.vue'
import AppFooter from '../components/AppFooter.vue'
import { chatWithRelationshipAiClient, generateRelationshipReport } from '../api'

// 设置页面标题和元数据
useHead({
  title: '关系成长顾问 - 心旅 AI',
  meta: [
    {
      name: 'description',
      content: '心旅 AI 关系成长顾问通过知识库增强问答分析关系问题并提供行动建议'
    },
    {
      name: 'keywords',
      content: '关系成长顾问,情感咨询,关系分析,AI聊天,情感报告'
    }
  ]
})

const router = useRouter()
const messages = ref([])
const chatId = ref('')
const connectionStatus = ref('disconnected')
const lastUserMessage = ref('')
const report = ref(null)
const reportLoading = ref(false)
let eventSource = null

// 生成随机会话ID
const generateChatId = () => {
  return 'love_' + Math.random().toString(36).substring(2, 10)
}

// 添加消息到列表
const addMessage = (content, isUser) => {
  messages.value.push({
    content,
    isUser,
    time: new Date().getTime()
  })
}

// 发送消息
const sendMessage = (message) => {
  lastUserMessage.value = message
  report.value = null
  addMessage(message, true)

  // 连接SSE
  if (eventSource) {
    eventSource.close()
  }

  // 创建一个空的AI回复消息
  const aiMessageIndex = messages.value.length
  addMessage('', false)

  connectionStatus.value = 'connecting'
  eventSource = chatWithRelationshipAiClient(message, chatId.value)

  // 监听SSE消息
  eventSource.onmessage = (event) => {
    const data = event.data
    if (data && data !== '[DONE]') {
      // 更新最新的AI消息内容，而不是创建新消息
      if (aiMessageIndex < messages.value.length) {
        messages.value[aiMessageIndex].content += data
      }
    }

    if (data === '[DONE]') {
      connectionStatus.value = 'disconnected'
      eventSource.close()
    }
  }

  // 监听SSE错误
  eventSource.onerror = (error) => {
    console.error('SSE Error:', error)
    connectionStatus.value = 'error'
    eventSource.close()
  }
}

const createReport = async () => {
  if (!lastUserMessage.value || reportLoading.value) return
  reportLoading.value = true
  try {
    const response = await generateRelationshipReport(lastUserMessage.value, chatId.value)
    report.value = response.data
  } catch (error) {
    console.error('Report Error:', error)
    addMessage('关系报告生成失败，请稍后重试。', false)
  } finally {
    reportLoading.value = false
  }
}

// 返回主页
const goBack = () => {
  router.push('/')
}

// 页面加载时添加欢迎消息
onMounted(() => {
  // 生成聊天ID
  chatId.value = generateChatId()

  // 添加欢迎消息
  addMessage(
    '你好，我是心旅 AI 关系成长顾问。你可以从关系状态、事情经过和自己的感受开始说起。',
    false
  )
})

// 组件销毁前关闭SSE连接
onBeforeUnmount(() => {
  if (eventSource) {
    eventSource.close()
  }
})
</script>

<style scoped>
.love-master-container {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
  background-color: #fff9f9;
}

.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 24px;
  background-color: #ff6b8b;
  color: white;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
  position: sticky;
  top: 0;
  z-index: 10;
}

.back-button {
  font-size: 16px;
  cursor: pointer;
  display: flex;
  align-items: center;
  transition: opacity 0.2s;
}

.back-button:hover {
  opacity: 0.8;
}

.back-button:before {
  content: '←';
  margin-right: 8px;
}

.title {
  font-size: 20px;
  font-weight: bold;
  margin: 0;
}

.chat-id {
  font-size: 14px;
  opacity: 0.8;
}

.content-wrapper {
  display: flex;
  flex-direction: column;
  flex: 1;
}

.report-panel {
  margin: 18px 16px 0;
  padding: 18px;
  background: #fff;
  border: 1px solid #ffd9df;
  border-radius: 14px;
  box-shadow: 0 8px 24px rgba(118, 63, 78, 0.07);
}

.report-heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
}
.report-heading div {
  display: grid;
  gap: 4px;
}
.report-heading span {
  color: #85808a;
  font-size: 13px;
}
.report-heading button {
  padding: 9px 16px;
  color: white;
  background: #e85f7d;
  border: 0;
  border-radius: 999px;
}
.report-heading button:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
.report-content {
  margin-top: 16px;
  color: #4c4650;
  line-height: 1.7;
}
.report-content h3 {
  margin-bottom: 6px;
}
.report-content ul {
  margin: 10px 0 10px 20px;
}
.risk {
  display: inline-block;
  margin-top: 10px;
  padding: 3px 9px;
  background: #fff1f3;
  border-radius: 6px;
  font-size: 13px;
}
.next-action {
  padding: 10px 12px;
  background: #fff8ed;
  border-radius: 8px;
}

.chat-area {
  flex: 1;
  padding: 16px;
  overflow: hidden;
  position: relative;
  /* 设置最小高度确保内容显示正常 */
  min-height: calc(100vh - 56px - 180px); /* 100vh减去头部高度和页脚高度 */
  margin-bottom: 16px; /* 为页脚留出空间 */
}

.footer-container {
  margin-top: auto;
}

/* 响应式样式 */
@media (max-width: 768px) {
  .header {
    padding: 12px 16px;
  }

  .title {
    font-size: 18px;
  }

  .chat-id {
    font-size: 12px;
  }

  .chat-area {
    padding: 12px;
    min-height: calc(100vh - 48px - 160px); /* 调整计算值 */
    margin-bottom: 12px;
  }
}

@media (max-width: 480px) {
  .header {
    padding: 10px 12px;
  }

  .back-button {
    font-size: 14px;
  }

  .title {
    font-size: 16px;
  }

  .chat-id {
    display: none;
  }

  .chat-area {
    padding: 8px;
    min-height: calc(100vh - 42px - 150px); /* 再次调整计算值 */
    margin-bottom: 8px;
  }
}
</style>
