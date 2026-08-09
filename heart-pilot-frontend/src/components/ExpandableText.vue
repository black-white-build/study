<template>
  <div class="expandable-text" :class="{ expanded, collapsible }">
    <div class="expandable-copy-wrap">
      <p ref="copyRef" class="expandable-copy" :style="{ '--collapsed-lines': lines }">
        <template v-for="(segment, index) in segments" :key="index">
          <a
            v-if="segment.href"
            class="expandable-link"
            :href="segment.href"
            target="_blank"
            rel="noopener noreferrer"
            title="在新窗口打开链接"
            @click.stop
            >{{ segment.text }}</a
          ><span v-else>{{ segment.text }}</span>
        </template>
      </p>
    </div>
    <button
      v-if="collapsible"
      type="button"
      class="expand-toggle"
      :aria-expanded="expanded"
      @click="expanded = !expanded"
    >
      {{ expanded ? '收起' : '展开全文' }}
      <span aria-hidden="true">{{ expanded ? '↑' : '↓' }}</span>
    </button>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = defineProps({
  content: { type: String, default: '' },
  lines: { type: Number, default: 5 }
})

const copyRef = ref(null)
const expanded = ref(false)
const collapsible = ref(false)
let resizeObserver

const segments = computed(() => {
  const source = props.content || ''
  const result = []
  const urlPattern = /https?:\/\/[^\s<>"']+/gi
  let cursor = 0
  let match

  while ((match = urlPattern.exec(source)) !== null) {
    if (match.index > cursor) result.push({ text: source.slice(cursor, match.index) })

    const rawUrl = match[0]
    const cleanUrl = rawUrl.replace(/[),.;!?，。；！？、）】》]+$/u, '')
    const trailing = rawUrl.slice(cleanUrl.length)
    result.push({ text: cleanUrl, href: cleanUrl })
    if (trailing) result.push({ text: trailing })
    cursor = match.index + rawUrl.length
  }

  if (cursor < source.length) result.push({ text: source.slice(cursor) })
  return result.length ? result : [{ text: source }]
})

function checkOverflow() {
  const element = copyRef.value
  if (!element) return
  const lineHeight = Number.parseFloat(getComputedStyle(element).lineHeight) || 24
  const hasManyExplicitLines = props.content.split('\n').length > props.lines
  const isClearlyLong = props.content.length > props.lines * 120
  collapsible.value =
    hasManyExplicitLines || isClearlyLong || element.scrollHeight > lineHeight * props.lines + 2
}

watch(
  () => props.content,
  async () => {
    expanded.value = false
    await nextTick()
    checkOverflow()
  }
)

watch(expanded, async (value) => {
  if (value) return
  await nextTick()
  checkOverflow()
})

onMounted(() => {
  checkOverflow()
  resizeObserver = new ResizeObserver(checkOverflow)
  resizeObserver.observe(copyRef.value)
})

onBeforeUnmount(() => resizeObserver?.disconnect())
</script>

<style scoped>
.expandable-text {
  min-width: 0;
}
.expandable-copy-wrap {
  position: relative;
  min-width: 0;
}
.expandable-copy {
  margin: 0;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  word-break: break-word;
}
.expandable-link {
  color: #aa493b;
  text-decoration: underline;
  text-decoration-color: #dfa79e;
  text-decoration-thickness: 1px;
  text-underline-offset: 3px;
  transition: 0.18s;
}
.expandable-link:hover {
  color: var(--coral);
  text-decoration-color: currentColor;
}
.expandable-link:focus-visible {
  outline: 2px solid #e5a093;
  outline-offset: 2px;
  border-radius: 3px;
}
.expandable-text:not(.expanded) .expandable-copy {
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: var(--collapsed-lines);
  overflow: hidden;
}
.expandable-text.collapsible:not(.expanded) .expandable-copy-wrap::after {
  content: '';
  position: absolute;
  right: 0;
  bottom: 0;
  width: 28%;
  height: 1.7em;
  pointer-events: none;
  background: linear-gradient(90deg, transparent, var(--paper) 78%);
}
.expand-toggle {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  min-height: 32px;
  margin-top: 7px;
  padding: 3px 0;
  border: 0;
  background: transparent;
  color: #a64b3d;
  font-size: 13px;
  font-weight: 700;
}
.expand-toggle:hover {
  color: var(--coral);
}
.expand-toggle:focus-visible {
  outline: 2px solid #e5a093;
  outline-offset: 3px;
  border-radius: 4px;
}
.expand-toggle span {
  font-size: 12px;
}
</style>
