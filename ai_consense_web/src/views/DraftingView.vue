<script setup lang="ts">
import type { GraphLocationTarget, GraphNavigation } from '@/drafting/graph-navigation'
import { computed, defineAsyncComponent, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ApiError, draftingApi } from '@/api'
import { containTab, focusableControls } from '@/focus'
import type { DraftCatalog, DraftDocument, DraftField, DraftGroup, DraftIdentityConfirmation, DraftPlan, DraftPlanAction, DraftTargetOverride, DraftUnresolved, DraftVariable, DraftVariablePatch, EvidenceItem, ExtractTrace, TemplateItem } from '@/api'
import { useAppStore } from '@/stores/app'
import AppModal from '@/components/AppModal.vue'
import DraftingSourceReview from '@/components/DraftingSourceReview.vue'
import DraftingCandidateReview from '@/components/DraftingCandidateReview.vue'
import DraftingAutomationResults from '@/components/DraftingAutomationResults.vue'
import { sourceReviewDrafts, candidateReviewDrafts } from '@/drafting/source-review-state'
import type { ReviewHead, ReviewRevision } from '@/drafting/source-review-types'
import type { DraftAutomationRun } from '@/drafting/automation-types'
import DraftingInputField from '@/components/DraftingInputField.vue'
import DraftingExtractionReport from '@/components/DraftingExtractionReport.vue'
import DraftingBillDistribution from '@/components/DraftingBillDistribution.vue'
import DraftingDocumentWorkspace from '@/components/DraftingDocumentWorkspace.vue'
import DraftingTemplatePreview from '@/components/DraftingTemplatePreview.vue'
import DraftingValueDisplay from '@/components/DraftingValueDisplay.vue'
import DraftingUnresolvedItems from '@/components/DraftingUnresolvedItems.vue'
import DraftingBodyEditor from '@/components/DraftingBodyEditor.vue'
import { bodyDirty, bodyPatch, createBodyDraft } from '@/drafting/body-edit'
import { actionForUnresolved, adoptionPatch, applicability, clone, decode, dirtyPatch, documentCapabilities, encode, mergeServerValues, projectDrafts, replaceTargetOverride, restoreDraftView, reversedSiteDates, validationIssue, type DraftValue, type DraftViewState } from '@/drafting/state'
import { draftWord, localized, type DraftWord } from '@/drafting/words'
import { buildBusinessGraph } from '@/drafting/business-graph'
const DraftingBusinessGraph = defineAsyncComponent(() => import('@/components/DraftingBusinessGraph.vue').then(module => module.default))

const store = useAppStore()
const w = (key: DraftWord) => draftWord(key, store.locale)
const l = (text: Parameters<typeof localized>[0]) => localized(text, store.locale)
type Step = DraftViewState['step']


const projectId = computed(() => store.activeProjectId)
const step = ref<Step>('inputs')
const phrase = (zh: string, en: string, hant = zh) => store.locale === 'en' ? en : store.locale === 'zh-Hant' ? hant : zh
const sourceReview = ref<InstanceType<typeof DraftingSourceReview>>()
const reviewMounted = ref(false), reviewDirty = ref(false), candidateDirty = ref(false), draftRevisionId = ref(''), candidateReload = ref(0)
const automation = ref<DraftAutomationRun | null>(null), automationError = ref(''), automationChecking = ref(false), automationResultPending = ref(false)
const automationRevision = ref<ReviewRevision | null>(null)
const automationActive = computed(() => ['QUEUED', 'RUNNING'].includes(automation.value?.status ?? ''))
const automationResumable = computed(() => automation.value?.status === 'INTERRUPTED' && !automation.value.resultRevisionId)
const automationPhase = computed(() => ({
  PREPARING_SOURCES: phrase('正在核对文件来源', 'Checking source files'),
  PLANNING: phrase('正在整理全部模板要求', 'Planning template requirements'),
  DISCOVERING_VARIABLES: phrase('正在识别变量并进行原子拆解', 'Identifying variables and decomposing atomic capabilities'),
  LINKING_GRAPH: phrase('正在连接条件、依赖和修改位置', 'Linking conditions, dependencies and edit targets'),
  SAVING_GRAPH: phrase('正在保存变量图谱', 'Saving the variable graph'),
  EXTRACTING_CANDIDATES: phrase('正在从项目资料提取候选值', 'Extracting candidate values from project sources'),
  READY_FOR_REVIEW: phrase('识别结果已可核对', 'Results are ready for review')
}[automation.value?.phase ?? 'PREPARING_SOURCES']))
const automationStatus = computed(() => ({ QUEUED: phrase('排队中', 'Queued'), RUNNING: phrase('执行中', 'Running'), COMPLETED: phrase('处理完成，等待人工核对', 'Finished; human review required'), PARTIAL: phrase('部分完成，已有结果可核对', 'Partially finished; available results can be reviewed'), FAILED: phrase('任务失败，已有结果保留', 'Task failed; available results are retained'), INTERRUPTED: phrase('任务已中断，可继续', 'Interrupted; can be resumed'), CANCELLED: phrase('任务已停止，已有结果保留', 'Stopped; available results are retained') }[automation.value?.status ?? 'QUEUED']))
let automationTimer: ReturnType<typeof setTimeout> | undefined
let automationAbort: AbortController | undefined
let automationSequence = 0, automationRefreshed = ''
const manualNotes = reactive<Record<string, string>>({})
const intentionalEmpty = reactive<Record<string, boolean>>({}), emptyBaseline = reactive<Record<string, boolean>>({})
async function ensureReview() { reviewMounted.value = true; await nextTick(); await sourceReview.value?.ready(); return sourceReview.value }
function stopAutomationPolling() { automationSequence++; if (automationTimer) clearTimeout(automationTimer); automationTimer = undefined; automationAbort?.abort(); automationAbort = undefined }
async function refreshAutomationResult(id = projectId.value, token = generation) {
  const run = automation.value
  if (!run?.resultRevisionId || automationActive.value || !current(id, token)) return
  if (reviewDirty.value || candidateDirty.value || dirtyInputs.value || documentDirty.value) { automationResultPending.value = true; return }
  const key = `${run.id}:${run.updatedAt}`
  if (key === automationRefreshed) return
  await refresh(id, token)
  if (!current(id, token) || automation.value?.id !== run.id) return
  const selectedRevision = generationReview.value?.draft ?? generationReview.value?.current
  if (selectedRevision) automationRevision.value = selectedRevision.graph.graph.automaticDiscovery ? selectedRevision : null
  else {
    const revision = await draftingApi.reviewRevision(id, run.resultRevisionId)
    if (!current(id, token) || automation.value?.id !== run.id) return
    if (revision.id !== run.resultRevisionId) throw Error(phrase('识别结果的保存版本不一致，请重新读取。', 'The saved identification revision does not match. Reload the result.'))
    automationRevision.value = revision
  }
  if (reviewMounted.value) await sourceReview.value?.refresh()
  if (!current(id, token)) return
  candidateReload.value++; automationRefreshed = key; automationResultPending.value = false
}
function scheduleAutomationPoll(id: string, token: number) {
  if (automationTimer) clearTimeout(automationTimer)
  if (current(id, token) && automationActive.value) automationTimer = setTimeout(() => { void loadAutomation(id, token, automation.value?.id) }, 2000)
}
async function loadAutomation(id: string, token: number, runId?: string, refreshResult = true) {
  const sequence = ++automationSequence
  automationAbort?.abort(); const controller = new AbortController(); automationAbort = controller
  automationChecking.value = true
  try {
    const run = runId ? await draftingApi.automationGet(id, runId, controller.signal) : await draftingApi.automationLatest(id, controller.signal)
    if (!current(id, token) || sequence !== automationSequence) return
    if (run && run.projectId !== id) throw Error(phrase('任务不属于当前项目，请重新读取。', 'The task does not belong to this project. Reload its status.'))
    automation.value = run; automationError.value = ''
    if (refreshResult && automationActive.value && !runId) { step.value = 'review'; await ensureReview() }
    if (refreshResult) await refreshAutomationResult(id, token)
  } catch (caught) { if (current(id, token) && sequence === automationSequence && !controller.signal.aborted) automationError.value = caught instanceof Error ? caught.message : String(caught) }
  finally { if (current(id, token) && sequence === automationSequence) { automationChecking.value = false; if (refreshResult) scheduleAutomationPoll(id, token) } }
}
async function runAutomation(resume = false) {
  if (automationActive.value || automationChecking.value || automationError.value) return
  const llmSelection = store.captureLlmSelection(), runId = resume ? automation.value?.id : undefined
  await action(phrase('正在启动变量识别与图谱任务', 'Starting variable identification and graph construction'), async (id, token) => {
    await ensureReview()
    if (!current(id, token)) return
    if (reviewDirty.value || candidateDirty.value || dirtyInputs.value || documentDirty.value) throw Error(phrase('请先保存或取消尚未保存的核对、候选和填写内容，再开始自动识别。', 'Save or cancel unsaved review, candidate and input edits before starting automation.'))
    const head = await draftingApi.reviewHead(id)
    if (!current(id, token)) return
    stopAutomationPolling(); automationChecking.value = false
    const binding = { expectedCurrentRevisionId: head.currentRevisionId, expectedDraftRevisionId: head.draftRevisionId }
    if (!resume) { trace.value = null; traceOpen.value = false }
    try {
      const run = runId ? await draftingApi.automationResume(id, runId, binding, llmSelection) : await draftingApi.automationStart(id, binding, llmSelection)
      if (!current(id, token)) return
      if (run.projectId !== id) throw Error(phrase('任务不属于当前项目，请重新读取。', 'The task does not belong to this project. Reload its status.'))
      automation.value = run; automationError.value = ''; step.value = 'review'
      if (!resume) automationRevision.value = null
      await refreshAutomationResult(id, token); scheduleAutomationPoll(id, token)
    } catch (caught) { if (current(id, token)) await loadAutomation(id, token); throw caught }
  })
}
async function cancelAutomation() {
  const runId = automation.value?.id
  if (!runId || !automationActive.value) return
  await action(phrase('正在请求停止任务', 'Requesting task cancellation'), async (id, token) => {
    stopAutomationPolling(); automationChecking.value = false
    try { const run = await draftingApi.automationCancel(id, runId); if (!current(id, token)) return; if (run.projectId !== id) throw Error(phrase('任务不属于当前项目，请重新读取。', 'The task does not belong to this project. Reload its status.')); automation.value = run; await refreshAutomationResult(id, token) }
    finally { if (current(id, token)) scheduleAutomationPoll(id, token) }
  })
}
function reviewSaved(revision: ReviewRevision) { candidateReviewRevision.value = revision; draftRevisionId.value = revision.id; if (revision.graph.graph.automaticDiscovery) automationRevision.value = revision; candidateReload.value++ }
async function reviewConfirmed() { await action(w('loading'), refresh); step.value = 'variables' }
async function locateCandidate(sourceId: string, sourceSha256?: string | null) { if (candidateDirty.value) return; step.value = 'review'; const editor = await ensureReview(); await editor?.locateSource(sourceId, sourceSha256) }
async function locateCandidateQuestion(key: string, revisionId: string) { if (candidateDirty.value || busy.value) return; step.value = 'review'; const editor = await ensureReview(); await editor?.locateQuestion(key, revisionId) }

const catalog = ref<DraftCatalog>({ ruleVersion: '', groups: [] })
const templates = ref<TemplateItem[]>([])
const templateRows = computed(() => ['NTT', 'SCT', 'SCC'].map(key => ({ key, template: templates.value.find(item => item.key === key) })))
const generationReview = ref<ReviewHead>()
const candidateReviewRevision = ref<ReviewRevision>()
const generationWorkingRevision = computed(() => [generationReview.value?.current, generationReview.value?.draft].find(revision => revision?.id === catalog.value.graphRevisionId))
const nonEditableSelectedSources = computed(() => {
  const head = generationReview.value, current = generationWorkingRevision.value
  if (!graphMode.value || !head || !current) return []
  return Object.values(current.graph.selectedDocuments).flatMap(id => { const source = head.sources.find(item => String(item.id) === String(id)); return source && (source.format !== 'docx' || !source.canEdit) ? [source] : [] })
})
const generationSourceBlocked = computed(() => graphMode.value && (!generationWorkingRevision.value || generationWorkingRevision.value.sourceStale || Object.values(generationWorkingRevision.value.graph.selectedDocuments).some(id => !generationReview.value!.sources.some(source => String(source.id) === String(id) && source.evidenceStatus === 'bound')) || nonEditableSelectedSources.value.length > 0))
const inputs = ref<EvidenceItem[]>([])
const variables = ref<DraftVariable[]>([])
const documents = ref<DraftDocument[]>([])
const plan = ref<DraftPlan | null>(null)
const trace = ref<ExtractTrace | null>(null)
const traceOpen = ref(false)
const values = reactive<Record<string, DraftValue>>({})
const baseline = reactive<Record<string, string>>({})
const inputSaveStates = reactive<Record<string, 'saving' | 'saved' | 'failed'>>({})
const busy = ref('')
const error = ref('')
const restored = ref(false)
const search = ref('')
const statusFilter = ref('all')
const reviewLayout = ref<'list' | 'focus'>('list')
const focusedGroupId = ref('')
const activeFile = ref('NTT')
const documentText = ref('')
const documentBaseline = ref('')
const editingDocument = ref(false)
const tocRefreshNotice = ref('')
const immersiveDocument = ref(false)
const documentInfoOpen = ref(false)
const graphOpen = ref(false)
const graphNavigation = ref<GraphNavigation>()
let graphSequence = 0
let graphInvoker: HTMLElement | undefined
let graphProject = ''
const documentWorkspace = ref<HTMLElement>()
const immersiveToggle = ref<HTMLButtonElement>()
const documentInfoToggle = ref<HTMLButtonElement>()
const targetModal = ref<{ $el: HTMLElement }>()
let targetInvoker: HTMLElement | undefined
let targetInvokerProject = ''
const bodyDraft = ref(createBodyDraft(undefined))
const targetId = ref('')
const targetAction = ref<DraftTargetOverride['action']>('amend')
const targetText = ref('')
const targetSourceMapping = ref('')
const removalItem = ref<EvidenceItem | null>(null)
const readingOpen = ref(false)
const readingFile = ref('NTT')
const readingKey = ref('')
const readingActionId = ref('')
const previewEditKey = ref('')
const identityConfirmation = ref<{ projectId: string; token: number; request: DraftIdentityConfirmation; name: string; number: string; currentName: string }>()
const identityError = ref('')
let generation = 0, planGeneration = 0
let alive = true
let restoreViewPending = true
let planTimer: ReturnType<typeof setTimeout> | undefined

