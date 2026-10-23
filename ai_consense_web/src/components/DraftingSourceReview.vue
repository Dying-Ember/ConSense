<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, reactive, ref, shallowRef, watch } from 'vue'
import { draftingApi } from '@/api'
import { reviewSourceBindings } from '@/api/source-locations'
import { containTab } from '@/focus'
import type { DraftDocumentBindings } from '@/api/types'
import { bindingPoint, matchesBindingPdf, pdfSha256, type PdfBindingLocation } from '@/drafting/document-bindings'
import AppModal from './AppModal.vue'
import DraftingQuestionBatch from './DraftingQuestionBatch.vue'
import DraftingPdfPreview from './DraftingPdfPreview.vue'
import { bindPageSelection, type SourcePageSelection } from '@/drafting/source-page-selection'
import { createReviewReducer } from '@/drafting/source-review/reducer.js'
import graphView from '@/drafting/source-review/graph-business-view.js'
import { appendNativeSelection, nativeAnchor, emptyReviewState, reviewProblem, sourceReviewDrafts, targetSpans } from '@/drafting/source-review-state'
import type { ReviewAnchor, ReviewGraph, ReviewHead, ReviewObject, ReviewReading, ReviewRevision, ReviewRevisionSummary, ReviewSource, ReviewState, ReviewVariable } from '@/drafting/source-review-types'
const reviewDraftCache = sourceReviewDrafts as Map<string, NonNullable<ReturnType<typeof sourceReviewDrafts.get>> & { batch?: { ids: string[]; open: boolean; draft?: ReviewObject } }>
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
const graph = shallowRef<ReviewGraph>({ kind: 'consense-template-graph-v1', graph: emptyReviewState(), sources: [], selectedDocuments: {} })
const reading = shallowRef<ReviewReading>(), sourceId = ref(''), selectedId = ref(''), selection = shallowRef<ReviewAnchor>()
const focusText=ref(''),focusRequest=ref(0),selectionError=ref('')
const pageSelection=shallowRef<SourcePageSelection>(),manualParagraphId=ref('')
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
const sourceBindings=shallowRef<DraftDocumentBindings>(),locationParagraphs=ref<string[]>([]),selectedBindingId=ref('')
const sourceLocations=computed<PdfBindingLocation[]>(()=>{
  if(!reading.value||!sourceBindings.value)return []
  return locationParagraphs.value.flatMap((id,index)=>{
    const paragraph=reading.value!.paragraphs.find(item=>item.id===id)
    const rows=sourceBindings.value!.bindings.filter(row=>row.sourceParagraphId===id&&row.sourceText===paragraph?.text)
    if(rows.length!==1)return []
    const row=rows[0]!,geometry=bindingPoint(row)
    return geometry?[{bindingId:row.bindingId,label:t('关联原文位置 ','Linked source position ')+(index+1),geometry,text:row.sourceText}]:[]
  })
})
const locationIndex=computed(()=>sourceLocations.value.findIndex(location=>location.bindingId===selectedBindingId.value))
watch(sourceLocations,locations=>{
  selectedBindingId.value=locations[0]?.bindingId??'';focusRequest.value++
  if(sourceBindings.value&&locationParagraphs.value.length)locationNotice.value=locations.length===locationParagraphs.value.length?'':t('部分原文位置尚未取得当前PDF坐标，已显示可定位的部分；原件仍可浏览。','Some source positions have no current PDF coordinates. Available positions are shown; the original remains readable.')
})
function moveLocation(delta:number){const location=sourceLocations.value[locationIndex.value+delta];if(location){selectedBindingId.value=location.bindingId;focusRequest.value++}}
const locationNotice=ref('')
function locationUnavailable(reason: 'not_found' | 'unreadable') {
  locationNotice.value = reason === 'unreadable'
    ? t('暂时无法检索页面文字，原件仍可浏览。可继续翻页，或在页面上选词核对。','Page text could not be searched. You can still browse, change pages or select words on the original.')
    : t('未在页面文字中找到这段原文，原件仍可浏览。请对照页面选取准确文字。','This passage was not found in the page text. You can still browse the original and select the exact words.')
}
const busy = ref(false), error = ref(''), dirty = ref(false), search = ref(''), formBase = ref(''), formOpen = ref(false)
const saving = ref(false)
const graphSelected = ref(''), graphOnlySelected = ref(false)
const showGraph = ref(false), historyOpen = ref(false), history = ref<ReviewRevisionSummary[]>([]), pdfUrl = ref('')
const originalLoading = ref(false), originalError = ref('')
const sourceLoading=ref(false)
const immersive = ref(false), workspace = ref<HTMLElement>(), immersiveToggle = ref<HTMLButtonElement>()
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
const questionRows=computed(()=>filtered.value.filter(item=>isReviewCheck(item)===(questionKind.value==='checks')))
const shownQuestions=computed(()=>questionRows.value.slice(questionPage.value*8,questionPage.value*8+8))
watch([search,sourceId,questionKind],()=>questionPage.value=0)
watch([sourceId,selectedId],()=>locationNotice.value='')
watch(sourceId,()=>{sourceBindings.value=undefined;locationParagraphs.value=[];selectedBindingId.value=''})
const form = reactive<ReviewObject>({ nameZh: '', nameEn: '', kind: 'boolean', role: 'input', parentId: '', fixedValue: '', optionsText: '', splitMode: 'unknown', branches: [], targets: [] })
let generation = 0, sourceGeneration = 0, alive = true
let loading: Promise<void> = Promise.resolve()
const state = computed(() => graph.value.graph)
const selected = computed(() => state.value.variables.find(item => item.id === selectedId.value))
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
    members: (group.orderedMemberIds ?? []).map((id: string) => {
      const matches = source.sourceMembers.filter(member => member.id === id)
      const member = matches.length === 1 ? matches[0] : undefined
      const paragraphId=memberParagraph(source, member)
      return { id, text: member?.textObservation?.text, paragraphId, canLocate: !!source.paragraphs.find(paragraph=>paragraph.id===paragraphId)?.text.trim() }
    })
  }))
})
const filtered = computed(() => state.value.variables.filter(item => item.status !== 'excluded' && (!sourceId.value || !item.docIds?.length || item.docIds.some(id=>[sourceId.value,reading.value?.docId,reading.value?.editionId].includes(id))) && (!search.value || (questionLabel(item) + ' ' + item.nameZh + ' ' + item.nameEn).toLowerCase().includes(search.value.toLowerCase()))))
function questionLabel(item: ReviewObject) {
  if (item.definitionKind !== 'source_seed' || item.manual&&!/guidance:[a-f0-9]{16,}/i.test(item.nameZh+' '+item.nameEn)) return props.locale === 'en' ? item.nameEn || item.nameZh : item.nameZh || item.nameEn
  const quote = item.sourceObservation?.text || item.sourceAnchors?.[0]?.text || ''
  return t('来源待核对：', 'Source to review: ') + (quote ? quote.slice(0, 100) : t('请查看关联原文', 'See linked source'))
}
function statusLabel(status: string) { return ({ pending: t('待核对', 'To review'), confirmed: t('已确认', 'Confirmed'), changed: t('已调整，待确认', 'Changed; confirm'), help: t('需处理', 'Needs attention'), excluded: t('已排除', 'Excluded') } as Record<string, string>)[status] || t('待核对', 'To review') }
const ruleSource = computed(() => !!reading.value && graph.value.sources.some(source => String(source.id) === String(reading.value!.id)))
const invalidTemplateSources = computed(() => head.value.sources.filter(source => ['NTT', 'SCT', 'SCC'].includes(source.fileKey ?? '') && source.evidenceStatus !== 'bound' && (!Object.values(graph.value.selectedDocuments).length || Object.values(graph.value.selectedDocuments).some(id => String(id) === String(source.id)))))
const selectedSource = computed(() => reading.value ?? head.value.sources.find(source => String(source.id) === sourceId.value))
const sourceEvidenceLabel = (source: ReviewSource) => source.evidenceStatus === 'bound' ? t('来源证据已绑定', 'Source evidence bound') : source.evidenceStatus === 'invalid' ? t('来源证据已失效（包括旧分析代次）', 'Source evidence invalid (including an old analysis revision)') : t('缺少当前来源证据', 'Current source evidence missing')
const isReviewCheck = (item: ReviewObject) => item.role?.kind === 'verification' || item.definitionKind === 'source_seed' && !item.manual
const answerSources = computed(() => state.value.variables.filter(item => item.status !== 'excluded' && !isReviewCheck(item)))
const sourceChecks = computed(() => state.value.variables.filter(item => item.status !== 'excluded' && isReviewCheck(item)).length)
const businessQuestions = computed(() => state.value.variables.filter(item => item.status !== 'excluded').length - sourceChecks.value)
const pending = computed(() => state.value.variables.filter(item => item.status !== 'excluded' && item.status !== 'confirmed').length)
const nextPending = computed(() => {
  const items = state.value.variables, at = items.findIndex(item => item.id === selectedId.value)
  return [...items.slice(at + 1), ...items.slice(0, at + 1)].find(item => item.status !== 'excluded' && item.status !== 'confirmed')
})
const selectedProblem = computed(() => selected.value ? reviewProblem(selected.value, state.value) : null)
const splitDirty = computed(() => !!splitFirst.value || !!splitSecond.value || [splitNames.firstZh, splitNames.firstEn, splitNames.secondZh, splitNames.secondEn].some(value => value.trim()))
function cancelSplit() { splitFirst.value = undefined; splitSecond.value = undefined; Object.assign(splitNames, { firstZh: '', firstEn: '', secondZh: '', secondEn: '', mode: 'unknown' }); splitOpen.value = false }
const formDirty = computed(() => formOpen.value && JSON.stringify(form) !== formBase.value)
const isolatedRule = computed(() => selected.value?.discoveryCoreRetained === true && selected.value.status !== 'excluded' && selected.value.correction?.kind === 'rule' && selected.value.correction.needsRebind === true && !selected.value.correction.branches?.length && !selected.value.correction.inputIds?.length && !selected.value.editTargets?.length)
const parents = computed(() => state.value.variables.filter(item => item.status !== 'excluded' && ['record', 'list'].includes(item.type.kind) && item.id !== selectedId.value))
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
function graphLabel(item: ReviewObject) { const label = { ...item.node, ...item }; if (label.definitionKind === 'source_seed' && !label.manual) return questionLabel(label); return props.locale === 'en' ? label.labelEn ?? label.nameEn ?? label.labelZh ?? label.nameZh : label.labelZh ?? label.nameZh ?? label.labelEn ?? label.nameEn }
async function locateGraphSelection() { const item = graphInspection.value; if (!item) return; showGraph.value = false; if (item.docId) await selectSource(item.docId); else await choose(item.ownerId ?? item.id) }
const kinds: Record<string, string> = { boolean: '是／否 / Yes / No', number: '数字 / Number', text: '文字 / Text', date: '日期 / Date', choice: '单选 / Single choice', multiselect: '多选 / Multiple choices', record: '一组资料 / Related details', list: '逐项清单 / List of items' }
const roles: Record<string, string> = { input: '填写项目事实 / Project fact', derived: '由其他答案决定 / Derived answer', fixed: '使用固定内容 / Fixed wording', verification: '需人工核查 / Manual check', decision: '决定是否采用 / Adoption decision', content: '待提供文字 / Wording to provide' }
const actions: Record<string, string> = { keep: '保留 / Retain', fill: '填写 / Fill', replace: '替换 / Replace', insert: '在原件锚点插入 / Insert at source anchor', delete: '删除 / Delete', unused: '标为Not used / Mark Not used', check: '需要核查 / Check' }
const operators: Record<string, string> = { yes: '为是 / Yes', no: '为否 / No', known: '已填写 / Provided', eq: '等于 / Equals', ge: '不少于 / At least', le: '不多于 / At most', gt: '多于 / Greater than', lt: '少于 / Less than', has: '包含 / Includes', lacks: '不包含 / Does not include', empty: '明确为空 / Confirmed empty', notempty: '已填且有内容 / Provided with content' }
function allowedOperators(id: string) { const kind = state.value.variables.find(item => item.id === id)?.type.kind; return kind === 'boolean' ? ['yes', 'no', 'known'] : kind === 'number' ? ['eq', 'ge', 'le', 'gt', 'lt', 'known'] : kind === 'multiselect' ? ['has', 'lacks', 'empty', 'notempty', 'known'] : ['text', 'list'].includes(kind) ? ['eq', 'empty', 'notempty', 'known'].filter(value => kind !== 'list' || value !== 'eq') : ['eq', 'known'] }
function targetScope(target: ReviewObject) { return form.branches.find((branch: ReviewObject) => branch.id === target.branchId && branch.scopeMode === 'each' && branch.scopeId) }
function itemKeys(target: ReviewObject) { const scope = targetScope(target)?.scopeId; return state.value.variables.filter(item => item.parentId === scope && !['record', 'list'].includes(item.type.kind) && item.status !== 'excluded') }
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
  if (!item) { formOpen.value = false; return }
  Object.assign(form, { nameZh: item.nameZh, nameEn: item.nameEn, kind: item.type.kind, role: item.role.kind, parentId: item.parentId ?? '', fixedValue: item.fixedValue ?? '', splitMode: item.correction?.kind === 'split' ? item.correction.mode : 'unknown', optionsText: (item.options ?? []).map(option => option.labelZh + ' | ' + option.labelEn).join('\n'), branches: copy(item.correction?.kind === 'rule' ? item.correction.branches ?? [] : []), targets: copy(item.editTargets ?? []).map((target: ReviewObject) => ({ ...target, action: target.action === 'retain' ? 'keep' : target.action === 'not_used' ? 'unused' : target.action })) })
  formBase.value = JSON.stringify(form); formOpen.value = true
}
function protect() { if (busy.value) return false; if (formDirty.value) { error.value = t('请先保存或取消当前问题调整。', 'Save or cancel the current question edit first.'); return false }; return true }
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
  const paragraphIds=targetSpans(anchor).filter(span=>reading.value!.paragraphs.some(paragraph=>paragraph.id===span.paragraphId&&paragraph.paragraphSha256===span.paragraphSha256)).map(span=>span.paragraphId)
  if (!paragraphIds.length && anchor.sourceObservationBinding?.status === 'bound' && anchor.sourceSha256 === reading.value.sourceSha256 && ['analysisRevision', 'analysisInputSha256', 'analysisSha256'].every(key => anchor[key] === reading.value![key])) {
    for(const range of anchor.sourceObservationBinding.observedRanges??[]){
      const matches = reading.value.sourceMembers.filter(member => member.id === range.memberId)
      const id=matches.length===1?memberParagraph(reading.value,matches[0]):undefined
      if(id)paragraphIds.push(id)
    }
  }
  locateParagraphs(paragraphIds,anchor.text)
}
function locateParagraph(id?: string, quote?: string) {
  locateParagraphs(id?[id]:[],quote)
}
function locateParagraphs(ids:string[],quote?:string){
  const paragraphs=reading.value?.paragraphs.filter(item=>ids.includes(item.id))??[]
  const text=paragraphs.find(paragraph=>paragraph.text.trim())?.text
  locationParagraphs.value=[...new Set(paragraphs.filter(paragraph=>paragraph.text.trim()).map(paragraph=>paragraph.id))]
  if(text){focusText.value=reading.value?.format==='docx'?'':quote?.trim()||text;focusRequest.value++;error.value='';locationNotice.value=reading.value?.format==='docx'&&!sourceBindings.value?t('正在读取原件定位，文件仍可浏览；若稍后仍未定位，请重新读取原件。','Source locations are being loaded. The file remains readable; reopen the original if location stays unavailable.'):''}
  else {
    focusText.value=''
    locationNotice.value=paragraphs.length&&!quote?.trim()
      ? t('这处原文是空白段落，没有文字可自动定位；原件仍可正常浏览。','This is a blank paragraph with no text to locate. The original remains available to browse.')
      : t('未能自动定位这段关联原文；原件仍可浏览。可继续翻页，或在页面上选词核对。','This linked passage could not be located automatically. You can still browse, change pages or select words on the original.')
  }
}
function capturePage(value:SourcePageSelection){
  if(busy.value||!reading.value||value.source!==pdfUrl.value)return
  selection.value=undefined;selectionError.value='';pageSelection.value=value;manualParagraphId.value=''
  if(!value.words.length)return
  try{selection.value=bindPageSelection(reading.value,value)}catch(caught){selectionError.value=caught instanceof Error?caught.message:String(caught)}
}
function selectFamily(family:string){
  const sources=head.value.sources.filter(source=>source.fileKey===family)
  const source=sources.find(item=>String(item.id)===String(graph.value.selectedDocuments[family]))??sources[0]
  if(source&&(String(source.id)!==sourceId.value||!reading.value))void selectSource(String(source.id))
}
function useSelectedEdition(){
  if(!protect()||splitDirty.value||!reading.value?.fileKey||!ruleSource.value)return
  graph.value={...graph.value,selectedDocuments:{...graph.value.selectedDocuments,[reading.value.fileKey]:String(reading.value.id)}};changed()
}

