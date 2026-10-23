<script setup lang="ts">
import { computed } from 'vue'
import type { DraftCatalog } from '@/api/types'
import type { ReviewObject, ReviewState, ReviewVariable } from '@/drafting/source-review-types'
import { baselineVariables, businessVariable, catalogLabel, compatibleBaseline, suggestedBaselines, variableKind } from '@/drafting/variable-catalog'
import DraftingQuestionPicker from './DraftingQuestionPicker.vue'
const props = defineProps<{ catalog: DraftCatalog; state: ReviewState; draft: ReviewObject; locale: string; disabled?: boolean }>()
const emit = defineEmits<{ align: [id: string, alignment: ReviewObject]; add: [key: string, reason: string]; exclude: [key: string, reason: string | null]; inspect: [id: string] }>()
const t = (zh: string, en: string) => props.locale === 'en' ? en : zh
const typeLabel = (kind: string) => ({ boolean:t('是／否','Yes / no'),number:t('数字','Number'),text:t('文字','Text'),date:t('日期','Date'),choice:t('单选','Single choice'),multiselect:t('多选','Multiple choices'),record:t('资料组','Record'),list:t('明细变量','Repeated details') }[variableKind(kind)] ?? t('需核对类型','Review value type'))
const alignmentLabel = (status: string) => ({ matched:t('已对应基准','Baseline matched'),new:t('新变量','New variable'),ambiguous:t('歧义待核','Ambiguous'),unreviewed:t('待人工审核','Awaiting review') }[status] ?? t('待人工审核','Awaiting review'))
const fields = computed(() => baselineVariables(props.catalog))
const nodes = computed(() => props.state.variables.filter(businessVariable))
const classify = (node: ReviewVariable) => node.catalogAlignment?.status === 'ambiguous' ? 'ambiguous' : node.catalogAlignment?.status === 'new' ? 'new' : node.catalogAlignment?.status === 'matched' || suggestedBaselines(node, fields.value, props.state).length === 1 ? 'matched' : suggestedBaselines(node, fields.value, props.state).length > 1 ? 'ambiguous' : 'new'
const choices = computed(() => nodes.value.filter(node => classify(node) === props.draft.tab).map(node => ({ id: node.id, label: (props.locale === 'en' ? node.nameEn : node.nameZh) + (node.catalogAlignment?.confirmed ? t(' · 已确认对齐', ' · Alignment confirmed') : t(' · 待审核', ' · To review')), searchText: [node.nameZh, node.nameEn, ...(node.sourceAnchors ?? []).map(anchor => anchor.text)].join(' ') })))
const selected = computed(() => nodes.value.find(node => node.id === props.draft.nodeId))
const suggestions = computed(() => selected.value ? suggestedBaselines(selected.value, fields.value, props.state) : [])
const compatible = computed(() => selected.value ? fields.value.filter(field => compatibleBaseline(selected.value!, field, props.state)) : [])
const baselineChoices = computed(() => compatible.value.map(field => ({ id: field.baselineKey, label: catalogLabel(field.label, props.locale) + (field.parentKey ? ' · ' + catalogLabel(fields.value.find(parent => parent.baselineKey === field.parentKey)?.label, props.locale) : ''), searchText: Object.values(field.label).join(' ') + ' ' + Object.values(field.description ?? {}).join(' ') })))
const selectedBaseline = computed(() => fields.value.find(field => field.baselineKey === props.draft.baselineKey))
const exclusions = computed(() => props.state.catalogReview?.exclusions ?? {})
const missing = computed(() => fields.value.filter(field => !nodes.value.some(node => node.catalogAlignment?.confirmed && node.catalogAlignment.baselineKey === field.baselineKey && node.catalogAlignment.baselineVersion === props.catalog.ruleVersion) && exclusions.value[field.baselineKey]?.baselineVersion !== props.catalog.ruleVersion))
const missingChoices = computed(() => missing.value.map(field => ({ id: field.baselineKey, label: catalogLabel(field.label, props.locale), searchText: Object.values(field.label).join(' ') })))
const missingField = computed(() => missing.value.find(field => field.baselineKey === props.draft.missingKey))
function selectNode() { props.draft.baselineKey = selected.value?.catalogAlignment?.baselineKey ?? suggestions.value[0]?.baselineKey ?? ''; props.draft.reason = ''; props.draft.scope = selected.value?.catalogAlignment?.scope ?? '' }
function align(status: string) {
  if (!selected.value || !props.draft.reason?.trim() || status === 'matched' && !compatible.value.some(field => field.baselineKey === props.draft.baselineKey)) return
  emit('align', selected.value.id, { status, confirmed: status !== 'ambiguous', reason: props.draft.reason.trim(), ...(status === 'matched' ? { baselineKey: props.draft.baselineKey, baselineVersion: props.catalog.ruleVersion } : {}), ...(props.draft.scope?.trim() ? { scope: props.draft.scope.trim() } : {}) })
}
</script>
<template>
  <fieldset class="catalog-review" :disabled="disabled" data-catalog-review>
    <legend>{{ t('变量基准对齐审核', 'Review variable alignment') }}</legend>
    <p>{{ t('基准定义与识别结果均须人手核对。对齐保留变量、来源、条件和修改位置；不会采用候选值。基准未识别不等于必需补入。', 'Review both baseline definitions and identified variables. Alignment preserves variables, sources, conditions and amendment locations without adopting candidate values. Missing baseline entries are not automatically required.') }}</p>
    <nav><button v-for="[key, zh, en] in [['matched','匹配','Matches'],['missing','基准未识别','Missing baseline'],['new','新增候选变量','New candidate variables'],['ambiguous','歧义待核','Ambiguous']]" :key="key" type="button" class="btn" :aria-pressed="draft.tab === key" :data-catalog-tab="key" @click="draft.tab=key;draft.nodeId='';draft.reason=''">{{ t(zh!,en!) }}</button></nav>
    <template v-if="draft.tab !== 'missing'">
      <DraftingQuestionPicker v-model="draft.nodeId" v-model:query="draft.query" :items="choices" :locale="locale" :label="t('选择候选变量', 'Select a candidate variable')" :empty-label="t('请选择变量', 'Choose a variable')" search-attribute="data-catalog-variable-search" context="variables" @change="selectNode" />
      <template v-if="selected">
        <p>{{ t('值类型', 'Value type') }}: {{ typeLabel(selected.type.kind) }} {{ selected.type.unit || '' }} · {{ selected.definitionScope || t('项目范围', 'Project scope') }}</p>
        <blockquote v-for="(anchor,index) in selected.sourceAnchors" :key="index">{{ anchor.text }}</blockquote>
        <p v-if="suggestions.length">{{ t('名称和类型相同的建议，仍需核对语义：', 'Same-name and type suggestions; review their meaning: ') }}{{ suggestions.map(field => catalogLabel(field.label,locale)).join('；') }}</p>
        <p v-if="selected.catalogAlignment">{{ t('已记录', 'Recorded') }}: {{ alignmentLabel(selected.catalogAlignment.status) }} · {{ selected.catalogAlignment.reason }}</p>
        <DraftingQuestionPicker v-model="draft.baselineKey" v-model:query="draft.baselineQuery" :items="baselineChoices" :locale="locale" :label="t('对应基准变量', 'Baseline variable')" :empty-label="t('请选择对应基准', 'Choose a baseline')" search-attribute="data-catalog-baseline-search" context="baseline" />
        <p>{{ t('只列出类型、单位、选项、必填性和明细归属相容的基准；需要调整定义时请打开变量编辑。明细变量须将必需列一并对应，再保存审核。', 'Only compatible types, units, options, optionality and detail memberships are listed. Edit the variable to correct a definition. Match required detail columns before saving.') }}</p>
        <p v-if="selectedBaseline">{{ catalogLabel(selectedBaseline.description,locale) }} <span>{{ selectedBaseline.unit || '' }}</span></p>
        <label>{{ t('适用范围（同一基准在不同范围重复时必填）', 'Scope (required for distinct uses of the same baseline)') }}<input v-model="draft.scope" data-catalog-scope /></label>
        <label>{{ t('人工审核理由', 'Review reason') }}<textarea v-model="draft.reason" data-catalog-reason /></label>
        <button class="btn" type="button" data-catalog-confirm-match :disabled="!draft.reason?.trim() || !selectedBaseline" @click="align('matched')">{{ t('确认对应基准', 'Confirm baseline match') }}</button>
        <button class="btn" type="button" data-catalog-confirm-new :disabled="!draft.reason?.trim()" @click="align('new')">{{ t('确认为新变量', 'Confirm new variable') }}</button>
        <button class="btn" type="button" data-catalog-ambiguous :disabled="!draft.reason?.trim()" @click="align('ambiguous')">{{ t('保留歧义待核', 'Retain for review') }}</button>
        <button class="btn" type="button" data-catalog-inspect @click="emit('inspect',selected.id)">{{ t('编辑变量／拆分／合并', 'Edit, split or merge variable') }}</button>
      </template>
    </template>
    <template v-else>
      <DraftingQuestionPicker v-model="draft.missingKey" v-model:query="draft.missingQuery" :items="missingChoices" :locale="locale" :label="t('基准中尚未对应的变量', 'Unmatched baseline variables')" :empty-label="t('请选择基准变量', 'Choose a baseline variable')" search-attribute="data-catalog-missing-search" context="missing" />
      <template v-if="missingField"><p>{{ catalogLabel(missingField.description,locale) }} · {{ typeLabel(missingField.kind) }} {{ missingField.unit || '' }}</p><p v-if="missingField.parentKey">{{ t('补入列变量时一并补齐所属明细组的定义；已有对应变量保留，不导入任何项目值。', 'Adding a column also completes its detail group definitions. Existing matches are kept and no project values are imported.') }}</p><label>{{ t('补入或不适用的人工理由', 'Reason for adding or excluding') }}<textarea v-model="draft.reason" data-catalog-missing-reason /></label><button type="button" class="btn" data-catalog-add-missing :disabled="!draft.reason?.trim()" @click="emit('add',missingField.baselineKey,draft.reason.trim())">{{ t('补入此变量及所属明细定义', 'Add variable and its detail group definitions') }}</button><button type="button" class="btn" data-catalog-exclude :disabled="!draft.reason?.trim()" @click="emit('exclude',missingField.baselineKey,draft.reason.trim())">{{ t('本项目不适用', 'Not applicable to this project') }}</button></template>
      <ul><li v-for="(entry,key) in exclusions" :key="key">{{ catalogLabel(fields.find(field => field.baselineKey === key)?.label,locale) || key }} · {{ entry.reason }}<button type="button" class="btn" @click="emit('exclude',String(key),null)">{{ t('重新核对', 'Review again') }}</button></li></ul>
    </template>
  </fieldset>
</template>
<style scoped>
.catalog-review{margin:16px 0;padding:16px;border:1px solid var(--line);border-radius:6px}.catalog-review>p{font-size:13px;color:var(--muted)}nav{display:flex;gap:8px;flex-wrap:wrap}nav [aria-pressed=true]{background:var(--accent-soft)}label{display:block;margin:12px 0}input,textarea{display:block;width:100%;font:inherit;padding:8px;box-sizing:border-box}blockquote{max-height:130px;overflow:auto;white-space:pre-wrap;font-size:12px}.btn{margin:4px}
</style>
