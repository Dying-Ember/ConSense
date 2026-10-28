<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, useId, watch } from 'vue'
import { draftingApi } from '@/api'
import { convertDocumentReading } from '@/drafting/document-reading'
import { bindWordReview, selectWordReviewRange, wordReviewRanges } from '@/drafting/word-source-selection'
import type { ReviewAnchor, ReviewReading, ReviewSpan } from '@/drafting/source-review-types'

const props = defineProps<{ projectId: string; reading: ReviewReading; locale: string; locations: string[]; locationRequest: number; spans?: ReviewSpan[] }>()
const emit = defineEmits<{ selection: [value: ReviewAnchor | undefined]; error: [value: string] }>()
const t = (zh: string, en: string) => props.locale === 'en' ? en : zh
const surface = ref<HTMLElement>(), html = ref(''), loading = ref(false), error = ref(''), boundCount = ref(0), warning = ref(false), locationNotice = ref('')
const highlightName = `consense-word-${useId().replace(/[^a-zA-Z0-9_-]/g, '')}`
let highlightStyle: HTMLStyleElement | undefined
let generation = 0, alive = true

function clearHighlights() {
  const doc = surface.value?.ownerDocument ?? highlightStyle?.ownerDocument
  doc?.defaultView?.CSS?.highlights?.delete(highlightName)
  highlightStyle?.remove(); highlightStyle = undefined
}
function showHighlights(ranges: Range[]): boolean {
  const doc = surface.value?.ownerDocument, view = doc?.defaultView
  const registry = view?.CSS?.highlights, Constructor = (view as (Window & { Highlight?: typeof Highlight }) | null | undefined)?.Highlight
  if (!doc || !registry || !Constructor) return false
  try {
    highlightStyle = doc.createElement('style')
    highlightStyle.textContent = `::highlight(${highlightName}) { background: #ffd65c; color: #111; }`
    doc.head.append(highlightStyle)
    registry.set(highlightName, new Constructor(...ranges))
    return true
  } catch { clearHighlights(); return false }
}

async function open() {
  const token = ++generation, source = props.reading, project = props.projectId
  clearHighlights(); loading.value = true; error.value = ''; html.value = ''; boundCount.value = 0; warning.value = false; locationNotice.value = ''
  emit('selection', undefined); emit('error', '')
  try {
    const blob = await draftingApi.reviewSource(project, String(source.id), source.sourceSha256)
    const converted = await convertDocumentReading(await blob.arrayBuffer(), source.sourceSha256)
    if (!alive || token !== generation || project !== props.projectId || source.id !== props.reading.id || source.sourceSha256 !== props.reading.sourceSha256) return
    html.value = converted.html
    warning.value = !!converted.warnings?.length || !converted.coverage?.complete
    await nextTick()
    if (!alive || token !== generation || !surface.value) return
    boundCount.value = bindWordReview(surface.value, converted, source)
    warning.value ||= boundCount.value < source.paragraphs.filter(paragraph => paragraph.text.trim()).length
    await locate()
  } catch (caught) {
    if (alive && token === generation) error.value = caught instanceof Error ? caught.message : String(caught)
  } finally { if (alive && token === generation) loading.value = false }
}
function capture() {
  const root = surface.value, selected = root?.ownerDocument.getSelection()
  if (!root || !selected || selected.rangeCount !== 1 || selected.isCollapsed) { emit('selection', undefined); emit('error', ''); return }
  try {
    const selectedRange = selected.getRangeAt(0), anchor = selectWordReviewRange(root, props.reading, selectedRange)
    emit('selection', anchor)
    emit('error', !anchor && (root.contains(selectedRange.startContainer) || root.contains(selectedRange.endContainer)) && selectedRange.toString().trim() ? t('所选文字尚未精确绑定当前 Word 原文，请改选可绑定的文字或切换 PDF 核对。', 'The selected text is not exactly bound to this Word source. Select a bound passage or use the PDF for review.') : '')
  }
  catch (caught) { emit('selection', undefined); emit('error', caught instanceof Error ? caught.message : String(caught)) }
}
function clear() { surface.value?.ownerDocument.getSelection()?.removeAllRanges(); emit('selection', undefined); emit('error', '') }
async function locate() {
  await nextTick()
  const root = surface.value
  if (!root || !html.value) return
  clearHighlights(); locationNotice.value = ''
  const locations = new Set(props.locations)
  const spans = props.spans ?? [], result = wordReviewRanges(root, props.reading, spans)
  for (const range of result.ranges) {
    const id = range.startContainer.parentElement?.closest<HTMLElement>('[data-native-id]')?.dataset.nativeId
    if (id) locations.add(id)
  }
  const paragraphs = Array.from(root.querySelectorAll<HTMLElement>('[data-native-id]'))
  for (const paragraph of paragraphs) paragraph.classList.toggle('word-source-location', locations.has(paragraph.dataset.nativeId ?? ''))
  const located = paragraphs.filter(paragraph => locations.has(paragraph.dataset.nativeId ?? ''))
  const highlighted = result.ranges.length > 0 && showHighlights(result.ranges)
  if (result.unresolved) locationNotice.value = t('部分字符范围未能精确核对，已显示可定位的关联段落；请核对原文或切换 PDF。', 'Some character ranges could not be verified. Available source paragraphs are shown; check the original or PDF.')
  else if (locations.size && !located.length && !result.ranges.length) locationNotice.value = t('未能定位这处 Word 原文，请切换 PDF 或重新选词。', 'This Word passage could not be located. Use the PDF or select the source again.')
  else if (located.length < locations.size) locationNotice.value = t('部分关联原文尚未定位，已显示可定位的段落。', 'Some linked passages could not be located. Available paragraphs are shown.')
  else if (result.ranges.length && !highlighted) locationNotice.value = t('当前浏览器不能高亮准确字符，已标示关联段落；请核对原文。', 'This browser cannot highlight exact characters. The linked paragraph is shown; check the source.')
  else if (located.length && !spans.length) locationNotice.value = t('已标示关联段落，尚未指定精确字符范围。', 'The linked paragraph is shown; no exact character range is specified.')
  const spanElement = result.ranges[0]?.startContainer.parentElement
  ;(located[0] ?? spanElement)?.scrollIntoView?.({ block: 'center' })
}
watch(() => [props.projectId, props.reading.id, props.reading.sourceSha256], () => { void open() }, { immediate: true })
watch(() => [props.locationRequest, props.locations.join('|'), JSON.stringify(props.spans ?? [])], () => { void locate() })
onBeforeUnmount(() => { alive = false; generation++; clearHighlights() })
defineExpose({ clear })
</script>

