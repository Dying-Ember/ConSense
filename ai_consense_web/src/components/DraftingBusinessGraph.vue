<script setup lang="ts">
import type { GraphLocationTarget } from '@/drafting/graph-navigation'
import { computed, nextTick, onMounted, ref, shallowRef, watch } from 'vue'
import type { DraftCatalog, DraftCondition, DraftField, DraftPlan, DraftTarget, DraftVariable } from '@/api/types'
import { applicability, type DraftValue } from '@/drafting/state'
import type { AppLocale } from '@/i18n'
import { draftWord, localized, type DraftWord } from '@/drafting/words'
import { buildBusinessGraph } from '@/drafting/business-graph'
import { businessGraphGeometry as geometry, routeBusinessGraphEdges } from '@/drafting/business-graph-layout'
import { isObjectKind, isVisibleDetail } from '@/drafting/field-kinds'
import { presentationValue, scalarValueLabel, valueSummary } from '@/drafting/value-presentation'
import DraftingValueDisplay from './DraftingValueDisplay.vue'

const props = defineProps<{ projectId: string; catalog: DraftCatalog; plan?: DraftPlan | null; values: Record<string, DraftValue>; variables: DraftVariable[]; fieldStates: Record<string, string>; dirtyKeys: string[]; locale: AppLocale; disabled?: boolean }>()
const emit = defineEmits<{ input: [key: string]; location: [target: GraphLocationTarget]; close: [] }>()
const selectedGroup = ref(props.catalog.groups[0]?.id ?? ''), selectedKey = ref(''), selectedActionId = ref('')
const selectedSubfieldKey = ref('')
const query = ref(''), selectedFile = ref('')
const dialog = ref<HTMLElement | null>(null), fullscreen = ref(false)
// Plans do not carry project identity. A project switch must await a new receipt.
const rejectedPlan = shallowRef<DraftPlan | null | undefined>()
const graph = computed(() => buildBusinessGraph({ catalog: props.catalog, plan: props.plan === rejectedPlan.value ? null : props.plan ?? null, values: props.values }))
const l = (value: Parameters<typeof localized>[0]) => localized(value, props.locale)
const t = (en: string, cn: string, hk = cn) => props.locale === 'en' ? en : props.locale === 'zh-Hant' ? hk : cn
const selectedInput = computed(() => graph.value.inputs.find(input => input.key === selectedKey.value))
const selectedAction = computed(() => graph.value.actions.find(action => action.id === selectedActionId.value))
const selectedGroupLabel = computed(() => l(graph.value.groups.find(item => item.id === selectedInput.value?.groupId)?.label))
const field = (key: string) => graph.value.inputs.find(input => input.key === key)?.field ?? props.catalog.systemFields?.find(item => item.key === key)
const inputLabel = (key: string) => l(field(key)?.label) || t('Manual target override', '人工目标覆盖设置', '人工目標覆寫設定')
const visibleSubfields = (input: typeof graph.value.inputs[number]) => input.subfields.filter(item => isVisibleDetail(item.field))
const structure = computed(() => selectedInput.value ? visibleSubfields(selectedInput.value) : [])
const selectedSubfield = computed(() => structure.value.find(item => item.key === selectedSubfieldKey.value))
const structuralCount = computed(() => graph.value.inputs.reduce((count, input) => count + visibleSubfields(input).length, 0))
const compositeGroup = (groupId: string) => groupId === 'contractType' && ['foundationIncluded', 'periodAtLeast39Months'].every(key => graph.value.inputs.some(input => input.groupId === groupId && input.key === key))
const inputKind = (input: typeof graph.value.inputs[number]) => compositeGroup(input.groupId) && ['foundationIncluded', 'periodAtLeast39Months'].includes(input.key) ? 'subvariable' : compositeGroup(input.groupId) && input.key === 'contractPeriodMonths' ? 'supporting-value' : 'variable'
const inputKindLabel = (input: typeof graph.value.inputs[number]) => inputKind(input) === 'subvariable' ? t('Sub-variable', '子变量', '子變量') : inputKind(input) === 'supporting-value' ? t('Supporting value (optional)', '支持事实（可选）', '支持事實（可選）') : t('Variable', '变量', '變量')
const subfieldKindLabel = (input: typeof graph.value.inputs[number]) => isObjectKind(input.field.kind) ? t('Sub-variable', '子变量', '子變量') : t('Item field', '条目字段', '項目欄位')
const selectedSubfieldValues = computed(() => {
  if (!selectedInput.value || !selectedSubfield.value || invalidCurrentValue.value) return []
  const current = presentationValue(selectedInput.value.field, selectedInput.value.value).value
  if (!current || typeof current !== 'object') return []
  return (Array.isArray(current) ? current : [current]).map((record, index) => {
    const row = record && typeof record === 'object' && !Array.isArray(record) ? record : {}
    return { index: index + 1, value: row[selectedSubfield.value!.key] ?? null, applicability: applicability(selectedSubfield.value!.field.condition, row) }
  })
})
function invalidStructuredValue(valueField: DraftField, raw: DraftValue | undefined): boolean {
  const presented = presentationValue(valueField, raw)
  if (presented.invalid) return true
  if (!valueField.columnFields?.length || !presented.value || typeof presented.value !== 'object') return false
  const records = Array.isArray(presented.value) ? presented.value : [presented.value]
  return records.some(record => record && typeof record === 'object' && !Array.isArray(record) && valueField.columnFields!.some(column => isVisibleDetail(column) && invalidStructuredValue(column, record[column.key])))
}
const invalidCurrentValue = computed(() => selectedInput.value ? invalidStructuredValue(selectedInput.value.field, selectedInput.value.value) : false)
const applicableLabel = (value: string) => value === 'no' ? t('Inactive for current inputs', '当前输入下不适用', '目前輸入下不適用') : value === 'yes' ? t('Applicable', '适用', '適用') : t('Applicability unanswered', '适用条件尚未明确', '適用條件尚未明確')
const actionLabel = (value: string) => ['retain', 'delete', 'amend', 'insert', 'pending', 'not_used', 'not_adopted'].includes(value) ? draftWord(value as DraftWord, props.locale) : ({ fill: t('Fill', '填写', '填寫'), manual: t('Manual review', '人工核对', '人工核對'), select: t('Select', '选择', '選擇'), remove: t('Remove', '移除'), replace: t('Replace', '替换', '替換') })[value] || t('Planned processing', '方案处理', '方案處理')
const operatorLabel = (operator: string) => ({ eq: t('equals', '等于', '等於'), gt: t('greater than', '大于', '大於'), includes: t('includes', '包含'), in: t('is one of', '属于以下任一项', '屬於以下任一項'), countGt: t('item count greater than', '条目数大于', '項目數大於'), includesComponent: t('includes component', '包括工程类别', '包括工程類別'), unknown: t('is unanswered', '尚未回答') })[operator] || operator
function criterionValue(term: DraftCondition['all'][number]) {
  const termField: DraftField = field(term.field) ?? { key: term.field, kind: 'text', label: { en: '', zhHans: '', zhHant: '' } }
  const itemLabel = (value: unknown) => value == null || ['string', 'number', 'boolean'].includes(typeof value) ? scalarValueLabel(termField, value as DraftValue, props.locale) : t('Structured criterion', '结构化条件值', '結構化條件值')
  return Array.isArray(term.value) ? term.value.map(itemLabel).join(' / ') : itemLabel(term.value)
}
const matches = (text: string) => !query.value.trim() || text.toLocaleLowerCase().includes(query.value.trim().toLocaleLowerCase())
const groupInputs = computed(() => graph.value.inputs.filter(input => (!selectedGroup.value || input.groupId === selectedGroup.value) && (!selectedFile.value || input.potentialTargets.some(target => target.document === selectedFile.value) || graph.value.actions.some(action => action.document === selectedFile.value && action.inputKeys.includes(input.key)))))
const groupActions = computed(() => graph.value.actions.filter(action => (!selectedFile.value || action.document === selectedFile.value) && (!selectedGroup.value || action.inputKeys.some(key => groupInputs.value.some(input => input.key === key)))))
const matchingActions = computed(() => groupActions.value.filter(action => matches(`${action.document} ${action.clause} ${l(action.result.detail)} ${actionLabel(action.result.action)}`)))
const matchingInputs = computed(() => groupInputs.value.filter(input => matches(`${input.key} ${l(input.field.label)} ${valueSummary(input.field, input.value, props.locale)} ${l(graph.value.groups.find(group => group.id === input.groupId)?.label)} ${visibleSubfields(input).map(item => `${input.key}.${item.key} ${l(item.field.label)}`).join(' ')}`) || (!!query.value.trim() && matchingActions.value.some(action => action.inputKeys.includes(input.key)))))
// Explicit filters define the pool. Selection only highlights it, appending any
// outside-pool prerequisites/shared inputs as context without moving its nodes.
const inputs = computed(() => {
  if (!selectedInput.value && !selectedAction.value) return matchingInputs.value
  const keys = new Set(selectedAction.value?.inputKeys ?? activeActions.value.flatMap(action => action.inputKeys))
  if (selectedInput.value) { keys.add(selectedInput.value.key); selectedInput.value.prerequisiteKeys.forEach(key => keys.add(key)) }
  const poolKeys = new Set(matchingInputs.value.map(input => input.key))
  return [...matchingInputs.value, ...graph.value.inputs.filter(input => keys.has(input.key) && !poolKeys.has(input.key))]
})
const poolActions = computed(() => groupActions.value.filter(action => matchingActions.value.includes(action) || action.inputKeys.some(key => matchingInputs.value.some(input => input.key === key))))
const actions = computed(() => {
  const poolIds = new Set(poolActions.value.map(action => action.id))
  return [...poolActions.value, ...activeActions.value.filter(action => !poolIds.has(action.id))]
})
const visibleChildren = computed(() => inputs.value.flatMap(input => visibleSubfields(input).map(child => ({ ...child, parent: input }))))
const activeActions = computed(() => selectedAction.value ? [selectedAction.value] : graph.value.actions.filter(action => selectedInput.value?.actionIds.includes(action.id)))
const variable = computed(() => props.variables.find(item => item.key === selectedKey.value))
function recordedAdoption(key: string): string {
  const record = props.variables.find(item => item.key === key)
  const state: DraftWord = record?.adoptionState === 'conflict' ? 'conflict'
    : record?.manuallyEdited ? 'manual'
      : record?.confirmed || record?.adoptionState === 'adopted' ? 'adopted'
        : record?.reviewRequired || record?.adoptionState === 'needs_review' ? 'needs_review'
          : record?.value != null && record.value !== '' ? 'suggested' : 'unknown'
  return draftWord(state, props.locale)
}
function recordedReviewNeeded(key: string): boolean {
  const record = props.variables.find(item => item.key === key)
  return !!record?.reviewRequired || record?.adoptionState === 'needs_review'
}
function inputLayout(items: typeof graph.value.inputs) {
  const boxes = new Map<string, { x: number; y: number; w: number; h: number }>()
  const containers: { id: string; groupId: string; y: number; h: number; inputKeys: string[]; context: boolean }[] = []
  const poolKeys = new Set(matchingInputs.value.map(input => input.key))
  let y = geometry.input.y, previous: typeof containers[number] | undefined
  for (const input of items) {
    const context = !poolKeys.has(input.key)
    if (!previous || previous.groupId !== input.groupId || previous.context !== context) {
      previous = { id: `group:${input.groupId}:${containers.length}`, groupId: input.groupId, y, h: 0, inputKeys: [], context }
      containers.push(previous); y += 40
    }
    previous.inputKeys.push(input.key)
    boxes.set(input.id, { ...geometry.input, y }); y += geometry.input.row
    for (const child of visibleSubfields(input)) { boxes.set(child.id, { ...geometry.subfield, y }); y += geometry.subfield.row }
    previous.h = y - previous.y - 8
    const next = items[items.indexOf(input) + 1]
    if (!next || next.groupId !== input.groupId || !poolKeys.has(next.key) !== context) y += 12
  }
  return { boxes, containers, bottom: y }
}
const variableLayout = computed(() => inputLayout(inputs.value))
const actionHeight = (count: number) => count ? geometry.action.y + (count - 1) * geometry.action.row + geometry.action.h + geometry.bottom : 0
const poolHeight = computed(() => Math.max(geometry.minHeight, inputLayout(matchingInputs.value).bottom + geometry.bottom, actionHeight(poolActions.value.length)))
const height = computed(() => Math.max(poolHeight.value, variableLayout.value.bottom + geometry.bottom, actionHeight(actions.value.length)))
const position = (id: string) => {
  const input = variableLayout.value.boxes.get(id)
  if (input) return input
  const action = actions.value.findIndex(item => item.id === id)
  if (action >= 0) return { ...geometry.action, y: geometry.action.y + action * geometry.action.row }
  const file = graph.value.files.findIndex(item => item.id === id)
  return file >= 0 ? { ...geometry.file, y: geometry.file.y + file * (poolHeight.value - geometry.file.y * 2) / graph.value.files.length } : undefined
}
function nodeTransform(id: string) { const box = position(id)!; return `translate(${box.x} ${box.y})` }
const chain = computed(() => {
  const ids = new Set<string>()
  const related = selectedAction.value ? [selectedAction.value] : activeActions.value
  if (selectedInput.value) { ids.add(selectedInput.value.id); selectedInput.value.prerequisiteKeys.forEach(key => ids.add(`input:${key}`)); selectedInput.value.potentialTargets.forEach(target => ids.add(`file:${target.document}`)); visibleSubfields(selectedInput.value).filter(child => !selectedSubfield.value || child.id === selectedSubfield.value.id).forEach(child => ids.add(child.id)) }
  for (const action of related) { ids.add(action.id); ids.add(`file:${action.document}`); for (const key of action.inputKeys) ids.add(`input:${key}`) }
  return ids
})
const eligibleEdges = computed(() => graph.value.edges.filter(edge => ['structure', 'dependency', 'destination', 'prerequisite'].includes(edge.kind) || (edge.kind === 'potential' && (!graph.value.actions.length || !graph.value.inputs.find(input => input.id === edge.from)?.actionIds.length))))
const edgeRoutes = computed(() => routeBusinessGraphEdges(eligibleEdges.value, position))
const edges = computed(() => eligibleEdges.value.filter(edge => edgeRoutes.value.has(edge.id)).sort((a, b) => Number(chain.value.has(a.from) && chain.value.has(a.to)) - Number(chain.value.has(b.from) && chain.value.has(b.to))))
function selectInput(key: string) { selectedKey.value = key; selectedActionId.value = ''; selectedSubfieldKey.value = '' }
function selectSubfield(parentKey: string, key: string) { selectInput(parentKey); selectedSubfieldKey.value = key }
function selectAction(id: string) { selectedActionId.value = id; selectedSubfieldKey.value = ''; if (!selectedAction.value?.inputKeys.includes(selectedKey.value)) selectedKey.value = '' }
function clearSelection() { selectedKey.value = ''; selectedActionId.value = ''; selectedSubfieldKey.value = '' }
function filterFile(key: string) { clearSelection(); selectedGroup.value = ''; selectedFile.value = selectedFile.value === key ? '' : key }
function clear() { clearSelection(); query.value = ''; selectedFile.value = ''; selectedGroup.value = props.catalog.groups[0]?.id ?? '' }
const graphLabel = (text: string, limit = 34) => {
  let width = 0, label = ''
  for (const char of text) { width += char.charCodeAt(0) > 255 ? 2 : 1; if (width > limit) return `${label}…`; label += char }
  return label
}
function openClause(action: typeof graph.value.actions[number]) {
  if (!props.disabled && selectedInput.value && action.inputKeys.includes(selectedInput.value.key)) emit('location', { fieldKey: selectedInput.value.key, actionId: action.id, document: action.document, clause: action.clause })
}
function returnInput() { if (!props.disabled && selectedInput.value && selectedInput.value.applicability !== 'no') emit('input', selectedInput.value.key) }
function openPotential(target: DraftTarget) { if (!props.disabled && selectedInput.value) emit('location', { fieldKey: selectedInput.value.key, actionId: '', document: target.document, clause: target.clause }) }
function focusControl(selector: string) { dialog.value?.querySelector<HTMLElement>(selector)?.focus() }
async function toggleFullscreen() { fullscreen.value = !fullscreen.value; await nextTick(); focusControl('[data-graph-fullscreen]') }
function keydown(event: KeyboardEvent) {
  // The reader behind this modal also owns Escape and arrows.
  event.stopPropagation()
  if (event.key === 'Escape') {
    event.preventDefault()
    if (fullscreen.value) { fullscreen.value = false; void nextTick(() => focusControl('[data-graph-fullscreen]')) }
    else emit('close')
    return
  }
  if (event.key !== 'Tab') return
  const controls = [...(dialog.value?.querySelectorAll<HTMLElement>('button:not(:disabled),input,select,[tabindex="0"]') ?? [])]
  const first = controls[0], last = controls[controls.length - 1]
  if (!first) { event.preventDefault(); dialog.value?.focus(); return }
  if (event.shiftKey && (document.activeElement === first || document.activeElement === dialog.value)) { event.preventDefault(); last?.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus() }
}
onMounted(() => { void nextTick(() => focusControl('[data-graph-search]')) })
watch(() => props.projectId, () => { rejectedPlan.value = props.plan; clear(); fullscreen.value = false })
watch([selectedGroup, selectedFile, query], clearSelection)
watch(graph, () => { if (selectedActionId.value && !selectedAction.value) selectedActionId.value = ''; if (selectedKey.value && !selectedInput.value) selectedKey.value = '' })
</script>

