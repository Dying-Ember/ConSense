<script setup lang="ts">
import { computed, defineAsyncComponent, nextTick, onBeforeUnmount, reactive, ref, shallowRef, watch } from 'vue'
import { draftingApi } from '@/api'
import { reviewSourceBindings } from '@/api/source-locations'
import { containTab } from '@/focus'
import type { DraftCatalog, DraftDocumentBindings } from '@/api/types'
import { bindingPoint, matchesBindingPdf, pdfSha256, type PdfBindingLocation } from '@/drafting/document-bindings'
import AppModal from './AppModal.vue'
import DraftingQuestionBatch from './DraftingQuestionBatch.vue'
import DraftingPdfPreview from './DraftingPdfPreview.vue'
import DraftingQuestionPicker from './DraftingQuestionPicker.vue'
import DraftingVariableCatalog from './DraftingVariableCatalog.vue'
import DraftingFixedRuleSuggestions from './DraftingFixedRuleSuggestions.vue'
import DraftingPdfSccReference from './DraftingPdfSccReference.vue'
import DraftingNewVariable from './DraftingNewVariable.vue'
import { baselineVariables, baselineNode, businessVariable, compatibleBaseline } from '@/drafting/variable-catalog'
import { questionSearchMatches } from '@/drafting/question-search'
import { bindPageSelection, type SourcePageSelection } from '@/drafting/source-page-selection'
import { createReviewReducer } from '@/drafting/source-review/reducer.js'
import { isSourceCheck, pendingEditorial, isTreatmentRule, isValueVariable, valueDependencies } from '@/drafting/source-review/value-dependencies.js'
import graphView from '@/drafting/source-review/graph-business-view.js'
import { appendNativeSelection, nativeAnchor, emptyReviewState, reviewProblem, sourceReviewDrafts, targetSpans } from '@/drafting/source-review-state'
import type { ReviewAnchor, ReviewGraph, ReviewHead, ReviewObject, ReviewReading, ReviewRevision, ReviewRevisionSummary, ReviewSource, ReviewSpan, ReviewState, ReviewVariable } from '@/drafting/source-review-types'
import type { RelationReviewRequest } from '@/drafting/automation-types'
const DraftingWordSource = defineAsyncComponent(() => import('./DraftingWordSource.vue'))
const reviewDraftCache = sourceReviewDrafts as Map<string, NonNullable<ReturnType<typeof sourceReviewDrafts.get>> & { batch?: { ids: string[]; open: boolean; draft?: ReviewObject }; newConditionInput?: ReviewObject; catalogDraft?: ReviewObject; catalogOpen?: boolean; view?: { immersive: boolean; page: number; editorScrollTop: number; editingQuestion: boolean; queries: Record<string, string> } }>
const props = defineProps<{ projectId: string; locale: string; active?: boolean; locked?: boolean }>()
const emit = defineEmits<{ dirty: [value: boolean]; confirmed: []; saved: [revision: ReviewRevision]; replace: [] }>()
const t = (zh: string, en: string) => props.locale === 'en' ? en : zh
const copy = <T,>(value: T): T => JSON.parse(JSON.stringify(value))
const sourceExcerpt=(value:unknown)=>{const text=String(value??'').replace(/\s+/g,' ').trim();return text.length>180?text.slice(0,180)+'…':text}
function sourceIssueMessage(issue: ReviewObject) {
  if (issue.code === 'GUIDANCE_SOURCE_SYNTAX_INCOMPLETE') return t('指导说明未找到结束标记。请对照原件核对说明范围；系统保留已识别原文，不补造结束位置。', issue.message)
  return issue.message || t('此处来源关系尚未确认，请对照原件核对。', 'This source relationship remains unverified; check the original.')
}
function targetBindingMessage(target: ReviewObject) {
  return target.nativeBinding?.reason === 'NATIVE_SPANS_REQUIRED'
    ? t('已找到相关原文，但尚未指定准确修改位置。请在左侧原文选词，再建立修改目标；同一答案要填多处时，每处分别建立目标。', 'Related source text was found, but the exact amendment range is missing. Select words on the left and create a target; create separate targets when the same answer must fill several places.')
    : t('当前修改位置未通过原件校验。请核对所选原件版本，并重新选择准确文字；现有记录已保留。', 'This amendment range has not passed source validation. Check the selected source edition and select the exact words again; the existing record is retained.')
}
const head = shallowRef<ReviewHead>({ currentRevisionId: null, draftRevisionId: null, current: null, draft: null, sources: [] })
const scopeBinding = shallowRef<{ integrity: Record<string, string>; entries: Record<string, string> }>({integrity:{},entries:{}})
function bindScopeRevision(revision?: ReviewRevision | null) {
  const entries: Record<string,string>={};for(const [key,value] of Object.entries(revision?.graph?.graph?.sourceScopeObservations ?? {}))entries[key]=JSON.stringify(value)
  scopeBinding.value={integrity:{...(revision?.scopeObservationIntegrity ?? {})},entries}
}
function workingReview(value: ReviewHead) {
  if (value.workingRevisionId === undefined) return value.draft ?? value.current
  return [value.draft, value.current].find(item => item?.id === value.workingRevisionId) ?? null
}
const graph = shallowRef<ReviewGraph>({ kind: 'consense-template-graph-v1', graph: emptyReviewState(), sources: [], selectedDocuments: {} })
const reading = shallowRef<ReviewReading>(), sourceId = ref(''), selectedId = ref(''), selection = shallowRef<ReviewAnchor>()
const focusText=ref(''),focusPage=ref<number>(),focusRequest=ref(0),selectionError=ref('')
const pageSelection=shallowRef<SourcePageSelection>(),manualParagraphId=ref('')
const sourceMode = ref<'pdf' | 'word'>('pdf'), wordOpened = ref(false), wordSource = ref<InstanceType<typeof DraftingWordSource>>(), pdfSource = ref<InstanceType<typeof DraftingPdfPreview>>()
const manualParagraphs=computed(()=>{
  const receipt=pageSelection.value,first=receipt?.words[0]
  if(!reading.value||!receipt||!first||!sourceBindings.value||selection.value?.native)return []
  return sourceBindings.value.bindings.flatMap(row=>{
    const point=bindingPoint(row),paragraph=reading.value!.paragraphs.find(p=>p.id===row.sourceParagraphId&&p.text===row.sourceText)
    return point?.pageNumber===receipt.page&&paragraph?[{paragraph,distance:Math.hypot(point.x-first.rectangle.x,point.y-first.rectangle.y)}]:[]
  }).sort((a,b)=>a.distance-b.distance).slice(0,5).map(row=>row.paragraph)
})
const manualParagraph=computed(()=>manualParagraphs.value.find(p=>p.id===manualParagraphId.value))
function useWholeParagraph(){
  const paragraph=manualParagraph.value
  if(!reading.value||!paragraph||!pageSelection.value)return
  selection.value={...nativeAnchor(reading.value,paragraph.id,0,paragraph.text.length),page:pageSelection.value.page};selectionError.value=''
}
const sourceBindings=shallowRef<DraftDocumentBindings>(),locationParagraphs=ref<string[]>([]),locationSpans=ref<ReviewSpan[]>([]),selectedBindingId=ref('')
const sourceLocations=computed<PdfBindingLocation[]>(()=>{
  if(!reading.value||!sourceBindings.value)return []
  return locationParagraphs.value.flatMap((id,index)=>{
    const paragraph=reading.value!.paragraphs.find(item=>item.id===id)
    const rows=sourceBindings.value!.bindings.filter(row=>row.sourceParagraphId===id&&row.sourceText===paragraph?.text)
    if(rows.length!==1)return []
    const row=rows[0]!,geometry=bindingPoint(row)
    return geometry?[{bindingId:row.bindingId,label:t('关联原文位置 ', 'Linked source position ')+(index+1),geometry,text:row.sourceText}]:[]
  })
})
const locationIndex=computed(()=>sourceLocations.value.findIndex(location=>location.bindingId===selectedBindingId.value))
watch(sourceLocations,locations=>{
  selectedBindingId.value=locations[0]?.bindingId??'';focusRequest.value++
  if(sourceBindings.value&&locationParagraphs.value.length)locationNotice.value=locations.length===locationParagraphs.value.length?'':t('部分原文位置尚未取得当前PDF坐标，已显示可定位的部分；原件仍可浏览。', 'Some source positions have no current PDF coordinates. Available positions are shown; the original remains readable.')
})
function moveLocation(delta:number){const location=sourceLocations.value[locationIndex.value+delta];if(location){selectedBindingId.value=location.bindingId;focusRequest.value++}}
const locationNotice=ref('')
function locationUnavailable(reason: 'not_found' | 'unreadable') {
  locationNotice.value = reason === 'unreadable'
    ? t('暂时无法检索页面文字，原件仍可浏览。可继续翻页，或在页面上选词核对。', 'Page text could not be searched. You can still browse, change pages or select words on the original.')
    : t('未在页面文字中找到这段原文，原件仍可浏览。请对照页面选取准确文字。', 'This passage was not found in the page text. You can still browse the original and select the exact words.')
}
const busy = ref(false), error = ref(''), dirty = ref(false), search = ref(''), formBase = ref(''), formOpen = ref(false)
const saving = ref(false)
const catalogOpen = ref(false), baselineCatalog = shallowRef<DraftCatalog>(), catalogLoading = ref(false), catalogError = ref('')
const emptyCatalogDraft = () => ({ tab: 'matched', nodeId: '', baselineKey: '', proposalVersion: '', missingKey: '', query: '', baselineQuery: '', missingQuery: '', reason: '', scope: '' })
const catalogDraft = reactive<ReviewObject>(emptyCatalogDraft())
async function openCatalog() {
  catalogOpen.value = !catalogOpen.value
  if (!catalogOpen.value || baselineCatalog.value || catalogLoading.value) return
  const project = props.projectId; catalogLoading.value = true; catalogError.value = ''
  try { const result = await draftingApi.baselineCatalog(project); if (project === props.projectId) baselineCatalog.value = result }
  catch (caught) { if (project === props.projectId) catalogError.value = String(caught) }
  finally { if (project === props.projectId) catalogLoading.value = false }
}
function alignVariable(id: string, alignment: ReviewObject) {
  if (!protect() || splitDirty.value || !baselineCatalog.value) return
  const node = state.value.variables.find(item => item.id === id)
  if (!node || !businessVariable(node) || !alignment.reason?.trim()) return
  if (alignment.status === 'matched') {
    const field = baselineVariables(baselineCatalog.value).find(item => item.baselineKey === alignment.baselineKey)
    if (!field || !compatibleBaseline(node, field, state.value, alignment.scope ?? '')) { error.value = t('变量类型、单位、选项或明细归属与基准不相容。', 'The type, unit, options or detail membership is incompatible with the baseline.'); return }
    if (state.value.variables.some(item => item.id !== id && businessVariable(item) && item.catalogAlignment?.confirmed && item.catalogAlignment.baselineKey === alignment.baselineKey && (item.parentId ?? '') === (node.parentId ?? '') && (item.catalogAlignment.scope ?? '') === (alignment.scope ?? ''))) { error.value = t('已有变量对应同一基准和范围；请先核对并合并，或明确不同适用范围。', 'Another variable already uses this baseline and scope. Review and merge it, or identify the distinct scope.'); return }
  }
  const reviewed = { ...node.catalogAlignment, ...alignment }
  if (alignment.status !== 'matched') { delete reviewed.baselineKey; delete reviewed.baselineVersion }
  if (!alignment.scope) delete reviewed.scope
  apply({ type: 'EDIT', id, values: { catalogAlignment: reviewed } }); resetForm(); catalogDraft.tab = alignment.status; catalogDraft.reason = ''
}
function excludeBaseline(key: string, reason: string | null) {
  if (!protect() || splitDirty.value || !baselineCatalog.value) return
  apply({ type: 'CATALOG_EXCLUDE', key, reason, baselineVersion: baselineCatalog.value.ruleVersion }); catalogDraft.reason = ''; catalogDraft.missingKey = ''
}
function addBaseline(key: string, reason: string) {
  if (!protect() || splitDirty.value || !baselineCatalog.value || !reason.trim()) return
  const fields = baselineVariables(baselineCatalog.value), requested = fields.find(field => field.baselineKey === key)
  if (!requested) return
  const actions: ReviewObject[] = [], ids = new Map(state.value.variables.filter(businessVariable).filter(item => item.catalogAlignment?.confirmed).map(item => [item.catalogAlignment.baselineKey, item.id]))
  let nextId = state.value.nextId
  const append = (field: typeof requested) => {
    if (ids.has(field.baselineKey)) return
    if (field.parentKey && !ids.has(field.parentKey)) { const parent = fields.find(item => item.baselineKey === field.parentKey); if (parent) append(parent) }
    ids.set(field.baselineKey, `review-${nextId++}`)
    actions.push({ type: 'ADD', values: baselineNode(field, baselineCatalog.value!.ruleVersion, reason, field.parentKey ? ids.get(field.parentKey)! : null), reason })
  }
  let root = requested
  while (root.parentKey) { const parent = fields.find(field => field.baselineKey === root.parentKey); if (!parent) break; root = parent }
  const mapped = state.value.variables.filter(item => businessVariable(item) && item.catalogAlignment?.confirmed && (item.catalogAlignment.baselineKey === root.baselineKey || item.catalogAlignment.baselineKey?.startsWith(root.baselineKey + '.')))
  if (mapped.some((item,index) => mapped.slice(index+1).some(other => other.catalogAlignment.baselineKey === item.catalogAlignment.baselineKey))) { error.value = t('此基准已有多个适用范围；请从“新增变量”明确选择所属明细变量后再对齐，不能自动选择其中一个范围。', 'This baseline has multiple scopes. Add the variable with an explicit detail parent, then align it; a scope cannot be selected automatically.'); return }
  const excluded = new Set(Object.entries(state.value.catalogReview?.exclusions ?? {}).filter(([,entry]) => entry.baselineVersion === baselineCatalog.value!.ruleVersion).map(([key]) => key))
  const subtree = fields.filter(field => field.baselineKey === root.baselineKey || field.baselineKey.startsWith(root.baselineKey + '.'))
  if (excluded.has(root.baselineKey) || subtree.some(field => excluded.has(field.baselineKey) && !field.optional && ![...excluded].some(key => field.baselineKey.startsWith(key + '.')))) { error.value = t('所属明细或必需列已记录不适用；请先在基准审核中重新核对，不会自动覆盖该决定。', 'The detail group or a required column was marked not applicable. Reopen that decision in baseline review before adding it.'); return }
  append(root)
  for (const child of subtree.filter(field => field.baselineKey !== root.baselineKey && ![...excluded].some(key => field.baselineKey === key || field.baselineKey.startsWith(key + '.')))) append(child)
  if (!actions.length) return
  applyBatch(actions); catalogDraft.reason = ''; catalogDraft.missingKey = ''
}
const graphSelected = ref(''), graphOnlySelected = ref(false)
const showGraph = ref(false), historyOpen = ref(false), history = ref<ReviewRevisionSummary[]>([]), pdfUrl = ref('')
const originalLoading = ref(false), originalError = ref('')
const sourceLoading=ref(false)
const immersive = ref(false), workspace = ref<HTMLElement>(), immersiveToggle = ref<HTMLButtonElement>()
const initialPdfPage = ref(1)
const workspaceHeight = ref(0)
function setImmersive(expanded: boolean) {
  if (expanded) workspaceHeight.value = workspace.value?.getBoundingClientRect().height ?? 0
  immersive.value = expanded
  nextTick(() => (expanded ? workspace.value : immersiveToggle.value)?.focus?.({ preventScroll: true }))
}
function workspaceKeydown(event: KeyboardEvent) {
  if (!immersive.value || event.defaultPrevented) return
  if (event.key === 'Escape') {
    event.preventDefault(); event.stopPropagation()
    if (batchOpen.value) batchOpen.value = false
    else if (splitOpen.value) splitOpen.value = false
    else if (showGraph.value) showGraph.value = false
    else if (historyOpen.value) historyOpen.value = false
    else setImmersive(false)
    nextTick(() => { if (immersive.value) workspace.value?.focus?.({ preventScroll: true }) })
  } else if (event.key === 'Tab' && workspace.value) {
    const modal = workspace.value.closest('.source-review')?.querySelector<HTMLElement>('.modal-backdrop.show [role="dialog"]')
    containTab(event, modal ?? workspace.value)
  }
}
watch(immersive, (expanded, _previous, onCleanup) => {
  if (!expanded) return
  const overflow = document.body.style.overflow
  document.body.style.overflow = 'hidden'
  onCleanup(() => { document.body.style.overflow = overflow })
})
watch([() => props.projectId, () => props.active], () => { immersive.value = false })
const splitOpen = ref(false), splitNames = reactive({ firstZh: '', firstEn: '', secondZh: '', secondEn: '', mode: 'unknown' })
const splitFirst = shallowRef<ReviewAnchor>(), splitSecond = shallowRef<ReviewAnchor>()
const checkedIds = ref<string[]>([]), batchOpen = ref(false), batchError = ref(''), batchDraft = ref<ReviewObject>()
const checkedQuestions = computed(() => state.value.variables.filter(item => checkedIds.value.includes(item.id) && item.status !== 'excluded'))
const checkedQuestionIds = computed(() => checkedQuestions.value.map(item => item.id))
function toggleChecked(id: string) { checkedIds.value = checkedIds.value.includes(id) ? checkedIds.value.filter(value => value !== id) : [...checkedIds.value, id]; batchDraft.value = undefined }
function openBatch(includeSelected = false) {
  if (!protect() || splitDirty.value) return
  if (includeSelected && selected.value && !checkedIds.value.includes(selected.value.id)) toggleChecked(selected.value.id)
  if (!checkedQuestions.value.length) return
  batchError.value = ''; batchOpen.value = true
}
function applyBatch(actions: ReviewObject[], targetId?: string) {
  if (!protect()) return
  try {
    const reducer = createReviewReducer({ variables: state.value.variables, sourceUnits: [], sources: graph.value.sources })
    let next = state.value
    for (const action of actions) {
      const result = reducer.reduce(next, { ...action, at: new Date().toISOString() })
      if (result === next) throw Error(t('来源版次或关联条件不相容；本组未作任何修改。请逐处核对原文及前提。', 'Source editions or linked conditions are incompatible. This batch made no changes; review the passages and prerequisites.'))
      next = result
    }
    if (valueDependencies(next).some(edge => edge.cyclic)) throw Error(t('本组会产生派生答案循环；请先核对取值规则及明细归属。未记录本组调整。', 'This batch would create a derived-answer cycle. Review the value rules and record membership; the batch was not recorded.'))
    undo.value = [...undo.value.slice(-49), state.value]; graph.value = { ...graph.value, graph: next }; changed()
    if (targetId) selectedId.value = targetId
    resetForm(); editingQuestion.value = true; batchOpen.value = false; batchDraft.value = undefined; checkedIds.value = []; error.value = ''
  } catch (caught) { batchError.value = caught instanceof Error ? caught.message : String(caught) }
}
function mergeChecked(ids: string[], targetId: string) {
  const reducer = createReviewReducer({ sources: graph.value.sources })
  if (reducer.mergeMany(state.value, ids, targetId).problem) { batchError.value = t('勾选项目或规则已经变化，请重新查看对照；未作任何修改。', 'The selected items or rules changed. Review the comparison again; no changes were made.'); return }
  applyBatch([{ type: 'MERGE_MANY', ids, targetId, reason: '人工逐项对照后一次合并 / Explicit batch duplicate review' }], targetId)
}
async function locateBatchQuestion(id: string) { if (!protect()) return; batchOpen.value = false; await choose(id); editingQuestion.value = true }
const undo = shallowRef<ReviewState[]>([])
const questionPage=ref(0),questionKind=ref('business'),editingQuestion=ref(false)
function questionKindOf(item: ReviewObject) { return isSourceCheck(item) ? 'checks' : businessVariable(item) ? 'business' : 'rules' }
const questionRows=computed(()=>filtered.value.filter(item=>questionKindOf(item)===questionKind.value))
const shownQuestions=computed(()=>questionRows.value.slice(questionPage.value*8,questionPage.value*8+8))
watch([search,sourceId,questionKind],()=>questionPage.value=0)
watch([sourceId,selectedId],()=>locationNotice.value='')
watch(sourceId,()=>{sourceBindings.value=undefined;locationParagraphs.value=[];locationSpans.value=[];selectedBindingId.value=''})
const form = reactive<ReviewObject>({ nameZh: '', nameEn: '', kind: 'boolean', unit: '', optional: false, scope: '', manualReason: '', role: 'input', parentId: '', fixedValue: '', optionsText: '', splitMode: 'unknown', branches: [], targets: [] })
const optionQueries = reactive<Record<string, string>>({})
const emptyNewVariable = () => ({ branchId: '', clauseIndex: -1, nameZh: '', nameEn: '', kind: 'boolean', unit: '', optional: false, parentId: '', scope: '', optionsText: '', reason: '', error: '' })
const newConditionInput = reactive(emptyNewVariable())
let generation = 0, sourceGeneration = 0, alive = true
let loading: Promise<void> = Promise.resolve()
let preparation: Promise<ReviewRevision | undefined> | undefined, preparationProject = ''
const state = computed(() => graph.value.graph)
const selected = computed(() => state.value.variables.find(item => item.id === selectedId.value))
watch(selected,item=>{if(item)questionKind.value=questionKindOf(item)},{flush:'sync'})
const selectedRequirements = computed(() => (state.value.requirements ?? []).filter(item => item.nodeIds?.includes(selectedId.value) && item.sourceQuoteEvidence?.kind === 'scope'))
function sameObservation(left: unknown,right: unknown): boolean {
  if (left===right) return true
  if (!left || !right || typeof left!=='object' || typeof right!=='object') return false
  if (Array.isArray(left) || Array.isArray(right)) return Array.isArray(left) && Array.isArray(right) && left.length===right.length && left.every((value,index)=>sameObservation(value,right[index]))
  const a=left as ReviewObject,b=right as ReviewObject,keys=Object.keys(a)
  return keys.length===Object.keys(b).length && keys.every(key=>Object.prototype.hasOwnProperty.call(b,key) && sameObservation(a[key],b[key]))
}
function requirementScope(requirement: ReviewObject): { text: string; observation: ReviewObject } | null {
  const evidence=requirement.sourceQuoteEvidence, ref=evidence?.sourceScopeObservationRef
  if (!evidence) return null
  if (!ref) return evidence.sourceScopeObservation?.id===evidence.sourceObservationId && typeof evidence.sourceScopeText==='string' && typeof requirement.sourceQuote==='string' && !!requirement.sourceQuote.trim() && evidence.sourceScopeText.includes(requirement.sourceQuote) ? {text:evidence.sourceScopeText,observation:evidence.sourceScopeObservation} : null
  const entry=state.value.sourceScopeObservations?.[ref]
  if (scopeBinding.value.integrity[ref] !== 'valid' || scopeBinding.value.entries[ref]!==JSON.stringify(entry)) return null
  if (!entry || !['sourceId','sourceSha256','analysisRevision','analysisInputSha256','analysisSha256'].every(key => typeof requirement[key]==='string' && !!requirement[key] && String(entry[key])===String(requirement[key])) || entry.sourceObservationId!==evidence.sourceObservationId || entry.sourceScopeObservation?.id!==evidence.sourceObservationId || typeof entry.sourceScopeText!=='string' || typeof requirement.sourceQuote!=='string' || !requirement.sourceQuote.trim() || !entry.sourceScopeText.includes(requirement.sourceQuote)) return null
  if (evidence.sourceScopeObservation && !sameObservation(evidence.sourceScopeObservation,entry.sourceScopeObservation) || evidence.sourceScopeText!==undefined && evidence.sourceScopeText!==entry.sourceScopeText) return null
  return {text:entry.sourceScopeText,observation:entry.sourceScopeObservation}
}
const unassignedLimit=ref(20)
const unassignedRequirements=computed(() => (state.value.requirements ?? []).filter(item => !item.nodeIds?.length && item.sourceQuoteEvidence?.kind === 'scope' && reading.value && [String(reading.value.id),reading.value.docId,reading.value.editionId].includes(String(item.sourceId))))
const unavailableRequirements=computed(() => (state.value.requirements ?? []).filter(item => {
  if (item.nodeIds?.length || item.sourceQuoteEvidence?.kind !== 'scope') return false
  const source=graph.value.sources.find(value => [String(value.id),value.docId,value.editionId].includes(String(item.sourceId)))
  return !source || !reading.value && !sourceLoading.value && String(source.id)===sourceId.value
}))
watch(sourceId,()=>{unassignedLimit.value=20})
const usesUnverifiedScc = (item: ReviewObject) => {
  const ids = [...(item.docIds || []), ...['sourceAnchors', 'editTargets', 'evidence'].flatMap(group => (item[group] || []).flatMap((entry: ReviewObject) => [entry.docId, entry.sourceDocumentId, entry.editionId]))].map(String)
  return graph.value.sources.some(source => source.fileKey === 'SCC' && source.catalogueSourceVerified !== true && [String(source.id), source.docId, source.editionId].some(id => id && ids.includes(String(id))))
}
const selectedUsesUnverifiedScc = computed(() => !!selected.value && usesUnverifiedScc(selected.value))
const projectionKey = ref(''), projectionRoot = ref(''), projectionExhaustive = ref(false)
const definitionGraphReason = ref(''), definitionGraphAcknowledged = ref(false), definitionReuseForEdition = ref(false), definitionCompleteEdition = ref(false)
const definitionGroupSelection = ref<string[]>([])
const fixedCandidateFieldIds = computed(() => new Set(workingReview(head.value)?.definitionGraphReview?.fixedCandidateFieldIds ?? []))
const supportsDefinitionGroups = computed(() => Array.isArray(workingReview(head.value)?.definitionGraphReview?.confirmedFieldIds) && Array.isArray(workingReview(head.value)?.definitionGraphReview?.pendingFieldIds))
const definitionGroupPending = computed(() => {
  const confirmed = new Set(workingReview(head.value)?.definitionGraphReview?.confirmedFieldIds ?? [])
  return state.value.variables.filter(item => businessVariable(item) && !item.parentId && !confirmed.has(item.id))
})
const definitionAlignmentReviewed = (item: ReviewObject) => !item.catalogAlignment || item.catalogAlignment.confirmed === true || item.pdfReferenceAlignment?.confirmed === true
const definitionGroupOptions = computed(() => {
  const revision = workingReview(head.value), review = revision?.definitionGraphReview
  if (revision?.sourceStale) return []
  const current = new Set(review?.currentDefinitionReviewFieldIds ?? [])
  const ready = Array.isArray(review?.definitionGroupReadyFieldIds) ? new Set(review.definitionGroupReadyFieldIds) : undefined
  const matched = (item: ReviewObject) => item.catalogAlignment?.status === 'matched' && item.catalogAlignment?.confirmed === true
  const children = new Map<string, ReviewVariable[]>()
  if (!ready) for (const item of state.value.variables) if (businessVariable(item) && item.parentId) children.set(item.parentId, [...(children.get(item.parentId) ?? []), item])
  const matchedTree = (item: ReviewVariable, seen = new Set<string>()): boolean => {
    if (seen.has(item.id) || !matched(item)) return false
    seen.add(item.id)
    return (children.get(item.id) ?? []).every(child => matchedTree(child, seen))
  }
  return definitionGroupPending.value.filter(item => {
    const fixed = fixedCandidateFieldIds.value.has(item.id)
    if (!fixed && !definitionAlignmentReviewed(item)) return false
    if (ready) return ready.has(item.id) && (fixed || matched(item) || current.has(item.id) && item.definitionReview?.confirmed === true)
    if (current.has(item.id) && item.definitionReview?.confirmed === true) return true
    return fixed ? !children.has(item.id) : matchedTree(item)
  })
})
const definitionAlignmentPending = computed(() => definitionGroupPending.value.filter(item => !fixedCandidateFieldIds.value.has(item.id) && !definitionAlignmentReviewed(item)))
watch(definitionGroupOptions, options => { const available=new Set(options.map(item=>item.id));definitionGroupSelection.value=definitionGroupSelection.value.filter(id=>available.has(id)) })
const definitionBaselineReuseBlocked = computed(() => !!state.value.fixedRuleBaseline || definitionGroupSelection.value.some(id => fixedCandidateFieldIds.value.has(id)))
watch(definitionBaselineReuseBlocked, blocked => { if (blocked) { definitionReuseForEdition.value=false; definitionCompleteEdition.value=false } })
watch(() => props.projectId, () => { definitionGroupSelection.value = [] })
const manualProjectionRoot = ref(''), manualProjectionBranch = ref(''), manualProjectionPolarity = ref(''), manualProjectionReason = ref(''), manualProjectionExhaustive = ref(false)
const previousProjectionReason=ref(''), previousProjectionChecked=ref(false), previousProjectionDecision=ref('')
const projectionHistory = shallowRef<ReviewObject>(), projectionHistoryReason = ref('')
const projectionHistorySources = shallowRef<Awaited<ReturnType<typeof draftingApi.projectionHistorySources>>>()
const projectionProposals = computed(() => (selected.value?.catalogAlignment?.proposedMatches ?? []).filter((item: ReviewObject) => item.semanticStatus !== 'conflict' && item.factProjection && typeof item.factProjection.branchPolarity === 'boolean'))
const projectionProposal = computed(() => projectionProposals.value.find((item: ReviewObject) => item.baselineKey === projectionKey.value))
const projectionRoots = computed(() => state.value.variables.filter(item => item.id !== selectedId.value && item.status !== 'excluded' && !usesUnverifiedScc(item) && item.type.kind === 'choice' && item.catalogAlignment?.confirmed === true && item.catalogAlignment?.baselineKey === projectionKey.value && item.catalogAlignment?.baselineVersion === projectionProposal.value?.baselineVersion))
const manualProjectionEligible = computed(() => !!selected.value && selected.value.status !== 'excluded' && selected.value.type.kind === 'boolean' && ['input','decision','content'].includes(selected.value.role.kind) && !selected.value.parentId && !selected.value.factProjectionReview && selected.value.definitionKind !== 'source_seed')
const projectionTargetIdsReady = computed(() => { const targets=selected.value?.editTargets ?? [], ids=targets.map((target:ReviewObject)=>target.id);return ids.every((id:unknown)=>typeof id==='string' && !!id) && new Set(ids).size===ids.length })
const manualProjectionRoots = computed(() => state.value.variables.filter(item => item.id !== selectedId.value && item.status !== 'excluded' && !item.parentId && !usesUnverifiedScc(item) && item.type.kind === 'choice' && ['input','decision','content'].includes(item.role.kind) && item.catalogAlignment?.status === 'matched' && item.catalogAlignment?.confirmed === true && item.catalogAlignment?.baselineKey && item.catalogAlignment?.baselineVersion && item.options?.length === 2 && item.options[0].value !== item.options[1].value))
const manualProjectionChoice = computed(() => manualProjectionRoots.value.find(item => item.id === manualProjectionRoot.value))
function ruleInputIds(rule:ReviewObject | null | undefined) { return [...(rule?.inputIds ?? []),...historicalBranches(rule).flatMap((branch:ReviewObject)=>[branch.scopeId,...(branch.clauses ?? []).map((clause:ReviewObject)=>clause.inputId)])].filter(Boolean) }
const previousProjectionExternal = computed(() => {const review=selected.value?.factProjectionReview;return [...new Set(ruleInputIds(review?.original?.correction).filter((id:string)=>id!==selectedId.value && id!==review?.rootId))]})
const canReplacePreviousProjection = computed(() => {const review=selected.value?.factProjectionReview;return review?.original?.correction?.kind==='rule' && !previousProjectionExternal.value.length && JSON.stringify(selected.value?.correction)===JSON.stringify(review.convertedCorrection)})
const canConfirmRebuiltProjection = computed(() => {const review=selected.value?.factProjectionReview, refs=ruleInputIds(selected.value?.correction);return !!review && JSON.stringify(selected.value?.correction)!==JSON.stringify(review.convertedCorrection) && refs.includes(review.rootId) && previousProjectionExternal.value.every(id=>refs.includes(id))})
const canRestoreProjectionTargets = computed(() => {const review=selected.value?.factProjectionReview, original=review?.original?.targetBindings;if(!original)return true;const saved=original.map((item:ReviewObject)=>item.id),current=(selected.value?.editTargets ?? []).map((item:ReviewObject)=>item.id);return saved.length===current.length && new Set(saved).size===saved.length && new Set(current).size===current.length && saved.every((id:string)=>!!id && current.includes(id))})
watch(selectedId, () => { projectionKey.value=''; projectionRoot.value=''; projectionExhaustive.value=false; manualProjectionRoot.value=''; manualProjectionBranch.value=''; manualProjectionPolarity.value=''; manualProjectionReason.value=''; manualProjectionExhaustive.value=false; previousProjectionReason.value='';previousProjectionChecked.value=false;previousProjectionDecision.value='';projectionHistory.value=undefined; projectionHistorySources.value=undefined; projectionHistoryReason.value='' })
watch(projectionRoot, () => { projectionExhaustive.value=false })
watch(manualProjectionRoot, () => { manualProjectionBranch.value=''; manualProjectionPolarity.value=''; manualProjectionReason.value=''; manualProjectionExhaustive.value=false })
watch([manualProjectionBranch,manualProjectionPolarity], () => { manualProjectionReason.value=''; manualProjectionExhaustive.value=false })
function memberParagraph(source: ReviewReading, member?: ReviewObject) {
  const position = member?.sourcePosition
  const paragraphs = position?.sourceSha256 === source.sourceSha256 && position.partName && position.nodePath ? source.paragraphs.filter(paragraph => paragraph.sourceSha256 === source.sourceSha256 && paragraph.sourcePosition?.sourceSha256 === position.sourceSha256 && paragraph.sourcePosition?.partName === position.partName && paragraph.sourcePosition?.nodePath === position.nodePath) : []
  return paragraphs.length === 1 ? paragraphs[0]!.id : undefined
}
const relatedScopes = computed(() => {
  const source = reading.value
  if (!source || !selected.value) return []
  const anchors = selected.value.sourceAnchors.filter(anchor => [String(source.id), source.docId, source.editionId].includes(anchor.docId) && anchor.sourceSha256 === source.sourceSha256 && ['analysisRevision', 'analysisInputSha256', 'analysisSha256'].every(key => anchor[key] === source[key]))
  const observations = new Set(anchors.map(anchor => anchor.sourceObservationId).filter(Boolean))
  const markerTargets = new Set(source.markerCandidates.filter(marker => observations.has(marker.id)).flatMap(marker => marker.targetGroupIds ?? []))
  return source.groups.filter(group => markerTargets.has(group.id) || observations.has(group.guidanceNoteId) || group.guidanceNoteIds?.some((id: string) => observations.has(id))).map(group => ({
    group,
    members: (group.orderedMemberIds?.length ? group.orderedMemberIds : [...new Set((group.memberRanges ?? []).map((range: ReviewObject) => range.memberId).filter(Boolean))]).map((id: string) => {
      const matches = source.sourceMembers.filter(member => member.id === id)
      const member = matches.length === 1 ? matches[0] : undefined
      const paragraphId=memberParagraph(source, member)
      return { id, text: member?.textObservation?.text, paragraphId, canLocate: !!source.paragraphs.find(paragraph=>paragraph.id===paragraphId)?.text.trim() }
    })
  }))
})
const filtered = computed(() => state.value.variables.filter(item => item.status !== 'excluded' && (!sourceId.value || !item.docIds?.length || item.docIds.some(id=>[sourceId.value,reading.value?.docId,reading.value?.editionId].includes(id))) && (!search.value || (questionLabel(item) + ' ' + item.nameZh + ' ' + item.nameEn).toLowerCase().includes(search.value.toLowerCase()))))
function questionLabel(item: ReviewObject) {
  if (item.definitionKind !== 'source_seed') return props.locale === 'en' ? item.nameEn || item.nameZh : item.nameZh || item.nameEn
  const quote = item.sourceObservation?.text || item.sourceAnchors?.[0]?.text || ''
  return t('来源待核对：', 'Source to review: ') + (quote ? quote.slice(0, 100) : t('请查看关联原文', 'See linked source'))
}
function statusLabel(status: string) { return ({ pending: t('待核对', 'To review'), confirmed: t('已确认', 'Confirmed'), changed: t('已调整，待确认', 'Changed; confirm'), help: t('需处理', 'Needs attention'), excluded: t('已排除', 'Excluded') } as Record<string, string>)[status] || t('待核对', 'To review') }
const ruleSource = computed(() => !!reading.value && graph.value.sources.some(source => String(source.id) === String(reading.value!.id)))
const invalidTemplateSources = computed(() => head.value.sources.filter(source => ['NTT', 'SCT', 'SCC'].includes(source.fileKey ?? '') && source.evidenceStatus !== 'bound' && (!Object.values(graph.value.selectedDocuments).length || Object.values(graph.value.selectedDocuments).some(id => String(id) === String(source.id)))))
const selectedSource = computed(() => reading.value ?? head.value.sources.find(source => String(source.id) === sourceId.value))
const sourceEvidenceLabel = (source: ReviewSource) => source.evidenceStatus === 'bound' ? t('来源证据已绑定', 'Source evidence bound') : source.evidenceStatus === 'invalid' ? t('来源证据已失效（包括旧分析代次）', 'Source evidence invalid (including an old analysis revision)') : t('缺少当前来源证据', 'Current source evidence missing')
const isReviewCheck = isSourceCheck
const answerSources = computed(() => state.value.variables.filter(isValueVariable))
const sourceChecks = computed(() => state.value.variables.filter(item => item.status !== 'excluded' && isReviewCheck(item)).length)
const businessQuestions = computed(() => state.value.variables.filter(businessVariable).length)
const processingRules = computed(() => state.value.variables.filter(item => item.status !== 'excluded' && questionKindOf(item)==='rules').length)
const pending = computed(() => state.value.variables.filter(item => item.status !== 'excluded' && item.status !== 'confirmed').length)
const nextPending = computed(() => {
  const items = state.value.variables, at = items.findIndex(item => item.id === selectedId.value)
  return [...items.slice(at + 1), ...items.slice(0, at + 1)].find(item => item.status !== 'excluded' && item.status !== 'confirmed')
})
const selectedProblem = computed(() => selected.value ? reviewProblem(selected.value, state.value) : null)
const selectedSplitReview = computed(() => selected.value?.splitReview ?? (selected.value?.correction?.kind === 'split' ? selected.value.correction : undefined))
const splitDirty = computed(() => !!splitFirst.value || !!splitSecond.value || [splitNames.firstZh, splitNames.firstEn, splitNames.secondZh, splitNames.secondEn].some(value => value.trim()))
function cancelSplit() { splitFirst.value = undefined; splitSecond.value = undefined; Object.assign(splitNames, { firstZh: '', firstEn: '', secondZh: '', secondEn: '', mode: 'unknown' }); splitOpen.value = false }
const newVariableDirty = computed(() => !!newConditionInput.branchId && [newConditionInput.nameZh,newConditionInput.nameEn,newConditionInput.unit,newConditionInput.optionsText,newConditionInput.reason,newConditionInput.scope,newConditionInput.parentId].some(value => value.trim()))
const formDirty = computed(() => formOpen.value && JSON.stringify(form) !== formBase.value || newVariableDirty.value)
const hasReviewRevision = computed(() => !!(head.value.currentRevisionId || head.value.draftRevisionId || head.value.workingRevisionId || head.value.current || head.value.draft))
const emptyReview = computed(() => !hasReviewRevision.value && !state.value.variables.length)
const boundTemplateAvailable = computed(() => head.value.sources.some(source => ['NTT', 'SCT', 'SCC'].includes(source.fileKey ?? '') && source.evidenceStatus === 'bound' && (!Object.values(graph.value.selectedDocuments).length || Object.values(graph.value.selectedDocuments).some(id => String(id) === String(source.id)))))
const preparationEditPending = computed(() => dirty.value || formDirty.value || splitDirty.value || formOpen.value || !!newConditionInput.branchId)
const isolatedRule = computed(() => {
  const item = selected.value, correction = item?.correction
  const editorial = !!item?.editorialReferenceHistory?.length && !!correction?.supersededCorrection
  return !!item && (editorial || item.role?.kind === 'input' && !isReviewCheck(item)) && item.status !== 'excluded' && !item.missingSource &&
    (editorial || item.discoveryCoreRetained === true || item.definitionKind === 'discovered') && (editorial || !!(item.sourceAnchors?.length || item.sourceUnitIds?.length)) &&
    correction?.kind === 'rule' && correction.needsRebind === true && Array.isArray(correction.branches) && !correction.branches.length &&
    Array.isArray(correction.inputIds) && !correction.inputIds.length && Object.keys(correction).every(key => ['kind', 'needsRebind', 'branches', 'inputIds', 'supersededCorrection'].includes(key)) &&
    (editorial || !item.editTargets?.length && !item.targets?.length)
})
function historicalBranches(correction: ReviewObject | null | undefined) { return correction?.branches ?? (correction?.clauses?.length ? [correction] : []) }
function historicalValue(value: unknown) { return typeof value === 'string' ? value : JSON.stringify(value ?? '') }
const parents = computed(() => state.value.variables.filter(item => isValueVariable(item) && ['record', 'list'].includes(item.type.kind) && item.id !== selectedId.value))
const conditionInputs = computed(() => state.value.variables.filter(item => isValueVariable(item) && (item.id !== selectedId.value || form.role !== 'derived')))
const conditionLists = computed(() => state.value.variables.filter(item => isValueVariable(item) && item.type.kind === 'list' && (item.id !== selectedId.value || form.role !== 'derived')))
const conditionSourceChecks = computed(() => state.value.variables.filter(item => item.status !== 'excluded' && (item.definitionKind === 'source_seed' || isReviewCheck(item))).length)
function matchesOption(item: ReviewObject, key: string) {
  return questionSearchMatches([questionLabel(item), item.nameZh, item.nameEn, ...(item.sourceAnchors ?? []).map((anchor: ReviewAnchor) => anchor.text)].join(' '), optionQueries[key] ?? '')
}
function searchedOptions(items: ReviewVariable[], key: string, retained?: string) { return items.filter(item => item.id === retained || matchesOption(item, key)) }
function hasOptionMatch(items: ReviewVariable[], key: string) { return items.some(item => matchesOption(item, key)) }
function pickerOptions(items: ReviewVariable[]) { return items.map(item => ({ id: item.id, label: questionLabel(item), searchText: [questionLabel(item), item.nameZh, item.nameEn, ...(item.sourceAnchors ?? []).map(anchor => anchor.text)].join(' ') })) }
function sourceCheckDetail(item: ReviewObject) {
  const sources = graph.value.sources.filter(source => [String(source.id), source.docId, source.editionId].some(id => item.docIds?.includes(id)))
  const file = sources.map(source => [source.fileKey, source.fileName].filter(Boolean).join(' · ')).join('；')
  const reason = item.missingSource || !item.sourceAnchors?.length
    ? t('尚未关联可核对的来源原文。', 'No reviewable source passage is linked yet.')
    : item.definitionKind === 'source_seed'
      ? t('已保留指导原文／修改标记，尚未核对成独立、可回答的变量。', 'The instruction or marker is retained, but has not been reviewed as an independent answerable variable.')
      : t('这是人工核对事项，尚无可用于条件判断的变量值。', 'This is a manual source check, without a project answer for condition evaluation.')
  return [reason, file && t('来源：', 'Source: ') + file, sourceExcerpt(item.sourceObservation?.text || item.sourceAnchors?.[0]?.text)].filter(Boolean).join(' ')
}
function conditionInputProblem(id: string) {
  if (!id) return ''
  const item = state.value.variables.find(value => value.id === id)
  if (!item) return t('原引用的变量已不存在；现有条件仍保留，请重新选择变量。', 'The referenced variable is missing. The existing condition is retained; choose another input.')
  if (id === selectedId.value && form.role === 'derived') return t('派生答案不能依靠自身计算；现有引用仍保留，请核对取值来源。', 'A derived answer cannot calculate itself. The existing reference is retained; review its value source.')
  if (item.status === 'excluded') return t('原引用的变量已排除；现有条件仍保留，请重新选择变量。', 'The referenced variable was excluded. The existing condition is retained; choose another input.')
  if (isTreatmentRule(item)) return t('这是一项原文处理规则，不能提供业务变量值；旧引用仍保留，请重新选择变量。', 'This is a source treatment rule and cannot supply a business value. The retained reference needs an answerable variable.')
  if (item.definitionKind === 'source_seed' || isReviewCheck(item)) return sourceCheckDetail(item)
  return !isValueVariable(item) ? t('该项尚无可用于条件或取值的业务答案；旧引用仍保留，请重新核对。', 'This item has no business value for a condition or value source. The retained reference needs review.') : ''
}
function conditionReferenceLabel(id: string) { const item = state.value.variables.find(value => value.id === id); return item ? questionLabel(item) : t('原变量已不存在', 'Referenced variable missing') }
function closeNewConditionInput() { Object.assign(newConditionInput, emptyNewVariable()) }
function openNewConditionInput(branchId: string, clauseIndex: number) {
  if (newConditionInput.branchId === branchId && newConditionInput.clauseIndex === clauseIndex) return
  if (newVariableDirty.value) { newConditionInput.error = t('请先创建或取消正在填写的变量，再切换条件。', 'Create or cancel the variable being entered before switching conditions.'); return }
  closeNewConditionInput(); Object.assign(newConditionInput, { branchId, clauseIndex })
}
function removeConditionClause(branch: ReviewObject, index: number) {
  branch.clauses.splice(index, 1)
  if (newConditionInput.branchId === branch.id) { if (newConditionInput.clauseIndex === index) closeNewConditionInput(); else if (newConditionInput.clauseIndex > index) newConditionInput.clauseIndex-- }
  for (const key of Object.keys(optionQueries)) if (key.startsWith('input:' + branch.id + ':')) delete optionQueries[key]
}
function removeConditionBranch(index: number) { if (newConditionInput.branchId === form.branches[index]?.id) closeNewConditionInput(); form.branches.splice(index, 1) }
function createConditionInput() {
  if (busy.value) return
  const branch = form.branches.find((value: ReviewObject) => value.id === newConditionInput.branchId), clause = branch?.clauses?.[newConditionInput.clauseIndex]
  const nameZh = newConditionInput.nameZh.trim() || newConditionInput.nameEn.trim(), nameEn = newConditionInput.nameEn.trim() || nameZh
  const standalone = newConditionInput.branchId === '@standalone', reason = newConditionInput.reason.trim()
  if ((!standalone && !clause) || !nameZh || !reason || !kinds[newConditionInput.kind]) { newConditionInput.error = t('请填写变量名称、值类型和人工新增理由。', 'Supply a variable name, value type and manual reason.'); return }
  const options = ['choice','multiselect'].includes(newConditionInput.kind) ? newConditionInput.optionsText.split('\n').filter(line => line.trim()).map(line => { const [value,zh,en] = line.split('|').map(part => part.trim()); return { value, labelZh: zh || value, labelEn: en || zh || value } }) : []
  if (['choice','multiselect'].includes(newConditionInput.kind) && (!options.length || options.some(option => !option.value) || new Set(options.map(option => option.value)).size !== options.length)) { newConditionInput.error = t('请填写不重复的选项稳定值。', 'Supply unique stable option values.'); return }
  try {
    apply({ type: 'ADD', values: { nameZh, nameEn, questionZh: nameZh, questionEn: nameEn, type: { kind: newConditionInput.kind, zh: kinds[newConditionInput.kind], en: newConditionInput.kind, ...(newConditionInput.kind === 'number' && newConditionInput.unit.trim() ? { unit: newConditionInput.unit.trim() } : {}) }, role: { kind: 'input', zh: '变量', en: 'Variable' }, parentId: newConditionInput.parentId || null, optional: newConditionInput.optional, options, definitionOrigin: { kind: 'manual', reason }, catalogAlignment: { status: 'new', confirmed: true, reason }, definitionScope: newConditionInput.scope.trim() }, reason })
    if (clause && !standalone) { clause.inputId = state.value.variables.at(-1)!.id; clause.operator = allowedOperators(clause.inputId)[0]; clause.value = ''; optionQueries['input:' + branch.id + ':' + newConditionInput.clauseIndex] = '' }
    closeNewConditionInput()
    if (standalone) { selectedId.value = state.value.variables.at(-1)!.id; resetForm(); editingQuestion.value = true }
  } catch (caught) { newConditionInput.error = caught instanceof Error ? caught.message : String(caught) }
}
const graphLayout = computed(() => showGraph.value ? graphView.build(state.value, { includeExcluded: false, sources: graph.value.sources }) : null)
const graphItems = computed<ReviewObject[]>(() => graphLayout.value ? [...graphLayout.value.variables, ...graphLayout.value.actions, ...graphLayout.value.files] : [])
const graphInspection = computed(() => graphItems.value.find(item => item.id === graphSelected.value))
const graphImpact = computed<ReviewObject | null>(() => {
  const view = graphLayout.value; if (!view || !graphSelected.value) return null
  if (!graphSelected.value.startsWith('file:')) return graphView.selection(view, graphSelected.value)
  const parts = view.actions.filter((action: ReviewObject) => action.docIds.includes(graphSelected.value.slice(5))).map((action: ReviewObject) => graphView.selection(view, action.id))
  return Object.fromEntries(['variableIds', 'actionIds', 'fileIds', 'edgeIds', 'upstreamIds', 'downstreamIds'].map(key => [key, [...new Set(parts.flatMap((part: ReviewObject) => part[key] ?? []))]]))
})
const graphChain = computed(() => new Set<string>(graphImpact.value ? [...graphImpact.value.variableIds, ...graphImpact.value.actionIds, ...graphImpact.value.fileIds, graphSelected.value] : []))
const shownGraphItems = computed(() => graphOnlySelected.value && graphSelected.value ? graphItems.value.filter(item => graphChain.value.has(item.id)) : graphItems.value)
const shownGraphEdges = computed(() => graphLayout.value?.edges.filter((edge: ReviewObject) => !graphOnlySelected.value || !graphSelected.value || graphImpact.value?.edgeIds.includes(edge.id)) ?? [])
function graphLabel(item: ReviewObject) { const label = { ...item.node, ...item }; if (label.definitionKind === 'source_seed') return questionLabel(label); return props.locale === 'en' ? label.labelEn ?? label.nameEn ?? label.labelZh ?? label.nameZh : label.labelZh ?? label.nameZh ?? label.labelEn ?? label.nameEn }
async function locateGraphSelection() { const item = graphInspection.value; if (!item) return; showGraph.value = false; if (item.docId) await selectSource(item.docId); else await choose(item.ownerId ?? item.id) }
const kinds: Record<string, string> = { boolean: '是／否 / Yes / No', number: '数字 / Number', text: '文字 / Text', date: '日期 / Date', choice: '单选 / Single choice', multiselect: '多选 / Multiple choices', record: '一组资料 / Related details', list: '逐项清单 / List of items' }
const roles: Record<string, string> = { input: '填写变量值 / Variable value', derived: '由其他变量值决定 / Derived value', fixed: '使用固定内容 / Fixed wording', verification: '需人工核查 / Manual check', decision: '决定是否采用 / Adoption decision', content: '待提供文字 / Wording to provide' }
const actions: Record<string, string> = { keep: '保留 / Retain', fill: '填写 / Fill', replace: '替换 / Replace', insert: '在原件锚点插入 / Insert at source anchor', delete: '删除 / Delete', unused: '标为Not used / Mark Not used', check: '需要核查 / Check' }
const operators: Record<string, string> = { yes: '为是 / Yes', no: '为否 / No', known: '已填写 / Provided', eq: '等于 / Equals', ge: '不少于 / At least', le: '不多于 / At most', gt: '多于 / Greater than', lt: '少于 / Less than', has: '包含 / Includes', lacks: '不包含 / Does not include', empty: '明确为空 / Confirmed empty', notempty: '已填且有内容 / Provided with content' }
function allowedOperators(id: string) { const kind = state.value.variables.find(item => item.id === id)?.type.kind; return kind === 'boolean' ? ['yes', 'no', 'known'] : kind === 'number' ? ['eq', 'ge', 'le', 'gt', 'lt', 'known'] : kind === 'multiselect' ? ['has', 'lacks', 'empty', 'notempty', 'known'] : ['text', 'list'].includes(kind) ? ['eq', 'empty', 'notempty', 'known'].filter(value => kind !== 'list' || value !== 'eq') : ['eq', 'known'] }
function targetScope(target: ReviewObject) { return form.branches.find((branch: ReviewObject) => branch.id === target.branchId && branch.scopeMode === 'each' && branch.scopeId) }
function itemKeys(target: ReviewObject) { const scope = targetScope(target)?.scopeId; return state.value.variables.filter(item => item.parentId === scope && isValueVariable(item) && !['record', 'list'].includes(item.type.kind)) }
function optionInput(id: string) { return state.value.variables.find(item => item.id === id)?.options ?? [] }
function changed() { dirty.value = true; emit('dirty', true) }
function apply(action: ReviewObject) {
  if (busy.value) return
  const reducer = createReviewReducer({ variables: state.value.variables, sourceUnits: [], sources: graph.value.sources })
  const next = reducer.reduce(state.value, { ...action, at: new Date().toISOString() })
  if (next === state.value) throw Error(t('这些项目或版本不能这样合并／关联，请核对。', 'These items or editions cannot be combined or linked this way.'))
  undo.value = [...undo.value.slice(-49), state.value]; graph.value = { ...graph.value, graph: next }; changed()
}
function resetForm(item = selected.value) {
  closeNewConditionInput(); for (const key of Object.keys(optionQueries)) delete optionQueries[key]
  if (!item) { formOpen.value = false; return }
  Object.assign(form, { nameZh: item.nameZh, nameEn: item.nameEn, kind: item.type.kind, unit: item.type.unit ?? '', optional: !!item.optional, scope: item.definitionScope ?? '', manualReason: item.definitionOrigin?.reason ?? '', role: item.role.kind, parentId: item.parentId ?? '', fixedValue: item.fixedValue ?? '', splitMode: (item.splitReview ?? (item.correction?.kind === 'split' ? item.correction : undefined))?.mode ?? 'unknown', optionsText: (item.options ?? []).map(option => String(option.value) + ' | ' + option.labelZh + ' | ' + option.labelEn).join('\n'), branches: copy(item.correction?.kind === 'rule' ? item.correction.branches ?? (item.correction.clauses?.length ? [{ ...item.correction, id: 'case-1', mode: item.correction.mode ?? 'all' }] : []) : []), targets: copy(item.editTargets ?? []).map((target: ReviewObject) => ({ ...target, action: target.action === 'retain' ? 'keep' : target.action === 'not_used' ? 'unused' : target.action })) })
  formBase.value = JSON.stringify(form); formOpen.value = true
}
function protect() { if (busy.value) return false; if (formDirty.value) { error.value = t('请先保存或取消当前变量调整。', 'Save or cancel the current variable edit first.'); return false }; return true }
async function choose(id: string) { if (splitDirty.value && id !== selectedId.value) { error.value = t('请先完成或取消拆分。', 'Complete or cancel the split first.'); return }; if (!protect()) return; selectedId.value = id;editingQuestion.value=false; resetForm(); const anchor = selected.value?.sourceAnchors?.[0]; if (anchor) await locateAnchor(anchor) }
async function locateAnchor(anchor: ReviewAnchor) {
  if (!protect()) return
  error.value = ''
  locationNotice.value = ''
  const source = graph.value.sources.find(item => [String(item.id), item.docId, item.editionId].includes(anchor.docId))
  if (!source || anchor.sourceSha256 && source.sourceSha256 !== anchor.sourceSha256) { error.value = t('关联的原件版本不可用，请核对来源。', 'The linked source edition is unavailable. Check the source.'); return }
  const project = props.projectId
  if (String(source.id) !== sourceId.value || !reading.value) await selectSource(String(source.id))
  await nextTick()
  if (project !== props.projectId || sourceId.value !== String(source.id) || !reading.value) return
  if (reading.value.format === 'pdf' && anchor.sourceSha256 === reading.value.sourceSha256 && Number.isInteger(anchor.page) && anchor.page > 0 && typeof anchor.text === 'string' && anchor.text.trim()) {
    focusPage.value=anchor.page;focusText.value=anchor.text.trim();focusRequest.value++;return
  }
  const spans=targetSpans(anchor).filter(span=>reading.value!.paragraphs.some(paragraph=>paragraph.id===span.paragraphId&&paragraph.paragraphSha256===span.paragraphSha256&&paragraph.text.slice(span.start,span.end)===span.expectedText)).map(span=>!span.sourceSha256&&anchor.sourceSha256===reading.value!.sourceSha256?{...span,sourceSha256:anchor.sourceSha256}:span)
  const paragraphIds=spans.map(span=>span.paragraphId)
  if (!paragraphIds.length && anchor.sourceObservationBinding?.status === 'bound' && anchor.sourceSha256 === reading.value.sourceSha256 && ['analysisRevision', 'analysisInputSha256', 'analysisSha256'].every(key => anchor[key] === reading.value![key])) {
    for(const range of anchor.sourceObservationBinding.observedRanges??[]){
      const matches = reading.value.sourceMembers.filter(member => member.id === range.memberId)
      const id=matches.length===1?memberParagraph(reading.value,matches[0]):undefined
      if(id)paragraphIds.push(id)
    }
  }
  locateParagraphs(paragraphIds,anchor.text)
  locationSpans.value=spans
}
function locateParagraph(id?: string, quote?: string) {
  locateParagraphs(id?[id]:[],quote)
}
async function locateRequirementScope(requirement: ReviewObject) {
  if (!protect()) return
  const source=graph.value.sources.find(item => [String(item.id),item.docId,item.editionId].includes(String(requirement.sourceId))), project=props.projectId
  if (!source) { error.value=t('历史范围的原件不可用，请重新识别或手动核对。', 'The original for this historical range is unavailable. Rediscover or review it manually.');return }
  if (String(source.id)!==sourceId.value || !reading.value) await selectSource(String(source.id))
  if (project!==props.projectId || !reading.value || String(source.id)!==sourceId.value) return
  if (requirement.sourceSha256!==reading.value.sourceSha256 || !['analysisRevision','analysisInputSha256','analysisSha256'].every(key => typeof requirement[key]==='string' && !!requirement[key] && requirement[key]===reading.value![key])) { error.value=t('历史范围的来源分析身份缺失或已变化，请重新识别或手动核对；不会按旧位置跳转。', 'The historical range has missing or changed analysis identity. Rediscover or review manually; its old position is not used.');return }
  const scope=requirementScope(requirement)
  if (!scope) { error.value=t('历史范围证据缺失或不一致，请重新识别或手动核对。','Historical scope evidence is missing or inconsistent. Rediscover or review manually.');return }
  const observations=[...(reading.value.groups ?? []),...(reading.value.markerCandidates ?? []),...(reading.value.guidanceNotes ?? [])].filter(item=>item.id===requirement.sourceQuoteEvidence?.sourceObservationId)
  if(observations.length!==1 || !sameObservation(observations[0],scope.observation)) { error.value=t('当前分析中的范围与历史记录无法唯一核对，请手动查找原文。','The current analysis cannot uniquely verify this historical scope. Find the passage manually.');return }
  let tooDeep=false
  function memberIds(observation: ReviewObject,depth=0): string[] {
    if(depth>20){tooDeep=true;return []}
    if(Array.isArray(observation.memberRanges))return observation.memberRanges.map((range: ReviewObject)=>range.memberId).filter(Boolean)
    if(Array.isArray(observation.insertionAnchors))return observation.insertionAnchors.map((range: ReviewObject)=>range.memberId).filter(Boolean)
    if(Array.isArray(observation.targets))return observation.targets.flatMap((target: ReviewObject)=>memberIds(target,depth+1))
    return Array.isArray(observation.orderedMemberIds)?observation.orderedMemberIds.filter((id: unknown)=>typeof id==='string'):[]
  }
  const expected=memberIds(scope.observation)
  const ids=expected.map((memberId: string) => { const matches=reading.value!.sourceMembers.filter(member=>member.id===memberId);return matches.length===1?memberParagraph(reading.value!,matches[0]):undefined }).filter((id): id is string => !!id)
  error.value='';locateParagraphs(ids,scope.text || requirement.sourceQuote)
  if (!ids.length && sourceMode.value==='word') error.value=t('没有可唯一映射的原段落，请在当前原件中手动查找。', 'No uniquely mapped source paragraph is available; find it manually in the current original.')
  else if (tooDeep || ids.length < expected.length) error.value=t('仅部分范围能定位；请在原件中继续核对其余内容。','Only part of this range could be located; review the remaining content in the original.')
}
function locateTarget(target: ReviewObject) {
  if (busy.value || !reading.value) return
  const sha = target.sourceSha256 ?? target.nativeBinding?.sourceSha256
  if (String(target.docId) !== String(reading.value.id) || sha !== reading.value.sourceSha256) {
    if (formDirty.value || splitDirty.value) { error.value=t('请先记录或取消当前调整，再切换原件定位。', 'Keep or cancel the current changes before locating another source.'); return }
    void locateAnchor(target as ReviewAnchor); return
  }
  const spans=targetSpans(target).filter(span=>reading.value!.paragraphs.some(paragraph=>paragraph.id===span.paragraphId&&paragraph.paragraphSha256===span.paragraphSha256&&paragraph.text.slice(span.start,span.end)===span.expectedText)).map(span=>span.sourceSha256?span:{...span,sourceSha256:sha})
  locateParagraphs(spans.map(span=>span.paragraphId),target.text)
  locationSpans.value=spans
}
function locateParagraphs(ids:string[],quote?:string){
  focusPage.value=undefined
  locationSpans.value=[]
  const paragraphs=reading.value?.paragraphs.filter(item=>ids.includes(item.id))??[]
  const text=paragraphs.find(paragraph=>paragraph.text.trim())?.text
  locationParagraphs.value=[...new Set(paragraphs.filter(paragraph=>paragraph.text.trim()).map(paragraph=>paragraph.id))]
  if(text){focusText.value=reading.value?.format==='docx'?'':quote?.trim()||text;focusRequest.value++;error.value='';locationNotice.value=reading.value?.format==='docx'&&!sourceBindings.value?t('正在读取原件定位，文件仍可浏览；若稍后仍未定位，请重新读取原件。', 'Source locations are being loaded. The file remains readable; reopen the original if location stays unavailable.'):''}
  else {
    focusText.value=''
    locationNotice.value=paragraphs.length&&!quote?.trim()
      ? t('这处原文是空白段落，没有文字可自动定位；原件仍可正常浏览。', 'This is a blank paragraph with no text to locate. The original remains available to browse.')
      : t('未能自动定位这段关联原文；原件仍可浏览。可继续翻页，或在页面上选词核对。', 'This linked passage could not be located automatically. You can still browse, change pages or select words on the original.')
  }
}
function capturePage(value:SourcePageSelection){
  if(busy.value||sourceMode.value!=='pdf'||!reading.value||value.source!==pdfUrl.value)return
  selection.value=undefined;selectionError.value='';pageSelection.value=value;manualParagraphId.value=''
  if(!value.words.length)return
  try{selection.value=bindPageSelection(reading.value,value)}catch(caught){selectionError.value=caught instanceof Error?caught.message:String(caught)}
}
function selectSourceMode(mode: 'pdf' | 'word') {
  if (mode === sourceMode.value) return
  if (sourceMode.value === 'pdf') pdfSource.value?.clearSelection()
  else wordSource.value?.clear()
  selection.value = undefined; selectionError.value = ''; pageSelection.value = undefined; manualParagraphId.value = ''
  if (mode === 'word') wordOpened.value = true
  sourceMode.value = mode
}
function captureWord(value: ReviewAnchor | undefined) {
  if (busy.value || sourceMode.value !== 'word') return
  if (value && (!reading.value || value.docId !== String(reading.value.id) || value.sourceSha256 !== reading.value.sourceSha256)) return
  pageSelection.value = undefined; manualParagraphId.value = ''; selection.value = value
}
function selectFamily(family:string){
  const sources=head.value.sources.filter(source=>source.fileKey===family)
  const source=sources.find(item=>String(item.id)===String(graph.value.selectedDocuments[family]))??sources[0]
  if(source&&(String(source.id)!==sourceId.value||!reading.value))void selectSource(String(source.id))
}
function useSelectedEdition(){
  if(!protect()||props.locked||sourceLoading.value||splitDirty.value||workingReview(head.value)?.sourceStale||!reading.value?.fileKey)return
  const source=head.value.sources.find(item=>String(item.id)===sourceId.value)
  if(!source||!['NTT','SCT','SCC'].includes(source.fileKey??'')||source.evidenceStatus!=='bound'||['id','sourceDocumentId'].some(key=>String(source[key])!==String(reading.value![key]))||['docId','editionId','fileKey','format','canEdit','sourceSha256','analysisRevision','analysisInputSha256','analysisSha256','evidenceStatus'].some(key=>source[key]!==reading.value![key])){error.value=t('原件版本已变化，请刷新。','Source revision changed. Reload it.');return}
  graph.value={...graph.value,sources:graph.value.sources.some(item=>String(item.id)===String(source.id))?graph.value.sources:[...graph.value.sources,copy(source)],selectedDocuments:{...graph.value.selectedDocuments,[source.fileKey!]:String(source.id)}};changed()
}

