<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAppStore } from '@/stores/app'
import { inspectionApi, inspectionJson, inspectionOriginalUrl, type InspectionCatalog, type InspectionDocument, type InspectionPage, type InspectionRecord, type InspectionTask } from '@/api/inspection'
import { inspectionWords, inspectionReason, inspectionOcrReviewWords, inspectionSourceUnitWords } from '@/i18n/inspection'
import AppIcon from '@/components/AppIcon.vue'
import InspectionOcrDiagnostics from '@/components/InspectionOcrDiagnostics.vue'
import type { InspectionOcrExperiment } from '@/api/inspection-ocr'

const store = useAppStore(), route = useRoute(), router = useRouter()
const w = computed(() => inspectionWords(store.locale))
const sw = computed(() => inspectionSourceUnitWords(store.locale))
const ow = computed(() => inspectionOcrReviewWords(store.locale))
const tab = ref<'chunks' | 'ocr' | 'retrieval'>('chunks')
const catalog = ref<InspectionCatalog>({ datasets: [], runs: [] })
const datasetId = ref(''), runId = ref(''), taskId = ref(''), documentId = ref(''), search = ref(''), stage = ref('rerank')
const role = ref(''), physicalPage = ref(1)
const documents = ref<InspectionDocument[]>([]), tasks = ref<InspectionTask[]>([])
const page = ref(1), pageSize = ref(30), list = ref<InspectionPage>({ items: [], total: 0, page: 1, pageSize: 30 })
const selected = ref<InspectionRecord | null>(null), loading = ref(false), detailLoading = ref(false), error = ref(''), catalogError = ref(false)
let requestId = 0, detailId = 0, datasetRequest = 0, runRequest = 0, disposed = false
const dataset = computed(() => catalog.value.datasets.find(x => x.id === datasetId.value))
const currentRun = computed(() => catalog.value.runs.find(x => x.id === runId.value))
const snapshotBoundary = computed(() => (tab.value === 'retrieval' ? currentRun.value?.provenance : dataset.value?.provenance) as InspectionRecord | undefined)
const currentTask = computed(() => tasks.value.find(x => x.id === taskId.value))
const rankingStages = computed(() => ['dense', 'bm25', 'rrf', 'rerank', 'selected', ...(currentTask.value?.stages?.includes('source_units') ? ['source_units'] : []), 'sent'])
const sourceUnit = computed<InspectionRecord | null>(() => selected.value?.sourceUnit && typeof selected.value.sourceUnit === 'object' ? selected.value.sourceUnit as InspectionRecord : null)
const sourceUnitMembers = computed<InspectionRecord[]>(() => Array.isArray(sourceUnit.value?.members) ? sourceUnit.value.members.filter((x: unknown) => x !== null && typeof x === 'object') as InspectionRecord[] : [])
const selectedDocument = computed(() => documents.value.find(x => x.id === documentId.value))
const wordWithoutPages = computed(() => tab.value === 'ocr' && /word|docx?/i.test(String(selectedDocument.value?.contentType ?? selectedDocument.value?.fileName ?? '')) && !(Number(selectedDocument.value?.pageCount) > 0))
const roles = computed(() => [...new Set(documents.value.map(x => String(x.role ?? '')).filter(Boolean))])
const lastPage = computed(() => Math.max(1, Math.ceil(list.value.total / pageSize.value)))
const chunk = computed<InspectionRecord | null>(() => {
  if (!selected.value) return null
  if (tab.value === 'ocr') return { documentId: selectedDocument.value?.id, fileName: selectedDocument.value?.fileName, sourceHash: selectedDocument.value?.sourceHash, role: selectedDocument.value?.role, sourceQualityHash: selectedDocument.value?.sourceQualityHash, ...selected.value }
  return (selected.value.chunk as InspectionRecord | undefined) ?? selected.value
})
const nativeParts = computed(() => {
  const parts = chunk.value?.parts
  return Array.isArray(parts) ? parts.filter((p: InspectionRecord) => p.table || p.tableSlice) : []
})
const textRevisions = computed<InspectionRecord[]>(() => Array.isArray(selected.value?.textRevisions) ? selected.value!.textRevisions.filter((x: unknown) => x !== null && typeof x === 'object') as InspectionRecord[] : [])
const ocrReview = computed<InspectionRecord | null>(() => {
  const value = selected.value?.ocrReview
  return value && typeof value === 'object' && !Array.isArray(value) ? value as InspectionRecord : null
})
const ocrExperiments = computed<InspectionOcrExperiment[]>(() => Array.isArray(ocrReview.value?.experiments) ? ocrReview.value!.experiments.filter((x: unknown) => x && typeof x === 'object') as InspectionOcrExperiment[] : [])
function reviewImage(value: unknown): string | null {
  return value && typeof value === 'object' ? inspectionOriginalUrl((value as InspectionRecord).assetUrl) : null
}
const ocrReviewReason = computed(() => ocrReview.value?.unavailableReason === 'ocr_review_source_or_artifact_binding_invalid' ? ow.value.invalid : ocrReview.value?.unavailableReason === 'ocr_review_manifest_not_registered' ? ow.value.notRegistered : ocrReview.value?.unavailableReason === 'ocr_review_not_recorded_for_dataset' ? ow.value.notRecordedDataset : ocrReview.value?.unavailableReason === 'ocr_review_not_recorded_for_page' ? ow.value.notRecorded : ow.value.unavailable)
const detailText = computed(() => {
  const c = chunk.value
  return typeof c?.content === 'string' ? c.content : typeof c?.text === 'string' ? c.text : null
})
const originalUrl = computed(() => inspectionOriginalUrl(selected.value?.originalUrl ?? chunk.value?.originalUrl ?? (tab.value === 'ocr' ? selectedDocument.value?.originalUrl : null)))
const documentOriginalUrl = computed(() => {
  if (!originalUrl.value || tab.value !== 'ocr') return originalUrl.value
  const doc = documents.value.find(x => x.id === documentId.value)
  if (!/pdf/i.test(String(doc?.contentType ?? doc?.fileName ?? '')) || !Number.isInteger(Number(selected.value?.pageNo))) return originalUrl.value
  const u = new URL(originalUrl.value); u.hash = `page=${Number(selected.value?.pageNo)}`; return u.href
})
const pageIsOcr = computed(() => Array.isArray(selected.value?.extractionSources) && selected.value!.extractionSources.some(x => /ocr/i.test(String(x))))
function text(row: InspectionRecord, ...names: string[]): string {
  for (const name of names) { const value = row[name]; if (value !== null && value !== undefined && value !== '') return String(value) }
  return '—'
}
function summary(row: InspectionRecord): string { return text(row, 'contentPreview', 'preview', 'content', 'text').slice(0, 180) }
function rankingDocument(row: InspectionRecord): string {
  const explicit = text(row, 'fileName', 'file'), id = text(row, 'documentId')
  if (explicit !== '—') return explicit
  const doc = documents.value.find(item => String(item.id) === id)
  return doc ? `${doc.fileName} · ${id}` : id
}
function rowId(row: InspectionRecord): string { return text(row, 'id', 'chunkId', 'parentId', 'windowId') }
function unitRecord(row: InspectionRecord): InspectionRecord { return row.sourceUnit && typeof row.sourceUnit === 'object' ? row.sourceUnit as InspectionRecord : {} }
function unitMemberCount(row: InspectionRecord): number { const members = unitRecord(row).members; return Array.isArray(members) ? members.length : 0 }
function empty() { return { items: [], total: 0, page: page.value, pageSize: pageSize.value } }
function humanReason(value: unknown) { return inspectionReason(store.locale, value) }
function unavailableDataset() {
  if (tab.value === 'retrieval' || dataset.value?.status !== 'unavailable') return false
  datasetRequest++; requestId++; loading.value = false
  documents.value = []; documentId.value = ''; role.value = ''; clearSelection(); error.value = ''
  list.value = { ...empty(), status: 'unavailable', unavailableReason: dataset.value.unavailableReason || 'recorded_artifact_missing_or_identity_failed' }
  return true
}
function defaultOcrDocument() {
  const candidates = documents.value.filter(d => /pdf/i.test(String(d.contentType ?? d.fileName ?? '')) && Number(d.pageCount) > 0)
  return [...candidates].sort((a,b) => {
    const score = (d: InspectionDocument) => (/\bscan(?:ned)?\b/i.test(d.fileName) ? 4 : 0) + (d.ocrUsed || Number(d.ocrPageCount) > 0 ? 2 : 0)
    return score(b) - score(a)
  })[0]?.id ?? documents.value[0]?.id ?? ''
}
function clearSelection() { detailId++; selected.value = null; detailLoading.value = false }
function changeTab(value: typeof tab.value) { tab.value = value; if (value === 'ocr' && (!/pdf/i.test(String(selectedDocument.value?.contentType ?? selectedDocument.value?.fileName ?? '')) || !(Number(selectedDocument.value?.pageCount) > 0))) documentId.value = defaultOcrDocument(); page.value = 1; clearSelection(); void loadList() }
async function refreshCatalog() {
  catalogError.value = false
  try {
    const data = await inspectionApi.catalog()
    if (disposed) return
    catalog.value = data
    const oldDataset = datasetId.value, oldRun = runId.value
    const queryDataset = typeof route.query.dataset === 'string' ? route.query.dataset : ''
    const queryRun = typeof route.query.run === 'string' ? route.query.run : ''
    const requestedRun = data.runs.find(x => x.id === queryRun)
    const requestedRunDataset = requestedRun?.datasetId
    datasetId.value = data.datasets.some(x => x.id === queryDataset) ? queryDataset : requestedRunDataset && data.datasets.some(x => x.id === requestedRunDataset) ? requestedRunDataset : data.datasets.some(x => x.id === datasetId.value && x.status === 'available') ? datasetId.value : data.datasets.find(x => x.status === 'available')?.id ?? data.datasets[0]?.id ?? ''
    const matchingRuns = data.runs.filter(x => x.datasetId === datasetId.value)
    runId.value = matchingRuns.some(x => x.id === queryRun) ? queryRun : matchingRuns.some(x => x.id === oldRun) ? oldRun : matchingRuns[0]?.id ?? ''
    if (oldDataset === datasetId.value && oldRun === runId.value) void loadList()
    unavailableDataset()
    if (!datasetId.value && tab.value !== 'retrieval') list.value = empty()
  } catch { if (!disposed) catalogError.value = true }
}
async function loadDocuments() {
  const n = ++datasetRequest, id = datasetId.value
  documents.value = []; documentId.value = ''; role.value = ''; page.value = 1; clearSelection()
  if (!id) { list.value = empty(); return }
  if (unavailableDataset()) return
  try {
    const data = await inspectionApi.documents(id)
    if (disposed || n !== datasetRequest || dataset.value?.status === 'unavailable') return
    const all = [...(data.items ?? [])]
    for (let p = 2; all.length < data.total; p++) {
      const next = await inspectionApi.documents(id, p)
      if (disposed || n !== datasetRequest) return
      if (!next.items?.length) throw new Error('Document pagination ended before its recorded total')
      all.push(...next.items)
    }
    documents.value = all
    if (tab.value === 'ocr') documentId.value = defaultOcrDocument()
    await loadList()
  } catch { if (n === datasetRequest && !disposed) { error.value = w.value.unavailableApi; list.value = empty() } }
}
async function loadTasks() {
  const n = ++runRequest, id = runId.value
  tasks.value = []; taskId.value = ''; clearSelection()
  if (!id) { if (tab.value === 'retrieval') list.value = empty(); return }
  try {
    const data = await inspectionApi.tasks(id)
    if (disposed || n !== runRequest) return
    const all = [...(data.items ?? [])]
    for (let p = 2; all.length < data.total; p++) {
      const next = await inspectionApi.tasks(id, p)
      if (disposed || n !== runRequest) return
      if (!next.items?.length) throw new Error('Task pagination ended before its recorded total')
      all.push(...next.items)
    }
    tasks.value = all; taskId.value = tasks.value[0]?.id ?? ''
    if (tab.value === 'retrieval') await loadList()
  } catch { if (n === runRequest && !disposed) { error.value = w.value.unavailableApi; if (tab.value === 'retrieval') list.value = empty() } }
}
async function loadList() {
  const n = ++requestId; loading.value = true; error.value = ''; clearSelection()
  try {
    if (unavailableDataset()) return
    let result: InspectionPage
    if (tab.value === 'chunks' && datasetId.value) result = await inspectionApi.chunks(datasetId.value, page.value, pageSize.value, search.value.trim(), documentId.value, role.value)
    else if (tab.value === 'ocr' && datasetId.value && documentId.value) result = await inspectionApi.pages(datasetId.value, documentId.value, page.value, pageSize.value)
    else if (tab.value === 'retrieval' && runId.value && taskId.value) result = await inspectionApi.ranking(runId.value, taskId.value, stage.value, page.value, pageSize.value)
    else result = empty()
    if (!disposed && n === requestId) list.value = { ...result, items: result.items ?? [], total: result.total ?? 0 }
  } catch { if (!disposed && n === requestId) { error.value = w.value.unavailableApi; list.value = empty() } }
  finally { if (!disposed && n === requestId) loading.value = false }
}
async function inspect(row: InspectionRecord) {
  if (unavailableDataset()) return
  const n = ++detailId; detailLoading.value = true; selected.value = null
  try {
    let result: InspectionRecord
    if (tab.value === 'ocr') result = await inspectionApi.page(datasetId.value, documentId.value, Number(row.pageNo))
    else if (tab.value === 'chunks') result = await inspectionApi.chunk(datasetId.value, rowId(row))
    else if (row.sourceUnit || row.chunk || row.content || row.text) result = row
    else {
      const boundDataset = currentRun.value?.datasetId
      if (!boundDataset) result = { ...row, unavailableReason: w.value.unrecorded }
      else result = { ...await inspectionApi.chunk(boundDataset, rowId(row)), rankingRecord: row }
    }
    if (!disposed && n === detailId) selected.value = result
  } catch { if (!disposed && n === detailId) selected.value = { ...row, unavailableReason: w.value.unrecorded } }
  finally { if (!disposed && n === detailId) detailLoading.value = false }
}
async function openPhysicalPage() {
  const number = Number(physicalPage.value), count = Number(selectedDocument.value?.pageCount)
  if (!Number.isInteger(number) || number < 1 || !(count > 0) || number > count || !documentId.value) return
  await inspect({ pageNo: number })
}
watch(documentId, () => { physicalPage.value = 1 })
watch(taskId, () => { if (!rankingStages.value.includes(stage.value)) stage.value = 'rerank' })
function goPage(p: number) { page.value = p; void loadList() }
function filterChanged() { page.value = 1; void loadList() }
watch(datasetId, () => {
  if (currentRun.value?.datasetId !== datasetId.value) runId.value = catalog.value.runs.find(x => x.datasetId === datasetId.value)?.id ?? ''
  void loadDocuments()
})
watch(runId, () => {
  const boundDataset = currentRun.value?.datasetId
  if (boundDataset && catalog.value.datasets.some(x => x.id === boundDataset) && datasetId.value !== boundDataset) datasetId.value = boundDataset
  void loadTasks()
})
watch([datasetId, runId], () => { void router.replace({ query: { ...route.query, dataset: datasetId.value || undefined, run: runId.value || undefined } }) })
onMounted(() => { void refreshCatalog() })
onBeforeUnmount(() => { disposed = true; requestId++; detailId++; datasetRequest++; runRequest++ })
</script>

