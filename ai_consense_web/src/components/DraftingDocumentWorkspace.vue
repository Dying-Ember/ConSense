<script setup lang="ts">
import type { GraphNavigation } from '@/drafting/graph-navigation'
import { computed, defineAsyncComponent, nextTick, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import { draftingApi } from '@/api'
import type { DraftDocument, DraftDocumentBindings, DraftField, DraftPlanAction, DraftVariable, ExtractTrace } from '@/api/types'
import type { AppLocale } from '@/i18n'
import type { DraftValue } from '@/drafting/state'
import type { DocumentReading } from '@/drafting/document-reading'
const DraftingDocumentReview = defineAsyncComponent(() => import('./DraftingDocumentReview.vue').then(module => module.default))
const DraftingPdfPreview = defineAsyncComponent(() => import('./DraftingPdfPreview.vue').then(module => module.default))

const props = defineProps<{
  projectId: string; fileKey: string; document?: DraftDocument; locale: AppLocale;
  fields: DraftField[]; values: Record<string, DraftValue>; variables: DraftVariable[];
  actions: DraftPlanAction[]; fieldStates: Record<string, string>; dirtyKeys: string[];
  editing: boolean; dirty: boolean; immersive?: boolean; disabled?: boolean; trace?: ExtractTrace | null; graphNavigation?: GraphNavigation
}>()
const emit = defineEmits<{ input: [key: string]; file: [fileKey: string] }>()
const original = shallowRef<DocumentReading>(), result = shallowRef<DocumentReading>()
const sourceBindings = shallowRef<DraftDocumentBindings>(), resultBindings = shallowRef<DraftDocumentBindings>()
const loading = ref(false), error = ref('')
const pdfView = ref<'source' | 'result'>(), pdfUrl = ref(''), pdfError = ref(''), pdfLoading = ref(false)
const pdfPanel = ref<HTMLElement>(), pdfInvoker = ref<HTMLElement>()
const identity = computed(() => JSON.stringify([props.projectId, props.fileKey, props.document?.sourceSha256, props.document?.revisionId, props.document?.docxSha256, !!props.document?.stale]))
const t = (en: string, cn: string, hk = cn) => props.locale === 'en' ? en : props.locale === 'zh-Hant' ? hk : cn
let sequence = 0, pdfSequence = 0, alive = true
async function load() {
  const request = ++sequence, token = identity.value, project = props.projectId, file = props.fileKey, saved = props.document
  original.value = undefined; result.value = undefined; sourceBindings.value = undefined; resultBindings.value = undefined
  loading.value = true; error.value = ''; closePdf(false)
  const current = () => alive && request === sequence && token === identity.value
  try {
    const { convertDocumentReading } = await import('@/drafting/document-reading')
    const [source, savedResult] = await Promise.all([
      (async () => {
        const bundle = await draftingApi.templateBindings(project, file, saved?.stale ? undefined : saved?.sourceSha256)
        if (bundle.view !== 'source' || bundle.fileKey !== file || !bundle.sourceSha256 || !saved?.stale && saved?.sourceSha256 && saved.sourceSha256 !== bundle.sourceSha256) throw new Error(t('Template version changed. Reload the document.', '模板版本已变化，请重新读取文稿。', '模板版本已變化，請重新讀取文稿。'))
        const blob = await draftingApi.templateSource(project, file)
        return { reading: await convertDocumentReading(await blob.arrayBuffer(), bundle.sourceSha256), bundle }
      })(),
      (async () => {
        if (!saved?.generated || saved.stale || !saved.revisionId || !saved.docxSha256) return undefined
        const [bundle, blob] = await Promise.all([draftingApi.documentBindings(project, file, saved.revisionId, saved.docxSha256), draftingApi.documentSource(project, file, saved.revisionId)])
        if (bundle.view !== 'result' || bundle.fileKey !== file || bundle.revisionId !== saved.revisionId || bundle.docxSha256 !== saved.docxSha256 || saved.sourceSha256 && bundle.sourceSha256 !== saved.sourceSha256) throw new Error(t('Saved revision changed. Reload the document.', '已保存文稿版本已变化，请重新读取。', '已儲存文稿版本已變化，請重新讀取。'))
        return { reading: await convertDocumentReading(await blob.arrayBuffer(), saved.docxSha256), bundle }
      })()
    ])
    if (!current()) return
    if (savedResult && savedResult.bundle.sourceSha256 !== source.bundle.sourceSha256) throw new Error(t('The template and saved draft belong to different versions.', '模板和已保存文稿不属于同一版本。', '模板和已儲存文稿不屬於同一版本。'))
    original.value = source.reading; sourceBindings.value = source.bundle
    result.value = savedResult?.reading; resultBindings.value = savedResult?.bundle
  } catch (caught) { if (current()) error.value = caught instanceof Error ? caught.message : String(caught) }
  finally { if (current()) loading.value = false }
}
function closePdf(restore = true) {
  pdfSequence++; pdfView.value = undefined; pdfLoading.value = false; pdfError.value = ''
  if (pdfUrl.value) URL.revokeObjectURL(pdfUrl.value)
  pdfUrl.value = ''
  if (restore) void nextTick(() => pdfInvoker.value?.focus({ preventScroll: true }))
}
async function openPdf(view: 'source' | 'result') {
  if (props.disabled || props.dirty || view === 'result' && (!resultBindings.value || props.document?.stale)) return
  pdfInvoker.value = document.activeElement instanceof HTMLElement ? document.activeElement : undefined
  closePdf(false); pdfView.value = view; pdfLoading.value = true
  const request = ++pdfSequence, token = identity.value, project = props.projectId, file = props.fileKey, revision = props.document?.revisionId, hash = sourceBindings.value?.sourceSha256
  void nextTick(() => pdfPanel.value?.focus({ preventScroll: true }))
  try {
    const blob = view === 'source' ? await draftingApi.templatePreview(project, file, hash) : await draftingApi.previewDocument(project, file, revision)
    if (!alive || request !== pdfSequence || token !== identity.value) return
    pdfUrl.value = URL.createObjectURL(blob)
  } catch (caught) { if (request === pdfSequence) pdfError.value = caught instanceof Error ? caught.message : String(caught) }
  finally { if (request === pdfSequence) pdfLoading.value = false }
}
function pdfKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); closePdf(); return }
  if (event.key !== 'Tab' || !pdfPanel.value) return
  const controls = [...pdfPanel.value.querySelectorAll<HTMLElement>('button:not(:disabled), input, select, [tabindex="0"]')].filter(node => node.getClientRects().length)
  const first = controls[0], last = controls.at(-1)
  if (event.shiftKey && (document.activeElement === first || document.activeElement === pdfPanel.value)) { event.preventDefault(); last?.focus() }
  else if (!event.shiftKey && (document.activeElement === last || document.activeElement === pdfPanel.value)) { event.preventDefault(); first?.focus() }
}
watch(identity, load, { immediate: true })
onBeforeUnmount(() => { alive = false; sequence++; closePdf(false) })
</script>
<template>
  <section data-document-review-workspace class="review-workspace" :class="{ immersive }">
    <DraftingDocumentReview :project-id="projectId" :file-key="fileKey" :document="document" :original="original" :result="result" :source-bindings="sourceBindings" :result-bindings="resultBindings" :loading="loading" :error="error" :fields="fields" :values="values" :variables="variables" :actions="actions" :field-states="fieldStates" :dirty-keys="dirtyKeys" :locale="locale" :immersive="immersive" :disabled="disabled || dirty" :editing="editing" :trace="trace" :graph-navigation="graphNavigation" @input="!disabled && !dirty && emit('input', $event)" @file="!disabled && !dirty && emit('file', $event)" @retry="load" @pdf="openPdf"><slot /></DraftingDocumentReview>
    <Teleport to="body">
      <section v-if="pdfView" class="layout-check-backdrop" @click.self="closePdf">
        <div ref="pdfPanel" class="layout-check" role="dialog" aria-modal="true" :aria-label="t('PDF layout check', 'PDF 版式核对', 'PDF 版式核對')" tabindex="-1" @keydown="pdfKeydown">
          <header><strong>{{ fileKey }} · {{ pdfView === 'source' ? t('Original template', '原始模板', '原始模板') : t('Saved draft', '已保存文稿', '已儲存文稿') }} · PDF</strong><button type="button" class="btn" @click="closePdf">{{ t('Close layout check', '关闭版式核对', '關閉版式核對') }}</button></header>
          <p v-if="pdfLoading" role="status">{{ t('Loading PDF…', '正在读取 PDF…', '正在讀取 PDF…') }}</p>
          <p v-if="pdfError" role="alert">{{ pdfError }} <button type="button" class="btn" @click="openPdf(pdfView!)">{{ t('Retry', '重试', '重試') }}</button></p>
          <DraftingPdfPreview v-if="pdfUrl" :source="pdfUrl" :title="`${fileKey} · PDF`" :locale="locale" immersive />
        </div>
      </section>
    </Teleport>
  </section>
</template>
<style scoped>
.review-workspace { min-width: 0; min-height: 0; height: 100%; }
.review-workspace.immersive { display: flex; flex-direction: column; }
.layout-check-backdrop { position: fixed; inset: 0; z-index: 150; background: #142c3ce0; padding: 12px; display: grid; }
.layout-check { background: var(--surface); border-radius: 8px; padding: 12px; min-width: 0; min-height: 0; display: flex; flex-direction: column; gap: 10px; }
.layout-check header { display: flex; justify-content: space-between; align-items: center; gap: 12px; }
.layout-check :deep(.pdf-pages) { flex: 1; min-height: 0; }
</style>
