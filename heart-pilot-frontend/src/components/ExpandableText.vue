<template>
  <div class="expandable-text" :class="{ expanded, collapsible }">
    <div class="expandable-copy-wrap">
      <p ref="copyRef" class="expandable-copy" :style="{ '--collapsed-lines': lines }">
        <template v-for="(segment, index) in segments" :key="index">
          <template v-if="segment.href">
            <a
              class="expandable-link"
              :href="segment.href"
              target="_blank"
              rel="noopener noreferrer"
              title="在新窗口打开链接"
              @click.stop
              >{{ isUrlExpanded(index) ? segment.text : truncateUrl(segment.text) }}</a
            >
            <button
              v-if="segment.text.length > 30 && !isUrlExpanded(index)"
              type="button"
              class="url-expand-btn"
              @click.stop="toggleUrl(index)"
            >
              展开
            </button>
          </template>
          <span v-else>{{ segment.text }}</span>
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

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = defineProps({
  content: { type: String, default: '' },
  lines: { type: Number, default: 5 }
})

const expandedUrls = ref<Set<number>>(new Set())

const truncateUrl = (url: string) => {
  if (url.length <= 30) return url
  // 提取域名部分，后面用...代替
  const match = url.match(/^(https?:\/\/[^/]+)/)
  if (match) return match[1] + '/...'
  return url.slice(0, 25) + '…'
}

const isUrlExpanded = (index: number) => expandedUrls.value.has(index)

const toggleUrl = (index: number) => {
  if (expandedUrls.value.has(index)) {
    expandedUrls.value.delete(index)
  } else {
    expandedUrls.value.add(index)
  }
}

const copyRef = ref<HTMLElement | null>(null)
const expanded = ref(false)
const collapsible = ref(false)
let resizeObserver: ResizeObserver | undefined

const segments = computed<{ text: string; href?: string }[]>(() => {
  // 先清洗掉 Markdown 原始标记，显示更美观
  let source = props.content || ''
  // 把行首的 ### 标题标记换成竖线前缀
  source = source.replace(/^#+\s*/gm, '▎ ')
  // 把加粗标记 ** 去掉，只保留文字
  source = source.replace(/\*\*([^*]+)\*\*/g, '$1')
  const result: { text: string; href?: string }[] = []
  // 先匹配 Markdown 链接 [文字](https://...)，允许 ] 和 ( 之间有换行/空白（长行自动换行时会断开）
  const mdLinkPattern = /\[([^\]]+)\]\s*\(\s*(https?:\/\/[^\s)]+?)\s*\)/gi
  // 再匹配裸 URL（整段里剩下的 http(s)://...）
  const urlPattern = /https?:\/\/[^\s<>"']+/gi
  let cursor = 0
  let match: RegExpExecArray | null

  // 第一遍：切出 Markdown 链接
  while ((match = mdLinkPattern.exec(source)) !== null) {
    if (match.index > cursor) result.push({ text: source.slice(cursor, match.index) })
    result.push({ text: match[2], href: match[2] })
    cursor = match.index + match[0].length
  }
  if (cursor < source.length) result.push({ text: source.slice(cursor) })

  // 第二遍：对 Markdown 链接之外的纯文本段，把裸 URL 也变成可点击链接
  const withLinks: { text: string; href?: string }[] = []
  for (const seg of result) {
    if (seg.href) {
      withLinks.push(seg)
      continue
    }
    let subCursor = 0
    urlPattern.lastIndex = 0
    let subMatch: RegExpExecArray | null
    while ((subMatch = urlPattern.exec(seg.text)) !== null) {
      if (subMatch.index > subCursor) {
        withLinks.push({ text: seg.text.slice(subCursor, subMatch.index) })
      }
      const rawUrl = subMatch[0]
      const cleanUrl = rawUrl.replace(/[),.;!?，。；！？、）】》]+$/u, '')
      const trailing = rawUrl.slice(cleanUrl.length)
      withLinks.push({ text: cleanUrl, href: cleanUrl })
      if (trailing) withLinks.push({ text: trailing })
      subCursor = subMatch.index + rawUrl.length
    }
    if (subCursor < seg.text.length) {
      withLinks.push({ text: seg.text.slice(subCursor) })
    }
  }
  return withLinks.length ? withLinks : [{ text: source }]
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
  if (copyRef.value) resizeObserver.observe(copyRef.value)
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
.url-expand-btn {
  display: inline-block;
  margin-left: 4px;
  padding: 0 6px;
  border: 1px solid #dfa79e;
  border-radius: 3px;
  background: transparent;
  color: #aa493b;
  font-size: 11px;
  line-height: 1.5;
  cursor: pointer;
  vertical-align: middle;
}
.url-expand-btn:hover {
  background: #f9ecea;
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
