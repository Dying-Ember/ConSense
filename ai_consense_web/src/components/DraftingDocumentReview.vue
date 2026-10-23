<script setup lang="ts">
import type { GraphNavigation } from '@/drafting/graph-navigation'
import { computed, nextTick, ref, watch } from 'vue'
import type { AppLocale } from '@/i18n'
import type { DraftDocument, DraftDocumentBindings, DraftField, DraftPlanAction, DraftVariable, ExtractTrace } from '@/api/types'
import type { DraftValue } from '@/drafting/state'
import type { DocumentReading } from '@/drafting/document-reading'
import { draftWord, localized, type DraftWord } from '@/drafting/words'
import DraftingValueDisplay from './DraftingValueDisplay.vue'
import DraftingQuestionPicker from './DraftingQuestionPicker.vue'
import { reviewLocationLabel } from '@/drafting/review-location-label'
import { associatedReviewFields, prepareDocumentReview, reviewEvidenceContext, type DocumentReviewLocation } from '@/drafting/document-review'


const props = defineProps<{ projectId: string; fileKey: string; document?: DraftDocument; original?: DocumentReading; result?: DocumentReading; sourceBindings?: DraftDocumentBindings; resultBindings?: DraftDocumentBindings; loading: boolean; error?: string; fields: DraftField[]; values: Record<string, DraftValue>; variables: DraftVariable[]; actions: DraftPlanAction[]; fieldStates: Record<string, string>; dirtyKeys: string[]; locale: AppLocale; immersive?: boolean; disabled?: boolean; staleNoticePresented?: boolean; editing?: boolean; selectedKey?: string; trace?: ExtractTrace | null; graphNavigation?: GraphNavigation }>()
const emit = defineEmits<{ input: [key: string]; file: [fileKey: string]; retry: []; pdf: [view: 'source' | 'result'] }>()
const mode = ref<'original' | 'review' | 'saved'>('review')
const selected = ref(props.selectedKey ?? props.fields[0]?.key ?? '')
const activeBindingId = ref(''), inspectedBindingId = ref(''), choices = ref<string[]>([]), navigationNotice = ref('')
const paper = ref<HTMLElement>(), scroll = ref<HTMLElement>(), inspector = ref<HTMLElement>()
const pendingLocation = ref<DocumentReviewLocation>()
let handledGraphNavigation = ''
const field = computed(() => props.fields.find(item => item.key === selected.value))
const variable = computed(() => props.variables.find(item => item.key === selected.value))
const evidenceCandidates = computed(() => (variable.value?.candidates ?? []).map(candidate => ({ candidate, context: reviewEvidenceContext(props.trace, selected.value, candidate) })))
const blocked = computed(() => props.disabled || props.editing || props.loading)
const prepared = computed(() => prepareDocumentReview({ ...props, selectedKey: selected.value, mode: mode.value, planCurrent: !props.document?.stale && !props.dirtyKeys.length }))
const html = computed(() => prepared.value.html)
const l = (value: Parameters<typeof localized>[0]) => localized(value, props.locale)
const t = (en: string, cn: string, hk = cn) => props.locale === 'en' ? en : props.locale === 'zh-Hant' ? hk : cn
const fieldSearch = ref('')
const fieldChoices = computed(() => props.fields.map(item => ({ id: item.key, label: l(item.label), searchText: Object.values(item.label ?? {}).join(' ') })))
const allBindings = computed(() => {
  const bindings = new Map((props.sourceBindings?.bindings ?? []).map(binding => [binding.bindingId, binding]))
  for (const binding of props.resultBindings?.bindings ?? []) bindings.set(binding.bindingId, binding)
  return [...bindings.values()]
})
const currentBinding = computed(() => allBindings.value.find(binding => binding.bindingId === activeBindingId.value))
const currentSavedBinding = computed(() => prepared.value.savedBindingIds.includes(activeBindingId.value) ? props.resultBindings?.bindings.find(binding => binding.bindingId === activeBindingId.value) : undefined)
const currentChange = computed(() => prepared.value.changes.find(change => change.bindingId === activeBindingId.value))
const changeDescription = computed(() => currentChange.value?.kind === 'joint' ? t('Joint saved change. Related variables:', '共同处理的已保存修改，关联变量：', '共同處理的已儲存修改，關聯變量：') + currentChange.value.fieldKeys.map(key => l(props.fields.find(item => item.key === key)?.label) || key).join(' / ') : currentChange.value?.kind === 'editorial' ? t('Verified template instruction cleanup.', '已核对的模板编辑指引清理。', '已核對的模板編輯指引清理。') : currentChange.value?.kind === 'manual' ? t('Saved manual document change; it is not assigned to a variable.', '已保存的人工正文修改，未归因于某项变量。', '已儲存的人工正文修改，未歸因於某項變量。') : '')
const inspectedBinding = computed(() => inspectedBindingId.value === activeBindingId.value && prepared.value.bindingIds.includes(inspectedBindingId.value) ? currentBinding.value : undefined)
const inspectionKind = computed(() => {
  if (!inspectedBinding.value) return undefined
  if (currentChange.value?.kind === 'manual' || currentChange.value?.kind === 'editorial') return currentChange.value.kind
  if (currentChange.value) return 'saved'
  const binding = currentSavedBinding.value
  const normalized = (text: string | null | undefined) => (text ?? '').replace(/\s+/gu, ' ').trim()
  if (mode.value === 'review' && binding && (binding.locationStatus === 'removed' || normalized(binding.sourceText) !== normalized(binding.text))) return 'unverified'
  return 'registered'
})
const inspectionKeys = computed(() => !inspectedBinding.value || ['manual', 'editorial', 'unverified'].includes(inspectionKind.value ?? '') ? [] : currentChange.value ? currentChange.value.fieldKeys : associatedReviewFields(inspectedBinding.value, props.fields, props.actions))
const inspectionInputs = computed(() => inspectionKeys.value.map(key => {
  const variable = props.variables.find(item => item.key === key)
  return { key, field: props.fields.find(item => item.key === key), variable, candidates: (variable?.candidates ?? []).map(candidate => ({ candidate, context: reviewEvidenceContext(props.trace, key, candidate) })) }
}))
const inspectionActions = computed(() => inspectionKind.value === 'saved' ? props.actions.filter(action => action.document === props.fileKey && currentSavedBinding.value?.actionIds.includes(action.id) && currentSavedBinding.value.appliedActionIds?.includes(action.id) && currentSavedBinding.value.operationIds.some(operation => operation === action.id || operation.startsWith(`${action.id}-`))) : [])
const inspectionDescription = computed(() => inspectionKind.value === 'editorial' ? t('Verified template instruction cleanup. It does not establish a business-variable change.', '已核对的模板编辑指引清理，不据此归因于业务变量修改。', '已核對的模板編輯指引清理，不據此歸因於業務變數修改。') : inspectionKind.value === 'manual' ? t('Saved manual document change; it is not assigned to a variable.', '已保存的人工正文修改，未归因于某项变量。', '已儲存的人工正文修改，未歸因於某項變量。') : inspectionKind.value === 'unverified' ? t('This difference cannot be attributed to a verified saved action. Its cause remains unverified.', '此处差异尚无法对应已核对的保存动作，修改原因待核。', '此處差異尚無法對應已核對的儲存動作，修改原因待核。') : inspectionKind.value === 'registered' ? t('Registered related variables. This view does not attribute a saved edit to them.', '以下为登记的关联变量，当前视图不将其认定为已保存修改的原因。', '以下為登記的關聯變量，目前視圖不將其認定為已儲存修改的原因。') : inspectionKeys.value.length > 1 || currentChange.value?.kind === 'joint' ? t('Joint saved change. All related variable evidence is shown below; individual words are not assigned to one variable.', '共同处理的已保存修改，相关变量的证据全部显示如下，不将个别文字单独归因于一项变量。', '共同處理的已儲存修改，相關變量的證據全部顯示如下，不將個別文字單獨歸因於一項變量。') : t('Saved change linked to this variable. Its recorded source evidence is shown below.', '此已保存修改关联以下变量，其登记来源依据显示如下。', '此已儲存修改關聯以下變量，其登記來源依據顯示如下。'))
const relatedActions = computed(() => props.actions.filter(action => (action.inputKeys ?? action.fieldKeys ?? []).includes(selected.value)))
const locations = computed<DocumentReviewLocation[]>(() => {
  const locations: DocumentReviewLocation[] = []
  for (const binding of allBindings.value.filter(binding => associatedReviewFields(binding, props.fields, props.actions).includes(selected.value))) {
    const label = reviewLocationLabel(binding, props.actions, props.fileKey, selected.value, prepared.value.savedBindingIds.includes(binding.bindingId))
    locations.push({ id: `${props.fileKey}:${binding.bindingId}`, bindingId: binding.bindingId, fileKey: props.fileKey, actionId: label?.actionId, clause: label?.clause ?? field.value?.affects?.find(item => item.document === props.fileKey)?.clause ?? t('Paragraph', '段落', '段落'), paragraph: binding.sourceParagraphOrdinal ?? binding.resultParagraphOrdinal ?? undefined, status: binding.locationStatus })
  }
  for (const action of relatedActions.value.filter(action => action.document !== props.fileKey)) locations.push({ id: `${action.document}:${action.id}`, fileKey: action.document, actionId: action.id, clause: action.clause })
  for (const target of field.value?.affects ?? []) if (target.document !== props.fileKey && !locations.some(item => item.fileKey === target.document && item.clause === target.clause)) locations.push({ id: `${target.document}:${target.clause}`, fileKey: target.document, clause: target.clause })
  return locations
})
function notice(key: string) {
  const messages: Record<string, string> = {
    unavailable: t('No complete reading is available for this view.', '本视图尚无完整正文。', '本視圖尚無完整正文。'),
    identityMismatch: t('Document versions differ. Retry to load one consistent revision.', '文件版本不一致，请重试载入同一版本。', '檔案版本不一致，請重試載入同一版本。'),
    reviewUnavailable: t('Saved revision bindings are unavailable; inline changes cannot be verified.', '尚无已保存版本的可靠位置登记，无法核对逐字修改。', '尚無已儲存版本的可靠位置登記，無法核對逐字修改。'),
    savedReadingFallback: t('Showing the current saved draft. Its change mapping is unverified, so no changes are attributed or marked. Use Original template to read the source.', '当前显示真实已保存文稿。修改映射尚未验证，因此不归因或标注修改；原始模板可在原文模式单独查看。', '目前顯示真實已儲存文稿。修改映射尚未驗證，因此不歸因或標註修改；原始模板可在原文模式獨立查看。'),
    mappingUnavailable: t('A bound paragraph could not be verified in the reading; its change is not marked.', '有登记段落无法与正文核对，其修改暂不标注。', '有登記段落無法與正文核對，其修改暫不標註。'),
    selectedMappingUnavailable: t('A saved mapping for a selected variable location is unavailable or unverified. Its saved change status cannot be established.', '选中变量的部分位置尚无可靠已保存映射，无法确定这些位置的文稿修改状态。', '選中變量的部分位置尚無可靠已儲存映射，無法確定這些位置的文稿修改狀態。'),
    diffUnavailable: t('A paragraph change is too large for verified inline comparison. Read its saved result.', '有段落修改超出逐字对照范围，请阅读已保存文稿。', '有段落修改超出逐字對照範圍，請閱讀已儲存文稿。'),
    diffUnverified: t('A text difference cannot be attributed to a verified applied action or cleanup record. That difference remains unverified.', '有文字差异的修改记录尚无法对应已核对的处理动作或清理记录，该处差异暂不标注。', '有文字差異的修改記錄尚無法對應已核對的處理動作或清理記錄，該處差異暫不標註。'),
    unrelatedSharedChange: t('A saved change in this shared paragraph belongs to another variable. Read the complete saved result; it is not coloured as this variable\'s change.', '此共用段落的已保存修改属于另一变量，请阅读完整已保存文稿；此处不将其作为本变量的修改着色。', '此共用段落的已儲存修改屬於另一變量，請閱讀完整已儲存文稿；此處不將其作為本變量的修改著色。'),
    jointRangeAttribution: t('Joint changes are shown for the complete saved action. Individual character ranges are not assigned to one variable.', '共同处理已按完整保存动作显示修改；不将其中每段文字单独归因于某项变量。', '共同處理已按完整儲存動作顯示修改；不將其中每段文字單獨歸因於某項變量。'),
    manualSharedChange: t('Saved manual body edits are shown as document changes; they cannot be attributed to the selected variable.', '已保存的人工正文修改按文稿差异显示，不归因于选中变量。', '已儲存的人工正文修改按文稿差異顯示，不歸因於選中變量。'),
    additionUnmapped: t('A saved addition has no verified original context. Read it in the saved result.', '有文稿新增内容尚无可靠原文上下文，请在已保存文稿中阅读。', '有文稿新增內容尚無可靠原文上下文，請在已儲存文稿中閱讀。')
  }
  return messages[key] ?? key
}
function readingWarning(warning: string, carrier: 'original' | 'saved') {
  const numbering = warning.match(/Native numbering labels.*\((\d+) numbered paragraph\(s\)\)/i)
  const lists = warning.match(/\((\d+) converted list\(s\)\)/i)
  const detail = numbering ? t(`Native numbering labels and continuation sequence are not verified (${numbering[1]} numbered paragraphs). Web-generated list markers are hidden${lists ? ` (${lists[1]} converted lists)` : ''}; check the PDF for the original labels.`, `正文含 ${numbering[1]} 个自动编号段落，原编号标号及续号尚未核对。网页自动标号已隐藏${lists ? `（${lists[1]} 个转换列表）` : ''}，原编号请核对 PDF。`, `正文含 ${numbering[1]} 個自動編號段落，原編號標號及續號尚未核對。網頁自動標號已隱藏${lists ? `（${lists[1]} 個轉換列表）` : ''}，原編號請核對 PDF。`) : warning
  return `${carrier === 'original' ? t('Original', '原始模板', '原始模板') : t('Saved result', '已保存文稿', '已儲存文稿')} · ${detail}`
}
const conversionWarnings = computed(() => [...new Set([...(mode.value === 'saved' ? [] : (props.original?.warnings ?? []).map(warning => readingWarning(warning, 'original'))), ...(mode.value === 'original' ? [] : (props.result?.warnings ?? []).map(warning => readingWarning(warning, 'saved')))])])
const numberingUnverified = computed(() => (mode.value === 'original' ? [props.original] : mode.value === 'saved' ? [props.result] : [props.original, props.result]).some(reading => reading?.coverage?.numberingLabelsVerified === false || reading?.warnings?.some(warning => /Native numbering labels/i.test(warning))))
const coverageWarning = (warning: string) => /Office Math|cannot be assigned|Incomplete main-document coverage|Native drawings\/images omitted|Native numbering labels|自动编号|自動編號/i.test(warning)
const warnings = computed(() => [...new Set([...conversionWarnings.value.filter(coverageWarning), ...prepared.value.notices.filter(key => key !== 'savedReadingFallback').map(notice)])])
const diagnostics = computed(() => conversionWarnings.value.filter(warning => !coverageWarning(warning)))
const inputState = computed(() => props.dirtyKeys.includes(selected.value) ? t('Unsaved variable value', '变量值未保存', '變量值未儲存') : draftWord((props.fieldStates[selected.value] || 'unknown') as DraftWord, props.locale))
const stamp = () => `${props.projectId}|${props.fileKey}|${props.document?.revisionId}|${props.original?.docxSha256}|${props.result?.docxSha256}`
function changeMode(next: typeof mode.value) { if (!blocked.value) mode.value = next }
function returnInput() { if (!blocked.value && field.value) emit('input', field.value.key) }
function showPdf() { if (!blocked.value) emit('pdf', mode.value === 'original' ? 'source' : 'result') }
async function focusBinding(move = true) {
  const identity = stamp(); await nextTick()
  if (identity !== stamp() || props.loading || props.editing) return
  const nodes = [...(paper.value?.querySelectorAll<HTMLElement>('[data-review-binding]') ?? [])]
  for (const node of nodes) node.classList.remove('review-focused')
  if (!activeBindingId.value) return
  let node = nodes.find(item => item.dataset.reviewBinding === activeBindingId.value)
  navigationNotice.value = ''
  if (!node && mode.value === 'original') {
    const parent = currentBinding.value?.parentBindingId
    node = nodes.find(item => item.dataset.reviewBinding === parent)
    if (node) navigationNotice.value = t('Located recorded parent context. The saved row is registered as added without a verified one-to-one original mapping.', '已定位登记的上级上下文；文稿行登记为新增，尚无可靠的一对一原文映射。', '已定位登記的上級上下文；文稿行登記為新增，尚無可靠的一對一原文映射。')
  }
  if (!node) { navigationNotice.value = t('No verified native location in this view. Choose another mode or location.', '本视图尚无可靠正文位置，请选择另一视图或位置。', '本視圖尚無可靠正文位置，請選擇另一視圖或位置。'); return }
  node.classList.add('review-focused')
  if (move && scroll.value) { const box = node.getBoundingClientRect(), viewport = scroll.value.getBoundingClientRect(); scroll.value.scrollTo({ top: Math.max(0, scroll.value.scrollTop + box.top - viewport.top - (viewport.height - Math.min(box.height, viewport.height)) / 2), behavior: 'smooth' }); node.focus({ preventScroll: true }) }
}
function chooseField(key: string, keepLocation = false) {
  if (blocked.value) return
  selected.value = key
  if (!keepLocation) { inspectedBindingId.value = ''; choices.value = []; activeBindingId.value = locations.value.find(item => item.fileKey === props.fileKey && prepared.value.bindingIds.includes(item.bindingId ?? ''))?.bindingId ?? '' }
  void focusBinding()
}
function inspect(event: MouseEvent | KeyboardEvent) {
  if (blocked.value) return
  if ('key' in event && event.key !== 'Enter' && event.key !== ' ') return
  const node = (event.target as Element)?.closest<HTMLElement>('[data-review-binding]')
  const binding = allBindings.value.find(item => item.bindingId === node?.dataset.reviewBinding)
  if (!binding || !prepared.value.bindingIds.includes(binding.bindingId)) return
  event.preventDefault(); activeBindingId.value = binding.bindingId; inspectedBindingId.value = binding.bindingId; pendingLocation.value = undefined
  const linked = inspectionKeys.value.filter(key => props.fields.some(field => field.key === key))
  selected.value = linked.length === 1 ? linked[0]! : linked.includes(selected.value) ? selected.value : ''
  choices.value = linked.length > 1 ? linked : []
  void focusBinding(false)
  void nextTick(() => { inspector.value?.scrollTo({ top: 0, behavior: 'auto' }); inspector.value?.focus({ preventScroll: true }) })
}
async function locate(location: DocumentReviewLocation) {
  if (blocked.value) return
  choices.value = []; inspectedBindingId.value = ''
  if (location.fileKey !== props.fileKey) { pendingLocation.value = location; emit('file', location.fileKey); return }
  activeBindingId.value = location.bindingId ?? ''
  if (mode.value === 'saved' && currentBinding.value?.locationStatus === 'removed') mode.value = 'review'
  await focusBinding()
}
function applyGraphNavigation() {
  const request = props.graphNavigation
  if (!request || request.projectId !== props.projectId || request.fileKey !== props.fileKey || !props.fields.some(field => field.key === request.fieldKey)) return false
  if (handledGraphNavigation === `${request.projectId}:${request.sequence}`) return true
  if (blocked.value || !props.original || !props.sourceBindings) return true
  selected.value = request.fieldKey; mode.value = 'original'; choices.value = []; inspectedBindingId.value = ''; pendingLocation.value = undefined
  const actionIds = request.actionId ? [request.actionId] : props.actions.filter(action => action.document === request.fileKey && action.clause === request.clause && (action.inputKeys ?? action.fieldKeys ?? []).includes(request.fieldKey)).map(action => action.id)
  const target = (request.actionId || actionIds.length === 1) ? props.sourceBindings.bindings.find(binding => binding.actionIds.some(id => actionIds.includes(id)) && associatedReviewFields(binding, props.fields, props.actions).includes(request.fieldKey) && prepared.value.bindingIds.includes(binding.bindingId)) : undefined
  activeBindingId.value = target?.bindingId ?? ''
  navigationNotice.value = target ? '' : t('The requested graph location has no verified native binding in this original. Choose a registered location explicitly.', '图谱请求的位置尚无可靠原文映射，请明确选择其他登记位置。', '圖譜請求的位置尚無可靠原文映射，請明確選擇其他登記位置。')
  handledGraphNavigation = `${request.projectId}:${request.sequence}`
  void focusBinding()
  return true
}
watch(() => props.selectedKey, key => { if (key && props.fields.some(item => item.key === key)) chooseField(key) })
watch(() => props.projectId, () => { selected.value = props.selectedKey ?? props.fields[0]?.key ?? ''; fieldSearch.value = ''; activeBindingId.value = ''; inspectedBindingId.value = ''; choices.value = []; pendingLocation.value = undefined; navigationNotice.value = '' })
watch(() => [props.fileKey, props.loading, props.original, props.sourceBindings, props.result, props.resultBindings, props.document?.revisionId, props.document?.docxSha256, props.document?.sourceSha256], () => {
  inspectedBindingId.value = ''
  if (props.loading) return
  if (applyGraphNavigation()) return
  const pending = pendingLocation.value
  const available = locations.value.filter(item => item.fileKey === props.fileKey && prepared.value.bindingIds.includes(item.bindingId ?? ''))
  const requested = pending?.fileKey === props.fileKey
  const first = requested ? available.find(item => !!pending.actionId && item.actionId === pending.actionId || !!pending.clause && item.clause === pending.clause) : available[0]
  activeBindingId.value = first?.bindingId ?? ''; pendingLocation.value = undefined; choices.value = []
  if (requested && !first) {
    navigationNotice.value = t('The requested cross-file location has no verified native binding in this reading. Choose a registered location explicitly.', '请求跳转的跨文件位置尚无可靠正文映射，请明确选择其他登记位置。', '請求跳轉的跨檔案位置尚無可靠正文映射，請明確選擇其他登記位置。')
    void focusBinding(false); return
  }
  void focusBinding()
}, { immediate: true })
watch(() => [props.graphNavigation, props.disabled, props.editing], () => { applyGraphNavigation() }, { immediate: true })
watch(() => html.value, () => { void focusBinding(false) })
watch(() => mode.value, () => { inspectedBindingId.value = ''; choices.value = []; void focusBinding() })
watch(() => props.immersive, () => { void focusBinding() })
</script>
<template>
  <section class="document-review" :class="{ immersive }">
    <nav class="review-fields" :aria-label="t('Select variable annotation', '选择变量批注', '選擇變量批註')"><button v-for="item in fields" :key="item.key" type="button" :data-review-field="item.key" :aria-pressed="selected === item.key" :disabled="blocked" @click="chooseField(item.key)">{{ l(item.label) }}</button></nav>
    <nav class="review-toolbar" :aria-label="t('Document reading modes', '文稿阅读模式', '文稿閱讀模式')">
      <button v-for="item in (['original', 'review', 'saved'] as const)" :key="item" type="button" :data-review-mode="item" :aria-pressed="mode === item" :disabled="blocked || item === 'saved' && !result" @click="changeMode(item)">{{ item === 'original' ? t('Original', '原始模板', '原始模板') : item === 'review' ? t('Review changes', '审阅修改', '審閱修改') : t('Saved result', '已保存文稿', '已儲存文稿') }}</button>
      <button type="button" data-review-pdf :disabled="blocked || mode !== 'original' && !document?.generated" @click="showPdf">{{ t('Check PDF layout', '核对 PDF 版式', '核對 PDF 版式') }}</button>
    </nav>
    <p class="reading-note">{{ mode === 'review' ? t('Review the complete saved revision: red strike-through means deleted text, green means added text, and grey strike-through means template instruction cleanup. Variable selection locates related wording; joint changes remain visible as a whole.', '审阅完整已保存版本：红色删除线为删除、绿色为新增、灰色删除线为模板指引清理。选择变量用于定位关联正文，共同处理仍整体显示。', '審閱完整已儲存版本：紅色刪除線為刪除、綠色為新增、灰色刪除線為模板指引清理。選擇變量用於定位關聯正文，共同處理仍整體顯示。') : t('Complete DOCX content in reading order. PDF provides the final layout check.', '按阅读顺序展示完整 DOCX 正文，PDF 用于最终版式核对。', '按閱讀順序展示完整 DOCX 正文，PDF 用於最終版式核對。') }}</p>
    <p v-if="!loading && !error && !editing && numberingUnverified" class="reading-numbering-notice" data-reading-numbering-notice role="status">{{ t('Web-generated list markers are hidden. Check the PDF for the original labels.', '网页自动标号已隐藏，原编号请核对 PDF。', '網頁自動標號已隱藏，原編號請核對 PDF。') }}</p>
    <p v-if="!staleNoticePresented && (document?.stale || dirtyKeys.length)" class="review-notice" role="status">{{ t('Current variables differ from the saved document. Save/adopt and regenerate through the existing workflow to update that document.', '当前变量与已保存文稿尚未同步，须通过现有流程保存／采用并重新生成文稿。', '目前變量與已儲存文稿尚未同步，須透過現有流程儲存／採用並重新產生文稿。') }}</p>
    <div class="review-workspace">
      <main class="document-column">
        <p v-if="loading" role="status">{{ t('Loading complete document…', '正在载入完整正文…', '正在載入完整正文…') }}</p>
        <div v-else-if="error" role="alert"><p>{{ error }}</p><button type="button" @click="emit('retry')">{{ t('Retry', '重试', '重試') }}</button></div>
        <div v-else-if="editing" class="body-editor-slot"><slot /></div>
        <template v-else><p v-if="prepared.notices.includes('savedReadingFallback')" class="review-notice" data-review-carrier-status="saved-unverified">{{ notice('savedReadingFallback') }}</p><details v-if="warnings.length" class="reading-warnings" data-reading-limits><summary>{{ t('Unverified reading and change mapping', '阅读与修改映射仍未验证', '閱讀與修改映射仍未驗證') }} · {{ warnings.length }}</summary><ul><li v-for="warning in warnings" :key="warning">{{ warning }}</li></ul></details><details v-if="diagnostics.length" class="conversion-diagnostics"><summary>{{ t('Conversion diagnostics', '转换诊断记录', '轉換診斷記錄') }} · {{ diagnostics.length }}</summary><ul><li v-for="diagnostic in diagnostics" :key="diagnostic">{{ diagnostic }}</li></ul></details><p v-if="navigationNotice" class="navigation-notice" role="status">{{ navigationNotice }}</p><div ref="scroll" class="document-scroll"><article ref="paper" data-document-paper class="document-paper" :class="mode" lang="en" @click="inspect" @keydown="inspect" v-html="html" /></div></template>
      </main>
      <aside ref="inspector" tabindex="-1" class="review-inspector" :aria-label="t('Current variable', '当前变量', '目前變量')">
        <section v-if="inspectedBinding" data-change-context class="change-context review-evidence">
          <h3>{{ t('Selected change and evidence', '选中修改与来源依据', '選中修改與來源依據') }}</h3>
          <p data-change-context-description role="status" aria-live="polite">{{ inspectionDescription }}</p>
          <p v-if="inspectionInputs.length" data-change-input-summary><strong>{{ inspectionInputs.map(item => l(item.field?.label) || item.key).join(' / ') }}</strong></p>
          <p v-if="!staleNoticePresented && (document?.stale || dirtyKeys.length)" class="review-notice">{{ t('Values and source records below are current variable details. They do not verify the values used by this saved revision.', '以下取值和来源记录是当前变量详情，不能据此核对本保存版本生成时的取值。', '以下取值和來源記錄是目前變量詳情，不能據此核對本儲存版本產生時的取值。') }}</p>
          <article v-for="action in inspectionActions" :key="action.id" :data-change-action="action.id"><strong>{{ action.document }} · {{ action.clause }}</strong><p>{{ draftWord((action.action === 'fill' ? 'amend' : action.action) as DraftWord, locale) }}</p><p v-if="!document?.stale && !dirtyKeys.length">{{ l(action.detail) }}</p></article>
          <article v-for="item in inspectionInputs" :key="item.key" :data-change-variable="item.key" class="change-variable">
            <h4>{{ l(item.field?.label) || item.key }}</h4>
            <template v-if="item.field"><p class="input-state">{{ t('Current value · read only', '当前取值 · 此处只读', '目前取值 · 此處唯讀') }}</p><div data-change-current-value><DraftingValueDisplay :field="item.field" :value="values[item.key]" :locale="locale" /></div></template>
            <p v-else class="review-notice">{{ t('This recorded variable has no current field definition.', '此登记变量暂无对应的当前字段定义。', '此登記變量暫無對應的目前欄位定義。') }}</p>
            <p v-if="item.variable?.reviewRequired" class="review-notice">{{ t('Adopted value requires review.', '采用值需要复核。', '採用值需要覆核。') }}</p>
            <p v-if="item.variable?.manuallyEdited" class="review-notice">{{ t('The current value was manually edited; recorded model candidates may differ.', '当前取值经人工修改，登记的模型候选可能与其不同。', '目前取值經人工修改，登記的模型候選可能與其不同。') }}</p>
            <h4>{{ t('Recorded source evidence', '登记的来源依据', '登記的來源依據') }}</h4>
            <p v-if="item.variable?.source" class="source-reference">{{ item.variable.source }}</p>
            <p v-if="!item.variable?.source && !item.variable?.candidates?.some(candidate => candidate.sourceQuote)">{{ t('No source quotation is recorded for this variable.', '此变量尚未登记来源引文。', '此變量尚未登記來源引文。') }}</p>
            <article v-for="({ candidate, context }, index) in item.candidates" :key="index">
              <strong v-if="candidate.fileName">{{ candidate.fileName }}</strong>
              <p>{{ t('Recorded candidate value', '登记的候选值', '登記的候選值') }}: {{ candidate.value }}</p>
              <blockquote v-if="candidate.sourceQuote">{{ candidate.sourceQuote }}</blockquote>
              <details v-if="context" data-evidence-context><summary>{{ t('Expand recorded source context', '展开登记的来源上下文', '展開登記的來源上下文') }}</summary><p v-if="trace?.stale" class="review-notice">{{ t('This historical extraction record is stale; it does not verify the latest source.', '此历史识别记录已过期，不能据此核对最新来源。', '此歷史識別記錄已過期，不能據此核對最新來源。') }}</p><pre data-evidence-context-text>{{ context.sourceText }}</pre></details>
              <p v-else-if="candidate.sourceQuote" data-evidence-context-unavailable class="review-notice">{{ t('No unique matching source-context record is available for this quotation.', '此引文尚无唯一匹配的来源上下文记录。', '此引文尚無唯一匹配的來源上下文記錄。') }}</p>
              <p v-if="candidate.reason">{{ t('Recorded explanation', '登记的识别说明', '登記的識別說明') }}: {{ candidate.reason }}</p>
            </article><p v-if="item.variable?.note">{{ item.variable.note }}</p>
          </article>
        </section>
        <section v-if="choices.length" class="association-choices"><h4>{{ t('Related variable evidence is shown above. Select a variable for more detail.', '相关变量的证据已在上方显示，可选择一项查看更多详情。', '相關變量的證據已在上方顯示，可選擇一項查看更多詳情。') }}</h4><button v-for="key in choices" :key="key" type="button" :data-association-choice="key" :aria-pressed="selected === key" :disabled="blocked" @click="chooseField(key, true)">{{ l(fields.find(item => item.key === key)?.label) }}</button></section>
        <fieldset class="review-field-picker" :disabled="blocked"><DraftingQuestionPicker :model-value="selected" v-model:query="fieldSearch" :items="fieldChoices" :locale="locale" :label="t('Search variables', '搜索变量', '搜尋變量')" :empty-label="t('Select variable details', '选择变量详情', '選擇變量詳情')" :retained-label="t('Current variable definition unavailable: ', '当前变量定义不可用：', '目前變量定義不可用：') + selected" search-attribute="data-review-field-search" :context="fileKey" data-review-field-picker @update:model-value="key => chooseField(key, !!inspectedBinding && inspectionKeys.includes(key))" /></fieldset>
        <template v-if="field"><h3>{{ l(field.label) }}</h3><h4>{{ t('Current value · read only', '当前取值 · 此处只读', '目前取值 · 此處唯讀') }}</h4><div data-current-value><DraftingValueDisplay :field="field" :value="values[field.key]" :locale="locale" /></div><p class="input-state">{{ inputState }}</p><p v-if="variable?.reviewRequired" class="review-notice">{{ t('Adopted value requires review.', '采用值需要复核。', '採用值需要覆核。') }}</p><button type="button" data-return-input :disabled="blocked" @click="returnInput">{{ t('Return to variable', '返回变量修改', '返回變量修改') }}</button>
          <section class="linked-locations"><h4>{{ t('All linked locations', '全部关联位置', '全部關聯位置') }} · {{ locations.length }}</h4><p v-if="!locations.length">{{ t('No registered location for this variable.', '此变量尚无登记位置。', '此變量尚無登記位置。') }}</p><button v-for="location in locations" :key="location.id" type="button" :data-review-location="location.bindingId ?? location.id" :data-review-location-file="location.fileKey" :aria-pressed="location.bindingId === activeBindingId && location.fileKey === fileKey" :disabled="blocked" @click="locate(location)"><strong>{{ location.fileKey }} · {{ location.clause }}</strong><small v-if="location.paragraph">P{{ location.paragraph }}</small><small v-if="location.status === 'removed'">{{ t('Removed in saved document', '已从文稿删除', '已從文稿刪除') }}</small></button></section>
          <section v-if="currentBinding" class="binding-status"><p v-if="changeDescription" data-review-change-description>{{ changeDescription }}</p><h4>{{ t('Selected paragraph / registered group', '选中段落／登记范围', '選中段落／登記範圍') }}</h4><p v-if="!currentBinding.sourceParagraphId">{{ t('Saved row registered as added; no verified one-to-one original mapping.', '文稿行登记为新增，尚无可靠的一对一原文映射。', '文稿行登記為新增，尚無可靠的一對一原文映射。') }}</p><p v-if="!currentSavedBinding">{{ t('A source location is registered, but its saved change status cannot be verified.', '已登记原文位置，但尚无法核对其已保存修改状态。', '已登記原文位置，但尚無法核對其已儲存修改狀態。') }}</p><p v-else>{{ currentSavedBinding.locationStatus === 'removed' ? t('This paragraph was removed.', '此段落已删除。', '此段落已刪除。') : currentSavedBinding.operationIds.length || currentSavedBinding.bodyOperationIds?.length ? t('The saved ledger records an applied document edit.', '已保存记录登记了文稿修改。', '已儲存記錄登記了文稿修改。') : currentSavedBinding.sourceText === currentSavedBinding.text ? t('Related wording is unchanged in the saved document.', '关联正文在已保存文稿中未改动。', '關聯正文在已儲存文稿中未改動。') : t('The saved wording differs, but an applied edit record is unavailable.', '已保存正文存在差异，但尚无可靠已应用修改记录。', '已儲存正文存在差異，但尚無可靠已套用修改記錄。') }}</p></section>
          <section v-if="!inspectedBinding || !inspectionKeys.includes(field.key)" class="review-evidence"><h4>{{ t('Source evidence', '来源依据', '來源依據') }}</h4><p v-if="variable?.source" class="source-reference">{{ variable.source }}</p><p v-if="!variable?.source && !variable?.candidates?.some(candidate => candidate.sourceQuote)">{{ t('No source quotation is recorded for this variable.', '此变量尚未登记来源引文。', '此變量尚未登記來源引文。') }}</p><article v-for="({ candidate, context }, index) in evidenceCandidates" :key="index"><strong v-if="candidate.fileName">{{ candidate.fileName }}</strong><blockquote v-if="candidate.sourceQuote">{{ candidate.sourceQuote }}</blockquote><details v-if="context" data-evidence-context><summary>{{ t('Expand recorded source context', '展开登记的来源上下文', '展開登記的來源上下文') }}</summary><p v-if="trace?.stale" class="review-notice">{{ t('This historical extraction record is stale; it does not verify the latest source.', '此历史识别记录已过期，不能据此核对最新来源。', '此歷史識別記錄已過期，不能據此核對最新來源。') }}</p><pre data-evidence-context-text>{{ context.sourceText }}</pre></details><p v-else-if="candidate.sourceQuote" data-evidence-context-unavailable class="review-notice">{{ t('No unique matching source-context record is available for this quotation.', '此引文尚无唯一匹配的来源上下文记录。', '此引文尚無唯一匹配的來源上下文記錄。') }}</p><p v-if="candidate.reason">{{ t('Recorded explanation', '登记的识别说明', '登記的識別說明') }}: {{ candidate.reason }}</p></article><p v-if="variable?.note">{{ variable.note }}</p></section>
          <section class="review-actions"><h4>{{ document?.stale || dirtyKeys.length ? t('Current pending plan actions', '当前待应用的处理方案', '目前待套用的處理方案') : t('Related clause treatments', '关联条款处理', '關聯條款處理') }}</h4><article v-for="action in relatedActions" :key="action.id"><strong>{{ action.document }} · {{ action.clause }}</strong><p>{{ l(action.detail) }}</p><p v-if="action.sourceWarning" class="review-notice">{{ l(action.sourceWarning) }}</p></article></section>
        </template>
      </aside>
    </div>
  </section>
