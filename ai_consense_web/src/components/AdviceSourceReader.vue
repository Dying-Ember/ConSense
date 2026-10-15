<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { getDocument, GlobalWorkerOptions } from 'pdfjs-dist'
import pdfWorkerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?url'
import DraftingPdfPreview from '@/components/DraftingPdfPreview.vue'
import type { Citation } from '@/api/types'
import type { AppLocale } from '@/i18n'
import type { NativeDocxPosition } from '@/api/canonicalEvidence'
import { loadAdviceOriginal } from '@/advice/sourceOriginal'
import { readAdviceDocx } from '@/advice/docxSourceReading'

const props = defineProps<{ projectId: string; citation: Citation; scope?: string; sourceModule?: 'advisory' | 'vetting' }>()
const { locale } = useI18n()
const status = ref<'loading' | 'ready' | 'error'>('loading'), error = ref(''), warnings = ref<string[]>([])
const sourceUrl = ref(''), filename = ref(''), format = ref(''), html = ref(''), pages = ref<number[]>([])
const page = ref(1), reader = ref<HTMLElement>(), revision = ref(0)
let epoch = 0, abort: AbortController | undefined, pendingPdf: ReturnType<typeof getDocument> | undefined
GlobalWorkerOptions.workerSrc = pdfWorkerUrl
const copy = (en: string, hans: string, hant: string) => locale.value.startsWith('en') ? en : locale.value === 'zh-Hant' ? hant : hans
function release() {
  abort?.abort(); abort = undefined
  if (pendingPdf) void pendingPdf.destroy().catch(() => {})
  pendingPdf = undefined
  if (sourceUrl.value) URL.revokeObjectURL(sourceUrl.value)
  sourceUrl.value = ''; html.value = ''; pages.value = []; warnings.value = []; error.value = ''
}
function failureText(failure: unknown) {
  const reason = failure instanceof Error ? failure.message : String(failure)
  if (/SOURCE_ORIGINAL_BYTES_CHANGED|SOURCE_STALE|SUPERSEDED|REVISION|ROLE_CHANGED|BINDING.*CHANGED/.test(reason)) return copy('The source file or its registered identity changed or was superseded. This historical location cannot open a newer source; query the current source again.', '源文件或登记身份已变化或被替代；不能用这个历史位置打开新版，请按当前来源重新查询。', '原始檔案或登記身份已變更或被替代；不能用此歷史位置開啟新版，請按目前來源重新查詢。')
  if (/IDENTITY|ROLE_UNCONFIRMED|ADDRESS|HEADER|BYTES_MISMATCH/.test(reason)) return copy('The source identity or file bytes could not be verified. Check the document registration before retrying.', '未能核实来源身份或文件字节，请核对文件登记后重试。', '未能核實來源身份或檔案位元組，請核對檔案登記後重試。')
  return copy('The complete original could not be loaded. Check source availability and retry; the citation has been retained.', '未能加载完整原件，请核对来源是否可用后重试；原引用已保留。', '未能載入完整原件，請核對來源是否可用後重試；原引用已保留。')
}
async function load() {
  const current = ++epoch; revision.value++
  release(); status.value = 'loading'; abort = new AbortController()
  try {
    const original = await loadAdviceOriginal(props.projectId, props.citation, props.scope ?? 'fullset', abort.signal, props.sourceModule ?? 'advisory')
    if (current !== epoch) return
    filename.value = original.filename; format.value = original.type
    sourceUrl.value = URL.createObjectURL(new Blob([original.bytes], { type: original.type === 'pdf' ? 'application/pdf' : original.type === 'docx' ? 'application/vnd.openxmlformats-officedocument.wordprocessingml.document' : 'application/octet-stream' }))
    if (original.type === 'pdf') {
      const task = getDocument({ data: new Uint8Array(original.bytes.slice(0)) }); pendingPdf = task
      const document = await task.promise
      if (current !== epoch) { void document.destroy().catch(() => {}); return }
      const physical = original.pages.filter(value => Number.isSafeInteger(value) && value > 0 && value <= document.numPages)
      pages.value = [...new Set(physical)]; page.value = pages.value[0] ?? 1
      if (!physical.length || physical.length !== original.pages.length) warnings.value.push(copy('The citation physical page could not be verified. The complete original opens at its first valid page.', '引用的物理页无法核实；完整原件打开到首个有效页面。', '引用的實體頁無法核實；完整原件開啟到首個有效頁面。'))
      // shortcut: Physical pages only; add highlights when original crop/rotation geometry is verified.
      warnings.value.push(copy('Located by the original physical page. Precise highlighting is unavailable because the original crop/rotation coordinate mapping is not verified.', '按原件物理页定位；原始裁切／旋转坐标映射尚未核实，暂不显示精确高亮。', '按原件實體頁定位；原始裁切／旋轉座標映射尚未核實，暫不顯示精確標示。'))
      await task.destroy(); if (pendingPdf === task) pendingPdf = undefined
    } else if (original.type === 'docx') {
      const positions = [...(original.nativePositions ?? []), ...original.pointers.flatMap(pointer => pointer.parts.flatMap(part => part.sourcePosition ? [part.sourcePosition] : []))]
        .filter((position): position is NativeDocxPosition => position.kind === 'docx_native')
      const reading = await readAdviceDocx(original.bytes, original.sha256, positions)
      if (current !== epoch) return
      html.value = reading.html; warnings.value = reading.warnings
      warnings.value.push(copy('DOCX is read from the original package with paragraphs, tables and other parts. This view has no Word page numbers or verified printed layout.', 'DOCX 从原始文件包读取段落、整表和其他部件；此视图不提供 Word 页码或已验证的打印版式。', 'DOCX 從原始檔案包讀取段落、整表及其他部件；此視圖不提供 Word 頁碼或已驗證的列印版式。'))
      if (!positions.length || reading.missingTargets.length) warnings.value.push(copy('Some cited native locations are unavailable. The whole source remains readable; no similar text was used to replace them.', '部分引用原生位置不可用；仍可阅读完整源文，未用相似文本代替定位。', '部分引用原生位置不可用；仍可閱讀完整源文，未用相似文字代替定位。'))
      status.value = 'ready'
      await nextTick()
      if (current !== epoch) return
      const keys = new Set(reading.targetKeys)
      const target = Array.from(reader.value?.querySelectorAll<HTMLElement>('[data-advice-target]') ?? []).find(node => keys.has(node.dataset.adviceTarget!))
      const selected = target?.closest<HTMLElement>('p,tr,table,td,th') ?? target
      selected?.classList.add('advice-source-selected'); selected?.scrollIntoView?.({ block: 'center' })
      return
    } else warnings.value.push(copy('This original file type has no verified browser reader or locator. Download the exact source for review.', '此原件格式尚无已验证的浏览器阅读与定位能力，请下载这份原件核查。', '此原件格式尚無已驗證的瀏覽器閱讀與定位能力，請下載這份原件核查。'))
    if (current === epoch) status.value = 'ready'
  } catch (caught) {
    if (current !== epoch) return
    error.value = failureText(caught); status.value = 'error'
    if (sourceUrl.value) URL.revokeObjectURL(sourceUrl.value)
    sourceUrl.value = ''
  }
}
watch(() => [props.projectId, props.citation, props.scope, props.sourceModule], () => { void load() }, { immediate: true })
onBeforeUnmount(() => { ++epoch; release() })
</script>

