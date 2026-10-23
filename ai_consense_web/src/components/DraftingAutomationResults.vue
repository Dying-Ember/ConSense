<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { ReviewRevision } from '@/drafting/source-review-types'
const props = defineProps<{ revision: ReviewRevision; locale: 'zh-Hans' | 'zh-Hant' | 'en' }>()
const emit = defineEmits<{ locate: [sourceId: string, sourceSha256: string] }>()
const t = (zh: string, en: string) => props.locale === 'en' ? en : zh
const state = computed(() => props.revision.graph.graph)
const requirements = computed(() => state.value.requirements ?? [])
const relations = computed(() => state.value.relationProposals ?? [])
const issues = computed(() => state.value.automaticDiscoveryIssues ?? [])
function reasonLabel(reason: string) { return ({
  incomplete_json_complete_members_only: t('模型返回不完整，已保留完整且有来源依据的部分；其余需补核。', 'The model response was incomplete. Complete source-supported results were retained; remaining parts require review.'),
  requirements_array_required: t('模型未返回完整的要求清单，仍需核对原文是否遗漏。', 'The model did not return the complete requirement list. Check the source for omissions.'),
  unsupported_requirement_quote_kind_or_node_reference: t('要求的原文依据或节点对应未通过核验，未作为已确认要求。', 'A requirement quotation or node assignment did not pass verification.'),
  node_without_supported_requirement_mapping: t('此原子节点尚未对应到有依据的要求，需要人工核对。', 'This atomic node does not yet map to a supported requirement.'),
  relation_source_or_reference_invalid: t('关系候选的原文依据或连接对象未通过核验。', 'A relation proposal quotation or endpoint did not pass verification.'),
  member_identity_missing_or_duplicated: t('原文位置的身份缺失或重复，尚不能安全识别。', 'A source position is missing or duplicated and cannot be safely processed.'),
  member_source_channel_or_overlap_unresolved: t('此处原文的来源绑定或重叠范围未明确，需要核对。', 'The source binding or overlapping range remains unresolved.'),
  complete_source_context_exceeds_packet_budget: t('完整原文超过本次模型可处理范围，已保留原文待后续处理。', 'The complete source exceeds the model context budget and remains available for further processing.'),
  complete_relation_catalog_exceeds_context_budget: t('完整关系范围超过模型可处理范围，图谱关系仍需补核。', 'The complete relationship catalog exceeds the model context budget and needs further review.')
  ,semantic_completeness_not_proven_by_native_member_inventory: t('OER未将此位置列为编制要求；此清单不代表业务含义已全部核实。', 'OER did not list this position as a drafting requirement; this inventory does not establish semantic completeness.')
} as Record<string, string>)[reason] ?? t('此项未能完整确认，请对照原文核对；已有可用结果保留。', 'This item could not be fully established. Review the source; usable results are retained.') }
const tab=ref<'requirements'|'relations'|'issues'>('requirements'),page=ref(0),search=ref('')
function nodeName(id:string){const node=state.value.variables.find(item=>item.id===id);return node?.definitionKind==='source_seed'?t('来源待核对','Source requires review'):node?(props.locale==='en'?node.nameEn||node.nameZh:node.nameZh||node.nameEn):t('需核对的变量','Variable requiring review')}
const rows=computed(()=>{
  const records=tab.value==='requirements'?requirements.value.map(item=>({title:item.nodeIds.map(nodeName).join('、')||t('要求尚待对应变量','Requirement needs a variable'),quote:item.sourceQuote,sourceId:item.sourceId,sha:item.sourceSha256})):tab.value==='relations'?relations.value.map(item=>({title:nodeName(item.fromId)+' → '+nodeName(item.toId),quote:[item.fromQuote,item.toQuote].filter(Boolean).join(' / '),sourceId:'',sha:''})):issues.value.map(item=>({title:item.nodeId?nodeName(item.nodeId):t('来源或规则需补核','Source or rule requires review'),quote:reasonLabel(item.reason),sourceId:'',sha:''}))
  return records.filter(item=>!search.value||(item.title+' '+item.quote).toLowerCase().includes(search.value.toLowerCase()))
})
watch([tab,search,()=>props.revision.id],()=>page.value=0)
const shown=computed(()=>rows.value.slice(page.value*5,page.value*5+5))
</script>

