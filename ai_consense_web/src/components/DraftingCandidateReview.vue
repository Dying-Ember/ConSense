<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { draftingApi } from '@/api'
import type { DraftCandidate, DraftCompositeBillSuggestion, DraftField, DraftReviewCandidates, DraftVariable } from '@/api'
import graphView from '@/drafting/source-review/graph-business-view.js'
import type { ReviewRevision } from '@/drafting/source-review-types'
import { candidateReviewDrafts } from '@/drafting/source-review-state'
import { localized } from '@/drafting/words'
import DraftingValueDisplay from './DraftingValueDisplay.vue'
import DraftingInputField from './DraftingInputField.vue'
import { decode, encode } from '@/drafting/state'
import { presentationValue } from '@/drafting/value-presentation'
const props = defineProps<{ projectId: string; revisionId: string; locale: 'zh-Hans' | 'zh-Hant' | 'en'; reloadKey: number; unassignedOnly?: boolean; allowAdoption?: boolean; review?: ReviewRevision }>()
const emit = defineEmits<{ locate: [sourceId: string, sourceSha256?: string | null]; question: [key: string, revisionId: string]; dirty: [value: boolean]; corrected: []; adoptComposite: [key: string, suggestion: DraftCompositeBillSuggestion] }>()
const t = (zh: string, en: string) => props.locale === 'en' ? en : zh
const result = ref<DraftReviewCandidates>(), busy = ref(false), error = ref(''), editing = ref('')
const reviewVariables = computed(() => [...(props.unassignedOnly ? [] : (result.value?.variables ?? []).filter(variable => variable.candidates?.length)), ...(result.value?.unassignedVariables ?? [])])
const sameReview = computed(() => props.review?.id === props.revisionId && !props.review.sourceStale ? props.review : undefined)
const impacts = computed(() => {
  const review = sameReview.value
  if (!review) return new Map<string, any[]>()
  const view = graphView.build(review.graph.graph, { sources: review.graph.sources, includeExcluded: false })
  for (const action of view.actions) {
    const groups = new Map<string, any>()
    for (const entry of action.targets) {
      const id = String(entry.target.docId ?? '')
      if (!groups.has(id)) groups.set(id, { id, entries: [], placeholders: 0, bound: 0, pending: 0 })
      const group = groups.get(id), target = entry.target
      if (/^[\s_*]+$/.test(target.text ?? '')) group.placeholders++
      else group.entries.push(entry)
      if (target.nativeBinding?.status === 'bound') group.bound++
      if (action.referenceOnly || action.needsRebind || action.unresolved || action.missing || action.incompatible || target.action === 'check' || target.needsRebind || target.nativeBinding?.status !== 'bound') group.pending++
    }
    action.sourceGroups = [...groups.values()]
  }
  return new Map<string, any[]>((review.graph.graph.variables ?? []).filter(node => node.status !== 'excluded').map(node => [node.id, view.actions.filter((action: any) => !action.excluded && (action.ownerId === node.id || action.inputIds.includes(node.id)))]))
})
const sourceName = (id: string) => sameReview.value?.graph.sources.find(source => String(source.id) === String(id))?.fileName ?? t('来源文件待核对', 'Source file needs review')
const candidateSearch = ref('')
const candidateRows = computed(() => reviewVariables.value.flatMap(variable => (variable.candidates ?? []).map((candidate, index) => ({ variable, candidate, index }))))
const compositeRows = computed(() => props.unassignedOnly ? [] : (result.value?.variables ?? []).filter(variable => variable.compositeBillSuggestion && variable.candidates?.length))
const visibleRows = computed(() => {
  const query = candidateSearch.value.trim().toLowerCase()
  return candidateRows.value.filter(({ variable, candidate }) => !query || [localized(variable.label, props.locale), candidate.fileName, candidate.sourceQuote].some(value => value?.toLowerCase().includes(query)))
})
const candidateFields = computed(() => new Map((result.value?.catalog?.groups ?? []).flatMap(group => group.fields).map(field => [field.key, field])))
const fieldDefinition = (variable: DraftVariable, candidate: DraftCandidate) => candidate.fieldDefinitionHash && result.value?.candidateDefinitions?.[candidate.fieldDefinitionHash] || (!unassigned(variable.key) ? candidateFields.value.get(variable.key) : undefined)
const candidateField = (variable: DraftVariable, candidate: DraftCandidate): DraftField => fieldDefinition(variable, candidate) ?? { key: variable.key, label: variable.label, kind: variable.kind === 'record' ? 'object' : variable.kind || 'text' }
const unassigned = (key: string) => !!result.value?.unassignedVariables?.some(variable => variable.key === key)
const form = reactive({ key: '', candidateIndex: -1, value: '', sourceQuote: '', targetKey: '', expectedRunId: '', columns: {} as Record<string, string> })
const targetSearch = ref('')
const targetOptions = computed(() => (result.value?.variables ?? []).filter(variable => variable.key === form.targetKey || !targetSearch.value.trim() || localized(variable.label, props.locale).toLowerCase().includes(targetSearch.value.trim().toLowerCase())))
const editingField = computed(() => { const variable = reviewVariables.value.find(item => item.key === form.key), candidate = variable?.candidates?.[form.candidateIndex]; return variable && candidate ? fieldDefinition(variable, candidate) : undefined })
const targetField = computed(() => candidateFields.value.get(form.targetKey))
const needsColumns = computed(() => !!editingField.value?.columnFields?.length && !!targetField.value?.columnFields?.length && editingField.value.columnFields.some(column => !targetField.value!.columnFields!.some(target => target.key === column.key)))
const mappedValue = computed(() => {
  if (!needsColumns.value) return form.value
  const source = editingField.value!, target = targetField.value!, columns = source.columnFields!, destinations = target.columnFields!
  if (source.kind !== target.kind) return undefined
  const keys = columns.map(column => form.columns[column.key])
  if (keys.some((key, index) => !destinations.some(column => column.key === key && column.kind === columns[index]!.kind)) || new Set(keys).size !== keys.length) return undefined
  try {
    const value = JSON.parse(form.value)
    const rows = Array.isArray(value) ? value : [value]
    const mapped = rows.map(row => {
      if (!row || typeof row !== 'object' || Array.isArray(row)) throw Error('Invalid row')
      const output: Record<string, unknown> = {}
      for (const [key, cell] of Object.entries(row)) {
        if (key === '_itemId') output[key] = cell
        else { const destination = form.columns[key]; if (!columns.some(column => column.key === key) || !destination || !keys.includes(destination)) throw Error('Unmapped column'); output[destination] = cell }
      }
      return output
    })
    return JSON.stringify(Array.isArray(value) ? mapped : mapped[0])
  } catch { return undefined }
})
const formBase = ref('')
const dirty = computed(() => !!editing.value && JSON.stringify(form) !== formBase.value)
let generation = 0, alive = true
function edit(variable: DraftVariable, index: number) {
  if (busy.value) return
  if (dirty.value) { error.value = t('请先保存或取消候选调整。', 'Save or cancel the candidate edit first.'); return }
  const candidate = variable.candidates?.[index]; if (!candidate) return
  targetSearch.value = ''
  Object.assign(form, { key: variable.key, candidateIndex: index, value: candidate.value, sourceQuote: candidate.sourceQuote ?? '', targetKey: unassigned(variable.key) ? '' : variable.key, expectedRunId: result.value?.runId ?? '', columns: {} })
  formBase.value = JSON.stringify(form); editing.value = variable.key + ':' + index
}
function snapshot(project = props.projectId, revision = props.revisionId) {
  const key = project + ':' + revision
  if (dirty.value) candidateReviewDrafts.set(key, { form: JSON.parse(JSON.stringify(form)), formBase: formBase.value, editing: editing.value })
  else candidateReviewDrafts.delete(key)
}
async function refresh() {
  const project = props.projectId, revision = props.revisionId, token = ++generation
  if (!project || !revision) return
  const cached = candidateReviewDrafts.get(project + ':' + revision)
  if (cached) { Object.assign(form, cached.form); formBase.value = cached.formBase; editing.value = cached.editing }
  busy.value = true; error.value = ''
  try { const incoming = await draftingApi.reviewCandidates(project, revision); if (alive && token === generation && project === props.projectId && revision === props.revisionId) { result.value = incoming; if (!cached) editing.value = '' } }
  catch (caught) { if (token === generation) error.value = caught instanceof Error ? caught.message : String(caught) }
  finally { if (token === generation) busy.value = false }
}
async function save() {
  if (!form.expectedRunId || !form.targetKey || result.value?.stale || busy.value || mappedValue.value === undefined) return
  const project = props.projectId, revision = props.revisionId, token = generation; busy.value = true; error.value = ''
  try { const incoming = await draftingApi.reviewCorrectCandidate(project, revision, form.key, { expectedRunId: form.expectedRunId, candidateIndex: form.candidateIndex, value: mappedValue.value, sourceQuote: form.sourceQuote, targetKey: form.targetKey }); if (alive && token === generation && project === props.projectId && revision === props.revisionId) { result.value = incoming; editing.value = ''; candidateReviewDrafts.delete(project + ':' + revision); emit('dirty', false); emit('corrected') } }
  catch (caught) { if (token === generation) error.value = caught instanceof Error ? caught.message : String(caught) }
  finally { if (token === generation) busy.value = false }
}
watch(() => [props.projectId, props.revisionId, props.reloadKey] as const, (_next, previous) => { if (previous) snapshot(previous[0], previous[1]); result.value = undefined; editing.value = ''; candidateSearch.value = ''; void refresh() }, { immediate: true })
watch([dirty, busy], () => emit('dirty', dirty.value || busy.value))
onBeforeUnmount(() => { snapshot(); alive = false; generation++ })
</script>