async function selectSource(id: string, restoring = false) {
  if (!restoring && !protect()) return
  if (pdfUrl.value) URL.revokeObjectURL(pdfUrl.value); pdfUrl.value = '';
  originalLoading.value = false; originalError.value = ''
  const project = props.projectId, token = ++sourceGeneration;sourceLoading.value=true; sourceId.value = id; selection.value = undefined;selectionError.value='';focusText.value=''; reading.value = undefined
  try { const result = await draftingApi.reviewReading(project, id); if (!alive || project !== props.projectId || token !== sourceGeneration) return; const expected = head.value.sources.find(item => String(item.id) === id); if (!expected || ['sourceSha256', 'analysisRevision', 'analysisInputSha256', 'analysisSha256', 'evidenceStatus'].some(key => result[key] !== expected[key])) throw Error(t('原件版本已变化，请刷新。', 'Source revision changed. Reload it.')); reading.value = { ...result, blocks: Array.isArray(result.blocks) ? result.blocks : (result.blocks as ReviewObject)?.blocks ?? [] }; void openOriginal() } catch (caught) { if (alive && project === props.projectId && token === sourceGeneration) error.value = caught instanceof Error ? caught.message : String(caught) } finally { if(token===sourceGeneration)sourceLoading.value=false }
}
async function locateSource(id: string, expectedSha?: string | null) {
  await loading
  const source = head.value.sources.find(item => String(item.id) === id)
  if (expectedSha && source?.sourceSha256 !== expectedSha) { error.value = t('候选引用的原件已变化；请重新识别，不能用当前原件冒充旧证据。', 'The candidate source changed. Re-extract; the current source cannot stand in for the old evidence.'); return }
  await selectSource(id)
}
function options() { return String(form.optionsText).split('\n').filter(line => line.trim()).map((line, index) => { const [zh, en] = line.split('|').map(value => value.trim()); const old = selected.value?.options.find(value => value.labelZh === zh && value.labelEn === (en || zh)); return { value: old?.value ?? 'option-' + selectedId.value + '-' + index, labelZh: zh, labelEn: en || zh } }) }
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
    for (const language of ['Zh', 'En']) if (form['name' + language] !== base['name' + language]) { values['name' + language] = form['name' + language]; values['question' + language] = form['name' + language] }
    if (form.kind !== base.kind) values.type = { kind: form.kind, zh: kinds[form.kind], en: form.kind }
    if (form.role !== base.role) values.role = { kind: form.role, zh: roles[form.role], en: form.role }
    if (form.parentId !== base.parentId) values.parentId = form.parentId || null
    if (form.fixedValue !== base.fixedValue) values.fixedValue = form.fixedValue
    if (form.optionsText !== base.optionsText) values.options = options()
    const actions = [...(Object.keys(values).length ? [{ type: 'EDIT', id: selectedId.value, values }] : []),
      ...(selected.value.correction?.kind === 'split' && form.splitMode !== selected.value.correction.mode ? [{ type: 'RULE', id: selectedId.value, correction: { ...copy(selected.value.correction), mode: form.splitMode, needsRebind: form.splitMode === 'unknown' } }] : []),
      ...(JSON.stringify(form.branches) !== JSON.stringify(selected.value.correction?.kind === 'rule' ? selected.value.correction.branches ?? [] : []) ? [{ type: 'RULE', id: selectedId.value, correction: form.branches.length ? { kind: 'rule', branches: copy(form.branches), ...(selected.value.correction?.supersededCorrection ? { supersededCorrection: copy(selected.value.correction.supersededCorrection) } : {}), otherwise: 'needs_review' } : null }] : []),
      { type: 'TARGETS', id: selectedId.value, targets }]
    const reducer = createReviewReducer({ variables: state.value.variables, sourceUnits: [], sources: graph.value.sources })
    let next = state.value
    for (const action of actions) { const result = reducer.reduce(next, { ...action, at: new Date().toISOString() }); if (result === next) throw Error(t('问题的条件或来源版本不相容。', 'Conditions or source editions are incompatible.')); next = result }
    undo.value = [...undo.value.slice(-49), state.value]; graph.value = { ...graph.value, graph: next }; changed(); resetForm(); error.value = ''
  } catch (caught) { error.value = caught instanceof Error ? caught.message : String(caught) }
}
function addQuestion() { if (!protect() || !ruleSource.value || !selection.value) return; try { apply({ type: 'ADD', docId: selection.value.docId, anchor: selection.value, values: { nameZh: t('新问题', 'New question'), nameEn: 'New question', type: { kind: 'boolean', zh: '是／否', en: 'Yes / No' }, role: { kind: 'input', zh: '项目资料', en: 'Project information' } }, reason: '人工从原文补充 / Added from the source' }); selectedId.value = state.value.variables.at(-1)!.id; resetForm();editingQuestion.value=true } catch (caught) { error.value = String(caught) } }
function linkSource() { if (!protect() || !ruleSource.value || !selection.value || !selected.value) return; try { apply({ type: 'LINK', id: selectedId.value, anchor: selection.value }); resetForm() } catch (caught) { error.value = String(caught) } }
function appendTarget(index: number) { if (busy.value || !selection.value?.native) return; try { form.targets[index] = appendNativeSelection(form.targets[index], selection.value); error.value = '' } catch (caught) { error.value = caught instanceof Error ? caught.message : String(caught) } }
function addTarget() { if (busy.value) return; if (!ruleSource.value || !selection.value || !selected.value) return; editingQuestion.value=true;form.targets.push({ ...copy(selection.value), action: selection.value.native?'fill':'check', valueMode: 'single', valueSourceId: isReviewCheck(selected.value) ? '' : selectedId.value, branchId: '', prefix: '', suffix: '' }) }
function addBranch() { if (busy.value) return; form.branches.push({ id: 'case-' + Date.now().toString(36), mode: 'all', scopeId: '', scopeMode: '', clauses: [], effectZh: '', effectEn: '' }) }
function split() { if (busy.value) return; try { if (!selected.value || !splitFirst.value || !splitSecond.value || ![splitNames.firstZh, splitNames.firstEn, splitNames.secondZh, splitNames.secondEn].every(value => value.trim())) throw Error(t('请分别选取两项原文并填写名称。', 'Select both source passages and supply both names.')); apply({ type: 'SPLIT', id: selectedId.value, mode: splitNames.mode, items: [{ nameZh: splitNames.firstZh, nameEn: splitNames.firstEn, sourceAnchors: [splitFirst.value] }, { nameZh: splitNames.secondZh, nameEn: splitNames.secondEn, sourceAnchors: [splitSecond.value] }] }); cancelSplit(); selectedId.value = state.value.variables.find(item => item.originId === selectedId.value && item.status !== 'excluded')!.id; resetForm() } catch (caught) { error.value = caught instanceof Error ? caught.message : String(caught) } }
function removeIsolatedRule() { if (!protect() || !isolatedRule.value) return; try { apply({ type: 'RULE', id: selectedId.value, correction: null }); resetForm(); error.value = '' } catch (caught) { error.value = caught instanceof Error ? caught.message : String(caught) } }
function reviewItem() { if (!protect() || !selected.value) return; const problem = reviewProblem(selected.value, state.value); if (problem) { error.value = problem; return }; apply({ type: 'REVIEW', id: selectedId.value }); resetForm() }
function snapshot(project = props.projectId) {
  if (!project) return
  if (dirty.value || formDirty.value || splitDirty.value || checkedIds.value.length) reviewDraftCache.set(project, { graph: copy(graph.value), head: copy(head.value), dirty: dirty.value, selectedId: selectedId.value, sourceId: sourceId.value, form: copy(form), formBase: formBase.value, formOpen: formOpen.value, split: { names: copy(splitNames), first: copy(splitFirst.value ?? null) ?? undefined, second: copy(splitSecond.value ?? null) ?? undefined }, batch: { ids: [...checkedIds.value], open: batchOpen.value, draft: batchDraft.value ? copy(batchDraft.value) : undefined } })
  else if (!busy.value) reviewDraftCache.delete(project)
}
async function refresh(preserve = true) {
  const project = props.projectId, token = ++generation; saving.value = false; if (!project) return; busy.value = true; error.value = ''
  try { const result = await draftingApi.reviewHead(project); if (!alive || project !== props.projectId || token !== generation) return; if (!Array.isArray(result?.sources)) throw Error(t('模板核对状态返回不完整，请重试读取。', 'Template review state is incomplete. Retry loading it.')); head.value = result; if (result.draft ?? result.current) emit('saved', (result.draft ?? result.current)!); const cached = preserve ? reviewDraftCache.get(project) : undefined; if (cached) { graph.value = copy(cached.graph); dirty.value = cached.dirty; selectedId.value = cached.selectedId; Object.assign(form, copy(cached.form)); formBase.value = cached.formBase; formOpen.value = cached.formOpen; editingQuestion.value = JSON.stringify(cached.form) !== cached.formBase; if (cached.split) { Object.assign(splitNames, cached.split.names); splitFirst.value = cached.split.first; splitSecond.value = cached.split.second }; if (cached.batch) { checkedIds.value = cached.batch.ids; batchDraft.value = cached.batch.draft; batchOpen.value = cached.batch.open }; if (cached.head.currentRevisionId !== result.currentRevisionId || cached.head.draftRevisionId !== result.draftRevisionId) error.value = t('已恢复未保存调整；服务器版本已变化，保存时需核对冲突。', 'Unsaved work restored; the server revision changed. Resolve the conflict before saving.'); head.value = { ...result, currentRevisionId: cached.head.currentRevisionId, draftRevisionId: cached.head.draftRevisionId }; await selectSource(cached.sourceId, true); return }; graph.value = result.draft?.graph ?? result.current?.graph ?? { kind: 'consense-template-graph-v1', graph: emptyReviewState(), sources: result.sources, selectedDocuments: {} }; selectedId.value = ''; formOpen.value = false; dirty.value = false; emit('dirty', false); undo.value = []; if (result.sources.length) await selectSource(String(result.sources[0]!.id), true) } catch (caught) { error.value = caught instanceof Error ? caught.message : String(caught) } finally { if (token === generation) busy.value = false }
}
async function save(): Promise<ReviewRevision | undefined> {
  if (splitDirty.value) { error.value = t('请先完成或取消拆分，再保存。', 'Complete or cancel the split before saving.'); return }
  if (props.locked) throw Error(t('请先完成候选调整或当前操作。', 'Complete the candidate edit or current operation first.'))
  if (!protect() || !props.projectId) return
  const project = props.projectId, token = generation; busy.value = true; saving.value = true; error.value = ''
  try { const result = await draftingApi.reviewSave(project, graph.value, head.value.currentRevisionId, head.value.draftRevisionId); if (!alive || project !== props.projectId || token !== generation) return; head.value = { ...head.value, draftRevisionId: result.id, draft: result }; graph.value = result.graph; dirty.value = false; reviewDraftCache.delete(project); emit('dirty', false); resetForm(); emit('saved', result); return result } catch (caught) { if (alive && project === props.projectId && token === generation) error.value = caught instanceof Error ? caught.message : String(caught) } finally { if (token === generation) { busy.value = false; saving.value = false } }
}
async function prepareCandidates(rebuild = false) {
  await loading
  if (splitDirty.value) throw Error(t('请先完成或取消拆分。', 'Complete or cancel the split first.'))
  if (!protect()) throw Error(error.value)
  if (invalidTemplateSources.value.length) throw Error(t('模板缺少当前有效来源证据。请先在来源步骤替换或重新上传，再建立核对版本。', 'Templates need current bound source evidence. Replace or re-upload them in Sources before preparing a review.'))
  if (!rebuild && (head.value.draft ?? head.value.current)?.sourceStale) throw Error(t('当前核对版本引用的是旧来源证据。请按现有原件新建核对版本；历史记录继续保留。', 'This review references old source evidence. Start a new review from current sources; history is retained.'))
  if (!rebuild && state.value.variables.length) { if (dirty.value || !head.value.draft) return await save(); return head.value.draft }
  if (dirty.value) throw Error(t('先保存现有模板核对草稿，避免覆盖。', 'Save the current review before rebuilding candidates.'))
  busy.value = true; const project = props.projectId, token = generation
  try { const result = await draftingApi.reviewBootstrap(project); if (!alive || project !== props.projectId || token !== generation) return; graph.value = result.graph; head.value = { ...head.value, sources: [...head.value.sources.filter(source => !result.sources.some(item => String(item.id) === String(source.id))), ...result.sources] }; selectedId.value = ''; formOpen.value = false; changed(); busy.value = false; return await save() } finally { if (token === generation) busy.value = false }
}
async function confirm() { if (head.value.draft?.sourceStale || invalidTemplateSources.value.length || splitDirty.value || props.locked || dirty.value || formDirty.value || pending.value || !state.value.variables.some(item => item.status !== 'excluded') || !head.value.draftRevisionId) return; const project = props.projectId, token = generation; busy.value = true; try { await draftingApi.reviewConfirm(project, head.value.draftRevisionId, head.value.currentRevisionId); if (!alive || project !== props.projectId || token !== generation) return; await refresh(); emit('confirmed') } catch (caught) { error.value = caught instanceof Error ? caught.message : String(caught) } finally { if (project === props.projectId) busy.value = false } }
async function openHistory() { historyOpen.value = true; const project = props.projectId, token = generation; try { const incoming = await draftingApi.reviewRevisions(project); if (alive && project === props.projectId && token === generation) history.value = incoming } catch (caught) { if (project === props.projectId && token === generation) error.value = String(caught) } }
async function restoreRevision(revision: ReviewRevisionSummary) {
  if (busy.value || props.locked || !protect() || dirty.value) { error.value = t('请先保存当前草稿和候选调整。', 'Save the current draft and candidate edits first.'); return }
  const project = props.projectId, token = generation; busy.value = true
  try {
    const detail = await draftingApi.reviewRevision(project, revision.id)
    if (!alive || project !== props.projectId || token !== generation) return
    if (detail.id !== revision.id || detail.sourceStale || detail.graph?.kind !== 'consense-template-graph-v1') throw Error(t('此历史版本的来源已变化或内容不完整，不能恢复为当前草稿。', 'The historical source changed or its graph is incomplete. It cannot be restored as the current draft.'))
    graph.value = copy(detail.graph); changed(); selectedId.value = ''; formOpen.value = false; historyOpen.value = false
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
      catch{if(current())locationNotice.value=t('原件定位数据暂不可用，仍可浏览文件；请稍后重新读取原件。','Source locations are temporarily unavailable. You can still browse; reopen the original to retry.')}
    }
    if(!current())return
    const blob=source.format==='docx'?await draftingApi.reviewPreview(project,String(source.id),source.sourceSha256):await draftingApi.reviewSource(project,String(source.id),source.sourceSha256)
    if(!current())return
    if(bindings){
      const hash=await pdfSha256(blob)
      if(!current())return
      if(bindings.sourceSha256===source.sourceSha256&&matchesBindingPdf(bindings,String(source.id),'source',hash))sourceBindings.value=bindings
      else locationNotice.value=t('定位数据与当前预览版本不一致，已停用定位；原件仍可浏览。请重新读取原件。','Locations do not match this preview and were disabled. The original remains readable; reopen it to retry.')
    }
    pdfUrl.value=URL.createObjectURL(blob)
  }catch(caught){if(current())originalError.value=caught instanceof Error?caught.message:String(caught)}
  finally{if(current())originalLoading.value=false}
}
watch(() => props.projectId, (_project, previous) => { if (previous) snapshot(previous); sourceGeneration++; if (pdfUrl.value) URL.revokeObjectURL(pdfUrl.value); pdfUrl.value = ''; originalLoading.value = false; originalError.value = ''; historyOpen.value = false; showGraph.value = false; batchOpen.value = false; checkedIds.value = []; batchDraft.value = undefined; cancelSplit(); reading.value = undefined; selection.value = undefined; sourceLoading.value=false; dirty.value = false; formOpen.value = false; loading = refresh() }, { immediate: true })
watch(showGraph, open => { if (open) { graphSelected.value = selectedId.value; graphOnlySelected.value = false } })
watch([dirty, formDirty, splitDirty], () => emit('dirty', dirty.value || formDirty.value || splitDirty.value))
onBeforeUnmount(() => { snapshot(); alive = false; generation++; sourceGeneration++; if (pdfUrl.value) URL.revokeObjectURL(pdfUrl.value) })
async function locateQuestion(id: string, revisionId: string) {
  if (revisionId !== (head.value.draftRevisionId || head.value.currentRevisionId) || !state.value.variables.some(item => item.id === id && item.status !== 'excluded')) { error.value = t('候选对应的问题版本已变化或已退出，请返回第三步重新核对归属。', 'The candidate question revision changed or was retired. Return to step 3 and review its assignment.'); return }
  if (!protect()) return
  search.value = ''; await choose(id)
}
defineExpose({ prepareCandidates, refresh, save, selectSource, locateSource, locateQuestion, ready: () => loading, currentRevisionId: () => head.value.currentRevisionId })
</script>

