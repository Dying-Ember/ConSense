<script setup lang="ts">
import { computed, defineAsyncComponent, onBeforeUnmount, ref, watch } from 'vue'
import { fixedReferenceApi, slLibraryApi, type FixedReferenceSelectionStatus, type SlLibraryStatus } from '@/api'
import { slEditionLabel } from '@/api/slEditionLabel'
import type { FixedBaselineEvidence, FixedBaselineEvidenceAddress, FixedBaselineSourceUnit } from '@/api/fixedBaselineEvidence'
import AppModal from './AppModal.vue'
const FixedEvidencePanel = defineAsyncComponent(() => import('./FixedBaselineEvidencePanel.vue'))

const props = withDefaults(defineProps<{ projectId: string; locale?: string; module?: 'vetting' | 'advisory' }>(), { locale: 'zh-Hans', module: 'vetting' })
const status = ref<FixedReferenceSelectionStatus | null>(null), library = ref<SlLibraryStatus | null>(null)
const configured = ref(false), loading = ref(false), saving = ref(false), building = ref(false), open = ref(false)
const error = ref(''), libraryError = ref(''), buildError = ref(''), selected = ref<string[]>([])
const query = ref(''), searching = ref(false), searchError = ref(''), searched = ref(false), hits = ref<FixedBaselineEvidence[]>([])
const selectedHit = ref<FixedBaselineEvidence | null>(null), resultScope = ref('')
let searchSequence = 0
let generation = 0, selectionSequence = 0, disposed = false, timer: ReturnType<typeof setTimeout> | undefined, libraryLoading = false
const label = (en: string, hans: string, hant: string) => props.locale === 'en' ? en : props.locale === 'zh-Hant' ? hant : hans
const title = computed(() => label('SL editions and library', 'SL 版本与资料库', 'SL 版本與資料庫'))
const active = computed(() => ['queued', 'running', 'building', 'parsing', 'indexing'].includes(library.value?.job.status.toLowerCase() ?? ''))
const staleSelection = computed(() => !!status.value?.selection && status.value.selection.manifestSha256 !== status.value.manifestSha256)
const saved = computed(() => status.value?.selection?.manifestSha256 === status.value?.manifestSha256 ? status.value?.selection?.slSources ?? [] : [])
const dirty = computed(() => selected.value.length !== saved.value.length || saved.value.some(source => !selected.value.includes(source.sha256)))
const scopeKey = computed(() => JSON.stringify([props.projectId, props.module, status.value?.manifestSha256, saved.value]))
const searchReady = computed(() => !!props.projectId && configured.value && !!status.value && !!saved.value.length && library.value?.retrievalAvailable === true && !loading.value && !saving.value && !error.value && !libraryError.value)
const selectedPointer = computed(() => selectedHit.value && resultScope.value === scopeKey.value ? evidencePointer(selectedHit.value) : null)
const panelLocale = computed(() => props.locale === 'en' || props.locale === 'zh-Hant' ? props.locale : 'zh-Hans')
const editionLabel = (source: { editionId: string; label?: string }) => slEditionLabel(source.editionId) || source.label || library.value?.versions.find(version => version.editionId === source.editionId)?.label
  || (source.editionId.startsWith('legacy:') ? label('Legacy SL reference', '历史 SL 参考', '歷史 SL 參考') : source.editionId.replace(/[_-]+/g, ' '))
const count = (value: number | null | undefined) => typeof value === 'number' && Number.isInteger(value) && value >= 0 ? String(value) : label('Unknown', '未知', '未知')
const progressKnown = computed(() => typeof library.value?.job.processedPages === 'number' && typeof library.value.job.totalPages === 'number'
  && library.value.job.processedPages >= 0 && library.value.job.totalPages > 0)
