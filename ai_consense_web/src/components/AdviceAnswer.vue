<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { answerParts, renderAnswerFlowchart } from '@/advice/answerFlowchart'

const props = defineProps<{ content: string }>()
const { locale } = useI18n()
const parts = ref<{ kind: 'text' | 'flowchart'; source: string; svg?: string; failed?: boolean }[]>([])
let epoch = 0
watch(() => props.content, async content => {
  const current = ++epoch
  parts.value = answerParts(content)
  const pending = parts.value
  for (const part of pending) {
    if (part.kind !== 'flowchart') continue
    try {
      const svg = await renderAnswerFlowchart(part.source)
      if (current !== epoch) return
      part.svg = svg
    } catch {
      if (current !== epoch) return
      part.failed = true
    }
  }
}, { immediate: true })
onBeforeUnmount(() => { ++epoch })

function copy(en: string, hans: string, hant: string) {
  return locale.value.startsWith('en') ? en : locale.value === 'zh-Hant' ? hant : hans
}
</script>

<template>
  <div class="advice-answer">
    <template v-for="(part, index) in parts" :key="index">
      <p v-if="part.kind === 'text'">{{ part.source }}</p>
      <figure v-else class="answer-flowchart">
        <div v-if="part.svg" class="flowchart-graphic" role="img"
          :aria-label="copy('Answer flowchart', '回答流程图', '回答流程圖')" v-html="part.svg"></div>
        <p v-else-if="part.failed" class="warning-note" role="status">{{ copy('The flowchart could not be displayed. The original diagram text is retained below.', '流程图无法显示，原始图表文本保留如下。', '流程圖無法顯示，原始圖表文字保留如下。') }}</p>
        <p v-else role="status">{{ copy('Drawing flowchart…', '正在绘制流程图…', '正在繪製流程圖…') }}</p>
        <details :open="part.failed || undefined">
          <summary>{{ copy('Diagram source', '图表原文', '圖表原文') }}</summary>
          <pre>{{ part.source }}</pre>
        </details>
      </figure>
    </template>
  </div>
</template>

<style scoped>
.advice-answer { min-width: 0; }
.advice-answer > p { white-space: pre-wrap; overflow-wrap: anywhere; }
.answer-flowchart { margin: 12px 0; }
.flowchart-graphic { overflow: auto; max-width: 100%; background: #fff; color: #172b4d; padding: 10px; border: 1px solid #cbd5e1; border-radius: 6px; }
.flowchart-graphic :deep(svg) { display: block; min-width: 360px; max-width: 100%; height: auto; font: 14px sans-serif; }
.flowchart-graphic :deep(text) { fill: #172b4d; }
.flowchart-graphic :deep(.node text) { text-anchor: middle; }
.flowchart-graphic :deep(.node rect), .flowchart-graphic :deep(.node polygon), .flowchart-graphic :deep(.node circle), .flowchart-graphic :deep(.node ellipse), .flowchart-graphic :deep(.cluster rect) { fill: #f1f5f9; stroke: #475569; stroke-width: 1.5px; }
.flowchart-graphic :deep(.flowchart-link) { fill: none; stroke: #475569; stroke-width: 1.5px; }
.flowchart-graphic :deep(marker path) { fill: #475569; stroke: #475569; }
.flowchart-graphic :deep(.edgeLabel rect), .flowchart-graphic :deep(.labelBkg), .flowchart-graphic :deep(.background) { fill: #fff; }
details { margin-top: 8px; }
summary { cursor: pointer; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; font-size: 12px; }
</style>
