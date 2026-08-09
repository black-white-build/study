<template>
  <div class="landing">
    <header class="land-nav">
      <router-link class="land-logo" to="/"><span>心</span><b>心旅 HeartPilot</b></router-link>
      <nav><a href="#method">如何工作</a><a href="#safety">安全边界</a></nav>
      <div>
        <router-link v-if="!authState.token" to="/login" class="text-link">登录</router-link
        ><router-link v-else to="/consult" class="btn primary">进入工作台</router-link>
      </div>
    </header>
    <main>
      <section class="hero">
        <div class="hero-copy">
          <span class="hero-pill">关系成长，不只是一场问答</span>
          <h1>把说不清的关系困扰，<em>变成下一步行动。</em></h1>
          <p>
            一套从倾诉、分析、规划到持续复盘的 AI
            关系成长系统。温和理解你，也认真帮你把改变落到今天。
          </p>
          <div class="hero-actions">
            <router-link :to="authState.token ? '/consult' : '/register'" class="btn coral"
              >开始一次倾诉 <span>→</span></router-link
            ><a href="#method" class="btn">看看如何工作</a>
          </div>
          <div class="trust">
            <span>✓ 私密会话隔离</span><span>✓ 知识库增强</span><span>✓ 高风险安全提醒</span>
          </div>
        </div>
        <div class="hero-card">
          <div class="card-top">
            <span class="avatar">旅</span>
            <div><b>心旅顾问</b><small>正在陪你梳理</small></div>
            <i></i>
          </div>
          <div class="bubble user">最近我们总因小事争吵，我不知道该怎么开口。</div>
          <div class="bubble ai">
            <span
              >我听见你既在意这段关系，也有些疲惫。我们先不急着判断谁对谁错——最近一次争吵，是从哪句话开始升级的？</span
            >
            <div class="action-chip">今天的小行动：先写下事实，不急着解释动机</div>
          </div>
          <div class="typing"><i></i><i></i><i></i></div>
        </div>
      </section>
      <section id="method" class="method">
        <div class="section-intro">
          <span>一条清楚的改善路径</span>
          <h2>从理解问题，到持续看见变化</h2>
        </div>
        <div class="steps">
          <article>
            <b>01</b><span class="step-icon">◌</span>
            <h3>倾诉问题</h3>
            <p>在安全、连贯的多轮对话里，把事实、情绪与需要慢慢说清。</p>
          </article>
          <article>
            <b>02</b><span class="step-icon">▤</span>
            <h3>获得分析</h3>
            <p>结合关系档案与专业知识库，形成有来源的结构化报告。</p>
          </article>
          <article>
            <b>03</b><span class="step-icon">↗</span>
            <h3>制定行动</h3>
            <p>让 Agent 搜集信息、整理预算，并在关键步骤等待你确认。</p>
          </article>
          <article>
            <b>04</b><span class="step-icon">✓</span>
            <h3>跟踪改善</h3>
            <p>用 7 天计划、每日情绪打卡与每周复盘，看见真实进展。</p>
          </article>
        </div>
      </section>
      <section id="safety" class="safety">
        <div>
          <span>为真实关系而设计</span>
          <h2>AI 给建议，决定权始终在你手里。</h2>
          <p>
            敏感操作需要确认，所有 Agent
            步骤均可查看、取消和追溯。遇到暴力、自伤或人身安全风险时，我们会优先提示寻求现实世界的可信支持。
          </p>
        </div>
        <router-link :to="authState.token ? '/profile' : '/register'" class="btn"
          >建立你的关系档案 →</router-link
        >
      </section>
    </main>
    <footer class="land-footer">
      <b>心旅 HeartPilot</b><span>倾诉 · 分析 · 行动 · 成长</span
      ><small>AI 建议不替代医疗、心理、法律等专业服务。</small>
    </footer>
  </div>
