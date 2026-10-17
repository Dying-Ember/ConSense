<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { draftingApi } from '@/api'
import type { DraftReviewCandidates, DraftVariable } from '@/api'
import { candidateReviewDrafts } from '@/drafting/source-review-state'
import { localized } from '@/drafting/words'
const props = defineProps<{ projectId: string; revisionId: string; locale: 'zh-Hans' | 'zh-Hant' | 'en'; reloadKey: number }>()
const emit = defineEmits<{ locate: [sourceId: string, sourceSha256?: string | null]; dirty: [value: boolean] }>()
const t = (zh: string, en: string) => props.locale === 'en' ? en : zh
const result = ref<DraftReviewCandidates>(), busy = ref(false), error = ref(''), editing = ref('')
const form = reactive({ key: '', candidateIndex: -1, value: '', sourceQuote: '', targetKey: '', expectedRunId: '' })
const formBase = ref('')
const dirty = computed(() => !!editing.value && JSON.stringify(form) !== formBase.value)
let generation = 0, alive = true
function edit(variable: DraftVariable, index: number) {
  if (busy.value) return
  if (dirty.value) { error.value = t('请先保存或取消候选调整。', 'Save or cancel the candidate edit first.'); return }
  const candidate = variable.candidates?.[index]; if (!candidate) return
  Object.assign(form, { key: variable.key, candidateIndex: index, value: candidate.value, sourceQuote: candidate.sourceQuote ?? '', targetKey: variable.key, expectedRunId: result.value?.runId ?? '' })
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
  if (!form.expectedRunId || result.value?.stale || busy.value) return
  const project = props.projectId, revision = props.revisionId, token = generation; busy.value = true; error.value = ''
  try { const incoming = await draftingApi.reviewCorrectCandidate(project, revision, form.key, { expectedRunId: form.expectedRunId, candidateIndex: form.candidateIndex, value: form.value, sourceQuote: form.sourceQuote, targetKey: form.targetKey }); if (alive && token === generation && project === props.projectId && revision === props.revisionId) { result.value = incoming; editing.value = ''; candidateReviewDrafts.delete(project + ':' + revision); emit('dirty', false) } }
  catch (caught) { if (token === generation) error.value = caught instanceof Error ? caught.message : String(caught) }
  finally { if (token === generation) busy.value = false }
}
watch(() => [props.projectId, props.revisionId, props.reloadKey] as const, (_next, previous) => { if (previous) snapshot(previous[0], previous[1]); result.value = undefined; editing.value = ''; void refresh() }, { immediate: true })
watch(dirty, value => emit('dirty', value))
onBeforeUnmount(() => { snapshot(); alive = false; generation++ })
</script>

<template>
  <section class="candidate-review" data-correspondence-review><fieldset data-candidate-controls :disabled="busy">
    <h3>{{ t('沟通材料：核对候选答案', 'Correspondence: review candidate answers') }}</h3>
    <p class="hint">{{ t('原文证据、候选值与问题归属可在此校正；保存调整不会采用项目事实。确认模板后，再到下一步逐项采用。', 'Correct source quotations, candidate values and question assignments here. Saving a correction does not adopt a project fact. Confirm the template, then adopt each answer in the next step.') }}</p>
    <p v-if="busy" role="status">{{ t('正在读取或保存候选…', 'Reading or saving candidates…') }}</p><p v-if="error" role="alert" class="candidate-error">{{ error }}</p>
    <p v-if="result?.stale" role="alert" class="candidate-error">{{ t('沟通原件已变化；保留的候选需重新识别，不能校正或采用为当前证据。', 'Correspondence sources changed. Re-extract before correcting or adopting these retained candidates.') }}</p><p v-if="!result?.variables.some(variable => variable.candidates?.length)" class="hint">{{ t('此模板草稿尚无沟通候选。可到上一步明确启动识别。', 'No correspondence candidates for this review draft. Start extraction in the previous step.') }}</p>
    <template v-for="variable in result?.variables ?? []" :key="variable.key"><article v-for="(candidate,index) in variable.candidates ?? []" :key="variable.key+':'+index" class="candidate-row">
      <h4>{{ localized(variable.label, locale) }}</h4><strong>{{ candidate.fileName }}</strong><pre>{{ candidate.value }}</pre><blockquote>{{ candidate.sourceQuote }}</blockquote><p class="hint">{{ candidate.reason }}</p>
      <div class="candidate-actions"><button class="btn" :disabled="candidate.sourceDocumentId == null || busy || dirty" @click="emit('locate', String(candidate.sourceDocumentId), candidate.originalSha256)">{{ t('查看本份沟通原件', 'Locate this source document') }}</button><button class="btn" :disabled="busy || dirty" @click="edit(variable,index)">{{ t('校正候选／问题归属', 'Correct candidate or assignment') }}</button></div>
      <form v-if="editing === variable.key+':'+index" @submit.prevent="save"><label>{{ t('候选值（清单或资料组使用完整JSON）', 'Candidate value (complete JSON for lists or records)') }}<textarea v-model="form.value" data-candidate-value /></label><label>{{ t('同一原件内的准确引文', 'Exact quotation from this source') }}<textarea v-model="form.sourceQuote" data-candidate-quote /></label><label>{{ t('归属于哪个问题？', 'Which question does this answer?') }}<select v-model="form.targetKey" data-candidate-target><option v-for="target in result?.variables ?? []" :key="target.key" :value="target.key">{{ localized(target.label,locale) }}</option></select></label><button type="button" class="btn" :disabled="busy" @click="editing = ''">{{ t('取消调整', 'Cancel correction') }}</button><button type="submit" class="btn primary" :disabled="busy || !dirty || result?.stale">{{ t('保存候选调整，仍不采用', 'Save correction without adoption') }}</button></form>
    </article></template>
  </fieldset></section>
</template>

<style scoped>
fieldset{border:0;margin:0;padding:0;min-width:0}.candidate-review{margin-top:20px;border:1px solid var(--line);border-radius:var(--radius);background:var(--surface);padding:20px}.candidate-row{border-top:1px solid var(--line);padding:15px 0}.candidate-actions{display:flex;gap:8px;flex-wrap:wrap}pre,blockquote{white-space:pre-wrap;overflow-wrap:anywhere;font-size:12px;line-height:1.7}blockquote{border-left:2px solid var(--accent);padding-left:12px}.hint{font-size:12px;color:var(--muted)}label{display:block;margin:12px 0;font-size:12px}textarea,select{display:block;width:100%;margin-top:6px;padding:8px;background:var(--surface);color:var(--ink);border:1px solid var(--line);border-radius:5px}textarea{min-height:75px}.candidate-error{color:var(--red)}
</style>
