<script setup lang="ts">
import { computed, reactive, ref, toRaw, watch } from 'vue'
import { createReviewReducer } from '@/drafting/source-review/reducer.js'
import { targetSpans } from '@/drafting/source-review-state'
import type { ReviewObject, ReviewSource, ReviewState, ReviewVariable } from '@/drafting/source-review-types'

const props = defineProps<{ state: ReviewState; ids: string[]; sources: ReviewSource[]; locale: string; draft?: ReviewObject }>()
const emit = defineEmits<{ merge: [ids: string[], targetId: string]; branches: [actions: ReviewObject[], targetId: string]; locate: [id: string]; draft: [value: ReviewObject] }>()
const t = (zh: string, en: string) => props.locale === 'en' ? en : zh
const copy = <T,>(value: T): T => JSON.parse(JSON.stringify(value))
const check = (item: ReviewVariable) => item.role.kind === 'verification' || item.definitionKind === 'source_seed' && !item.manual
function label(item?: ReviewVariable) {
  if (!item) return t('待核对的资料', 'Input to review')
  if (item.definitionKind === 'source_seed' && (!item.manual || /guidance:[a-f0-9]{16,}/i.test(item.nameZh + ' ' + item.nameEn))) return t('来源核对：', 'Source check: ') + (item.sourceAnchors[0]?.text ?? t('查看原文', 'See source'))
  return props.locale === 'en' ? item.nameEn || item.nameZh : item.nameZh || item.nameEn
}
const items = computed(() => props.ids.flatMap(id => props.state.variables.find(item => item.id === id && item.status !== 'excluded') ?? []))
const inputs = computed(() => props.state.variables.filter(item => item.status !== 'excluded' && !check(item)))
const primaryOptions = computed(() => items.value.filter(item => !check(item) && item.role.kind === 'input' && ['boolean', 'choice'].includes(item.type.kind) && (!item.correction || item.correction.kind === 'rule' && Array.isArray(item.correction.branches))))
const mode = ref('branches'), primaryId = ref(''), acknowledged = ref(false), branchAcknowledged = ref(false)
const paths = ref<ReviewObject[]>([])
const assignments = reactive<Record<string, ReviewObject>>({})
const decisionLinks = reactive<Record<string, string>>({})
const primary = computed(() => items.value.find(item => item.id === primaryId.value))
const file = (id: string) => { const source = props.sources.find(source => [String(source.id), source.docId, source.editionId].includes(String(id))); return source ? [source.fileKey, source.fileName].filter(Boolean).join(' · ') : t('来源版本待核对', 'Source edition to review') }
const actionLabels: Record<string, string> = { keep: '保留 / Retain', retain: '保留 / Retain', delete: '删除 / Delete', fill: '填写 / Fill', replace: '替换 / Replace', insert: '插入 / Insert', unused: '标为Not used / Mark Not used', not_used: '标为Not used / Mark Not used', check: '待核查 / Check' }
const scopeLabel = (mode: string) => ({ each: t('逐项判断', 'Each item'), any: t('任一项成立', 'Any item'), all: t('全部项成立', 'All items'), none: t('全部项均不成立', 'No items') }[mode] ?? t('范围方式待核对', 'Review scope mode'))
const targetExcerpt = (value: string) => value.length > 180 ? value.slice(0,180) + '…' : value
function condition(clause: ReviewObject) {
  const input = props.state.variables.find(item => item.id === clause.inputId)
  const operators: Record<string, string> = { yes: t('为是', 'is yes'), no: t('为否', 'is no'), known: t('已填写', 'is provided'), eq: t('等于', 'equals'), ge: '≥', le: '≤', gt: '>', lt: '<', has: t('包含', 'includes'), lacks: t('不包含', 'does not include'), empty: t('明确为空', 'is explicitly empty'), notempty: t('有内容', 'has content') }
  const option = input?.options?.find(option => option.value === clause.value)
  return `${label(input)} ${operators[clause.operator] ?? t('判断方式待核对', 'operator to review')} ${option ? (props.locale === 'en' ? option.labelEn : option.labelZh) : clause.value ?? ''}`.trim()
}
const rows = computed(() => items.value.flatMap(owner => owner.editTargets.map((target, index) => ({ key: owner.id + ':' + index, owner, index, target, branch: owner.correction?.kind === 'rule' ? owner.correction.branches?.find((branch: ReviewObject) => branch.id === target.branchId) : undefined }))))
watch(() => props.ids.join('|'), () => {
  primaryId.value = primaryOptions.value[0]?.id ?? items.value[0]?.id ?? ''
  acknowledged.value = false; branchAcknowledged.value = false
}, { immediate: true })
watch(primaryId, () => {
  const item = primary.value
  paths.value = item?.type.kind === 'boolean'
    ? [{ key: 'yes', operator: 'yes', value: '', nameZh: '为是', nameEn: 'Yes' }, { key: 'no', operator: 'no', value: '', nameZh: '为否', nameEn: 'No' }]
    : item?.type.kind === 'choice' ? item.options.map((option, index) => ({ key: 'choice-' + index, operator: 'eq', value: option.value, nameZh: option.labelZh, nameEn: option.labelEn })) : []
  Object.keys(assignments).forEach(key => delete assignments[key])
  Object.keys(decisionLinks).forEach(key => delete decisionLinks[key])
  for (const item of items.value) if (item.id !== primaryId.value && !check(item) && item.role.kind === 'input' && item.type.kind === 'boolean') decisionLinks[item.id] = ''
  for (const row of rows.value) assignments[row.key] = { routes: Object.fromEntries(paths.value.map(path => [path.key, ''])), extra: [] }
  branchAcknowledged.value = false; acknowledged.value = false
}, { immediate: true, flush: 'sync' })
if (props.draft && items.value.some(item => item.id === props.draft!.primaryId)) {
  primaryId.value = props.draft.primaryId; mode.value = props.draft.mode
  paths.value = copy(props.draft.paths); Object.assign(assignments, copy(props.draft.assignments))
  Object.assign(decisionLinks, copy(props.draft.decisionLinks ?? {}))
  acknowledged.value = props.draft.acknowledged; branchAcknowledged.value = props.draft.branchAcknowledged
}
watch([mode, primaryId, paths, assignments, decisionLinks, acknowledged, branchAcknowledged], () => emit('draft', { mode: mode.value, primaryId: primaryId.value, paths: copy(paths.value), assignments: copy(assignments), decisionLinks: copy(decisionLinks), acknowledged: acknowledged.value, branchAcknowledged: branchAcknowledged.value }), { deep: true })
const mergePreview = computed(() => mode.value === 'duplicates' ? createReviewReducer({ sources: props.sources }).mergeMany(toRaw(props.state), props.ids, primaryId.value) : { state: props.state, problem: null })
const mergeReason = computed(() => {
  const issue = mergePreview.value.problem
  if (!issue) return ''
  const reasons: Record<string, string> = {
    MERGE_SELECTION_REQUIRED: t('请至少勾选两个条目。', 'Select at least two items.'),
    MERGE_TARGET_REQUIRED: t('请选择本次勾选中要保留的问题。', 'Choose a retained question from the selection.'),
    MERGE_TYPE_MISMATCH: t('答案格式不同，例如是／否与文字核对项，不能合并。', 'Answer shapes differ, such as yes/no and a text check; these cannot be merged.'),
    MERGE_ROLE_MISMATCH: t('业务答案与来源核对的用途不同，不能合并。', 'A business answer and a source check serve different purposes and cannot be merged.'),
    MERGE_PARENT_MISMATCH: t('所属资料／明细不同，需先核对归属。', 'The record/list memberships differ; review ownership first.'),
    MERGE_OPTIONAL_MISMATCH: t('必答与选填不同，不能静默改变回答要求。', 'Required and optional answers cannot be silently combined.'),
    MERGE_OPTIONS_MISMATCH: t('可选答案不同。互斥处理请使用“同一问题的分支处理”。', 'Answer options differ. Use branch handling for alternative treatments.'),
    MERGE_FIXED_VALUE_MISMATCH: t('固定内容不同，需分别核对。', 'Fixed wording differs; review it separately.'),
    MERGE_RULE_CONFLICT: t('两项都有处理规则，不能拍扁；请在“同一问题的分支处理”逐处归属。', 'Both items have rules. Assign their passages in branch handling before considering a duplicate merge.'),
    MERGE_SOURCE_EDITION_CONFLICT: t('包含不同版次的SCC，不能共用一套修改。', 'Different SCC editions cannot share amendments.'),
    MERGE_SOURCE_SCOPE_CONFLICT: t('相关条件或答案跨越不相容来源版次。', 'Related conditions or answers cross incompatible source editions.')
  }
  return `${label(props.state.variables.find(item => item.id === issue.id))}：${reasons[issue.code] ?? t('选择已变化，请重新勾选核对。', 'The selection changed; select and review it again.')}`
})
const assigned = computed(() => rows.value.filter(row => paths.value.some(path => assignments[row.key]?.routes[path.key])))
const unassignedRoutes = computed(() => rows.value.reduce((count, row) => count + paths.value.filter(path => !assignments[row.key]?.routes[path.key]).length, 0))
const groups = computed(() => paths.value.map(path => ({ path, files: [...new Set(assigned.value.filter(row => assignments[row.key].routes[path.key]).map(row => row.target.docId))].map(docId => ({ docId, rows: assigned.value.filter(row => assignments[row.key].routes[path.key] && row.target.docId === docId) })) })))
const oldDecisions = computed(() => items.value.filter(item => Object.prototype.hasOwnProperty.call(decisionLinks, item.id)))
function replaceableRoute(item: ReviewVariable) {
  const correction = item.correction, branch = correction?.branches?.[0]
  return correction?.kind === 'rule' && Array.isArray(correction.branches) && correction.branches.length === 1 && branch.mode === 'all' && !branch.scopeId && !branch.scopeMode && branch.resultValue === undefined && branch.clauses?.filter((clause: ReviewObject) => clause.inputId === item.id).length === 1 && branch.clauses.some((clause: ReviewObject) => clause.inputId === item.id && ['yes', 'no'].includes(clause.operator))
}
function mappingProblem(item: ReviewVariable) {
  if (paths.value.length !== 2 || item.parentId !== primary.value?.parentId) return t('答案路线或所属明细尚不能对应。', 'Answer routes or list membership cannot be mapped yet.')
  const correction = item.correction
  if (correction && !replaceableRoute(item) && !(correction.kind === 'rule' && correction.needsRebind === true && Array.isArray(correction.branches) && !correction.branches.length && Array.isArray(correction.inputIds) && !correction.inputIds.length && Object.keys(correction).every(key => ['kind','needsRebind','branches','inputIds'].includes(key)))) return t('原问题的处理规则尚不能明确分出旧路由与额外前提，需先在原问题核对；本次保留规则及独立提问。', 'The original treatment rules do not clearly separate routing from additional prerequisites. Review the original question first; its rules and independent input are retained.')
  if (rows.value.filter(row => row.owner.id === item.id).some(row => paths.value.some(path => !assignments[row.key].routes[path.key]))) return t('须先把该项全部目标在两条路线分别明确处理，才能取消独立提问。', 'Specify both route actions for all its targets before removing the independent input.')
  return ''
}
function mappedClauses(clauses: ReviewObject[]) {
  return clauses.map(clause => {
    const mapped = decisionLinks[clause.inputId], path = paths.value.find(path => path.key === mapped)
    if (!path) return copy(clause)
    if (clause.operator === 'known') return { ...copy(clause), inputId: primaryId.value }
    const positive = clause.operator === 'yes' || clause.operator === 'eq' && clause.value === true
    const negative = clause.operator === 'no' || clause.operator === 'eq' && clause.value === false
    if (!positive && !negative) return copy(clause)
    const route = positive ? path : paths.value.find(other => other.key !== mapped)!
    return { inputId: primaryId.value, operator: route.operator, value: route.value }
  })
}
const branchProblem = computed(() => {
  if (!primaryOptions.value.some(item => item.id === primaryId.value)) return t('请选择一个是／否或单选的项目事实作为主问题。', 'Choose one yes/no or single-choice project fact as the primary question.')
  if (paths.value.length < 2 || paths.value.some(path => !path.nameZh?.trim() || !path.nameEn?.trim())) return t('请为每个答案路线补齐中英文名称。', 'Name each answer route in both languages.')
  if (!assigned.value.length) return t('尚未选择准确修改目标。仅有关联原文的条目需先回原件选取范围。', 'No amendment targets selected. Items with source quotations only need exact ranges selected on the original first.')
  if (assigned.value.some(row => row.owner.id !== primaryId.value && row.owner.role.kind === 'input' && row.owner.type.kind === 'boolean' && inherited(row).some((clause: ReviewObject) => clause.inputId === row.owner.id) && !decisionLinks[row.owner.id])) return t('所选目标含旧问题自己的路由条件。请先明确旧“是”对应主问题哪条路线，再替代旧路由；不能同时保留两套独立答案。', 'A selected target contains its old question’s routing condition. Explicitly map the old yes answer to a primary route before replacing it; two independent routing answers cannot be retained.')
  if (oldDecisions.value.some(item => decisionLinks[item.id] && mappingProblem(item))) return t('旧问题的答案映射尚不完整，未取消独立提问。', 'An old answer mapping is incomplete; its independent input has not been removed.')
  if (assigned.value.some(row => row.target.branchId && !row.branch || row.branch?.mode === 'any' && inherited(row).length > 1)) return t('所选目标的原条件未能完整保留；请先在原问题核对分支，不能丢掉原前提后搬移。', 'The original conditions cannot be preserved completely. Review the original branch before moving; existing prerequisites cannot be dropped.')
  for (const row of assigned.value) for (const path of paths.value.filter(path => assignments[row.key].routes[path.key])) {
    if (mappedClauses([...retainedPrerequisites(row), ...assignments[row.key].extra]).some(clause => clause.inputId === primaryId.value && (['yes','no'].includes(clause.operator) && ['yes','no'].includes(path.operator) && clause.operator !== path.operator || clause.operator === 'eq' && path.operator === 'eq' && clause.value !== path.value))) return t('原前提与所分配的主答案互相矛盾；请先在原问题核对这处条件，不能把相反答案同时当作成立。', 'An existing prerequisite conflicts with the assigned primary answer. Review this condition on the original question; opposite answers cannot both hold.')
  }
  if (assigned.value.some(row => assignments[row.key].extra.some((clause: ReviewObject) => !clause.inputId))) return t('请补齐新增前提，或移除空白前提。', 'Complete added prerequisites or remove the empty ones.')
  return ''
})
function inherited(row: ReviewObject) { return row.branch?.clauses ?? [] }
function retainedPrerequisites(row: ReviewObject) { return inherited(row).filter((clause: ReviewObject) => !(decisionLinks[row.owner.id] && replaceableRoute(row.owner) && clause.inputId === row.owner.id)) }
function commitBranches() {
  if (branchProblem.value || !branchAcknowledged.value || !primary.value) return
  const owner = primary.value, now = Date.now().toString(36), branches = copy(owner.correction?.kind === 'rule' ? owner.correction.branches ?? [] : [])
  const targets = copy(owner.editTargets).filter((_target: ReviewObject, index: number) => !assigned.value.some(row => row.owner.id === owner.id && row.index === index))
  for (const [index, row] of assigned.value.entries()) {
    const assignment = assignments[row.key]
    for (const path of paths.value.filter(path => assignment.routes[path.key])) {
      const id = 'review-route-' + now + '-' + index + '-' + path.key
      branches.push({ ...copy(row.branch ?? {}), id, mode: 'all', clauses: [{ inputId: owner.id, operator: path.operator, value: path.value }, ...mappedClauses(retainedPrerequisites(row)), ...mappedClauses(assignment.extra)], effectZh: path.nameZh, effectEn: path.nameEn })
      targets.push({ ...copy(row.target), ...(row.target.id ? { id: row.target.id + '-' + id } : {}), action: assignment.routes[path.key], branchId: id, needsRebind: true })
    }
  }
  const actions: ReviewObject[] = []
  for (const source of items.value.filter(item => item.id !== owner.id)) {
    const moved = assigned.value.filter(row => row.owner.id === source.id)
    if (!moved.length) continue
    actions.push({ type: 'TARGETS', id: source.id, targets: copy(source.editTargets).filter((_target: ReviewObject, index: number) => !moved.some(row => row.index === index)) })
    if (source.correction && !decisionLinks[source.id]) actions.push({ type: 'RULE', id: source.id, correction: { ...copy(source.correction), needsRebind: true } })
  }
  for (const old of oldDecisions.value.filter(item => decisionLinks[item.id])) {
    actions.push({ type: 'EDIT', id: old.id, values: { role: { kind: 'derived', zh: '由主问题答案决定', en: 'Derived from the primary answer' } } }, { type: 'RULE', id: old.id, correction: { kind: 'rule', branches: paths.value.map((path, index) => ({ id: 'review-answer-' + now + '-' + index, mode: 'all', clauses: [{ inputId: owner.id, operator: path.operator, value: path.value }], resultValue: path.key === decisionLinks[old.id], effectZh: path.nameZh, effectEn: path.nameEn })), ...(old.correction ? { supersededCorrection: copy(old.correction) } : {}), otherwise: 'needs_review', needsRebind: true } })
  }
  actions.push({ type: 'RULE', id: owner.id, correction: { ...(owner.correction?.kind === 'rule' ? copy(owner.correction) : {}), kind: 'rule', branches, otherwise: 'needs_review', needsRebind: true } }, { type: 'TARGETS', id: owner.id, targets })
  // Vue-wrapped arrays must become plain graph records before the reducer clones them.
  emit('branches', copy(actions), owner.id)
}
</script>

