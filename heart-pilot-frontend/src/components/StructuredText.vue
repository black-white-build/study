<template>
  <div class="structured-text">
    <template v-for="(line, index) in lines" :key="index">
      <h3 v-if="line.type === 'heading'">
        <template v-for="(part, i) in line.parts" :key="i">
          <template v-if="part.url">
            <a
              :href="part.url"
              :target="part.url.startsWith('#') ? undefined : '_blank'"
              rel="noopener noreferrer"
              >{{ isUrlExpanded(part.url) ? part.text : truncateUrl(part.text) }}</a
            >
            <button
              v-if="part.text.length > 30 && !isUrlExpanded(part.url) && !part.url.startsWith('#')"
              type="button"
              class="url-expand-btn"
              @click.prevent="toggleUrl(part.url)"
            >
              展开
            </button>
          </template>
          <template v-else>{{ part.text }}</template>
        </template>
      </h3>
      <p v-else-if="line.type === 'bullet'" class="bullet">
        <span>•</span>
        <span>
          <template v-for="(part, i) in line.parts" :key="i">
            <template v-if="part.url">
              <a
                :href="part.url"
                :target="part.url.startsWith('#') ? undefined : '_blank'"
                rel="noopener noreferrer"
                >{{ isUrlExpanded(part.url) ? part.text : truncateUrl(part.text) }}</a
              >
              <button
                v-if="
                  part.text.length > 30 && !isUrlExpanded(part.url) && !part.url.startsWith('#')
                "
                type="button"
                class="url-expand-btn"
                @click.prevent="toggleUrl(part.url)"
              >
                展开
              </button>
            </template>
            <template v-else>{{ part.text }}</template>
          </template>
        </span>
      </p>
      <p v-else-if="line.type === 'numbered'" class="numbered">
        <span>{{ line.marker }}</span>
        <span>
          <template v-for="(part, i) in line.parts" :key="i">
            <template v-if="part.url">
              <a
                :href="part.url"
                :target="part.url.startsWith('#') ? undefined : '_blank'"
                rel="noopener noreferrer"
                >{{ isUrlExpanded(part.url) ? part.text : truncateUrl(part.text) }}</a
              >
              <button
                v-if="
                  part.text.length > 30 && !isUrlExpanded(part.url) && !part.url.startsWith('#')
                "
                type="button"
                class="url-expand-btn"
                @click.prevent="toggleUrl(part.url)"
              >
                展开
              </button>
            </template>
            <template v-else>{{ part.text }}</template>
          </template>
        </span>
      </p>
      <p v-else-if="line.type === 'table'" class="table-row">
        <template v-for="(part, i) in line.parts" :key="i">
          <template v-if="part.url">
            <a
              :href="part.url"
              :target="part.url.startsWith('#') ? undefined : '_blank'"
              rel="noopener noreferrer"
              >{{ isUrlExpanded(part.url) ? part.text : truncateUrl(part.text) }}</a
            >
            <button
              v-if="part.text.length > 30 && !isUrlExpanded(part.url) && !part.url.startsWith('#')"
              type="button"
              class="url-expand-btn"
              @click.prevent="toggleUrl(part.url)"
            >
              展开
            </button>
          </template>
          <template v-else>{{ part.text }}</template>
        </template>
      </p>
      <div v-else-if="line.type === 'space'" class="space"></div>
      <p v-else>
        <template v-for="(part, i) in line.parts" :key="i">
          <template v-if="part.url">
            <a
              :href="part.url"
              :target="part.url.startsWith('#') ? undefined : '_blank'"
              rel="noopener noreferrer"
              >{{ isUrlExpanded(part.url) ? part.text : truncateUrl(part.text) }}</a
            >
            <button
              v-if="part.text.length > 30 && !isUrlExpanded(part.url) && !part.url.startsWith('#')"
              type="button"
              class="url-expand-btn"
              @click.prevent="toggleUrl(part.url)"
            >
              展开
            </button>
          </template>
          <template v-else>{{ part.text }}</template>
        </template>
      </p>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'

const props = defineProps({ content: { type: String, default: '' } })

const expandedUrls = ref<Set<string>>(new Set())

const truncateUrl = (url: string) => {
  if (url.length <= 30) return url
  const match = url.match(/^(https?:\/\/[^/]+)/)
  if (match) return match[1] + '/...'
  return url.slice(0, 25) + '…'
}

