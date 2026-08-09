<template>
  <div class="page-head">
    <div>
      <h1>关系档案</h1>
      <p>让 AI 记住长期背景、关注点和边界，减少每次重复说明。</p>
    </div>
    <button class="btn primary" :disabled="saving" @click="save">
      {{ saving ? '保存中…' : '保存档案' }}
    </button>
  </div>
  <section class="profile-flow panel">
    <div>
      <span class="eyebrow">档案如何发挥作用</span>
      <h2>一份档案，贯穿咨询、行动与复盘</h2>
      <p>
        咨询时用于个性化语气与建议；关系报告沉淀问题；报告可转为 7 天计划；事件与打卡再进入每周 AI
        复盘。
      </p>
    </div>
    <div class="flow">
      <span>关系档案</span><i>→</i><span>AI 咨询</span><i>→</i><span>关系报告</span><i>→</i
      ><span>7 天计划</span><i>→</i><span>每周复盘</span>
    </div>
    <div class="flow-actions">
      <router-link class="btn" to="/consult">带着档案去咨询</router-link
      ><router-link class="btn coral" to="/growth">查看成长闭环</router-link>
    </div>
  </section>
  <section class="profile-impact panel panel-pad">
    <header class="section-title">
      <span>作用</span>
      <div>
        <h2>每个属性会影响哪里</h2>
        <p>这些不是仅供展示的资料；保存后会进入对应业务的上下文、筛选或默认计划。</p>
      </div>
    </header>
    <div class="impact-grid">
      <article v-for="item in profileImpacts" :key="item.field">
        <b>{{ item.field }}</b
        ><span>{{ item.modules }}</span>
        <p>{{ item.effect }}</p>
      </article>
    </div>
  </section>
  <div class="profile-grid">
    <section class="panel panel-pad">
      <header class="section-title">
        <span>01</span>
        <div>
          <h2>此刻的你</h2>
          <p>这些信息会用于调整咨询语气和建议方向。</p>
        </div>
      </header>
      <div class="profile-person">
        <span class="big-avatar">{{ authState.user?.nickname?.slice(0, 1) }}</span>
        <div class="field"><label>称呼</label><input v-model="user.nickname" class="input" /></div>
        <div class="field">
          <label>当前情绪</label
          ><select v-model="user.emotionStatus" class="select">
            <option v-for="e in emotions" :key="e">{{ e }}</option>
          </select>
        </div>
      </div>
      <div class="emotion-row">
        <button
          v-for="e in emotions"
          :key="e"
          :class="{ active: user.emotionStatus === e }"
          @click="user.emotionStatus = e"
        >
          <span>{{ emotionIcon(e) }}</span
          >{{ e }}
        </button>
      </div>
    </section>
    <section class="panel panel-pad">
      <header class="section-title">
        <span>02</span>
        <div>
          <h2>关系基本情况</h2>
          <p>只填写你愿意分享的内容。</p>
        </div>
      </header>
      <div class="grid-2">
        <div class="field">
          <label>关系状态</label
          ><select v-model="profile.relationshipStatus" class="select">
            <option>未设置</option>
            <option>单身</option>
            <option>暧昧/约会中</option>
            <option>恋爱中</option>
            <option>已婚</option>
            <option>分手/修复期</option>
          </select>
        </div>
        <div class="field">
          <label>关系时长（月）</label
          ><input v-model.number="profile.relationshipMonths" class="input" type="number" min="0" />
        </div>
      </div>
      <div class="field">
        <label>常见沟通方式</label
        ><input
          v-model="profile.communicationStyle"
          class="input"
          placeholder="例如：我倾向回避冲突，对方希望当场解决"
        />
      </div>
    </section>
    <section class="panel panel-pad full">
      <header class="section-title">
        <span>03</span>
        <div>
          <h2>偏好、困扰与边界</h2>
          <p>AI 会优先尊重边界，并把困扰转换为成长计划的默认目标。</p>
        </div>
      </header>
      <div class="grid-3">
        <div class="field">
          <label>目前最困扰的事</label
          ><textarea
            v-model="profile.concerns"
            class="textarea"
            placeholder="希望改善的问题…"
          ></textarea>
        </div>
        <div class="field">
          <label>你重视的体验</label
          ><textarea
            v-model="profile.preferences"
            class="textarea"
            placeholder="沟通、约会或关系中的偏好…"
          ></textarea>
        </div>
        <div class="field">
          <label>不可触碰的边界</label
          ><textarea
            v-model="profile.boundaries"
            class="textarea"
            placeholder="明确不能接受的行为…"
          ></textarea>
        </div>
      </div>
    </section>
  </div>
  <div v-if="toast" class="toast">{{ toast }}</div>