async function selectSource(id: string, restoring = false) {
  if (!restoring && !protect()) return
  if (!restoring) initialPdfPage.value = 1
  if (pdfUrl.value) URL.revokeObjectURL(pdfUrl.value); pdfUrl.value = '';
  originalLoading.value = false; originalError.value = ''
  const project = props.projectId, token = ++sourceGeneration;sourceLoading.value=true; sourceId.value = id; selection.value = undefined;selectionError.value='';focusText.value='';focusPage.value=undefined; reading.value = undefined
  try { const result = await draftingApi.reviewReading(project, id); if (!alive || project !== props.projectId || token !== sourceGeneration) return; const expected = head.value.sources.find(item => String(item.id) === id); if (!expected || ['sourceSha256', 'analysisRevision', 'analysisInputSha256', 'analysisSha256', 'evidenceStatus'].some(key => result[key] !== expected[key])) throw Error(t('原件版本已变化，请刷新。', 'Source revision changed. Reload it.')); reading.value = { ...result, blocks: Array.isArray(result.blocks) ? result.blocks : (result.blocks as ReviewObject)?.blocks ?? [] }; if (result.format !== 'docx') sourceMode.value = 'pdf'; void openOriginal() } catch (caught) { if (alive && project === props.projectId && token === sourceGeneration) error.value = caught instanceof Error ? caught.message : String(caught) } finally { if(token===sourceGeneration)sourceLoading.value=false }
}
async function locateSource(id: string, expectedSha?: string | null) {
  await loading
  const source = head.value.sources.find(item => String(item.id) === id)
  if (expectedSha && source?.sourceSha256 !== expectedSha) { error.value = t('候选引用的原件已变化；请重新识别，不能用当前原件冒充旧证据。', 'The candidate source changed. Re-extract; the current source cannot stand in for the old evidence.'); return }
  await selectSource(id)
}
function options() { return String(form.optionsText).split('\n').filter(line => line.trim()).map((line,index) => {
  const parts = line.split('|').map(value => value.trim())
  if (parts.length < 3) { const [zh,en] = parts, old = selected.value?.options.find(option => option.labelZh === zh && option.labelEn === (en || zh)); return { value: old?.value ?? 'option-' + selectedId.value + '-' + index, labelZh: zh, labelEn: en || zh } } // Retain an unsaved form opened before stable values were exposed.
  const [value,zh,en] = parts, old = selected.value?.options.find(option => String(option.value) === value)
  return { value: old?.value ?? value, labelZh: zh || value, labelEn: en || zh || value }
}) }
function reviewReplacementPlacement(target: ReviewObject) {
  const original = selected.value?.editTargets.find(item => item.id && item.id === target.id), spans = target.native?.spans
  if (!original?.needsRebind || original.scopeComplete !== false || original.sourceScopeObservation?.scopeComplete !== true || !['fill', 'replace'].includes(original.proposedAction) || target.action !== original.proposedAction || !Array.isArray(spans) || spans.length <= 1) return
  const placement = target.replacementPlacement?.spanIndex, binding = target.nativeBinding
  if (!Number.isInteger(placement) || placement < 0 || placement >= spans.length || binding?.status !== 'bound' || JSON.stringify(target.native) !== JSON.stringify(original.native) || JSON.stringify(binding) !== JSON.stringify(original.nativeBinding) || target.text !== original.text || target.docId !== original.docId || target.sourceSha256 !== original.sourceSha256) return
  const current = head.value.sources.find(source => String(source.id) === String(target.docId)), saved = graph.value.sources.find(source => String(source.id) === String(target.docId))
  if (!current || !saved || current.evidenceStatus !== 'bound' || current.format !== 'docx' || !current.canEdit || current.sourceSha256 !== target.sourceSha256 || binding.sourceSha256 !== current.sourceSha256 || ['sourceSha256', 'analysisRevision', 'analysisInputSha256', 'analysisSha256', 'evidenceStatus'].some(key => current[key] !== saved[key])) return
  if (binding.spans?.length !== spans.length || spans.some((span: ReviewObject, index: number) => ['paragraphId', 'paragraphSha256', 'start', 'end', 'expectedText', 'sourceSha256', 'channel'].some(key => span[key] !== binding.spans[index]?.[key]))) return
  target.needsRebind = false; target.scopeComplete = true
  target.scopeReviewReason = 'Complete source scope and explicit replacement placement reviewed; project applicability remains separate.'
}
function setTargetAction(target: ReviewObject, action: string) {
  if (action === 'check' && target.action !== 'check') target.proposedAction = target.action
  else if (action !== 'check' && action !== target.proposedAction) delete target.proposedAction
  target.action = action
}
function saveForm() {
  if (busy.value) return
  try {
    if (newConditionInput.branchId) throw Error(t('请先创建或取消正在新增的变量值，再记录当前变量调整。', 'Create or cancel the new project fact before recording this variable edit.'))
    if (!selected.value || !form.nameZh.trim() || !form.nameEn.trim()) throw Error(t('请填写中英文名称。', 'Supply both names.'))
    const targets = copy(form.targets).map((target: ReviewObject) => {
      if (target.itemId?.trim()) { delete target.itemKey; delete target.itemValue }
      else if (target.itemKey && target.itemValue !== undefined) { const kind = state.value.variables.find(item => item.id === target.itemKey)?.type.kind; if (kind === 'number' && String(target.itemValue).trim()) target.itemValue = Number(target.itemValue) }
      const usesAnswer = ['fill', 'replace', 'insert'].includes(target.action) || target.action === 'check' && ['fill', 'replace', 'insert'].includes(target.proposedAction)
      if (target.valueMode !== 'join' || !target.valueSourceId || !usesAnswer) { delete target.listId; delete target.separator; target.valueMode = 'single' }
      if (!usesAnswer) { delete target.valueSourceId; delete target.replacementZh; delete target.replacementEn }
      if (!['fill', 'replace'].includes(target.action === 'check' ? target.proposedAction : target.action) || targetSpans(target).length <= 1) delete target.replacementPlacement
      reviewReplacementPlacement(target)
      return target
    })
    const base = JSON.parse(formBase.value), values: ReviewObject = {}
    if (selected.value.factProjectionReview && JSON.stringify(form.branches) !== JSON.stringify(selected.value.correction?.branches ?? [])) for (const target of targets) target.needsRebind = true
    for (const language of ['Zh', 'En']) if (form['name' + language] !== base['name' + language]) { values['name' + language] = form['name' + language]; values['question' + language] = form['name' + language] }
    if (form.kind !== base.kind || form.unit !== base.unit) values.type = { ...selected.value.type, kind: form.kind, zh: kinds[form.kind], en: form.kind, unit: form.kind === 'number' ? form.unit.trim() : '' }
    if (form.optional !== base.optional) values.optional = form.optional
    if (form.scope !== base.scope) values.definitionScope = form.scope.trim()
    if (form.manualReason !== base.manualReason) { if (!form.manualReason.trim()) throw Error(t('人工定义理由不能为空。', 'A manual definition needs a reason.')); values.definitionOrigin = { kind: 'manual', reason: form.manualReason.trim() } }
    if (form.role !== base.role) values.role = { kind: form.role, zh: roles[form.role], en: form.role }
    if (form.parentId !== base.parentId) values.parentId = form.parentId || null
    if (form.fixedValue !== base.fixedValue) values.fixedValue = form.fixedValue
    if (form.optionsText !== base.optionsText) { const changedOptions = options(); if (!changedOptions.length || changedOptions.some(option=>!String(option.value).trim()) || new Set(changedOptions.map(option=>String(option.value))).size !== changedOptions.length) throw Error(t('请填写不重复的选项稳定值。', 'Supply unique stable option values.')); values.options = changedOptions }
    const actions = [...(Object.keys(values).length ? [{ type: 'EDIT', id: selectedId.value, values }] : []),
      ...(selected.value.splitReview && form.splitMode !== selected.value.splitReview.mode ? [{ type: 'EDIT', id: selectedId.value, values: { splitReview: { ...copy(selected.value.splitReview), mode: form.splitMode } } }] : []),
      ...(selected.value.correction?.kind === 'split' && form.splitMode !== selected.value.correction.mode ? [{ type: 'RULE', id: selectedId.value, correction: { ...copy(selected.value.correction), mode: form.splitMode, needsRebind: form.splitMode === 'unknown' } }] : []),
      ...(JSON.stringify(form.branches) !== JSON.stringify(selected.value.correction?.kind === 'rule' ? selected.value.correction.branches ?? [] : []) ? [{ type: 'RULE', id: selectedId.value, correction: form.branches.length ? { kind: 'rule', branches: copy(form.branches), ...(selected.value.correction?.supersededCorrection ? { supersededCorrection: copy(selected.value.correction.supersededCorrection) } : {}), otherwise: 'needs_review' } : null }] : []),
      { type: 'TARGETS', id: selectedId.value, targets }]
    const reducer = createReviewReducer({ variables: state.value.variables, sourceUnits: [], sources: graph.value.sources })
    let next = state.value
    for (const action of actions) { const result = reducer.reduce(next, { ...action, at: new Date().toISOString() }); if (result === next) throw Error(t('变量的条件或来源版本不相容。', 'Conditions or source editions are incompatible.')); next = result }
    if (valueDependencies(next).some(edge => edge.cyclic)) throw Error(t('派生答案存在循环，请核对取值规则及明细归属；当前调整仍保留。', 'Derived answers have a cyclic dependency. Review the value rules and record membership; your current edits are retained.'))
    undo.value = [...undo.value.slice(-49), state.value]; graph.value = { ...graph.value, graph: next }; changed(); resetForm(); error.value = ''
  } catch (caught) { error.value = caught instanceof Error ? caught.message : String(caught) }
}
function addQuestion() { if (!protect() || !ruleSource.value || !selection.value) return; try { apply({ type: 'ADD', docId: selection.value.docId, anchor: selection.value, values: { nameZh: t('新变量', 'New variable'), nameEn: 'New question', type: { kind: 'boolean', zh: '是／否', en: 'Yes / No' }, role: { kind: 'input', zh: '项目资料', en: 'Project information' } }, reason: '人工从原文补充 / Added from the source' }); selectedId.value = state.value.variables.at(-1)!.id; resetForm();editingQuestion.value=true } catch (caught) { error.value = String(caught) } }
function linkSource() { if (!protect() || !ruleSource.value || !selection.value || !selected.value) return; try { apply({ type: 'LINK', id: selectedId.value, anchor: selection.value }); resetForm() } catch (caught) { error.value = String(caught) } }
function appendTarget(index: number) { if (busy.value || !selection.value?.native) return; try { form.targets[index] = appendNativeSelection(form.targets[index], selection.value); error.value = '' } catch (caught) { error.value = caught instanceof Error ? caught.message : String(caught) } }
function acceptRefinement(index: number) {
  const target=form.targets[index], candidate=target?.refinementCandidate, source=reading.value, span=candidate?.native?.spans?.[0]
  const paragraph=source?.paragraphs.find(item => item.id===span?.paragraphId)
  const originalSpans=target?.native?.spans ?? (target?.native ? [target.native] : [])
  if (!source || !span || !paragraph || candidate?.status!=='pending' || candidate.editableAuthority!==false || candidate.native.spans.length!==1 || String(target.docId)!==String(source.id) || source.format!=='docx' || !source.canEdit || source.sourceSha256!==target.sourceSha256 || source.sourceSha256!==candidate.native.sourceSha256 || span.sourceSha256!==source.sourceSha256 || paragraph.paragraphSha256!==span.paragraphSha256 || !Number.isInteger(span.start) || !Number.isInteger(span.end) || span.start<0 || span.end<=span.start || !originalSpans.some((part: ReviewObject) => part.paragraphId===span.paragraphId && part.paragraphSha256===span.paragraphSha256 && part.start<=span.start && part.end>=span.end) || paragraph.text.slice(span.start,span.end)!==candidate.text || span.expectedText!==candidate.text) { error.value=t('该建议不能与当前原件准确对应，请手动选词。', 'This suggestion cannot be matched exactly to the current source. Select the text manually.'); return }
  target.observedScopeOriginal={text:target.text,native:copy(target.native),sourceScopeId:target.sourceScopeId ?? null}
  target.text=candidate.text; target.native=copy(candidate.native); delete target.nativeBinding; target.needsRebind=false; target.scopeComplete=true
  target.refinementCandidate={...candidate,status:'accepted_for_review'}; target.scopeReviewReason='Human selected the suggested subrange; original scope retained. Treatment and original binding still require review.'
  error.value=''
}
function addTarget() { if (busy.value) return; if (!ruleSource.value || !selection.value || !selected.value) return; editingQuestion.value=true;form.targets.push({ ...copy(selection.value), action: selection.value.native?'fill':'check', valueMode: 'single', valueSourceId: isValueVariable(selected.value) ? selectedId.value : '', branchId: '', prefix: '', suffix: '' }) }
function addBranch() { if (busy.value) return; form.branches.push({ id: 'case-' + Date.now().toString(36), mode: 'all', scopeId: '', scopeMode: '', clauses: [], effectZh: '', effectEn: '' }) }
function split() {
  if (busy.value) return
  try {
    if (!selected.value || !splitFirst.value || !splitSecond.value || ![splitNames.firstZh, splitNames.firstEn, splitNames.secondZh, splitNames.secondEn].every(value => value.trim())) throw Error(t('请分别选取两项原文并填写名称。', 'Select both source passages and supply both names.'))
    const items = [{ nameZh: splitNames.firstZh, nameEn: splitNames.firstEn, sourceAnchors: [splitFirst.value] }, { nameZh: splitNames.secondZh, nameEn: splitNames.secondEn, sourceAnchors: [splitSecond.value] }]
    if (createReviewReducer({ sources: graph.value.sources }).splitProblem(state.value, selectedId.value, items)) throw Error(t('每处修改目标须准确归属其中一个选区。请选取包含完整目标且互不重叠的原文范围，或先在原变量调整目标；本次未拆分。', 'Each amendment target must belong to exactly one selection. Select non-overlapping source ranges containing the complete targets, or adjust the targets on the original variable first; nothing was split.'))
    apply({ type: 'SPLIT', id: selectedId.value, mode: splitNames.mode, items }); cancelSplit(); selectedId.value = state.value.variables.find(item => item.originId === selectedId.value && item.status !== 'excluded')!.id; resetForm()
  } catch (caught) { error.value = caught instanceof Error ? caught.message : String(caught) }
}
function removeIsolatedRule() { if (!protect() || !isolatedRule.value) return; try { apply({ type: 'RULE', id: selectedId.value, correction: null }); resetForm(); error.value = '' } catch (caught) { error.value = caught instanceof Error ? caught.message : String(caught) } }
function reviewItem() { if (!protect() || !selected.value) return; const problem = reviewProblem(selected.value, state.value); if (problem) { error.value = problem; return }; apply({ type: 'REVIEW', id: selectedId.value }); resetForm() }
function confirmDefinition() { if (!protect() || !selected.value || !['input','decision','content'].includes(selected.value.role?.kind) || isReviewCheck(selected.value) || pendingEditorial(selected.value)) return; apply({ type: 'CONFIRM_DEFINITION', id: selectedId.value }); error.value = ''; resetForm() }
function classifyEditorial(asValue: boolean) {
  if (!protect() || !selected.value || selected.value.definitionKind === 'source_seed') return
  if (!asValue && state.value.variables.some(item => item.status !== 'excluded' && item.parentId === selectedId.value)) { error.value = t('请先调整或移出该事项下的子变量，再标记为指导或修改事项。', 'Move or review its child variables before classifying this item as guidance or editing.'); return }
  if (asValue && isReviewCheck(selected.value) && selected.value.editorialClassificationHistory?.some((item: ReviewObject) => !item.restored)) {
    try { apply({type:'RESTORE_BUSINESS_CLASSIFICATION',id:selectedId.value});error.value='';resetForm() }
    catch { error.value=t('当前用途在分类后已被修改，请人工核对后重新设置。', 'The role was changed after classification; review and set it manually.') }
    return
  }
  const values: ReviewObject = { editorialReview: { kind: asValue ? 'business_value' : 'editorial', confirmed: true } }
  if (!asValue) values.role = { kind: 'verification', zh: roles.verification, en: 'Manual check' }
  else if (isReviewCheck(selected.value)) values.role = { kind: 'input', zh: roles.input, en: 'Project information' }
  apply({ type: 'EDIT', id: selectedId.value, values }); error.value = ''; resetForm()
}
function convertProjection() {
  if (!protect() || !projectionProposal.value || !projectionExhaustive.value) return
  try { const proposal=projectionProposal.value; apply({ type:'CONVERT_FACT_PROJECTION', id:selectedId.value, rootId:projectionRoot.value, baselineKey:proposal.baselineKey, branchValue:proposal.factProjection.branchValue, branchPolarity:proposal.factProjection.branchPolarity, exhaustiveConfirmed:true }); error.value=''; resetForm() }
  catch (caught) { error.value = caught instanceof Error ? caught.message : String(caught) }
}
function convertManualProjection() {
  const root=manualProjectionChoice.value
  if (!protect() || !root || !manualProjectionBranch.value || !['true','false'].includes(manualProjectionPolarity.value) || !manualProjectionReason.value.trim() || !manualProjectionExhaustive.value) return
  try { apply({type:'CONVERT_FACT_PROJECTION',id:selectedId.value,rootId:root.id,baselineKey:root.catalogAlignment.baselineKey,branchValue:manualProjectionBranch.value,branchPolarity:manualProjectionPolarity.value==='true',exhaustiveConfirmed:true,origin:'manual',reason:manualProjectionReason.value.trim()}); error.value=''; resetForm() }
  catch (caught) { error.value = caught instanceof Error ? caught.message : String(caught) }
}
function adoptFixedApplicability(id: string, applicability: ReviewObject) {
  if (!protect() || dirty.value || splitDirty.value) return
  try { apply({type:'SET_FIXED_APPLICABILITY',id,applicability}); error.value=''; resetForm() }
  catch (caught) { error.value = caught instanceof Error ? caught.message : String(caught) }
}
function clearFixedApplicability() {
  if (!protect()) return
  try { apply({type:'CLEAR_FIXED_APPLICABILITY',id:selectedId.value}); error.value=''; resetForm() }
  catch (caught) { error.value = caught instanceof Error ? caught.message : String(caught) }
}
function alignPdfReference(id: string, alignment: ReviewObject) {
  if (!protect() || dirty.value || splitDirty.value) return
  try { apply({type:'SET_PDF_REFERENCE_ALIGNMENT',id,alignment}); error.value=''; resetForm() }
  catch (caught) { error.value = caught instanceof Error ? caught.message : String(caught) }
}
function unalignPdfReference(id: string) {
  if (!protect() || dirty.value || splitDirty.value) return
  try { apply({type:'CLEAR_PDF_REFERENCE_ALIGNMENT',id}); error.value=''; resetForm() }
  catch (caught) { error.value = caught instanceof Error ? caught.message : String(caught) }
}
function revertProjection() { if (!protect()) return; try { apply({type:'REVERT_FACT_PROJECTION',id:selectedId.value}); error.value=''; resetForm() } catch (caught) { error.value = caught instanceof Error ? caught.message : String(caught) } }
function resolvePreviousProjection() {
  if(!protect() || !previousProjectionReason.value.trim() || !previousProjectionChecked.value || !['replace','rebuilt'].includes(previousProjectionDecision.value))return
  try { apply({type:'RESOLVE_PREVIOUS_PROJECTION_RULE',id:selectedId.value,decision:previousProjectionDecision.value,reason:previousProjectionReason.value.trim(),checked:true});error.value='';previousProjectionReason.value='';previousProjectionChecked.value=false;previousProjectionDecision.value='';resetForm() }
  catch(caught){error.value=caught instanceof Error?caught.message:String(caught)}
}
async function loadProjectionHistory() {
  if (!protect() || dirty.value || splitDirty.value || !selected.value?.factProjectionReview) return
  const project=props.projectId, id=selectedId.value, token=generation, revision=workingReview(head.value)?.id
  busy.value=true
  try {
    const plan=await draftingApi.plan(project) as ReviewObject
    if (!alive || project!==props.projectId || id!==selectedId.value || token!==generation) return
    if (plan.graphRevisionId!==revision) throw Error(t('方案使用了不同图谱版本，请先保存或重新读取当前图谱。', 'The plan uses a different graph revision. Save or reload the current graph.'))
    const issue=(plan.unresolved ?? []).find((item: ReviewObject) => ['HistoricalProjectionAnswerConflict','HistoricalProjectionAnswerReview'].includes(item.kind) && item.inputKeys?.includes(id))
    if (!issue) { projectionHistory.value=undefined;projectionHistorySources.value=undefined;error.value=t('当前方案没有需要复核的历史答案。', 'The current plan has no historical answer requiring review.');return }
    const sources=await draftingApi.projectionHistorySources(project,revision!,id)
    if (!alive || project!==props.projectId || id!==selectedId.value || token!==generation) return
    if (sources.graphRevisionId!==revision || sources.nodeId!==id || sources.evidenceFingerprint!==issue.evidenceFingerprint) throw Error(t('历史来源在读取期间发生变化，请重新核对。','Historical evidence changed while reading. Review it again.'))
    projectionHistory.value=issue;projectionHistorySources.value=sources;error.value=''
  } catch (caught) { error.value=caught instanceof Error ? caught.message : String(caught) }
  finally { if (token===generation) busy.value=false }
}
function resolveProjectionHistory() {
  const review=selected.value?.factProjectionReview, issue=projectionHistory.value
  if (!protect() || !review || !issue || issue.rootId!==review.rootId || issue.rootValue==null || typeof issue.oldValue!=='boolean' || typeof issue.derivedValue!=='boolean' || typeof issue.evidenceFingerprint!=='string' || !issue.evidenceFingerprint || !projectionHistoryReason.value.trim()) return
  apply({type:'EDIT',id:selectedId.value,values:{factProjectionReview:{...copy(review),historicalAnswerResolution:{confirmed:true,decision:'use_derived',rootValue:issue.rootValue,oldValue:issue.oldValue,evidenceFingerprint:issue.evidenceFingerprint,reason:projectionHistoryReason.value.trim()}}}})
  projectionHistory.value=undefined;projectionHistorySources.value=undefined;projectionHistoryReason.value='';resetForm();error.value=''
}
function snapshot(project = props.projectId) {
  if (!project) return
  const page = Number(workspace.value?.querySelector?.<HTMLInputElement>('.pdf-page-number')?.value) || initialPdfPage.value
  const editorScrollTop = workspace.value?.querySelector?.<HTMLElement>('.review-editor')?.scrollTop ?? 0
  if (dirty.value || formDirty.value || splitDirty.value || checkedIds.value.length || catalogOpen.value || newConditionInput.branchId) reviewDraftCache.set(project, { graph: copy(graph.value), head: copy(head.value), scopeBinding:copy(scopeBinding.value), dirty: dirty.value, selectedId: selectedId.value, sourceId: sourceId.value, form: copy(form), formBase: formBase.value, formOpen: formOpen.value, newConditionInput: copy(newConditionInput), catalogDraft: copy(catalogDraft), catalogOpen: catalogOpen.value, view: { immersive: immersive.value, page, editorScrollTop, editingQuestion: editingQuestion.value, queries: copy(optionQueries) }, split: { names: copy(splitNames), first: copy(splitFirst.value ?? null) ?? undefined, second: copy(splitSecond.value ?? null) ?? undefined }, batch: { ids: [...checkedIds.value], open: batchOpen.value, draft: batchDraft.value ? copy(batchDraft.value) : undefined } })
  else if (!busy.value) reviewDraftCache.delete(project)
}
async function refresh(preserve = true) {
  const project = props.projectId, token = ++generation; saving.value = false; if (!project) return; busy.value = true; error.value = ''
  const cached = preserve ? reviewDraftCache.get(project) : undefined
  if (cached?.graph && cached.head && cached.form) {
    graph.value = copy(cached.graph); head.value = copy(cached.head); scopeBinding.value=copy(cached.scopeBinding ?? {integrity:{},entries:{}}); dirty.value = cached.dirty; selectedId.value = cached.selectedId; sourceId.value = cached.sourceId
    Object.assign(form, copy(cached.form)); formBase.value = cached.formBase; formOpen.value = cached.formOpen
    closeNewConditionInput(); if (cached.newConditionInput) Object.assign(newConditionInput, copy(cached.newConditionInput))
    Object.assign(catalogDraft, emptyCatalogDraft(), copy(cached.catalogDraft ?? {})); catalogOpen.value = !!cached.catalogOpen
    editingQuestion.value = cached.view?.editingQuestion ?? JSON.stringify(cached.form) !== cached.formBase
    for (const key of Object.keys(optionQueries)) delete optionQueries[key]
    if (cached.view) { Object.assign(optionQueries, cached.view.queries); initialPdfPage.value = cached.view.page; immersive.value = cached.view.immersive }
    if (cached.split) { Object.assign(splitNames, cached.split.names); splitFirst.value = cached.split.first; splitSecond.value = cached.split.second }
    if (cached.batch) { checkedIds.value = cached.batch.ids; batchDraft.value = cached.batch.draft; batchOpen.value = cached.batch.open }
  }
  try {
    const result = await draftingApi.reviewHead(project)
    if (!alive || project !== props.projectId || token !== generation) return
    if (!Array.isArray(result?.sources)) throw Error(t('模板核对状态返回不完整，请重试读取。', 'Template review state is incomplete. Retry loading it.'))
    if (result.workingRevisionId && !workingReview(result)) throw Error(t('当前图谱版本未包含在核对响应中，请重试。', 'The active graph revision is missing from the review response. Retry.'))
    head.value = result; if (workingReview(result)) emit('saved', workingReview(result)!)
    if (cached) {
      graph.value = copy(cached.graph); scopeBinding.value=copy(cached.scopeBinding ?? {integrity:{},entries:{}}); dirty.value = cached.dirty; selectedId.value = cached.selectedId
      Object.assign(form, copy(cached.form)); formBase.value = cached.formBase; formOpen.value = cached.formOpen
      closeNewConditionInput(); if (cached.newConditionInput) Object.assign(newConditionInput, copy(cached.newConditionInput))
      Object.assign(catalogDraft, emptyCatalogDraft(), copy(cached.catalogDraft ?? {})); catalogOpen.value = false; if (cached.catalogOpen) void openCatalog()
      editingQuestion.value = cached.view?.editingQuestion ?? JSON.stringify(cached.form) !== cached.formBase
      for (const key of Object.keys(optionQueries)) delete optionQueries[key]
      if (cached.view) { Object.assign(optionQueries, cached.view.queries); initialPdfPage.value = cached.view.page }
      if (cached.split) { Object.assign(splitNames, cached.split.names); splitFirst.value = cached.split.first; splitSecond.value = cached.split.second }
      if (cached.batch) { checkedIds.value = cached.batch.ids; batchDraft.value = cached.batch.draft; batchOpen.value = cached.batch.open }
      if (cached.head.currentRevisionId !== result.currentRevisionId || cached.head.draftRevisionId !== result.draftRevisionId) error.value = t('已恢复未保存调整；服务器版本已变化，保存时需核对冲突。', 'Unsaved work restored; the server revision changed. Resolve the conflict before saving.')
      head.value = { ...result, currentRevisionId: cached.head.currentRevisionId, draftRevisionId: cached.head.draftRevisionId, workingRevisionId: cached.head.workingRevisionId }
      if (cached.sourceId && result.sources.some(source => String(source.id) === cached.sourceId)) await selectSource(cached.sourceId, true)
      if (cached.view && alive && project === props.projectId && token === generation) { immersive.value = cached.view.immersive; await nextTick(); const editor = workspace.value?.querySelector?.<HTMLElement>('.review-editor'); if (editor) editor.scrollTop = cached.view.editorScrollTop }
      return
    }
    graph.value = workingReview(result)?.graph ?? { kind: 'consense-template-graph-v1', graph: emptyReviewState(), sources: result.sources, selectedDocuments: {} }; bindScopeRevision(workingReview(result))
    selectedId.value = ''; formOpen.value = false; closeNewConditionInput(); initialPdfPage.value = 1; dirty.value = false; emit('dirty', false); undo.value = []
    if (result.sources.length) await selectSource(String(result.sources[0]!.id), true)
  } catch (caught) { error.value = caught instanceof Error ? caught.message : String(caught) } finally { if (token === generation) busy.value = false }
}
async function save(): Promise<ReviewRevision | undefined> {
  if (splitDirty.value) { error.value = t('请先完成或取消拆分，再保存。', 'Complete or cancel the split before saving.'); return }
  if (props.locked) throw Error(t('请先完成候选调整或当前操作。', 'Complete the candidate edit or current operation first.'))
  if (!protect() || !props.projectId) return
  const project = props.projectId, token = generation; busy.value = true; saving.value = true; error.value = ''
  try { const result = await draftingApi.reviewSave(project, graph.value, head.value.currentRevisionId, head.value.draftRevisionId); if (!alive || project !== props.projectId || token !== generation) return; head.value = { ...head.value, draftRevisionId: result.id, workingRevisionId: result.id, draft: result }; graph.value = result.graph; bindScopeRevision(result); dirty.value = false; reviewDraftCache.delete(project); emit('dirty', false); resetForm(); emit('saved', result); return result } catch (caught) { if (alive && project === props.projectId && token === generation) error.value = caught instanceof Error ? caught.message : String(caught) } finally { if (token === generation) { busy.value = false; saving.value = false } }
}
async function reviewRelations(request: RelationReviewRequest): Promise<ReviewRevision | undefined> {
  const revision=workingReview(head.value)
  if(!revision||revision.id!==request.revisionId||revision.sourceStale||dirty.value||formDirty.value||splitDirty.value||props.locked||!protect())throw Error(t('请先保存当前调整，并重新读取当前关系建议。','Save current edits and reload the current relationship proposals.'))
  const before=state.value
  apply({type:'REVIEW_RELATIONS',indices:request.indices,decision:request.decision,reason:request.reason})
  if(state.value===before||!dirty.value)throw Error(t('请选择有效的关系建议，并填写处理理由。','Select valid relationship proposals and give a review reason.'))
  const saved=await save();if(!saved)throw Error(error.value||t('关系处理尚未保存。','The relationship review has not been saved.'));return saved
}
async function confirmDefinitionGraph() {
  const revision=workingReview(head.value), project=props.projectId, token=generation, source=sourceId.value
  if (!revision || revision.status!=='DRAFT' || revision.id!==head.value.draftRevisionId || !supportsDefinitionGroups.value || revision.sourceStale || !definitionGraphReason.value.trim() || !definitionGraphAcknowledged.value || !definitionGroupSelection.value.length || busy.value || props.locked || dirty.value || formDirty.value || splitDirty.value || invalidTemplateSources.value.length) return
  busy.value=true;error.value=''
  try {
    const result=definitionReuseForEdition.value && !definitionBaselineReuseBlocked.value ? definitionCompleteEdition.value && revision.definitionGraphReview?.completeBaselineReuseSupported ? await draftingApi.reviewConfirmDefinitions(project,revision.id,head.value.currentRevisionId,definitionGraphReason.value.trim(),true,definitionGroupSelection.value,true,true) : await draftingApi.reviewConfirmDefinitions(project,revision.id,head.value.currentRevisionId,definitionGraphReason.value.trim(),true,definitionGroupSelection.value,true) : await draftingApi.reviewConfirmDefinitions(project,revision.id,head.value.currentRevisionId,definitionGraphReason.value.trim(),true,definitionGroupSelection.value)
    if(!alive || project!==props.projectId || token!==generation)return
    await refresh(false)
    if(!alive || project!==props.projectId)return
    if(head.value.draftRevisionId!==result.id || head.value.draft?.definitionGraphReview?.valid!==true)throw Error(t('确认结果或当前图谱版本发生变化，请重新核对。','The confirmation or current graph revision changed. Review it again.'))
    definitionGraphReason.value='';definitionGraphAcknowledged.value=false;definitionReuseForEdition.value=false;definitionCompleteEdition.value=false;definitionGroupSelection.value=[];emit('saved',head.value.draft)
    if(source && head.value.sources.some(item=>String(item.id)===source) && source!==sourceId.value)await selectSource(source)
  } catch(caught){if(alive && project===props.projectId)error.value=caught instanceof Error?caught.message:String(caught)}
  finally{if(token===generation)busy.value=false}
}
async function readyReview() {
  const project = props.projectId
  await loading
  if (project === props.projectId && preparationProject === project) await preparation
}
function prepareCandidates(): Promise<ReviewRevision | undefined> {
  const project = props.projectId, token = generation
  if (preparation && preparationProject === project) return preparation
  const pending = prepareReview(project, token)
  preparation = pending; preparationProject = project
  const clearPreparation = () => { if (preparation === pending) { preparation = undefined; preparationProject = '' } }
  void pending.then(clearPreparation, clearPreparation)
  return pending
}
async function prepareReview(project: string, token: number): Promise<ReviewRevision | undefined> {
  await loading
  if (!alive || !project || project !== props.projectId || token !== generation) return
  if (props.locked || busy.value) throw Error(t('请先完成候选调整或当前操作。', 'Complete the candidate edit or current operation first.'))
  if (splitDirty.value) throw Error(t('请先完成或取消拆分。', 'Complete or cancel the split first.'))
  if (formDirty.value || formOpen.value || newConditionInput.branchId) throw Error(t('请先保存或取消当前变量调整。', 'Save or cancel the current variable edit first.'))
  if (invalidTemplateSources.value.length) throw Error(t('模板缺少当前有效来源证据。请先在来源步骤替换或重新上传，再建立核对版本。', 'Templates need current bound source evidence. Replace or re-upload them in Sources before preparing a review.'))
  const revision = workingReview(head.value)
  if (revision?.sourceStale) throw Error(t('当前核对版本引用的是旧来源证据。请按现有原件新建核对版本；历史记录继续保留。', 'This review references old source evidence. Start a new review from current sources; history is retained.'))
  if (hasReviewRevision.value || state.value.variables.length) { if (dirty.value || !revision) return await save(); return revision }
  if (dirty.value) throw Error(t('先保存现有模板核对草稿，避免覆盖。', 'Save the current review before preparing candidates.'))
  if (!boundTemplateAvailable.value) throw Error(t('请先上传并完成模板来源处理，再建立核对草稿。', 'Upload a template and complete source processing before preparing a review draft.'))
  busy.value = true; error.value = ''
  try {
    const result = await draftingApi.reviewBootstrap(project)
    if (!alive || project !== props.projectId || token !== generation) return
    if (props.locked || preparationEditPending.value || hasReviewRevision.value || state.value.variables.length) throw Error(t('当前核对状态已变化；已保留现有调整，请重新检查后操作。', 'The current review changed. Existing edits were kept; inspect them before continuing.'))
    graph.value = result.graph; bindScopeRevision(); head.value = { ...head.value, sources: [...head.value.sources.filter(source => !result.sources.some(item => String(item.id) === String(source.id))), ...result.sources] }; selectedId.value = ''; formOpen.value = false; changed(); busy.value = false
    return await save()
  } finally { if (token === generation) busy.value = false }
}
async function prepareEmptyReview() { const project = props.projectId, token = generation; try { await prepareCandidates() } catch (caught) { if (alive && project === props.projectId && token === generation) error.value = caught instanceof Error ? caught.message : String(caught) } }
async function confirm() { if (workingReview(head.value)?.status === 'CONFIRMED' || head.value.draft?.sourceStale || invalidTemplateSources.value.length || splitDirty.value || props.locked || dirty.value || formDirty.value || pending.value || !state.value.variables.some(item => item.status !== 'excluded') || !head.value.draftRevisionId) return; const project = props.projectId, token = generation; busy.value = true; try { await draftingApi.reviewConfirm(project, head.value.draftRevisionId, head.value.currentRevisionId); if (!alive || project !== props.projectId || token !== generation) return; await refresh(); emit('confirmed') } catch (caught) { error.value = caught instanceof Error ? caught.message : String(caught) } finally { if (project === props.projectId) busy.value = false } }
async function openHistory() { historyOpen.value = true; const project = props.projectId, token = generation; try { const incoming = await draftingApi.reviewRevisions(project); if (alive && project === props.projectId && token === generation) history.value = incoming } catch (caught) { if (project === props.projectId && token === generation) error.value = String(caught) } }
async function restoreRevision(revision: ReviewRevisionSummary) {
  if (busy.value || props.locked || !protect() || dirty.value) { error.value = t('请先保存当前草稿和候选调整。', 'Save the current draft and candidate edits first.'); return }
  const project = props.projectId, token = generation; busy.value = true
  try {
    const detail = await draftingApi.reviewRevision(project, revision.id)
    if (!alive || project !== props.projectId || token !== generation) return
    if (detail.id !== revision.id || detail.sourceStale || detail.graph?.kind !== 'consense-template-graph-v1') throw Error(t('此历史版本的来源已变化或内容不完整，不能恢复为当前草稿。', 'The historical source changed or its graph is incomplete. It cannot be restored as the current draft.'))
    const restored=copy(detail.graph);restored.graph.relationProposals=copy(workingReview(head.value)?.graph.graph.relationProposals??[])
    graph.value = restored; bindScopeRevision(detail); changed(); selectedId.value = ''; formOpen.value = false; historyOpen.value = false
  } catch (caught) { if (project === props.projectId && token === generation) error.value = caught instanceof Error ? caught.message : String(caught) }
  finally { if (project === props.projectId && token === generation) busy.value = false }
}
async function openOriginal() {
  if (!reading.value || originalLoading.value) return
  originalLoading.value = true; originalError.value = ''; sourceBindings.value=undefined
  if (pdfUrl.value) URL.revokeObjectURL(pdfUrl.value)
  pdfUrl.value = ''
  const project = props.projectId, source = reading.value, token = sourceGeneration
  const current=()=>alive&&project===props.projectId&&token===sourceGeneration
  try {
    let bindings:DraftDocumentBindings|undefined
    if(source.format==='docx'){
      try{bindings=await reviewSourceBindings(project,String(source.id),source.sourceSha256)}
      catch{if(current())locationNotice.value=t('原件定位数据暂不可用，仍可浏览文件；请稍后重新读取原件。', 'Source locations are temporarily unavailable. You can still browse; reopen the original to retry.')}
    }
    if(!current())return
    const blob=source.format==='docx'?await draftingApi.reviewPreview(project,String(source.id),source.sourceSha256):await draftingApi.reviewSource(project,String(source.id),source.sourceSha256)
    if(!current())return
    if(bindings){
      const hash=await pdfSha256(blob)
      if(!current())return
      if(bindings.sourceSha256===source.sourceSha256&&matchesBindingPdf(bindings,String(source.id),'source',hash))sourceBindings.value=bindings
      else locationNotice.value=t('定位数据与当前预览版本不一致，已停用定位；原件仍可浏览。请重新读取原件。', 'Locations do not match this preview and were disabled. The original remains readable; reopen it to retry.')
    }
    pdfUrl.value=URL.createObjectURL(blob)
  }catch(caught){if(current())originalError.value=caught instanceof Error?caught.message:String(caught)}
  finally{if(current())originalLoading.value=false}
}
watch(() => props.projectId, (_project, previous) => { if (previous) snapshot(previous); sourceGeneration++; if (pdfUrl.value) URL.revokeObjectURL(pdfUrl.value); pdfUrl.value = ''; originalLoading.value = false; originalError.value = ''; historyOpen.value = false; showGraph.value = false; catalogOpen.value = false; baselineCatalog.value = undefined; catalogLoading.value = false; catalogError.value = ''; Object.assign(catalogDraft, emptyCatalogDraft()); batchOpen.value = false; checkedIds.value = []; batchDraft.value = undefined; cancelSplit(); reading.value = undefined; selection.value = undefined; sourceId.value=''; selectedId.value=''; search.value=''; history.value=[]; graphSelected.value=''; sourceBindings.value=undefined; locationParagraphs.value=[]; locationSpans.value=[]; locationNotice.value=''; closeNewConditionInput(); graph.value={kind:'consense-template-graph-v1',graph:emptyReviewState(),sources:[],selectedDocuments:{}}; head.value={currentRevisionId:null,draftRevisionId:null,current:null,draft:null,sources:[]}; bindScopeRevision(); undo.value=[]; sourceLoading.value=false; dirty.value = false; formOpen.value = false; loading = refresh() }, { immediate: true })
watch(showGraph, open => { if (open) { graphSelected.value = selectedId.value; graphOnlySelected.value = false } })
watch([dirty, formDirty, splitDirty], () => emit('dirty', dirty.value || formDirty.value || splitDirty.value))
onBeforeUnmount(() => { snapshot(); alive = false; generation++; sourceGeneration++; if (pdfUrl.value) URL.revokeObjectURL(pdfUrl.value) })
async function locateQuestion(id: string, revisionId: string) {
  if (revisionId !== workingReview(head.value)?.id || !state.value.variables.some(item => item.id === id && item.status !== 'excluded')) { error.value = t('候选对应的变量版本已变化或已退出，请返回第三步重新核对归属。', 'The candidate variable revision changed or was retired. Return to step 3 and review its assignment.'); return }
  if (!protect()) return
  search.value = ''; await choose(id)
}
defineExpose({ prepareCandidates, refresh, save, reviewRelations, selectSource, locateSource, locateQuestion, ready: readyReview, currentRevisionId: () => head.value.currentRevisionId })
</script>