const isUrlExpanded = (url: string) => expandedUrls.value.has(url)

const toggleUrl = (url: string) => {
  if (expandedUrls.value.has(url)) {
    expandedUrls.value.delete(url)
  } else {
    expandedUrls.value.add(url)
  }
}

interface TextPart {
  text: string
  url?: string
}
type StructuredLine =
  | {
      type: 'heading' | 'bullet' | 'numbered' | 'table' | 'text'
      text: string
      parts: TextPart[]
      marker?: string
    }
  | { type: 'space'; text: string }

const cleanInline = (text: string): string =>
  text
    .replace(/^>\s*/, '')
    .replace(/\*\*|__/g, '')
    .replace(/`/g, '')
const linkify = (text: string): TextPart[] => {
  const parts: TextPart[] = []
  let cursor = 0
  const matcher = /https?:\/\/[^\s<>()]+|\[来源\s*\d+\]/g
  let match: RegExpExecArray | null
  while ((match = matcher.exec(text))) {
    if (match.index > cursor) parts.push({ text: text.slice(cursor, match.index) })
    const clean = match[0].replace(/[，。；、,.;!?！？]+$/, '')
    const citation = clean.match(/^\[来源\s*(\d+)\]$/)
    if (!citation) {
      parts.push({ text: clean, url: clean })
    }
    if (clean.length < match[0].length) parts.push({ text: match[0].slice(clean.length) })
    cursor = match.index + match[0].length
  }
  if (cursor < text.length) parts.push({ text: text.slice(cursor) })
  return parts.length ? parts : [{ text }]
}
const lines = computed<StructuredLine[]>(() =>
  props.content.split(/\r?\n/).map((raw) => {
    const text = raw.trim()
    if (!text) return { type: 'space', text: '' }
    if (/^[-*_]{2,}$/.test(text) || /^\|?[\s|:-]+\|?$/.test(text))
      return { type: 'space', text: '' }
    const heading = text.match(/^#{1,6}\s*(.+)$/)
    if (heading) {
      const value = cleanInline(heading[1])
      return { type: 'heading', text: value, parts: linkify(value) }
    }
    const bullet = text.match(/^[-*•◆]\s*(.+)$/)
    if (bullet) {
      const value = cleanInline(bullet[1])
      return { type: 'bullet', text: value, parts: linkify(value) }
    }
    const numbered = text.match(/^(\d+[.、])\s*(.+)$/)
    if (numbered) {
      const value = cleanInline(numbered[2])
      return { type: 'numbered', marker: numbered[1], text: value, parts: linkify(value) }
    }
    if (text.startsWith('|') && text.endsWith('|')) {
      const value = text
        .slice(1, -1)
        .split('|')
        .map((cell) => cleanInline(cell.trim()))
        .join(' ｜ ')
      return { type: 'table', text: value, parts: linkify(value) }
    }
    const value = cleanInline(text)
    return { type: 'text', text: value, parts: linkify(value) }
  })
)
</script>

<style scoped>
.structured-text {
  font-size: 16px;
  line-height: 1.85;
  overflow-wrap: anywhere;
  word-break: break-word;
}
.structured-text p {
  margin: 0 0 10px;
}
.structured-text h3 {
  margin: 20px 0 9px;
  font-size: 19px;
}
.structured-text h3:first-child {
  margin-top: 0;
}
.bullet,
.numbered {
  display: grid;
  grid-template-columns: 22px 1fr;
  gap: 5px;
}
.bullet span {
  color: var(--coral);
  font-weight: 700;
}
.numbered span {
  color: var(--muted);
  font-weight: 700;
}
.table-row {
  padding: 9px 12px;
  margin-bottom: 2px !important;
  background: #f6f3ed;
  font-size: 14px;
}
.space {
  height: 7px;
}
.structured-text a {
  color: #b04e3e;
  text-decoration: underline;
  text-underline-offset: 3px;
  overflow-wrap: anywhere;
}
.url-expand-btn {
  display: inline-block;
  margin-left: 4px;
  padding: 0 6px;
  border: 1px solid #e0b0a8;
  border-radius: 3px;
  background: transparent;
  color: #b04e3e;
  font-size: 11px;
  line-height: 1.5;
  cursor: pointer;
  vertical-align: middle;
}
.url-expand-btn:hover {
  background: #f9efed;
}
.bullet > span:last-child,
.numbered > span:last-child {
  color: inherit;
  font-weight: inherit;
}
</style>