</template>
<script setup>
import { reactive, ref, onMounted } from 'vue'
import { api } from '../api'
import { authState, refreshMe } from '../stores/auth'
const emotions = ['平静', '开心', '期待', '困惑', '难过', '焦虑', '生气'],
  profileImpacts = [
    {
      field: '称呼',
      modules: 'AI 咨询 · 全局界面',
      effect: '用于顾问称呼和个人身份展示，不改变事实判断。'
    },
    {
      field: '当前情绪',
      modules: 'AI 咨询 · 每日连接',
      effect: '调整回复语气、信息密度、行动难度，并参与实时连接话题的选择。'
    },
    {
      field: '关系状态',
      modules: 'AI 咨询 · 关系报告 · 周复盘',
      effect: '区分初识、稳定关系或修复阶段，避免给出阶段不匹配的建议。'
    },
    {
      field: '关系时长',
      modules: 'AI 咨询 · 关系报告 · 周复盘',
      effect: '帮助判断互动是短期磨合还是长期模式，但不会替代具体事实。'
    },
    {
      field: '常见沟通方式',
      modules: 'AI 咨询 · 7 天计划 · 周复盘',
      effect: '用于调整沟通练习，例如降低回避或防御式表达的行动门槛。'
    },
    {
      field: '目前最困扰的事',
      modules: '成长焦点 · 7 天计划 · 关系报告',
      effect: '作为默认成长目标，并决定复盘重点和建议优先级。'
    },
    {
      field: '你重视的体验',
      modules: 'AI 咨询 · 行动规划 · 每日连接',
      effect: '影响地点与活动筛选、实时话题方向及 7 天共同活动的默认设计。'
    },
    {
      field: '不可触碰的边界',
      modules: 'AI 咨询 · 行动规划 · 周复盘',
      effect: '作为必须遵守的限制，不会被当成建议目标，也不会为了完成计划而越过。'
    }
  ],
  user = reactive({ nickname: '', emotionStatus: '平静' }),
  profile = reactive({
    relationshipStatus: '未设置',
    relationshipMonths: null,
    communicationStyle: '',
    concerns: '',
    preferences: '',
    boundaries: ''
  }),
  saving = ref(false),
  toast = ref('')
onMounted(async () => {
  Object.assign(user, await api.get('/users/me'))
  Object.assign(profile, await api.get('/users/me/relationship-profile'))
})
async function save() {
  saving.value = true
  try {
    await Promise.all([
      api.patch('/users/me', user),
      api.put('/users/me/relationship-profile', profile)
    ])
    await refreshMe()
    toast.value = '关系档案已保存，并会用于后续咨询和周复盘'
    setTimeout(() => (toast.value = ''), 2600)
  } finally {
    saving.value = false
  }
}
function emotionIcon(e) {
  return { 平静: '—', 开心: '◡', 期待: '✦', 困惑: '?', 难过: '⌒', 焦虑: '≈', 生气: '!' }[e]
}
</script>
<style scoped>
.profile-flow {
  padding: 30px;
  margin-bottom: 22px;
  background: linear-gradient(120deg, #fffefa, #edf3ee);
}
.profile-flow h2 {
  margin: 9px 0;
  font-size: 24px;
}
.profile-flow p {
  margin: 0;
  color: var(--muted);
  font-size: 15px;
  line-height: 1.7;
}
.flow {
  display: flex;
  align-items: center;
  gap: 9px;
  margin-top: 22px;
  flex-wrap: wrap;
}
.flow span {
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: 9px;
  background: white;
  font-size: 13px;
  font-weight: 650;
}
.flow i {
  color: #9e9a91;
  font-style: normal;
}
.flow-actions {
  display: flex;
  gap: 9px;
  margin-top: 20px;
}
.profile-impact {
  margin-bottom: 22px;
}
.impact-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
}
.impact-grid article {
  padding: 16px;
  border: 1px solid var(--line);
  border-radius: 11px;
  background: #fbfaf6;
}
.impact-grid b,
.impact-grid span {
  display: block;
}
.impact-grid b {
  font-size: 14px;
}
.impact-grid span {
  margin-top: 6px;
  color: #9b4d40;
  font-size: 12px;
  font-weight: 650;
}
.impact-grid p {
  margin: 8px 0 0;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.65;
}
.profile-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 22px;
}
.full {
  grid-column: 1/-1;
}
.section-title {
  display: flex;
  gap: 14px;
  margin-bottom: 24px;
}
.section-title > span {
  display: grid;
  place-items: center;
  width: 38px;
  height: 38px;
  border-radius: 10px;
  color: #99483b;
  background: var(--coral-soft);
  font-size: 12px;
  font-weight: 700;
}
.section-title h2 {
  margin: 0;
  font-size: 18px;
}
.section-title p {
  margin: 5px 0 0;
  color: var(--muted);
  font-size: 13px;
}
.profile-person {
  display: grid;
  grid-template-columns: 62px 1fr 1fr;
  gap: 16px;
  align-items: end;
}
.big-avatar {
  display: grid;
  place-items: center;
  width: 58px;
  height: 58px;
  border-radius: 16px;
  background: #e7dfcf;
  font-weight: 700;
  font-size: 20px;
}
.emotion-row {
  display: flex;
  gap: 7px;
  margin-top: 22px;
}
.emotion-row button {
  flex: 1;
  min-height: 54px;
  padding: 9px 5px;
  border: 1px solid var(--line);
  border-radius: 9px;
  background: #faf9f5;
  color: #77746c;
  font-size: 12px;
}
.emotion-row button span {
  display: block;
  margin-bottom: 4px;
  font-size: 17px;
}
.emotion-row button.active {
  border-color: #e8a092;
  color: #9d4436;
  background: var(--coral-soft);
}
@media (max-width: 1000px) {
  .impact-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}
@media (max-width: 850px) {
  .profile-grid {
    grid-template-columns: 1fr;
  }
  .full {
    grid-column: auto;
  }
}
@media (max-width: 580px) {
  .profile-flow {
    padding: 22px;
  }
  .profile-person,
  .impact-grid {
    grid-template-columns: 1fr;
  }
  .emotion-row {
    flex-wrap: wrap;
  }
  .emotion-row button {
    min-width: 64px;
  }
}
</style>