<template>
  <section class="word-source" data-word-source>
    <p class="word-source-note">{{ t('直接在 Word 原文中拖选文字，松开后可关联变量或设置修改范围。此阅读视图不代表 Word 的最终分页和排版。', 'Select text in the Word source, then link a variable or set an affected passage. This reading view does not reproduce final Word pagination or layout.') }}</p>
    <p v-if="loading" role="status">{{ t('正在读取 Word 原文…', 'Opening the Word source…') }}</p>
    <p v-if="error" role="alert">{{ error }} <button type="button" class="btn" @click="open">{{ t('重试', 'Retry') }}</button></p>
    <p v-if="!loading && !error && !boundCount" role="status">{{ t('当前 Word 没有可精确绑定的文字，请切换 PDF 核对。', 'No text in this Word reading can be bound exactly. Use the PDF for review.') }}</p>
    <p v-if="!loading && !error && warning" class="word-source-note" data-word-coverage-warning role="status">{{ t('部分内容或编号可能与原版排版不同，或部分文字尚未精确绑定；这些文字不可作为修改范围，请切换 PDF 核对。', 'Some content or numbering may differ from the original layout, or some text is not exactly bound. Unbound text is unavailable for amendment; check the PDF.') }}</p>
    <p v-if="locationNotice" class="word-source-note" data-word-location-notice role="status">{{ locationNotice }}</p>
    <div v-show="!!html" ref="surface" class="word-source-surface" data-word-source-surface :aria-label="reading.fileName" tabindex="0" lang="en" @mouseup="capture" @keyup="capture" v-html="html" />
  </section>
</template>

<style scoped>
.word-source-note { color: var(--muted); font-size: 13px; line-height: 1.5; }
.word-source-surface { max-height: 68vh; overflow: auto; background: white; color: #222; padding: 24px; border: 1px solid var(--line); border-radius: var(--radius-sm); font: 14px/1.6 Georgia, 'Times New Roman', serif; user-select: text; }
.word-source-surface :deep(table) { border-collapse: collapse; max-width: 100%; }
.word-source-surface :deep(td), .word-source-surface :deep(th) { border: 1px solid #ccc; padding: 4px 8px; vertical-align: top; }
.word-source-surface :deep(p) { white-space: pre-wrap; overflow-wrap: anywhere; }
.word-source-surface :deep(.word-source-location) { outline: 2px solid var(--accent); outline-offset: 2px; }
</style>
