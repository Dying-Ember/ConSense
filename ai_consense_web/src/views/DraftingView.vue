<script setup lang="ts">
import type { GraphLocationTarget, GraphNavigation } from '@/drafting/graph-navigation'
import { computed, defineAsyncComponent, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { draftingApi } from '@/api'
import type { DraftCatalog, DraftDocument, DraftField, DraftGroup, DraftPlan, DraftPlanAction, DraftTargetOverride, DraftUnresolved, DraftVariable, DraftVariablePatch, EvidenceItem, ExtractTrace, TemplateItem } from '@/api'
import { useAppStore } from '@/stores/app'
import AppModal from '@/components/AppModal.vue'
import DraftingInputField from '@/components/DraftingInputField.vue'
import DraftingExtractionReport from '@/components/DraftingExtractionReport.vue'
import DraftingBillDistribution from '@/components/DraftingBillDistribution.vue'
import DraftingDocumentWorkspace from '@/components/DraftingDocumentWorkspace.vue'
import DraftingTemplatePreview from '@/components/DraftingTemplatePreview.vue'
import DraftingValueDisplay from '@/components/DraftingValueDisplay.vue'
import DraftingUnresolvedItems from '@/components/DraftingUnresolvedItems.vue'
import DraftingBodyEditor from '@/components/DraftingBodyEditor.vue'
import { bodyDirty, bodyPatch, createBodyDraft } from '@/drafting/body-edit'
import { actionForUnresolved, adoptionPatch, applicability, clone, decode, dirtyPatch, documentCapabilities, encode, mergeServerValues, projectDrafts, replaceTargetOverride, restoreDraftView, reversedSiteDates, validationIssue, type DraftValue } from '@/drafting/state'
import { draftWord, localized, type DraftWord } from '@/drafting/words'
import { buildBusinessGraph } from '@/drafting/business-graph'
const DraftingBusinessGraph = defineAsyncComponent(() => import('@/components/DraftingBusinessGraph.vue').then(module => module.default))

const store = useAppStore()
const w = (key: DraftWord) => draftWord(key, store.locale)
const l = (text: Parameters<typeof localized>[0]) => localized(text, store.locale)
type Step = 'inputs' | 'variables' | 'preview'


const projectId = computed(() => store.activeProjectId)
const step = ref<Step>('inputs')
const catalog = ref<DraftCatalog>({ ruleVersion: '', groups: [] })
const templates = ref<TemplateItem[]>([])
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
  return tag === 'missing' ? false : tag === 'ok' ? true : undefined
})
const variableMap = computed(() => new Map(variables.value.map(variable => [variable.key, variable])))
const steps = computed(() => [{ key: 'inputs' as Step, label: w('documents') }, { key: 'variables' as Step, label: w('inputs') }, { key: 'preview' as Step, label: w('preview') }])
const allTemplates = computed(() => ['NTT', 'SCT', 'SCC'].every(key => templates.value.some(template => template.key === key && template.tag === 'ok')))
const activeDocument = computed(() => documents.value.find(document => document.fileKey === activeFile.value))
const documentDirty = computed(() => bodyDirty(activeDocument.value, bodyDraft.value))
const dirtyInputs = computed(() => fields.value.some(dirty))
const documentAccess = computed(() => documentCapabilities(activeDocument.value, documentDirty.value))
const canExport = computed(() => documentAccess.value.exportable)
const durationKnown = computed(() => values.contractPeriodMonths !== null && values.contractPeriodMonths !== undefined && values.contractPeriodMonths !== '' && Number.isFinite(Number(values.contractPeriodMonths)) && Number(values.contractPeriodMonths) >= 0)
const durationAdopted = computed(() => durationKnown.value && (dirty(fields.value.find(field => field.key === 'contractPeriodMonths') ?? { key: 'contractPeriodMonths', kind: 'number', label: { en: '', zhHans: '', zhHant: '' } }) || variableMap.value.get('contractPeriodMonths')?.confirmed || variableMap.value.get('contractPeriodMonths')?.manuallyEdited))
const thresholdPresent = computed(() => values.periodAtLeast39Months !== null && values.periodAtLeast39Months !== undefined && values.periodAtLeast39Months !== '')
const durationThreshold = computed(() => String(Number(values.contractPeriodMonths) >= 39))
const durationConflict = computed(() => durationKnown.value && values.periodAtLeast39Months !== null && values.periodAtLeast39Months !== undefined && values.periodAtLeast39Months !== '' && String(values.periodAtLeast39Months) !== durationThreshold.value)
const actionableFields = computed(() => fields.value.filter(field => !field.hidden && applicability(field.condition, values) !== 'no'))
const unresolvedInputCount = computed(() => actionableFields.value.filter(field => !field.optional && !['adopted', 'manual'].includes(state(field))).length)
const adoptedCount = computed(() => actionableFields.value.filter(field => ['adopted', 'manual'].includes(state(field))).length)
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
function inputDisabled(field: DraftField) { return !!busy.value || applicability(field.condition, values) === 'no' || derivedInput(field) }
function previewAdoptable(field: DraftField) {
  const variable = variableMap.value.get(field.key)
  return !dirty(field) && encode(field, values[field.key]).trim() !== '' && validationIssue(field, values[field.key]) === null && !variable?.validationIssue && applicability(field.condition, values) !== 'no' && (state(field) === 'suggested' || !!variable?.reviewRequired)
}
function dirty(field: DraftField) { return encode(field, values[field.key]) !== (baseline[field.key] ?? '') }
function inputSaveStatus(field: DraftField): DraftWord | undefined {
  const status = inputSaveStates[field.key]
  if (status === 'saving') return 'savingInput'
  if (dirty(field)) return status === 'failed' ? 'saveInputFailed' : 'unsaved'
  return status === 'saved' ? 'savedInput' : undefined
}
function state(field: DraftField): DraftWord {
  if (applicability(field.condition, values) === 'no') return 'inactive'
  const variable = variableMap.value.get(field.key)
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
  if (!id || !fields.value.length) return
  const docs = clone(projectDrafts.get(id)?.documents ?? {})
  if (documentDirty.value) docs[activeFile.value] = { content: documentText.value, baseline: documentBaseline.value, body: clone(bodyDraft.value) }
  else delete docs[activeFile.value]
  projectDrafts.set(id, { values: clone(values), baseline: clone(baseline), documents: docs, view: { step: step.value, activeFile: activeFile.value, review: { layout: reviewLayout.value, groupId: focusedGroupId.value }, reading: readingOpen.value ? { fileKey: readingFile.value, selectedKey: readingKey.value, selectedActionId: readingActionId.value } : undefined } })
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
  const merged = mergeServerValues(fields.value, list, values, baseline)
  variables.value = list
  for (const key of Object.keys(values)) if (!(key in merged.values)) delete values[key]
  Object.assign(values, merged.values); Object.assign(baseline, merged.baseline)
}
function update(field: DraftField, value: DraftValue) {
  if (inputDisabled(field)) return
  delete inputSaveStates[field.key]
  values[field.key] = value
  if (field.key === 'contractPeriodMonths' && durationKnown.value) values.periodAtLeast39Months = durationThreshold.value
  snapshotDraft(projectId.value)
}
function discard(group: DraftGroup) { for (const field of group.fields) values[field.key] = decode(field, baseline[field.key]); snapshotDraft(projectId.value) }
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
  try { const result = await draftingApi.plan(id, dirtyInputs.value ? rawValues() : undefined); if (sequence === planGeneration && current(id, token)) plan.value = result }
  catch (caught) { if (sequence === planGeneration && current(id, token)) error.value = caught instanceof Error ? caught.message : String(caught) }
}
async function refresh(id: string, token: number) {
  const [schema, list] = await Promise.all([draftingApi.catalog(id), draftingApi.variables(id)])
  if (!current(id, token)) return
  catalog.value = schema
  const cached = projectDrafts.get(id)
  if (!Object.keys(values).length && cached) { Object.assign(values, clone(cached.values)); Object.assign(baseline, clone(cached.baseline)); restored.value = schema.groups.some(group => group.fields.some(field => encode(field, values[field.key]) !== (baseline[field.key] ?? ''))) }
  initialize(list)
  const [ts, ins, docs, tr] = await Promise.all([draftingApi.templates(id), draftingApi.inputs(id), draftingApi.documents(id), draftingApi.extractTrace(id)])
  if (!current(id, token)) return
  templates.value = ts; inputs.value = ins; documents.value = docs; trace.value = tr
  if (restoreViewPending) {
    restoreViewPending = false
    const view = restoreDraftView(cached?.view, docs)
    reviewLayout.value = view.review?.layout ?? 'list'
    focusedGroupId.value = schema.groups.some(group => group.id === view.review?.groupId) ? view.review!.groupId : schema.groups[0]?.id ?? ''
    activeFile.value = view.activeFile; step.value = view.step; loadDocument(false)
    if (view.reading) { readingOpen.value = true; readingFile.value = view.reading.fileKey; readingKey.value = view.reading.selectedKey; readingActionId.value = view.reading.selectedActionId }
  }
  await updatePlan()
}
async function saveField(id: string, token: number, field: DraftField, patch: DraftVariablePatch) {
  const updated = await draftingApi.updateVariable(id, field.key, patch)
  if (!current(id, token)) return
  variables.value = [...variables.value.filter(variable => variable.key !== updated.key), updated]
  values[field.key] = decode(field, updated.value); baseline[field.key] = updated.value ?? ''; snapshotDraft(id)
}
async function persistDirty(id: string, token: number, subset = fields.value) { for (const field of subset) { if (!current(id, token)) return; if (dirty(field)) await saveField(id, token, field, { value: encode(field, values[field.key]) }) } }
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
    const patch = adoptionPatch(encode(field, values[field.key]), baseline[field.key] ?? '', !!variable?.reviewRequired, candidateIndex, variable)
    await saveField(id, token, field, patch)
    if (current(id, token)) await refresh(id, token)
  })
}
async function upload(event: Event, target: 'templates' | 'inputs', key?: string) {
  const el = event.target as HTMLInputElement, files = Array.from(el.files ?? []); el.value = ''; if (!files.length) return
  await action(w('uploading'), async (id, token) => { const result = key ? await draftingApi.replaceTemplate(id, key, files[0]!) : target === 'templates' ? await draftingApi.uploadTemplates(id, files) : await draftingApi.uploadInputs(id, files); if (!current(id, token)) return; store.notify(`${result.parsed}/${result.accepted} ${w('parsed')}`); await refresh(id, token); if (result.failed && current(id, token)) error.value = result.messages.join('\n') })
}
async function removeCorrespondence() {
  const item = removalItem.value
  if (item?.id === null || item?.id === undefined) return
  await action(w('saving'), async (id, token) => {
    await draftingApi.deleteInput(id, item.id!)
    if (!current(id, token)) return
    removalItem.value = null
    await refresh(id, token)
  })
}
async function extract() {
  const llmSelection = store.captureLlmSelection()
  await action(w('extracting'), async (id, token) => {
    await persistDirty(id, token); if (!current(id, token)) return
    const previousTrace = trace.value
    try { await draftingApi.extract(id, llmSelection) }
    catch (caught) {
      if (current(id, token)) {
        trace.value = null
        try {
          const failedTrace = await draftingApi.extractTrace(id)
          const newer = failedTrace && (failedTrace.runId ? failedTrace.runId !== previousTrace?.runId : failedTrace.finishedAt !== previousTrace?.finishedAt)
          if (current(id, token) && newer) trace.value = failedTrace
        }
        catch { /* Keep the extraction error; an earlier report must not masquerade as this failed run. */ }
      }
      throw caught
    }
    if (!current(id, token)) return; await refresh(id, token); if (current(id, token)) step.value = 'variables'
  })
}
async function generate() {
  if (!allTemplates.value || documentDirty.value) return
  const llmSelection = store.captureLlmSelection()
  await action(w('generating'), async (id, token) => { await persistDirty(id, token); if (!current(id, token)) return; const sharedPlan = await draftingApi.plan(id); if (!current(id, token)) return; plan.value = sharedPlan; const docs = await draftingApi.generate(id, 'en', llmSelection); if (!current(id, token)) return; documents.value = docs; step.value = 'preview'; documentText.value = docs.find(document => document.fileKey === activeFile.value)?.content ?? ''; documentBaseline.value = documentText.value; bodyDraft.value = createBodyDraft(activeDocument.value); editingDocument.value = false; snapshotDraft(id) })
}
async function saveDocument() {
  if (!documentAccess.value.editable || !documentDirty.value || !activeDocument.value) return
  const key = activeFile.value
  await action(w('saving'), async (id, token) => { const patch = bodyPatch(activeDocument.value!, bodyDraft.value); const updated = await draftingApi.updateDocument(id, key, patch); if (!current(id, token)) return; documents.value = documents.value.map(document => document.fileKey === key ? updated : document); documentText.value = updated.content; documentBaseline.value = updated.content; bodyDraft.value = createBodyDraft(updated); editingDocument.value = false; snapshotDraft(id) })
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
function focusableControls(container: HTMLElement) {
  return [...container.querySelectorAll<HTMLElement>('button, a[href], input, select, textarea, summary, [tabindex]')].filter(control => {
    const style = window.getComputedStyle(control)
    return control.tabIndex >= 0 && !control.matches(':disabled, [aria-disabled="true"]') && !control.closest('[hidden], [inert], [aria-hidden="true"]') && style.visibility !== 'hidden' && style.display !== 'none' && control.getClientRects().length > 0
  })
}
function containTab(event: KeyboardEvent, container: HTMLElement) {
  const controls = focusableControls(container), first = controls[0], last = controls.at(-1), focused = document.activeElement
  if (!first || !controls.some(control => control === focused)) {
    event.preventDefault(); ((event.shiftKey ? last : first) ?? container).focus({ preventScroll: true })
  } else if (event.shiftKey && focused === first || !event.shiftKey && focused === last) {
    event.preventDefault(); (event.shiftKey ? last : first)?.focus({ preventScroll: true })
  }
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
function move(next: Step) { if (busy.value || documentDirty.value) return; graphNavigation.value = undefined; if (next === 'preview' && !documents.value.some(document => document.generated)) { void generate(); return }; step.value = next }
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
  await action(w('saving'), async (project, token) => { await saveField(project, token, { ...field, key: 'targetOverrides' }, { value: JSON.stringify(list) }); if (!current(project, token)) return; targetId.value = ''; await refresh(project, token) })
}
function beforeUnload(event: BeforeUnloadEvent) {
  const cachedChanges = [...projectDrafts.values()].some(cached => Object.keys(dirtyPatch(fields.value, cached.values, cached.baseline)).length > 0 || Object.keys(cached.documents ?? {}).length > 0)
  if (dirtyInputs.value || documentDirty.value || cachedChanges) { event.preventDefault(); event.returnValue = w('unsavedLeave') }
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
watch(values, () => { if (planTimer) clearTimeout(planTimer); planTimer = setTimeout(() => { if (!busy.value) void updatePlan() }, 350) }, { deep: true })
watch(projectId, (id, old) => {
  if (old) snapshotDraft(old)
  closeGraph(false)
  graphNavigation.value = undefined
  generation++; planGeneration++; busy.value = ''; error.value = ''; step.value = 'inputs'; activeFile.value = 'NTT'; editingDocument.value = false; immersiveDocument.value = false; targetId.value = ''; restored.value = false; restoreViewPending = true; traceOpen.value = false
  reviewLayout.value = 'list'; focusedGroupId.value = ''; search.value = ''; statusFilter.value = 'all'
  catalog.value = { ruleVersion: '', groups: [] }; templates.value = []; inputs.value = []; variables.value = []; documentText.value = ''; documentBaseline.value = ''; documents.value = []; plan.value = null; trace.value = null
  bodyDraft.value = createBodyDraft(undefined); removalItem.value = null; readingOpen.value = false; readingKey.value = ''; readingActionId.value = ''; readingFile.value = 'NTT'; previewEditKey.value = ''
  for (const key of Object.keys(values)) delete values[key]; for (const key of Object.keys(baseline)) delete baseline[key]
  for (const key of Object.keys(inputSaveStates)) delete inputSaveStates[key]
  if (id) void action(w('loading'), refresh)
}, { immediate: true })
watch(activeFile, () => { if (graphNavigation.value?.fileKey !== activeFile.value) graphNavigation.value = undefined; loadDocument(false) })
watch(activeDocument, () => { loadDocument() })
watch(step, () => { if (step.value !== 'preview') { immersiveDocument.value = false; graphNavigation.value = undefined } })
onBeforeUnmount(() => { snapshotDraft(projectId.value); alive = false; generation++; planGeneration++; if (planTimer) clearTimeout(planTimer); window.removeEventListener('beforeunload', beforeUnload); window.removeEventListener('keydown', workspaceKeydown) })
</script>

<template>
  <section class="draft-flow" :class="{ 'with-template': readingOpen && step === 'variables' }">
    <header class="flow-head"><div><h2>{{ w('title') }}</h2><p>{{ w('subtitle') }}</p></div><div class="row"><button type="button" class="btn" data-open-business-graph="header" :disabled="!projectId || !!busy || documentDirty || !catalog.groups.length" @click="openGraph">{{ w('businessGraph') }}</button><span class="flow-count">{{ adoptedCount }} / {{ actionableFields.length }} {{ w('adopted') }} · {{ unresolvedInputCount }} {{ w('pending') }}</span></div></header>
    <nav class="flow-steps"><button v-for="item in steps" :key="item.key" type="button" :class="{ active: step === item.key }" :aria-current="step === item.key ? 'step' : undefined" :disabled="!!busy || documentDirty || (item.key === 'preview' && !allTemplates && !documents.some(document => document.generated))" @click="move(item.key)">{{ item.label }}</button></nav>
    <p v-if="!projectId" class="empty-state">{{ w('selectProject') }}</p><p v-if="busy" class="flow-status" role="status">{{ busy }}</p><p v-if="error" class="flow-error" role="alert">{{ error }}</p><p v-if="restored" class="review-note">{{ w('restoreNote') }}</p>
    <div v-if="projectId && step === 'inputs'" class="source-stack">
      <section class="flow-card"><div class="card-head"><h3>{{ w('templates') }}</h3><label class="btn" :class="{ disabled: !!busy }">{{ w('uploadTemplates') }}<input type="file" accept=".doc,.docx,.pdf,.txt,.md" multiple :disabled="!!busy" @change="upload($event, 'templates')" /></label></div><p class="hint">{{ w('templateNote') }}</p><p class="hint">{{ w('editableSource') }}</p><div v-for="key in ['NTT', 'SCT', 'SCC']" :id="`template-source-${key}`" :key="key" class="source-row"><strong>{{ key }}</strong><div><div>{{ templates.find(template => template.key === key)?.fileName || w('notUploaded') }}</div><small>{{ l(templates.find(template => template.key === key)?.status) }} · {{ l(templates.find(template => template.key === key)?.note) }}</small></div><button :id="`template-upload-button-${key}`" type="button" class="btn" :disabled="!!busy" :aria-label="`${key} · ${w('replace')}`" @click="chooseTemplate(key)">{{ w('replace') }}</button><input :id="`template-upload-${key}`" type="file" hidden accept=".doc,.docx,.pdf,.txt,.md" :disabled="!!busy" @change="upload($event, 'templates', key)" /></div></section>
      <section class="flow-card"><div class="card-head"><h3>{{ w('evidence') }}</h3><label class="btn" :class="{ disabled: !!busy }">{{ w('uploadEvidence') }}<input type="file" accept=".doc,.docx,.pdf,.txt,.md,.eml,.msg" multiple :disabled="!!busy" @change="upload($event, 'inputs')" /></label></div><p class="hint">{{ w('evidenceNote') }}</p><p v-if="!inputs.length" class="hint">{{ w('emptyEvidence') }}</p><div v-for="input in inputs" :key="input.id ?? input.code" class="evidence-row"><div class="card-head"><strong>{{ input.fileName || l(input.title) }}</strong><div class="row"><span>{{ input.status === 'PARSED' ? w('parsedStatus') : ['FAILED', 'PARSE_FAILED'].includes(input.status) ? w('failedStatus') : w('waitingStatus') }}</span><button v-if="input.id !== null && input.id !== undefined" class="btn text-link" :data-remove-input="input.id" :disabled="!!busy" @click="removalItem = input">{{ w('removeCorrespondence') }}</button></div></div><small>{{ l(input.message) }} {{ input.pageCount || '' }}</small></div></section>
      <footer class="flow-actions"><button v-if="trace" class="btn" @click="traceOpen = true">{{ w('trace') }}</button><button class="btn" :disabled="!!busy || !catalog.groups.length" @click="move('variables')">{{ w('manualEntry') }}</button><button class="btn primary" :disabled="!!busy || !inputs.some(input => input.status === 'PARSED')" @click="extract">{{ w('extract') }}</button></footer>
    </div>
    <div v-if="projectId && step === 'variables'" class="input-stack">
      <div class="input-intro"><p>{{ w('reviewIntro') }}</p><div class="row"><button class="btn" :disabled="!!busy" @click="openReading()">{{ w('templateReading') }}</button><button class="btn" :disabled="!!busy || !inputs.some(input => input.status === 'PARSED')" @click="extract">{{ w('extract') }}</button><button v-if="trace" class="btn" @click="traceOpen = true">{{ w('trace') }}</button></div></div>
      <div class="question-tools"><input v-model="search" :placeholder="w('search')" :aria-label="w('search')" /><select v-model="statusFilter" :aria-label="w('all')"><option value="all">{{ w('all') }}</option><option v-for="status in ['missing', 'suggested', 'adopted', 'manual', 'needs_review', 'conflict', 'inactive'] as DraftWord[]" :key="status" :value="status">{{ w(status) }}</option></select><button class="btn" @click="search = ''; statusFilter = 'all'">{{ w('clearFilters') }}</button></div>
      <div class="review-layout-switch" role="group" :aria-label="w('reviewLayout')"><button class="btn" :class="{ primary: reviewLayout === 'list' }" :aria-pressed="reviewLayout === 'list'" @click="changeReviewLayout('list')">{{ w('listLayout') }}</button><button class="btn" :class="{ primary: reviewLayout === 'focus' }" :aria-pressed="reviewLayout === 'focus'" @click="changeReviewLayout('focus')">{{ w('focusLayout') }}</button></div>
      <p v-if="!filteredGroups.length" class="empty-state">{{ w('noMatches') }}</p>
      <div class="question-review" :class="{ focus: reviewLayout === 'focus' }">
      <nav v-if="reviewLayout === 'focus' && filteredGroups.length" class="question-index" :aria-label="w('questionIndex')"><button v-for="group in filteredGroups" :key="group.id" class="question-index-item" :aria-current="focusedGroup?.id === group.id ? 'step' : undefined" :data-review-group="group.id" @click="chooseGroup(group.id)"><span class="group-number">{{ groupNumber(group) }}</span><span>{{ l(group.label) }}<small class="state" :class="groupState(group)">{{ w(groupState(group)) }}</small></span></button></nav>
      <div class="question-editor">
      <div v-if="reviewLayout === 'focus' && filteredGroups.length" class="question-navigation"><button class="btn" :disabled="focusedGroupIndex <= 0" @click="moveQuestion(-1)">{{ w('previousQuestion') }}</button><span aria-live="polite">{{ w('questionPosition') }} {{ focusedGroupIndex + 1 }} / {{ filteredGroups.length }}</span><button class="btn" :disabled="focusedGroupIndex >= filteredGroups.length - 1" @click="moveQuestion(1)">{{ w('nextQuestion') }}</button></div>
      <section v-for="group in displayedGroups" :id="`group-${group.id}`" :key="group.id" class="flow-card input-group" :data-group="group.id">
        <div class="card-head"><h3><span class="group-number">{{ groupNumber(group) }}</span>{{ l(group.label) }}</h3><span class="state" :class="groupState(group)">{{ w(groupState(group)) }}</span></div><p v-if="group.description" class="hint">{{ l(group.description) }}</p>
        <article v-for="field in visibleFields(group)" :id="`field-${field.key}`" :key="field.key" class="variable" :class="{ 'source-selected': readingOpen && readingKey === field.key }" :data-variable="field.key"><div class="variable-head"><button class="btn text-link" :data-open-template="field.key" :disabled="!!busy" @click="openReading(field)">{{ w('locateTemplate') }}</button><span class="state" :class="state(field)">{{ dirty(field) ? w('unsaved') : w(state(field)) }}</span><button v-if="variableMap.get(field.key)?.reviewRequired" class="btn soft" :disabled="!!busy" @click="adopt(field)">{{ w('reviewed') }}</button><button v-else-if="state(field) === 'suggested'" class="btn soft" :disabled="!!busy" @click="adopt(field)">{{ w('adoptSuggested') }}</button></div><DraftingInputField :field="field" :value="values[field.key]" :values="values" :locale="store.locale" :trace="trace" :disabled="inputDisabled(field)" @update="update(field, $event)"><template #actions><div class="field-save-actions"><button type="button" class="btn primary" :data-save-input="field.key" :disabled="!!busy || !dirty(field) || applicability(field.condition, values) === 'no'" @click="saveInput(field)">{{ w('saveInput') }}</button><span v-if="inputSaveStatus(field)" class="field-save-feedback" :class="{ failed: inputSaveStates[field.key] === 'failed' }" :data-input-save-status="field.key" role="status" aria-live="polite">{{ w(inputSaveStatus(field)!) }}</span></div></template></DraftingInputField><p v-if="derivedInput(field)" class="hint">{{ w('durationDerived') }}</p><p v-if="['contractPeriodMonths', 'periodAtLeast39Months'].includes(field.key) && durationConflict" class="condition-note">{{ w('durationConflict') }}</p><p v-if="applicability(field.condition, values) === 'unknown'" class="condition-note">{{ w('prerequisite') }}</p><p v-if="validationIssue(field, values[field.key]) && validationIssue(field, values[field.key]) !== 'missing'" class="condition-note">{{ w(validationIssue(field, values[field.key])!) }}</p><p v-if="['siteInspectionStartDate', 'siteInspectionEndDate'].includes(field.key) && reversedSiteDates(values)" class="condition-note">{{ w('reversedDates') }}</p><p v-if="variableMap.get(field.key)?.validationIssue" class="condition-note">{{ variableMap.get(field.key)?.validationIssue }}</p><details class="field-evidence"><summary>{{ w('sourceDetails') }}</summary><blockquote v-if="variableMap.get(field.key)?.source">{{ variableMap.get(field.key)?.source }}</blockquote><p v-else class="hint">{{ w('noSource') }}</p><p v-if="variableMap.get(field.key)?.note" class="hint raw-text">{{ variableMap.get(field.key)?.note }}</p><div v-for="(candidate, candidateIndex) in variableMap.get(field.key)?.candidates ?? []" :key="candidateIndex" class="candidate"><strong>{{ candidate.fileName || w('candidates') }}</strong><DraftingValueDisplay :field="field" :value="candidate.value" :locale="store.locale" /><blockquote>{{ candidate.sourceQuote }}</blockquote><p class="hint raw-text">{{ candidate.reason }}</p><button class="btn" :disabled="!!busy" @click="adopt(field, candidateIndex)">{{ w('adoptCandidate') }}</button></div><p v-for="target in field.affects ?? []" :key="`${target.document}-${target.clause}`" class="hint">{{ target.document }} {{ target.clause }} · {{ target.paragraphs }}</p></details></article>
        <DraftingBillDistribution v-if="group.id === 'bills'" :values="values" :locale="store.locale" /><p v-if="visibleFields(group).length !== group.fields.length" class="hint">{{ w('hiddenFields') }}</p><details v-if="relatedActions(group).length" class="clause-details"><summary>{{ w('affected') }}</summary><div v-for="item in relatedActions(group)" :key="item.id" class="clause-action" :data-action-id="item.id"><div class="card-head"><strong>{{ item.document }} {{ item.clause }}</strong><span class="state">{{ w((item.action in { retain: 1, amend: 1, delete: 1, not_used: 1, pending: 1, not_adopted: 1 } ? item.action : 'pending') as DraftWord) }}</span></div><p class="hint">{{ item.paragraphs }}</p><p>{{ l(item.detail) }}</p><details v-if="item.sourceWarning" class="source-warning"><summary>{{ w('sourceWarning') }}</summary><p class="condition-note">{{ l(item.sourceWarning) }}</p></details><pre v-if="item.value">{{ item.value }}</pre><button class="btn text-link" :disabled="!!busy" @click="openReading(undefined, item)">{{ w('locateTemplate') }}</button><button class="btn text-link" :disabled="!!busy" @click="openTarget(item)">{{ w('exactEdit') }}</button></div></details><footer v-if="group.fields.some(dirty)" class="flow-actions"><button class="btn" :disabled="!!busy" @click="discard(group)">{{ w('discard') }}</button><button class="btn primary" :disabled="!!busy" @click="saveGroup(group)">{{ w('save') }}</button></footer>
      </section>
      </div></div>
      <footer class="flow-actions"><button class="btn" :disabled="!!busy" @click="move('inputs')">{{ w('backSources') }}</button><button class="btn" :disabled="!!busy || !dirtyInputs" @click="saveGroup()">{{ w('saveAll') }}</button><button class="btn primary" :disabled="!!busy || !allTemplates" @click="generate">{{ w('generate') }}</button></footer><p class="hint">{{ w('generateNote') }}</p><p v-if="!allTemplates" class="condition-note">{{ w('missingTemplates') }}</p>
      <details v-if="plan?.unresolved.length" class="flow-card"><summary>{{ w('unresolved') }} ({{ plan.unresolved.length }})</summary><DraftingUnresolvedItems :items="plan.unresolved" :fields="fields" :values="values" :actions="plan.actions" :locale="store.locale" @input="goInput" @target="openTarget" /></details>
    </div>
    <section v-if="projectId && step === 'preview'" ref="documentWorkspace" class="flow-card preview-card" :class="{ 'preview-card--immersive': immersiveDocument }" data-document-workspace :role="immersiveDocument ? 'dialog' : undefined" :aria-modal="immersiveDocument ? true : undefined" :aria-label="w('documentWorkspace')" tabindex="-1">
      <div class="card-head">
        <div class="document-tabs"><button v-for="key in ['NTT', 'SCT', 'SCC']" :key="key" class="btn" :class="{ primary: activeFile === key }" :disabled="!!busy || documentDirty" @click="activeFile = key">{{ key }}</button></div>
        <div class="document-tools"><button class="btn" :disabled="!!busy || !documentAccess.editable || documentDirty" @click="editingDocument = !editingDocument">{{ editingDocument && !activeDocument?.stale ? w('readDocument') : w('editContent') }}</button><template v-if="documentDirty"><button class="btn" :disabled="!!busy" @click="discardBody">{{ w('discard') }}</button><button class="btn primary" :disabled="!!busy || !documentAccess.editable" @click="saveDocument">{{ w('saveContent') }}</button></template><button v-if="activeDocument?.stale" class="btn primary" :disabled="!!busy || !allTemplates || documentDirty" @click="generate">{{ w('regenerate') }}</button><button v-if="immersiveDocument" ref="documentInfoToggle" type="button" class="btn" :aria-expanded="documentInfoOpen" aria-controls="draft-document-info" @click="documentInfoOpen = !documentInfoOpen">{{ w('documentInformation') }}</button></div>
        <div class="row"><button v-if="immersiveDocument" type="button" class="btn" data-open-business-graph="immersive" :disabled="!!busy || documentDirty || !catalog.groups.length" @click="openGraph">{{ w('businessGraph') }}</button><button class="btn" :disabled="!!busy || !canExport" @click="exportDocument('docx')">{{ w('exportWord') }}</button><button class="btn primary" :disabled="!!busy || !canExport" @click="exportDocument('pdf')">{{ w('exportPdf') }}</button><button ref="immersiveToggle" type="button" class="btn" :aria-expanded="immersiveDocument" @click="setImmersive(!immersiveDocument)">{{ immersiveDocument ? w('exitImmersive') : w('immersivePreview') }}</button></div>
      </div>
      <div v-show="!immersiveDocument || documentInfoOpen" class="document-info" id="draft-document-info">
      <h3>{{ activeDocument?.title }}</h3>
      <p v-if="activeDocument?.revisionId" class="document-revision-status" role="status"><span>{{ w('savedBodyRevision') }}: {{ activeDocument.revisionId }}</span><strong v-if="documentDirty">{{ w('pendingBodyRevision') }}</strong></p>
      <details v-if="activeDocument?.revisionId" class="document-versions"><summary>{{ w('documentVersions') }}</summary><p v-if="activeDocument.snapshotId">{{ w('snapshot') }}: {{ activeDocument.snapshotId }}</p><p>Revision: {{ activeDocument.revisionId }}</p><p>DOCX SHA-256: {{ activeDocument.docxSha256 }}</p><p v-if="activeDocument.pdfSha256">PDF SHA-256: {{ activeDocument.pdfSha256 }}</p><p v-if="activeDocument.renderProfileHash">Render profile: {{ activeDocument.renderProfileHash }}</p></details>
      <p v-if="activeDocument?.generated" class="condition-note">{{ w('formatReview') }}</p>
      </div>
      <p v-if="documentDirty" class="condition-note">{{ w('pendingBodyNote') }}</p>
      <p v-if="activeDocument?.stale || dirtyInputs" class="review-note">{{ w('stale') }}</p><p v-if="activeDocument?.stale" class="condition-note">{{ w('staleReadOnly') }}</p>
      <p v-if="immersiveDocument && busy" class="flow-status" role="status">{{ busy }}</p><p v-if="immersiveDocument && error" class="flow-error" role="alert">{{ error }}</p>
      <div class="document-surface">
      <DraftingDocumentWorkspace :project-id="projectId" :file-key="activeFile" :document="activeDocument" :dirty="documentDirty" :editing="editingDocument" :locale="store.locale" :immersive="immersiveDocument" :fields="readingFields" :values="values" :variables="variables" :actions="plan?.actions ?? []" :field-states="readingStates" :dirty-keys="readingDirtyKeys" :trace="trace" :graph-navigation="graphNavigation" :disabled="!!busy" @input="goInput" @file="chooseBoundFile">
        <DraftingBodyEditor v-if="activeDocument?.blocks" :blocks="activeDocument.blocks" :draft="bodyDraft" :locale="store.locale" :readonly="!documentAccess.editable" :disabled="!!busy" :immersive="immersiveDocument" @text="editBody" @insert="insertBody" />
        <pre v-else class="raw-text">{{ documentText }}</pre>
      </DraftingDocumentWorkspace>
      </div>
      <details v-if="currentUnresolved.length" class="draft-unresolved" :open="!immersiveDocument"><summary>{{ w('unresolved') }} ({{ currentUnresolved.length }})</summary><DraftingUnresolvedItems :items="currentUnresolved" :fields="fields" :values="values" :actions="plan?.actions ?? []" :locale="store.locale" @input="goInput" @target="openTarget" /></details>
      <footer class="flow-actions"><button class="btn" :disabled="!!busy || documentDirty" @click="move('variables')">{{ w('backInputs') }}</button><p v-if="immersiveDocument" class="hint regeneration-warning">{{ w('regenerationNote') }}</p><button class="btn" :disabled="!!busy || !allTemplates || documentDirty" @click="generate">{{ w('regenerate') }}</button></footer><p v-if="!immersiveDocument" class="hint">{{ w('regenerationNote') }}</p>
    </section>
    <footer v-if="catalog.ruleVersion" class="hint">{{ w('ruleVersion') }}: {{ catalog.ruleVersion }}</footer>
    <aside v-if="projectId && step === 'variables' && readingOpen" id="drafting-template-panel" class="template-side-panel" data-template-panel :aria-label="w('templateReading')">
      <header class="card-head"><h3>{{ w('templateReading') }}</h3><button class="btn" @click="closeReading">{{ w('close') }}</button></header>
      <DraftingTemplatePreview :project-id="projectId" :file-key="readingFile" :source-available="readingSourceAvailable" :locale="store.locale" :fields="readingFields" :values="values" :variables="variables" :actions="plan?.actions ?? []" :selected-key="readingKey" :selected-action-id="readingActionId" :field-states="readingStates" :dirty-keys="readingDirtyKeys" :disabled="!!busy" @select="selectReading" @edit="editReading" @input="selectReading" @target="readingTarget" @locate="locateReadingTarget" @file="changeReadingFile" @upload="goTemplateUpload" />
    </aside>
    <DraftingBusinessGraph v-if="graphOpen" :project-id="projectId" :catalog="catalog" :plan="plan" :values="values" :variables="variables" :field-states="readingStates" :dirty-keys="readingDirtyKeys" :locale="store.locale" :disabled="!!busy || documentDirty" @input="graphInput" @location="graphLocation" @close="closeGraph()" />
    <AppModal :open="!!previewEditField" :title="w('editPreviewValue')" @close="previewEditKey = ''">
      <template v-if="previewEditField"><span class="state">{{ dirty(previewEditField) ? w('unsavedValue') : w(state(previewEditField)) }}</span><p class="hint">{{ w('previewEditNote') }}</p><DraftingInputField :field="previewEditField" :value="values[previewEditField.key]" :values="values" :locale="store.locale" id-prefix="preview-edit" :disabled="inputDisabled(previewEditField)" @update="update(previewEditField, $event)" /><p v-if="derivedInput(previewEditField)" class="hint">{{ w('durationDerived') }}</p><p v-if="error" class="flow-error" role="alert">{{ error }}</p><div class="flow-actions"><button class="btn" :disabled="!!busy" @click="previewEditKey = ''">{{ w('close') }}</button><button v-if="previewAdoptable(previewEditField)" class="btn" :disabled="!!busy" @click="adoptReading">{{ w('adoptSuggested') }}</button><button class="btn primary" :data-preview-save="previewEditField.key" :disabled="!!busy || !dirty(previewEditField) || applicability(previewEditField.condition, values) === 'no'" @click="saveReading">{{ w('save') }}</button></div></template>
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
.flow-steps { display: grid; grid-template-columns: repeat(3,minmax(0,1fr)); gap: 8px; }
.flow-steps button { background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius); padding: 10px 12px; min-height: 46px; text-align: left; color: var(--ink-soft); font: inherit; font-size: 12px; font-weight: 600; }
.flow-steps button.active { border-color: var(--accent); background: var(--accent-soft); color: var(--accent-dark); }
.source-stack,.input-stack { display: flex; flex-direction: column; gap: 12px; padding: 16px; background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius); box-shadow: var(--shadow); min-width: 0; }
.flow-card { background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius); padding: 14px 16px; min-width: 0; }
.flow-card h3 { font-size: 14px; line-height: 1.4; }
.group-number { display: inline-flex; align-items: center; justify-content: center; background: var(--accent-soft); color: var(--accent-dark); width: 22px; height: 22px; border-radius: 50%; margin-right: 8px; font-size: 11px; }
.source-row { display: grid; grid-template-columns: 45px minmax(0,1fr) auto; gap: 12px; align-items: center; padding: 12px 0; border-bottom: 1px solid var(--line); }
.source-row:last-child { border: 0; }
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
  .flow-steps { gap: 6px; }
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