<template>
  <section class="automation-results" data-automation-results>
    <h3>{{ t('本次识别记录','Identification record') }}</h3>
    <p>{{ t('以下为任务结束时的记录。请在上方原件中逐项核对，当前变量与修改以已保存的核对草稿为准。','These are the task-end records. Review the original above; the saved review draft owns current variables and amendments.') }}</p>
    <p v-if="revision.sourceStale" class="warning" role="status">{{ t('来源已变化，以下为旧任务记录。当前原件及历史核对结果均保留。','Sources have changed. These are historical task records; current originals and review history are retained.') }}</p>
    <div class="result-summary"><span>{{ t('识别要求','Requirements') }} {{ requirements.length }}</span><span>{{ t('关系建议待核对','Relationship proposals to review') }} {{ relations.length }}</span><span>{{ t('补核记录','Further-review records') }} {{ issues.length }}</span></div>
    <nav class="record-tabs" :aria-label="t('识别记录类别','Identification record category')"><button v-for="kind in (['requirements','relations','issues'] as const)" :key="kind" class="btn" :aria-pressed="tab===kind" @click="tab=kind">{{ kind==='requirements'?t('编制要求','Requirements'):kind==='relations'?t('关系建议','Relationships'):t('需补核','Further review') }}</button><input v-model="search" :placeholder="t('搜索本次记录','Search task records')"></nav>
    <p v-if="!rows.length">{{ t('没有匹配的任务记录，当前变量仍以上方核对草稿为准。','No matching task records. The review draft above owns current variables.') }}</p>
    <article v-for="(item,index) in shown" :key="`${tab}-${page}-${index}`"><strong>{{ item.title }}</strong><blockquote>{{ item.quote }}</blockquote><button v-if="item.sourceId" class="btn" @click="emit('locate',item.sourceId,item.sha)">{{ t('在原件中核对','Review on original') }}</button></article>
    <nav v-if="rows.length>5" class="record-tabs"><button class="btn" :disabled="page===0" @click="page--">{{ t('上一组','Previous') }}</button><span>{{ page+1 }} / {{ Math.ceil(rows.length/5) }}</span><button class="btn" :disabled="(page+1)*5>=rows.length" @click="page++">{{ t('下一组','Next') }}</button></nav>
    <p class="hint">{{ t('识别数量不代表已确认或已采用。未决内容继续保留，系统不会自动执行关系建议。','Counts do not mean confirmation or adoption. Unresolved items remain; relationship proposals are not applied automatically.') }}</p>
  </section>
</template>

<style scoped>
.automation-results { padding: 14px 16px; border: 1px solid var(--line); border-radius: var(--radius); }
h3 { margin: 0 0 10px; font-size: 15px; }
p,summary,li { font-size: 13px; line-height: 1.65; }
details { margin-top: 12px; } summary { cursor: pointer; font-weight: 600; }
article { border-top: 1px solid var(--line); padding: 12px 0; overflow-wrap: anywhere; }
blockquote { margin: 8px 0; padding: 8px 12px; border-left: 3px solid var(--line); white-space: pre-wrap; }
.warning { color: var(--warning, #895400); }
.result-summary,.source-records{display:flex;gap:12px;flex-wrap:wrap;margin:12px 0}.result-summary span{padding:6px 10px;background:var(--bg);border-radius:5px}.hint{color:var(--muted)}
.record-tabs{display:flex;gap:8px;align-items:center;flex-wrap:wrap;margin:12px 0}.record-tabs [aria-pressed="true"]{background:var(--accent-soft);border-color:var(--accent)}.record-tabs input{padding:7px;border:1px solid var(--line);border-radius:5px}article{padding:10px 0}blockquote{max-height:110px;overflow:auto}
</style>