function stateLabel(value?: string) {
  const labels: Record<string, string[]> = {
    ready: ['Ready', '已就绪', '已就緒'], completed: ['Completed', '已完成', '已完成'], partial: ['Partial', '部分完成', '部分完成'],
    running: ['Running', '进行中', '進行中'], building: ['Building', '建库中', '建庫中'], queued: ['Queued', '排队中', '排隊中'],
    parsing: ['Reading text and clauses', '读取文字与条款', '讀取文字與條款'], indexing: ['Indexing', '建立索引', '建立索引'],
    failed: ['Failed', '失败', '失敗'], idle: ['Not started', '尚未开始', '尚未開始'], not_started: ['Not started', '尚未开始', '尚未開始'],
    unconfigured: ['Service not configured', '服务未配置', '服務未設定'], cancelled: ['Cancelled', '已取消', '已取消'],
    interrupted: ['Interrupted — resume explicitly', '已中断，需手动继续', '已中斷，需手動繼續'],
    awaiting_build: ['Waiting to start', '等待开始建库', '等待開始建庫'],
    verify_originals_and_scope: ['Checking originals and technical scope', '核验原件与技术范围', '核驗原件與技術範圍'],
    fresh_native_read: ['Reading original text', '读取原件文字', '讀取原件文字'],
    native_clause_preparation: ['Organizing available text and clauses', '整理可用文字与条款', '整理可用文字與條款'],
    embedding_and_persistence: ['Indexing and saving', '建立并保存索引', '建立並儲存索引'],
    retrieval_ready: ['Retrieval ready', '检索已就绪', '檢索已就緒'],
    original_available: ['Original available', '原件已就绪', '原件已就緒'],
    prepared: ['Available text and clauses prepared', '可用文字与条款已整理', '可用文字與條款已整理'],
    indexed: ['Indexed', '已建立索引', '已建立索引']
  }
  const words = value ? labels[value.toLowerCase()] : null
  return words ? label(words[0]!, words[1]!, words[2]!) : value || label('Unknown', '未知', '未知')
}
function clearPoll() { if (timer !== undefined) clearTimeout(timer); timer = undefined }
function selectionChangedMessage() { return label('The SL library changed. Review and save the project editions shown below, then search again.', 'SL 资料库已更新。请核对并保存下方本项目版本，然后重新检索。', 'SL 資料庫已更新。請核對並儲存下方本項目版本，然後重新檢索。') }
function setSelection(result: FixedReferenceSelectionStatus, keepChoices = false) {
  const choices = keepChoices ? selected.value : result.selection?.slSources.filter(source => result.availableSlSources.some(available => available.editionId === source.editionId && available.sha256 === source.sha256)).map(source => source.sha256) ?? []
  status.value = result
  selected.value = choices.filter(sha => result.availableSlSources.some(source => source.sha256 === sha))
}
function staleFailure(failure: unknown) { return failure instanceof Error && failure.message === 'fixed_sl_selection_required_or_stale' }
function schedulePoll() {
  clearPoll()
  if (!disposed && (open.value || active.value)) timer = setTimeout(() => { void loadLibrary() }, 3000)
}
async function loadSelection(token = generation, id = props.projectId, keepChoices = false) {
  if (saving.value) return
  const sequence = ++selectionSequence
  const current = () => !disposed && token === generation && sequence === selectionSequence
  loading.value = true; error.value = ''
  try {
    const health = await fixedReferenceApi.status()
    if (!current()) return
    configured.value = health.configured === true
    if (!configured.value || !id) { status.value = null; selected.value = []; return }
    const result = await fixedReferenceApi.selection(id)
    if (!current()) return
    setSelection(result, keepChoices)
  } catch (failure) { if (current()) error.value = failure instanceof Error ? failure.message : String(failure) }
  finally { if (current()) loading.value = false }
}
async function loadLibrary() {
  if (disposed || libraryLoading) return
  libraryLoading = true
  try {
    const result = await slLibraryApi.status()
    if (disposed) return
    if (!result || !Array.isArray(result.versions) || result.versions.some(version => !version || typeof version.editionId !== 'string' || !version.editionId || typeof version.status !== 'string' || version.label != null && typeof version.label !== 'string')
      || !result.job || typeof result.job.status !== 'string' || [result.job.stage, result.job.currentEdition, result.job.message].some(value => value != null && typeof value !== 'string')
      || typeof result.retrievalAvailable !== 'boolean') throw new Error(label('Invalid SL library status', 'SL 资料库状态无效', 'SL 資料庫狀態無效'))
    const wasActive = active.value
    library.value = result; libraryError.value = ''
    if (wasActive && !active.value && !saving.value) void loadSelection(generation, props.projectId, dirty.value)
  } catch (failure) { if (!disposed) libraryError.value = failure instanceof Error ? failure.message : String(failure) }
  finally { libraryLoading = false; schedulePoll() }
}
watch(() => props.projectId, () => {
  ++generation; status.value = null; selected.value = []; error.value = ''; configured.value = false; saving.value = false
  void loadSelection(); void loadLibrary()
}, { immediate: true })
function showLibrary() { open.value = true; void refresh() }
function closeLibrary() { open.value = false; clearSearch(); schedulePoll() }
async function refresh() { await Promise.all([loadLibrary(), loadSelection(generation, props.projectId, dirty.value)]) }
async function build() {
  if (building.value || active.value || !library.value || library.value.job.status === 'unconfigured') return
  building.value = true; buildError.value = ''
  try { await slLibraryApi.build(); if (!disposed) await loadLibrary() }
  catch (failure) { if (!disposed) buildError.value = failure instanceof Error ? failure.message : String(failure) }
  finally { if (!disposed) building.value = false }
}
async function save() {
  if (!status.value || saving.value || loading.value || !selected.value.length) return
  const id = props.projectId, token = generation, choices = [...selected.value]
  clearSearch(); saving.value = true; error.value = ''
  try {
    const current = await fixedReferenceApi.selection(id)
    if (disposed || token !== generation) return
    setSelection(current, true)
    const slSources = current.availableSlSources.filter(source => choices.includes(source.sha256)).map(({ editionId, sha256 }) => ({ editionId, sha256 }))
    if (slSources.length !== choices.length) throw new Error(label('A selected SL edition is no longer available. Review the current choices and save again.', '所选 SL 版本已不在当前目录中。请核对现有版本并重新保存。', '所選 SL 版本已不在目前目錄中。請核對現有版本並重新儲存。'))
    const result = await fixedReferenceApi.save(id, { manifestSha256: current.manifestSha256, slSources })
    if (!disposed && token === generation) {
      setSelection(result)
    }
  } catch (failure) {
    if (!disposed && token === generation) {
      if (staleFailure(failure)) {
        try {
          const latest = await fixedReferenceApi.selection(id)
          if (!disposed && token === generation) setSelection(latest, true)
        } catch { /* Keep the visible choices for a manual refresh. */ }
      }
      if (!disposed && token === generation) error.value = staleFailure(failure) ? selectionChangedMessage() : failure instanceof Error ? failure.message : String(failure)
    }
  }
  finally { if (!disposed && token === generation) saving.value = false }
}
function clearSearch() { ++searchSequence; hits.value = []; selectedHit.value = null; searched.value = false; searching.value = false; searchError.value = ''; resultScope.value = '' }
watch(scopeKey, clearSearch)
watch(query, clearSearch)
function evidencePointer(hit: FixedBaselineEvidence): FixedBaselineEvidenceAddress {
  const payload = hit?.payload, sha = (value: unknown) => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value)
  const record = (value: unknown): value is Record<string, unknown> => !!value && typeof value === 'object' && !Array.isArray(value)
  const original = payload?.originalSourceSha256 ?? payload?.sourceHash
  const pages = payload?.originalPageNos ?? [hit?.pageNo], edition = payload?.fixedBaselineEditionId
  if (!hit || typeof hit.id !== 'string' || !hit.id || hit.id.length > 512 || typeof hit.content !== 'string' || typeof hit.fileLabel !== 'string'
    || !record(payload) || payload.fixedBaselineManifestSha256 !== status.value?.manifestSha256 || !sha(payload.fixedBaselineManifestSha256) || !sha(original)
    || typeof payload.fixedBaselineVersion !== 'string' || !payload.fixedBaselineVersion || (payload.sourceRole ?? payload.role) !== 'standard'
    || !['SL', 'GCC', 'GCT'].includes(hit.sourceFamily) || !record(payload.sourceQuality) || payload.sourceQuality.family !== hit.sourceFamily
    || !Number.isInteger(hit.pageNo) || hit.pageNo < 1 || !Array.isArray(pages) || !pages.length
    || !pages.every(page => Number.isInteger(page) && page > 0) || new Set(pages).size !== pages.length || !pages.includes(hit.pageNo)
    || hit.sourceFamily === 'SL' && !saved.value.some(source => source.editionId === edition && source.sha256 === original)) throw new Error(label('Search results do not match the saved source scope.', '检索结果与已保存来源范围不一致。', '檢索結果與已儲存來源範圍不一致。'))
  let sourceUnit: FixedBaselineSourceUnit | undefined
  if (hit.sourceUnit != null) {
    const unit = hit.sourceUnit
    if (!Array.isArray(unit.requiredMemberIds) || !unit.requiredMemberIds.every(id => typeof id === 'string' && !!id)
      || new Set(unit.requiredMemberIds).size !== unit.requiredMemberIds.length || !record(unit.sourceIdentity)) throw new Error(label('Search source members are invalid.', '检索来源成员无效。', '檢索來源成員無效。'))
    sourceUnit = { ...unit, requiredMemberIds: unit.requiredMemberIds, sourceIdentity: unit.sourceIdentity }
  }
  return { evidenceBackend: 'fixed_development_baseline', parentId: hit.id, manifestSha256: payload.fixedBaselineManifestSha256 as string,
    baselineVersion: payload.fixedBaselineVersion, sourceFamily: hit.sourceFamily, sourceRole: 'standard', originalSourceSha256: original as string,
    ...(typeof edition === 'string' ? { editionId: edition } : {}), originalPageNos: pages, pageNo: hit.pageNo,
    projectApplicability: 'unconfirmed', qualifiersComplete: 'unknown', sourceQualityAccepted: false, sourceUnit }
}
function resultTitle(hit: FixedBaselineEvidence) { return hit.sourceFamily === 'SL' ? editionLabel({ editionId: String(hit.payload.fixedBaselineEditionId) }) : hit.fileLabel || hit.sourceFamily }
function resultField(hit: FixedBaselineEvidence, field: 'chapter' | 'observedClause') {
  const value = hit.payload[field] ?? (field === 'observedClause' ? hit.payload.clauseId : null)
  return typeof value === 'string' && value ? value : label('Unspecified', '未标明', '未標明')
}
function resultPages(hit: FixedBaselineEvidence) { return evidencePointer(hit).originalPageNos!.join(', ') }
async function search() {
  const question = query.value.trim()
  if (!searchReady.value || searching.value || !question || question.length > 4000) return
  clearSearch(); searching.value = true
  const token = searchSequence, generationAtStart = generation, capturedScope = scopeKey.value
  const current = () => !disposed && token === searchSequence && generationAtStart === generation && capturedScope === scopeKey.value && !saving.value
  try {
    const currentSelection = await fixedReferenceApi.selection(props.projectId)
    if (!current()) return
    const previousScope = scopeKey.value
    setSelection(currentSelection, dirty.value)
    if (scopeKey.value !== previousScope || staleSelection.value) { error.value = selectionChangedMessage(); return }
    const result = await fixedReferenceApi.search(props.projectId, props.module, question)
    if (!current()) return
    if (!Array.isArray(result)) throw new Error(label('Invalid search response.', '检索响应无效。', '檢索回應無效。'))
    const visible = result.slice(0, 10)
    visible.forEach(evidencePointer)
    if (new Set(visible.map(hit => hit.id)).size !== visible.length) throw new Error(label('Search results contain duplicate sources.', '检索结果包含重复来源。', '檢索結果包含重複來源。'))
    hits.value = visible; resultScope.value = capturedScope; searched.value = true
  } catch (failure) {
    if (current()) {
      const previousScope = scopeKey.value
      try {
        const latest = await fixedReferenceApi.selection(props.projectId)
        if (!current()) return
        setSelection(latest, dirty.value)
      } catch { /* Preserve the original search failure. */ }
      if (disposed || token !== searchSequence || generationAtStart !== generation) return
      if (scopeKey.value !== previousScope || staleSelection.value || staleFailure(failure)) error.value = selectionChangedMessage()
      else searchError.value = failure instanceof Error ? failure.message : String(failure)
    }
  }
  finally { if (current()) searching.value = false }
}
onBeforeUnmount(() => { disposed = true; ++generation; clearPoll(); clearSearch() })
</script>

