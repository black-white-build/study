<template>
  <footer class="app-footer">
    <strong>心旅 AI 情感决策与约会规划平台</strong>
    <span>© {{ currentYear }} · 从理解问题到落实行动</span>
  </footer>
</template>

<script setup>
import { computed } from 'vue'

const currentYear = computed(() => new Date().getFullYear())
</script>

<style scoped>
.app-footer {
  width: 100%;
  padding: 28px 20px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  color: #777b8d;
  background: rgba(255, 255, 255, 0.82);
  border-top: 1px solid #ececf2;
}

.app-footer strong {
  color: #343853;
}

@media (max-width: 640px) {
  .app-footer {
    flex-direction: column;
    text-align: center;
    font-size: 13px;
  }
}
</style>
