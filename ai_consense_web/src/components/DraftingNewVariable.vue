<script setup lang="ts">
import type { ReviewObject, ReviewVariable } from '@/drafting/source-review-types'
import DraftingQuestionPicker from './DraftingQuestionPicker.vue'
const props = defineProps<{ draft: ReviewObject; parents: ReviewVariable[]; locale: string }>()
const emit = defineEmits<{ create: []; cancel: [] }>()
const t = (zh: string, en: string) => props.locale === 'en' ? en : zh
</script>
<template>
  <section class="new-variable">
    <label>{{ t('变量名称（中文）', 'Variable name (Chinese)') }}<input v-model="draft.nameZh" data-condition-new-name-zh /></label><label>{{ t('英文名称（可选）', 'English name (optional)') }}<input v-model="draft.nameEn" data-condition-new-name-en /></label>
    <label>{{ t('值类型', 'Value type') }}<select v-model="draft.kind" data-condition-new-kind><option v-for="[kind,zh,en] in [['boolean','是／否','Yes / no'],['number','数字','Number'],['text','文字','Text'],['date','日期','Date'],['choice','单选','Single choice'],['multiselect','多选','Multiple choices'],['record','资料组','Record'],['list','明细变量','Repeated details']]" :key="kind" :value="kind">{{ t(zh!,en!) }}</option></select></label>
    <label v-if="draft.kind === 'number'">{{ t('单位（如 months、HKD/page）', 'Unit (for example months or HKD/page)') }}<input v-model="draft.unit" data-new-variable-unit /></label>
    <label><input v-model="draft.optional" type="checkbox" data-new-variable-optional />{{ t('允许不提供变量值（可选变量）', 'Optional variable value') }}</label>
    <label v-if="['choice','multiselect'].includes(draft.kind)">{{ t('选项：每行稳定值 | 中文 | English', 'Options: stable value | Chinese | English, one per line') }}<textarea v-model="draft.optionsText" data-new-variable-options /></label>
    <DraftingQuestionPicker v-model="draft.parentId" :query="draft.parentQuery ?? ''" @update:query="draft.parentQuery=$event" :items="parents.map(parent => ({id:parent.id,label:locale==='en'?parent.nameEn:parent.nameZh,searchText:parent.nameZh+' '+parent.nameEn}))" :locale="locale" :label="t('变量归属', 'Variable membership')" :empty-label="t('项目变量', 'Project variable')" search-attribute="data-new-variable-parent-search" context="parent" data-new-variable-parent />
    <label>{{ t('适用范围（可选）', 'Scope (optional)') }}<input v-model="draft.scope" data-new-variable-scope /></label>
    <label>{{ t('人工新增理由（必填）', 'Reason for adding (required)') }}<textarea v-model="draft.reason" data-new-variable-reason /></label>
    <p>{{ t('只新增变量定义。无模板引文时保留人工理由；变量值仍须单独采用，修改原文仍须核对准确位置。', 'Create a variable definition only. A manual reason records definitions without a template quotation; values require separate adoption and amendments require verified source positions.') }}</p>
    <p v-if="draft.error" class="review-error" role="alert">{{ draft.error }}</p><button type="button" class="btn" data-condition-create-input @click="emit('create')">{{ draft.branchId === '@standalone' ? t('创建为新变量', 'Create as a new variable') : t('创建新变量并选入当前条件', 'Create a new variable for this condition') }}</button><button type="button" class="btn" data-condition-cancel-input @click="emit('cancel')">{{ t('取消新增', 'Cancel new variable') }}</button>
  </section>
</template>
<style scoped>
.new-variable{padding:12px;border:1px solid var(--line);border-radius:6px}label{display:block;margin:10px 0}input,select,textarea{display:block;width:100%;font:inherit;padding:8px;box-sizing:border-box}p{font-size:12px}.btn{margin-right:8px}.review-error{color:var(--red)}
</style>