<template>
  <section class="source-review" :class="{ 'source-review--immersive': immersive }" data-source-review @keydown="workspaceKeydown"><fieldset class="review-controls" data-review-controls :disabled="busy">
    <header class="review-heading"><div><h3>{{ t('在原件上核对规则', 'Review rules on the source') }}</h3><p>{{ t('模板规则确认与本变量值采用分别保存。未知范围仍需核对。', 'Template confirmation and project-fact adoption are separate. Unresolved scope stays under review.') }}</p></div><div class="review-actions"><button class="btn" :disabled="busy" @click="openHistory">{{ t('图谱版本', 'Graph versions') }}</button><button class="btn" @click="showGraph = true">{{ t('查看关系', 'View relationships') }}</button><button class="btn" :disabled="busy || !undo.length || formDirty" @click="graph = { ...graph, graph: undo.pop()! }; changed(); resetForm()">{{ t('撤销', 'Undo') }}</button><button class="btn primary" data-review-save :disabled="busy || locked || !dirty || formDirty || splitDirty" @click="save">{{ saving ? t('正在保存草稿…', 'Saving draft…') : t('保存核对草稿', 'Save review draft') }}</button><button class="btn" data-review-confirm :disabled="busy || locked || dirty || formDirty || splitDirty || workingReview(head)?.status === 'CONFIRMED' || pending > 0 || !state.variables.length || !head.draftRevisionId || head.draft?.sourceStale || invalidTemplateSources.length > 0" @click="confirm">{{ t('确认此版模板用于起草', 'Confirm this template for drafting') }}</button></div></header>
    <section v-if="emptyReview" class="source-note" data-empty-review><p>{{ t('如有同版已核基线，将先恢复已核部分；否则建立手工核对草稿。此操作不调用模型。', 'Restore reviewed parts from a matching edition baseline when available, or create a draft for manual review. This operation does not call the model.') }}</p><button type="button" class="btn primary" data-prepare-review :disabled="busy || locked || !projectId || preparationEditPending || !boundTemplateAvailable || invalidTemplateSources.length > 0" @click="prepareEmptyReview">{{ t('恢复基线／建立手工草稿', 'Restore baseline / prepare manual draft') }}</button></section>
    <div class="review-actions"><button type="button" class="btn" data-review-new-variable :disabled="busy || splitDirty || formDirty" @click="openNewConditionInput('@standalone',-1)">{{ t('新增变量', 'Add variable') }}</button><button type="button" class="btn" data-review-catalog :aria-expanded="catalogOpen" @click="openCatalog">{{ t('变量基准对齐审核', 'Review baseline alignment') }}</button></div><DraftingNewVariable v-if="newConditionInput.branchId === '@standalone'" :draft="newConditionInput" :parents="state.variables.filter(item => isValueVariable(item) && ['record','list'].includes(item.type.kind))" :locale="locale" @create="createConditionInput" @cancel="closeNewConditionInput" /><p v-if="catalogOpen && catalogLoading" role="status">{{ t('正在读取基准目录…', 'Loading baseline catalog…') }}</p><p v-if="catalogOpen && catalogError" role="alert" class="review-error">{{ t('基准目录暂时无法读取；现有变量仍可编辑。', 'The baseline catalog is unavailable; existing variables can still be edited.') }}<button type="button" class="btn" @click="catalogOpen=false;openCatalog()">{{ t('重试读取', 'Retry') }}</button></p><DraftingVariableCatalog v-if="catalogOpen && baselineCatalog" :project-id="projectId" :catalog="baselineCatalog" :state="state" :draft="catalogDraft" :locale="locale" :disabled="busy || formDirty || splitDirty" @align="alignVariable" @add="addBaseline" @exclude="excludeBaseline" @inspect="locateBatchQuestion" /><p v-if="busy" role="status">{{ t('正在读取或保存…', 'Reading or saving…') }}</p><p v-if="error" class="review-error" role="alert">{{ error }}</p><p class="review-status">{{ businessQuestions }} {{ t('个业务变量', 'business variables') }} · {{ processingRules }} {{ t('项处理规则／派生项', 'treatment rules / derived items') }} · {{ sourceChecks }} {{ t('项核对事项', 'review checks') }} · {{ pending }} {{ t('项未确认', 'unconfirmed') }} · {{ dirty || formDirty || splitDirty ? t('有未保存调整', 'Unsaved changes') : t('草稿与确认版本分别保留', 'Draft and confirmed revisions are kept separately') }}</p>
    <p v-if="state.baselineRestoration?.kind === 'fixed_catalog_candidates'" class="source-note" data-restored-fixed-candidates>{{ t('已按一致的模板原件恢复内置变量定义候选；可在下方按组核对定义及前提条件。候选尚未确认，项目取值及未绑定的修改范围仍需单独处理。','Built-in variable definition candidates were restored for matching template originals. Review their definitions and prerequisites as a group below. Candidates remain unconfirmed; project values and unbound amendment ranges require separate review.') }}</p>
    <p v-else-if="state.baselineRestoration?.status === 'complete_requires_review'" class="source-note" data-restored-edition-baseline>{{ t('已恢复人工标记完整的同版图谱；请按当前原件核对修改范围，并单独采用项目取值。','A baseline marked complete by a reviewer was restored for the matching edition. Review edit ranges against the current original and adopt project values separately.') }}</p>
    <p v-else-if="state.baselineRestoration?.status === 'partial_requires_review'" class="source-note" data-restored-edition-baseline>{{ t('已按一致的原件恢复部分模板定义及关联，仍需核对范围与未覆盖事项；未带入项目答案。','Some template definitions and relationships were restored from matching originals. Review their ranges and remaining gaps; no project answers were imported.') }}</p>
    <p v-else-if="state.baselineRestoration?.status === 'unavailable_requires_review'" class="source-note">{{ t('找到同版基线记录，但暂无法安全还原；现有原文仍可识别和手动核对。','A same-version baseline was found but could not be safely restored. The current sources remain available for identification and manual review.') }}</p>
    <DraftingFixedRuleSuggestions v-if="workingReview(head)" :revision="workingReview(head)!" :locale="locale" :disabled="busy || locked || dirty || formDirty || splitDirty" @question="locateQuestion" @adopt="adoptFixedApplicability" />
    <section v-if="workingReview(head)" class="source-note" data-definition-graph-review>
      <h4>{{ t('按组确认变量用于项目取值','Confirm a group of variables for project values') }}</h4>
      <p>{{ t('已确认变量','Confirmed variables') }}: {{ workingReview(head)?.definitionGraphReview?.confirmedFieldIds?.length ?? 0 }} · {{ t('仍待核变量','Variables awaiting review') }}: {{ workingReview(head)?.definitionGraphReview?.pendingFieldIds?.length ?? '—' }} · {{ t('未决关系','Unresolved relationships') }}: {{ workingReview(head)?.definitionGraphReview?.unresolvedRelationCount ?? '—' }}</p>
      <p v-if="workingReview(head)?.definitionGraphReview?.valid">{{ t('已确认部分可用于项目候选提取；其他部分和修改范围继续核对。','The confirmed group is available for project candidate extraction; review the remaining definitions and amendment ranges separately.') }}</p>
      <template v-if="workingReview(head)?.status === 'DRAFT'">
        <p v-if="!supportsDefinitionGroups" class="review-error">{{ t('当前服务尚不支持按组确认；请使用前后端版本一致的服务。现有草稿保留。','The current service does not support group confirmation. Use matching frontend and backend versions; the draft is retained.') }}</p>
        <p>{{ t('选择要确认的一组变量，核对定义、前提条件和明细，填写理由并勾选确认。内置候选可直接在此按组确认；其他变量须先核对对应或定义并保存草稿。系统会校验每项及其来源；未决关系不自动采用。','Select a group and review its definitions, prerequisites and details, then enter a reason and acknowledge the review. Built-in candidates can be confirmed here as a group; review and save other variable alignments or definitions first. The service checks each definition and source; unresolved relationships are not adopted automatically.') }}</p>
        <p v-if="definitionAlignmentPending.length" class="source-note" data-definition-alignment-pending>{{ definitionAlignmentPending.length }} {{ t('个变量尚未完成固定对应审核；请先核对对应并保存，再加入这组。','variables need alignment review. Review and save their correspondence before adding them to this group.') }} <button type="button" class="btn" data-definition-open-alignment :disabled="busy || formDirty || splitDirty" @click="catalogOpen || openCatalog()">{{ t('变量基准对齐审核','Review baseline alignment') }}</button></p>
        <details v-if="definitionGroupOptions.length"><summary>{{ t('选择待确认的变量定义','Select variable definitions awaiting confirmation') }} · {{ definitionGroupSelection.length }} / {{ definitionGroupOptions.length }}</summary>
          <button type="button" class="btn" data-definition-select-all @click="definitionGroupSelection=definitionGroupOptions.map(item=>item.id)">{{ t('全选这组','Select this group') }}</button>
          <button type="button" class="btn" @click="definitionGroupSelection=[]">{{ t('清除选择','Clear selection') }}</button>
          <div v-for="item in definitionGroupOptions" :key="item.id">
            <label><input v-model="definitionGroupSelection" type="checkbox" :value="item.id" :data-definition-group-choice="item.id">{{ item.nameZh || item.nameEn }} · {{ item.nameEn || item.id }} · {{ item.catalogAlignment?.baselineKey || item.type?.kind }}</label>
            <p v-if="fixedCandidateFieldIds.has(item.id)" class="source-note" :data-definition-fixed-candidate="item.id">{{ t('内置定义候选：模板原件身份已核对，定义仍待本次人工确认；不会据此采用项目取值或授权未知修改范围。','Built-in definition candidate: the template original identity was checked; this definition still needs human confirmation. Confirmation does not adopt project values or authorize unknown amendment ranges.') }}</p>
            <p>{{ item.questionZh || item.questionEn }}</p><p v-if="item.catalogAlignment?.reason">{{ t('对应理由','Alignment reason') }}: {{ item.catalogAlignment.reason }}</p>
            <blockquote v-if="item.semanticSourceQuote || item.sourceAnchors?.[0]?.text">{{ item.semanticSourceQuote || item.sourceAnchors[0].text }}</blockquote>
            <details v-if="item.correction"><summary>{{ t('查看条件及分支','Review conditions and branches') }}</summary><div v-for="branch in item.correction.branches ?? []" :key="branch.id"><p>{{ branch.mode === 'any' ? t('满足任一条件','Any condition') : t('同时满足','All conditions') }}: {{ branch.effectZh || branch.effectEn }}</p><ul><li v-for="(clause,index) in branch.clauses ?? []" :key="index">{{ state.variables.find(variable=>variable.id===clause.inputId)?.nameZh || state.variables.find(variable=>variable.id===clause.inputId)?.nameEn || clause.inputId }} {{ operators[clause.operator] || clause.operator }} {{ clause.value }}</li></ul></div><p v-if="!item.correction.branches?.length">{{ t('条件结构须在关系图中核对。','Review this condition in the relationship graph.') }}</p></details>
          </div>
        </details>
        <p v-else>{{ t('没有可加入的待确认变量定义。','No variable definitions are available for group confirmation.') }}</p>
        <label>{{ t('人工核对理由','Human review reason') }}<textarea v-model="definitionGraphReason" data-definition-graph-reason rows="2" /></label>
        <label><input v-model="definitionGraphAcknowledged" data-definition-graph-acknowledged type="checkbox">{{ t('我已核对选中的变量及其关系；未决关系继续待核，不自动采用。','I reviewed the selected variables and their relationships; unresolved relationships remain pending and are not automatically adopted.') }}</label>
        <p v-if="definitionBaselineReuseBlocked" class="source-note" data-definition-fixed-reuse-blocked>{{ t('内置定义会自动恢复；当前固定规则草稿不登记为已完整核对基线。定义确认不会把空白或未知修改范围变成已核对范围。','Built-in definitions restore automatically; this fixed-rule draft is not registered as a fully reviewed baseline. Confirming definitions does not make empty or unknown amendment ranges reviewed.') }}</p>
        <label v-if="workingReview(head)?.definitionGraphReview?.baselineReuseSupported"><input v-model="definitionReuseForEdition" data-definition-reuse-edition type="checkbox" :disabled="definitionBaselineReuseBlocked">{{ t('选中定义、必要依赖及关联的已确认处理关系适用于这一组模板版本；我已确认其名称、说明、选项和条件不含本项目专属内容，同意以后同版项目作为待核草稿复用。项目候选和采用值不会带入。','The selected definitions, necessary dependencies and associated confirmed processing relationships apply to this template version. I have checked their names, descriptions, options and conditions contain no project-specific content and agree to reuse them as review drafts for this version. Project candidates and adopted values are not imported.') }}</label>
        <label v-if="definitionReuseForEdition && workingReview(head)?.definitionGraphReview?.completeBaselineReuseSupported"><input v-model="definitionCompleteEdition" data-definition-complete-edition type="checkbox" :disabled="definitionBaselineReuseBlocked">{{ t('我已核对本版全部原文、变量、关系及修改范围，确认没有遗漏；可作为完整基线供同版恢复。系统仍会检查识别覆盖及未决项。','I reviewed all source text, variables, relationships and edit ranges for this edition and found no omissions. Reuse it as a complete baseline for this edition; coverage and unresolved items will still be checked.') }}</label>
        <button type="button" class="btn" data-definition-graph-confirm :disabled="busy || locked || dirty || formDirty || splitDirty || !supportsDefinitionGroups || workingReview(head)?.sourceStale || !definitionGraphReason.trim() || !definitionGraphAcknowledged || !definitionGroupSelection.length" @click="confirmDefinitionGraph">{{ t('确认选中变量用于项目取值','Confirm selected variables for project values') }}</button>
        <p v-if="dirty || formDirty || splitDirty">{{ t('请先保存或取消未保存调整。','Save or discard unsaved changes first.') }}</p>
      </template>
    </section>
    <div v-if="pending" class="source-note" data-review-next-step><p>{{ t('逐项核对变量和影响范围，保存核对草稿。未决修改保留原文，可导出明确标记的未完成草稿；变量值仍需在下一步采用。', 'Review variables and their affected passages, then save the draft. Unresolved changes retain their original wording in an explicitly marked incomplete draft. Project answers are not adopted automatically.') }}</p><button class="btn" data-review-next-pending :disabled="busy || formDirty || splitDirty || !nextPending" @click="nextPending && choose(nextPending.id)">{{ t('继续核对下一项', 'Review next unfinished item') }}</button><p v-if="formDirty || splitDirty">{{ t('请先保存当前变量的调整，或完成／取消拆分，再切换下一项。', 'Save the current variable changes, or finish/cancel splitting, before continuing.') }}</p></div>
    <div class="source-choice" data-source-edition-toolbar>
      <nav class="source-tabs" :aria-label="t('目标原件', 'Source documents')"><button v-for="family in ['NTT','SCT','SCC']" :key="family" class="btn" :aria-pressed="selectedSource?.fileKey===family" :disabled="busy||formDirty||splitDirty||!head.sources.some(source=>source.fileKey===family)" @click="selectFamily(family)">{{ family }}</button></nav>
      <label>{{ t('正在核对的原件', 'Source being reviewed') }}<select :value="sourceId" :disabled="busy||formDirty||splitDirty" @change="selectSource(($event.target as HTMLSelectElement).value)"><option v-for="source in head.sources" :key="source.id" :value="String(source.id)">{{ source.fileName }}</option></select></label>
      <span v-if="reading?.fileKey && String(graph.selectedDocuments[reading.fileKey])===String(reading.id)" class="edition-status">{{ t('本次起草采用此版', 'Selected drafting edition') }}</span>
      <button v-else-if="reading?.fileKey && ['NTT','SCT','SCC'].includes(reading.fileKey)" class="btn" :disabled="busy||locked||sourceLoading||formDirty||splitDirty||reading.evidenceStatus!=='bound'||workingReview(head)?.sourceStale" data-review-select-edition @click="useSelectedEdition">{{ t('将此版设为本次起草版本', 'Select this edition for drafting') }}</button>
    </div>
    <p v-if="(head.draft ?? head.current)?.sourceStale" class="review-error" data-source-review-stale role="status">{{ t('此核对版本引用了旧来源证据。请返回“识别”并按现有原件新建核对版本；保存的历史记录保留。', 'This review references old source evidence. Return to Identify and start a new review from current sources. Saved history is retained.') }}</p>
    <p v-if="selectedSource" class="review-status" data-source-evidence-status role="status">{{ sourceEvidenceLabel(selectedSource) }}</p>
    <div v-if="invalidTemplateSources.length" class="review-error" data-source-evidence-block role="alert"><p>{{ t('这些模板没有当前有效的来源证据；原件未变化也可能是分析代次已过期。请先替换或重新上传，再建立并核对新版本。', 'These templates lack current bound evidence. An unchanged original can still have an expired analysis revision. Replace or re-upload them, then prepare and review a new version.') }}</p><ul><li v-for="source in invalidTemplateSources" :key="source.id">{{ source.fileKey }} · {{ source.fileName }} · {{ sourceEvidenceLabel(source) }}</li></ul><button type="button" class="btn" data-source-replace @click="emit('replace')">{{ t('到来源步骤替换／重传', 'Replace or re-upload in Sources') }}</button></div>
    <p v-if="selectedSource?.format === 'pdf'" class="source-note" data-source-pdf-limit>{{ t('这份 PDF 可阅读和核对来源；生成保留原格式的 Word 需要所选版次的可编辑 DOCX，不能借另一版 Word。', 'This PDF supports source reading and review. Word generation needs an editable DOCX of the selected edition; another Word edition cannot be substituted.') }}</p>
    <DraftingPdfSccReference v-if="selectedSource?.fileKey === 'SCC' && selectedSource.format === 'pdf' && workingReview(head)" :project-id="projectId" :revision-id="workingReview(head)!.id" :source="selectedSource" :variables="state.variables" :locale="locale" :disabled="busy || locked || dirty || formDirty || splitDirty || workingReview(head)!.sourceStale" @question="locateQuestion" @align="alignPdfReference" @unalign="unalignPdfReference" />
    <p v-if="state.discoverySummary" class="review-status">{{ t('本次模板识别', 'This template discovery run') }} · {{ t('已尝试', 'Attempted') }} {{ state.discoverySummary.attempted }} · {{ t('成功返回', 'Returned') }} {{ state.discoverySummary.succeeded }} · {{ t('失败', 'Failed') }} {{ state.discoverySummary.failed }} · {{ t('预算外未执行', 'Outside budget') }} {{ state.discoverySummary.skippedBudget }}. {{ t('再次点击只识别尚未尝试的要求；失败记录保留，不会自动重试。', 'A later run processes unattempted requirements. Failures remain recorded and are not retried automatically.') }}</p>
    <details v-if="unassignedRequirements.length" class="source-note" data-unassigned-requirements><summary>{{ t('尚未关联变量的来源范围', 'Source ranges not yet linked to a variable') }} · {{ unassignedRequirements.length }}</summary><p>{{ t('这些范围尚未归属；可定位原件并人工补充变量或目标，不会自动建立关联。', 'These ranges are unassigned. Locate the source and add a variable or target manually; no association is made automatically.') }}</p><article v-for="requirement in unassignedRequirements.slice(0,unassignedLimit)" :key="requirement.id"><blockquote>{{ requirementScope(requirement)?.text || requirement.sourceQuote }}</blockquote><p v-if="!requirementScope(requirement)">{{ t('历史范围证据缺失或不一致，请重新识别或手动核对。', 'Historical scope evidence is missing or inconsistent. Rediscover or review manually.') }}</p><button type="button" class="btn" :data-requirement-locate="requirement.id" :disabled="busy || formDirty || splitDirty" @click="locateRequirementScope(requirement)">{{ t('定位并核对原文', 'Locate and review source') }}</button></article><button v-if="unassignedLimit < unassignedRequirements.length" type="button" class="btn" @click="unassignedLimit+=20">{{ t('显示更多', 'Show more') }}</button></details>
    <details v-if="unavailableRequirements.length" class="source-note"><summary>{{ t('原件不可用的未归属历史范围', 'Unassigned historical ranges with unavailable sources') }} · {{ unavailableRequirements.length }}</summary><p>{{ t('原件不在图谱中或暂时无法读取；仅保留历史引文，不能映射到其他版本。', 'The source is absent from this graph or could not be read. Historical quotations are retained and cannot be mapped to another edition.') }}</p><article v-for="requirement in unavailableRequirements.slice(0,unassignedLimit)" :key="requirement.id"><p>{{ requirement.sourceId }}</p><blockquote>{{ requirement.sourceQuote }}</blockquote></article><button v-if="unassignedLimit < unavailableRequirements.length" type="button" class="btn" @click="unassignedLimit+=20">{{ t('显示更多', 'Show more') }}</button></details>
    <p v-if="sourceLoading" role="status">{{ t('正在读取所选原件与核对位置…', 'Reading the selected source and review positions…') }}</p>
    <div v-if="reading || immersive || state.variables.length" :style="immersive ? { height: workspaceHeight + 'px' } : undefined">
    <section ref="workspace" class="source-workspace" :class="{ 'source-workspace--immersive': immersive }" data-source-workspace :role="immersive ? 'dialog' : undefined" :aria-modal="immersive || undefined" :aria-label="t('原件核对工作区', 'Original document review')" tabindex="-1">
      <header class="source-workspace-toolbar"><strong>{{ reading?.fileName || selectedSource?.fileName || t('变量审核','Variable review') }}</strong><button ref="immersiveToggle" type="button" class="btn" data-source-immersive :aria-expanded="immersive" @click="setImmersive(!immersive)">{{ immersive ? t('退出沉浸式预览（Esc）', 'Exit immersive preview (Esc)') : t('沉浸式预览', 'Immersive preview') }}</button></header>
      <p v-if="immersive && error" class="review-error" role="alert">{{ error }}</p>
      <p v-if="immersive && busy" role="status">{{ t('正在读取或保存…', 'Reading or saving…') }}</p>
      <p v-if="immersive && sourceLoading" role="status">{{ t('正在读取所选原件与核对位置…', 'Reading the selected source and review positions…') }}</p>
    <div class="review-columns" :class="{'review-columns--variables-only':!reading}">
      <section v-if="reading" class="original-source" data-source-page-review>
        <nav v-if="reading.format==='docx'" class="review-actions" :aria-label="t('原件阅读方式', 'Source reading view')"><button type="button" class="btn" data-source-view-pdf :aria-pressed="sourceMode==='pdf'" @click="selectSourceMode('pdf')">PDF</button><button type="button" class="btn" data-source-view-word :aria-pressed="sourceMode==='word'" @click="selectSourceMode('word')">Word</button></nav>
        <p v-if="locationNotice && sourceMode==='pdf'" class="source-note location-notice" data-review-location-notice role="status">{{ locationNotice }}</p>
        <button v-if="sourceMode==='pdf'&&reading.format==='docx'&&pdfUrl&&!originalLoading&&!sourceBindings" type="button" class="btn" @click="openOriginal">{{ t('重新读取原件定位', 'Reload source locations') }}</button>
        <p v-if="sourceMode==='pdf'" class="source-note">{{ t('在原版页面上框选文字；点击可补选或取消单词。选词后补充／关联变量，或指定修改范围。', 'Select words on the original page. Click words to refine the selection, then add/link a variable or identify the affected passage.') }}</p>
        <section v-if="reading.issues?.length||reading.markerCandidates?.some(marker=>marker.disposition==='unknown')" data-source-unresolved class="source-note"><strong>{{ t('原件仍有待核对内容', 'Source requires review') }}</strong><p>{{ t('未知指导范围和修改符号不能直接授权自动修改，请结合当前变量核对。', 'Unknown instruction and marker scopes cannot yet authorize automatic edits. Review them with the current variable.') }}</p><p v-for="(issue,index) in reading.issues.slice(0,2)" :key="index">{{ sourceIssueMessage(issue) }}</p><blockquote v-for="marker in reading.markerCandidates.filter(marker=>marker.disposition==='unknown').slice(0,2)" :key="marker.id">{{ sourceExcerpt(marker.localSentenceObservation?.text||marker.symbol) }}</blockquote><p v-if="reading.issues.length>2">{{ t('其余事项请结合当前变量及关联原文核对。', 'Review remaining items with the current variable and its source.') }}</p></section>
        <p v-if="originalLoading && sourceMode==='pdf'" role="status">{{ t('正在打开原版文件…', 'Opening the original document…') }}</p>
        <p v-if="originalError && sourceMode==='pdf'" role="alert" class="review-error">{{ originalError }}</p>
        <button v-if="!pdfUrl&&!originalLoading&&sourceMode==='pdf'" class="btn" data-review-original @click="openOriginal">{{ t('重新打开原件', 'Retry original document') }}</button>
        <nav v-if="sourceMode==='pdf'&&sourceLocations.length>1" class="review-actions source-note" :aria-label="t('关联原文位置', 'Linked source positions')"><button type="button" class="btn" :disabled="locationIndex<=0" @click="moveLocation(-1)">{{ t('上一处', 'Previous passage') }}</button><span>{{ t('关联原文', 'Linked source') }} {{ locationIndex+1 }} / {{ sourceLocations.length }} · {{ t('第', 'Page') }} {{ sourceLocations[locationIndex]?.geometry.pageNumber }} {{ t('页', '') }}</span><button type="button" class="btn" :disabled="locationIndex>=sourceLocations.length-1" @click="moveLocation(1)">{{ t('下一处', 'Next passage') }}</button></nav>
        <DraftingPdfPreview v-if="pdfUrl" v-show="sourceMode==='pdf'" ref="pdfSource" :source="pdfUrl" :title="reading.fileName" :locale="locale as any" :initial-page="initialPdfPage" :immersive="immersive" :select-words="true" :focus-text="focusText" :focus-page="focusPage" :focus-request="focusRequest" :locations="sourceLocations" :selected-binding-id="selectedBindingId" :location-request="focusRequest" @selection="capturePage" @focus-unavailable="locationUnavailable" @location-unavailable="locationNotice=t('此位置未能在当前预览中定位，原件仍可浏览；请重新读取原件。', 'This position is unavailable in the current preview. You can still browse; reopen the original to retry.')">
          <template #selection="{clear}"><p v-if="selectionError" class="review-error" role="alert">{{ selectionError }}</p><template v-if="selection"><blockquote>{{ selection.text }}</blockquote><template v-if="reading.format==='docx'&&!selection.native"><p class="source-note" data-selection-pending role="status">{{ t('选词已保留，可补充变量、关联来源或记录待核对的修改范围。自动定位未完成，不会据此改写文件。', 'Selection retained. Add/link a variable or record a passage for review. The document will not be edited from an unbound selection.') }}</p><template v-if="manualParagraphs.length"><label>{{ t('选择对应的 Word 原段（当前页）', 'Choose the corresponding Word paragraph on this page') }}<select v-model="manualParagraphId" data-manual-source-paragraph><option value="">{{ t('请选择并核对下方完整原文', 'Choose and review the full source below') }}</option><option v-for="paragraph in manualParagraphs" :key="paragraph.id" :value="paragraph.id">{{ paragraph.text.slice(0,120) }}</option></select></label><blockquote v-if="manualParagraph">{{ manualParagraph.text }}</blockquote><button type="button" class="btn" data-use-whole-source-paragraph :disabled="!manualParagraph" @click="useWholeParagraph">{{ t('确认改用这整段原文', 'Use this entire source paragraph') }}</button></template></template><div class="review-actions"><button class="btn" :disabled="!ruleSource||formDirty||splitDirty" @click="addQuestion">{{ t('补充变量', 'Add a variable') }}</button><button class="btn" :disabled="!selected||!ruleSource||formDirty||splitDirty" @click="linkSource">{{ t('关联已有变量', 'Link a variable') }}</button><button class="btn" :disabled="!selected||!ruleSource" @click="addTarget">{{ t('作为修改范围', 'Use as affected passage') }}</button></div></template><button class="btn" @click="clear">{{ t('清除选词', 'Clear selection') }}</button></template>
        </DraftingPdfPreview>
        <DraftingWordSource v-if="reading.format==='docx'&&wordOpened" v-show="sourceMode==='word'" ref="wordSource" :project-id="projectId" :reading="reading" :locale="locale" :locations="locationParagraphs" :spans="locationSpans" :location-request="focusRequest" @selection="captureWord" @error="selectionError=$event" />
        <section v-if="sourceMode==='word'" class="source-note" data-word-selection><p v-if="selectionError" class="review-error" role="alert">{{ selectionError }}</p><template v-if="selection"><blockquote>{{ selection.text }}</blockquote><div class="review-actions"><button class="btn" data-word-add-variable :disabled="!ruleSource||formDirty||splitDirty" @click="addQuestion">{{ t('补充变量', 'Add a variable') }}</button><button class="btn" data-word-link-variable :disabled="!selected||!ruleSource||formDirty||splitDirty" @click="linkSource">{{ t('关联已有变量', 'Link a variable') }}</button><button class="btn" data-word-add-target :disabled="!selected||!ruleSource" @click="addTarget">{{ t('作为修改范围', 'Use as affected passage') }}</button></div><button class="btn" data-word-clear-selection @click="wordSource?.clear()">{{ t('清除选词', 'Clear selection') }}</button></template></section>
        <p v-if="reading.format!=='docx'" class="source-note">{{ t('此处显示原PDF，可关联变量；不能借另一版Word执行修改。', 'This is the original PDF. Link variables for review; another Word edition does not authorize editing it.') }}</p>
      </section>
      <aside class="review-editor" :class="{'question-open':selected}" data-source-question-drawer>
        <section class="batch-selection" data-review-selection><p>{{ checkedQuestions.length }} {{ t('项已勾选（跨文件保留）', 'items selected (kept across files)') }}</p><p v-if="checkedQuestions.length">{{ checkedQuestions.map(questionLabel).join('；') }}</p><div class="review-actions"><button class="btn" data-review-open-batch :disabled="busy||formDirty||splitDirty||!checkedQuestions.length" @click="openBatch()">{{ t('查看勾选项与处理方式', 'Review selected items and treatment') }}</button><button class="btn" :disabled="!checkedIds.length" @click="checkedIds=[];batchDraft=undefined">{{ t('清空勾选', 'Clear selection') }}</button><button v-if="selected" class="btn" data-review-check-current :aria-pressed="checkedIds.includes(selected.id)" @click="toggleChecked(selected.id)">{{ checkedIds.includes(selected.id)?t('取消勾选当前项', 'Deselect this item'):t('勾选当前项', 'Select this item') }}</button></div></section>
        <template v-if="!selected"><h4>{{ t('核对变量', 'Review variables') }}</h4><input v-model="search" class="question-search" :placeholder="t('搜索变量', 'Search variables')"><nav class="question-tabs"><button class="btn" data-review-tab-business :aria-pressed="questionKind==='business'" @click="questionKind='business'">{{ t('业务变量', 'Business variables') }}</button><button class="btn" data-review-tab-rules :aria-pressed="questionKind==='rules'" @click="questionKind='rules'">{{ t('处理规则／派生项', 'Treatment rules / derived items') }}</button><button class="btn" data-review-tab-checks :aria-pressed="questionKind==='checks'" @click="questionKind='checks'">{{ t('原文待核', 'Source checks') }}</button></nav><p v-if="!questionRows.length">{{ t('此原件暂无匹配变量，可在页面上选词补充。', 'No matching variables for this source. Select words to add one.') }}</p><div v-for="item in shownQuestions" :key="item.id" class="question-select-row"><label><input type="checkbox" :checked="checkedIds.includes(item.id)" :data-review-check="item.id" :aria-label="t('勾选：', 'Select: ')+questionLabel(item)" @change="toggleChecked(item.id)"></label><button class="question-card" :data-review-question="item.id" @click="choose(item.id)"><strong>{{ questionLabel(item) }}</strong><small>{{ statusLabel(item.status) }}</small></button></div><nav v-if="questionRows.length>8" class="review-actions"><button class="btn" :disabled="questionPage===0" @click="questionPage--">{{ t('上一组', 'Previous') }}</button><span>{{ questionPage+1 }} / {{ Math.ceil(questionRows.length/8) }}</span><button class="btn" :disabled="(questionPage+1)*8>=questionRows.length" @click="questionPage++">{{ t('下一组', 'Next') }}</button></nav></template>
        <div v-if="selected" class="question-summary"><button class="btn" :disabled="formDirty||splitDirty" @click="selectedId='';formOpen=false;editingQuestion=false">{{ t('返回变量列表', 'Back to variables') }}</button><h4>{{ questionLabel(selected) }}</h4><p>{{ statusLabel(selected.status) }}</p><p v-if="selected.definitionKind === 'source_seed' || isReviewCheck(selected)" class="source-note" data-review-source-check-detail>{{ sourceCheckDetail(selected) }}</p><p>{{ t('关联原文', 'Linked source passages') }} · {{ selected.sourceAnchors.length }}</p><blockquote v-for="(anchor,index) in selected.sourceAnchors" :key="index">{{ anchor.text }}<button class="btn" data-review-locate-source :disabled="busy||formDirty||splitDirty" @click="locateAnchor(anchor)">{{ t('定位这处原文', 'Locate this passage') }} {{ index+1 }}</button></blockquote><div v-if="!editingQuestion" class="review-actions"><button class="btn primary" data-review-confirm-item :disabled="formDirty" @click="reviewItem">{{ t('该项已核对', 'Mark reviewed') }}</button><button class="btn" data-review-edit-question @click="editingQuestion=true">{{ t('调整变量与影响范围', 'Edit variable and affected passages') }}</button></div><p v-if="selectedProblem&&!editingQuestion" class="review-error">{{ selectedProblem }}</p></div>
        <section v-if="selected && selected.status !== 'excluded'" class="source-note" data-definition-review>
          <p v-if="selectedUsesUnverifiedScc">{{ t('此项涉及尚未核实与固定目录同版的 SCC。固定目录可作为历史对照，关系和范围须按当前原件核对；版次不由 Word 或 PDF 格式决定。', 'This item involves an SCC whose edition has not been verified against the fixed catalogue. The catalogue is a historical reference; review relationships and ranges against this original. Word or PDF format does not determine the edition.') }}</p>
          <p v-if="selected.editorialSuggestion">{{ t('识别建议（待人工核对）：', 'Classification suggestion (requires review): ') }}{{ selected.editorialSuggestion.kind }} · {{ selected.editorialSuggestion.reason }} <q>{{ selected.editorialSuggestion.sourceQuote }}</q></p>
          <p>{{ t('先核对变量的含义、类型、选项及固定变量匹配，再确认定义并保存草稿；修改范围可以继续单独核对。更改定义后需重新确认。', 'Review the meaning, type, options and baseline match, then confirm the definition and save the draft. Affected passages can be reviewed separately. Confirm again after changing the definition.') }}</p>
          <p v-if="selected.definitionReview?.confirmed">{{ selected.definitionReview.basis ? t('已保存定义确认；保存后若定义发生变化，提取时会要求重新确认。', 'Definition confirmation was saved; changed definitions require confirmation again before extraction.') : t('定义确认已记录，请保存核对草稿。', 'Definition confirmation is recorded; save the review draft.') }}</p>
          <div v-if="(selected.editorialSuggestion || selected.editorialClassificationHistory?.some((item: ReviewObject) => !item.restored)) && selected.definitionKind !== 'source_seed'" class="review-actions"><button type="button" class="btn" :disabled="busy || locked || formDirty || splitDirty" @click="classifyEditorial(true)">{{ t('这是业务变量', 'This is a business variable') }}</button><button type="button" class="btn" :disabled="busy || locked || formDirty || splitDirty" @click="classifyEditorial(false)">{{ t('这是指导或修改事项', 'This is a guidance or editing item') }}</button></div>
          <button v-if="!isReviewCheck(selected) && ['input','decision','content'].includes(selected.role?.kind)" type="button" class="btn" data-review-confirm-definition :disabled="busy || locked || formDirty || splitDirty || pendingEditorial(selected)" @click="confirmDefinition">{{ t('确认此变量定义', 'Confirm this variable definition') }}</button>
          <p v-else-if="isReviewCheck(selected)">{{ t('该项是人工核查事项，不作为条件或值来源；可在“调整变量与影响范围”中改正类型。', 'This is a review check, not a condition or value source. Correct its role under Edit variable and affected passages.') }}</p>
          <p v-else-if="selected.role?.kind === 'derived'">{{ t('该项由其他变量的取值和分支规则计算，无需单独填写；请在“调整变量与影响范围”中核对规则和修改范围。', 'This value is calculated from other variable values and branch rules. No separate answer is needed; review the rules and affected passages under Edit variable and affected passages.') }}</p>
          <p v-else>{{ t('该项无需单独填写；请在“调整变量与影响范围”中核对其值来源和修改范围。', 'This item needs no separate answer. Review its value source and affected passages under Edit variable and affected passages.') }}</p>
        </section>
        <section v-if="selectedRequirements.length" class="source-note" data-review-observed-scopes><h4>{{ t('关联的来源范围候选', 'Associated source scope candidates') }}</h4><p>{{ t('以下是同源观察，仍需在原件上定位并人工确认；不会自动变成修改目标。', 'These are observations from the same source. Locate and review them on the original; they are not automatically amendment targets.') }}</p><article v-for="requirement in selectedRequirements" :key="requirement.id"><p>{{ requirement.sourceId }} · {{ requirement.sourceQuoteEvidence?.sourceObservationId }}</p><blockquote>{{ requirementScope(requirement)?.text || requirement.sourceQuote }}</blockquote><p v-if="!requirementScope(requirement)">{{ t('历史范围证据缺失或不一致，请重新识别或手动核对。', 'Historical scope evidence is missing or inconsistent. Rediscover or review manually.') }}</p><button type="button" class="btn" :data-requirement-locate="requirement.id" :disabled="busy || formDirty || splitDirty" @click="locateRequirementScope(requirement)">{{ t('定位并核对原文', 'Locate and review source') }}</button></article></section>
        <details v-if="selected?.editorialReferenceHistory?.length" class="source-note"><summary>{{ t('查看分类前的条件和目标记录', 'View conditions and targets before classification') }}</summary><p>{{ t('这些是历史记录，不会自动重新接线；请按当前需要重新设置条件和目标。', 'These are historical records, not automatically restored. Reconfigure conditions and targets as needed.') }}</p><article v-for="(history,index) in selected.editorialReferenceHistory" :key="index"><p>{{ t('原引用变量', 'Previously referenced variable') }}: {{ state.variables.find(item => item.id===history.variableId)?.nameZh || history.variableId || t('引用来源未知', 'Reference source unknown') }}</p><template v-for="(branch,branchIndex) in historicalBranches(history.correction)" :key="branchIndex"><p>{{ t('条件组合', 'Condition combination') }}: {{ branch.mode }} · {{ t('明细范围', 'Detail scope') }}: {{ branch.scopeId || '—' }} {{ branch.scopeMode || '' }} · {{ t('结果', 'Result') }}: {{ historicalValue(branch.resultValue) }} · {{ branch.effectZh || branch.effectEn }}</p><p v-for="(clause,clauseIndex) in branch.clauses ?? []" :key="clauseIndex">{{ state.variables.find(item => item.id===clause.inputId)?.nameZh || clause.inputId }} · {{ clause.operator }} · {{ historicalValue(clause.value) }}</p></template><p v-if="history.correction?.otherwise">{{ t('其他情况', 'Otherwise') }}: {{ history.correction.otherwise }}</p><p v-if="history.relatedInputIds?.length">{{ t('其他关联变量', 'Other linked variables') }}: {{ history.relatedInputIds.map((id: string) => state.variables.find(item => item.id===id)?.nameZh || id).join('；') }}</p><article v-for="(target,targetIndex) in history.targets ?? []" :key="targetIndex"><blockquote>{{ target.original?.text }}</blockquote><p>{{ t('处理及分支', 'Action and branch') }}: {{ target.original?.action }} · {{ target.original?.branchId || '—' }} · {{ t('值来源', 'Value source') }}: {{ target.original?.valueSourceId || '—' }} · {{ t('明细组／键／值', 'List / key / value') }}: {{ target.original?.listId || '—' }} / {{ target.original?.itemKey || '—' }} / {{ historicalValue(target.original?.itemValue) }} · {{ t('明细标记', 'Item marker') }}: {{ target.original?.itemId || '—' }}</p><p>{{ t('替换文字及前后缀', 'Replacement and surrounding text') }}: {{ target.original?.replacementEn || target.original?.replacementZh || '—' }} · {{ target.original?.prefix || '' }} / {{ target.original?.suffix || '' }} · {{ t('值模式／分隔符', 'Value mode / separator') }}: {{ target.original?.valueMode || '—' }} / {{ target.original?.separator || '' }} · {{ t('替换位置', 'Replacement placement') }}: {{ historicalValue(target.original?.replacementPlacement) }}</p></article></article></details>
        <section v-if="selected?.applicability?.kind === 'fixed_catalog'" class="source-note" data-current-fixed-applicability><h4>{{ t('已记录的变量适用条件', 'Recorded variable applicability') }}</h4><p>{{ t('此条件只决定何时需要该变量的答案，不修改模板内容；原有答案仍保留。', 'This condition controls when the answer is needed and does not modify the template. Existing answers are retained.') }}</p><p>{{ selected.applicability.reason }}</p><p v-for="(clause,index) in selected.applicability.clauses" :key="index">{{ state.variables.find(item => item.id === clause.inputId)?.nameZh || clause.inputId }} · {{ clause.operator }} · {{ JSON.stringify(clause.value) }}</p><button type="button" class="btn" data-fixed-applicability-remove :disabled="busy || locked || formDirty || splitDirty" @click="clearFixedApplicability">{{ t('移除此适用条件，保留答案', 'Remove this condition and keep the answer') }}</button></section>
        <section v-if="selected && !selectedUsesUnverifiedScc && (projectionProposals.length || selected.factProjectionReview || manualProjectionEligible)" class="source-note" data-fact-projection>
          <h4>{{ t('关联共同选择变量', 'Link to a shared choice') }}</h4>
          <p>{{ t('此操作保留本项原文和各自修改范围，将局部是／否从共同选择推导；原有答案不自动转为新答案，目标仍需重新核对。', 'This retains this item’s source and affected passages while deriving its local yes/no from a shared choice. Existing answers are not adopted as the new answer; targets require review again.') }}</p>
          <p v-if="!selected.factProjectionReview && selected.correction">{{ t('现有条件会完整保留为历史并标记待核；确认关联后须逐条核对旧条件与目标分支，不会自动认定已替换。','The existing rule is retained in full as pending history. After linking, review each old condition and target branch; it is not automatically treated as replaced.') }}</p><p v-if="!selected.factProjectionReview && !projectionTargetIdsReady">{{ t('部分修改目标尚无稳定标识，请先保存核对草稿后再关联。','Some amendment targets have no stable identity. Save the review draft before linking.') }}</p>
          <template v-if="!selected.factProjectionReview"><div v-if="projectionProposals.length"><label>{{ t('待核对的固定变量建议', 'Proposed fixed variable') }}<select v-model="projectionKey" data-projection-key @change="projectionRoot='';projectionExhaustive=false"><option value="">{{ t('请选择','Choose') }}</option><option v-for="proposal in projectionProposals" :key="proposal.baselineKey" :value="proposal.baselineKey">{{ proposal.baselineKey }} · {{ proposal.factProjection.branchValue }}</option></select></label><label v-if="projectionProposal">{{ t('已确认的共同选择', 'Confirmed shared choice') }}<select v-model="projectionRoot" data-projection-root><option value="">{{ t('请选择','Choose') }}</option><option v-for="root in projectionRoots" :key="root.id" :value="root.id">{{ questionLabel(root) }}</option></select></label><label v-if="projectionRoot"><input v-model="projectionExhaustive" data-projection-exhaustive type="checkbox">{{ t('我已对照本版原件，确认这两个选项互斥且穷尽，且该局部变量与建议分支的对应方向正确。', 'I checked this edition and confirm these two choices are mutually exclusive and exhaustive, and the suggested branch polarity is correct.') }}</label><button type="button" class="btn" data-projection-confirm :disabled="busy || locked || formDirty || splitDirty || !projectionRoot || !projectionExhaustive || !projectionTargetIdsReady" @click="convertProjection">{{ t('确认关联并重新核对范围', 'Confirm link and review affected passages') }}</button></div>
          <details v-if="manualProjectionEligible" data-manual-projection><summary>{{ t('人工选择共同变量及分支', 'Manually select shared variable and branch') }}</summary><p>{{ t('模型未建议或建议有误时，可按本版原件自行选择；先确认共同变量是两个互斥且穷尽的选项。', 'If the model missed or misstated the link, choose it from this edition. First confirm the shared variable has two mutually exclusive, exhaustive choices.') }}</p><p v-if="!manualProjectionRoots.length">{{ t('请先新增或核对一个已匹配固定定义的二选变量，并保存其定义。', 'First add or review a two-choice variable matched to a fixed definition and save it.') }}</p><template v-else><label>{{ t('共同选择变量', 'Shared choice variable') }}<select v-model="manualProjectionRoot" data-manual-projection-root><option value="">{{ t('请选择','Choose') }}</option><option v-for="root in manualProjectionRoots" :key="root.id" :value="root.id">{{ questionLabel(root) }}</option></select></label><template v-if="manualProjectionChoice"><label>{{ t('选择哪一个共同选项', 'Which shared option') }}<select v-model="manualProjectionBranch" data-manual-projection-branch><option value="">{{ t('请选择','Choose') }}</option><option v-for="option in manualProjectionChoice.options" :key="option.value" :value="option.value">{{ option.labelZh || option.labelEn || option.value }}</option></select></label><label>{{ t('选中该选项时，本项的答案', 'Local answer when this option is selected') }}<select v-model="manualProjectionPolarity" data-manual-projection-polarity><option value="">{{ t('请选择','Choose') }}</option><option value="true">{{ t('是', 'Yes') }}</option><option value="false">{{ t('否', 'No') }}</option></select></label><label>{{ t('对照原件的核对理由', 'Reason after checking the source') }}<textarea v-model="manualProjectionReason" data-manual-projection-reason rows="2" /></label><label><input v-model="manualProjectionExhaustive" data-manual-projection-exhaustive type="checkbox">{{ t('我已对照本版原件，确认共同选项互斥且穷尽，且所选分支与本项答案对应正确。', 'I checked this edition and confirm the options are mutually exclusive and exhaustive and the selected branch maps to the local answer.') }}</label><button type="button" class="btn" data-manual-projection-confirm :disabled="busy || locked || formDirty || splitDirty || !manualProjectionBranch || !manualProjectionPolarity || !manualProjectionReason.trim() || !manualProjectionExhaustive || !projectionTargetIdsReady" @click="convertManualProjection">{{ t('确认人工关联并重新核对范围', 'Confirm manual link and review affected passages') }}</button></template></template></details></template>
          <template v-else><button type="button" class="btn" data-projection-revert :disabled="busy || locked || formDirty || splitDirty || !canRestoreProjectionTargets" @click="revertProjection">{{ t('撤销共同选择关联', 'Revert shared choice link') }}</button><p v-if="!canRestoreProjectionTargets">{{ t('修改目标已新增或删除，无法自动恢复原分支；请先核对目标身份。','Amendment targets were added or removed, so their previous branches cannot be restored automatically. Review their identities first.') }}</p><button type="button" class="btn" data-projection-read-history :disabled="busy || locked || dirty || formDirty || splitDirty" @click="loadProjectionHistory">{{ t('读取并核对旧答案', 'Review historical answer') }}</button><div v-if="projectionHistory"><p>{{ t('旧答案', 'Historical answer') }}: {{ projectionHistory.oldValue }} · {{ t('已采用的共同选择', 'Adopted shared choice') }}: {{ projectionHistory.rootValue ?? t('未知', 'unknown') }} · {{ t('推导结果', 'Derived value') }}: {{ projectionHistory.derivedValue ?? t('未知', 'unknown') }}</p><p v-if="projectionHistorySources?.sourceStatus === 'manual_unbound'">{{ t('旧答案没有已绑定来源，以下说明未经原件核实。', 'The historical answer has no bound source; this note is not verified against an original.') }} {{ projectionHistorySources.unverifiedSourceRef }}</p><article v-for="source in projectionHistorySources?.sources ?? []" :key="source.sourceDocumentId"><p>{{ source.fileName }} · {{ source.status }}</p><blockquote>{{ source.sourceQuote }}</blockquote></article><label>{{ t('核对理由', 'Review reason') }}<textarea v-model="projectionHistoryReason" data-projection-history-reason rows="2" /></label><button type="button" class="btn" data-projection-resolve-history :disabled="busy || locked || formDirty || splitDirty || projectionHistory.rootValue == null || typeof projectionHistory.oldValue !== 'boolean' || typeof projectionHistory.derivedValue !== 'boolean' || !projectionHistory.evidenceFingerprint || !projectionHistoryReason.trim()" @click="resolveProjectionHistory">{{ t('确认使用当前推导结果', 'Confirm use of current derived value') }}</button></div></template>
        </section>
        <section v-if="selected?.factProjectionReview?.previousRuleResolution" class="source-note" data-projection-previous-rule><h4>{{ t('核对关联前后的条件和范围','Review conditions and ranges before and after linking') }}</h4><p>{{ t('旧规则完整保留。系统只检查引用和环，不判断两套条件语义等价；在人工核对完成前，规则不会执行。','The complete previous rule is retained. The system checks references and cycles but does not determine semantic equivalence. The rule will not execute until human review is complete.') }}</p><details open><summary>{{ t('关联前的完整条件','Complete previous conditions') }}</summary><pre>{{ JSON.stringify(selected.factProjectionReview.original.correction,null,2) }}</pre></details><details open><summary>{{ t('当前完整条件','Complete current conditions') }}</summary><pre>{{ JSON.stringify(selected.correction,null,2) }}</pre></details><article v-for="binding in selected.factProjectionReview.original.targetBindings ?? []" :key="binding.id"><blockquote>{{ selected.editTargets.find((target:ReviewObject)=>target.id===binding.id)?.text || t('目标已移除','Target removed') }}</blockquote><p>{{ t('原分支','Previous branch') }}: {{ binding.branchId || '—' }} · {{ t('当前分支','Current branch') }}: {{ selected.editTargets.find((target:ReviewObject)=>target.id===binding.id)?.branchId || '—' }}</p></article><template v-if="selected.factProjectionReview.previousRuleResolution.status !== 'resolved'"><p v-if="previousProjectionExternal.length">{{ t('旧规则还有其他条件输入。请在“调整变量与影响范围”中逐项重建并保存，然后对照旧规则核对；或撤销关联恢复旧规则。','The previous rule has additional inputs. Rebuild them in Edit variable and affected passages, save, then compare with the previous rule; or revert the link to restore it.') }}</p><label>{{ t('处理方式','Treatment') }}<select v-model="previousProjectionDecision" data-projection-previous-decision><option value="">{{ t('请选择','Choose') }}</option><option value="replace" :disabled="!canReplacePreviousProjection">{{ t('旧规则无其他需保留条件，确认替换','No additional conditions need retaining; replace') }}</option><option value="rebuilt" :disabled="!canConfirmRebuiltProjection">{{ t('已按旧规则人工重建并核对','Rebuilt and manually checked against the old rule') }}</option></select></label><label>{{ t('核对理由','Review reason') }}<textarea v-model="previousProjectionReason" data-projection-previous-reason rows="2" /></label><label><input v-model="previousProjectionChecked" data-projection-previous-checked type="checkbox">{{ t('我已逐条对照旧、新条件的组合、取值、结果及每个目标分支，确认没有遗漏需要保留的内容。','I compared the old and new combinations, values, results and each target branch, and confirmed that no required content was omitted.') }}</label><button type="button" class="btn" data-projection-previous-resolve :disabled="busy || locked || formDirty || splitDirty || !previousProjectionReason.trim() || !previousProjectionChecked || (previousProjectionDecision === 'replace' ? !canReplacePreviousProjection : previousProjectionDecision === 'rebuilt' ? !canConfirmRebuiltProjection : true)" @click="resolvePreviousProjection">{{ t('确认条件处理，待保存','Confirm condition treatment and save later') }}</button></template><p v-else>{{ t('已记录人工条件核对；修改范围仍需逐项确认。','Human condition review is recorded; each amendment range still needs confirmation.') }} {{ selected.factProjectionReview.previousRuleResolution.reason }}</p></section>
        <section v-if="selected?.factProjectionReview && editingQuestion && form.targets.some((target: ReviewObject) => target.needsRebind)" class="source-note"><p>{{ t('为每个修改范围选择适用分支并核对位置，然后逐项确认；记录变量调整并保存草稿。', 'Choose the applicable branch and check each affected passage, then confirm each one. Record changes and save the draft.') }}</p><article v-for="(target,index) in form.targets" :key="index"><template v-if="target.needsRebind"><blockquote>{{ target.text }}</blockquote><button type="button" class="btn" :disabled="busy || !target.branchId || target.nativeBinding?.status !== 'bound'" @click="target.needsRebind=false">{{ t('已核对本范围和适用分支', 'Reviewed this passage and its branch') }}</button></template></article></section>
        <section v-if="selected && editingQuestion && form.targets.some((target: ReviewObject) => target.refinementCandidate?.status === 'pending')" class="source-note" data-review-refinements><p>{{ t('以下是模型提出的局部范围，先对照原件；采用后仍须选择处理方式并保存，完整原范围会保留。', 'These are suggested subranges. Check them against the original; after selecting one, choose its treatment and save. The complete observed scope is retained.') }}</p><article v-for="(target,index) in form.targets" :key="index"><template v-if="target.refinementCandidate?.status === 'pending'"><blockquote>{{ target.refinementCandidate.text }}</blockquote><button type="button" class="btn" :disabled="busy" @click="acceptRefinement(index)">{{ t('采用这段作为待核范围', 'Use this subrange for review') }}</button></template></article></section>
        <div v-if="isolatedRule && formOpen" class="source-note"><p>{{ t('变量及原文已保留，无法确认的规则已隔离。可移除此空规则，保留变量供人工核对；不会增加修改目标或采用变量值。', 'The variable and source are retained; the unresolved rule is isolated. Remove this empty rule to review the variable manually. This adds no amendment targets or adopted project answers.') }}</p><button class="btn" data-review-remove-isolated-rule :disabled="busy || formDirty" @click="removeIsolatedRule">{{ t('移除隔离规则，保留变量', 'Remove isolated rule and retain variable') }}</button></div>
        <template v-if="selected && formOpen && editingQuestion"><p v-if="selectedProblem && !formDirty" class="review-error" data-review-item-problem>{{ selectedProblem }}</p><div class="review-actions"><button class="btn" data-review-confirm-item :disabled="formDirty" @click="reviewItem">{{ t('该项已核对', 'Mark this item reviewed') }}</button><button class="btn" :disabled="formDirty" @click="splitOpen = true">{{ t('拆为两个变量', 'Split into two variables') }}</button><button class="btn" :disabled="formDirty" @click="apply({type: selected.status === 'excluded' ? 'RESTORE' : 'EXCLUDE', id: selectedId, reason: '人工核对 / Human review'}); resetForm()">{{ selected.status === 'excluded' ? t('恢复', 'Restore') : t('不再提问', 'Exclude') }}</button></div><label v-if="selectedSplitReview">{{ t('拆分后的关系', 'Relationship after splitting') }}<select v-model="form.splitMode" data-review-split-mode><option value="unknown">{{ t('待核对', 'Needs review') }}</option><option value="all">{{ t('两项同时成立', 'Both hold') }}</option><option value="any">{{ t('任一项成立', 'Either holds') }}</option></select></label><label>{{ t('中文变量', 'Chinese variable') }}<input v-model="form.nameZh" data-review-name-zh></label><label>{{ t('英文变量', 'English variable') }}<input v-model="form.nameEn" data-review-name-en></label><label>{{ t('值类型', 'Value type') }}<select v-model="form.kind"><option v-for="(label, key) in kinds" :key="key" :value="key">{{ label }}</option></select></label><label>{{ t('值来源', 'Value source') }}<select v-model="form.role" data-review-role><option v-for="(label, key) in roles" :key="key" :value="key" :disabled="selected.definitionKind === 'source_seed' && key !== 'verification'">{{ label }}</option></select></label><label>{{ t('属于哪条资料／明细？', 'Which record or list owns it?') }}<input v-model="optionQueries.parent" type="search" data-review-parent-search :placeholder="t('搜索资料／明细组', 'Search records or lists')"><select v-model="form.parentId" data-review-parent><option value="">{{ t('项目变量', 'Project level') }}</option><option v-if="form.parentId && !parents.some(item => item.id === form.parentId)" :value="form.parentId" disabled>{{ conditionReferenceLabel(form.parentId) }} · {{ t('原引用需核对', 'Retained reference needs review') }}</option><option v-for="item in searchedOptions(parents,'parent',form.parentId)" :key="item.id" :value="item.id">{{ questionLabel(item) }}</option></select></label><p v-if="conditionInputProblem(form.parentId)" class="review-error" role="alert" data-parent-reference-problem>{{ conditionInputProblem(form.parentId) }}</p><label v-if="['choice','multiselect'].includes(form.kind)">{{ t('选项：每行稳定值 | 中文 | English', 'Options: stable value | Chinese | English, one per line') }}<textarea v-model="form.optionsText" rows="4" data-review-options /></label><label v-if="form.role === 'fixed'">{{ t('固定内容', 'Fixed wording') }}<textarea v-model="form.fixedValue" rows="3" /></label>
          <label v-if="form.kind === 'number'">{{ t('单位', 'Unit') }}<input v-model="form.unit" data-review-unit /></label><label><input v-model="form.optional" type="checkbox" data-review-optional />{{ t('允许不提供变量值（可选变量）', 'Optional variable value') }}</label><label>{{ t('变量适用范围', 'Variable scope') }}<input v-model="form.scope" data-review-definition-scope /></label><label v-if="selected.definitionOrigin?.kind === 'manual' || selected.missingSource">{{ t('人工定义理由', 'Manual definition reason') }}<textarea v-model="form.manualReason" data-review-manual-reason /></label><section class="question-section"><h4>{{ t('按哪些答案决定处理或计算结果？', 'Answers controlling amendments or derived results') }}</h4>
            <p class="source-note" data-condition-scope-help>{{ t('“按哪组明细逐项判断”只选择重复填写的明细组。下方可选择本变量答案决定怎样修改原文；计算派生答案时须引用其他变量，不能依靠自身取值。', 'Choose a repeated list for row-by-row evaluation. An independent variable can use its own answer to control source amendments. A derived answer must use other variables instead of calculating itself.') }}</p>
            <p v-if="conditionSourceChecks" class="source-note" data-condition-source-help>{{ conditionSourceChecks }} {{ t('项来源核对记录保留了指导原文／修改标记，尚未建立可供条件判断的独立答案，因此不列为条件变量。请在“原文待核”查看具体来源，核对后明确建立变量；仅改标题不会自动合并或补出答案。', 'source-check records retain instructions or markers without independent answers for condition evaluation. Review their passages under “Source checks” and explicitly create an answerable variable when needed. Renaming does not merge records or create an answer.') }}</p>
            <article v-for="(branch, branchIndex) in form.branches" :key="branch.id" class="condition-case">
              <label>{{ t('本情况', 'Case') }} {{ branchIndex + 1 }}<select v-model="branch.mode"><option value="all">{{ t('同时满足', 'All conditions') }}</option><option value="any">{{ t('任一满足', 'Any condition') }}</option></select></label>
              <DraftingQuestionPicker v-model="branch.scopeId" :query="optionQueries['scope:'+branch.id] ?? ''" @update:query="optionQueries['scope:'+branch.id]=$event" :items="pickerOptions(conditionLists)" :locale="locale" :label="t('按哪组明细逐项判断？', 'Evaluate within which repeated list?')" :empty-label="t('整个项目（不逐项）', 'Whole project (no rows)')" search-attribute="data-condition-scope-search" :context="branch.id" :retained-label="conditionReferenceLabel(branch.scopeId)" :data-condition-scope="branch.id" @change="branch.scopeMode = branch.scopeId ? 'each' : ''" /><p v-if="conditionInputProblem(branch.scopeId)" class="review-error" role="alert" :data-condition-scope-reference-problem="branch.id">{{ conditionInputProblem(branch.scopeId) }}</p>
              <select v-if="branch.scopeId" v-model="branch.scopeMode"><option value="each">{{ t('逐项分别判断', 'Evaluate each row') }}</option><option value="any">{{ t('任一条满足', 'Any row matches') }}</option><option value="all">{{ t('所有条均满足', 'All rows match') }}</option><option value="none">{{ t('没有条目满足', 'No rows match') }}</option></select>
              <div v-for="(clause, index) in branch.clauses" :key="index" class="condition-row">
                <DraftingQuestionPicker v-model="clause.inputId" class="condition-input-choice" :query="optionQueries['input:'+branch.id+':'+index] ?? ''" @update:query="optionQueries['input:'+branch.id+':'+index]=$event" :items="pickerOptions(conditionInputs)" :locale="locale" :label="t('选择变量', 'Choose variable')" :empty-label="t('请选择变量', 'Choose an answerable variable')" search-attribute="data-condition-input-search" :context="branch.id+':'+index" :retained-label="conditionReferenceLabel(clause.inputId)" :data-condition-input="branch.id+':'+index" @change="clause.operator = allowedOperators(clause.inputId)[0]; clause.value = ''" />
                <select v-model="clause.operator"><option v-for="operator in allowedOperators(clause.inputId)" :key="operator" :value="operator">{{ operators[operator] }}</option></select><select v-if="optionInput(clause.inputId).length" v-model="clause.value"><option v-for="option in optionInput(clause.inputId)" :key="option.value" :value="option.value">{{ option.labelZh }} / {{ option.labelEn }}</option></select><input v-else-if="['eq','ge','le','gt','lt'].includes(clause.operator)" v-model="clause.value"><button class="btn" :data-condition-remove="branch.id+':'+index" @click="removeConditionClause(branch,index)">×</button>
                <p v-if="conditionInputProblem(clause.inputId)" class="review-error condition-help" role="alert" :data-condition-reference-problem="branch.id+':'+index">{{ conditionInputProblem(clause.inputId) }}</p>
                <p v-if="optionQueries['input:'+branch.id+':'+index] && !hasOptionMatch(conditionInputs,'input:'+branch.id+':'+index)" class="source-note condition-help" :data-condition-input-help="branch.id+':'+index">{{ t('没有匹配的变量。可清空搜索或新增变量；这里只搜索现有图谱，不从标题猜测或自动拆分变量。', 'No matching answerable variable. Clear the search or create an independent project fact. Search only uses the current graph; titles do not create or split variables.') }}</p>
                <button type="button" class="btn" :data-condition-new-input="branch.id+':'+index" @click="openNewConditionInput(branch.id,index)">{{ t('缺少变量？新增变量', 'Missing input? Create an independent project fact') }}</button>
                <DraftingNewVariable v-if="newConditionInput.branchId === branch.id && newConditionInput.clauseIndex === index" :draft="newConditionInput" :parents="parents" :locale="locale" @create="createConditionInput" @cancel="closeNewConditionInput" />
              </div>
              <button class="btn" @click="branch.clauses.push({inputId:'',operator:'known',value:''})">{{ t('增加条件', 'Add condition') }}</button><label>{{ t('这种情况怎样处理？（中文）', 'Amendment for this case (Chinese)') }}<textarea v-model="branch.effectZh" /></label><label>{{ t('同一处理说明（英文）', 'Amendment for this case (English)') }}<textarea v-model="branch.effectEn" /></label><label v-if="form.role === 'derived'">{{ t('本情况的答案', 'Answer for this case') }}<select v-if="form.kind === 'boolean'" v-model="branch.resultValue"><option :value="true">{{ t('是', 'Yes') }}</option><option :value="false">{{ t('否', 'No') }}</option></select><select v-else-if="form.kind === 'choice'" v-model="branch.resultValue"><option v-for="option in options()" :key="option.value" :value="option.value">{{ option.labelZh }} / {{ option.labelEn }}</option></select><input v-else v-model="branch.resultValue"></label><button class="btn" :data-condition-remove-case="branch.id" @click="removeConditionBranch(branchIndex)">{{ t('移除此情况', 'Remove case') }}</button>
            </article><button class="btn" @click="addBranch">{{ t('增加一种情况', 'Add a case') }}</button>
          </section>
          <section class="question-section"><h4>{{ t('改哪段、怎样改？', 'Which passage changes, and how?') }}</h4><article v-for="(target, index) in form.targets" :key="index" class="target-row"><blockquote>{{ target.text }}</blockquote><button type="button" class="btn" :data-review-locate-target="index" :disabled="busy" @click="locateTarget(target)">{{ t('定位修改范围', 'Locate affected passage') }}</button><p v-if="target.nativeBinding && target.nativeBinding.status !== 'bound'" class="review-error">{{ targetBindingMessage(target) }}</p><select :value="target.action" :data-target-action="index" @change="setTargetAction(target, ($event.target as HTMLSelectElement).value)"><option v-for="(label, action) in actions" :key="action" :value="action" :disabled="action === 'insert' && !(targetSpans(target).length === 1 && targetSpans(target)[0]?.operation === 'insert')">{{ label }}</option></select><label>{{ t('适用于哪种情况？', 'Which case applies?') }}<select v-model="target.branchId"><option value="">{{ t('无条件', 'Unconditional') }}</option><option v-for="(branch, i) in form.branches" :key="branch.id" :value="branch.id">{{ i + 1 }} · {{ branch.effectZh }}</option></select></label><fieldset v-if="targetScope(target)" class="item-target"><legend>{{ t('这段原文对应哪一条明细？', 'Which list item does this source passage belong to?') }}</legend><label>{{ t('明细标记（若已知）', 'Item marker (if known)') }}<input v-model="target.itemId" placeholder="Item marker"></label><template v-if="!target.itemId"><label>{{ t('或按这项资料寻找同一条明细', 'Or match this detail within the same item') }}<input v-model="optionQueries['item-key:'+index]" type="search" :placeholder="t('搜索识别资料', 'Search identifying details')"><select v-model="target.itemKey" :data-target-item-key="index"><option value="">{{ t('选择识别资料', 'Choose identifying detail') }}</option><option v-if="target.itemKey && !itemKeys(target).some(item => item.id === target.itemKey)" :value="target.itemKey" disabled>{{ conditionReferenceLabel(target.itemKey) }} · {{ t('原引用需核对', 'Retained reference needs review') }}</option><option v-for="key in searchedOptions(itemKeys(target),'item-key:'+index,target.itemKey)" :key="key.id" :value="key.id">{{ questionLabel(key) }}</option></select></label><p v-if="conditionInputProblem(target.itemKey)" class="review-error" role="alert" :data-target-item-reference-problem="index">{{ conditionInputProblem(target.itemKey) }}</p><label v-if="target.itemKey">{{ t('这一条明细的准确值', 'Exact value for this item') }}<select v-if="state.variables.find(item => item.id === target.itemKey)?.type.kind === 'boolean'" v-model="target.itemValue"><option :value="true">{{ t('是', 'Yes') }}</option><option :value="false">{{ t('否', 'No') }}</option></select><select v-else-if="optionInput(target.itemKey).length" v-model="target.itemValue"><option v-for="option in optionInput(target.itemKey)" :key="option.value" :value="option.value">{{ option.labelZh }} / {{ option.labelEn }}</option></select><input v-else v-model="target.itemValue"></label></template><p class="source-note">{{ t('必须能唯一对应一条明细；其他条目的答案不会用于此段原文。', 'The match must identify one item. Answers from other items do not apply to this passage.') }}</p></fieldset><label v-if="['fill','replace'].includes(target.action) && targetSpans(target).length > 1">{{ t('新文字放在哪个选区？其余已选区将清空。', 'Which span receives the new text? Other selected spans will be cleared.') }}<select :value="target.replacementPlacement?.spanIndex ?? ''" :data-target-placement="index" @change="target.replacementPlacement = ($event.target as HTMLSelectElement).value === '' ? undefined : { spanIndex: Number(($event.target as HTMLSelectElement).value) }"><option value="">{{ t('请明确选择', 'Choose explicitly') }}</option><option v-for="(span,spanIndex) in targetSpans(target)" :key="spanIndex" :value="spanIndex">{{ spanIndex + 1 }} · {{ span.expectedText }}</option></select></label><p v-if="target.action === 'insert'" class="source-note">{{ t('只在来源指定的插入点增加文字；清单条数和内容需明确采用项目资料。', 'Insert only at the source anchor. List size and contents require explicit adoption of project facts.') }}</p><template v-if="['fill','replace','insert'].includes(target.action)"><label>{{ t('引用哪个变量的值？', 'Which answer supplies the text?') }}<input v-model="optionQueries['answer:'+index]" type="search" :data-target-answer-search="index" :placeholder="t('搜索变量', 'Search answer variables')"><select v-model="target.valueSourceId" data-target-answer-source><option value="">{{ t('固定替换文字', 'Fixed replacement') }}</option><option v-if="target.valueSourceId && !answerSources.some(item => item.id === target.valueSourceId)" :value="target.valueSourceId" disabled>{{ conditionReferenceLabel(target.valueSourceId) }} · {{ t('当前答案来源需重新核对', 'Review answer source') }}</option><option v-for="item in searchedOptions(answerSources,'answer:'+index,target.valueSourceId)" :key="item.id" :value="item.id">{{ questionLabel(item) }}</option></select></label><p v-if="conditionInputProblem(target.valueSourceId)" class="review-error" role="alert" :data-target-value-reference-problem="index">{{ conditionInputProblem(target.valueSourceId) }}</p><template v-if="target.valueSourceId"><select v-model="target.valueMode"><option value="single">{{ t('一个答案', 'One answer') }}</option><option value="join">{{ t('串联逐项答案', 'Join item answers') }}</option></select><template v-if="target.valueMode === 'join'"><input v-model="optionQueries['target-list:'+index]" type="search" :placeholder="t('搜索重复明细组', 'Search repeated lists')"><select v-model="target.listId" :data-target-list="index"><option v-if="target.listId && !conditionLists.some(item => item.id === target.listId)" :value="target.listId" disabled>{{ conditionReferenceLabel(target.listId) }} · {{ t('原引用需核对', 'Retained reference needs review') }}</option><option v-for="item in searchedOptions(conditionLists,'target-list:'+index,target.listId)" :key="item.id" :value="item.id">{{ questionLabel(item) }}</option></select><p v-if="conditionInputProblem(target.listId)" class="review-error" role="alert" :data-target-list-reference-problem="index">{{ conditionInputProblem(target.listId) }}</p><label>{{ t('分隔文字', 'Exact separator') }}<input v-model="target.separator"></label></template><label>{{ t('答案前文字', 'Exact prefix') }}<textarea v-model="target.prefix" /></label><label>{{ t('答案后文字', 'Exact suffix') }}<textarea v-model="target.suffix" /></label></template><template v-else><label>{{ t('固定文字（中文）', 'Fixed wording (Chinese)') }}<textarea v-model="target.replacementZh" /></label><label>{{ t('固定文字（英文）', 'Fixed wording (English)') }}<textarea v-model="target.replacementEn" /></label></template></template><button class="btn" :disabled="!selection?.native || busy || target.action === 'insert'" :data-target-append="index" @click="appendTarget(index)">{{ t('把当前选区追加到此修改位置', 'Append current selection to this amendment target') }}</button><ol v-if="targetSpans(target).length > 1"><li v-for="(span,spanIndex) in targetSpans(target)" :key="spanIndex"><blockquote>{{ span.expectedText }}</blockquote></li></ol><button class="btn" @click="form.targets.splice(index,1)">{{ t('移除此目标', 'Remove target') }}</button></article><p class="source-note">{{ t('先在左边选词，再点“作为修改目标”。跨段目标分别保留，不扩成首尾整段。', 'Select words on the left and use them as an amendment target. Multiple spans remain distinct.') }}</p></section>
          <section v-if="relatedScopes.length" class="question-section" data-review-related-scopes>
            <h4>{{ t('指导说明关联的原文范围', 'Source ranges linked by the guidance') }}</h4>
            <p class="source-note">{{ t('以下展开范围涉及的原文段落。点击定位后可在左侧准确选词，再设定修改方式和条件；查看范围不会自动选中、修改或确认。', 'These are the source paragraphs involved in each range. Locate a paragraph, select exact words on the left, then set the amendment and conditions. Viewing a range does not select, amend or confirm it.') }}</p>
            <article v-for="scope in relatedScopes" :key="scope.group.id">
              <h5>{{ scope.group.instructionReferenceText || t('关联范围', 'Linked range') }}</h5>
              <p>{{ scope.group.scopeComplete === true ? t('来源范围已识别，修改方案待核对', 'Source range identified; amendment still needs review') : t('来源范围尚不完整，请核对原件', 'Source range incomplete; check the original') }}</p>
              <div v-if="scope.group.kind === 'inline_parenthetical_scope'" data-review-inline-scope>
                <strong>{{ t('仅以下标记词语属于修改范围', 'Marked words only') }}</strong>
                <blockquote>{{ scope.group.text || t('准确词语不可用，请核对原件。', 'Exact words unavailable; check the original.') }}</blockquote>
                <p v-if="scope.group.wrapperCleanupStatus === 'independent_decision_required'" class="source-note">{{ t('两侧括号不在此范围内；是否清理括号须另行核对。下面整段仅供理解上下文，不代表整段都可修改。', 'Parentheses are outside this range; their cleanup needs a separate review. The full paragraph below supplies context, not authority to change the whole paragraph.') }}</p>
              </div>
              <p v-if="!scope.members.length">{{ t('此范围没有可展开的段落列表，请查看原件。', 'No paragraph list is available for this range. Check the original.') }}</p>
              <div v-for="member in scope.members" :key="member.id">
                <blockquote>{{ member.text?.trim() ? member.text : member.text === undefined ? t('原文段落不可用，请核对来源。', 'Source paragraph unavailable; check the source.') : t('（空白段落或无文字位置）', '(Blank paragraph or non-text position)') }}</blockquote>
                <button class="btn" :data-review-locate-scope="member.id" :disabled="busy || formDirty || !member.canLocate" @click="locateParagraph(member.paragraphId)">{{ member.canLocate ? t('定位此段并选词', 'Locate paragraph to select words') : t('无文字可自动定位', 'No text available to locate') }}</button>
              </div>
            </article>
          </section>
          <section class="question-section"><h4>{{ t('重复变量或同一变量的分支', 'Duplicate variables or branches of one variable') }}</h4><p class="source-note">{{ t('先勾选要对照的条目，再分别处理重复提问与不同答案路线。不会把来源核对项直接变成业务答案。', 'Select items for comparison, then distinguish duplicate variables from alternative answer routes. Source checks retain their purpose.') }}</p><button class="btn" data-review-merge :disabled="formDirty||splitDirty" @click="openBatch(true)">{{ t('勾选当前项并查看对照', 'Select this item and compare') }}</button></section><section class="question-section"><h4>{{ t('已关联原文', 'Linked source passages') }}</h4><article v-for="(anchor,index) in selected.sourceAnchors" :key="index"><blockquote>{{ anchor.text }}</blockquote><button class="btn" data-review-locate-anchor :disabled="busy || formDirty" @click="locateAnchor(anchor)">{{ t('定位这段原文', 'Locate this passage') }}</button><button class="btn" :disabled="formDirty" @click="apply({type:'UNLINK',id:selectedId,index}); resetForm()">{{ t('移除错误关联', 'Remove incorrect link') }}</button></article></section><footer class="review-actions"><button class="btn" @click="resetForm()">{{ t('取消当前调整', 'Cancel edit') }}</button><button class="btn primary" data-review-save-form :disabled="!formDirty" @click="saveForm">{{ t('记录变量调整', 'Keep variable changes') }}</button><button class="btn primary" data-review-save-bottom :disabled="busy || locked || !dirty || formDirty || splitDirty" @click="save">{{ saving ? t('正在保存草稿…', 'Saving draft…') : t('保存核对草稿', 'Save review draft') }}</button><p class="source-note" role="status">{{ saving ? t('正在保存，请等待服务器确认；尚未确认模板或采用答案。', 'Saving; waiting for server confirmation. Template and answers are not adopted.') : formDirty ? t('先记录当前变量调整，再保存草稿。', 'Keep the current variable changes, then save the draft.') : dirty ? t('调整已记录，尚未保存。保存草稿不会确认模板或采用变量值。', 'Changes are recorded but not saved. Saving does not confirm the template or adopt project answers.') : t('当前没有未保存调整。', 'There are no unsaved changes.') }}</p></footer>
        </template>
      </aside>
    </div>
    </section></div>
    <AppModal :open="splitOpen" :title="t('分别选取两个变量的原文', 'Select the source for each variable')" wide @close="splitOpen = false"><p>{{ t('可先收起此窗在左侧选词，再打开保留选区。拆分不会复制旧变量值。', 'Select each exact source passage. Splitting does not copy an adopted project answer.') }}</p><div class="split-columns"><section><button class="btn" :disabled="!selection" @click="splitFirst = selection">{{ t('当前选词作为第一项', 'Use selection for the first item') }}</button><blockquote>{{ splitFirst?.text }}</blockquote><input v-model="splitNames.firstZh" placeholder="中文名称"><input v-model="splitNames.firstEn" placeholder="English name"></section><section><button class="btn" :disabled="!selection" @click="splitSecond = selection">{{ t('当前选词作为第二项', 'Use selection for the second item') }}</button><blockquote>{{ splitSecond?.text }}</blockquote><input v-model="splitNames.secondZh" placeholder="中文名称"><input v-model="splitNames.secondEn" placeholder="English name"></section></div><select v-model="splitNames.mode"><option value="unknown">{{ t('关系仍待核对', 'Relationship to review') }}</option><option value="all">{{ t('两项同时成立', 'Both must hold') }}</option><option value="any">{{ t('任一项成立', 'Either may hold') }}</option></select><button class="btn" @click="cancelSplit">{{ t('取消本次拆分', 'Cancel this split') }}</button><button class="btn primary" @click="split">{{ t('保留两个独立变量', 'Keep two independent variables') }}</button></AppModal>
    <AppModal :open="batchOpen" :title="t('勾选项对照与分支处理', 'Selected items and answer branches')" wide @close="batchOpen = false"><p v-if="batchError" class="review-error" role="alert" data-batch-error>{{ batchError }}</p><DraftingQuestionBatch v-if="batchOpen" :state="state" :ids="checkedQuestionIds" :sources="graph.sources" :locale="locale" :draft="batchDraft" @draft="batchDraft=$event" @merge="mergeChecked" @branches="applyBatch" @locate="locateBatchQuestion" /></AppModal>
    <AppModal :open="showGraph" :title="t('变量、修改方案与原文文件', 'Variables, amendments and source files')" wide @close="showGraph = false">
      <div class="review-actions"><button class="btn" data-review-graph-clear @click="graphSelected=''; graphOnlySelected=false">{{ t('完整图谱／清除选中', 'Complete graph / clear selection') }}</button><button class="btn" data-review-graph-only :disabled="!graphSelected" :aria-pressed="graphOnlySelected" @click="graphOnlySelected=!graphOnlySelected">{{ graphOnlySelected ? t('显示完整图谱', 'Show complete graph') : t('只看选中链路', 'Show selected chain only') }}</button><span class="source-note">{{ t('选中链路与可能影响高亮，其他项目仍可选择。实际方案以已采用事实为准。', 'The selected chain and possible impact are highlighted. Other items remain selectable. Adopted facts determine the actual plan.') }}</span></div>
      <div v-if="graphLayout" class="review-graph-body"><div class="graph-scroll"><div class="graph-canvas" :style="{width:graphLayout.width+'px',height:graphLayout.height+'px'}"><svg :width="graphLayout.width" :height="graphLayout.height"><path v-for="edge in shownGraphEdges" :key="edge.id" :data-review-graph-edge="edge.id" :d="edge.path" class="graph-edge" :class="[edge.kind, {selected:graphImpact?.edgeIds.includes(edge.id),dim:graphSelected && !graphImpact?.edgeIds.includes(edge.id)}]" /></svg><button v-for="item in shownGraphItems" :key="item.id" :data-review-graph-node="item.id" class="graph-node" :class="[{selected:graphChain.has(item.id),dim:graphSelected && !graphChain.has(item.id)},item.id.startsWith('action:')?'action-node':item.docId?'file-node':'input-node']" :aria-pressed="graphSelected===item.id" :style="{left:item.x+'px',top:item.y+'px',width:item.w+'px',height:item.h+'px'}" @click="graphSelected=item.id"><strong>{{ graphLabel(item) }}</strong><small>{{ item.id.startsWith('action:') ? t('修改方案', 'Amendment action') : item.docId ? t('原文文件', 'Source file') : isReviewCheck(item.node ?? item) ? t('待核对事项', 'Review check') : t('变量', 'Variable') }}</small><small v-if="item.cyclic" class="review-error">{{ t('派生答案存在循环', 'Cyclic derived answer') }}</small><small v-else-if="item.needsReview">{{ t('方案仍待核对', 'Action needs review') }}</small><small v-if="item.summaryZh || item.summaryEn">{{ locale==='en'?item.summaryEn:item.summaryZh }}</small></button></div></div>
      <aside class="review-graph-inspector"><template v-if="graphInspection"><h3>{{ graphLabel(graphInspection) }}</h3><p v-if="graphInspection.cyclic" class="review-error">{{ t('派生答案存在循环依赖，请核对取值规则及明细所属关系。', 'Derived answers have a cyclic dependency. Review the value rules and record membership.') }}</p><p class="source-note">{{ t('前提关联', 'Upstream dependencies') }} {{ graphImpact?.upstreamIds?.length ?? 0 }} · {{ t('可能受影响的变量', 'Potentially affected variables') }} {{ graphImpact?.downstreamIds?.length ?? 0 }}</p><button class="btn" data-review-graph-locate :disabled="busy || formDirty" @click="locateGraphSelection">{{ graphInspection.docId ? t('对照这份原件', 'Open this source') : t('对照原文核对该项', 'Review this item on its source') }}</button><blockquote v-for="(anchor,index) in graphInspection.sourceAnchors ?? graphInspection.node?.sourceAnchors ?? []" :key="index">{{ anchor.text }}</blockquote><article v-for="(entry,index) in graphInspection.targets ?? []" :key="index"><blockquote>{{ entry.target?.text }}</blockquote></article></template><p v-else class="source-note">{{ t('选择一个变量、方案或文件，查看完整关联与影响范围。', 'Select a variable, action or file to inspect its relationships and possible impact.') }}</p></aside></div>
    </AppModal>
    <AppModal :open="historyOpen" :title="t('已保存的图谱版本', 'Saved graph revisions')" wide @close="historyOpen = false"><p data-history-relations-notice>{{ t('恢复历史变量和范围时，关系建议清单及处理记录沿用当前保存版本。相关定义、条件或范围变化后，对应关系需重新核对。','Restoring historical variables and ranges keeps the current saved relationship proposals and review records. Related definition, condition or range changes require those relationships to be reviewed again.') }}</p><article v-for="revision in history" :key="revision.id" class="revision-row"><span>{{ revision.createdAt }} · {{ revision.status }} · {{ revision.sourceStale ? t('来源已变化', 'Source changed') : t('保存的来源绑定', 'Saved source bindings') }}</span><button class="btn" :disabled="busy || locked || revision.sourceStale" @click="restoreRevision(revision)">{{ t('作为新草稿核对', 'Review as a new draft') }}</button></article></AppModal>
  </fieldset></section>