const fields = computed(() => [...catalog.value.groups.flatMap(group => group.fields), ...(catalog.value.systemFields ?? [])])
const previewEditField = computed(() => fields.value.find(field => field.key === previewEditKey.value))
const readingFields = computed(() => fields.value.filter(field => !field.hidden))
const readingStates = computed(() => Object.fromEntries(readingFields.value.map(field => [field.key, state(field)])))
const readingDirtyKeys = computed(() => readingFields.value.filter(dirty).map(field => field.key))
const readingSourceAvailable = computed(() => {
  const tag = templates.value.find(template => template.key === readingFile.value)?.tag
  return tag === 'missing' ? false : tag === 'ok' || tag === 'partial' ? true : undefined
})
const variableMap = computed(() => new Map(variables.value.map(variable => [variable.key, variable])))
const identityProposal = computed(() => {
  const variable = variableMap.value.get('contractTitle'), field = fields.value.find(item => item.key === 'contractTitle')
  const project = store.projects.find(item => item.id === projectId.value)
  if (!variable?.confirmed || variable.reviewRequired || variable.validationIssue || !field || dirty(field) || !project) return undefined
  try {
    const value = JSON.parse(variable.value)
    if (!value || Array.isArray(value) || typeof value.title !== 'string' || !value.title.trim() || typeof value.number !== 'string' || !value.number.trim()) return undefined
    const name = value.title, number = value.number
    if (project.contractNo === number && [project.name.zhHans, project.name.zhHant, project.name.en].every(item => item === name)) return undefined
    return { project, variable, name, number }
  } catch { return undefined }
})
const steps = computed(() => [{ key: 'inputs' as Step, label: phrase('1 文件识别', '1 File identification') }, { key: 'review' as Step, label: phrase('2 变量识别与图谱', '2 Variables and graph') }, { key: 'variables' as Step, label: phrase('3 采用与修改方案', '3 Adopt and plan') }, { key: 'preview' as Step, label: phrase('4 预览与导出', '4 Preview and export') }])
const allTemplates = computed(() => ['NTT', 'SCT', 'SCC'].every(key => templates.value.some(template => template.key === key && template.tag === 'ok')))
const activeDocument = computed(() => documents.value.find(document => document.fileKey === activeFile.value))
const documentDirty = computed(() => bodyDirty(activeDocument.value, bodyDraft.value))
const graphMode = computed(() => !!catalog.value.graphRevisionId)
const pendingGraphReview = computed(() => !!draftRevisionId.value && draftRevisionId.value !== catalog.value.graphRevisionId && !(generationReview.value?.current && generationReview.value.current.id === catalog.value.graphRevisionId && generationReview.value.current.parentRevisionId === draftRevisionId.value))
const dirtyInputs = computed(() => fields.value.some(dirty))
const documentAccess = computed(() => documentCapabilities(activeDocument.value, documentDirty.value))
const canExport = computed(() => documentAccess.value.exportable)
const canRefreshToc = computed(() => !!projectId.value && activeFile.value === 'SCC' && documentAccess.value.editable && !!activeDocument.value?.revisionId && !!activeDocument.value?.docxSha256 && !busy.value && !documentDirty.value && !dirtyInputs.value && !reviewDirty.value && !candidateDirty.value && !automationActive.value)
const durationKnown = computed(() => values.contractPeriodMonths !== null && values.contractPeriodMonths !== undefined && values.contractPeriodMonths !== '' && Number.isFinite(Number(values.contractPeriodMonths)) && Number(values.contractPeriodMonths) >= 0)
const durationAdopted = computed(() => durationKnown.value && (dirty(fields.value.find(field => field.key === 'contractPeriodMonths') ?? { key: 'contractPeriodMonths', kind: 'number', label: { en: '', zhHans: '', zhHant: '' } }) || variableMap.value.get('contractPeriodMonths')?.confirmed || variableMap.value.get('contractPeriodMonths')?.manuallyEdited))
const thresholdPresent = computed(() => values.periodAtLeast39Months !== null && values.periodAtLeast39Months !== undefined && values.periodAtLeast39Months !== '')
const durationThreshold = computed(() => String(Number(values.contractPeriodMonths) >= 39))
const durationConflict = computed(() => durationKnown.value && values.periodAtLeast39Months !== null && values.periodAtLeast39Months !== undefined && values.periodAtLeast39Months !== '' && String(values.periodAtLeast39Months) !== durationThreshold.value)
const actionableFields = computed(() => fields.value.filter(field => !field.hidden && applicability(field.condition, values) !== 'no'))
const unresolvedInputCount = computed(() => actionableFields.value.filter(field => !field.optional && (graphMode.value && dirty(field) || !['adopted', 'manual'].includes(state(field)))).length)
const adoptedCount = computed(() => actionableFields.value.filter(field => (!graphMode.value || !dirty(field)) && ['adopted', 'manual'].includes(state(field))).length)
const currentUnresolved = computed<DraftUnresolved[]>(() => step.value === 'preview' ? activeDocument.value?.unresolved ?? [] : plan.value?.unresolved ?? [])
const selectedTarget = computed(() => plan.value?.actions.find(action => action.id === targetId.value))
const targetField = computed<DraftField>(() => fields.value.find(field => ['targetOverrides', 'targetEdits'].includes(field.key)) ?? { key: 'targetOverrides', kind: 'list', hidden: true, label: { en: 'Target edits', zhHans: '目标修改', zhHant: '目標修改' } })
const filteredGroups = computed(() => catalog.value.groups.filter(group => {
  const needle = search.value.trim().toLowerCase()
  const matchesText = !needle || [l(group.label), ...group.fields.map(field => `${l(field.label)} ${field.affects?.map(target => `${target.document} ${target.clause}`).join(' ') ?? ''}`)].join(' ').toLowerCase().includes(needle)
  return matchesText && (statusFilter.value === 'all' || groupState(group) === statusFilter.value)
}))
const focusedGroup = computed(() => filteredGroups.value.find(group => group.id === focusedGroupId.value) ?? filteredGroups.value[0])
const focusedGroupIndex = computed(() => filteredGroups.value.findIndex(group => group.id === focusedGroup.value?.id))
const displayedGroups = computed(() => reviewLayout.value === 'focus' ? focusedGroup.value ? [focusedGroup.value] : [] : filteredGroups.value)
function groupNumber(group: DraftGroup) { return catalog.value.groups.findIndex(item => item.id === group.id) + 1 }
function chooseGroup(id: string) { if (filteredGroups.value.some(group => group.id === id)) { focusedGroupId.value = id; snapshotDraft(projectId.value) } }
function changeReviewLayout(layout: 'list' | 'focus') { focusedGroupId.value = focusedGroup.value?.id ?? ''; reviewLayout.value = layout; snapshotDraft(projectId.value) }
function moveQuestion(offset: number) { const group = filteredGroups.value[focusedGroupIndex.value + offset]; if (group) chooseGroup(group.id) }

