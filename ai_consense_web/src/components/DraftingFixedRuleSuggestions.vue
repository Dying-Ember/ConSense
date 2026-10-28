<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { FixedRuleSuggestion, ReviewRevision } from '@/drafting/source-review-types'
const props = defineProps<{ revision: ReviewRevision; locale: string; disabled?: boolean }>()
const emit = defineEmits<{ question: [id: string, revisionId: string]; adopt: [id: string, applicability: Record<string, unknown>] }>()
const t = (zh: string, en: string) => props.locale === 'en' ? en : zh
const rules = computed(() => props.revision.fixedRuleSuggestions ?? [])
const page = ref(0)
const reasons = ref<Record<string,string>>({}), confirmed = ref<Record<string,boolean>>({})
watch(() => props.revision.id, () => { page.value = 0; reasons.value = {}; confirmed.value = {} })
const shown = computed(() => rules.value.slice(page.value * 5, page.value * 5 + 5))
function node(id: string) { return props.revision.graph.graph.variables.find(item => item.id === id) }
function label(id: string) { const item = node(id); return item ? (props.locale === 'en' ? item.nameEn || item.nameZh : item.nameZh || item.nameEn) : t('变量已不存在', 'Variable no longer exists') }
function canOpen(id: string) { return !props.disabled && !props.revision.sourceStale && !!node(id) && node(id)?.status !== 'excluded' }
function value(value: unknown) { return JSON.stringify(value) ?? '—' }
function coverageLabel(rule: FixedRuleSuggestion) {
  if (props.revision.sourceStale) return t('原件已变化，请重新核对。', 'Source changed; review again.')
  if (rule.kind !== 'field_condition') return t('当前修改范围：未绑定。', 'Current amendment range: unbound.')
  const labels = {
    covered: t('已表示', 'Represented'),
    missing: t('缺少', 'Missing'),
    stale: t('需重核', 'Review again'),
    ambiguous: t('无法唯一判断', 'Cannot determine uniquely'),
  }
  const status = rule.reviewCoverage?.status
  return `${t('当前条件覆盖', 'Current condition coverage')}: ${(status && labels[status]) || t('待核对', 'Awaiting review')}`
}
function confirmedMapping(id: string, key: string, version: string) {
  const item=node(id), alignment=item?.catalogAlignment
  return canOpen(id) && alignment?.status==='matched' && alignment?.confirmed===true && alignment.baselineKey===key && alignment.baselineVersion===version
}
function mappedClauses(rule: FixedRuleSuggestion) {
  if (rule.kind!=='field_condition' || !rule.bindingFingerprint || !rule.targetBaselineKey || rule.targetNodeIds?.length!==1 || !rule.conditions?.length || rule.inputs.some(input => input.nodeIds.length!==1)) return null
  const target=rule.targetNodeIds[0]!
  if (!confirmedMapping(target,rule.targetBaselineKey,rule.ruleVersion) || node(target)?.applicability) return null
  const clauses=[]
  for (const condition of rule.conditions) {
    const input=rule.inputs.find(item => item.baselineKey===condition.field)
    const id=input?.nodeIds[0]
    if (!id || id===target || !confirmedMapping(id,condition.field,rule.ruleVersion)) return null
    clauses.push({baselineKey:condition.field,inputId:id,operator:condition.operator,value:condition.value})
  }
  return {target,clauses}
}
function adopt(rule: FixedRuleSuggestion) {
  const mapping=mappedClauses(rule), reason=reasons.value[rule.actionId]?.trim()
  if (!mapping || !reason || !confirmed.value[rule.actionId]) return
  emit('adopt',mapping.target,{kind:'fixed_catalog',ruleVersion:rule.ruleVersion,targetBaselineKey:rule.targetBaselineKey,bindingFingerprint:rule.bindingFingerprint,mode:'all',confirmed:true,reason,clauses:mapping.clauses})
  reasons.value[rule.actionId]=''; confirmed.value[rule.actionId]=false
}
</script>