</template>

<style scoped>
.review-columns--variables-only{grid-template-columns:minmax(0,1fr)!important}
.native-paragraph.blank-paragraph{height:6px;padding:0}.blank-paragraph .paragraph-number{visibility:hidden}
.question-section{margin:18px 0;border-top:1px solid var(--line);padding-top:10px}.question-section h4{font-size:13px;margin:6px 0 12px}
.review-controls{border:0;margin:0;padding:0;min-width:0}.review-controls:disabled .native-source{user-select:none;pointer-events:none}.source-review{border:1px solid var(--line);border-radius:var(--radius);background:var(--surface);padding:20px}.review-heading,.review-actions,.source-choice{display:flex;flex-wrap:wrap;gap:8px;align-items:flex-start;justify-content:space-between}.review-heading p,.source-note,.review-status{font-size:12px;color:var(--muted)}.review-status{border-bottom:1px solid var(--line);padding-bottom:12px}.source-choice{justify-content:flex-start;margin:12px 0}.review-columns{display:grid;grid-template-columns:minmax(0,1fr) 370px;gap:18px;margin-top:16px}.native-source,.review-editor{min-height:0;max-height:78vh;overflow:auto;padding:14px;border:1px solid var(--line);border-radius:6px}.native-source{background:var(--surface-subtle)}.native-paragraph{display:grid;grid-template-columns:35px minmax(0,1fr);gap:8px;padding:5px 0}.native-paragraph p{margin:0;white-space:pre-wrap;line-height:1.8;user-select:text}.paragraph-number{color:var(--muted);font-size:10px;user-select:none;padding-top:5px}.review-editor>select,.question-search{width:100%;margin:10px 0}.selection-tools{background:var(--surface);border-bottom:1px solid var(--line);padding-bottom:10px}label{display:block;font-size:12px;margin:10px 0}input,textarea,select{font:inherit;padding:8px;border:1px solid var(--line-strong);border-radius:5px;max-width:100%;background:var(--surface);color:var(--ink)}label>input,label>textarea,label>select{display:block;width:100%;margin-top:5px}textarea{min-height:65px;resize:vertical}blockquote{white-space:pre-wrap;font-size:12px;line-height:1.7;border-left:2px solid var(--accent);padding:8px 12px;margin:10px 0;background:var(--surface-subtle)}blockquote small{display:block;color:var(--muted)}details{margin:14px 0;border-top:1px solid var(--line);padding-top:10px}summary{cursor:pointer;font-size:13px}pre{white-space:pre-wrap;overflow-wrap:anywhere}.review-error{color:var(--red);background:var(--red-soft);padding:10px;font-size:12px}.condition-case,.target-row{padding:12px;border:1px solid var(--line);border-radius:6px;margin:10px 0}.condition-row{display:flex;flex-wrap:wrap;gap:5px;margin:10px 0}.condition-row>select{min-width:100px;max-width:100%}.split-columns{display:grid;grid-template-columns:1fr 1fr;gap:16px}.split-columns input{width:100%;margin:5px 0}.review-graph-body{display:grid;grid-template-columns:minmax(0,1fr) 280px;gap:14px;margin-top:14px}.graph-scroll{max-height:70vh;overflow:auto;border:1px solid var(--line);border-radius:8px;background:var(--surface-subtle)}.review-graph-inspector{max-height:70vh;overflow:auto;border:1px solid var(--line);border-radius:8px;padding:16px}.graph-canvas{position:relative}.graph-canvas svg{position:absolute;inset:0;pointer-events:none}.graph-canvas path{fill:none;stroke:#aebfb7;stroke-width:.9;opacity:.32}.graph-canvas path.selected{stroke:var(--orange,#d86b1f);stroke-width:1.6;opacity:1}.graph-canvas path.dim{opacity:.09}.graph-canvas path.reference,.graph-canvas path.split-origin{stroke-dasharray:5 4}.graph-canvas path.condition,.graph-canvas path.scope{stroke-dasharray:1 4;stroke:#779dbe}.graph-node{position:absolute;border:1px solid #8dc9c4;background:#e6f4f2;border-radius:5px;padding:10px;text-align:left;font:inherit;font-size:12px;line-height:1.55;overflow:hidden;cursor:pointer}.graph-node strong{display:block;font-weight:600}.graph-node.action-node{background:#fbf0e7;border-color:#e9aa7e}.graph-node.file-node{background:#edf2fa;border-color:#9abce0}.graph-node.selected{border:2px solid var(--orange,#d86b1f)}.graph-node.dim{opacity:.28}.graph-node:focus-visible{outline:2px solid var(--accent);outline-offset:2px}.graph-node small{display:block;color:var(--muted);font-size:10px}.revision-row{display:flex;justify-content:space-between;gap:12px;margin:14px 0}@media(max-width:700px){.review-graph-body{grid-template-columns:1fr}.review-graph-inspector{max-height:30vh}}@media(max-width:1100px){.review-columns{grid-template-columns:1fr}.native-source{max-height:55vh}.review-editor{max-height:none}.split-columns{grid-template-columns:1fr}}

.original-source{min-width:0}.original-source :deep(.pdf-pages){height:78vh;min-height:560px}.source-tabs,.question-tabs{display:flex;gap:6px}.source-tabs [aria-pressed="true"],.question-tabs [aria-pressed="true"]{background:var(--accent-soft);border-color:var(--accent)}.edition-status{color:var(--accent-dark);font-size:12px}.question-card{display:block;width:100%;padding:12px;text-align:left;border:1px solid var(--line);border-radius:6px;background:var(--surface);margin:8px 0;cursor:pointer}.question-card strong,.question-card small{display:block}.question-card small{color:var(--muted);margin-top:6px}.question-summary h4{font-size:16px;line-height:1.6}.question-summary blockquote{margin:12px 0;padding:10px;border-left:3px solid var(--accent);background:var(--bg);white-space:pre-wrap}.review-editor{background:var(--surface)}.selection-tools{position:sticky;top:0;background:var(--surface);z-index:3}.question-select-row{display:flex;align-items:center;gap:8px}.batch-selection{border-bottom:1px solid var(--line);padding-bottom:12px}.batch-selection p{overflow-wrap:anywhere}
.source-workspace-toolbar{display:flex;justify-content:space-between;align-items:center;gap:12px;margin-top:12px}.source-workspace-toolbar strong{font-size:13px;overflow-wrap:anywhere}.source-workspace-toolbar button{flex-shrink:0}
.location-notice{padding:8px 12px;background:var(--surface-subtle);border-left:2px solid var(--accent)}
.source-workspace--immersive{position:fixed;inset:0;z-index:100;height:100dvh;box-sizing:border-box;display:flex;flex-direction:column;padding:12px;gap:8px;background:var(--surface);overflow:hidden}
.source-workspace--immersive .source-workspace-toolbar{margin:0;flex-shrink:0}
.source-workspace--immersive .review-columns{grid-template-columns:minmax(0,2fr) minmax(360px,1fr);flex:1;min-height:0;margin:0;overflow:hidden}
.source-workspace--immersive .original-source{display:flex;flex-direction:column;min-height:0;overflow:hidden}
.source-workspace--immersive .original-source :deep(.pdf-pages){height:auto;min-height:0;flex:1}
.source-workspace--immersive .review-editor{max-height:none;overscroll-behavior:contain}
.source-review--immersive :deep(.modal-backdrop){z-index:120}
@media(max-width:1100px){.source-workspace--immersive .review-columns{grid-template-columns:minmax(0,1fr) 330px}}
@media(max-width:700px){.source-workspace--immersive .review-columns{grid-template-columns:minmax(0,1fr);grid-template-rows:minmax(0,3fr) minmax(0,2fr)}}
</style>

<style scoped>
.condition-input-choice{flex:1 1 220px;margin:0}.condition-input-choice>input,.condition-input-choice>select{width:100%}.condition-row{align-items:flex-start}.condition-help,.condition-new-input{flex-basis:100%;margin:0}.condition-new-input{border:1px solid var(--line);border-radius:5px;padding:10px}.condition-new-input .btn{margin:4px 6px 0 0}
</style>