function derivedInput(field: DraftField) { return field.key === 'periodAtLeast39Months' && durationAdopted.value && thresholdPresent.value && !durationConflict.value }
function inputDisabled(field: DraftField) { return !!busy.value || automationActive.value || applicability(field.condition, values) === 'no' || derivedInput(field) }
function previewAdoptable(field: DraftField) {
  const variable = variableMap.value.get(field.key)
  return !dirty(field) && encode(field, values[field.key]).trim() !== '' && validationIssue(field, values[field.key]) === null && !variable?.validationIssue && applicability(field.condition, values) !== 'no' && (state(field) === 'suggested' || !!variable?.reviewRequired)
}
function dirty(field: DraftField) { return encode(field, values[field.key]) !== (baseline[field.key] ?? '') || !!intentionalEmpty[field.key] !== !!emptyBaseline[field.key] }
function toggleEmpty(field: DraftField, checked: boolean) { intentionalEmpty[field.key] = checked; if (checked) values[field.key] = ''; snapshotDraft(projectId.value) }
function inputSaveStatus(field: DraftField): DraftWord | undefined {
  const status = inputSaveStates[field.key]
  if (status === 'saving') return 'savingInput'
  if (dirty(field)) return status === 'failed' ? 'saveInputFailed' : 'unsaved'
  return status === 'saved' ? 'savedInput' : undefined
}
function state(field: DraftField): DraftWord {
  if (applicability(field.condition, values) === 'no') return 'inactive'
  const variable = variableMap.value.get(field.key)
  if (graphMode.value && field.kind === 'text' && intentionalEmpty[field.key] && !variable?.reviewRequired && !variable?.validationIssue) return dirty(field) || variable?.manuallyEdited ? 'manual' : 'adopted'
  const issue = validationIssue(field, values[field.key])
  if (issue === 'missing') return variable?.adoptionState === 'conflict' && !dirty(field) ? 'conflict' : 'missing'
  if (issue || variable?.reviewRequired || variable?.validationIssue || ['siteInspectionStartDate', 'siteInspectionEndDate'].includes(field.key) && reversedSiteDates(values) || ['contractPeriodMonths', 'periodAtLeast39Months'].includes(field.key) && durationConflict.value || applicability(field.condition, values) === 'unknown' && values[field.key] !== null && values[field.key] !== undefined && values[field.key] !== '') return 'needs_review'
  if (variable?.adoptionState === 'conflict' && !dirty(field)) return 'conflict'
  if (dirty(field)) return 'manual'
  if (variable?.manuallyEdited && values[field.key] !== null && values[field.key] !== undefined && values[field.key] !== '') return 'manual'
  if (variable?.confirmed || variable?.adoptionState === 'adopted') return 'adopted'
  return values[field.key] !== null && values[field.key] !== undefined && values[field.key] !== '' ? 'suggested' : 'missing'
}
function groupState(group: DraftGroup): DraftWord {
  const active = group.fields.filter(field => applicability(field.condition, values) !== 'no' && !field.optional)
  if (!active.length) return 'inactive'
  const states = active.map(state)
  for (const status of ['needs_review', 'conflict', 'missing', 'suggested'] as DraftWord[]) if (states.includes(status)) return status
  return states.includes('manual') ? 'manual' : 'adopted'
}
function visibleFields(group: DraftGroup) { return group.fields.filter(field => !field.hidden && applicability(field.condition, values) !== 'no') }
function relatedActions(group: DraftGroup) {
  const keys = new Set(group.fields.map(field => field.key))
  return plan.value?.actions.filter(action => (action.inputKeys ?? action.fieldKeys ?? []).some(key => keys.has(key))) ?? []
}
function rawValues() { return dirtyPatch(fields.value, values, baseline) }
function snapshotDraft(id: string) {
  if (!id || !fields.value.length && restoreViewPending) return
  const docs = clone(projectDrafts.get(id)?.documents ?? {})
  if (documentDirty.value) docs[activeFile.value] = { content: documentText.value, baseline: documentBaseline.value, body: clone(bodyDraft.value) }
  else delete docs[activeFile.value]
  projectDrafts.set(id, { values: clone(values), baseline: clone(baseline), notes: clone(manualNotes), intentionalEmpty: clone(intentionalEmpty), emptyBaseline: clone(emptyBaseline), documents: docs, view: { step: step.value, activeFile: activeFile.value, review: { layout: reviewLayout.value, groupId: focusedGroupId.value }, reading: readingOpen.value ? { fileKey: readingFile.value, selectedKey: readingKey.value, selectedActionId: readingActionId.value } : undefined } })
}
function loadDocument(preserve = true) {
  if (preserve && documentDirty.value) return
  const pending = projectDrafts.get(projectId.value)?.documents?.[activeFile.value]
  documentText.value = pending?.content ?? activeDocument.value?.content ?? ''
  documentBaseline.value = pending?.baseline ?? activeDocument.value?.content ?? ''
  bodyDraft.value = pending?.body ? clone(pending.body) : createBodyDraft(activeDocument.value)
  editingDocument.value = !!pending
  if (pending) restored.value = true
}
function initialize(list: DraftVariable[]) {
  const retainedEmpty = new Set(fields.value.filter(field => !!intentionalEmpty[field.key] !== !!emptyBaseline[field.key]).map(field => field.key))
  const merged = mergeServerValues(fields.value, list, values, baseline)
  variables.value = list
  for (const field of fields.value) { const variable = list.find(item => item.key === field.key); const adoptedEmpty = !!variable?.graphRevisionId && field.kind === 'text' && variable?.value === '' && variable.confirmed; emptyBaseline[field.key] = adoptedEmpty; if (!retainedEmpty.has(field.key)) intentionalEmpty[field.key] = adoptedEmpty }
  for (const key of Object.keys(values)) if (!(key in merged.values)) delete values[key]
  Object.assign(values, merged.values); Object.assign(baseline, merged.baseline)
}
function update(field: DraftField, value: DraftValue) {
  if (inputDisabled(field)) return
  delete inputSaveStates[field.key]
  values[field.key] = value
  if (value !== '' && value !== null && value !== undefined) intentionalEmpty[field.key] = false
  if (field.key === 'contractPeriodMonths' && durationKnown.value) values.periodAtLeast39Months = durationThreshold.value
  snapshotDraft(projectId.value)
}
function discard(group: DraftGroup) { for (const field of group.fields) { values[field.key] = decode(field, baseline[field.key]); intentionalEmpty[field.key] = !!emptyBaseline[field.key] }; snapshotDraft(projectId.value) }
function current(id: string, token: number) { return alive && id === projectId.value && token === generation }
async function action(label: string, task: (id: string, token: number) => Promise<void>) {
  if (busy.value || !projectId.value) return
  const id = projectId.value, token = generation
  busy.value = label; error.value = ''
  try { await task(id, token) }
  catch (caught) { if (current(id, token)) error.value = caught instanceof Error ? caught.message : String(caught) }
  finally { if (current(id, token)) busy.value = '' }
}
async function updatePlan() {
  if (!projectId.value || !fields.value.length) return
  const sequence = ++planGeneration, id = projectId.value, token = generation
  try { const result = await draftingApi.plan(id, !graphMode.value && dirtyInputs.value ? rawValues() : undefined); if (sequence === planGeneration && current(id, token)) plan.value = result }
  catch (caught) { if (sequence === planGeneration && current(id, token)) error.value = caught instanceof Error ? caught.message : String(caught) }
}
async function refresh(id: string, token: number) {
  generationReview.value = undefined
  if (step.value === 'inputs') { planGeneration++; plan.value = null }
  const showReadStages = busy.value === w('loading')
  const sourceLists = Promise.all([draftingApi.templates(id), draftingApi.inputs(id)]).then(([ts, ins]) => {
    if (current(id, token)) {
      templates.value = ts; inputs.value = ins
      if (showReadStages) busy.value = phrase('文件目录已载入，正在读取已保存的变量与文稿…', 'Files loaded; reading saved variables and documents…')
    }
  })
  // Attach a rejection handler immediately while independent catalogue reads are pending.
  const loaded = Promise.all([draftingApi.catalog(id), draftingApi.variables(id), draftingApi.documents(id), draftingApi.extractTrace(id), sourceLists])
  const [schema, list, docs, tr] = await loaded
  if (!current(id, token)) return
  catalog.value = schema
  const cached = projectDrafts.get(id)
  if (!Object.keys(values).length && cached) { Object.assign(values, clone(cached.values)); Object.assign(baseline, clone(cached.baseline)); Object.assign(manualNotes, clone(cached.notes ?? {})); Object.assign(intentionalEmpty, clone(cached.intentionalEmpty ?? {})); Object.assign(emptyBaseline, clone(cached.emptyBaseline ?? {})); restored.value = schema.groups.some(group => group.fields.some(field => encode(field, values[field.key]) !== (baseline[field.key] ?? ''))) }
  initialize(list)
  documents.value = docs; trace.value = tr
  if (restoreViewPending) {
    restoreViewPending = false
    const view = restoreDraftView(cached?.view, docs)
    reviewLayout.value = view.review?.layout ?? 'list'
    focusedGroupId.value = schema.groups.some(group => group.id === view.review?.groupId) ? view.review!.groupId : schema.groups[0]?.id ?? ''
    activeFile.value = view.activeFile; step.value = view.step; loadDocument(false)
    if (view.reading) { readingOpen.value = true; readingFile.value = view.reading.fileKey; readingKey.value = view.reading.selectedKey; readingActionId.value = view.reading.selectedActionId }
  }
  if (showReadStages) busy.value = phrase('正在核对已保存的图谱与来源版本…', 'Checking the saved graph and source versions…')
  const review = await draftingApi.reviewHead(id)
  if (!current(id, token)) return
  generationReview.value = review
  candidateReviewRevision.value = review.draft ?? review.current ?? undefined
  draftRevisionId.value = review.draftRevisionId ?? ''
  if (step.value !== 'inputs') await updatePlan()
}
async function saveField(id: string, token: number, field: DraftField, patch: DraftVariablePatch) {
  const variable = variableMap.value.get(field.key)
  const bound = variable?.graphRevisionId ? { ...patch, graphRevisionId: variable.graphRevisionId, fieldDefinitionHash: variable.fieldDefinitionHash } : patch
  if (variable?.graphRevisionId && patch.value !== undefined && (patch.value.trim() || patch.explicitEmpty) && !bound.note?.trim()) {
    const reason = manualNotes[field.key]?.trim()
    if (!reason) throw Error(phrase('请为此人工采用填写理由，再保存。', 'Supply a reason for adopting this manual value before saving.'))
    bound.note = reason
  }
  const updated = await draftingApi.updateVariable(id, field.key, bound)
  if (!current(id, token)) return
  variables.value = [...variables.value.filter(variable => variable.key !== updated.key), updated]
  values[field.key] = decode(field, updated.value); baseline[field.key] = updated.value ?? ''; emptyBaseline[field.key] = !!updated.graphRevisionId && field.kind === 'text' && updated.value === '' && updated.confirmed; intentionalEmpty[field.key] = !!emptyBaseline[field.key]; snapshotDraft(id)
  if (field.key === 'contractTitle') openIdentityConfirmation()
}
function openIdentityConfirmation() {
  const proposal = identityProposal.value
  if (!proposal) return
  const { project, variable, name, number } = proposal
  identityError.value = ''
  identityConfirmation.value = { projectId: project.id, token: generation, name, number, currentName: l(project.name), request: {
    expectedProjectIdentity: { nameZhHans: project.name.zhHans ?? null, nameZhHant: project.name.zhHant ?? null, nameEn: project.name.en ?? null, contractNo: project.contractNo ?? null },
    expectedAdoptedIdentity: { value: variable.value }
  } }
}
async function confirmIdentity() {
  const confirmation = identityConfirmation.value
  if (!confirmation || !current(confirmation.projectId, confirmation.token)) return
  await action(phrase('应用项目身份', 'Applying project identity', '套用項目身分'), async (id, token) => {
    let applied = false
    try {
      const project = await draftingApi.confirmIdentity(id, confirmation.request)
      if (project.id !== id) throw Error(phrase('返回的项目身份不匹配，请刷新后核对。', 'The returned project identity does not match. Reload to verify it.', '返回的項目身分不相符，請重新整理後核對。'))
      store.projects = store.projects.map(item => item.id === project.id ? project : item)
      if (!current(id, token)) return
      applied = true
      documents.value = documents.value.map(document => ({ ...document, stale: true }))
      identityConfirmation.value = undefined; identityError.value = ''
      await store.loadProjects()
      if (current(id, token)) await refresh(id, token)
    } catch (caught) {
      if (!current(id, token)) return
      if (applied) { error.value = phrase('项目身份已保存，但重新读取失败。请刷新后核对；现有文稿需重新生成。', 'Project identity was saved, but reloading failed. Refresh to verify it and regenerate the documents.', '項目身分已儲存，但重新讀取失敗。請重新整理後核對，並重新產生文稿。'); return }
      identityError.value = caught instanceof ApiError && caught.code === 4090
        ? phrase('项目身份或已采用值已变化，请刷新并重新核对后确认。', 'The project identity or adopted value changed. Reload and review it before confirming again.', '項目身分或已採用值已變化，請重新整理並核對後確認。')
        : caught instanceof Error ? caught.message : String(caught)
    }
  })
}
async function reloadIdentityConfirmation() {
  await action(w('loading'), async (id, token) => {
    await store.loadProjects()
    if (!current(id, token)) return
    await refresh(id, token)
    if (current(id, token)) { identityConfirmation.value = undefined; openIdentityConfirmation() }
  })
}
async function persistDirty(id: string, token: number, subset = fields.value) { for (const field of subset) { if (!current(id, token)) return; if (dirty(field)) await saveField(id, token, field, { value: encode(field, values[field.key]), ...(intentionalEmpty[field.key] ? { explicitEmpty: true } : {}) }) } }
async function saveGroup(group?: DraftGroup) { await action(w('saving'), async (id, token) => { await persistDirty(id, token, group?.fields); if (current(id, token)) await refresh(id, token) }) }
async function saveInput(field: DraftField) {
  if (!dirty(field) || applicability(field.condition, values) === 'no') return
  await action(w('saving'), async (id, token) => {
    inputSaveStates[field.key] = 'saving'
    try { await persistDirty(id, token, [field]) }
    catch (caught) { if (current(id, token)) inputSaveStates[field.key] = 'failed'; throw caught }
    if (!current(id, token)) return
    inputSaveStates[field.key] = 'saved'
    await refresh(id, token)
  })
}
async function adopt(field: DraftField, candidateIndex?: number) {
  await action(w('saving'), async (id, token) => {
    const variable = variableMap.value.get(field.key)
    const patch = candidateIndex === undefined && intentionalEmpty[field.key] && dirty(field) ? { value: '', explicitEmpty: true } : adoptionPatch(encode(field, values[field.key]), baseline[field.key] ?? '', !!variable?.reviewRequired, candidateIndex, variable)
    await saveField(id, token, field, patch)
    if (current(id, token)) await refresh(id, token)
  })
}
async function upload(event: Event, target: 'templates' | 'inputs', key?: string) {
  if (automationActive.value) { error.value = phrase('请先停止变量识别任务，再变更来源文件。', 'Stop the variable identification task before changing source files.'); return }
  if (reviewDirty.value || candidateDirty.value) { error.value = phrase('请先保存来源核对和候选调整，再变更来源文件。', 'Save source review and candidate edits before changing source files.'); return }
  const el = event.target as HTMLInputElement, files = Array.from(el.files ?? []); el.value = ''; if (!files.length) return
  await action(w('uploading'), async (id, token) => { const result = key ? await draftingApi.replaceTemplate(id, key, files[0]!) : target === 'templates' ? await draftingApi.uploadTemplates(id, files) : await draftingApi.uploadInputs(id, files); if (!current(id, token)) return; store.notify(`${result.parsed}/${result.accepted} ${w('parsed')}`); await refresh(id, token); if (reviewMounted.value && current(id, token)) await sourceReview.value?.refresh(); if (result.failed && current(id, token)) error.value = result.messages.join('\n') })
}
async function removeCorrespondence() {
  if (automationActive.value) return
  const item = removalItem.value
  if (item?.id === null || item?.id === undefined) return
  await action(w('saving'), async (id, token) => {
    if (reviewDirty.value || candidateDirty.value) throw Error(phrase('请先保存来源核对和候选调整。', 'Save source review and candidate edits first.'))
    await draftingApi.deleteInput(id, item.id!)
    if (!current(id, token)) return
    removalItem.value = null
    await refresh(id, token)
    if (reviewMounted.value && current(id, token)) await sourceReview.value?.refresh()
  })
}
async function generate() {
  if (!allTemplates.value || documentDirty.value) return
  if (generationSourceBlocked.value) { error.value = phrase('所选模板暂不能生成保留原格式的 Word。请核对来源和确认版本；PDF 版次需先提供同版可编辑 DOCX，不能借另一版 Word。', 'The selected template cannot generate a Word original. Check sources and the confirmed revision; a PDF edition needs its own editable DOCX, never another edition.'); return }
  if (graphMode.value && dirtyInputs.value) { error.value = phrase('请先逐项保存并采用项目事实，再生成文件。', 'Save and adopt project facts before generating documents.'); return }
  const llmSelection = store.captureLlmSelection()
  const navigation: { missingIdentity?: { id: string; token: number } } = {}
  await action(w('generating'), async (id, token) => { await persistDirty(id, token); if (!current(id, token)) return; const sharedPlan = await draftingApi.plan(id); if (!current(id, token)) return; plan.value = sharedPlan; if (!graphMode.value && sharedPlan.unresolved.some(item => item.kind === 'InputMissing' && item.inputKeys.includes('contractTitle'))) { error.value = phrase('请先在第3步“采用与修改方案”中补齐并采用项目名称和合约编号，再生成文件。现有文稿已保留。', 'Complete and adopt the project name and contract number in step 3 (Adopt and plan), then generate again. Existing drafts are retained.', '請先在第3步「採用與修改方案」補齊並採用項目名稱及合約編號，再產生文件。現有文稿已保留。'); navigation.missingIdentity = { id, token }; return }; const docs = await draftingApi.generate(id, llmSelection); if (!current(id, token)) return; documents.value = docs; step.value = 'preview'; documentText.value = docs.find(document => document.fileKey === activeFile.value)?.content ?? ''; documentBaseline.value = documentText.value; bodyDraft.value = createBodyDraft(activeDocument.value); editingDocument.value = false; snapshotDraft(id) })
  if (navigation.missingIdentity && current(navigation.missingIdentity.id, navigation.missingIdentity.token)) goInput('contractTitle')
}
async function saveDocument() {
  if (!documentAccess.value.editable || !documentDirty.value || !activeDocument.value) return
  const key = activeFile.value
  await action(w('saving'), async (id, token) => { const patch = bodyPatch(activeDocument.value!, bodyDraft.value); const updated = await draftingApi.updateDocument(id, key, patch); if (!current(id, token)) return; documents.value = documents.value.map(document => document.fileKey === key ? updated : document); documentText.value = updated.content; documentBaseline.value = updated.content; bodyDraft.value = createBodyDraft(updated); editingDocument.value = false; snapshotDraft(id) })
}
async function refreshDocumentToc() {
  if (!canRefreshToc.value || !activeDocument.value) return
  const saved = activeDocument.value, key = saved.fileKey, head = { revisionId: saved.revisionId!, docxSha256: saved.docxSha256! }
  tocRefreshNotice.value = ''
  await action(phrase('正在更新目录及预览…', 'Updating contents and preview…', '正在更新目錄及預覽…'), async (id, token) => {
    const result = await draftingApi.refreshToc(id, key, head)
    if (!current(id, token)) return
    const displayed = documents.value.find(document => document.fileKey === key)
    if (displayed?.revisionId !== head.revisionId || displayed.docxSha256 !== head.docxSha256 || displayed.stale || documentDirty.value || dirtyInputs.value || reviewDirty.value || candidateDirty.value || automationActive.value) throw Error(phrase('文稿或待保存修改已变化；更新结果未替换当前文稿，请先核对。', 'The document or pending edits changed. The refresh result was not applied; review the current draft.', '文稿或待儲存修改已變化；更新結果未取代目前文稿，請先核對。'))
    if (result.tocFinalization.status !== 'PASS') {
      const reason = result.tocFinalization.reason || phrase('转换及页码核对未完成。', 'Rendering and page verification did not complete.', '轉換及頁碼核對未完成。')
      throw Error(phrase('目录页码未更新，现有文稿已保留。未完成核对的原因：', 'Contents page numbers were not updated; the saved draft was retained. Verification stopped because: ', '目錄頁碼未更新，現有文稿已保留。未完成核對的原因：') + reason)
    }
    const updated = result.document
    if (updated.fileKey !== key || !updated.generated || updated.stale || !updated.revisionId || !updated.docxSha256 || saved.sourceSha256 && updated.sourceSha256 !== saved.sourceSha256 || !result.updated && (updated.revisionId !== head.revisionId || updated.docxSha256 !== head.docxSha256)) throw Error(phrase('更新结果的文稿版本不一致；当前文稿已保留，请重新读取。', 'The refreshed document identity does not match. The current draft was retained; reload it.', '更新結果的文稿版本不一致；目前文稿已保留，請重新讀取。'))
    documents.value = documents.value.map(document => document.fileKey === key ? updated : document)
    documentText.value = updated.content; documentBaseline.value = updated.content; bodyDraft.value = createBodyDraft(updated); editingDocument.value = false; snapshotDraft(id)
    await nextTick()
    if (!current(id, token) || activeDocument.value?.revisionId !== updated.revisionId || activeDocument.value?.docxSha256 !== updated.docxSha256) return
    tocRefreshNotice.value = result.updated ? phrase('目录页码已更新，预览与导出已切换到该保存版本。', 'Contents page numbers were updated. Preview and export now use that saved revision.', '目錄頁碼已更新，預覽與匯出已切換至該儲存版本。') : phrase('目录页码已核对，无需修改。', 'Contents page numbers were verified; no change was needed.', '目錄頁碼已核對，無需修改。')
    if (result.tocFinalization.headingFlowStatus === 'UNKNOWN') tocRefreshNotice.value += phrase(' 条款标题与正文的分页仍需人工核对。', ' Pagination between clause headings and body text still requires review.', ' 條款標題與正文的分頁仍需人工核對。')
  })
}
function editBody(id: string, text: string) { bodyDraft.value.texts[id] = text; snapshotDraft(projectId.value) }
function insertBody(id: string, lines: string[]) { bodyDraft.value.insertions[id] = lines; snapshotDraft(projectId.value) }
function chooseBoundFile(key: string) { if (!busy.value && !documentDirty.value && ['NTT', 'SCT', 'SCC'].includes(key)) activeFile.value = key }
function discardBody() { bodyDraft.value = createBodyDraft(activeDocument.value); documentText.value = activeDocument.value?.content ?? ''; documentBaseline.value = documentText.value; editingDocument.value = false; snapshotDraft(projectId.value) }
function setImmersive(expanded: boolean) {
  immersiveDocument.value = expanded
  documentInfoOpen.value = false
  nextTick(() => { if (expanded) documentWorkspace.value?.focus({ preventScroll: true }); else immersiveToggle.value?.focus({ preventScroll: true }) })
}
function workspaceKeydown(event: KeyboardEvent) {
  if (graphOpen.value || !immersiveDocument.value || event.defaultPrevented) return
  if (event.target instanceof Element && event.target.closest('.layout-check')) return
  if (targetId.value) {
    const dialog = targetModal.value?.$el.querySelector<HTMLElement>('[role="dialog"]')
    if (event.key === 'Tab' && dialog) containTab(event, dialog)
    return
  }
  if (traceOpen.value || previewEditKey.value || removalItem.value) return
  if (event.key === 'Escape') {
    event.preventDefault()
    const pending = documentWorkspace.value?.querySelector<HTMLDetailsElement>('.draft-unresolved[open]')
    if (documentInfoOpen.value) {
      documentInfoOpen.value = false
      nextTick(() => documentInfoToggle.value?.focus({ preventScroll: true }))
    } else if (pending) {
      const restoreFocus = pending.contains(document.activeElement)
      pending.open = false
      if (restoreFocus) nextTick(() => pending.querySelector<HTMLElement>('summary')?.focus({ preventScroll: true }))
    }
    else setImmersive(false)
    return
  }
  if (event.key !== 'Tab' || !documentWorkspace.value) return
  containTab(event, documentWorkspace.value)
}
async function exportDocument(format: 'docx' | 'pdf') { if (canExport.value) await action(w('exporting'), id => draftingApi.exportDocument(id, activeFile.value, format, activeDocument.value?.revisionId)) }
function move(next: Step) { if (busy.value || documentDirty.value || candidateDirty.value || automationActive.value && !['inputs', 'review'].includes(next)) return; if (next === 'review') { void ensureReview(); }; graphNavigation.value = undefined; if (next === 'preview' && !documents.value.some(document => document.generated)) { if (graphMode.value || pendingGraphReview.value) { error.value = phrase('先在采用与修改方案中明确生成文件，再查看预览。', 'Generate documents from the adopted values and plan before opening preview.'); return }; void generate(); return }; step.value = next }
function goInput(key: string) {
  const id = projectId.value, token = generation
  graphNavigation.value = undefined
  step.value = 'variables'; search.value = ''; statusFilter.value = 'all'
  const group = catalog.value.groups.find(item => item.fields.some(field => field.key === key))
  if (group) focusedGroupId.value = group.id
  snapshotDraft(id)
  nextTick(() => {
    if (!current(id, token) || step.value !== 'variables') return
    const el = document.getElementById(`field-${key}`)
    el?.scrollIntoView({ behavior: 'smooth', block: 'center' }); (el?.querySelector('input, select, textarea') as HTMLElement | null)?.focus({ preventScroll: true })
  })
}
function openGraph(event: MouseEvent) {
  if (pendingGraphReview.value) { move('review'); return }
  if (!projectId.value || busy.value || documentDirty.value || !catalog.value.groups.length) return
  graphInvoker = event.currentTarget instanceof HTMLElement ? event.currentTarget : undefined
  graphProject = projectId.value
  graphOpen.value = true
}
function closeGraph(restoreFocus = true) {
  const invoker = graphInvoker, owner = graphProject
  graphOpen.value = false; graphInvoker = undefined; graphProject = ''
  if (restoreFocus) nextTick(() => { if (projectId.value === owner && invoker?.isConnected) invoker.focus({ preventScroll: true }) })
}
function graphInput(key: string) {
  const field = readingFields.value.find(item => item.key === key)
  if (!graphOpen.value || graphProject !== projectId.value || busy.value || documentDirty.value || !field || applicability(field.condition, values) === 'no') return
  closeGraph(false)
  goInput(key)
}
function graphLocation(target: GraphLocationTarget) {
  const field = readingFields.value.find(item => item.key === target.fieldKey)
  if (!graphOpen.value || graphProject !== projectId.value || busy.value || documentDirty.value || !field || !['NTT', 'SCT', 'SCC'].includes(target.document)) return
  const registered = target.actionId
    ? buildBusinessGraph({ catalog: catalog.value, plan: plan.value, values }).actions.some(action => action.id === target.actionId && action.document === target.document && action.clause === target.clause && action.inputKeys.includes(target.fieldKey))
    : field.affects?.some(location => location.document === target.document && location.clause === target.clause)
  if (!registered) return
  closeGraph(false)
  editingDocument.value = false
  graphNavigation.value = { sequence: ++graphSequence, projectId: projectId.value, fileKey: target.document, fieldKey: target.fieldKey, actionId: target.actionId, clause: target.clause }
  activeFile.value = target.document
  step.value = 'preview'
  snapshotDraft(projectId.value)
}
function openReading(field?: DraftField, target?: DraftPlanAction) {
  if (documentDirty.value || busy.value) return
  step.value = 'variables'; readingOpen.value = true
  if (field) readingKey.value = field.key
  else if (target) readingKey.value = (target.inputKeys ?? target.fieldKeys ?? []).find(key => readingFields.value.some(item => item.key === key)) ?? ''
  readingActionId.value = target?.id ?? ''
  const file = target?.document ?? field?.affects?.[0]?.document
  if (file && ['NTT', 'SCT', 'SCC'].includes(file)) readingFile.value = file
  snapshotDraft(projectId.value)
}
function selectReading(key: string) {
  readingKey.value = key; readingActionId.value = ''; goInput(key); snapshotDraft(projectId.value)
  const id = projectId.value, token = generation
  nextTick(() => {
    if (!current(id, token) || !readingOpen.value || readingKey.value !== key || step.value !== 'variables') return
    const panel = document.getElementById('drafting-template-panel')
    const card = panel?.querySelector<HTMLElement>('[data-preview-card]')
    if (!panel || !card) return
    const header = panel.querySelector<HTMLElement>(':scope > header')
    const top = panel.scrollTop + card.getBoundingClientRect().top - panel.getBoundingClientRect().top - panel.clientTop - (header?.offsetHeight ?? 0) - 12
    panel.scrollTo({ top: Math.max(0, top), behavior: 'smooth' })
  })
}
function changeReadingFile(file: string) { if (['NTT', 'SCT', 'SCC'].includes(file)) { readingFile.value = file; readingActionId.value = ''; snapshotDraft(projectId.value) } }
function closeReading() { readingOpen.value = false; previewEditKey.value = ''; snapshotDraft(projectId.value) }
async function goTemplateUpload(fileKey: string) {
  if (busy.value || documentDirty.value || !['NTT', 'SCT', 'SCC'].includes(fileKey)) return
  const id = projectId.value, token = generation
  readingFile.value = fileKey; readingOpen.value = false; previewEditKey.value = ''; step.value = 'inputs'
  snapshotDraft(id)
  await nextTick()
  if (!current(id, token) || step.value !== 'inputs') return
  document.getElementById(`template-source-${fileKey}`)?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  document.getElementById(`template-upload-button-${fileKey}`)?.focus({ preventScroll: true })
}
function chooseTemplate(fileKey: string) {
  if (busy.value || !['NTT', 'SCT', 'SCC'].includes(fileKey)) return
  document.getElementById(`template-upload-${fileKey}`)?.click()
}
function editReading(key: string) { const field = readingFields.value.find(item => item.key === key); if (field && !busy.value && applicability(field.condition, values) !== 'no') previewEditKey.value = key }
async function saveReading() {
  const field = previewEditField.value; if (!field || !dirty(field) || applicability(field.condition, values) === 'no') return
  await action(w('saving'), async (id, token) => {
    await persistDirty(id, token, [field]); if (!current(id, token)) return
    await refresh(id, token); if (current(id, token) && !error.value) previewEditKey.value = ''
  })
}
async function adoptReading() { const field = previewEditField.value; if (field && previewAdoptable(field)) await adopt(field) }
function readingTarget(id: string) { const item = plan.value?.actions.find(action => action.id === id); if (item) openTarget(item) }
function locateReadingTarget(id: string) { const item = plan.value?.actions.find(action => action.id === id); if (item) openReading(readingFields.value.find(field => field.key === readingKey.value), item) }
function overrides(): DraftTargetOverride[] {
  const raw = variableMap.value.get('targetOverrides')?.value || variableMap.value.get('targetEdits')?.value || variableMap.value.get(targetField.value.key)?.value || ''
  try { const list = JSON.parse(raw); return Array.isArray(list) ? list.map(item => ({ ...item, value: item.value ?? item.adoptedText })) : [] } catch { return [] }
}
function targetActionForIssue(issue: DraftUnresolved) { return actionForUnresolved(issue, plan.value?.actions ?? []) }
function openTarget(item: DraftPlanAction | DraftUnresolved) {
  const resolved = 'action' in item ? item : targetActionForIssue(item)
  if (!resolved) return
  targetId.value = resolved.id
  const existing = overrides().find(override => override.actionId === resolved.id)
  targetAction.value = existing?.action ?? 'amend'; targetText.value = existing?.value ?? ''; targetSourceMapping.value = existing?.sourceMapping ?? ''
}
async function saveTarget(remove = false) {
  const field = targetField.value, id = targetId.value; if (!id || !remove && targetAction.value === 'amend' && !targetText.value.trim()) return
  const replacement: DraftTargetOverride | undefined = remove ? undefined : { actionId: id, action: targetAction.value, value: targetText.value, sourceMapping: targetSourceMapping.value, document: selectedTarget.value?.document, clause: selectedTarget.value?.clause }
  const list = replaceTargetOverride(overrides(), id, replacement)
  await action(w('saving'), async (project, token) => { await saveField(project, token, { ...field, key: 'targetOverrides' }, { value: JSON.stringify(list), ...(graphMode.value ? { note: targetSourceMapping.value.trim() || 'Explicitly adopted target amendment.' } : {}) }); if (!current(project, token)) return; targetId.value = ''; await refresh(project, token) })
}
function beforeUnload(event: BeforeUnloadEvent) {
  const cachedChanges = [...projectDrafts.values()].some(cached => Object.keys(dirtyPatch(fields.value, cached.values, cached.baseline)).length > 0 || Object.keys(cached.documents ?? {}).length > 0 || Object.keys(cached.intentionalEmpty ?? {}).some(key => !!cached.intentionalEmpty?.[key] !== !!cached.emptyBaseline?.[key]))
  if (dirtyInputs.value || documentDirty.value || reviewDirty.value || candidateDirty.value || sourceReviewDrafts.size || candidateReviewDrafts.size || cachedChanges) { event.preventDefault(); event.returnValue = w('unsavedLeave') }
}
window.addEventListener('beforeunload', beforeUnload)
window.addEventListener('keydown', workspaceKeydown)
watch(targetId, (id, previous) => {
  if (id && immersiveDocument.value) {
    const focused = document.activeElement
    targetInvoker = focused instanceof window.HTMLElement && documentWorkspace.value?.contains(focused) ? focused : undefined
    targetInvokerProject = projectId.value
    nextTick(() => {
      if (!alive || targetId.value !== id || !immersiveDocument.value || projectId.value !== targetInvokerProject) return
      const dialog = targetModal.value?.$el.querySelector<HTMLElement>('[role="dialog"]')
      if (dialog) focusableControls(dialog)[0]?.focus({ preventScroll: true })
    })
  } else if (!id && previous) {
    const invoker = targetInvoker, project = targetInvokerProject
    targetInvoker = undefined; targetInvokerProject = ''
    nextTick(() => {
      if (!alive || targetId.value || !immersiveDocument.value || projectId.value !== project) return
      if (invoker?.isConnected && invoker.getClientRects().length) invoker.focus({ preventScroll: true })
      else documentWorkspace.value?.focus({ preventScroll: true })
    })
  }
})
watch(filteredGroups, groups => {
  if (restoreViewPending || !catalog.value.groups.length) return
  if (!groups.some(group => group.id === focusedGroupId.value)) { focusedGroupId.value = groups[0]?.id ?? ''; snapshotDraft(projectId.value) }
})
watch(values, () => { if (planTimer) clearTimeout(planTimer); planTimer = setTimeout(() => { if (!busy.value && step.value !== 'inputs') void updatePlan() }, 350) }, { deep: true })
watch(projectId, (id, old) => {
  if (old) snapshotDraft(old)
  closeGraph(false)
  graphNavigation.value = undefined
  stopAutomationPolling(); automation.value = null; automationRevision.value = null; automationError.value = ''; automationChecking.value = false; automationResultPending.value = false; automationRefreshed = ''
  generation++; planGeneration++; busy.value = ''; error.value = ''; tocRefreshNotice.value = ''; reviewDirty.value = false; candidateDirty.value = false; draftRevisionId.value = ''; step.value = 'inputs'; activeFile.value = 'NTT'; editingDocument.value = false; immersiveDocument.value = false; targetId.value = ''; restored.value = false; restoreViewPending = true; traceOpen.value = false
  identityConfirmation.value = undefined; identityError.value = ''
  reviewLayout.value = 'list'; focusedGroupId.value = ''; search.value = ''; statusFilter.value = 'all'
  generationReview.value = undefined; candidateReviewRevision.value = undefined; catalog.value = { ruleVersion: '', groups: [] }; templates.value = []; inputs.value = []; variables.value = []; documentText.value = ''; documentBaseline.value = ''; documents.value = []; plan.value = null; trace.value = null
  bodyDraft.value = createBodyDraft(undefined); removalItem.value = null; readingOpen.value = false; readingKey.value = ''; readingActionId.value = ''; readingFile.value = 'NTT'; previewEditKey.value = ''
  for (const key of Object.keys(values)) delete values[key]; for (const key of Object.keys(baseline)) delete baseline[key]
  for (const key of Object.keys(inputSaveStates)) delete inputSaveStates[key]
  for (const key of Object.keys(manualNotes)) delete manualNotes[key]
  for (const key of Object.keys(intentionalEmpty)) delete intentionalEmpty[key]
  for (const key of Object.keys(emptyBaseline)) delete emptyBaseline[key]
  if (id) void action(w('loading'), async (project, token) => {
    await loadAutomation(project, token, undefined, false)
    if (!current(project, token)) return
    await refresh(project, token)
    if (!current(project, token)) return
    const run = automation.value, selected = generationReview.value?.draft ?? generationReview.value?.current
    if (run?.resultRevisionId && !automationActive.value && selected) {
      automationRevision.value = selected.graph.graph.automaticDiscovery ? selected : null
      automationRefreshed = `${run.id}:${run.updatedAt}`
    } else if (!automationActive.value) await refreshAutomationResult(project, token)
    if (current(project, token) && automationActive.value) { step.value = 'review'; await ensureReview(); scheduleAutomationPoll(project, token) }
  })
}, { immediate: true })
watch(activeFile, () => { tocRefreshNotice.value = ''; if (graphNavigation.value?.fileKey !== activeFile.value) graphNavigation.value = undefined; loadDocument(false) })
watch(activeDocument, (_document, previous) => { tocRefreshNotice.value = ''; if (!bodyDirty(previous, bodyDraft.value)) loadDocument(false) })
watch(documentDirty, dirty => { if (dirty) tocRefreshNotice.value = '' })
watch(step, () => { if (step.value === 'review') void ensureReview(); if (step.value !== 'inputs' && !busy.value && !plan.value) void updatePlan(); if (step.value !== 'preview') { immersiveDocument.value = false; graphNavigation.value = undefined } })
onBeforeUnmount(() => { snapshotDraft(projectId.value); alive = false; generation++; planGeneration++; stopAutomationPolling(); if (planTimer) clearTimeout(planTimer); window.removeEventListener('beforeunload', beforeUnload); window.removeEventListener('keydown', workspaceKeydown) })
</script>