<template>
  <section class="advice-source-reader" :aria-label="copy('Original source', '源文件', '原始檔案')" :aria-busy="status === 'loading'">
    <p v-if="status === 'loading'" role="status">{{ copy('Loading and checking original source…', '正在加载并核对源文件…', '正在載入並核對原始檔案…') }}</p>
    <div v-else-if="status === 'error'" role="alert">
      <p>{{ copy('The cited source is unavailable or its identity has changed. It has not been replaced by a newer file.', '引用源文件不可用或身份已变化，未替换为新版文件。', '引用原始檔案不可用或身份已變更，未替換為新版檔案。') }}</p>
      <p class="source-error">{{ error }}</p><button type="button" class="btn" @click="load">{{ copy('Retry', '重试', '重試') }}</button>
    </div>
    <template v-else>
      <a v-if="sourceUrl" :href="sourceUrl" :download="filename" class="btn">{{ copy('Download this original', '下载这份原件', '下載這份原件') }}</a>
      <details v-if="warnings.length" class="source-limitations" open><summary>{{ copy('Location and reading limits', '定位与阅读界限', '定位與閱讀界限') }}</summary><ul><li v-for="warning in warnings" :key="warning">{{ warning }}</li></ul></details>
      <template v-if="format === 'pdf'">
        <nav v-if="pages.length > 1" class="cited-pages" :aria-label="copy('Cited pages', '引用页', '引用頁')"><button v-for="physical in pages" :key="physical" class="btn" type="button" :aria-pressed="page === physical" @click="page = physical">{{ copy('Physical page', '物理页', '實體頁') }} {{ physical }}</button></nav>
        <DraftingPdfPreview :key="`${revision}:${page}`" :source="sourceUrl" :title="filename" :locale="locale as AppLocale" :initial-page="page" />
      </template>
      <div v-else-if="format === 'docx'" ref="reader" class="docx-source-flow" tabindex="0" v-html="html"></div>
    </template>
  </section>
</template>

<style scoped>
.advice-source-reader { min-width: 0; }
.source-error { overflow-wrap: anywhere; }
.source-limitations { margin: 10px 0; font-size: 12px; }
.source-limitations summary { cursor: pointer; }
.cited-pages { display: flex; gap: 6px; flex-wrap: wrap; margin: 8px 0; }
.docx-source-flow { height: min(72vh, 900px); overflow: auto; padding: 18px; background: #fff; color: #172b4d; border: 1px solid var(--line, #cbd5e1); border-radius: 6px; }
.docx-source-flow :deep(p) { white-space: pre-wrap; }
.docx-source-flow :deep(img) { max-width: 100%; height: auto; }
.docx-source-flow :deep(table) { width: 100%; border-collapse: collapse; margin: 10px 0; }
.docx-source-flow :deep(td), .docx-source-flow :deep(th) { border: 1px solid #cbd5e1; padding: 6px; vertical-align: top; }
.docx-source-flow :deep(.advice-source-selected) { background: #fef3c7; outline: 2px solid #d97706; outline-offset: 2px; }
.docx-source-flow :deep(.source-object-warning) { display: inline-block; color: #92400e; background: #fffbeb; padding: 3px; }
</style>