<template>
  <section class="fixed-reference-selection" data-sl-editions>
    <div class="edition-overview">
      <strong>{{ label('SL editions for this project', '本项目的 SL 版本', '本項目的 SL 版本') }}</strong>
      <p class="edition-summary" data-sl-saved-scope>
        <template v-if="loading && !status">{{ label('Checking saved editions…', '正在读取已保存版本…', '正在讀取已儲存版本…') }}</template>
        <template v-else-if="!status">{{ label('Edition status unavailable', '版本状态暂不可用', '版本狀態暫不可用') }}</template>
        <template v-else>{{ staleSelection ? label('Needs reconfirmation:', '需重新确认：', '需重新確認：') : label('Saved:', '已保存：', '已儲存：') }}
          {{ staleSelection ? status?.selection?.slSources.map(editionLabel).join(' · ') : saved.length ? saved.map(editionLabel).join(' · ') : label('No SL edition selected', '尚未选择 SL 版本', '尚未選擇 SL 版本') }}</template>
      </p>
      <p class="small muted">{{ label('Saved editions set the SL retrieval scope for Vetting and Advice. Confirm which editions apply to the project.', '已保存版本决定审查与咨询的 SL 检索范围，项目实际采用的版本仍需确认。', '已儲存版本決定審查與諮詢的 SL 檢索範圍，項目實際採用的版本仍需確認。') }}</p>
      <p v-if="error && status" class="small" role="alert">{{ label('Could not refresh the saved scope; the displayed editions may be out of date.', '无法刷新已保存范围，所显示版本可能已过时。', '無法重新整理已儲存範圍，顯示的版本可能已過時。') }}</p>
      <span v-if="active" class="small" role="status">{{ stateLabel(library?.job.status) }} · {{ stateLabel(library?.job.stage) }}</span>
    </div>
    <button type="button" class="btn soft" data-sl-library-open @click="showLibrary">{{ label('Choose editions and search', '选择版本与检索', '選擇版本與檢索') }}</button>
    <AppModal v-if="open" :open="open" :title="title" wide @close="closeLibrary">
      <p>{{ label('Saved editions define the SL retrieval scope for new Vetting reviews and Advice questions in this project. This does not establish project adoption.', '已保存版本定义本项目新审查与咨询的 SL 检索范围；这不证明项目已采用。', '已儲存版本定義本項目新審查與諮詢的 SL 檢索範圍；這不證明項目已採用。') }}</p>
      <h4>{{ label('Project retrieval editions', '本项目检索版本', '本項目檢索版本') }}</h4>
      <p v-if="loading" role="status">{{ label('Loading…', '读取中…', '讀取中…') }}</p>
      <template v-if="status">
        <label v-for="source in status.availableSlSources" :key="source.sha256" class="edition-choice"><input v-model="selected" type="checkbox" :value="source.sha256" :disabled="saving || loading" /><span>{{ editionLabel(source) }}</span></label>
        <p v-if="staleSelection" role="alert" data-sl-selection-stale>{{ selectionChangedMessage() }}</p>
        <p v-else-if="!saved.length">{{ label('Select and save editions before starting a query or review.', '请先选择并保存版本，再启动查询或审查。', '請先選擇並儲存版本，再啟動查詢或審查。') }}</p>
        <p v-if="dirty && !staleSelection" role="status">{{ label('Edition changes are not saved. Queries still use the saved scope.', '版本更改尚未保存；查询仍使用已保存范围。', '版本變更尚未儲存；查詢仍使用已儲存範圍。') }}</p>
        <button type="button" class="btn primary" data-sl-editions-save :disabled="saving || loading || !selected.length" @click="save">{{ label('Save editions', '保存版本', '儲存版本') }}</button>
        <details><summary>{{ label('Source identity details', '来源身份详情', '來源身份詳情') }}</summary><p v-for="source in status.availableSlSources" :key="source.sha256" class="small source-identity">{{ source.editionId }} · SHA-256 {{ source.sha256 }}</p></details>
      </template>
      <p v-else-if="!loading">{{ label('The application edition catalog is not ready. Build progress remains available; refresh after the catalog is configured.', '应用版本目录尚未就绪；仍可查看建库进度。目录配置后请刷新。', '應用版本目錄尚未就緒；仍可查看建庫進度。目錄設定後請重新整理。') }}</p>
      <p v-if="error" role="alert">{{ error }}</p>
      <section class="reference-search" data-fixed-reference-search>
        <h4>{{ label('Search saved reference scope', '检索已保存参考范围', '檢索已儲存參考範圍') }}</h4>
        <p>{{ label('Searches the saved SL editions and existing GCC/GCT references for this project. Unsaved checkboxes do not change this scope. Reading quality and project adoption remain unconfirmed.', '检索本项目已保存的 SL 版本及现有 GCC／GCT 参考；未保存的勾选不会改变本次范围。读取质量与项目采用状态仍待确认。', '檢索本項目已儲存的 SL 版本及現有 GCC／GCT 參考；未儲存的勾選不會改變本次範圍。讀取品質與項目採用狀態仍待確認。') }}</p>
        <form class="row" @submit.prevent="search">
          <label class="search-query">{{ label('Question or clause text', '问题或条款文字', '問題或條款文字') }}<input v-model="query" maxlength="4000" :disabled="!searchReady" data-fixed-search-query /></label>
          <button type="submit" class="btn" data-fixed-search-submit :disabled="!searchReady || searching || !query.trim()">{{ searching ? label('Searching…', '检索中…', '檢索中…') : label('Search', '检索', '檢索') }}</button>
        </form>
        <p v-if="!searchReady" class="small">{{ label('Configure the available catalog and save project editions before searching.', '请等待版本目录与检索就绪，并保存本项目版本后再检索。', '請等待版本目錄與檢索就緒，並儲存本項目版本後再檢索。') }}</p>
        <p v-if="searchError" role="alert">{{ searchError }}</p>
        <p v-if="searched && !hits.length">{{ label('No matching references in the saved scope.', '已保存范围内未找到匹配参考。', '已儲存範圍內未找到相符參考。') }}</p>
        <article v-for="hit in hits" :key="hit.id" class="reference-hit" data-fixed-search-hit>
          <strong>{{ resultTitle(hit) }}</strong>
          <p class="small">{{ label('Chapter', '章节', '章節') }}: {{ resultField(hit, 'chapter') }} · {{ label('Clause', '条款', '條款') }}: {{ resultField(hit, 'observedClause') }} · {{ label('Original pages', '原件页码', '原件頁碼') }}: {{ resultPages(hit) }}</p>
          <p class="search-snippet">{{ hit.content.slice(0, 700) }}{{ hit.content.length > 700 ? '…' : '' }}</p>
          <button type="button" class="btn" data-fixed-search-view @click="selectedHit = hit">{{ label('View source', '查看来源', '查看來源') }}</button>
        </article>
        <FixedEvidencePanel v-if="selectedPointer" :key="selectedPointer.parentId" :pointer="selectedPointer" :locale="panelLocale" />
      </section>
      <div class="row">
        <button type="button" class="btn" data-sl-library-refresh :disabled="loading || saving || building" @click="refresh">{{ label('Refresh status', '刷新状态', '重新整理狀態') }}</button>
        <button type="button" class="btn" data-sl-library-build :disabled="building || active || !library || library.job.status === 'unconfigured'" @click="build">{{ label('Build all official SL technical sources', '建立全部官方 SL 技术资料库', '建立全部官方 SL 技術資料庫') }}</button>
      </div>
      <div v-if="library" class="library-progress" role="status" data-sl-library-progress>
        <strong>{{ stateLabel(library.job.status) }}</strong><span v-if="library.job.stage"> · {{ stateLabel(library.job.stage) }}</span>
        <span v-if="library.job.currentEdition"> · {{ editionLabel({ editionId: library.job.currentEdition }) }}</span>
        <p v-if="active || progressKnown">{{ label('Processed pages', '已处理页数', '已處理頁數') }}: {{ count(library.job.processedPages) }} / {{ count(library.job.totalPages) }}</p>
        <progress v-if="progressKnown" :value="library.job.processedPages!" :max="library.job.totalPages!" />
        <p v-if="library.job.message">{{ library.job.message }}</p>
        <p>{{ library.retrievalAvailable ? label('Library retrieval available', '资料库检索已可用', '資料庫檢索已可用') : label('Library retrieval is not ready', '资料库检索尚未就绪', '資料庫檢索尚未就緒') }}</p>
      </div>
      <p v-if="libraryError" role="alert">{{ libraryError }}</p>
      <p v-if="buildError" role="alert">{{ buildError }}</p>
      <div v-if="library?.versions.length" class="table-wrap">
        <table data-sl-library-catalog>
          <thead><tr><th>{{ label('Official SL edition', '官方 SL 版本', '官方 SL 版本') }}</th><th>{{ label('Technical pages', '技术页数', '技術頁數') }}</th><th>{{ label('Read pages', '已读取页数', '已讀取頁數') }}</th><th>{{ label('Clauses', '条款数', '條款數') }}</th><th>{{ label('Indexed chunks', '索引块数', '索引區塊數') }}</th><th>{{ label('Status', '状态', '狀態') }}</th></tr></thead>
          <tbody><tr v-for="version in library.versions" :key="version.editionId"><td>{{ editionLabel(version) }}</td><td>{{ count(version.technicalPages) }}</td><td>{{ count(version.parsedPages) }}</td><td>{{ count(version.clauseCount) }}</td><td>{{ count(version.indexedChunks) }}</td><td>{{ stateLabel(version.status) }}</td></tr></tbody>
        </table>
      </div>
    </AppModal>
  </section>
</template>

<style scoped>
.fixed-reference-selection { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 12px 20px; margin-bottom: 18px; padding: 15px 18px; border: 1px solid var(--line); border-left: 4px solid var(--accent); border-radius: var(--radius); background: var(--surface); }
.edition-overview { min-width: 0; flex: 1 1 20rem; }
.edition-summary { margin: 5px 0; overflow-wrap: anywhere; }
.edition-overview p:last-of-type { margin: 0; }
.edition-choice { display: flex; align-items: flex-start; gap: .5rem; margin-block: .6rem; }
.library-progress { margin-block: 1rem; }
.library-progress p { margin-block: .4rem; }
progress { width: min(100%, 32rem); }
.source-identity { overflow-wrap: anywhere; }
th, td { white-space: normal; }
.reference-search { margin-top: 1.5rem; }
.search-query { display: grid; flex: 1 1 18rem; gap: .3rem; }
.reference-hit { padding-block: .8rem; border-bottom: 1px solid var(--line); }
.search-snippet { white-space: pre-wrap; overflow-wrap: anywhere; }
</style>