<template>
  <details v-if="rules.length" class="source-note" data-fixed-rule-suggestions>
    <summary>{{ t('固定规则参考（待核对）', 'Fixed rule references (review required)') }} · {{ rules.length }}</summary>
    <p>{{ t('以下来自固定目录的条件或部分历史规则示例，历史条号仅供参考；尚未绑定当前模板修改范围，不会自动执行。请对照本版原件并在变量核对中人工设置条件和范围。', 'These are fixed-catalog conditions or selected historical rule examples. Historical clauses are references only; no current-template amendment range is bound and nothing is applied automatically. Review this edition and set conditions and ranges manually.') }}</p>
    <article v-for="(rule,index) in shown" :key="`${page}-${index}`">
      <strong>{{ rule.kind === 'field_condition' ? t('固定字段条件', 'Fixed field condition') : t('历史动作示例', 'Historical action example') }}</strong>
      <p data-fixed-rule-coverage>{{ coverageLabel(rule) }}</p>
      <p>{{ rule.document }} · {{ t('历史条号', 'Historical clause') }}: {{ rule.historicalClause || '—' }} · {{ rule.ruleVersion }} · {{ t('待人工核对', 'Awaiting review') }}</p>
      <p v-if="rule.coverage === 'partial_examples'">{{ t('仅为部分示例，并非完整规则图谱。', 'Selected examples only; not the complete rule graph.') }}</p>
      <p>{{ locale === 'en' ? rule.detail?.en || rule.detail?.zhHans : rule.detail?.zhHans || rule.detail?.en }}</p>
      <p v-for="(condition,ci) in rule.conditions ?? []" :key="ci">{{ t('条件', 'Condition') }}: {{ condition.field }} · {{ condition.operator }} · {{ value(condition.value) }}</p>
      <p v-if="rule.targetBaselineKey">{{ t('受条件约束的固定字段', 'Fixed field governed by the condition') }}: {{ rule.targetBaselineKey }}</p>
      <div v-if="rule.targetBaselineKey"><span v-if="!rule.targetNodeIds?.length">{{ t('当前未对应变量', 'No current variable mapped') }}</span><button v-for="id in rule.targetNodeIds ?? []" :key="id" type="button" class="btn" :disabled="!canOpen(id)" @click="emit('question',id,revision.id)">{{ t('核对', 'Review') }} {{ label(id) }}</button></div>
      <div v-for="input in rule.inputs" :key="input.baselineKey"><p>{{ t('前置固定变量', 'Prerequisite fixed variable') }}: {{ input.baselineKey }}</p><span v-if="!input.nodeIds.length">{{ t('当前未对应变量', 'No current variable mapped') }}</span><button v-for="id in input.nodeIds" :key="id" type="button" class="btn" :disabled="!canOpen(id)" @click="emit('question',id,revision.id)">{{ t('核对', 'Review') }} {{ label(id) }}</button></div>
      <details v-if="rule.kind === 'field_condition' && mappedClauses(rule)" data-fixed-applicability><summary>{{ t('人工确认此变量的适用条件', 'Confirm this variable’s applicability') }}</summary><p>{{ t('仅决定是否需要该变量的项目答案；不改变原文修改动作或范围。前置答案未知时仍需人工核对，已有答案会保留。', 'This controls whether the project answer is needed; it does not change amendment actions or ranges. Unknown prerequisites remain for review, and existing answers are retained.') }}</p><label>{{ t('核对理由', 'Review reason') }}<textarea v-model="reasons[rule.actionId]" data-fixed-applicability-reason rows="2" /></label><label><input v-model="confirmed[rule.actionId]" data-fixed-applicability-confirmed type="checkbox">{{ t('我已对照本版模板，确认这些固定条件适用于该变量。', 'I checked this edition and confirm these fixed conditions apply to this variable.') }}</label><button type="button" class="btn" data-fixed-applicability-adopt :disabled="!reasons[rule.actionId]?.trim() || !confirmed[rule.actionId] || !mappedClauses(rule)" @click="adopt(rule)">{{ t('确认适用条件，待保存', 'Confirm applicability and save later') }}</button></details>
      <p v-else-if="rule.kind === 'field_condition'">{{ t('只有目标及每个前置条件均唯一对应已确认变量后，才能确认适用条件；已有条件请在变量中查看或撤销。', 'Confirm applicability only when the target and each prerequisite uniquely map to confirmed variables. Review or remove an existing condition on the variable.') }}</p>
    </article>
    <nav v-if="rules.length > 5"><button type="button" class="btn" :disabled="page === 0" @click="page--">{{ t('上一组', 'Previous') }}</button> {{ page + 1 }} / {{ Math.ceil(rules.length / 5) }} <button type="button" class="btn" :disabled="(page + 1) * 5 >= rules.length" @click="page++">{{ t('下一组', 'Next') }}</button></nav>
    <p v-if="revision.sourceStale || disabled">{{ t('请先核对来源并保存或取消未保存调整。', 'Review sources and save or discard unsaved edits first.') }}</p>
  </details>
</template>