<template>
  <section class="inspection-view">
    <div class="inspection-heading"><div><h2>{{ w.title }}</h2><p class="muted">{{ w.intro }}</p></div><button class="btn" @click="refreshCatalog"><AppIcon name="refresh" />{{ w.refresh }}</button></div>
    <div v-if="catalogError" class="inspection-error" role="alert">{{ w.unavailableApi }} <button class="btn" @click="refreshCatalog">{{ w.retry }}</button></div>
    <div class="inspection-tabs" role="tablist"><button v-for="name in (['chunks', 'ocr', 'retrieval'] as const)" :key="name" role="tab" :aria-selected="tab === name" :class="{ active: tab === name }" @click="changeTab(name)">{{ w[name] }}</button><span class="tag">{{ w.readOnly }}</span></div>
    <div class="inspection-toolbar">
      <template v-if="tab !== 'retrieval'">
        <label>{{ w.dataset }}<select v-model="datasetId"><option v-for="item in catalog.datasets" :key="item.id" :value="item.id">{{ item.label }} · {{ item.projectId || item.id }}</option></select></label>
        <label>{{ w.document }}<select v-model="documentId" @change="filterChanged"><option v-if="tab === 'chunks'" value="">{{ w.all }}</option><option v-for="doc in documents" :key="doc.id" :value="doc.id">{{ doc.fileName }} · {{ doc.id }}</option></select></label>
        <label v-if="tab === 'ocr' && Number(selectedDocument?.pageCount) > 0">{{ w.pageNo }}<input v-model.number="physicalPage" type="number" min="1" :max="Number(selectedDocument?.pageCount)" /></label><button v-if="tab === 'ocr' && Number(selectedDocument?.pageCount) > 0" class="btn" :disabled="detailLoading || physicalPage < 1 || physicalPage > Number(selectedDocument?.pageCount)" @click="openPhysicalPage">{{ w.openPage }}</button>
        <label v-if="tab === 'chunks'">{{ w.roles }}<select v-model="role" @change="filterChanged"><option value="">{{ w.all }}</option><option v-for="r in roles" :key="r" :value="r">{{ r }}</option></select></label>
        <form v-if="tab === 'chunks'" class="inspection-search" @submit.prevent="filterChanged"><label>{{ w.search }}<input v-model="search" :placeholder="w.search" /></label><button class="btn primary" type="submit"><AppIcon name="search" />{{ w.search }}</button></form>
      </template>
      <template v-else>
        <label>{{ w.run }}<select v-model="runId"><option v-if="!runId" value="">{{ w.noRetrievalRun }}</option><option v-for="run in catalog.runs" :key="run.id" :value="run.id">{{ run.label }}</option></select></label>
        <label class="inspection-task">{{ w.task }}<select v-model="taskId" @change="filterChanged"><option v-for="task in tasks" :key="task.id" :value="task.id">{{ task.profileId ? `${task.profileId} · ` : '' }}{{ task.label || task.query || task.id }}{{ task.role ? ` · ${task.role}` : '' }}</option></select></label>
        <label>{{ w.stage }}<select v-model="stage" @change="filterChanged"><option v-for="s in rankingStages" :key="s" :value="s">{{ s === 'source_units' ? sw.title : w[s as keyof typeof w] }}</option></select></label>
      </template>
      <label>{{ w.limit }}<select v-model.number="pageSize" @change="filterChanged"><option :value="30">30</option><option :value="50">50</option><option :value="100">100</option></select></label>
    </div>
    <div class="inspection-summary"><template v-if="tab !== 'retrieval'">{{ dataset?.label || w.unavailable }} · {{ w.chunks }} {{ dataset?.chunkCount ?? '—' }} · {{ w.status }} {{ humanReason(dataset?.status) }} · {{ humanReason(dataset?.vectorStatus) }} · Points: {{ dataset?.vectorPointCount ?? '—' }}</template><template v-else>{{ currentTask?.query || currentTask?.label || w.unavailable }} · {{ w.status }} {{ humanReason(currentRun?.status) }}</template></div>
    <p v-if="snapshotBoundary" class="inspection-summary">scope: {{ snapshotBoundary.scope ?? w.unknown }} · liveDatabaseRead: {{ snapshotBoundary.liveDatabaseRead ?? w.unknown }} · freshQueryExecuted: {{ snapshotBoundary.freshQueryExecuted ?? w.unknown }}</p>
    <p class="inspection-boundary">{{ tab === 'retrieval' ? (stage === 'source_units' ? sw.boundary : stage === 'sent' ? w.sentText : stage === 'selected' ? w.selectedText : w.rankingText) : w.boundary }}</p>
    <p v-if="tab === 'chunks'" class="inspection-summary">{{ w.savedIndexText }}</p>
    <p v-if="tab === 'retrieval'" class="inspection-summary">{{ w.snapshot }}: {{ currentRun?.datasetId || w.unknown }} · {{ currentRun?.label || w.unavailable }}</p>
    <p v-if="tab === 'retrieval' && !currentRun" class="inspection-boundary">{{ w.noRetrievalRun }}</p>
    <div v-if="wordWithoutPages" class="inspection-boundary">{{ w.wordPages }} <a v-if="documentOriginalUrl" :href="documentOriginalUrl" target="_blank" rel="noopener noreferrer">{{ w.original }}</a></div>
    <div v-if="error" class="inspection-error" role="alert">{{ error }} <button class="btn" @click="loadList">{{ w.retry }}</button></div>
    <div class="inspection-grid" :class="{ 'inspection-wide': tab === 'retrieval' && !selected && !detailLoading }">
      <div class="inspection-list panel" :class="{ 'inspection-ocr-list': tab === 'ocr' }">
        <p v-if="loading" class="inspection-empty" role="status">{{ w.loading }}</p>
        <p v-else-if="!list.items.length" class="inspection-empty">{{ list.unavailableReason ? humanReason(list.unavailableReason) : ((list as any).status === 'unavailable' || list.available === false ? w.unrecorded : tab === 'retrieval' && list.status === 'available' ? w.emptyRetrieval : w.noData) }}</p>
        <div v-else class="inspection-table-scroll"><table>
          <thead v-if="tab === 'chunks'"><tr><th>{{ w.document }}</th><th>{{ w.chunkId }}</th><th>{{ w.source }}</th><th>{{ w.text }}</th><th></th></tr></thead>
          <thead v-else-if="tab === 'ocr'"><tr><th>{{ w.pageNo }}</th><th>{{ w.extraction }}</th><th>{{ w.quality }}</th><th></th></tr></thead>
          <thead v-else-if="stage === 'source_units'"><tr><th>{{ sw.order }}</th><th>{{ w.document }}</th><th>{{ w.chunkId }}</th><th>{{ sw.members }}</th><th>{{ sw.seedRank }}</th><th>{{ sw.seedScore }}</th><th></th></tr></thead>
          <thead v-else><tr><th>{{ w.rank }}</th><th>{{ w.document }}</th><th>{{ w.chunkId }}</th><th>{{ w.before }} → {{ w.after }}</th><th>{{ w.score }}</th><th>{{ w.reason }}</th><th></th></tr></thead>
          <tbody><tr v-for="(row, i) in list.items" :key="rowId(row) + ':' + i" @click="inspect(row)">
            <template v-if="tab === 'chunks'"><td>{{ text(row, 'fileName', 'file') }}<small>{{ text(row, 'role', 'documentId') }}</small></td><td class="inspection-id">{{ rowId(row) }}</td><td>{{ text(row, 'clauseId', 'pageNo', 'anchor') }}<small>{{ text(row, 'sourceHash') }}</small></td><td class="inspection-preview">{{ summary(row) }}</td></template>
            <template v-else-if="tab === 'ocr'"><td>{{ text(row, 'pageNo') }}</td><td>{{ Number(row.ocrBlockCount) > 0 ? w.ocrText : w.otherExtraction }}<small v-if="row.blockCount !== undefined">{{ w.blocks }}: {{ row.blockCount }} · OCR: {{ row.ocrBlockCount ?? w.unknown }}</small></td><td>{{ humanReason(row.status) }}</td></template>
            <template v-else-if="stage === 'source_units'"><td>{{ text(row, 'transportOrdinal') }}</td><td>{{ rankingDocument(row) }}<small>{{ text(unitRecord(row), 'clauseId', 'status') }}</small></td><td class="inspection-id">{{ text(unitRecord(row), 'unitId') }}</td><td>{{ unitMemberCount(row) }}</td><td>{{ text(unitRecord(row), 'originalRerankOrdinal') }}</td><td>{{ text(unitRecord(row), 'seedScore') }}</td></template>
            <template v-else><td>{{ text(row, 'rank', 'ordinal') }}</td><td>{{ rankingDocument(row) }}</td><td class="inspection-id">{{ rowId(row) }}</td><td>{{ text(row, 'beforeRank', 'rrfRank', 'rankBefore') }} → {{ text(row, 'afterRank', 'rerankRank', 'rankAfter') }}</td><td>{{ text(row, 'score', 'rerankScore', 'denseScore') }}</td><td>{{ humanReason(text(row, 'dropReason', 'reason', 'status')) }}<small>{{ row.selected === undefined ? '' : `${w.selected}: ${row.selected}` }}</small></td></template>
            <td><button class="btn small" @click.stop="inspect(row)">{{ w.inspect }}</button></td>
          </tr></tbody>
        </table></div>
        <div class="inspection-pagination"><span>{{ list.total }} {{ w.total }} · {{ page }}/{{ lastPage }}</span><button class="btn" :disabled="loading || page <= 1" @click="goPage(page - 1)">{{ w.prev }}</button><button class="btn" :disabled="loading || page >= lastPage" @click="goPage(page + 1)">{{ w.next }}</button></div>
        <details v-if="list.provenance"><summary>{{ w.snapshot }}</summary><pre>{{ inspectionJson(list.provenance) }}</pre></details>
        <details v-if="list.status || list.unavailableReason"><summary>{{ w.metadata }}</summary><pre>{{ inspectionJson({ status: list.status, unavailableReason: list.unavailableReason, stage: list.stage, scoreMeaning: list.scoreMeaning, requestedCandidates: list.requestedCandidates, finalRetrievalLimit: list.finalRetrievalLimit }) }}</pre></details>
        <details v-if="list.actualRequest"><summary>{{ w.actualRequest }}</summary><pre>{{ inspectionJson({ actualRequest: list.actualRequest, outputSchema: list.outputSchema, profile: list.profile }) }}</pre></details>
      </div>
      <aside v-if="tab !== 'retrieval' || selected || detailLoading" class="inspection-detail panel" aria-live="polite">
        <h3>{{ w.detail }}</h3>
        <p v-if="detailLoading">{{ w.loading }}</p><p v-else-if="!selected" class="muted">{{ w.select }}</p>
        <template v-else>
          <div class="inspection-detail-heading"><strong>{{ text(chunk || {}, 'fileName', 'file', 'pageNo') }}<span v-if="tab === 'ocr'"> · {{ w.pageNo }} {{ selected.pageNo }}</span></strong><button class="btn small" @click="clearSelection">{{ w.close }}</button></div>
          <p v-if="selected.unavailableReason" class="inspection-error">{{ humanReason(selected.unavailableReason) }}</p>
          <a v-if="documentOriginalUrl" class="btn" :href="documentOriginalUrl" target="_blank" rel="noopener noreferrer"><AppIcon name="external" />{{ w.original }}</a>
          <h4>{{ tab === 'ocr' ? (pageIsOcr ? w.ocrText : w.extraction) : w.text }}</h4><pre class="inspection-source">{{ detailText === null ? w.unrecorded : detailText }}</pre>
          <details open><summary>{{ w.source }}</summary><dl class="inspection-fields"><template v-for="key in ['id', 'documentId', 'pageNo', 'sourceHash', 'role', 'clauseId', 'anchor', 'clauseHeadingLocation', 'sourceQualityHash']" :key="key"><dt>{{ key }}</dt><dd>{{ text(chunk || {}, key) }}</dd></template></dl></details>
          <details v-if="sourceUnit" open><summary>{{ sw.members }} · {{ sourceUnitMembers.length }}</summary>
            <p class="muted">{{ sw.boundary }}</p>
            <strong>{{ sw.seed }}</strong><pre>{{ inspectionJson({ seedId: selected.seedId, originalRerankOrdinal: sourceUnit.originalRerankOrdinal, seedScore: sourceUnit.seedScore, status: sourceUnit.status, semanticScopeVerified: sourceUnit.semanticScopeVerified, qualifiersComplete: sourceUnit.qualifiersComplete }) }}</pre>
            <p v-if="!sourceUnitMembers.length" class="muted">{{ sw.noMembers }}</p>
            <details v-for="member in sourceUnitMembers" :key="String(member.id)"><summary>{{ text(member, 'fileName') }} · {{ text(member, 'clauseId', 'anchor') }} · {{ text(member, 'id') }}</summary>
              <pre class="inspection-source">{{ member.content }}</pre><details><summary>{{ w.native }} / {{ w.source }}</summary><pre>{{ inspectionJson(member) }}</pre></details>
            </details>
            <details><summary>{{ sw.record }}</summary><pre>{{ inspectionJson(sourceUnit) }}</pre></details>
          </details>
          <details v-if="tab === 'ocr'" open><summary>{{ w.quality }}</summary><p class="muted">{{ w.ocrCoverageBoundary }} · {{ humanReason(selectedDocument?.parseStatus) }}</p><pre>{{ inspectionJson({ quality: selected.quality, sourceQuality: selectedDocument?.sourceQuality, extractionSources: selected.extractionSources, assetStatus: selected.assetStatus }) }}</pre></details>
          <details v-if="tab === 'ocr'" open class="inspection-ocr-review"><summary>{{ ow.title }}</summary>
            <p class="muted">{{ ow.boundary }}</p>
            <template v-if="ocrReview?.status === 'available'">
              <h5>{{ ow.original }}</h5><img v-if="reviewImage(ocrReview.originalImage)" class="inspection-ocr-raster" :src="reviewImage(ocrReview.originalImage)!" :alt="ow.original" loading="lazy" />
              <details><summary>{{ ow.risks }}</summary><pre>{{ inspectionJson(ocrReview.visualReview) }}</pre></details>
              <h5>{{ ow.experiments }} · {{ ocrExperiments.length }}</h5>
              <p v-if="!ocrExperiments.length" class="muted">{{ ow.noExperiments }}</p>
              <article v-for="experiment in ocrExperiments" :key="String(experiment.id)" class="inspection-ocr-experiment">
                <strong>{{ experiment.id }} · {{ ow.unapplied }}</strong><p class="muted">{{ ow.roi }}</p>
                <details><summary>{{ ow.original }} · {{ experiment.dpi }} dpi</summary><img v-if="reviewImage(experiment.image)" class="inspection-ocr-raster" :src="reviewImage(experiment.image)!" :alt="String(experiment.id)" loading="lazy" /></details>
                <h5>{{ ow.raw }}</h5><pre class="inspection-source">{{ experiment.rawJoinedText }}</pre>
                <details><summary>{{ ow.boxes }}</summary><pre>{{ inspectionJson(experiment.rawLines) }}</pre></details>
                 <InspectionOcrDiagnostics :locale="store.locale" :line-stage-diagnostics="experiment.lineStageDiagnostics" :raster-table-candidates="experiment.rasterTableCandidates" />
                <details><summary>{{ ow.parameters }}</summary><pre>{{ inspectionJson({ sourceBinding: ocrReview.sourceBinding, parameters: experiment.parameters, transform: experiment.transform, modelWeights: experiment.modelWeights, recognitionSeconds: experiment.recognitionSeconds, actualProviders: experiment.actualProviders, applied: experiment.applied, qualityStatus: experiment.qualityStatus }) }}</pre></details>
              </article>
            </template>
            <p v-else class="muted">{{ ocrReviewReason }}</p>
          </details>
          <details v-if="tab === 'ocr' && textRevisions.length" open><summary>{{ w.revisions }} · {{ textRevisions.length }}</summary><p class="muted">{{ w.revisionBoundary }}</p>
            <article v-for="revision in textRevisions" :key="String(revision.blockId)" class="inspection-revision"><strong>{{ revision.blockId }} · {{ w.pageNo }} {{ revision.pageNo }}</strong><div class="inspection-revision-text"><section><h5>{{ w.oldOcrText }}</h5><pre class="inspection-source">{{ revision.oldText }}</pre></section><section><h5>{{ w.revisedText }}</h5><pre class="inspection-source">{{ revision.newText }}</pre></section></div><details><summary>{{ w.hashes }}</summary><pre>{{ inspectionJson(revision) }}</pre></details></article>
          </details>
          <details v-if="nativeParts.length"><summary>{{ w.native }} · {{ nativeParts.length }}</summary><pre>{{ inspectionJson(nativeParts) }}</pre></details>
          <details v-if="selected.windows || selected.embeddingRecipe || selected.vectorStatus"><summary>{{ w.windows }}</summary><pre>{{ inspectionJson({ windows: selected.windows, embeddingRecipe: selected.embeddingRecipe, vectorStatus: selected.vectorStatus }) }}</pre></details>
          <details v-if="tab !== 'ocr'" open><summary>{{ w.vectorPoints }} · {{ Array.isArray(selected.vectorPoints) ? selected.vectorPoints.length : '—' }}</summary><p class="muted">{{ humanReason(selected.vectorSnapshotStatus) }}{{ selected.vectorSnapshotUnavailableReason ? ` · ${humanReason(selected.vectorSnapshotUnavailableReason)}` : '' }}</p>
            <template v-if="Array.isArray(selected.vectorPoints) && selected.vectorPoints.length"><div v-for="point in selected.vectorPoints" :key="String(point.id)" class="inspection-point"><dl class="inspection-fields"><template v-for="key in ['id', 'collection', 'vectorDimension', 'vectorSha256', 'vectorL2Norm']" :key="key"><dt>{{ key }}</dt><dd>{{ text(point, key) }}</dd></template></dl><strong>vectorPreview</strong><pre>{{ inspectionJson(point.vectorPreview) }}</pre><details><summary>payload · {{ w.source }}</summary><pre>{{ inspectionJson(point.payload) }}</pre></details><details><summary>{{ w.metadata }}</summary><pre>{{ inspectionJson(point) }}</pre></details></div></template>
            <p v-else class="muted">{{ w.unrecorded }}</p><pre v-if="selected.vectorSnapshotProvenance">{{ inspectionJson(selected.vectorSnapshotProvenance) }}</pre>
          </details>
          <details><summary>{{ w.metadata }}</summary><pre>{{ inspectionJson(selected) }}</pre></details>
        </template>
      </aside>
    </div>
  </section>