</template>
<style scoped>
.review-field-picker { min-width: 0; margin: 0; padding: 0; border: 0; }
.document-review { display: flex; flex-direction: column; min-width: 0; gap: 12px; }.review-toolbar { display: flex; gap: 6px; flex-wrap: wrap; }.review-workspace { display: grid; grid-template-columns: minmax(0,1fr) 310px; gap: 14px; align-items: stretch; }.document-column { display: flex; flex-direction: column; min-height: 0; min-width: 0; }.document-scroll { height: max(600px, calc(100dvh - 270px)); overflow: auto; padding: 20px; background: #edf1ef; }.document-paper { margin: 0 auto; max-width: 900px; padding: 38px; background: #fff; color: #26312b; font: 16px/1.65 'Times New Roman',serif; }.review-inspector { min-width: 0; padding: 16px; border: 1px solid var(--line); background: var(--surface); overflow: auto; max-height: max(600px, calc(100dvh - 270px)); }.document-paper :deep(p) { white-space: pre-wrap; }.immersive { height: 100%; min-height: 0; }.immersive .review-workspace { flex: 1; min-height: 0; }.immersive .document-scroll { flex: 1; height: auto; min-height: 0; }.immersive .review-inspector { max-height: none; min-height: 0; }@media(max-width:850px){.review-workspace { grid-template-columns: minmax(0,1fr) 260px; }}@media(max-width:650px){.review-workspace { grid-template-columns: 1fr; }.document-paper { padding: 18px; }.review-inspector { max-height: 350px; }}
.review-fields { display: flex; flex-wrap: nowrap; overflow-x: auto; gap: 6px; padding: 2px 0 5px; flex-shrink: 0; }.review-fields button { flex-shrink: 0; max-width: 240px; text-align: left; }
.document-review button, .document-review select { border: 1px solid var(--line, #ccd8d1); background: var(--surface, #fff); color: var(--ink, #213b30); border-radius: 4px; padding: 8px 10px; font: inherit; font-size: 12px; }.document-review button { cursor: pointer; }.document-review button:disabled { cursor: default; opacity: .55; }.document-review [aria-pressed=true] { border-color: var(--accent, #387e68); background: var(--accent-soft, #eaf4ee); }.document-review button:focus-visible, .document-review select:focus-visible { outline: 2px solid var(--accent, #387e68); outline-offset: 2px; }
.reading-note { margin: 0; font-size: 11px; color: var(--muted, #68766e); line-height: 1.5; }.review-notice, .navigation-notice { margin: 0 0 8px; padding: 9px 11px; font-size: 12px; line-height: 1.6; background: #fff8e9; color: #735821; border-left: 3px solid #c8a66b; }.navigation-notice { background: #f2f6f3; color: #425b4e; border-color: #8ea89a; }
.reading-warnings { margin-bottom: 8px; padding: 8px 11px; max-height: 160px; overflow: auto; flex-shrink: 0; background: #fff8e9; color: #735821; font-size: 12px; line-height: 1.6; }.reading-warnings summary { cursor: pointer; }.reading-warnings ul { margin: 6px 0 0; padding-left: 18px; }.conversion-diagnostics { margin: 0 0 8px; font-size: 11px; color: var(--muted, #68766e); flex-shrink: 0; }.conversion-diagnostics summary { cursor: pointer; }.conversion-diagnostics ul { max-height: 120px; overflow: auto; }
.review-inspector select { width: 100%; }.review-inspector h3 { margin: 15px 0; font-size: 16px; }.review-inspector h4 { margin: 0 0 9px; font-size: 12px; }.review-inspector section { margin-top: 19px; padding-top: 15px; border-top: 1px solid var(--line, #d5ded9); }.review-inspector p { font-size: 12px; line-height: 1.6; white-space: pre-wrap; overflow-wrap: anywhere; }.input-state { color: var(--muted, #66766c); }.review-inspector article + article { margin-top: 13px; }.review-inspector article strong { font-size: 12px; }.review-inspector blockquote { margin: 8px 0; padding-left: 10px; border-left: 2px solid #afc7bb; font: 13px/1.6 Georgia,serif; white-space: pre-wrap; overflow-wrap: anywhere; }.linked-locations button { display: flex; width: 100%; text-align: left; gap: 5px; flex-wrap: wrap; margin: 5px 0; }.linked-locations small { color: var(--muted, #66766c); }.association-choices button { margin: 3px; }
.document-paper { box-sizing: border-box; width: 100%; overflow-wrap: anywhere; }.document-paper :deep(p) { margin: 0 0 .75em; min-height: .2em; }.document-paper :deep(h1), .document-paper :deep(h2), .document-paper :deep(h3), .document-paper :deep(h4) { margin: 1.6em 0 .7em; line-height: 1.3; }.document-paper :deep(h1) { font-size: 1.45em; }.document-paper :deep(h2) { font-size: 1.3em; }.document-paper :deep(table) { border-collapse: collapse; width: 100%; margin: 1em 0; }.document-paper :deep(td), .document-paper :deep(th) { border: 1px solid #d8e0da; padding: 6px 8px; vertical-align: top; }.document-paper :deep(ul), .document-paper :deep(ol) { padding-left: 1.8em; }.document-paper :deep(li) { margin-bottom: .4em; }.document-paper :deep(img) { max-width: 100%; height: auto; }.document-paper :deep(sub), .document-paper :deep(sup) { line-height: 0; }.document-paper :deep([data-native-math]) { white-space: pre-wrap; }
.document-paper :deep([data-review-binding]) { cursor: pointer; }.document-paper :deep(.review-associated) { border-left: 2px solid #b9cdc3; padding-left: 6px; }.document-paper :deep(.review-focused) { outline: 2px solid #58856d; outline-offset: 5px; }.document-paper :deep([data-review-binding]:focus-visible) { outline: 2px solid #58856d; outline-offset: 5px; }
.document-paper :deep(.review-deleted-text) { color: #993f47; background: #fbe9eb; text-decoration: line-through; }.document-paper :deep(.review-inserted-text) { color: #276344; background: #e8f4ec; text-decoration: none; }.document-paper :deep(.review-retained) { border-left: 3px solid #2f8c82; padding-left: 9px; }.document-paper :deep(.review-removed) { border-left: 3px solid #b7454b; padding-left: 9px; color: #983f46; background: #fbedef; text-decoration: line-through; }.document-paper :deep(.review-guidance) { border-left: 3px dotted #aaa095; padding-left: 9px; color: #827970; background: #f3eee8; text-decoration: line-through; }.document-paper :deep(.review-added) { border-left: 3px solid #4c8c60; padding-left: 9px; color: #276344; background: #eaf5ed; }.document-paper :deep(.review-added)::before { content: attr(data-review-addition-label); display: block; margin-bottom: 4px; font: 10px/1.4 var(--font-ui,sans-serif); color: #4a7056; }
.immersive .review-fields { display: none; }.immersive .review-inspector { overscroll-behavior: contain; }.document-scroll { overscroll-behavior: contain; }.body-editor-slot { min-height: 0; flex: 1; overflow: auto; }
.review-evidence summary { cursor: pointer; font-size: 12px; padding: 5px 0; }.review-evidence pre { max-height: 320px; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; font: 12px/1.6 var(--font-mono,monospace); }
.review-inspector .change-context { margin-top: 0; padding-top: 0; border-top: 0; }.change-context > h3 { margin-top: 0; }.change-variable { padding: 12px 0; border-top: 1px solid var(--line, #d5ded9); }.change-variable > h4 { margin-top: 10px; }
.document-paper :deep(p:empty) { margin: 0; min-height: 0; }.document-paper :deep(table), .document-paper :deep(td), .document-paper :deep(th), .document-paper :deep(li) { font: inherit; }
@media(max-width:650px){.immersive .review-workspace { grid-template-rows: minmax(0,1fr) minmax(0,.55fr); }.immersive .review-inspector { max-height: none; }.document-scroll { padding: 9px; }.document-paper { font-size: 14px; }.document-paper :deep(td), .document-paper :deep(th) { padding: 4px; }}
</style>
