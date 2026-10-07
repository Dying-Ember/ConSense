import type { AppLocale } from '@/i18n'
import type { ExtractRelation } from '@/api/types'

const labels = {
  title: ['同一输入的资料联合核对', '同一輸入的資料聯合核對', 'Joint source review for this input'],
  note: ['仅为可核对的关系建议，不会自动采用或替换本次取值。请结合两侧原文，由业务人员判定。', '僅為可核對的關係建議，不會自動採用或替換本次取值。請結合兩側原文，由業務人員判定。', 'This source relationship proposal does not adopt or replace a value for this draft. A business user must review both original passages.'],
  supplement: ['建议补充', '建議補充', 'Supplement proposed'],
  explicit_replacement: ['建议明确替换', '建議明確替換', 'Explicit replacement proposed'],
  contradiction: ['建议核对矛盾要求', '建議核對矛盾要求', 'Contradictory requirements proposed'],
  undetermined: ['关系未能判定', '關係未能判定', 'Relationship undetermined'],
  skipped: ['未执行联合核对', '未執行聯合核對', 'Review skipped'],
  rejected: ['关系建议未通过接收校验', '關係建議未通過接收校驗', 'Proposal not accepted'],
  failed: ['联合核对失败', '聯合核對失敗', 'Review failed'],
  unknown: ['关系状态未知', '關係狀態未知', 'Relationship status unknown'],
  direction: ['修订方向', '修訂方向', 'Amendment direction'],
  input: ['输入', '輸入', 'Input'],
  scope: ['模型建议的共同范围引文', '模型建議的共同範圍引文', 'Model-proposed shared scope quotation'],
  evidence: ['原始资料及上下文', '原始資料及上下文', 'Original sources and context'],
  source: ['资料', '資料', 'Source'],
  candidate: ['该侧模型候选原值', '該側模型候選原值', 'Original model candidate on this side'],
  details: ['核对请求与原始返回', '核對請求與原始返回', 'Review request and original response'],
  raw: ['原始返回', '原始返回', 'Original response'],
  rawProposal: ['原始关系建议', '原始關係建議', 'Raw relationship proposal'],
  system: ['系统提示词', '系統提示詞', 'System prompt'],
  user: ['联合核对提示词', '聯合核對提示詞', 'Joint review prompt'],
  field_pair_budget_exhausted: ['此输入已达到有限核对次数；其余候选关系未核对。', '此輸入已達到有限核對次數；其餘候選關係未核對。', 'The bounded review limit for this input was reached. Remaining candidate relationships were not reviewed.'],
  joint_review_budget_exhausted: ['本轮有限核对次数已用尽；此关系未核对。', '本輪有限核對次數已用盡；此關係未核對。', 'The bounded run review limit was reached. This relationship was not reviewed.'],
  joint_context_insufficient: ['无法取得足够且身份一致的原始上下文。', '無法取得足夠且身分一致的原始上下文。', 'Sufficient original context with matching source identity was unavailable.'],
  joint_provider_failed: ['模型调用失败；原有候选及人工值保留。', '模型調用失敗；原有候選及人工值保留。', 'The model call failed. Existing candidates and human values are retained.'],
  joint_invalid_json: ['关系返回格式无法解析。', '關係返回格式無法解析。', 'The relationship response could not be parsed.'],
  joint_invalid_relation: ['返回的输入或关系类型无效。', '返回的輸入或關係類型無效。', 'The returned input or relationship type is invalid.'],
  joint_direction_invalid: ['未提供可校验的明确修订方向。', '未提供可校驗的明確修訂方向。', 'An explicit verifiable amendment direction was not provided.'],
  joint_confidence_invalid: ['返回的置信度未满足候选接收条件。', '返回的置信度未滿足候選接收條件。', 'The returned confidence did not meet candidate intake conditions.'],
  joint_scope_invalid: ['原文不能支持返回的共同适用范围。', '原文不能支持返回的共同適用範圍。', 'The original passages do not support the returned shared scope.'],
  joint_evidence_invalid: ['未提供两侧可校验的依据。', '未提供兩側可校驗的依據。', 'Verifiable evidence for both sides was not provided.'],
  joint_source_identity_invalid: ['依据的文件身份或内容指纹不符。', '依據的檔案身分或內容指紋不符。', 'The evidence source identity or content hash does not match.'],
  joint_source_range_invalid: ['依据的原文范围不符。', '依據的原文範圍不符。', 'The evidence range does not match the original source.'],
  joint_quote_invalid: ['依据引文不能在指定原文中核验。', '依據引文不能在指定原文中核驗。', 'The evidence quotation could not be verified in the specified source.'],
  joint_quote_not_candidate_evidence: ['引文并非此输入候选的实际依据。', '引文並非此輸入候選的實際依據。', 'The quotation is not the actual evidence for this input candidate.'],
  joint_amendment_language_missing: ['引文没有支持所建议的明确补充或替换；日期本身不足以证明修订。', '引文沒有支持所建議的明確補充或替換；日期本身不足以證明修訂。', 'The quotation does not support the proposed explicit supplement or replacement. A date alone does not establish amendment.'],
  joint_amendment_context_unresolved: ['原文中的关联的待定限定使修订尚不能确定；短引文不能单独证明已经替换或补充。', '原文中的關聯的待定限定使修訂尚不能確定；短引文不能單獨證明已經替換或補充。', 'A linked pending qualification in the original context leaves the amendment unresolved. A short quotation alone cannot establish a completed replacement or supplement.'],
  joint_relation_undetermined: ['现有原文不足以判定两侧关系。', '現有原文不足以判定兩側關係。', 'The available original passages do not establish the relationship.']
} as const
type Key = keyof typeof labels
export function jointWord(key: string, locale: AppLocale): string {
  const value = labels[key as Key]
  return value ? value[locale === 'en' ? 2 : locale === 'zh-Hant' ? 1 : 0] : key
}
export function jointStatusWord(relation: ExtractRelation): string {
  if (relation.status === 'proposed') return ['supplement', 'explicit_replacement', 'contradiction'].includes(relation.relation ?? '') ? relation.relation! : 'unknown'
  return ['undetermined', 'skipped', 'rejected', 'failed'].includes(relation.status) ? relation.status : 'unknown'
}