<template>
  <div class="business-graph-overlay" :class="{ fullscreen }" @keydown="keydown">
    <section ref="dialog" class="business-graph" role="dialog" aria-modal="true" tabindex="-1" :aria-label="t('Business relationship graph', '业务关系图谱', '業務關係圖譜')">
      <header><div><h2>{{ t('Business relationship graph', '业务关系图谱', '業務關係圖譜') }}</h2><p>{{ t('Planned actions from current inputs. Saved document changes are verified in document review.', '依据当前输入展示处理方案；已保存文稿的实际修改须在文稿审阅中核对。', '依據目前輸入展示處理方案；已儲存文稿的實際修改須在文稿審閱中核對。') }}</p></div><div class="graph-window-controls"><button type="button" data-graph-fullscreen :aria-pressed="fullscreen" @click="toggleFullscreen">{{ fullscreen ? t('Exit fullscreen', '退出全屏', '退出全螢幕') : t('Fullscreen', '全屏', '全螢幕') }}</button><button type="button" data-graph-close @click="emit('close')">{{ t('Close', '关闭', '關閉') }}</button></div></header>
      <p v-if="graph.planStatus !== 'current'" class="graph-notice" role="status">{{ graph.planStatus === 'stale' ? t('The plan does not match current inputs. Action results are withheld; catalogue targets remain inspectable.', '处理方案与当前输入不一致，暂不展示方案结果；仍可核对目录目标。', '處理方案與目前輸入不一致，暫不展示方案結果；仍可核對目錄目標。') : t('A current plan is unavailable. Catalogue targets remain inspectable.', '尚无当前处理方案，仍可核对目录目标。', '尚無目前處理方案，仍可核對目錄目標。') }}</p>
      <div class="graph-toolbar"><label>{{ t('Group', '分组', '分組') }} <select v-model="selectedGroup" data-graph-group><option value="">{{ t('All groups', '全部分组', '全部分組') }}</option><option v-for="item in graph.groups" :key="item.id" :value="item.id">{{ l(item.label) }} · {{ item.inputKeys.length }}</option></select></label><label class="graph-search">{{ t('Search', '搜索', '搜尋') }} <input v-model="query" data-graph-search type="search" :placeholder="t('Inputs, values or planned clauses', '输入、取值或方案条款', '輸入、取值或方案條款')"></label><div class="file-filters"><button type="button" data-graph-file-filter="" :aria-pressed="!selectedFile" @click="selectedFile = ''; clearSelection()">{{ t('All files', '全部文件', '全部檔案') }}</button><button v-for="file in graph.files" :key="file.key" type="button" :data-graph-file-filter="file.key" :aria-pressed="selectedFile === file.key" @click="filterFile(file.key)">{{ file.key }}</button></div><button type="button" data-graph-clear-selection @click="clearSelection">{{ t('Clear selection', '清除选中', '清除選取') }}</button><button type="button" data-graph-clear @click="clear">{{ t('Reset filters', '重置筛选', '重設篩選') }}</button></div>
      <p class="graph-count" aria-live="polite">{{ graph.groups.length }} {{ t('question groups', '业务问题组', '業務問題組') }} · {{ graph.inputs.length }} {{ t('catalogue inputs', '项目输入', '專案輸入') }} · {{ structuralCount }} {{ t('structural child fields', '个结构子字段', '個結構子欄位') }} · {{ graph.actions.length }} {{ t('current planned actions', '项当前方案', '項目前方案') }} · {{ selectedInput || selectedAction ? t('Selected chain highlighted; other pool nodes remain dimmed and selectable.', '选中链路高亮；变量池的其他节点置灰，仍可选择。', '選中鏈路高亮；變量池的其他節點轉灰，仍可選擇。') : t('Explicit filters define the visible pool.', '当前变量池由显式筛选确定。', '目前變量池由明確篩選決定。') }}</p>
      <div class="graph-body">
        <div class="graph-scroll"><p v-if="!inputs.length && !actions.length" class="graph-empty">{{ t('No relationships match these filters. Clear or change the filters to continue.', '当前筛选没有匹配的关联，请清除或调整筛选。', '目前篩選沒有符合的關聯，請清除或調整篩選。') }}</p><svg :width="geometry.width" :height="height" :viewBox="`0 0 ${geometry.width} ${height}`" role="group" :aria-label="t('Inputs, planned actions and destinations', '输入、处理方案及文件落点', '輸入、處理方案及檔案落點')">
          <text :x="geometry.input.x" y="23" class="column-heading">{{ t('Variables', '输入变量', '輸入變量') }} · {{ inputs.length }}</text><text :x="geometry.action.x" y="23" class="column-heading">{{ t('Planned actions', '当前处理方案', '目前處理方案') }} · {{ actions.length }}</text><text :x="geometry.file.x" y="23" class="column-heading">{{ t('File destinations', '文件落点', '檔案落點') }}</text>
          <g v-for="container in variableLayout.containers" :key="container.id" :transform="`translate(${geometry.group.x} ${container.y})`" class="variable-container" :class="{ 'composite-parent': compositeGroup(container.groupId), dim: chain.size && !container.inputKeys.some(key => chain.has(`input:${key}`)) }" role="group" :data-graph-parent="compositeGroup(container.groupId) ? container.groupId : undefined" :data-graph-pool-group="compositeGroup(container.groupId) ? undefined : container.groupId" :aria-label="l(graph.groups.find(group => group.id === container.groupId)?.label)"><rect :width="geometry.group.w" :height="container.h" rx="6"/><text x="10" y="16" class="group-kind">{{ compositeGroup(container.groupId) ? t('Parent variable', '父变量', '父變量') : t('Input group', '业务问题组', '業務問題組') }}{{ container.context ? ` · ${t('Related context', '关联上下文', '關聯上下文')}` : '' }}</text><text x="10" y="31" class="group-label">{{ graphLabel(l(graph.groups.find(group => group.id === container.groupId)?.label), 40) }}</text></g>
          <path v-for="edge in edges" :key="edge.id" :d="edgeRoutes.get(edge.id)" :data-graph-edge="edge.id" :data-edge-from="edge.from" :data-edge-to="edge.to" class="graph-edge" :class="[edge.kind, { selected: chain.has(edge.from) && chain.has(edge.to), dim: chain.size && !(chain.has(edge.from) && chain.has(edge.to)) }]" />
          <g v-for="input in inputs" :key="input.id" :transform="nodeTransform(input.id)" class="graph-node input-node" :class="{ selected: chain.has(input.id), dim: chain.size && !chain.has(input.id) }" role="button" tabindex="0" :aria-label="`${inputKindLabel(input)} · ${l(input.field.label)}`" :aria-pressed="selectedKey === input.key && !selectedSubfield" :data-graph-input="input.key" :data-graph-input-kind="inputKind(input)" @click="selectInput(input.key)" @keydown.enter.prevent="selectInput(input.key)" @keydown.space.prevent="selectInput(input.key)"><title>{{ inputKindLabel(input) }} · {{ l(input.field.label) }} · {{ valueSummary(input.field, input.value, locale) }} · {{ applicableLabel(input.applicability) }} · {{ recordedAdoption(input.key) }}{{ dirtyKeys.includes(input.key) ? ` · ${t('Unsaved input', '输入未保存', '輸入未儲存')}` : '' }}</title><rect :width="geometry.input.w" :height="geometry.input.h" rx="5"/><text x="10" y="14" class="node-kind">{{ inputKindLabel(input) }}</text><text x="10" y="30">{{ graphLabel(l(input.field.label), 30) }}</text><text x="10" y="46" class="node-sub">{{ graphLabel(`${draftWord((fieldStates[input.key] || 'unknown') as DraftWord, locale)} · ${valueSummary(input.field, input.value, locale)}${dirtyKeys.includes(input.key) ? ` · ${t('Unsaved', '未保存', '未儲存')}` : ''}`, 38) }}</text></g>
          <g v-for="child in visibleChildren" :key="child.id" :transform="nodeTransform(child.id)" class="graph-node subfield-node" :class="{ selected: chain.has(child.id), dim: chain.size && !chain.has(child.id) }" role="button" tabindex="0" :aria-label="`${subfieldKindLabel(child.parent)} · ${l(child.field.label)} · ${l(child.parent.field.label)}`" :aria-pressed="selectedSubfield?.id === child.id" :data-graph-subfield="`${child.parentKey}.${child.key}`" @click="selectSubfield(child.parentKey, child.key)" @keydown.enter.prevent="selectSubfield(child.parentKey, child.key)" @keydown.space.prevent="selectSubfield(child.parentKey, child.key)"><title>{{ subfieldKindLabel(child.parent) }} · {{ l(child.field.label) }} · {{ l(child.parent.field.label) }}</title><rect :width="geometry.subfield.w" :height="geometry.subfield.h" rx="4"/><text x="8" y="13" class="node-kind">{{ subfieldKindLabel(child.parent) }}{{ child.field.optional ? ` · ${t('Optional', '可选', '可選')}` : '' }}</text><text x="8" y="27">{{ graphLabel(l(child.field.label), 28) }}</text></g>
          <g v-for="action in actions" :key="action.id" :transform="nodeTransform(action.id)" class="graph-node action-node" :class="{ selected: chain.has(action.id), dim: chain.size && !chain.has(action.id) }" role="button" tabindex="0" :aria-label="`${action.document} ${action.clause} ${actionLabel(action.result.action)}`" :aria-pressed="selectedActionId === action.id" :data-graph-action="action.id" @click="selectAction(action.id)" @keydown.enter.prevent="selectAction(action.id)" @keydown.space.prevent="selectAction(action.id)"><title>{{ action.document }} · {{ action.clause }} · {{ l(action.result.detail) }}</title><rect :width="geometry.action.w" :height="geometry.action.h" rx="5"/><text x="10" y="22">{{ graphLabel(`${action.document} · ${action.clause}`, 30) }}</text><text x="10" y="40" class="node-sub">{{ actionLabel(action.result.action) }}</text></g>
          <g v-for="file in graph.files" :key="file.id" :transform="nodeTransform(file.id)" class="graph-node file-node" :class="{ selected: chain.has(file.id) || selectedFile === file.key, dim: chain.size && !chain.has(file.id) }" role="button" tabindex="0" :aria-label="`${t('Filter destination', '筛选文件落点', '篩選檔案落點')} ${file.key}`" :aria-pressed="selectedFile === file.key" :data-graph-file="file.key" @click="filterFile(file.key)" @keydown.enter.prevent="filterFile(file.key)" @keydown.space.prevent="filterFile(file.key)"><rect :width="geometry.file.w" :height="geometry.file.h" rx="5"/><text x="12" y="26">{{ file.key }}</text><text x="12" y="48" class="node-sub">{{ file.actionIds.length }} {{ t('planned actions', '项方案', '項方案') }}</text></g>
        </svg></div>
        <aside data-graph-inspector class="graph-inspector">
          <p v-if="!selectedInput && !selectedAction">{{ t('Select an input or planned action to inspect its relationships.', '选择输入或处理方案以核对关联关系。', '選擇輸入或處理方案以核對關聯關係。') }}</p>
          <section v-if="selectedInput && selectedSubfield" data-graph-selected-subfield><h3>{{ l(selectedSubfield.field.label) }}</h3><p>{{ subfieldKindLabel(selectedInput) }} · {{ t('Belongs to', '所属变量', '所屬變量') }}: {{ l(selectedInput.field.label) }}</p><p>{{ isObjectKind(selectedInput.field.kind) ? t('A component of the parent value; editing and adoption remain with the parent input.', '这是父变量取值的组成部分；仍在父输入中编辑和采用。', '這是父變量取值的組成部分；仍在父輸入中編輯和採用。') : t('This is a field of each item in the collection, not a separate project answer or a selected first row.', '这是集合每个条目的字段，不是独立项目答案，也不只代表第一行。', '這是集合每個項目的欄位，不是獨立專案答案，也不只代表第一列。') }}</p><p v-if="invalidCurrentValue">{{ t('The current value format cannot be displayed as structured data.', '当前取值格式暂无法按结构展示。', '目前取值格式暫無法按結構展示。') }}</p><p v-else-if="!selectedSubfieldValues.length">{{ valueSummary(selectedInput.field, selectedInput.value, locale) }}</p><table v-else class="subfield-values"><thead><tr><th>{{ isObjectKind(selectedInput.field.kind) ? t('Component', '组成字段', '組成欄位') : t('Item', '条目', '項目') }}</th><th>{{ t('Current value · read only', '当前取值 · 此处只读', '目前取值 · 此處唯讀') }}</th><th v-if="selectedSubfield.field.condition">{{ t('Within-item applicability', '条目内适用性', '項目內適用性') }}</th></tr></thead><tbody><tr v-for="entry in selectedSubfieldValues" :key="entry.index"><td>{{ isObjectKind(selectedInput.field.kind) ? l(selectedSubfield.field.label) : entry.index }}</td><td><DraftingValueDisplay :field="selectedSubfield.field" :value="entry.value" :locale="locale" /></td><td v-if="selectedSubfield.field.condition">{{ applicableLabel(entry.applicability) }}</td></tr></tbody></table></section>
          <section v-if="activeActions.length"><h3>{{ t('Planned actions', '当前处理方案', '目前處理方案') }}</h3><article v-for="action in activeActions" :key="action.id"><h4>{{ action.document }} · {{ action.clause }} · {{ actionLabel(action.result.action) }}</h4><p>{{ l(action.result.detail) }}</p><p v-if="action.inputKeys.length > 1" class="graph-notice">{{ t('This is one shared planned action. Its linked inputs are not independent decisions; the action wording supplies the relationship.', '这是同一项共用处理方案，关联输入并非各自独立决策；关系须依方案原有说明理解。', '這是同一項共用處理方案，關聯輸入並非各自獨立決策；關係須依方案原有說明理解。') }}</p><div class="shared-inputs"><template v-for="key in action.inputKeys" :key="key"><button v-if="graph.inputs.some(input => input.key === key)" type="button" :data-shared-input="key" @click="selectInput(key)">{{ inputLabel(key) }}</button><span v-else>{{ inputLabel(key) }} · {{ t('metadata', '附加设置', '附加設定') }}</span></template></div><p v-if="!action.inputKeys.some(key => graph.inputs.some(input => input.key === key))">{{ t('This source or guidance action has no linked business input. Inspect its actual plan wording here; no input is invented for navigation.', '此来源或指引处理没有关联业务输入。可在此核对原有方案说明；不虚构输入导航。', '此來源或指引處理沒有關聯業務輸入。可在此核對原有方案說明；不虛構輸入導覽。') }}</p><p v-else-if="!selectedInput">{{ t('Choose a linked input before opening its clause.', '先选择一项关联输入，再打开对应条款。', '先選擇一項關聯輸入，再開啟對應條款。') }}</p><button type="button" :data-graph-clause="action.id" :disabled="disabled || !selectedInput || !action.inputKeys.includes(selectedInput.key)" @click="openClause(action)">{{ t('Read original clause', '阅读原始条款', '閱讀原始條款') }} · {{ action.document }} {{ action.clause }}</button></article></section>
          <template v-if="selectedInput"><h3>{{ l(selectedInput.field.label) }}</h3><p>{{ t('Group membership', '所属分组', '所屬分組') }}: {{ selectedGroupLabel }}</p><p data-graph-applicability>{{ t('Current applicability', '当前适用性', '目前適用性') }}: {{ applicableLabel(selectedInput.applicability) }}</p><p data-graph-adoption>{{ t('Recorded adoption', '已登记采用状态', '已登記採用狀態') }}: {{ recordedAdoption(selectedInput.key) }}</p><p v-if="recordedReviewNeeded(selectedInput.key)" data-graph-recorded-review>{{ t('Recorded review required', '已登记需复核', '已登記需覆核') }} · {{ draftWord('needs_review', locale) }}</p><p v-if="fieldStates[selectedInput.key] && fieldStates[selectedInput.key] !== 'inactive'" data-graph-review-state>{{ t('Current review status', '当前核对状态', '目前核對狀態') }}: {{ draftWord(fieldStates[selectedInput.key] as DraftWord, locale) }}</p><p v-if="dirtyKeys.includes(selectedInput.key)" data-graph-dirty>{{ t('Unsaved input', '输入未保存', '輸入未儲存') }}</p><h4>{{ t('Current value · read only', '当前取值 · 此处只读', '目前取值 · 此處唯讀') }}</h4><div data-graph-current-value><p v-if="invalidCurrentValue" class="graph-notice">{{ t('The current value format cannot be displayed as structured data.', '当前取值格式暂无法按结构展示。', '目前取值格式暫無法按結構展示。') }}</p><DraftingValueDisplay v-else :field="selectedInput.field" :value="selectedInput.value" :locale="locale" /></div><button type="button" data-graph-return-input :disabled="disabled || selectedInput.applicability === 'no'" @click="returnInput">{{ t('Return to input', '返回输入修改', '返回輸入修改') }}</button>
          </template>
          <template v-if="selectedInput">
            <section data-graph-condition><h4>{{ t('Prerequisites', '条件依赖', '條件依賴') }}</h4><template v-if="selectedInput.condition"><p>{{ t('All prerequisites (AND)', '所有条件同时满足（AND）', '所有條件同時滿足（AND）') }}</p><ul><li v-for="(term, index) in selectedInput.condition.all" :key="index"><button v-if="graph.inputs.some(item => item.key === term.field)" type="button" :data-graph-prerequisite="term.field" @click="selectInput(term.field)">{{ inputLabel(term.field) }}</button><span v-else>{{ inputLabel(term.field) }}</span> {{ operatorLabel(term.operator) }} <span v-if="term.operator !== 'unknown'">{{ criterionValue(term) }}</span></li></ul></template><p v-else>{{ t('No conditional prerequisite is recorded. Group membership is a structural relationship.', '未登记条件依赖；所属分组表示结构关系。', '未登記條件依賴；所屬分組表示結構關係。') }}</p></section>
            <section v-if="structure.length" data-graph-structure><h4>{{ isObjectKind(selectedInput.field.kind) ? t('Sub-variables', '子变量', '子變量') : t('Collection item fields', '集合条目字段', '集合項目欄位') }}</h4><p>{{ isObjectKind(selectedInput.field.kind) ? t('These components belong to the parent value, with no separate adoption record.', '这些组成字段属于父变量取值，没有独立的采用记录。', '這些組成欄位屬於父變量取值，沒有獨立的採用記錄。') : t('These fields belong to each item in the parent collection. They are not separate project inputs or identified rows.', '以下字段属于父集合的各个条目，不代表独立项目输入或已识别的行。', '以下欄位屬於父集合的各個項目，不代表獨立專案輸入或已識別的列。') }}</p><ul><li v-for="column in structure" :key="column.id"><button type="button" @click="selectSubfield(selectedInput.key, column.key)">{{ l(column.field.label) }}</button><p v-if="column.field.condition">{{ t('Within each item: all prerequisites (AND)', '各条目内：所有条件同时满足（AND）', '各項目內：所有條件同時滿足（AND）') }}<span v-for="(term, index) in column.field.condition.all" :key="index"> · {{ l(selectedInput.field.columnFields?.find(item => item.key === term.field)?.label) || inputLabel(term.field) }} {{ operatorLabel(term.operator) }} {{ criterionValue(term) }}</span></p></li></ul></section>
            <section data-graph-potential-targets><h4>{{ t('Potential catalogue targets', '目录中的可能落点', '目錄中的可能落點') }}</h4><p>{{ t('Catalogue associations do not assert that a planned action or saved change occurred.', '目录关联并不表示已产生处理方案或已保存修改。', '目錄關聯並不表示已產生處理方案或已儲存修改。') }}</p><p v-if="!selectedInput.potentialTargets.length">{{ t('No catalogue target is recorded.', '未登记目录落点。', '未登記目錄落點。') }}</p><button v-for="(target, index) in selectedInput.potentialTargets" :key="index" type="button" :data-graph-potential="`${target.document}:${target.clause}`" :disabled="disabled" @click="openPotential(target)">{{ t('Read original clause', '阅读原始条款', '閱讀原始條款') }} · {{ target.document }} {{ target.clause }}</button></section>
            <section data-graph-evidence><h4>{{ t('Source evidence', '来源依据', '來源依據') }}</h4><p v-if="variable?.source">{{ variable.source }}</p><p v-if="!variable?.source && !variable?.candidates?.length">{{ t('No source evidence is recorded.', '尚无登记的来源依据。', '尚無登記的來源依據。') }}</p><article v-for="(candidate, index) in variable?.candidates ?? []" :key="index"><strong>{{ candidate.fileName }}</strong><blockquote v-if="candidate.sourceQuote">{{ candidate.sourceQuote }}</blockquote><p v-if="candidate.reason">{{ t('Recorded model explanation', '登记的模型说明', '登記的模型說明') }}: {{ candidate.reason }}</p></article></section>
          </template>
        </aside>
      </div>
      <footer>{{ t('Read-only graph. Solid lines link inputs to a shared planned action and its file. Dashed lines are potential catalogue targets; dotted lines are recorded prerequisites. Group and collection membership are structural.', '图谱仅供核对。实线关联输入、共用处理方案与文件；虚线表示目录可能落点，点线表示登记的条件依赖。分组与集合所属关系表示结构。', '圖譜僅供核對。實線關聯輸入、共用處理方案與檔案；虛線表示目錄可能落點，點線表示登記的條件依賴。分組與集合所屬關係表示結構。') }}</footer>
    </section>
  </div>