<template>
  <section class="source-review" :class="{ 'source-review--immersive': immersive }" data-source-review @keydown="workspaceKeydown"><fieldset class="review-controls" data-review-controls :disabled="busy">
    <header class="review-heading"><div><h3>{{ t('在原件上核对规则', 'Review rules on the source') }}</h3><p>{{ t('模板规则确认与本项目事实采用分别保存。未知范围仍需核对。', 'Template confirmation and project-fact adoption are separate. Unresolved scope stays under review.') }}</p></div><div class="review-actions"><button class="btn" :disabled="busy" @click="openHistory">{{ t('图谱版本', 'Graph versions') }}</button><button class="btn" @click="showGraph = true">{{ t('查看关系', 'View relationships') }}</button><button class="btn" :disabled="busy || !undo.length || formDirty" @click="graph = { ...graph, graph: undo.pop()! }; changed(); resetForm()">{{ t('撤销', 'Undo') }}</button><button class="btn primary" data-review-save :disabled="busy || locked || !dirty || formDirty || splitDirty" @click="save">{{ saving ? t('正在保存草稿…', 'Saving draft…') : t('保存核对草稿', 'Save review draft') }}</button><button class="btn" data-review-confirm :disabled="busy || locked || dirty || formDirty || splitDirty || pending > 0 || !state.variables.length || !head.draftRevisionId || head.draft?.sourceStale || invalidTemplateSources.length > 0" @click="confirm">{{ t('确认此版模板用于起草', 'Confirm this template for drafting') }}</button></div></header>
    <p v-if="busy" role="status">{{ t('正在读取或保存…', 'Reading or saving…') }}</p><p v-if="error" class="review-error" role="alert">{{ error }}</p><p class="review-status">{{ businessQuestions }} {{ t('个业务问题', 'business questions') }} · {{ sourceChecks }} {{ t('项核对事项', 'review checks') }} · {{ pending }} {{ t('项未确认', 'unconfirmed') }} · {{ dirty || formDirty || splitDirty ? t('有未保存调整', 'Unsaved changes') : t('草稿与确认版本分别保留', 'Draft and confirmed revisions are kept separately') }}</p>
    <div v-if="pending" class="source-note" data-review-next-step><p>{{ t('逐项核对问题和影响范围，保存核对草稿。未决修改保留原文，可导出明确标记的未完成草稿；项目答案仍需在下一步采用。', 'Review questions and their affected passages, then save the draft. Unresolved changes retain their original wording in an explicitly marked incomplete draft. Project answers are not adopted automatically.') }}</p><button class="btn" data-review-next-pending :disabled="busy || formDirty || splitDirty || !nextPending" @click="nextPending && choose(nextPending.id)">{{ t('继续核对下一项', 'Review next unfinished item') }}</button><p v-if="formDirty || splitDirty">{{ t('请先保存当前问题的调整，或完成／取消拆分，再切换下一项。', 'Save the current question changes, or finish/cancel splitting, before continuing.') }}</p></div>
    <div class="source-choice" data-source-edition-toolbar>
      <nav class="source-tabs" :aria-label="t('目标原件','Source documents')"><button v-for="family in ['NTT','SCT','SCC']" :key="family" class="btn" :aria-pressed="selectedSource?.fileKey===family" :disabled="busy||formDirty||splitDirty||!head.sources.some(source=>source.fileKey===family)" @click="selectFamily(family)">{{ family }}</button></nav>
      <label>{{ t('正在核对的原件','Source being reviewed') }}<select :value="sourceId" :disabled="busy||formDirty||splitDirty" @change="selectSource(($event.target as HTMLSelectElement).value)"><option v-for="source in head.sources" :key="source.id" :value="String(source.id)">{{ source.fileName }}</option></select></label>
      <span v-if="reading?.fileKey && String(graph.selectedDocuments[reading.fileKey])===String(reading.id)" class="edition-status">{{ t('本次起草采用此版','Selected drafting edition') }}</span>
      <button v-else-if="reading?.fileKey && ruleSource" class="btn" :disabled="formDirty||splitDirty" data-review-select-edition @click="useSelectedEdition">{{ t('将此版设为本次起草版本','Select this edition for drafting') }}</button>
    </div>
    <p v-if="(head.draft ?? head.current)?.sourceStale" class="review-error" data-source-review-stale role="status">{{ t('此核对版本引用了旧来源证据。请返回“识别”并按现有原件新建核对版本；保存的历史记录保留。', 'This review references old source evidence. Return to Identify and start a new review from current sources. Saved history is retained.') }}</p>
    <p v-if="selectedSource" class="review-status" data-source-evidence-status role="status">{{ sourceEvidenceLabel(selectedSource) }}</p>
    <div v-if="invalidTemplateSources.length" class="review-error" data-source-evidence-block role="alert"><p>{{ t('这些模板没有当前有效的来源证据；原件未变化也可能是分析代次已过期。请先替换或重新上传，再建立并核对新版本。', 'These templates lack current bound evidence. An unchanged original can still have an expired analysis revision. Replace or re-upload them, then prepare and review a new version.') }}</p><ul><li v-for="source in invalidTemplateSources" :key="source.id">{{ source.fileKey }} · {{ source.fileName }} · {{ sourceEvidenceLabel(source) }}</li></ul><button type="button" class="btn" data-source-replace @click="emit('replace')">{{ t('到来源步骤替换／重传', 'Replace or re-upload in Sources') }}</button></div>
    <p v-if="selectedSource?.format === 'pdf'" class="source-note" data-source-pdf-limit>{{ t('这份 PDF 可阅读和核对来源；生成保留原格式的 Word 需要所选版次的可编辑 DOCX，不能借另一版 Word。', 'This PDF supports source reading and review. Word generation needs an editable DOCX of the selected edition; another Word edition cannot be substituted.') }}</p>
    <p v-if="state.discoverySummary" class="review-status">{{ t('本次模板识别', 'This template discovery run') }} · {{ t('已尝试', 'Attempted') }} {{ state.discoverySummary.attempted }} · {{ t('成功返回', 'Returned') }} {{ state.discoverySummary.succeeded }} · {{ t('失败', 'Failed') }} {{ state.discoverySummary.failed }} · {{ t('预算外未执行', 'Outside budget') }} {{ state.discoverySummary.skippedBudget }}. {{ t('再次点击只识别尚未尝试的要求；失败记录保留，不会自动重试。', 'A later run processes unattempted requirements. Failures remain recorded and are not retried automatically.') }}</p>
    <p v-if="sourceLoading" role="status">{{ t('正在读取所选原件与核对位置…','Reading the selected source and review positions…') }}</p>
    <div v-if="reading || immersive" :style="immersive ? { height: workspaceHeight + 'px' } : undefined">
    <section ref="workspace" class="source-workspace" :class="{ 'source-workspace--immersive': immersive }" data-source-workspace :role="immersive ? 'dialog' : undefined" :aria-modal="immersive || undefined" :aria-label="t('原件核对工作区','Original document review')" tabindex="-1">
      <header class="source-workspace-toolbar"><strong>{{ reading?.fileName || selectedSource?.fileName }}</strong><button ref="immersiveToggle" type="button" class="btn" data-source-immersive :aria-expanded="immersive" @click="setImmersive(!immersive)">{{ immersive ? t('退出沉浸式预览（Esc）','Exit immersive preview (Esc)') : t('沉浸式预览','Immersive preview') }}</button></header>
      <p v-if="immersive && error" class="review-error" role="alert">{{ error }}</p>
      <p v-if="immersive && busy" role="status">{{ t('正在读取或保存…','Reading or saving…') }}</p>
      <p v-if="immersive && sourceLoading" role="status">{{ t('正在读取所选原件与核对位置…','Reading the selected source and review positions…') }}</p>
    <div v-if="reading" class="review-columns">
      <section class="original-source" data-source-page-review>
        <p v-if="locationNotice" class="source-note location-notice" data-review-location-notice role="status">{{ locationNotice }}</p>
        <button v-if="reading.format==='docx'&&pdfUrl&&!originalLoading&&!sourceBindings" type="button" class="btn" @click="openOriginal">{{ t('重新读取原件定位','Reload source locations') }}</button>
        <p class="source-note">{{ t('在原版页面上框选文字；点击可补选或取消单词。选词后补充／关联问题，或指定修改范围。','Select words on the original page. Click words to refine the selection, then add/link a question or identify the affected passage.') }}</p>
        <section v-if="reading.issues?.length||reading.markerCandidates?.some(marker=>marker.disposition==='unknown')" data-source-unresolved class="source-note"><strong>{{ t('原件仍有待核对内容','Source requires review') }}</strong><p>{{ t('未知指导范围和修改符号不能直接授权自动修改，请结合当前问题核对。','Unknown instruction and marker scopes cannot yet authorize automatic edits. Review them with the current question.') }}</p><p v-for="(issue,index) in reading.issues.slice(0,2)" :key="index">{{ sourceIssueMessage(issue) }}</p><blockquote v-for="marker in reading.markerCandidates.filter(marker=>marker.disposition==='unknown').slice(0,2)" :key="marker.id">{{ sourceExcerpt(marker.localSentenceObservation?.text||marker.symbol) }}</blockquote><p v-if="reading.issues.length>2">{{ t('其余事项请结合当前问题及关联原文核对。','Review remaining items with the current question and its source.') }}</p></section>
        <p v-if="originalLoading" role="status">{{ t('正在打开原版文件…','Opening the original document…') }}</p>
        <p v-if="originalError" role="alert" class="review-error">{{ originalError }}</p>
        <button v-if="!pdfUrl&&!originalLoading" class="btn" data-review-original @click="openOriginal">{{ t('重新打开原件','Retry original document') }}</button>
        <nav v-if="sourceLocations.length>1" class="review-actions source-note" :aria-label="t('关联原文位置','Linked source positions')"><button type="button" class="btn" :disabled="locationIndex<=0" @click="moveLocation(-1)">{{ t('上一处','Previous passage') }}</button><span>{{ t('关联原文','Linked source') }} {{ locationIndex+1 }} / {{ sourceLocations.length }} · {{ t('第','Page') }} {{ sourceLocations[locationIndex]?.geometry.pageNumber }} {{ t('页','') }}</span><button type="button" class="btn" :disabled="locationIndex>=sourceLocations.length-1" @click="moveLocation(1)">{{ t('下一处','Next passage') }}</button></nav>
        <DraftingPdfPreview v-if="pdfUrl" :source="pdfUrl" :title="reading.fileName" :locale="locale as any" :immersive="immersive" :select-words="true" :focus-text="focusText" :focus-request="focusRequest" :locations="sourceLocations" :selected-binding-id="selectedBindingId" :location-request="focusRequest" @selection="capturePage" @focus-unavailable="locationUnavailable" @location-unavailable="locationNotice=t('此位置未能在当前预览中定位，原件仍可浏览；请重新读取原件。','This position is unavailable in the current preview. You can still browse; reopen the original to retry.')">
          <template #selection="{clear}"><p v-if="selectionError" class="review-error" role="alert">{{ selectionError }}</p><template v-if="selection"><blockquote>{{ selection.text }}</blockquote><template v-if="reading.format==='docx'&&!selection.native"><p class="source-note" data-selection-pending role="status">{{ t('选词已保留，可补充问题、关联来源或记录待核对的修改范围。自动定位未完成，不会据此改写文件。','Selection retained. Add/link a question or record a passage for review. The document will not be edited from an unbound selection.') }}</p><template v-if="manualParagraphs.length"><label>{{ t('选择对应的 Word 原段（当前页）','Choose the corresponding Word paragraph on this page') }}<select v-model="manualParagraphId" data-manual-source-paragraph><option value="">{{ t('请选择并核对下方完整原文','Choose and review the full source below') }}</option><option v-for="paragraph in manualParagraphs" :key="paragraph.id" :value="paragraph.id">{{ paragraph.text.slice(0,120) }}</option></select></label><blockquote v-if="manualParagraph">{{ manualParagraph.text }}</blockquote><button type="button" class="btn" data-use-whole-source-paragraph :disabled="!manualParagraph" @click="useWholeParagraph">{{ t('确认改用这整段原文','Use this entire source paragraph') }}</button></template></template><div class="review-actions"><button class="btn" :disabled="!ruleSource||formDirty||splitDirty" @click="addQuestion">{{ t('补充问题','Add a question') }}</button><button class="btn" :disabled="!selected||!ruleSource||formDirty||splitDirty" @click="linkSource">{{ t('关联已有问题','Link a question') }}</button><button class="btn" :disabled="!selected||!ruleSource" @click="addTarget">{{ t('作为修改范围','Use as affected passage') }}</button></div></template><button class="btn" @click="clear">{{ t('清除选词','Clear selection') }}</button></template>
        </DraftingPdfPreview>
        <p v-if="reading.format!=='docx'" class="source-note">{{ t('此处显示原PDF，可关联问题；不能借另一版Word执行修改。','This is the original PDF. Link questions for review; another Word edition does not authorize editing it.') }}</p>
      </section>
      <aside class="review-editor" :class="{'question-open':selected}" data-source-question-drawer>
        <section class="batch-selection" data-review-selection><p>{{ checkedQuestions.length }} {{ t('项已勾选（跨文件保留）','items selected (kept across files)') }}</p><p v-if="checkedQuestions.length">{{ checkedQuestions.map(questionLabel).join('；') }}</p><div class="review-actions"><button class="btn" data-review-open-batch :disabled="busy||formDirty||splitDirty||!checkedQuestions.length" @click="openBatch()">{{ t('查看勾选项与处理方式','Review selected items and treatment') }}</button><button class="btn" :disabled="!checkedIds.length" @click="checkedIds=[];batchDraft=undefined">{{ t('清空勾选','Clear selection') }}</button><button v-if="selected" class="btn" data-review-check-current :aria-pressed="checkedIds.includes(selected.id)" @click="toggleChecked(selected.id)">{{ checkedIds.includes(selected.id)?t('取消勾选当前项','Deselect this item'):t('勾选当前项','Select this item') }}</button></div></section>
        <template v-if="!selected"><h4>{{ t('核对问题','Review questions') }}</h4><input v-model="search" class="question-search" :placeholder="t('搜索问题','Search questions')"><nav class="question-tabs"><button class="btn" :aria-pressed="questionKind==='business'" @click="questionKind='business'">{{ t('业务问题','Business questions') }}</button><button class="btn" :aria-pressed="questionKind==='checks'" @click="questionKind='checks'">{{ t('原文待核','Source checks') }}</button></nav><p v-if="!questionRows.length">{{ t('此原件暂无匹配问题，可在页面上选词补充。','No matching questions for this source. Select words to add one.') }}</p><div v-for="item in shownQuestions" :key="item.id" class="question-select-row"><label><input type="checkbox" :checked="checkedIds.includes(item.id)" :data-review-check="item.id" :aria-label="t('勾选：','Select: ')+questionLabel(item)" @change="toggleChecked(item.id)"></label><button class="question-card" :data-review-question="item.id" @click="choose(item.id)"><strong>{{ questionLabel(item) }}</strong><small>{{ statusLabel(item.status) }}</small></button></div><nav v-if="questionRows.length>8" class="review-actions"><button class="btn" :disabled="questionPage===0" @click="questionPage--">{{ t('上一组','Previous') }}</button><span>{{ questionPage+1 }} / {{ Math.ceil(questionRows.length/8) }}</span><button class="btn" :disabled="(questionPage+1)*8>=questionRows.length" @click="questionPage++">{{ t('下一组','Next') }}</button></nav></template>
        <div v-if="selected" class="question-summary"><button class="btn" :disabled="formDirty||splitDirty" @click="selectedId='';formOpen=false;editingQuestion=false">{{ t('返回问题列表','Back to questions') }}</button><h4>{{ questionLabel(selected) }}</h4><p>{{ statusLabel(selected.status) }}</p><p>{{ t('关联原文','Linked source passages') }} · {{ selected.sourceAnchors.length }}</p><blockquote v-for="(anchor,index) in selected.sourceAnchors" :key="index">{{ anchor.text }}<button class="btn" data-review-locate-source :disabled="busy||formDirty||splitDirty" @click="locateAnchor(anchor)">{{ t('定位这处原文','Locate this passage') }} {{ index+1 }}</button></blockquote><div v-if="!editingQuestion" class="review-actions"><button class="btn primary" data-review-confirm-item :disabled="formDirty" @click="reviewItem">{{ t('该项已核对','Mark reviewed') }}</button><button class="btn" data-review-edit-question @click="editingQuestion=true">{{ t('调整变量与影响范围','Edit question and affected passages') }}</button></div><p v-if="selectedProblem&&!editingQuestion" class="review-error">{{ selectedProblem }}</p></div>
        <div v-if="isolatedRule && formOpen" class="source-note"><p>{{ t('问题及原文已保留，无法确认的规则已隔离。可移除此空规则，保留问题供人工核对；不会增加修改目标或采用项目答案。', 'The question and source are retained; the unresolved rule is isolated. Remove this empty rule to review the question manually. This adds no amendment targets or adopted project answers.') }}</p><button class="btn" data-review-remove-isolated-rule :disabled="busy || formDirty" @click="removeIsolatedRule">{{ t('移除隔离规则，保留问题', 'Remove isolated rule and retain question') }}</button></div>
        <template v-if="selected && formOpen && editingQuestion"><p v-if="selectedProblem && !formDirty" class="review-error" data-review-item-problem>{{ selectedProblem }}</p><div class="review-actions"><button class="btn" data-review-confirm-item :disabled="formDirty" @click="reviewItem">{{ t('该项已核对', 'Mark this item reviewed') }}</button><button class="btn" :disabled="formDirty" @click="splitOpen = true">{{ t('拆为两个问题', 'Split into two questions') }}</button><button class="btn" :disabled="formDirty" @click="apply({type: selected.status === 'excluded' ? 'RESTORE' : 'EXCLUDE', id: selectedId, reason: '人工核对 / Human review'}); resetForm()">{{ selected.status === 'excluded' ? t('恢复', 'Restore') : t('不再提问', 'Exclude') }}</button></div><label v-if="selected.correction?.kind === 'split'">{{ t('拆分后的关系', 'Relationship after splitting') }}<select v-model="form.splitMode"><option value="unknown">{{ t('待核对', 'Needs review') }}</option><option value="all">{{ t('两项同时成立', 'Both hold') }}</option><option value="any">{{ t('任一项成立', 'Either holds') }}</option></select></label><label>{{ t('中文问题', 'Chinese question') }}<input v-model="form.nameZh" data-review-name-zh></label><label>{{ t('英文问题', 'English question') }}<input v-model="form.nameEn" data-review-name-en></label><label>{{ t('怎么回答？', 'Answer shape') }}<select v-model="form.kind"><option v-for="(label, key) in kinds" :key="key" :value="key">{{ label }}</option></select></label><label>{{ t('答案怎样得出？', 'How is the answer obtained?') }}<select v-model="form.role"><option v-for="(label, key) in roles" :key="key" :value="key">{{ label }}</option></select></label><label>{{ t('属于哪条资料／明细？', 'Which record or list owns it?') }}<select v-model="form.parentId"><option value="">{{ t('独立项目资料', 'Project level') }}</option><option v-for="item in parents" :key="item.id" :value="item.id">{{ questionLabel(item) }}</option></select></label><label v-if="['choice','multiselect'].includes(form.kind)">{{ t('选项：每行中文 | English', 'Options: Chinese | English, one per line') }}<textarea v-model="form.optionsText" rows="4" /></label><label v-if="form.role === 'fixed'">{{ t('固定内容', 'Fixed wording') }}<textarea v-model="form.fixedValue" rows="3" /></label>
          <section class="question-section"><h4>{{ t('先满足什么、哪些条件共同决定？', 'Prerequisites and combined conditions') }}</h4><article v-for="(branch, branchIndex) in form.branches" :key="branch.id" class="condition-case"><label>{{ t('本情况', 'Case') }} {{ branchIndex + 1 }}<select v-model="branch.mode"><option value="all">{{ t('同时满足', 'All conditions') }}</option><option value="any">{{ t('任一满足', 'Any condition') }}</option></select></label><label>{{ t('按哪组明细逐项判断？', 'Evaluate within which list?') }}<select v-model="branch.scopeId" @change="branch.scopeMode = branch.scopeId ? 'each' : ''"><option value="">{{ t('整个项目', 'Whole project') }}</option><option v-for="item in parents.filter(value => value.type.kind === 'list')" :key="item.id" :value="item.id">{{ questionLabel(item) }}</option></select></label><select v-if="branch.scopeId" v-model="branch.scopeMode"><option value="each">{{ t('逐项分别判断', 'Evaluate each row') }}</option><option value="any">{{ t('任一条满足', 'Any row matches') }}</option><option value="all">{{ t('所有条均满足', 'All rows match') }}</option><option value="none">{{ t('没有条目满足', 'No rows match') }}</option></select><div v-for="(clause, index) in branch.clauses" :key="index" class="condition-row"><select v-model="clause.inputId" @change="clause.operator = allowedOperators(clause.inputId)[0]; clause.value = ''"><option value="">{{ t('选择输入', 'Choose input') }}</option><option v-for="item in state.variables.filter(value => value.status !== 'excluded')" :key="item.id" :value="item.id">{{ questionLabel(item) }}</option></select><select v-model="clause.operator"><option v-for="operator in allowedOperators(clause.inputId)" :key="operator" :value="operator">{{ operators[operator] }}</option></select><select v-if="optionInput(clause.inputId).length" v-model="clause.value"><option v-for="option in optionInput(clause.inputId)" :key="option.value" :value="option.value">{{ option.labelZh }} / {{ option.labelEn }}</option></select><input v-else-if="['eq','ge','le','gt','lt'].includes(clause.operator)" v-model="clause.value"><button class="btn" @click="branch.clauses.splice(index,1)">×</button></div><button class="btn" @click="branch.clauses.push({inputId:'',operator:'known',value:''})">{{ t('增加条件', 'Add condition') }}</button><label>{{ t('这种情况怎样处理？（中文）', 'Amendment for this case (Chinese)') }}<textarea v-model="branch.effectZh" /></label><label>{{ t('同一处理说明（英文）', 'Amendment for this case (English)') }}<textarea v-model="branch.effectEn" /></label><label v-if="form.role === 'derived'">{{ t('本情况的答案', 'Answer for this case') }}<select v-if="form.kind === 'boolean'" v-model="branch.resultValue"><option :value="true">{{ t('是', 'Yes') }}</option><option :value="false">{{ t('否', 'No') }}</option></select><select v-else-if="form.kind === 'choice'" v-model="branch.resultValue"><option v-for="option in options()" :key="option.value" :value="option.value">{{ option.labelZh }} / {{ option.labelEn }}</option></select><input v-else v-model="branch.resultValue"></label><button class="btn" @click="form.branches.splice(branchIndex,1)">{{ t('移除此情况', 'Remove case') }}</button></article><button class="btn" @click="addBranch">{{ t('增加一种情况', 'Add a case') }}</button></section>
          <section class="question-section"><h4>{{ t('改哪段、怎样改？', 'Which passage changes, and how?') }}</h4><article v-for="(target, index) in form.targets" :key="index" class="target-row"><blockquote>{{ target.text }}</blockquote><p v-if="target.nativeBinding && target.nativeBinding.status !== 'bound'" class="review-error">{{ targetBindingMessage(target) }}</p><select :value="target.action" :data-target-action="index" @change="setTargetAction(target, ($event.target as HTMLSelectElement).value)"><option v-for="(label, action) in actions" :key="action" :value="action" :disabled="action === 'insert' && !(targetSpans(target).length === 1 && targetSpans(target)[0]?.operation === 'insert')">{{ label }}</option></select><label>{{ t('适用于哪种情况？', 'Which case applies?') }}<select v-model="target.branchId"><option value="">{{ t('无条件', 'Unconditional') }}</option><option v-for="(branch, i) in form.branches" :key="branch.id" :value="branch.id">{{ i + 1 }} · {{ branch.effectZh }}</option></select></label><fieldset v-if="targetScope(target)" class="item-target"><legend>{{ t('这段原文对应哪一条明细？', 'Which list item does this source passage belong to?') }}</legend><label>{{ t('明细标记（若已知）', 'Item marker (if known)') }}<input v-model="target.itemId" placeholder="Item marker"></label><template v-if="!target.itemId"><label>{{ t('或按这项资料寻找同一条明细', 'Or match this detail within the same item') }}<select v-model="target.itemKey"><option value="">{{ t('选择识别资料', 'Choose identifying detail') }}</option><option v-for="key in itemKeys(target)" :key="key.id" :value="key.id">{{ questionLabel(key) }}</option></select></label><label v-if="target.itemKey">{{ t('这一条明细的准确值', 'Exact value for this item') }}<select v-if="state.variables.find(item => item.id === target.itemKey)?.type.kind === 'boolean'" v-model="target.itemValue"><option :value="true">{{ t('是', 'Yes') }}</option><option :value="false">{{ t('否', 'No') }}</option></select><select v-else-if="optionInput(target.itemKey).length" v-model="target.itemValue"><option v-for="option in optionInput(target.itemKey)" :key="option.value" :value="option.value">{{ option.labelZh }} / {{ option.labelEn }}</option></select><input v-else v-model="target.itemValue"></label></template><p class="source-note">{{ t('必须能唯一对应一条明细；其他条目的答案不会用于此段原文。', 'The match must identify one item. Answers from other items do not apply to this passage.') }}</p></fieldset><label v-if="['fill','replace'].includes(target.action) && targetSpans(target).length > 1">{{ t('新文字放在哪个选区？其余已选区将清空。', 'Which span receives the new text? Other selected spans will be cleared.') }}<select :value="target.replacementPlacement?.spanIndex ?? ''" :data-target-placement="index" @change="target.replacementPlacement = ($event.target as HTMLSelectElement).value === '' ? undefined : { spanIndex: Number(($event.target as HTMLSelectElement).value) }"><option value="">{{ t('请明确选择', 'Choose explicitly') }}</option><option v-for="(span,spanIndex) in targetSpans(target)" :key="spanIndex" :value="spanIndex">{{ spanIndex + 1 }} · {{ span.expectedText }}</option></select></label><p v-if="target.action === 'insert'" class="source-note">{{ t('只在来源指定的插入点增加文字；清单条数和内容需明确采用项目资料。', 'Insert only at the source anchor. List size and contents require explicit adoption of project facts.') }}</p><template v-if="['fill','replace','insert'].includes(target.action)"><label>{{ t('文字来自哪个答案？', 'Which answer supplies the text?') }}<select v-model="target.valueSourceId" data-target-answer-source><option value="">{{ t('固定替换文字', 'Fixed replacement') }}</option><option v-if="target.valueSourceId && !answerSources.some(item => item.id === target.valueSourceId)" :value="target.valueSourceId" disabled>{{ t('当前答案来源需重新核对', 'Review answer source') }}</option><option v-for="item in answerSources" :key="item.id" :value="item.id">{{ questionLabel(item) }}</option></select></label><template v-if="target.valueSourceId"><select v-model="target.valueMode"><option value="single">{{ t('一个答案', 'One answer') }}</option><option value="join">{{ t('串联逐项答案', 'Join item answers') }}</option></select><template v-if="target.valueMode === 'join'"><select v-model="target.listId"><option v-for="item in parents.filter(value => value.type.kind === 'list')" :key="item.id" :value="item.id">{{ questionLabel(item) }}</option></select><label>{{ t('分隔文字', 'Exact separator') }}<input v-model="target.separator"></label></template><label>{{ t('答案前文字', 'Exact prefix') }}<textarea v-model="target.prefix" /></label><label>{{ t('答案后文字', 'Exact suffix') }}<textarea v-model="target.suffix" /></label></template><template v-else><label>{{ t('固定文字（中文）', 'Fixed wording (Chinese)') }}<textarea v-model="target.replacementZh" /></label><label>{{ t('固定文字（英文）', 'Fixed wording (English)') }}<textarea v-model="target.replacementEn" /></label></template></template><button class="btn" :disabled="!selection?.native || busy || target.action === 'insert'" :data-target-append="index" @click="appendTarget(index)">{{ t('把当前选区追加到此修改位置', 'Append current selection to this amendment target') }}</button><ol v-if="targetSpans(target).length > 1"><li v-for="(span,spanIndex) in targetSpans(target)" :key="spanIndex"><blockquote>{{ span.expectedText }}</blockquote></li></ol><button class="btn" @click="form.targets.splice(index,1)">{{ t('移除此目标', 'Remove target') }}</button></article><p class="source-note">{{ t('先在左边选词，再点“作为修改目标”。跨段目标分别保留，不扩成首尾整段。', 'Select words on the left and use them as an amendment target. Multiple spans remain distinct.') }}</p></section>
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
                <blockquote>{{ member.text?.trim() ? member.text : member.text === undefined ? t('原文段落不可用，请核对来源。', 'Source paragraph unavailable; check the source.') : t('（空白段落或无文字位置）','(Blank paragraph or non-text position)') }}</blockquote>
                <button class="btn" :data-review-locate-scope="member.id" :disabled="busy || formDirty || !member.canLocate" @click="locateParagraph(member.paragraphId)">{{ member.canLocate ? t('定位此段并选词', 'Locate paragraph to select words') : t('无文字可自动定位','No text available to locate') }}</button>
              </div>
            </article>
          </section>
          <section class="question-section"><h4>{{ t('重复问题或同一问题的分支', 'Duplicate questions or branches of one question') }}</h4><p class="source-note">{{ t('先勾选要对照的条目，再分别处理重复提问与不同答案路线。不会把来源核对项直接变成业务答案。', 'Select items for comparison, then distinguish duplicate questions from alternative answer routes. Source checks retain their purpose.') }}</p><button class="btn" data-review-merge :disabled="formDirty||splitDirty" @click="openBatch(true)">{{ t('勾选当前项并查看对照', 'Select this item and compare') }}</button></section><section class="question-section"><h4>{{ t('已关联原文', 'Linked source passages') }}</h4><article v-for="(anchor,index) in selected.sourceAnchors" :key="index"><blockquote>{{ anchor.text }}</blockquote><button class="btn" data-review-locate-anchor :disabled="busy || formDirty" @click="locateAnchor(anchor)">{{ t('定位这段原文', 'Locate this passage') }}</button><button class="btn" :disabled="formDirty" @click="apply({type:'UNLINK',id:selectedId,index}); resetForm()">{{ t('移除错误关联', 'Remove incorrect link') }}</button></article></section><footer class="review-actions"><button class="btn" @click="resetForm()">{{ t('取消当前调整', 'Cancel edit') }}</button><button class="btn primary" data-review-save-form :disabled="!formDirty" @click="saveForm">{{ t('记录问题调整', 'Keep question changes') }}</button><button class="btn primary" data-review-save-bottom :disabled="busy || locked || !dirty || formDirty || splitDirty" @click="save">{{ saving ? t('正在保存草稿…', 'Saving draft…') : t('保存核对草稿', 'Save review draft') }}</button><p class="source-note" role="status">{{ saving ? t('正在保存，请等待服务器确认；尚未确认模板或采用答案。', 'Saving; waiting for server confirmation. Template and answers are not adopted.') : formDirty ? t('先记录当前问题调整，再保存草稿。', 'Keep the current question changes, then save the draft.') : dirty ? t('调整已记录，尚未保存。保存草稿不会确认模板或采用项目答案。', 'Changes are recorded but not saved. Saving does not confirm the template or adopt project answers.') : t('当前没有未保存调整。', 'There are no unsaved changes.') }}</p></footer>
        </template>
      </aside>
    </div>
    </section></div>
    <AppModal :open="splitOpen" :title="t('分别选取两个问题的原文', 'Select the source for each question')" wide @close="splitOpen = false"><p>{{ t('可先收起此窗在左侧选词，再打开保留选区。拆分不会复制旧项目答案。', 'Select each exact source passage. Splitting does not copy an adopted project answer.') }}</p><div class="split-columns"><section><button class="btn" :disabled="!selection" @click="splitFirst = selection">{{ t('当前选词作为第一项', 'Use selection for the first item') }}</button><blockquote>{{ splitFirst?.text }}</blockquote><input v-model="splitNames.firstZh" placeholder="中文名称"><input v-model="splitNames.firstEn" placeholder="English name"></section><section><button class="btn" :disabled="!selection" @click="splitSecond = selection">{{ t('当前选词作为第二项', 'Use selection for the second item') }}</button><blockquote>{{ splitSecond?.text }}</blockquote><input v-model="splitNames.secondZh" placeholder="中文名称"><input v-model="splitNames.secondEn" placeholder="English name"></section></div><select v-model="splitNames.mode"><option value="unknown">{{ t('关系仍待核对', 'Relationship to review') }}</option><option value="all">{{ t('两项同时成立', 'Both must hold') }}</option><option value="any">{{ t('任一项成立', 'Either may hold') }}</option></select><button class="btn" @click="cancelSplit">{{ t('取消本次拆分', 'Cancel this split') }}</button><button class="btn primary" @click="split">{{ t('保留两个独立问题', 'Keep two independent questions') }}</button></AppModal>
    <AppModal :open="batchOpen" :title="t('勾选项对照与分支处理', 'Selected items and answer branches')" wide @close="batchOpen = false"><p v-if="batchError" class="review-error" role="alert" data-batch-error>{{ batchError }}</p><DraftingQuestionBatch v-if="batchOpen" :state="state" :ids="checkedQuestionIds" :sources="graph.sources" :locale="locale" :draft="batchDraft" @draft="batchDraft=$event" @merge="mergeChecked" @branches="applyBatch" @locate="locateBatchQuestion" /></AppModal>
    <AppModal :open="showGraph" :title="t('问题、修改方案与原文文件', 'Questions, amendments and source files')" wide @close="showGraph = false">
      <div class="review-actions"><button class="btn" data-review-graph-clear @click="graphSelected=''; graphOnlySelected=false">{{ t('完整图谱／清除选中', 'Complete graph / clear selection') }}</button><button class="btn" data-review-graph-only :disabled="!graphSelected" :aria-pressed="graphOnlySelected" @click="graphOnlySelected=!graphOnlySelected">{{ graphOnlySelected ? t('显示完整图谱', 'Show complete graph') : t('只看选中链路', 'Show selected chain only') }}</button><span class="source-note">{{ t('选中链路与可能影响高亮，其他项目仍可选择。实际方案以已采用事实为准。', 'The selected chain and possible impact are highlighted. Other items remain selectable. Adopted facts determine the actual plan.') }}</span></div>
      <div v-if="graphLayout" class="review-graph-body"><div class="graph-scroll"><div class="graph-canvas" :style="{width:graphLayout.width+'px',height:graphLayout.height+'px'}"><svg :width="graphLayout.width" :height="graphLayout.height"><path v-for="edge in shownGraphEdges" :key="edge.id" :data-review-graph-edge="edge.id" :d="edge.path" class="graph-edge" :class="[edge.kind, {selected:graphImpact?.edgeIds.includes(edge.id),dim:graphSelected && !graphImpact?.edgeIds.includes(edge.id)}]" /></svg><button v-for="item in shownGraphItems" :key="item.id" :data-review-graph-node="item.id" class="graph-node" :class="[{selected:graphChain.has(item.id),dim:graphSelected && !graphChain.has(item.id)},item.id.startsWith('action:')?'action-node':item.docId?'file-node':'input-node']" :aria-pressed="graphSelected===item.id" :style="{left:item.x+'px',top:item.y+'px',width:item.w+'px',height:item.h+'px'}" @click="graphSelected=item.id"><strong>{{ graphLabel(item) }}</strong><small>{{ item.id.startsWith('action:') ? t('修改方案', 'Amendment action') : item.docId ? t('原文文件', 'Source file') : isReviewCheck(item.node ?? item) ? t('待核对事项', 'Review check') : t('问题／资料', 'Question or detail') }}</small><small v-if="item.summaryZh || item.summaryEn">{{ locale==='en'?item.summaryEn:item.summaryZh }}</small></button></div></div>
      <aside class="review-graph-inspector"><template v-if="graphInspection"><h3>{{ graphLabel(graphInspection) }}</h3><p class="source-note">{{ t('前提关联', 'Upstream dependencies') }} {{ graphImpact?.upstreamIds?.length ?? 0 }} · {{ t('可能受影响的问题', 'Potentially affected questions') }} {{ graphImpact?.downstreamIds?.length ?? 0 }}</p><button class="btn" data-review-graph-locate :disabled="busy || formDirty" @click="locateGraphSelection">{{ graphInspection.docId ? t('对照这份原件', 'Open this source') : t('对照原文核对该项', 'Review this item on its source') }}</button><blockquote v-for="(anchor,index) in graphInspection.sourceAnchors ?? graphInspection.node?.sourceAnchors ?? []" :key="index">{{ anchor.text }}</blockquote><article v-for="(entry,index) in graphInspection.targets ?? []" :key="index"><blockquote>{{ entry.target?.text }}</blockquote></article></template><p v-else class="source-note">{{ t('选择一个问题、方案或文件，查看完整关联与影响范围。', 'Select a question, action or file to inspect its relationships and possible impact.') }}</p></aside></div>
    </AppModal>
    <AppModal :open="historyOpen" :title="t('已保存的图谱版本', 'Saved graph revisions')" wide @close="historyOpen = false"><article v-for="revision in history" :key="revision.id" class="revision-row"><span>{{ revision.createdAt }} · {{ revision.status }} · {{ revision.sourceStale ? t('来源已变化', 'Source changed') : t('保存的来源绑定', 'Saved source bindings') }}</span><button class="btn" :disabled="busy || locked || revision.sourceStale" @click="restoreRevision(revision)">{{ t('作为新草稿核对', 'Review as a new draft') }}</button></article></AppModal>
  </fieldset></section>
</template>

<style scoped>
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