</template>

<style scoped>
.inspection-ocr-raster { display:block;max-width:100%;max-height:70vh;object-fit:contain;margin:.75rem 0;border:1px solid var(--border); }
.inspection-ocr-experiment { border-top:1px solid var(--border);padding:1rem 0; }
/* This route scrolls the document; the main wrapper must not capture sticky positioning. */
:global(.main:has(.inspection-view)) { overflow:visible; }
.inspection-view { padding: 24px; max-width: 1800px; margin: 0 auto; }
.inspection-heading { display:flex; align-items:flex-start; justify-content:space-between; gap:20px; margin-bottom:16px; }
.inspection-heading h2 { margin:0 0 5px; }.inspection-heading p { margin:0; max-width:1050px; }
.inspection-tabs { display:flex; align-items:center; gap:6px; border-bottom:1px solid var(--line); margin-bottom:18px; }
.inspection-tabs button { background:transparent; border:0; padding:12px 18px; color:var(--muted); border-bottom:3px solid transparent; }.inspection-tabs button.active { border-color:var(--accent); color:var(--accent-dark); font-weight:600; }.inspection-tabs .tag { margin-left:auto; }
.inspection-toolbar { display:flex; flex-wrap:wrap; align-items:end; gap:12px; margin-bottom:12px; }.inspection-toolbar label { display:flex; flex-direction:column; gap:5px; font-size:12px; color:var(--muted); min-width:100px; }.inspection-toolbar select,.inspection-toolbar input { border:1px solid var(--line); border-radius:6px; background:white; color:var(--ink); padding:8px; max-width:380px; }.inspection-toolbar .inspection-task { flex:1; min-width:300px; }.inspection-task select { max-width:100%; width:100%; }.inspection-search { display:flex; align-items:end; gap:8px; }
.inspection-summary { font-size:12px; overflow-wrap:anywhere; color:var(--muted); }.inspection-boundary { background:var(--blue-soft); border-left:3px solid var(--blue); padding:10px 13px; font-size:12px; }
.inspection-grid { display:grid; grid-template-columns:minmax(0,1.2fr) minmax(350px,1fr); gap:18px; align-items:start; }.panel { padding:16px; border:1px solid var(--line); border-radius:8px; background:white; min-width:0; }
.inspection-grid.inspection-wide { grid-template-columns:1fr; }
.inspection-list.inspection-ocr-list table { min-width:0; }.inspection-list.inspection-ocr-list th { white-space:normal; }
.inspection-table-scroll { overflow-x:auto; }.inspection-list table { border-collapse:collapse; width:100%; min-width:690px; font-size:12px; }.inspection-list th { text-align:left; font-weight:600; color:var(--muted); background:var(--surface-subtle); white-space:nowrap; }.inspection-list td,.inspection-list th { padding:10px 8px; border-bottom:1px solid var(--line); vertical-align:top; }.inspection-list tbody tr { cursor:pointer; }.inspection-list tbody tr:hover { background:var(--accent-soft); }.inspection-list small { display:block; color:var(--muted); overflow-wrap:anywhere; max-width:180px; }.inspection-id { min-width:140px; max-width:180px; overflow-wrap:anywhere; font-family:var(--font-mono); }.inspection-preview { min-width:160px; max-width:300px; white-space:pre-wrap; word-break:break-word; }
.inspection-pagination { display:flex; flex-wrap:wrap; justify-content:flex-end; align-items:center; gap:8px; padding-top:14px; font-size:12px; }.inspection-pagination span { margin-right:auto; }.inspection-empty { padding:36px 12px; color:var(--muted); text-align:center; }.inspection-detail { position:sticky; top:12px; max-height:calc(100vh - 110px); overflow:auto; }.inspection-detail h3 { margin-top:0; }.inspection-detail-heading { display:flex; gap:12px; align-items:start; justify-content:space-between; }.inspection-detail-heading strong { overflow-wrap:anywhere; }
pre { white-space:pre-wrap; overflow-wrap:anywhere; word-break:break-word; background:var(--surface-subtle); border:1px solid var(--line); border-radius:5px; padding:12px; font:12px/1.7 var(--font-mono); max-height:520px; overflow:auto; }.inspection-source { color:var(--ink); background:white; font:13px/1.7 "Segoe UI","Microsoft YaHei",sans-serif; }.inspection-fields { display:grid; grid-template-columns:135px 1fr; gap:7px; font-size:12px; }.inspection-fields dt { color:var(--muted); }.inspection-fields dd { margin:0; overflow-wrap:anywhere; font-family:var(--font-mono); }.inspection-error { padding:12px; background:var(--amber-soft); color:var(--amber); border-radius:5px; margin-bottom:10px; }details { margin-top:14px; }summary { cursor:pointer; font-size:13px; font-weight:600; }
@media(max-width:1150px) { .inspection-grid { grid-template-columns:1fr; }.inspection-detail { position:static; max-height:none; } }.inspection-list .btn.small { white-space:nowrap; }

.inspection-revision { margin-top:16px; border-top:1px solid var(--line); padding-top:12px; }.inspection-revision-text { display:grid; grid-template-columns:1fr 1fr; gap:12px; }.inspection-revision-text section { min-width:0; }.inspection-revision-text h5 { margin-bottom:6px; }
@media(max-width:900px) { .inspection-revision-text { grid-template-columns:1fr; } }
</style>