</template>
<script setup>
import { authState } from '../stores/auth'
</script>
<style scoped>
.landing {
  background: #fbfaf6;
  min-height: 100vh;
}
.land-nav {
  height: 76px;
  max-width: 1200px;
  margin: auto;
  padding: 0 24px;
  display: flex;
  align-items: center;
  border-bottom: 1px solid #eeebe3;
}
.land-logo {
  display: flex;
  align-items: center;
  gap: 10px;
}
.land-logo span {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border-radius: 11px;
  color: white;
  background: var(--coral);
}
.land-nav nav {
  display: flex;
  gap: 30px;
  margin-left: 70px;
  color: #686761;
  font-size: 13px;
}
.land-nav > div {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 18px;
}
.text-link {
  font-size: 13px;
  font-weight: 600;
}
.hero {
  max-width: 1200px;
  margin: auto;
  padding: 90px 24px 110px;
  display: grid;
  grid-template-columns: 1.12fr 0.88fr;
  gap: 80px;
  align-items: center;
}
.hero-pill {
  display: inline-flex;
  padding: 7px 11px;
  border-radius: 99px;
  color: #8f4437;
  background: var(--coral-soft);
  font-size: 12px;
  font-weight: 700;
}
.hero h1 {
  max-width: 680px;
  margin: 22px 0;
  font-size: clamp(44px, 5.6vw, 76px);
  line-height: 1.08;
  letter-spacing: -3.4px;
}
.hero h1 em {
  color: var(--coral);
  font-style: normal;
}
.hero-copy > p {
  max-width: 600px;
  color: #66655f;
  font-size: 17px;
  line-height: 1.8;
}
.hero-actions {
  display: flex;
  gap: 12px;
  margin: 34px 0;
}
.trust {
  display: flex;
  gap: 18px;
  color: #75736c;
  font-size: 11px;
}
.hero-card {
  position: relative;
  padding: 24px;
  border: 1px solid var(--line);
  border-radius: 28px;
  background: white;
  box-shadow: 0 32px 80px rgba(44, 42, 36, 0.12);
  transform: rotate(1.5deg);
}
.hero-card:before {
  content: '';
  position: absolute;
  inset: -18px 20px auto -20px;
  height: 150px;
  background: #eef1e9;
  border-radius: 30px;
  z-index: -1;
  transform: rotate(-5deg);
}
.card-top {
  display: flex;
  align-items: center;
  gap: 10px;
  padding-bottom: 18px;
  border-bottom: 1px solid var(--line);
}
.card-top b,
.card-top small {
  display: block;
}
.card-top b {
  font-size: 13px;
}
.card-top small {
  color: #8b8981;
  font-size: 10px;
}
.card-top i {
  margin-left: auto;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #62a576;
}
.bubble {
  max-width: 88%;
  margin-top: 20px;
  padding: 14px 16px;
  border-radius: 16px;
  font-size: 13px;
  line-height: 1.7;
}
.bubble.user {
  margin-left: auto;
  background: #292a27;
  color: white;
  border-bottom-right-radius: 4px;
}
.bubble.ai {
  background: #f5f3ed;
  border-bottom-left-radius: 4px;
}
.action-chip {
  margin-top: 13px;
  padding: 10px;
  border-left: 2px solid var(--coral);
  background: white;
  color: #7a5048;
  font-size: 11px;
}
.typing {
  display: flex;
  gap: 3px;
  margin: 18px 10px;
}
.typing i {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: #aaa69c;
}
.method {
  padding: 100px 24px;
  background: #252622;
  color: white;
}
.section-intro {
  max-width: 1150px;
  margin: 0 auto 50px;
}
.section-intro span,
.safety > div > span {
  color: #ec8875;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 1px;
}
.section-intro h2 {
  margin: 12px 0;
  font-size: 36px;
}
.steps {
  max-width: 1150px;
  margin: auto;
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 1px;
  background: #444540;
}
.steps article {
  position: relative;
  min-height: 280px;
  padding: 28px;
  background: #2d2e2b;
}
.steps article > b {
  color: #777970;
  font-size: 11px;
}
.step-icon {
  display: grid;
  place-items: center;
  width: 45px;
  height: 45px;
  margin: 55px 0 20px;
  border: 1px solid #55564f;
  border-radius: 14px;
  color: #ff8b75;
}
.steps h3 {
  margin: 0 0 10px;
}
.steps p {
  margin: 0;
  color: #aaa9a2;
  font-size: 13px;
  line-height: 1.7;
}
.safety {
  max-width: 1150px;
  margin: 100px auto;
  padding: 55px;
  display: flex;
  align-items: end;
  gap: 60px;
  border-radius: 28px;
  background: #ede8dc;
}
.safety h2 {
  max-width: 700px;
  margin: 12px 0;
  font-size: 36px;
}
.safety p {
  max-width: 760px;
  margin: 0;
  color: #66635c;
  line-height: 1.8;
}
.safety > .btn {
  margin-left: auto;
  white-space: nowrap;
}
.land-footer {
  max-width: 1150px;
  margin: auto;
  padding: 30px 0 50px;
  border-top: 1px solid var(--line);
  display: flex;
  gap: 22px;
  color: #77756e;
  font-size: 12px;
}
.land-footer b {
  color: var(--ink);
}
.land-footer small {
  margin-left: auto;
}
@media (max-width: 850px) {
  .land-nav nav {
    display: none;
  }
  .hero {
    grid-template-columns: 1fr;
    padding-top: 60px;
    gap: 60px;
  }
  .hero h1 {
    letter-spacing: -2px;
  }
  .hero-card {
    max-width: 520px;
  }
  .steps {
    grid-template-columns: 1fr 1fr;
  }
  .safety {
    margin: 70px 18px;
    padding: 35px;
    align-items: flex-start;
    flex-direction: column;
  }
  .land-footer {
    margin: 0 20px;
    flex-wrap: wrap;
  }
  .land-footer small {
    width: 100%;
    margin: 0;
  }
}
@media (max-width: 520px) {
  .land-nav {
    height: 66px;
  }
  .land-logo b {
    font-size: 13px;
  }
  .text-link {
    display: none;
  }
  .hero {
    padding: 48px 18px 80px;
  }
  .hero h1 {
    font-size: 44px;
  }
  .hero-actions,
  .trust {
    flex-wrap: wrap;
  }
  .steps {
    grid-template-columns: 1fr;
  }
  .method {
    padding: 75px 18px;
  }
  .safety h2,
  .section-intro h2 {
    font-size: 29px;
  }
}
/* Keep the landing page generous and legible on large displays. */
.land-nav,
.hero {
  max-width: 1380px;
}
.land-nav nav,
.text-link {
  font-size: 14px;
}
.hero {
  padding-top: 100px;
  gap: 96px;
}
.hero-pill {
  font-size: 13px;
}
.trust {
  font-size: 13px;
}
.hero-card {
  padding: 28px;
}
.card-top b {
  font-size: 15px;
}
.card-top small {
  font-size: 12px;
}
.bubble {
  font-size: 15px;
}
.action-chip {
  font-size: 13px;
}
.section-intro,
.steps,
.safety,
.land-footer {
  max-width: 1320px;
}
.section-intro span,
.safety > div > span {
  font-size: 13px;
}
.steps h3 {
  font-size: 19px;
}
.steps p {
  font-size: 15px;
}
.land-footer {
  font-size: 13px;
}
@media (max-width: 850px) {
  .hero {
    gap: 64px;
  }
  .hero-card {
    padding: 24px;
  }
}
</style>