<template>
  <section class="draft-flow" :class="{ 'with-template': readingOpen && step === 'variables' }">
    <header class="flow-head"><div><h2>{{ w('title') }}</h2><p>{{ w('subtitle') }}</p></div><div class="row"><button type="button" class="btn" data-open-business-graph="header" :disabled="!projectId || !!busy || documentDirty || candidateDirty || (!pendingGraphReview && !catalog.groups.length)" @click="openGraph">{{ w('businessGraph') }}</button><span v-if="pendingGraphReview" class="flow-count" data-pending-graph-count>{{ phrase('本次图谱待核对确认', 'Current graph awaiting review and confirmation') }}</span><span v-else class="flow-count">{{ adoptedCount }} / {{ actionableFields.length }} {{ w('adopted') }} · {{ unresolvedInputCount }} {{ w('pending') }}</span></div></header>
    <nav class="flow-steps"><button v-for="item in steps" :key="item.key" type="button" :class="{ active: step === item.key }" :aria-current="step === item.key ? 'step' : undefined" :disabled="!!busy || documentDirty || candidateDirty || (automationActive && !['inputs', 'review'].includes(item.key)) || (item.key === 'preview' && !allTemplates && !documents.some(document => document.generated))" @click="move(item.key)">{{ item.label }}</button></nav>
    <p v-if="!projectId" class="empty-state">{{ w('selectProject') }}</p><p v-if="busy && !immersiveDocument" class="flow-status" role="status">{{ busy }}</p><p v-if="error && !immersiveDocument" class="flow-error" role="alert">{{ error }}</p><p v-if="restored" class="review-note">{{ w('restoreNote') }}</p>
    <button v-if="error && projectId && !busy && !immersiveDocument" type="button" class="btn" data-drafting-retry-read @click="action(w('loading'), refresh)">{{ phrase('重新读取当前项目','Reload current project') }}</button>
    <div v-if="projectId && step === 'inputs'" class="source-stack">
      <section class="flow-card">
        <div class="card-head"><h3>{{ w('templates') }}</h3><label class="btn" :class="{ disabled: !!busy || automationActive }">{{ w('uploadTemplates') }}<input type="file" accept=".doc,.docx,.pdf,.txt,.md" multiple :disabled="!!busy || automationActive" @change="upload($event, 'templates')" /></label></div>
        <p class="hint">{{ w('templateNote') }}</p><p class="hint">{{ w('editableSource') }}</p>
        <div v-for="{ key, template } in templateRows" :id="`template-source-${key}`" :key="key" class="source-row" :class="{ uploaded: template?.tag === 'ok' }">
          <strong>{{ key }}</strong>
          <div>
            <div class="template-file-name">{{ template?.fileName || w('notUploaded') }}</div>
            <span class="tag template-upload-state" :class="{ ok: template?.tag === 'ok', warn: ['partial', 'identity_pending'].includes(template?.tag || ''), danger: template?.tag === 'failed', neutral: !template?.tag || template.tag === 'missing' }"><span v-if="template?.tag === 'ok'" aria-hidden="true">✓</span>{{ l(template?.status) || w('notUploaded') }}</span>
            <small>{{ l(template?.note) }}</small>
          </div>
          <button :id="`template-upload-button-${key}`" type="button" class="btn" :disabled="!!busy || automationActive" :aria-label="`${key} · ${w('replace')}`" @click="chooseTemplate(key)">{{ w('replace') }}</button>
          <input :id="`template-upload-${key}`" type="file" hidden accept=".doc,.docx,.pdf,.txt,.md" :disabled="!!busy || automationActive" @change="upload($event, 'templates', key)" />
        </div>
      </section>
      <section class="flow-card"><div class="card-head"><h3>{{ w('evidence') }}</h3><label class="btn" :class="{ disabled: !!busy || automationActive }">{{ w('uploadEvidence') }}<input type="file" accept=".doc,.docx,.pdf,.txt,.md,.eml,.msg" multiple :disabled="!!busy || automationActive" @change="upload($event, 'inputs')" /></label></div><p class="hint">{{ w('evidenceNote') }}</p><p v-if="!inputs.length" class="hint">{{ w('emptyEvidence') }}</p><div v-for="input in inputs" :key="input.id ?? input.code" class="evidence-row"><div class="card-head"><strong>{{ input.fileName || l(input.title) }}</strong><div class="row"><span>{{ input.status === 'PARSED' ? w('parsedStatus') : ['FAILED', 'PARSE_FAILED'].includes(input.status) ? w('failedStatus') : w('waitingStatus') }}</span><button v-if="input.id !== null && input.id !== undefined" class="btn text-link" :data-remove-input="input.id" :disabled="!!busy || automationActive" @click="removalItem = input">{{ w('removeCorrespondence') }}</button></div></div><small>{{ l(input.message) }} {{ input.pageCount || '' }}</small></div></section>
      <p v-if="automationError" class="flow-error" role="alert">{{ automationError }} <button class="btn" data-reload-automation :disabled="!!busy || automationChecking" @click="loadAutomation(projectId, generation)">{{ phrase('重新读取任务进度', 'Reload task progress') }}</button></p>
      <p class="hint">{{ phrase('本步负责上传和识别文件。继续后，系统会自动识别全部模板变量、进行原子拆解、连接图谱，并从项目资料提取候选值。', 'This step uploads and identifies files. Continue to automatically identify template variables, decompose atomic capabilities, connect the graph and extract candidate values from project sources.') }}</p>
      <footer class="flow-actions"><button class="btn" :disabled="!!busy || automationActive || !catalog.groups.length" @click="move('variables')">{{ w('manualEntry') }}</button><button class="btn primary" data-start-automation :disabled="!!busy || automationActive || automationChecking || !!automationError || reviewDirty || candidateDirty || dirtyInputs || documentDirty || !templates.some(template => ['ok', 'partial'].includes(template.tag))" @click="runAutomation()">{{ phrase('开始识别变量并构建图谱', 'Identify variables and build graph') }}</button><button v-if="automation" class="btn" @click="move('review')">{{ phrase('查看变量识别任务', 'View variable identification task') }}</button></footer>
    </div>
    <div v-if="projectId && reviewMounted" v-show="step === 'review'" class="source-stack" data-review-stage>
      <section class="flow-card automation-card" data-automation-panel>
        <h3>{{ phrase('自动识别变量、原子拆解与构建图谱', 'Automatic variable identification, atomic decomposition and graph construction') }}</h3>
        <p class="hint">{{ phrase('系统连续处理全部模板要求并提取资料候选，无需逐批点击。结果先供核对，不会自动确认图谱或采用项目事实。', 'The system processes template requirements and source candidates continuously. You do not need to run individual batches. Results require review; graph confirmation and fact adoption remain explicit.') }}</p>
        <div v-if="automation" role="status" aria-live="polite" data-automation-progress>
          <strong>{{ automationActive ? automationPhase : automationStatus }}</strong>
          <p>{{ automation.message }}</p><p v-if="automation.currentFile">{{ automationActive ? phrase('当前文件：', 'Current file: ') : phrase('任务最后处理文件：', 'Last file handled by this task: ') }}{{ automation.currentFile }}</p>
          <p>{{ automationActive ? phrase('处理进度：', 'Processing progress: ') : phrase('本次识别任务记录：', 'Identification task record: ') }}{{ automation.completedItems }} / {{ automation.totalItems }} · {{ phrase('要求：', 'Requirements: ') }}{{ automation.requirementsCount }} · {{ phrase('原子节点：', 'Atomic nodes: ') }}{{ automation.nodesCount }}</p>
          <p v-if="automation.failedItems || automation.unresolvedItems" class="condition-note">{{ phrase('失败事项：', 'Failed items: ') }}{{ automation.failedItems }} · {{ automationActive ? phrase('待处理或待人工核对：', 'Unresolved or requiring human review: ') : phrase('任务结束时留待核对的事项：', 'Items left for review when the task ended: ') }}{{ automation.unresolvedItems }}</p>
          <p v-if="!automationActive" class="hint" data-automation-historical>{{ phrase('以上是这次自动识别结束时的记录，不随之后的合并、修改或核对更新。当前变量及待核对数量以下方核对草稿为准。', 'These counts describe the completed identification task and do not change with later merges, edits or reviews. Use the review draft below for current variable and review counts.') }}</p>
        </div>
        <p v-else class="hint">{{ phrase('尚未开始变量识别。点击开始，系统将自动执行并持续显示实际进度。', 'Variable identification has not started. Start once to run the task automatically and follow its actual progress.') }}</p>
        <p v-if="automationError" class="flow-error" role="alert">{{ automationError }}</p>
        <p v-if="automationResultPending" class="condition-note">{{ phrase('已有新的识别结果。请先保存或取消当前未保存修改，再读取结果。', 'New results are available. Save or cancel unsaved edits before loading the results.') }}</p>
        <div class="flow-actions">
          <button v-if="!automationActive && !automationResumable" class="btn primary" data-start-automation :disabled="!!busy || automationChecking || !!automationError || reviewDirty || candidateDirty || dirtyInputs || documentDirty || !templates.some(template => ['ok', 'partial'].includes(template.tag))" @click="runAutomation()">{{ automation ? phrase('按当前文件重新识别变量与图谱', 'Identify variables and graph from current files again') : phrase('开始识别变量并构建图谱', 'Identify variables and build graph') }}</button>
          <button v-if="automationResumable" class="btn primary" data-resume-automation :disabled="!!busy || automationChecking || !!automationError || reviewDirty || candidateDirty || dirtyInputs || documentDirty" @click="runAutomation(true)">{{ phrase('继续未完成的变量识别', 'Resume unfinished variable identification') }}</button>
          <button v-if="automationActive" class="btn" data-cancel-automation :disabled="!!busy" @click="cancelAutomation">{{ phrase('停止任务并保留已有结果', 'Stop task and retain available results') }}</button>
          <button v-if="automationError" class="btn" data-reload-automation :disabled="!!busy || automationChecking" @click="loadAutomation(projectId, generation, automation?.id)">{{ phrase('重新读取任务进度', 'Reload task progress') }}</button>
          <button v-if="automationResultPending" class="btn" data-load-automation-result :disabled="!!busy || reviewDirty || candidateDirty || dirtyInputs || documentDirty" @click="action(phrase('正在读取识别结果', 'Loading identification results'), refreshAutomationResult)">{{ phrase('读取已保存的识别结果', 'Load saved identification results') }}</button>
        </div>
      </section>
      <fieldset class="automation-review" :disabled="automationActive"><DraftingSourceReview ref="sourceReview" :project-id="projectId" :locale="store.locale" :active="step === 'review'" :locked="candidateDirty || automationActive" @dirty="reviewDirty = $event" @saved="reviewSaved" @confirmed="reviewConfirmed" @replace="move('inputs')" />
      </fieldset>
      <DraftingAutomationResults v-if="automationRevision && !automationActive" :revision="automationRevision" :locale="store.locale" @locate="locateCandidate" />
      <footer class="flow-actions"><button class="btn" :disabled="!!busy" @click="move('inputs')">{{ phrase('返回文件识别', 'Back to file identification') }}</button><button class="btn" :disabled="!!busy || automationActive || reviewDirty || candidateDirty" @click="move('variables')">{{ phrase('查看候选值与采用方案', 'Review candidate values and adoption plan') }}</button></footer>
    </div>
    <p v-if="projectId && generationSourceBlocked" class="condition-note" data-generation-source-block role="status">{{ nonEditableSelectedSources.length ? phrase('所选版次只有 PDF 或不可编辑原件，仍可阅读和核对；生成 Word 需先上传同版可编辑 DOCX 并重新核对确认。不能借另一版 Word。', 'The selected edition has a PDF or non-editable original. Reading and review remain available; Word generation needs an editable DOCX of that edition and a newly confirmed review. Another Word edition cannot be substituted.') : phrase('当前确认版本或来源绑定已变化，请在来源核对步骤刷新并确认后再生成。', 'The confirmed revision or source binding changed. Refresh and confirm it in Source review before generation.') }} {{ nonEditableSelectedSources.map(source => source.fileName).join(' · ') }}</p>
    <section v-if="projectId && step === 'variables' && pendingGraphReview" class="flow-card input-stack" data-pending-graph-review>
      <h3>{{ phrase('本次识别的候选值', 'Candidate values from this identification') }}</h3>
      <p>{{ phrase('以下候选值对应第二步保存的图谱。请先核对重复变量、条件和修改位置，并确认这版模板，再采用变量值和生成修改方案。', 'These candidate values belong to the graph saved in step 2. Review duplicate variables, conditions and amendment locations, then confirm this template before adopting values and generating the plan.') }}</p>
      <button class="btn primary" :disabled="candidateDirty" @click="move('review')">{{ phrase('返回第二步核对并确认图谱', 'Review and confirm the graph in step 2') }}</button>
      <button v-if="trace" class="btn" data-pending-extraction-report @click="traceOpen = true">{{ w('trace') }}</button>
      <DraftingCandidateReview :project-id="projectId" :revision-id="draftRevisionId" :review="candidateReviewRevision" :locale="store.locale" :reload-key="candidateReload" @dirty="candidateDirty = $event" @locate="locateCandidate" @question="locateCandidateQuestion" />
    </section>
    <div v-if="projectId && step === 'variables' && !pendingGraphReview" class="input-stack">
      <DraftingCandidateReview v-if="catalog.graphRevisionId" :project-id="projectId" :revision-id="catalog.graphRevisionId" :locale="store.locale" :reload-key="candidateReload" unassigned-only @dirty="candidateDirty = $event" @locate="locateCandidate" @corrected="action(w('loading'), refresh)" />
      <p v-if="generationWorkingRevision?.status === 'DRAFT'" class="condition-note" data-partial-draft-notice>{{ phrase('本次图谱仍在核对。可以保存并采用明确的变量值；只有已核对的修改才会执行。现在生成的未完成草稿会保留未决原文，并附标记及未处理清单。', 'This graph is under review. You can save and adopt known values; only reviewed changes will apply. Incomplete drafts retain unresolved source wording and include a notice and outstanding-items list.') }}</p>
      <div class="input-intro"><p>{{ graphMode ? phrase('只有明确采用且无复核标记的事实进入修改方案。人工值请附理由并保存；模板确认不会代替事实采用。', 'Only explicitly adopted facts without review flags enter the plan. Supply a reason and save manual values. Template confirmation does not adopt facts.') : w('reviewIntro') }}</p><div class="row"><button class="btn" :disabled="!!busy" @click="openReading()">{{ w('templateReading') }}</button><button class="btn" :disabled="!!busy" @click="move('review')">{{ phrase('变量识别与图谱任务', 'Variable identification and graph task') }}</button><button v-if="trace" class="btn" @click="traceOpen = true">{{ w('trace') }}</button></div></div>
      <div class="question-tools"><input v-model="search" :placeholder="w('search')" :aria-label="w('search')" /><select v-model="statusFilter" :aria-label="w('all')"><option value="all">{{ w('all') }}</option><option v-for="status in ['missing', 'suggested', 'adopted', 'manual', 'needs_review', 'conflict', 'inactive'] as DraftWord[]" :key="status" :value="status">{{ w(status) }}</option></select><button class="btn" @click="search = ''; statusFilter = 'all'">{{ w('clearFilters') }}</button></div>
      <div class="review-layout-switch" role="group" :aria-label="w('reviewLayout')"><button class="btn" :class="{ primary: reviewLayout === 'list' }" :aria-pressed="reviewLayout === 'list'" @click="changeReviewLayout('list')">{{ w('listLayout') }}</button><button class="btn" :class="{ primary: reviewLayout === 'focus' }" :aria-pressed="reviewLayout === 'focus'" @click="changeReviewLayout('focus')">{{ w('focusLayout') }}</button></div>
      <p v-if="!filteredGroups.length" class="empty-state">{{ w('noMatches') }}</p>
      <div class="question-review" :class="{ focus: reviewLayout === 'focus' }">
      <nav v-if="reviewLayout === 'focus' && filteredGroups.length" class="question-index" :aria-label="w('questionIndex')"><button v-for="group in filteredGroups" :key="group.id" class="question-index-item" :aria-current="focusedGroup?.id === group.id ? 'step' : undefined" :data-review-group="group.id" @click="chooseGroup(group.id)"><span class="group-number">{{ groupNumber(group) }}</span><span>{{ l(group.label) }}<small class="state" :class="groupState(group)">{{ w(groupState(group)) }}</small></span></button></nav>
      <div class="question-editor">
      <div v-if="reviewLayout === 'focus' && filteredGroups.length" class="question-navigation"><button class="btn" :disabled="focusedGroupIndex <= 0" @click="moveQuestion(-1)">{{ w('previousQuestion') }}</button><span aria-live="polite">{{ w('questionPosition') }} {{ focusedGroupIndex + 1 }} / {{ filteredGroups.length }}</span><button class="btn" :disabled="focusedGroupIndex >= filteredGroups.length - 1" @click="moveQuestion(1)">{{ w('nextQuestion') }}</button></div>
      <section v-for="group in displayedGroups" :id="`group-${group.id}`" :key="group.id" class="flow-card input-group" :data-group="group.id">
        <div class="card-head"><h3><span class="group-number">{{ groupNumber(group) }}</span>{{ l(group.label) }}</h3><span class="state" :class="groupState(group)">{{ w(groupState(group)) }}</span></div><p v-if="group.description" class="hint">{{ l(group.description) }}</p>
        <div v-if="group.id === 'identity' && identityProposal" class="condition-note" data-identity-pending><p>{{ phrase('已采用的名称和编号与当前工作区不同。确认后将替换顶部项目身份，并使包内现有文稿失效；重新生成后采用新身份。', 'The adopted name and number differ from this workspace. Confirm to replace the topbar identity and invalidate existing package drafts, then regenerate them with the new identity.', '已採用名稱及編號與目前工作區不同。確認後將替換頂部項目身分，並使包內現有文稿失效；重新產生後採用新身分。') }}</p><button type="button" class="btn" data-review-project-identity :disabled="!!busy" @click="openIdentityConfirmation">{{ phrase('核对并应用项目身份', 'Review and apply project identity', '核對並套用項目身分') }}</button></div>
        <article v-for="field in visibleFields(group)" :id="`field-${field.key}`" :key="field.key" class="variable" :class="{ 'source-selected': readingOpen && readingKey === field.key }" :data-variable="field.key"><div class="variable-head"><button class="btn text-link" :data-open-template="field.key" :disabled="!!busy" @click="openReading(field)">{{ w('locateTemplate') }}</button><span class="state" :class="state(field)">{{ dirty(field) ? w('unsaved') : w(state(field)) }}</span><button v-if="variableMap.get(field.key)?.reviewRequired" class="btn soft" :disabled="!!busy" @click="adopt(field)">{{ w('reviewed') }}</button><button v-else-if="state(field) === 'suggested'" class="btn soft" :disabled="!!busy" @click="adopt(field)">{{ w('adoptSuggested') }}</button></div><DraftingInputField :field="field" :value="values[field.key]" :values="values" :locale="store.locale" :row-identity-key="graphMode?'_itemId':'id'" :trace="trace" :disabled="inputDisabled(field)" @update="update(field, $event)"><template #actions><div class="field-save-actions"><button type="button" class="btn primary" :data-save-input="field.key" :disabled="!!busy || !dirty(field) || applicability(field.condition, values) === 'no'" @click="saveInput(field)">{{ w('saveInput') }}</button><span v-if="inputSaveStatus(field)" class="field-save-feedback" :class="{ failed: inputSaveStates[field.key] === 'failed' }" :data-input-save-status="field.key" role="status" aria-live="polite">{{ w(inputSaveStatus(field)!) }}</span></div></template></DraftingInputField><label v-if="graphMode && field.kind === 'text'" class="explicit-empty-choice"><input type="checkbox" :checked="!!intentionalEmpty[field.key]" :data-explicit-empty="field.key" :disabled="inputDisabled(field)" @change="toggleEmpty(field, ($event.target as HTMLInputElement).checked)">{{ phrase('明确留空（仍需理由并保存）', 'Intentionally blank (reason and save required)') }}</label><label v-if="variableMap.get(field.key)?.graphRevisionId && dirty(field)" class="manual-adoption-note">{{ phrase('人工采用理由', 'Reason for adopting a manual value') }}<textarea v-model="manualNotes[field.key]" :disabled="!!busy" :data-manual-note="field.key" rows="2" /></label><p v-if="derivedInput(field)" class="hint">{{ w('durationDerived') }}</p><p v-if="['contractPeriodMonths', 'periodAtLeast39Months'].includes(field.key) && durationConflict" class="condition-note">{{ w('durationConflict') }}</p><p v-if="applicability(field.condition, values) === 'unknown'" class="condition-note">{{ w('prerequisite') }}</p><p v-if="validationIssue(field, values[field.key]) && validationIssue(field, values[field.key]) !== 'missing'" class="condition-note">{{ w(validationIssue(field, values[field.key])!) }}</p><p v-if="['siteInspectionStartDate', 'siteInspectionEndDate'].includes(field.key) && reversedSiteDates(values)" class="condition-note">{{ w('reversedDates') }}</p><p v-if="variableMap.get(field.key)?.validationIssue" class="condition-note">{{ variableMap.get(field.key)?.validationIssue }}</p><details class="field-evidence"><summary>{{ w('sourceDetails') }}</summary><blockquote v-if="variableMap.get(field.key)?.source">{{ variableMap.get(field.key)?.source }}</blockquote><p v-else class="hint">{{ w('noSource') }}</p><p v-if="variableMap.get(field.key)?.note" class="hint raw-text">{{ variableMap.get(field.key)?.note }}</p><div v-for="(candidate, candidateIndex) in variableMap.get(field.key)?.candidates ?? []" :key="candidateIndex" class="candidate"><strong>{{ candidate.fileName || w('candidates') }}</strong><DraftingValueDisplay :field="field" :value="candidate.value" :locale="store.locale" /><blockquote>{{ candidate.sourceQuote }}</blockquote><p class="hint raw-text">{{ candidate.reason }}</p><button class="btn" :disabled="!!busy" @click="adopt(field, candidateIndex)">{{ w('adoptCandidate') }}</button></div><p v-for="target in field.affects ?? []" :key="`${target.document}-${target.clause}`" class="hint">{{ target.document }} {{ target.clause }} · {{ target.paragraphs }}</p></details></article>
        <DraftingBillDistribution v-if="group.id === 'bills'" :values="values" :locale="store.locale" /><p v-if="visibleFields(group).length !== group.fields.length" class="hint">{{ w('hiddenFields') }}</p><details v-if="relatedActions(group).length" class="clause-details"><summary>{{ w('affected') }}</summary><div v-for="item in relatedActions(group)" :key="item.id" class="clause-action" :data-action-id="item.id"><div class="card-head"><strong>{{ item.document }} {{ item.clause }}</strong><span class="state">{{ w((item.action in { retain: 1, amend: 1, insert: 1, delete: 1, not_used: 1, pending: 1, not_adopted: 1 } ? item.action : 'pending') as DraftWord) }}</span></div><p class="hint">{{ item.paragraphs }}</p><p>{{ l(item.detail) }}</p><details v-if="item.scopeResults"><summary>{{ phrase('逐项明细的条件判断', 'Condition result for each list item') }}</summary><ol><li v-for="result in item.scopeResults" :key="result.itemId ?? result.rowIndex">{{ phrase('第', 'Item ') }} {{ result.rowIndex + 1 }} {{ result.itemId ? ' · '+result.itemId : '' }} · {{ result.matches === true ? phrase('满足条件', 'Matches') : result.matches === false ? phrase('不满足条件', 'Does not match') : phrase('仍不确定', 'Unknown') }}</li></ol></details><details v-if="item.sourceWarning" class="source-warning"><summary>{{ w('sourceWarning') }}</summary><p class="condition-note">{{ l(item.sourceWarning) }}</p></details><pre v-if="item.value">{{ item.value }}</pre><button class="btn text-link" :disabled="!!busy" @click="openReading(undefined, item)">{{ w('locateTemplate') }}</button><button class="btn text-link" :disabled="!!busy" @click="openTarget(item)">{{ w('exactEdit') }}</button></div></details><footer v-if="group.fields.some(dirty)" class="flow-actions"><button class="btn" :disabled="!!busy" @click="discard(group)">{{ w('discard') }}</button><button class="btn primary" :disabled="!!busy" @click="saveGroup(group)">{{ w('save') }}</button></footer>
      </section>
      </div></div>
      <footer class="flow-actions"><button class="btn" :disabled="!!busy" @click="move('inputs')">{{ w('backSources') }}</button><button class="btn" :disabled="!!busy || !dirtyInputs" @click="saveGroup()">{{ w('saveAll') }}</button><button class="btn primary" :disabled="!!busy || !allTemplates || generationSourceBlocked" @click="generate">{{ w('generate') }}</button></footer><p class="hint" data-generated-output-language>{{ w('englishOutput') }}</p><p class="hint">{{ w(graphMode ? 'reviewedGenerateNote' : 'generateNote') }}</p><p v-if="!allTemplates" class="condition-note">{{ w('missingTemplates') }}</p>
      <details v-if="plan?.unresolved.length" class="flow-card"><summary>{{ w('unresolved') }} ({{ plan.unresolved.length }})</summary><DraftingUnresolvedItems :items="plan.unresolved" :fields="fields" :values="values" :actions="plan.actions" :locale="store.locale" @input="goInput" @target="openTarget" /></details>
    </div>
    <section v-if="projectId && step === 'preview'" ref="documentWorkspace" class="flow-card preview-card" :class="{ 'preview-card--immersive': immersiveDocument }" data-document-workspace :role="immersiveDocument ? 'dialog' : undefined" :aria-modal="immersiveDocument ? true : undefined" :aria-label="w('documentWorkspace')" tabindex="-1">
      <div class="card-head">
        <div class="document-tabs"><button v-for="key in ['NTT', 'SCT', 'SCC']" :key="key" class="btn" :class="{ primary: activeFile === key }" :disabled="!!busy || documentDirty" @click="activeFile = key">{{ key }}</button></div>
        <div class="document-tools"><button class="btn" :disabled="!!busy || !documentAccess.editable || documentDirty" @click="editingDocument = !editingDocument">{{ editingDocument && !activeDocument?.stale ? w('readDocument') : w('editContent') }}</button><template v-if="documentDirty"><button class="btn" :disabled="!!busy" @click="discardBody">{{ w('discard') }}</button><button class="btn primary" :disabled="!!busy || !documentAccess.editable" @click="saveDocument">{{ w('saveContent') }}</button></template><button v-if="activeDocument?.stale" class="btn primary" :disabled="!!busy || !allTemplates || generationSourceBlocked || documentDirty" @click="generate">{{ w('regenerate') }}</button><button v-if="immersiveDocument" ref="documentInfoToggle" type="button" class="btn" :aria-expanded="documentInfoOpen" aria-controls="draft-document-info" @click="documentInfoOpen = !documentInfoOpen">{{ w('documentInformation') }}</button></div>
        <div class="row"><button v-if="immersiveDocument" type="button" class="btn" data-open-business-graph="immersive" :disabled="!!busy || documentDirty || !catalog.groups.length" @click="openGraph">{{ w('businessGraph') }}</button><button v-if="activeFile === 'SCC' && activeDocument?.generated" type="button" class="btn" data-refresh-toc :disabled="!canRefreshToc" @click="refreshDocumentToc">{{ phrase('更新目录及预览', 'Update contents and preview', '更新目錄及預覽') }}</button><button class="btn" :disabled="!!busy || !canExport" @click="exportDocument('docx')">{{ w('exportWord') }}</button><button class="btn primary" :disabled="!!busy || !canExport" @click="exportDocument('pdf')">{{ w('exportPdf') }}</button><button ref="immersiveToggle" type="button" class="btn" :aria-expanded="immersiveDocument" @click="setImmersive(!immersiveDocument)">{{ immersiveDocument ? w('exitImmersive') : w('immersivePreview') }}</button></div>
      </div>
      <p class="hint" data-generated-output-language>{{ w('englishOutput') }}</p>
      <div v-show="!immersiveDocument || documentInfoOpen" class="document-info" id="draft-document-info">
      <h3>{{ activeDocument?.title }}</h3>
      <p v-if="activeDocument?.revisionId" class="document-revision-status" role="status"><span>{{ phrase('当前显示已保存的文稿', 'Showing the saved document', '目前顯示已儲存的文稿') }}</span><strong v-if="documentDirty">{{ w('pendingBodyRevision') }}</strong></p>
      <div v-if="activeDocument?.generated" class="document-layout-limits" data-draft-layout-limits><strong>{{ l({ en: 'Layout checks remain unverified', zhHans: '版式核对仍未验证', zhHant: '版式核對仍未驗證' }) }}</strong><p>{{ w('formatReview') }}</p></div>
      </div>
      <p v-if="tocRefreshNotice && !documentDirty && !dirtyInputs && !activeDocument?.stale" class="hint" data-toc-refresh-status role="status">{{ tocRefreshNotice }}</p>
      <p v-if="documentDirty" class="condition-note">{{ w('pendingBodyNote') }}</p>
      <p v-if="activeDocument?.stale || dirtyInputs" class="review-note" data-draft-stale-status role="status">{{ w('stale') }} <span v-if="activeDocument?.stale">{{ w('staleReadOnly') }}</span></p>
      <p v-if="immersiveDocument && busy" class="flow-status" role="status">{{ busy }}</p><p v-if="immersiveDocument && error" class="flow-error" role="alert">{{ error }}</p>
      <div class="document-surface">
      <DraftingDocumentWorkspace :project-id="projectId" :file-key="activeFile" :document="activeDocument" :dirty="documentDirty" :editing="editingDocument" :locale="store.locale" :immersive="immersiveDocument" :fields="readingFields" :values="values" :variables="variables" :actions="plan?.actions ?? []" :field-states="readingStates" :dirty-keys="readingDirtyKeys" :trace="trace" :graph-navigation="graphNavigation" :disabled="!!busy" :stale-notice-presented="true" @input="goInput" @file="chooseBoundFile">
        <DraftingBodyEditor v-if="activeDocument?.blocks" :blocks="activeDocument.blocks" :draft="bodyDraft" :locale="store.locale" :readonly="!documentAccess.editable" :disabled="!!busy" :immersive="immersiveDocument" @text="editBody" @insert="insertBody" />
        <pre v-else class="raw-text">{{ documentText }}</pre>
      </DraftingDocumentWorkspace>
      </div>
      <details v-if="currentUnresolved.length" class="draft-unresolved" :open="!immersiveDocument"><summary>{{ w('unresolved') }} ({{ currentUnresolved.length }})</summary><DraftingUnresolvedItems :items="currentUnresolved" :fields="fields" :values="values" :actions="plan?.actions ?? []" :locale="store.locale" @input="goInput" @target="openTarget" /></details>
      <footer class="flow-actions"><button class="btn" :disabled="!!busy || documentDirty" @click="move('variables')">{{ w('backInputs') }}</button><p v-if="immersiveDocument" class="hint regeneration-warning">{{ w('regenerationNote') }}</p><button class="btn" :disabled="!!busy || !allTemplates || generationSourceBlocked || documentDirty" @click="generate">{{ w('regenerate') }}</button></footer><p v-if="!immersiveDocument" class="hint">{{ w('regenerationNote') }}</p>
    </section>
    <footer v-if="catalog.ruleVersion" class="hint">{{ graphMode ? generationWorkingRevision?.status === 'CONFIRMED' ? phrase('当前使用已确认的模板核对版本', 'Using the confirmed template review') : phrase('当前使用已保存的核对草稿；未决修改保留原文。', 'Using the saved review draft; unresolved changes retain the source wording.') : phrase('当前使用预设起草规则', 'Using the preset drafting rules') }}</footer>
    <aside v-if="projectId && step === 'variables' && readingOpen" id="drafting-template-panel" class="template-side-panel" data-template-panel :aria-label="w('templateReading')">
      <header class="card-head"><h3>{{ w('templateReading') }}</h3><button class="btn" @click="closeReading">{{ w('close') }}</button></header>
      <DraftingTemplatePreview :project-id="projectId" :file-key="readingFile" :source-available="readingSourceAvailable" :locale="store.locale" :fields="readingFields" :values="values" :variables="variables" :actions="plan?.actions ?? []" :selected-key="readingKey" :selected-action-id="readingActionId" :field-states="readingStates" :dirty-keys="readingDirtyKeys" :disabled="!!busy" @select="selectReading" @edit="editReading" @input="selectReading" @target="readingTarget" @locate="locateReadingTarget" @file="changeReadingFile" @upload="goTemplateUpload" />
    </aside>
    <DraftingBusinessGraph v-if="graphOpen" :project-id="projectId" :catalog="catalog" :plan="plan" :values="values" :variables="variables" :field-states="readingStates" :dirty-keys="readingDirtyKeys" :locale="store.locale" :disabled="!!busy || documentDirty" @input="graphInput" @location="graphLocation" @close="closeGraph()" />
    <AppModal :open="!!identityConfirmation" :title="phrase('是否替换当前项目身份？', 'Replace the current project identity?', '是否替換目前項目身分？')" @close="!busy && (identityConfirmation = undefined)">
      <template v-if="identityConfirmation"><p>{{ phrase('以下值已经采用。只有点击确认才会替换工作区名称与合约编号，并贯穿此项目包。', 'These values are already adopted. Confirm to replace this workspace name and contract number across the project package.', '以下值已經採用。只有按下確認才會替換工作區名稱與合約編號，並貫穿此項目包。') }}</p><table data-project-identity-comparison><thead><tr><th></th><th>{{ phrase('当前', 'Current', '目前') }}</th><th>{{ phrase('拟采用', 'Proposed', '擬採用') }}</th></tr></thead><tbody><tr><th>{{ phrase('项目名称', 'Project name', '項目名稱') }}</th><td>{{ identityConfirmation.currentName || '—' }}</td><td>{{ identityConfirmation.name }}</td></tr><tr><th>{{ phrase('合约编号', 'Contract number', '合約編號') }}</th><td>{{ identityConfirmation.request.expectedProjectIdentity.contractNo || '—' }}</td><td>{{ identityConfirmation.number }}</td></tr></tbody></table><p class="hint">{{ phrase('确认后现有文稿须重新生成。来源文件和候选证据仍保留。', 'Existing drafts must be regenerated after confirmation. Source files and candidate evidence remain available.', '確認後現有文稿須重新產生。來源文件與候選證據仍保留。') }}</p><p v-if="identityError" role="alert" data-identity-error>{{ identityError }}</p></template>
      <template #footer><button class="btn" :disabled="!!busy" data-cancel-project-identity @click="identityConfirmation = undefined">{{ phrase('暂不替换', 'Keep current identity', '暫不替換') }}</button><button v-if="identityError" class="btn" :disabled="!!busy" data-reload-project-identity @click="reloadIdentityConfirmation">{{ phrase('刷新并核对', 'Reload and review', '重新整理並核對') }}</button><button class="btn primary" :disabled="!!busy || !identityConfirmation" data-confirm-project-identity @click="confirmIdentity">{{ phrase('确认替换项目身份', 'Confirm project identity', '確認替換項目身分') }}</button></template>
    </AppModal>
    <AppModal :open="!!previewEditField" :title="w('editPreviewValue')" @close="previewEditKey = ''">
      <template v-if="previewEditField"><span class="state">{{ dirty(previewEditField) ? w('unsavedValue') : w(state(previewEditField)) }}</span><p class="hint">{{ w('previewEditNote') }}</p><DraftingInputField :field="previewEditField" :value="values[previewEditField.key]" :values="values" :locale="store.locale" id-prefix="preview-edit" :row-identity-key="graphMode?'_itemId':'id'" :disabled="inputDisabled(previewEditField)" @update="update(previewEditField, $event)" /><p v-if="derivedInput(previewEditField)" class="hint">{{ w('durationDerived') }}</p><p v-if="error" class="flow-error" role="alert">{{ error }}</p><div class="flow-actions"><button class="btn" :disabled="!!busy" @click="previewEditKey = ''">{{ w('close') }}</button><button v-if="previewAdoptable(previewEditField)" class="btn" :disabled="!!busy" @click="adoptReading">{{ w('adoptSuggested') }}</button><button class="btn primary" :data-preview-save="previewEditField.key" :disabled="!!busy || !dirty(previewEditField) || applicability(previewEditField.condition, values) === 'no'" @click="saveReading">{{ w('save') }}</button></div></template>
    </AppModal>
    <AppModal :open="traceOpen" :title="w('trace')" wide @close="traceOpen = false"><DraftingExtractionReport :trace="trace" :project-id="projectId" :locale="store.locale" :open="traceOpen" /></AppModal>
    <AppModal :open="!!removalItem" :title="w('removeCorrespondence')" @close="removalItem = null">
      <template v-if="removalItem"><strong>{{ removalItem.fileName || l(removalItem.title) }}</strong><p class="condition-note">{{ w('removeCorrespondenceNote') }}</p><p v-if="error" class="flow-error" role="alert">{{ error }}</p><div class="flow-actions"><button class="btn" :disabled="!!busy" @click="removalItem = null">{{ w('close') }}</button><button class="btn primary" :disabled="!!busy" @click="removeCorrespondence">{{ w('removeFromProject') }}</button></div></template>
    </AppModal>
    <AppModal ref="targetModal" :open="!!targetId" :title="w('exactEdit')" wide @close="targetId = ''"><template v-if="selectedTarget"><p v-if="busy" class="flow-status" role="status">{{ busy }}</p><p v-if="error" class="flow-error" role="alert">{{ error }}</p><strong>{{ selectedTarget.document }} {{ selectedTarget.clause }}</strong><p class="hint">{{ selectedTarget.paragraphs }}</p><p>{{ l(selectedTarget.detail) }}</p><p v-if="selectedTarget.sourceWarning" class="condition-note"><strong>{{ w('sourceWarning') }}</strong><br />{{ l(selectedTarget.sourceWarning) }}</p><details v-if="selectedTarget.sourceText" class="source-target"><summary>{{ w('sourceTarget') }}</summary><pre lang="en">{{ selectedTarget.sourceText }}</pre></details><p class="condition-note">{{ w('targetNote') }}</p><label>{{ w('targetAction') }}<select v-model="targetAction" :disabled="!!busy"><option v-for="item in ['retain', 'amend', 'delete', 'not_used'] as const" :key="item" :value="item">{{ w(item) }}</option></select></label><label v-if="targetAction === 'amend'">{{ w('targetText') }}<textarea v-model="targetText" rows="8" :disabled="!!busy" /></label><label>{{ w('sourceMapping') }}<textarea v-model="targetSourceMapping" rows="3" :disabled="!!busy" /></label><p class="hint">{{ w('sourceMappingNote') }}</p><div class="flow-actions"><button class="btn" :disabled="!!busy" @click="saveTarget(true)">{{ w('targetReset') }}</button><button class="btn primary" :disabled="!!busy || (targetAction === 'amend' && !targetText.trim())" @click="saveTarget()">{{ w('targetSave') }}</button></div></template></AppModal>
  </section>
