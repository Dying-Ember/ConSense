<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { ReviewRevision } from '@/drafting/source-review-types'
import type { DraftAutomaticRelation, RelationReviewDecision, RelationReviewRequest } from '@/drafting/automation-types'
const props = defineProps<{ revision: ReviewRevision; locale: 'zh-Hans' | 'zh-Hant' | 'en'; disabled?: boolean }>()
const emit = defineEmits<{ locate: [sourceId: string, sourceSha256: string]; question: [id: string, revisionId: string]; reviewRelations: [request: RelationReviewRequest] }>()
const t = (zh: string, en: string) => props.locale === 'en' ? en : zh
const state = computed(() => props.revision.graph.graph)
const requirements = computed(() => state.value.requirements ?? [])
const relations = computed(() => state.value.relationProposals ?? [])
const selectedRelations=ref<number[]>([]),relationDecision=ref<RelationReviewDecision>('implemented'),relationReason=ref('')
const canReview=computed(()=>!props.disabled&&!props.revision.sourceStale)
function relationResolved(item: DraftAutomaticRelation) { return !!item.review?.basis && item.review.status==='reviewed' && item.status===({implemented:'confirmed',rejected:'rejected',not_applicable:'excluded'} as const)[item.review.decision] }
const unresolvedRelations=computed(()=>relations.value.filter(item=>!relationResolved(item)).length)
function decisionLabel(decision: RelationReviewDecision) { return ({implemented:t('已在图谱中处理','Handled in the graph'),rejected:t('建议不成立','Proposal rejected'),not_applicable:t('不适用于当前模板','Not applicable to this template')} as const)[decision] }
function toggleRelation(index: number) { selectedRelations.value=selectedRelations.value.includes(index)?selectedRelations.value.filter(item=>item!==index):[...selectedRelations.value,index] }
function reviewRelations(indices: number[]) {
  if(!canReview.value||!relationReason.value.trim()||!indices.length)return
  emit('reviewRelations',{revisionId:props.revision.id,indices:[...indices],decision:relationDecision.value,reason:relationReason.value.trim()})
}
const issues = computed(() => state.value.automaticDiscoveryIssues ?? [])
const groups = computed(() => state.value.automaticDiscovery?.groups ?? [])
function groupBasis(kind: string) { return ({source_observation:t('独立来源事项','Independent source item'),reviewed_fixed_root:t('已核对的固定变量关联','Reviewed fixed-variable link'),fixed_source_reference:t('同版固定定义的原文参考','Same-edition definition reference'),shared_current_scope:t('共享当前原文范围','Shared current source range'),explicit_source_condition:t('原文明确的条件关联','Explicit source condition'),related_guidance:t('关联指导说明','Related guidance')} as Record<string,string>)[kind] ?? t('关联依据待核对','Grouping basis requires review') }
function groupStatus(status: string) { return ({completed:t('模型处理完成，待人工核对','Model processing finished; human review pending'),reviewed_no_requirement:t('未提出编制要求，保留原文记录','No drafting requirement proposed; source record retained'),partial:t('已有部分结果，仍需补核','Partial results; further review needed'),failed:t('本组未完成','Group failed'),blocked:t('本组待处理','Group blocked'),unknown:t('调用结果未知','Call outcome unknown'),unexecuted:t('尚未处理','Not processed')} as Record<string,string>)[status] ?? t('待核对处理状态','Processing status requires review') }
function reasonLabel(reason: string) { return ({
  same_fact_structure_conflict: t('两个对象的类型、值域或定义不相容，已保留为未确定关系，请分别核对。', 'The types, domains or definitions conflict. The relation remains undetermined.'),
  source_context_candidate_conflict: t('补充证据后的候选含义发生冲突，原候选已保留，请结合原文核对。', 'The follow-up conflicts with the retained candidate. Review both against the source.'),
  source_context_rule_conflict: t('补充证据后的分支或修改范围发生冲突，原范围已保留。', 'A follow-up branch or amendment conflicts with the retained rule. The original range remains.'),
  source_evidence_request_unresolved: t('本轮仍有未完成的取证请求，已有候选及未决说明均已保留。', 'Evidence requests remain unresolved. Existing candidates and uncertainty are retained.'),
  source_evidence_review_unavailable: t('补充取证未完成，已保留前面取得的候选和原文范围。', 'Supplemental review did not finish. Earlier candidates and source ranges remain.'),
  source_evidence_context_budget_exceeded: t('补充证据超过本轮预算，完整原文仍保留，未作截断采用。', 'Supplemental evidence exceeds this round budget. Full originals remain available.'),
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
const tab=ref<'requirements'|'relations'|'issues'|'groups'>('requirements'),page=ref(0),search=ref('')
const tabs=computed(()=>groups.value.length?(['requirements','relations','issues','groups'] as const):(['requirements','relations','issues'] as const))
function nodeName(id:string){const node=state.value.variables.find(item=>item.id===id);return node?.definitionKind==='source_seed'?t('来源待核对','Source requires review'):node?(props.locale==='en'?node.nameEn||node.nameZh:node.nameZh||node.nameEn):t('需核对的变量','Variable requiring review')}
function relationKind(kind: string) { return ({ same_fact: t('可能为同一事实','Possibly the same fact'), dependency: t('可能存在依赖','Possible dependency'), contradiction: t('可能存在矛盾','Possible contradiction'), undetermined: t('关系未确定','Relationship undetermined') } as Record<string,string>)[kind] ?? t('关系待核对','Relationship requires review') }
function canInspect(id: string) { return !props.disabled && !props.revision.sourceStale && state.value.variables.some(item => item.id === id && item.status !== 'excluded') }
const rows=computed(()=>{
  if(tab.value==='groups')return groups.value.map((group,index)=>({title:t('处理组','Processing group')+' '+(index+1),quote:[groupStatus(group.status),group.basisKinds.map(groupBasis).join(' / '),group.sourceIds.map(id=>props.revision.graph.sources.find(source=>source.id===id)?.fileName||t('历史来源','Historical source')).join(' / '),t('覆盖来源事项：','Source items covered: ')+group.taskIds.length,t('实际模型调用：','Actual model calls: ')+(group.modelCalls==null?t('未知','Unknown'):group.modelCalls)].join('\n'),sourceId:'',sha:'',relation:undefined,relationIndex:-1})).filter(item=>!search.value||(item.title+' '+item.quote).toLowerCase().includes(search.value.toLowerCase()))
  const records=tab.value==='requirements'?requirements.value.map(item=>({title:item.nodeIds.map(nodeName).join('、')||t('要求尚待对应变量','Requirement needs a variable'),quote:item.sourceQuote,sourceId:item.sourceId,sha:item.sourceSha256,relation:undefined,relationIndex:-1})):tab.value==='relations'?relations.value.map((item,index)=>({title:nodeName(item.fromId)+' / '+nodeName(item.toId),quote:[item.fromQuote,item.toQuote,item.reason,relationKind(item.kind),item.review?.reason].filter(Boolean).join(' / '),sourceId:'',sha:'',relation:item,relationIndex:index})):issues.value.map(item=>({title:item.nodeId?nodeName(item.nodeId):t('来源或规则需补核','Source or rule requires review'),quote:reasonLabel(item.reason),sourceId:'',sha:'',relation:undefined,relationIndex:-1}))
  return records.filter(item=>!search.value||(item.title+' '+item.quote).toLowerCase().includes(search.value.toLowerCase()))
})
watch([tab,search,()=>props.revision.id],()=>page.value=0)
watch(()=>props.revision.id,()=>{selectedRelations.value=[];relationReason.value='';relationDecision.value='implemented'})
const shown=computed(()=>rows.value.slice(page.value*5,page.value*5+5))
</script>

<template>
  <section class="automation-results" data-automation-results>
    <h3>{{ t('本次识别记录','Identification record') }}</h3>
    <p>{{ t('以下为任务结束时的记录。请在上方原件中逐项核对，当前变量与修改以已保存的核对草稿为准。','These are the task-end records. Review the original above; the saved review draft owns current variables and amendments.') }}</p>
    <p v-if="state.automaticDiscovery?.restoredDefinitionCount || state.automaticDiscovery?.evidenceRounds">{{ t('复用定义参考','Restored definition references') }} {{ state.automaticDiscovery?.restoredDefinitionCount ?? 0 }} · {{ t('补充取证轮次','Evidence rounds') }} {{ state.automaticDiscovery?.evidenceRounds ?? 0 }} · {{ t('补取原文条目','Source items retrieved') }} {{ state.automaticDiscovery?.supplementalSourceItems ?? 0 }}. {{ t('引用和取证数量不代表已确认，也不授权文件修改。','These counts do not imply confirmation or edit authority.') }}</p>
    <p v-if="revision.sourceStale" class="warning" role="status">{{ t('来源已变化，以下为旧任务记录。当前原件及历史核对结果均保留。','Sources have changed. These are historical task records; current originals and review history are retained.') }}</p>
    <div class="result-summary"><span>{{ t('识别要求','Requirements') }} {{ requirements.length }}</span><span>{{ t('关系建议待核对','Relationship proposals to review') }} {{ unresolvedRelations }} / {{ relations.length }}</span><span>{{ t('补核记录','Further-review records') }} {{ issues.length }}</span></div>
    <nav class="record-tabs" :aria-label="t('识别记录类别','Identification record category')"><button v-for="kind in tabs" :key="kind" class="btn" :aria-pressed="tab===kind" @click="tab=kind">{{ kind==='requirements'?t('编制要求','Requirements'):kind==='relations'?t('关系建议','Relationships'):kind==='groups'?t('分组记录','Processing groups'):t('需补核','Further review') }}</button><input v-model="search" :placeholder="t('搜索本次记录','Search task records')"></nav>
    <p v-if="tab==='groups'" data-group-coverage-note>{{ t('分组用于共享相关证据，不代表组内变量已经合并或确认。各来源事项仍保留独立的覆盖与未决记录。','Groups share relevant evidence; they do not mean that variables have been merged or confirmed. Each source item retains its own coverage and unresolved records.') }}</p>
    <div v-if="tab==='relations' && relations.length" class="relation-review">
      <p>{{ t('先在图谱中完成合并、条件或范围调整并保存，再记录处理结果。此处不会自动建立关系或执行修改。','Save any merge, condition or range adjustments in the graph first, then record the outcome here. This does not create relationships or execute edits.') }}</p>
      <label>{{ t('处理结果','Review outcome') }}<select v-model="relationDecision" :disabled="!canReview"><option v-for="decision in (['implemented','rejected','not_applicable'] as const)" :key="decision" :value="decision">{{ decisionLabel(decision) }}</option></select></label>
      <label>{{ t('处理理由（必填）','Review reason (required)') }}<textarea v-model="relationReason" :disabled="!canReview" rows="2" /></label>
      <button type="button" class="btn" :disabled="!canReview || !relationReason.trim() || !selectedRelations.length" @click="reviewRelations(selectedRelations)">{{ t('保存所选关系的处理','Save selected relationship reviews') }} ({{ selectedRelations.length }})</button>
      <button v-if="selectedRelations.length" type="button" class="btn" @click="selectedRelations=[]">{{ t('清空选择','Clear selection') }}</button>
    </div>
    <p v-if="!rows.length">{{ t('没有匹配的任务记录，当前变量仍以上方核对草稿为准。','No matching task records. The review draft above owns current variables.') }}</p>
    <article v-for="(item,index) in shown" :key="`${tab}-${page}-${index}`"><strong>{{ item.title }}</strong><template v-if="item.relation">
      <p>{{ relationKind(item.relation.kind) }} · {{ relationResolved(item.relation)?t('已记录人工处理','Human review recorded'):t('待人工核对','Awaiting human review') }}</p>
      <p v-if="item.relation.reason">{{ t('建议理由','Reason') }}: {{ item.relation.reason }}</p>
      <p v-if="item.relation.review" :class="{warning:item.relation.review.status==='needs_review'}">{{ decisionLabel(item.relation.review.decision) }}: {{ item.relation.review.reason }} <span v-if="item.relation.review.confirmedAt">({{ item.relation.review.confirmedAt }})</span><br v-if="item.relation.review.status==='needs_review'"><span v-if="item.relation.review.status==='needs_review'">{{ t('相关定义、条件或范围已变化，请重新核对；原处理记录保留。','Related definitions, conditions or ranges changed. Review again; the earlier decision is retained.') }}</span></p>
      <div v-for="side in [{ id: item.relation.fromId, quote: item.relation.fromQuote }, { id: item.relation.toId, quote: item.relation.toQuote }]" :key="side.id"><p>{{ nodeName(side.id) }} · {{ t('来源引文','Source quotation') }}</p><blockquote>{{ side.quote }}</blockquote><button type="button" class="btn" :disabled="!canInspect(side.id)" @click="emit('question',side.id,revision.id)">{{ t('打开变量核对','Review variable') }}</button></div>
      <p v-if="revision.sourceStale || !canInspect(item.relation.fromId) || !canInspect(item.relation.toId)">{{ t('来源、变量或未保存调整需要先核对；不可从此记录直接跳转。','Review changed sources, variables or unsaved edits before opening this record.') }}</p>
      <label><input type="checkbox" :checked="selectedRelations.includes(item.relationIndex)" :disabled="!canReview" @change="toggleRelation(item.relationIndex)"> {{ t('选择此关系','Select this relationship') }}</label>
      <button type="button" class="btn" :disabled="!canReview || !relationReason.trim()" @click="reviewRelations([item.relationIndex])">{{ t('保存本项处理','Save this review') }}</button>
    </template><template v-else><blockquote>{{ item.quote }}</blockquote><button v-if="item.sourceId" class="btn" @click="emit('locate',item.sourceId,item.sha)">{{ t('在原件中核对','Review on original') }}</button></template></article>
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
.relation-review{padding:10px;background:var(--bg);border-radius:5px}.relation-review label{display:block;margin:8px 0}.relation-review select,.relation-review textarea{display:block;width:100%;box-sizing:border-box;padding:7px;border:1px solid var(--line);border-radius:5px}.relation-review .btn,article .btn{margin:4px}
.result-summary,.source-records{display:flex;gap:12px;flex-wrap:wrap;margin:12px 0}.result-summary span{padding:6px 10px;background:var(--bg);border-radius:5px}.hint{color:var(--muted)}
.record-tabs{display:flex;gap:8px;align-items:center;flex-wrap:wrap;margin:12px 0}.record-tabs [aria-pressed="true"]{background:var(--accent-soft);border-color:var(--accent)}.record-tabs input{padding:7px;border:1px solid var(--line);border-radius:5px}article{padding:10px 0}blockquote{max-height:110px;overflow:auto}
</style>
