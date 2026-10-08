import type { ExtractFieldDiagnostic } from '@/api/types'
import type { AppLocale } from '@/i18n'
import { draftWord, type DraftWord } from './words'

export function diagnosticValue(value: unknown): string {
  if (value === undefined) return '—'
  return typeof value === 'string' ? value : JSON.stringify(value, null, 2)
}
export function fieldDiagnosticWord(status: ExtractFieldDiagnostic['status']): DraftWord {
  const labels: Record<ExtractFieldDiagnostic['status'], DraftWord> = { suggested: 'intakeSuggested', candidate_conflict: 'candidateDisagreement', rejected: 'intakeRejected', model_unanswered: 'modelUnanswered', no_candidate: 'noCandidate', not_assessed: 'notAssessed', source_unresolved: 'sourceUnresolved', coverage_unresolved: 'coverageUnresolved' }
  return labels[status] ?? 'notAssessed'
}
export function diagnosticCode(code: string, locale: AppLocale): string {
  if (code === 'recall_key_not_allowed') return draftWord('reasonRecallKey', locale)
  if (code === 'context_incomplete_source') return draftWord('reasonIncompleteSourceContext', locale)
  if (code === 'unsupported_empty_list') return draftWord('reasonUnsupportedEmptyList', locale)
  if (code.startsWith('bill_metadata_unsupported:')) return draftWord('reasonBillMetadataUnsupported', locale)
  const labels: Record<string, DraftWord> = {
    unknown_key: 'reasonUnknownKey', hidden_key: 'reasonHiddenKey', confidence_missing: 'reasonConfidenceMissing',
    confidence_out_of_range: 'reasonConfidenceRange', confidence_below_threshold: 'reasonConfidenceLow',
    quote_missing: 'reasonQuoteMissing', quote_not_in_part: 'reasonQuoteNotInPart', invalid_value_shape: 'reasonValueShape',
    value_unanswered: 'reasonUnanswered', lexical_support_missing: 'reasonLiteralSupport', repair_key_not_allowed: 'reasonRepairKey',
    quote_value_mismatch: 'reasonQuoteValueMismatch', source_unresolved: 'reasonSourceUnresolved',
    coverage_key_not_allowed: 'reasonCoverageKey', unanswered_repair_value_not_allowed: 'reasonUnansweredRepairValue',
    bill_type_unsupported: 'reasonBillTypeUnsupported', input_not_applicable: 'reasonInputNotApplicable',
    input_applicability_conflict: 'reasonInputApplicabilityConflict', source_list_ambiguous: 'reasonSourceListAmbiguous',
    source_list_invalid_sequence: 'reasonSourceListInvalidSequence', source_list_incomplete: 'reasonSourceListIncomplete',
    source_list_value_mismatch: 'reasonSourceListValueMismatch', source_list_unresolved: 'reasonSourceListUnresolved',
    evidence_quote_reanchored: 'reasonEvidenceQuoteReanchored', source_value_typography_restored: 'reasonSourceTypographyRestored'
  }
  return draftWord(Object.prototype.hasOwnProperty.call(labels, code) ? labels[code] : 'reasonOther', locale)
}

export function recallStopReason(code: string, locale: AppLocale): string {
  const labels: Record<string, DraftWord> = {
    candidate_found: 'recallCandidateFound', no_supported_candidate: 'recallNoSupportedCandidate',
    explicit_pending: 'recallExplicitPending', context_insufficient: 'recallContextInsufficient',
    budget_exhausted: 'recallBudgetExhausted', model_call_failed: 'recallModelCallFailed',
    invalid_json: 'recallInvalidJson', inactive: 'recallInactive', no_source_cue: 'recallNoSourceCue'
  }
  return draftWord(Object.prototype.hasOwnProperty.call(labels, code) ? labels[code] : 'recallUnknownStop', locale)
}

export function contextTrigger(code: string, locale: AppLocale): string {
  const labels: Record<string, DraftWord> = {
    original_neighbors: 'contextOriginalNeighbors', missing_output: 'recallMissingOutput',
    ordinary_null: 'recallOrdinaryNull', rejected_candidate: 'recallRejectedCandidate', related_source: 'recallRelatedSource',
    joint_original_passage: 'contextJointOriginalPassage'
  }
  return draftWord(Object.prototype.hasOwnProperty.call(labels, code) ? labels[code] : 'recallUnknownTrigger', locale)
}

export function extractionAttemptWord(kind: string): DraftWord {
  const labels: Record<string, DraftWord> = { primary: 'primaryAttempt', repair: 'repairAttempt', coverage: 'coverageAttempt', recall: 'recallAttempt' }
  return Object.prototype.hasOwnProperty.call(labels, kind) ? labels[kind] : 'recordedAttempt'
}