<template>
  <section v-if="!unassignedOnly || reviewVariables.length || busy || error" class="candidate-review" data-correspondence-review><fieldset data-candidate-controls :disabled="busy">
    <h3>{{ t('沟通材料：核对候选值', 'Correspondence: review candidate values') }}</h3>
    <p class="hint">{{ t('原文证据、候选值与变量归属可在此校正；保存调整不会采用项目事实。可在本步骤逐项明确采用；未核对的规则仍不执行。', 'Correct source quotations, candidate values and variable assignments here. Saving a correction does not adopt a project fact. Explicitly adopt each value in this step; unreviewed rules still do not execute.') }}</p>
    <p v-if="busy" role="status">{{ t('正在读取或保存候选…', 'Reading or saving candidates…') }}</p><p v-if="error" role="alert" class="candidate-error">{{ error }}</p>
    <p v-if="result?.stale" role="alert" class="candidate-error">{{ t('沟通原件已变化；保留的候选需重新识别，不能校正或采用为当前证据。', 'Correspondence sources changed. Re-extract before correcting or adopting these retained candidates.') }}</p><p v-if="!busy && !error && result && !reviewVariables.some(variable => variable.candidates?.length)" class="hint">{{ t('此模板草稿尚无沟通候选；确认图谱后可以人工填写变量值。', 'No correspondence candidates for this draft. You can enter variable values manually after confirming the graph.') }}</p>
    <section v-for="variable in compositeRows" :key="variable.key" class="candidate-impact" data-composite-bill>
      <h4>{{ localized(variable.label, locale) }} · {{ t('跨通信合并候选', 'Combined correspondence candidate') }}</h4>
      <p>{{ t('合并结果尚未采用；各份原候选及其来源保留在下方。', 'This combined result is not adopted. Each original candidate and source is retained below.') }}</p>
      <p v-if="variable.compositeBillSuggestion?.partial" class="candidate-error">{{ t('本次提取未全部完成，合并结果的完整性仍需人工核对。', 'Extraction was incomplete; review whether the combined list is complete.') }}</p>
      <DraftingValueDisplay v-if="candidateFields.get(variable.key)" :field="candidateFields.get(variable.key)!" :value="variable.compositeBillSuggestion!.value" :locale="locale" />
      <details><summary>{{ t('逐份核对合并来源', 'Review each contributing source') }} · {{ variable.compositeBillSuggestion!.memberIndexes.length }}</summary>
        <article v-for="index in variable.compositeBillSuggestion!.memberIndexes" :key="index" data-composite-bill-source>
          <strong>{{ variable.candidates![index]?.fileName }}</strong><DraftingValueDisplay v-if="variable.candidates![index]" :field="candidateField(variable, variable.candidates![index]!)" :value="variable.candidates![index]!.value" :locale="locale" /><blockquote>{{ variable.candidates![index]?.sourceQuote }}</blockquote>
          <button class="btn" :disabled="busy || dirty || variable.candidates![index]?.sourceDocumentId == null" @click="emit('locate', String(variable.candidates![index]!.sourceDocumentId), variable.candidates![index]!.originalSha256)">{{ t('查看本份沟通原件', 'Locate this source document') }}</button>
        </article>
      </details>
      <button class="btn primary" data-adopt-composite-bill :disabled="!allowAdoption || busy || dirty || !!result?.stale" @click="emit('adoptComposite', variable.key, variable.compositeBillSuggestion!)">{{ t('采用这份合并候选', 'Adopt this combined candidate') }}</button>
      <p v-if="!allowAdoption" class="hint">{{ t('请先保存图谱和当前修改，再在对应版本采用。', 'Save the graph and edits before adopting on its working revision.') }}</p>
    </section>
    <label v-if="candidateRows.length">{{ t('搜索变量、文件名或原文', 'Search variables, filenames or source quotations') }}<input v-model="candidateSearch" type="search" data-candidate-search :disabled="dirty" /></label>
    <p v-if="candidateRows.length" role="status" data-candidate-count>{{ t('显示候选', 'Showing candidates') }} {{ visibleRows.length }} / {{ candidateRows.length }}</p>
    <p v-if="candidateRows.length && !visibleRows.length">{{ t('没有匹配的候选，请更换关键词或清空搜索。', 'No matching candidates. Try another keyword or clear the search.') }}</p>
    <article v-for="{variable,candidate,index} in visibleRows" :key="variable.key+':'+index" class="candidate-row">
      <h4>{{ localized(variable.label, locale) }}</h4><p v-if="unassigned(variable.key)" class="hint" data-unassigned-candidate>{{ t('原变量已合并、退出或定义已调整；候选和原文已保留，请核对取值与现在的变量是否对应。', 'The original variable was merged or retired, or its definition changed. The candidate and source are retained; check the value against the current variable before assigning it.') }}</p><p v-if="candidate.completenessStatus === 'needs_review'" class="candidate-error" data-candidate-incomplete>{{ t('此片段的完整范围尚未核实，不能直接采用；请对照原件补齐后在变量中人工填写并保存。', 'The complete source scope is not verified. Complete it against the source and enter the value manually before saving.') }}</p><strong>{{ candidate.fileName }}</strong><DraftingValueDisplay :field="candidateField(variable, candidate)" :value="candidate.value" :locale="locale" /><blockquote>{{ candidate.sourceQuote }}</blockquote><p class="hint">{{ candidate.reason }}</p>
      <section v-if="!unassigned(variable.key) && review" class="candidate-impact" data-candidate-impact>
        <h5>{{ t('此候选值关联的修改影响（尚未执行）', 'Linked amendment impact (not executed)') }}</h5>
        <p v-if="!sameReview">{{ t('当前变量版本或来源已变化，暂不展示修改影响；请返回第二步核对。', 'The variable revision or source changed. Review step 2 before inspecting amendment impact.') }}</p>
        <p v-else-if="!impacts.get(variable.key)?.length">{{ t('当前图谱没有为此变量建立直接修改方案。它可能只用于提供变量值；是否缺少关联仍需核对，不能据此认为已可自动修改文件。', 'No direct amendment action is linked to this variable. It may supply information; review whether links are missing before relying on automatic edits.') }}</p>
        <article v-for="action in sameReview ? impacts.get(variable.key) ?? [] : []" :key="action.id">
          <strong>{{ action.kind === 'all-cases' ? t('尚未设置适用条件的修改记录', 'Amendment record without an applicability condition') : (locale === 'en' ? action.summaryEn || action.labelEn : action.summaryZh || action.labelZh) }}</strong>
          <p>{{ t('以下仅为图谱中记录的影响，条件、变量值及原文范围仍需核对；候选不会自动填入。', 'This is the impact recorded in the graph. Conditions, variable values and source ranges still need review; candidates are not inserted automatically.') }}</p>
          <p v-if="!action.targets.length">{{ t('尚未关联准确修改位置。', 'No exact amendment range is linked yet.') }}</p>
          <section v-for="group in action.sourceGroups" :key="group.id" data-candidate-impact-source>
            <strong>{{ sourceName(group.id) }}</strong>
            <p v-if="group.placeholders">{{ t('填空目标', 'Placeholder targets') }}：{{ group.placeholders }} · {{ t('请查看模板原文核对语境。', 'Open the template source to review the context.') }}</p>
            <blockquote v-for="entry in group.entries" :key="entry.index">{{ entry.target.text }}</blockquote>
            <p>{{ t('原文位置已绑定', 'Source ranges bound') }}：{{ group.bound }} · {{ t('修改仍待核对', 'Amendments awaiting review') }}：{{ group.pending }}</p>
            <p v-if="group.pending">{{ t('待核对的修改暂不执行；位置绑定不代表已经采用变量值。', 'Amendments awaiting review will not execute; a bound range does not mean a value was adopted.') }}</p>
          </section>
        </article>
      </section>
      <div class="candidate-actions"><button class="btn" :disabled="candidate.sourceDocumentId == null || busy || dirty" @click="emit('locate', String(candidate.sourceDocumentId), candidate.originalSha256)">{{ t('查看本份沟通原件', 'Locate this source document') }}</button><button class="btn" :disabled="busy || dirty" @click="edit(variable,index)">{{ t('校正候选值／变量归属', 'Correct candidate or assignment') }}</button><button v-if="!unassigned(variable.key)" class="btn" data-candidate-question :disabled="busy || dirty" @click="emit('question', variable.key, revisionId)">{{ t('查看模板变量与修改范围', 'View template variable and edit scope') }}</button></div>
      <form v-if="editing === variable.key+':'+index" @submit.prevent="save"><DraftingInputField v-if="fieldDefinition(variable, candidate) && !presentationValue(candidateField(variable, candidate), form.value).invalid" :field="candidateField(variable, candidate)" :value="decode(candidateField(variable, candidate), form.value)" :values="{}" :locale="locale" :disabled="busy" id-prefix="candidate" row-identity-key="_itemId" multiline-text @update="form.value = encode(candidateField(variable, candidate), $event)" /><label v-else>{{ t('候选原值（缺少字段定义或格式待修复，保留原内容）', 'Original candidate (field definition unavailable or format needs repair)') }}<textarea v-model="form.value" data-candidate-value /></label><label>{{ t('同一原件内的准确引文', 'Exact quotation from this source') }}<textarea v-model="form.sourceQuote" data-candidate-quote /></label><label>{{ t('搜索变量名称', 'Search variable names') }}<input v-model="targetSearch" type="search" data-candidate-target-search /></label><p v-if="targetSearch.trim() && !targetOptions.length" role="status">{{ t('没有匹配的变量，请更换关键词。', 'No matching variables. Try another search.') }}</p><label>{{ t('归属于哪个变量？', 'Which variable does this value belong to?') }}<select v-model="form.targetKey" data-candidate-target @change="form.columns = {}"><option value="">{{ t('请选择当前变量', 'Choose a current variable') }}</option><option v-for="target in targetOptions" :key="target.key" :value="target.key">{{ localized(target.label,locale) }}</option></select></label><section v-if="needsColumns" data-candidate-column-mapping><h4>{{ t('确认旧资料列对应哪个新字段', 'Match the original columns to current fields') }}</h4><p>{{ t('只调整字段归属，保留所有行和值。每列必须明确对应一个不同字段；不会按名称或顺序自动猜测。', 'Keep every row and value; change only field assignments. Match each column to a different field explicitly.') }}</p><label v-for="column in editingField?.columnFields" :key="column.key">{{ localized(column.label, locale) }}<select v-model="form.columns[column.key]" :data-candidate-column="column.key"><option value="">{{ t('请选择对应字段', 'Choose the matching field') }}</option><option v-for="target in targetField?.columnFields?.filter(field => field.kind === column.kind)" :key="target.key" :value="target.key">{{ localized(target.label, locale) }}</option></select></label><p v-if="mappedValue === undefined" role="status">{{ t('请完整对应所有列且不要重复；未识别的附加字段也必须保留，不能直接丢弃。', 'Match every column without duplicates. Unrecognized extra fields cannot be discarded.') }}</p><DraftingValueDisplay v-else-if="targetField" :field="targetField" :value="mappedValue" :locale="locale" /></section><button type="button" class="btn" :disabled="busy" @click="editing = ''">{{ t('取消调整', 'Cancel correction') }}</button><button type="submit" class="btn primary" :disabled="busy || !form.targetKey || result?.stale || mappedValue === undefined">{{ t('保存候选调整，仍不采用', 'Save correction without adoption') }}</button></form>
    </article>
  </fieldset></section>
</template>

<style scoped>
fieldset{border:0;margin:0;padding:0;min-width:0}.candidate-review{margin-top:20px;border:1px solid var(--line);border-radius:var(--radius);background:var(--surface);padding:20px}.candidate-row{border-top:1px solid var(--line);padding:15px 0}.candidate-impact{margin:12px 0;padding:12px;background:var(--surface-alt,var(--surface));border-left:2px solid var(--line)}.candidate-impact h5{margin:0 0 8px;font-size:14px}.candidate-impact p{font-size:13px}.candidate-actions{display:flex;gap:8px;flex-wrap:wrap}pre,blockquote{white-space:pre-wrap;overflow-wrap:anywhere;font-size:12px;line-height:1.7}blockquote{border-left:2px solid var(--accent);padding-left:12px}.hint{font-size:12px;color:var(--muted)}label{display:block;margin:12px 0;font-size:12px}textarea,select{display:block;width:100%;margin-top:6px;padding:8px;background:var(--surface);color:var(--ink);border:1px solid var(--line);border-radius:5px}textarea{min-height:75px}.candidate-error{color:var(--red)}
</style>