</template>

<style scoped>
.preview-card.preview-card--immersive { position: fixed; inset: 0; z-index: 100; height: 100dvh; box-sizing: border-box; border-radius: 0; display: flex; flex-direction: column; gap: 4px; padding: 8px 12px; overflow: hidden; background: var(--surface); }
.preview-card--immersive > * { flex-shrink: 0; }
.preview-card--immersive .document-surface { flex: 1 1 0; min-height: 0; overflow: hidden; }
.document-info { display: contents; }
.preview-card--immersive .document-info { position: absolute; top: 88px; right: 12px; z-index: 8; width: min(540px, calc(100% - 24px)); max-height: min(50dvh, 420px); box-sizing: border-box; padding: 16px; overflow: auto; display: flex; flex-direction: column; gap: 12px; border: 1px solid var(--line); border-radius: var(--radius); background: var(--surface); box-shadow: var(--shadow); }
.preview-card--immersive .document-tools { margin: 0; }
.card-head .document-tools { margin: 0; }
.preview-card--immersive .flow-actions { margin-top: 0; padding-top: 4px; }
.regeneration-warning { flex: 1; text-align: right; }
.preview-card--immersive .draft-unresolved { position: absolute; right: 12px; bottom: 74px; z-index: 7; width: min(480px, calc(100% - 24px)); max-height: calc(100dvh - 180px); margin: 0; padding: 10px 12px; box-sizing: border-box; overflow: auto; border: 1px solid var(--line); border-radius: var(--radius); background: var(--surface); box-shadow: var(--shadow); }
.preview-card--immersive .draft-unresolved:not([open]) { width: min(310px, calc(100% - 24px)); bottom: 58px; padding: 6px 12px; }
.preview-card--immersive .draft-unresolved > summary { position: sticky; top: -10px; z-index: 1; padding: 4px 0; background: var(--surface); }
.preview-card--immersive .document-versions { max-height: min(18dvh, 140px); overflow: auto; }
.draft-flow:has(.preview-card--immersive) :deep(.modal-backdrop) { z-index: 120; }
.preview-card.preview-card--immersive h3, .preview-card.preview-card--immersive p { margin: 0; }
.document-revision-status { display: flex; gap: 12px; flex-wrap: wrap; font-size: 12px; overflow-wrap: anywhere; }
.document-revision-status strong { color: var(--amber); }
.document-versions { font-size: 12px; color: var(--muted); overflow-wrap: anywhere; }
.document-versions summary { cursor: pointer; }
.draft-flow {
  --draft-reading-width: clamp(460px, calc((100vw - var(--workspace-sidebar) - 80px) * .42), 600px);
  width: 100%; max-width: 1560px; margin: 0 auto; padding: 24px 28px 42px;
  display: flex; flex-direction: column; gap: 16px; color: var(--ink);
}
.flow-head,.card-head,.variable-head,.flow-actions,.input-intro,.document-tools,.row {
  display: flex; align-items: center; justify-content: space-between; gap: 10px; flex-wrap: wrap;
}
.row { justify-content: flex-start; }
h2,h3,p { margin: 0; }
h2 { font-size: 22px; line-height: 1.3; }
.flow-head p { margin-top: 5px; color: var(--muted); font-size: 13px; }
.flow-count { font-size: 11.5px; font-weight: 700; padding: 5px 10px; background: var(--accent-soft); color: var(--accent-dark); border-radius: 999px; white-space: nowrap; }
.flow-steps { display: grid; grid-template-columns: repeat(4,minmax(0,1fr)); gap: 8px; }
.flow-steps button { background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius); padding: 10px 12px; min-height: 46px; text-align: left; color: var(--ink-soft); font: inherit; font-size: 12px; font-weight: 600; }
.explicit-empty-choice { display: flex; align-items: center; gap: 6px; margin: 10px 0; font-size: 12px; }
.manual-adoption-note { display: block; font-size: 12px; margin: 12px 0; }
.manual-adoption-note textarea { display: block; width: 100%; box-sizing: border-box; margin-top: 6px; padding: 8px; border: 1px solid var(--line); border-radius: 5px; font: inherit; background: var(--surface); color: var(--ink); }
.automation-card { border-color: var(--accent); }
.automation-review { border: 0; padding: 0; margin: 0; min-width: 0; }
.automation-card [role=status] { border-left: 3px solid var(--accent); padding-left: 12px; margin: 16px 0; }
.flow-steps button.active { border-color: var(--accent); background: var(--accent-soft); color: var(--accent-dark); }
.source-stack,.input-stack { display: flex; flex-direction: column; gap: 12px; padding: 16px; background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius); box-shadow: var(--shadow); min-width: 0; }
.flow-card { background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius); padding: 14px 16px; min-width: 0; }
.flow-card h3 { font-size: 14px; line-height: 1.4; }
.group-number { display: inline-flex; align-items: center; justify-content: center; background: var(--accent-soft); color: var(--accent-dark); width: 22px; height: 22px; border-radius: 50%; margin-right: 8px; font-size: 11px; }
.source-row { display: grid; grid-template-columns: 45px minmax(0,1fr) auto; gap: 12px; align-items: center; padding: 12px 0; border-bottom: 1px solid var(--line); }
.source-row:last-child { border: 0; }
.source-row.uploaded { background: var(--green-soft); box-shadow: inset 3px 0 0 var(--green); border-radius: var(--radius-sm); padding: 12px; margin: 6px 0; border-bottom-color: transparent; }
.template-file-name { font-weight: 600; }
.template-upload-state { margin-top: 7px; border: 1px solid currentColor; }
.source-row small,.evidence-row small { color: var(--muted); display: block; font-size: 12px; line-height: 1.55; margin-top: 5px; }
.source-row div { overflow-wrap: anywhere; }
.evidence-row { padding: 12px 0; border-bottom: 1px solid var(--line); }
.evidence-row>span { float: right; font-size: 12px; color: var(--muted); }
label.btn input[type=file] { display: none; }
.disabled { opacity: .5; pointer-events: none; }
.variable { padding: 12px 0; border-bottom: 1px solid var(--line); }
.variable:last-of-type { border-bottom: 0; }
.variable-head { justify-content: flex-end; gap: 8px; margin-bottom: 4px; }
.variable.source-selected { border-left: 3px solid var(--accent); padding-left: 10px; }
.btn.text-link { min-height: 26px; padding: 0 4px; border-color: transparent; background: transparent; color: var(--accent-dark); font-size: 12px; }
.btn.text-link:hover { background: var(--accent-soft); }
.state { display: inline-flex; align-items: center; font-size: 11.5px; font-weight: 700; line-height: 1.4; border-radius: 999px; padding: 3px 8px; background: var(--surface-subtle); color: var(--muted); }
.state.adopted,.state.manual { background: var(--green-soft); color: var(--green); }
.field-save-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; margin-top: 10px; }
.field-save-feedback { color: var(--muted); font-size: 12px; }
.field-save-feedback.failed { color: var(--red); }
.state.missing,.state.needs_review { background: var(--amber-soft); color: var(--amber); }
.state.conflict { background: var(--red-soft); color: var(--red); }
.hint,.field-evidence,.clause-details { font-size: 12px; color: var(--muted); line-height: 1.6; }
.hint { margin: 6px 0; }
.condition-note,.review-note { background: var(--amber-soft); color: var(--amber); padding: 8px 10px; border-radius: var(--radius-sm); font-size: 12px; line-height: 1.6; margin: 8px 0; }
.field-evidence summary,.clause-details summary,.draft-unresolved summary { cursor: pointer; padding: 7px 0; }
.field-evidence blockquote { border-left: 3px solid var(--line-strong); margin: 10px 0; padding-left: 12px; white-space: pre-wrap; color: var(--ink-soft); }
.candidate { margin-top: 12px; padding: 12px 14px; background: var(--surface-subtle); border: 1px solid var(--line); border-radius: var(--radius); }
.candidate pre { color: var(--ink); }
.raw-text { white-space: pre-wrap; }
.flow-actions { justify-content: flex-end; padding-top: 12px; border-top: 1px solid var(--line); margin-top: 4px; }
.question-tools { display: flex; gap: 8px; }
.question-tools>input { flex: 1; min-width: 0; }
.question-tools>select { max-width: 200px; }
.input-stack { container-type: inline-size; container-name: drafting-review; }
.review-layout-switch { display: flex; flex-wrap: wrap; gap: 8px; }
.question-review,.question-editor { min-width: 0; }
.question-editor { display: flex; flex-direction: column; gap: 12px; }
.question-review.focus { display: grid; grid-template-columns: minmax(0,1fr); align-items: start; gap: 16px; }
.question-index { display: flex; flex-direction: column; gap: 6px; max-height: 260px; overflow: auto; padding: 4px; border: 1px solid var(--line); border-radius: var(--radius); background: var(--surface-subtle); }
.question-index-item { display: flex; align-items: flex-start; min-width: 0; width: 100%; gap: 4px; padding: 10px 8px; border: 1px solid transparent; border-radius: var(--radius-sm); background: transparent; color: var(--ink-soft); font: inherit; font-size: 12px; text-align: left; cursor: pointer; }
.question-index-item[aria-current=step] { border-color: var(--accent); background: var(--accent-soft); color: var(--accent-dark); }
.question-index-item:hover { background: var(--surface); }
.question-index-item:focus-visible { outline: none; box-shadow: var(--focus); }
.question-index-item>.group-number { flex-shrink: 0; margin-right: 3px; }
.question-index-item>span:last-child { min-width: 0; overflow-wrap: anywhere; }
.question-index-item .state { display: table; margin-top: 6px; }
.question-navigation { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 8px; font-size: 12px; color: var(--muted); }
@container drafting-review (min-width: 800px) {
  .question-review.focus { grid-template-columns: 220px minmax(0,1fr); }
  .question-index { max-height: min(64vh,640px); }
}
.flow-status { padding: 10px 12px; border-radius: var(--radius-sm); background: var(--accent-soft); color: var(--accent-dark); font-size: 12px; }
.flow-error { padding: 10px 12px; background: var(--red-soft); color: var(--red); white-space: pre-wrap; border-radius: var(--radius-sm); line-height: 1.6; font-size: 12px; }
.input-intro p { flex: 1; min-width: 270px; color: var(--muted); font-size: 12px; line-height: 1.55; }
.clause-action { padding: 12px 0; border-bottom: 1px solid var(--line); line-height: 1.6; }
.clause-action p { margin: 6px 0; }
.clause-action pre { background: var(--surface-subtle); padding: 10px; }
.document-tabs { display: flex; gap: 6px; }
.preview-card h3 { margin: 16px 0; }
.document-tools { justify-content: flex-start; margin: 12px 0; }
.pdf-preview { width: 100%; height: 70vh; min-height: 470px; border: 1px solid var(--line); border-radius: var(--radius-sm); }
.document-editor { height: 65vh; font-family: var(--font-mono); font-size: 13px; line-height: 1.7; }
.draft-unresolved { margin-top: 16px; }
input,select,textarea { width: 100%; box-sizing: border-box; min-height: 36px; border: 1px solid var(--line); border-radius: 6px; padding: 6px 10px; font: inherit; font-size: 13px; background: var(--surface); color: var(--ink); }
textarea { resize: vertical; }
label { display: block; font-size: 12px; margin: 10px 0; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; font-size: 12px; max-height: 50vh; overflow: auto; }
button:disabled { opacity: .5; cursor: not-allowed; }
.template-side-panel {
  position: fixed; z-index: 40; inset: 16px 16px 16px auto; width: min(600px,calc(100vw - 32px));
  box-sizing: border-box; display: flex; flex-direction: column; gap: 12px; padding: 16px;
  background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius); box-shadow: var(--shadow); overflow: auto;
}
.template-side-panel>header { flex-shrink: 0; position: sticky; top: 0; z-index: 1; background: var(--surface); padding-bottom: 8px; }
.template-side-panel>header h3 { font-size: 16px; }
.template-side-panel :deep(.template-reading) { min-height: 0; flex: 1; border: 0; padding: 0; }
@container workspace (min-width: 1200px) {
  .draft-flow.with-template { width: auto; max-width: none; margin-left: 0; margin-right: calc(var(--draft-reading-width) + 28px); }
  .template-side-panel { top: 88px; right: 28px; bottom: 24px; width: var(--draft-reading-width); }
}
@container workspace (max-width: 640px) {
  .draft-flow { padding: 18px 16px 32px; }
  .flow-steps { grid-template-columns: 1fr 1fr; gap: 6px; }
  .flow-steps button { padding: 9px 10px; font-size: 11.5px; }
  .flow-card,.source-stack,.input-stack { padding: 12px; }
  .source-row { grid-template-columns: 40px minmax(0,1fr); }
  .source-row>button { grid-column: 2; justify-self: start; }
  .question-tools { flex-wrap: wrap; }
  .question-tools>input { flex-basis: 100%; }
  .question-tools>select { flex: 1; max-width: none; }
  .input-intro p { min-width: 100%; }
  .flow-count { white-space: normal; }
  .pdf-preview { min-height: 380px; }
}
@media(max-width: 560px) {
  .template-side-panel { inset: 8px; width: auto; }
}
</style>