</template>

<style scoped>
.variable-container rect { fill: #fafcfb; stroke: #d0dfd9; stroke-dasharray: 4 3; }.variable-container.composite-parent rect { fill: #f0f8f5; stroke: #76a696; stroke-dasharray: none; }.group-kind,.node-kind { font-size: 9px; fill: var(--muted); }.group-label { font-size: 11px; font-weight: 700; fill: var(--ink); }.graph-node .node-kind { font-size: 9px; fill: var(--muted); }.subfield-node rect { fill: #f2f5fa; stroke: #a8b9ce; }.subfield-node text { font-size: 11px; }.graph-edge.structure { stroke: #93a6bd; stroke-width: 1; }.subfield-values { width: 100%; table-layout: fixed; border-collapse: collapse; }.subfield-values th,.subfield-values td { text-align: left; vertical-align: top; padding: 7px; border-bottom: 1px solid var(--line); overflow-wrap: anywhere; }.subfield-values th:first-child { width: 25%; }
.graph-inspector [data-graph-evidence] strong { display: block; max-width: 100%; white-space: normal; overflow-wrap: anywhere; word-break: break-word; }
.business-graph-overlay.fullscreen { padding: 0; }.fullscreen .business-graph { border-radius: 0; }.graph-window-controls,.graph-toolbar,.file-filters { display: flex; align-items: center; gap: 7px; flex-wrap: wrap; }.graph-window-controls { flex-shrink: 0; }.graph-toolbar { flex-shrink: 0; }.graph-toolbar label { display: flex; align-items: center; gap: 7px; min-width: 0; }.graph-toolbar input,.graph-toolbar select { border: 1px solid var(--line); border-radius: var(--radius-sm,6px); background: var(--surface); padding: 7px 9px; color: var(--ink); font: inherit; min-width: 0; max-width: 250px; }.graph-search { flex: 1; }.graph-search input { width: 100%; }.file-filters button[aria-pressed="true"] { background: var(--teal-soft,#e4f2ed); color: var(--teal,#17694c); border-color: var(--teal); }.graph-count { margin: 0; color: var(--muted); font-size: 11px; }.graph-edge.potential { stroke-dasharray: 5 4; }.graph-edge.prerequisite { stroke-dasharray: 1 4; stroke: #779dbe; }.graph-empty { padding: 10px; color: var(--muted); }.graph-inspector ul { padding-left: 18px; }.graph-inspector li + li { margin-top: 8px; }.graph-inspector [data-graph-potential-targets] button { margin: 0 5px 7px 0; }
.business-graph-overlay { position: fixed; inset: 0; z-index: 1400; padding: 24px; display: flex; background: #17312666; }.business-graph { display: flex; flex-direction: column; width: 100%; min-width: 0; min-height: 0; border: 1px solid var(--line); border-radius: var(--radius-lg,12px); background: var(--surface,#fff); color: var(--ink); box-shadow: var(--shadow-lg); padding: 18px; gap: 12px; font: 13px/1.55 var(--font-ui,Arial,sans-serif); }.business-graph header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; flex-shrink: 0; }.business-graph h2 { margin: 0; font-size: 19px; }.business-graph header p,footer { margin: 5px 0 0; color: var(--muted); font-size: 11.5px; }.graph-body { display: grid; grid-template-columns: minmax(0,1fr) 360px; gap: 14px; flex: 1; min-height: 0; }.graph-scroll { overflow: auto; min-width: 0; min-height: 0; border: 1px solid var(--line); border-radius: var(--radius-md,8px); background: var(--surface-subtle); }.graph-scroll svg { width: auto; min-width: 0; max-width: none; display: block; }.column-heading { font-size: 12px; font-weight: 700; fill: var(--muted); }.graph-edge { fill: none; stroke: #aebfb7; stroke-width: .9; opacity: .32; }.graph-edge.selected { stroke: var(--orange,#d86b1f); stroke-width: 1.6; opacity: 1; }.graph-edge.dim { opacity: .09; }.dim { opacity: .28; }.graph-node { cursor: pointer; }.graph-node rect { fill: #e6f4f2; stroke: #8dc9c4; }.action-node rect { fill: #fbf0e7; stroke: #e9aa7e; }.file-node rect { fill: #edf2fa; stroke: #9abce0; }.graph-node text { fill: var(--ink,#243f34); font-size: 12px; }.graph-node .node-sub { fill: var(--muted); font-size: 10px; }.graph-node.selected rect { stroke: var(--orange,#d86b1f); stroke-width: 2; }.graph-node:focus-visible { outline: none; }.graph-node:focus-visible rect { stroke: var(--accent,#00877f); stroke-width: 3; }.graph-inspector { overflow: auto; min-width: 0; min-height: 0; border: 1px solid var(--line); padding: 16px; border-radius: var(--radius-md,8px); }.graph-inspector h3 { margin: 0 0 10px; font-size: 16px; }.graph-inspector h4 { margin: 12px 0 8px; font-size: 12px; }.graph-inspector section { border-top: 1px solid var(--line); padding-top: 14px; margin-top: 18px; }.graph-inspector p { overflow-wrap: anywhere; white-space: pre-wrap; }.graph-inspector article+article { margin-top: 18px; }.graph-inspector blockquote { margin: 9px 0; border-left: 2px solid #9eb8ac; padding-left: 10px; white-space: pre-wrap; overflow-wrap: anywhere; }.graph-notice { margin: 0; padding: 9px 12px; background: var(--amber-soft,#fff7e9); color: var(--amber,#765719); border-left: 3px solid var(--amber); font-size: 12px; }.business-graph button { border: 1px solid var(--line); background: var(--surface); border-radius: var(--radius-sm,6px); color: var(--ink); padding: 7px 10px; font: inherit; cursor: pointer; }.business-graph button:disabled { opacity: .5; cursor: default; }.business-graph button:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }.shared-inputs { display: flex; flex-wrap: wrap; gap: 6px; margin: 10px 0; }
@media(max-width:1000px) { .graph-body { grid-template-columns: minmax(0,1fr) 320px; } }@media(max-width:700px) { .business-graph-overlay { padding: 8px; }.business-graph { padding: 12px; overflow: hidden; gap: 8px; }.business-graph header p { display: none; }.business-graph h2 { font-size: 16px; }.graph-toolbar { max-height: 25vh; overflow: auto; }.graph-toolbar label { flex: 1 1 130px; }.graph-body { grid-template-columns: minmax(0,1fr); grid-template-rows: minmax(0,1fr) minmax(0,.9fr); }.graph-inspector { padding: 12px; }.business-graph footer { font-size: 10px; } }
</style>