<template>
  <section class="batch-review" data-question-batch>
    <nav class="review-actions" :aria-label="t('处理方式','Review operation')"><button class="btn" data-batch-mode="branches" :aria-pressed="mode==='branches'" @click="mode='branches'">{{ t('同一问题的分支处理','Branches of one question') }}</button><button class="btn" data-batch-mode="duplicates" :aria-pressed="mode==='duplicates'" @click="mode='duplicates'">{{ t('重复问题合并','Merge duplicate questions') }}</button></nav>
    <p>{{ t('先对照勾选条目的用途、原文和修改目标，再确认处理。电子与纸质是一个问题的不同答案；来源核对项仍保留核对身份。','Compare the selected purposes, source passages and targets before confirming. Electronic and paper are alternative answers to one question; source checks retain their review purpose.') }}</p>
    <section class="batch-comparison" data-batch-comparison><article v-for="item in items" :key="item.id"><h4>{{ label(item) }}</h4><p>{{ item.type.zh || item.type.kind }} · {{ item.role.zh || item.role.kind }} · {{ item.editTargets.length }} {{ t('处修改目标','amendment targets') }}</p><article v-for="(anchor,index) in item.sourceAnchors" :key="index"><strong>{{ file(anchor.docId) }}</strong><blockquote>{{ anchor.text }}</blockquote></article><p v-if="!item.editTargets.length" class="source-note">{{ t('当前没有准确修改目标；关联原文不等于已建立分支动作。','There are currently no amendment targets. A source quotation does not establish branch actions.') }}</p><button class="btn" :data-batch-locate="item.id" @click="emit('locate',item.id)">{{ t('对照原件／建立准确目标','Review original / establish exact targets') }}</button></article></section>
    <label>{{ mode==='duplicates' ? t('合并后保留哪个问题？','Which question is retained?') : t('本组动作采用的主问题','Primary question for these actions') }}<select v-model="primaryId" data-batch-primary><option value="">{{ t('请选择','Choose') }}</option><option v-for="item in mode==='duplicates' ? items : primaryOptions" :key="item.id" :value="item.id">{{ label(item) }}</option></select></label>
    <template v-if="mode==='duplicates'"><p v-if="mergeReason" class="review-error" data-batch-merge-problem role="status">{{ mergeReason }}</p><p v-else class="source-note">{{ t('格式与来源检查允许合并；这仍需人工确认它们确实在问同一件事。保留所选问题名称，其余原文及引用转入，旧节点留在历史中。','Answer/source checks permit the merge. Confirm that these are the same question. The retained name stays; source evidence and references are transferred and old nodes remain in history.') }}</p><label><input v-model="acknowledged" type="checkbox" data-batch-ack>{{ t('已对照原文，确认是重复提问；不同答案的处理分支已分别核对。','I compared the sources and confirm these are duplicate questions; alternative answer branches were reviewed separately.') }}</label><button class="btn primary" data-batch-confirm-merge :disabled="!!mergeReason||!acknowledged" @click="emit('merge',ids,primaryId)">{{ t('确认后一次合并全部勾选项','Confirm and merge the selection once') }}</button></template>
    <template v-else>
      <p class="source-note">{{ t('每个答案各列作用范围和处理方法。仅搬移明确选择的目标；原问题的原文与历史保留，原问题不再重复执行这些目标。全部分支仍待核对，不会保存服务器或确认模板。纸质路线不代表所有材料都必须纸本。','Each answer has its own scope and treatment. Only selected targets move; original evidence/history stays and the previous question no longer executes those targets. All branches remain pending. This does not save or confirm the template. A paper route does not imply every material must be paper.') }}</p>
      <div class="batch-routes"><label v-for="path in paths" :key="path.key">{{ t('答案路线','Answer route') }} · {{ path.operator==='yes' ? t('为是','Yes') : path.operator==='no' ? t('为否','No') : path.value }}<input v-model="path.nameZh" :data-route-name-zh="path.key" :aria-label="t('路线中文名称','Chinese route name')"><input v-model="path.nameEn" :data-route-name-en="path.key" :aria-label="t('路线英文名称','English route name')"></label></div>
      <section v-if="oldDecisions.length" data-branch-old-inputs><h4>{{ t('哪些旧问题由这个答案决定？','Which old questions follow this answer?') }}</h4><p>{{ t('只有人工明确对应关系后才改为派生答案。未映射的旧问题仍会独立提问，当前一个主问题的收口仍未完成。','An old answer becomes derived only after an explicit mapping. Unmapped questions remain independent inputs; consolidation to one question is incomplete.') }}</p><article v-for="item in oldDecisions" :key="item.id"><label>{{ label(item) }}<select v-model="decisionLinks[item.id]" :data-branch-answer-map="item.id" :disabled="!!mappingProblem(item)"><option value="">{{ t('保持独立问题／待核对','Keep independent / review required') }}</option><option v-for="path in paths" :key="path.key" :value="path.key">{{ t('旧问题回答“是”对应：','The old answer “yes” means: ') }}{{ locale==='en'?path.nameEn:path.nameZh }}</option></select></label><p v-if="replaceableRoute(item)" class="source-note">{{ t('原规则含自身路由；明确映射后，新路线替代旧自身条件，其他前提保留，完整原规则保留在修订记录中。','The original rule contains its own routing predicate. After explicit mapping, the new route replaces that predicate, other prerequisites stay, and the complete original rule is retained in revision history.') }}</p><p v-if="mappingProblem(item)" class="source-note">{{ mappingProblem(item) }}</p></article></section>
      <article v-for="row in rows" :key="row.key" class="batch-target" :data-branch-target="row.key"><strong>{{ file(row.target.docId) }} · {{ label(row.owner) }}</strong><blockquote>{{ row.target.text }}</blockquote><p v-if="!targetSpans(row.target).length||row.target.nativeBinding&&row.target.nativeBinding.status!=='bound'||row.target.needsRebind" class="review-error">{{ t('准确范围或绑定仍待核对；此目标不能显示为已联动。','Exact range or binding still needs review; this target is not connected for execution.') }}</p><fieldset><legend>{{ t('同一原文在各答案路线分别怎样处理？','How is this passage treated on each answer route?') }}</legend><label v-for="path in paths" :key="path.key">{{ locale==='en'?path.nameEn:path.nameZh }}<select v-model="assignments[row.key].routes[path.key]" :data-branch-route="row.key+':'+path.key" :disabled="row.branch?.mode==='any'&&inherited(row).length>1||!!row.target.branchId&&!row.branch"><option value="">{{ t('此路线待核对（未记录动作）','Route pending (no action recorded)') }}</option><option v-for="key in ['keep','delete','fill','replace','insert','unused','check']" :key="key" :value="key">{{ actionLabels[key] }}</option></select></label></fieldset><p v-if="row.branch?.mode==='any'&&inherited(row).length>1||row.target.branchId&&!row.branch" class="review-error">{{ t('原条件未能完整映射（多个任一前提或分支缺失），需先在原问题核对；不能丢掉或改写此前提后搬移。','The original conditions cannot be mapped completely (multiple OR prerequisites or a missing branch). Review them on the original question; they cannot be dropped or rewritten during a move.') }}</p><p>{{ t('原有其他前提（保留）','Existing additional prerequisites (retained)') }}: {{ retainedPrerequisites(row).map(condition).join(row.branch?.mode==='any'?t(' 或 ',' OR '):t(' 且 ',' AND ')) || t('未记录，仍需人工核对','None recorded; human review remains required') }}</p><p v-if="row.branch?.scopeId">{{ t('原有明细范围（保留）','Existing list scope (retained)') }}: {{ label(state.variables.find(item=>item.id===row.branch.scopeId)) }} · {{ scopeLabel(row.branch.scopeMode) }}</p><div v-for="(clause,index) in assignments[row.key].extra" :key="index" class="review-actions"><select v-model="clause.inputId" :data-branch-prerequisite="row.key+':'+index" @change="clause.operator=state.variables.find(item=>item.id===clause.inputId)?.type.kind==='boolean'?'yes':'known'"><option value="">{{ t('选择其他前提，例如双信封','Choose another prerequisite, e.g. two-envelope tendering') }}</option><option v-for="input in inputs.filter(input=>input.id!==primaryId)" :key="input.id" :value="input.id">{{ label(input) }}</option></select><select v-model="clause.operator"><option value="known">{{ t('已填写','Provided') }}</option><template v-if="state.variables.find(item=>item.id===clause.inputId)?.type.kind==='boolean'"><option value="yes">{{ t('为是','Yes') }}</option><option value="no">{{ t('为否','No') }}</option></template></select><button class="btn" @click="assignments[row.key].extra.splice(index,1)">{{ t('移除此前提','Remove prerequisite') }}</button></div><button class="btn" :data-branch-add-prerequisite="row.key" @click="assignments[row.key].extra.push({inputId:'',operator:'known',value:''})">{{ t('增加这处的其他前提','Add a prerequisite for this passage') }}</button></article>
      <section data-branch-preview><h4>{{ t('按答案、文件对照处理方案','Compare treatments by answer and file') }}</h4><p class="source-note">{{ t('此处并排列出范围摘要和动作；完整原文在上方每处目标中核对。','Compare scope summaries and actions side by side; review each full passage above.') }}</p><div class="batch-route-preview"><article v-for="group in groups" :key="group.path.key"><h4>{{ locale==='en'?group.path.nameEn:group.path.nameZh }}</h4><p v-if="!group.files.length" class="source-note">{{ t('此路线尚无已选择的修改目标；不代表无需修改。','No amendment targets selected for this route; this does not establish that no changes are needed.') }}</p><section v-for="document in group.files" :key="document.docId"><h5>{{ file(document.docId) }}</h5><article v-for="row in document.rows" :key="row.key"><blockquote>{{ targetExcerpt(row.target.text) }}</blockquote><p>{{ t('动作','Action') }}: {{ actionLabels[assignments[row.key].routes[group.path.key]] }}</p><p>{{ t('其他前提','Additional prerequisites') }}: {{ mappedClauses([...retainedPrerequisites(row),...assignments[row.key].extra]).map(condition).join(t(' 且 ',' AND ')) || t('尚未记录，待核对','Not recorded; review required') }}</p><p class="source-note">{{ t('分支及目标待核对','Branch and target pending review') }}</p></article></section></article></div><p>{{ unassignedRoutes }} {{ t('处路线动作待核对','route actions remain unassigned') }}</p><p>{{ rows.length-assigned.length }} {{ t('处目标保持原问题／待归属','targets remain with the original question / unassigned') }}</p></section>
      <p v-if="branchProblem" class="review-error" data-branch-problem>{{ branchProblem }}</p><label><input v-model="branchAcknowledged" type="checkbox" data-branch-ack>{{ t('确认以上目标的搬移及路线；未知范围、额外前提仍保留待核。','Confirm the target moves and routes above; unknown ranges and prerequisites remain pending.') }}</label><button class="btn primary" data-branch-commit :disabled="!!branchProblem||!branchAcknowledged" @click="commitBranches">{{ t('一次记录分支与目标归属（待核对）','Record branches and target ownership once (pending review)') }}</button>
    </template>
  </section>
</template>

<style scoped>
.batch-review label{display:block;margin:12px 0}.batch-review select,.batch-review input:not([type=checkbox]){width:100%;box-sizing:border-box}.batch-comparison,.batch-routes,.batch-route-preview{display:grid;grid-template-columns:repeat(auto-fit,minmax(260px,1fr));gap:16px}.batch-comparison>article,.batch-target,.batch-route-preview>article{border:1px solid var(--line);padding:14px;border-radius:6px;margin:12px 0}.batch-review blockquote{white-space:pre-wrap;margin:10px 0;padding-left:12px;border-left:3px solid var(--accent);overflow-wrap:anywhere}.review-error{color:var(--danger)}.source-note{color:var(--muted)}
</style>
