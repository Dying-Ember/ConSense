import type { DraftCatalog, DraftField, LocalizedText } from '@/api/types'
import type { ReviewObject, ReviewState, ReviewVariable } from './source-review-types'
import { pendingEditorial } from './source-review/value-dependencies.js'

export type BaselineVariable = DraftField & { baselineKey: string; baselineVersion: string; parentKey: string; groupLabel: LocalizedText }
export type BaselineProposal = ReviewObject & { baselineKey: string; field?: BaselineVariable; compatible: boolean; issues: string[] }
export const variableKind = (kind: string) => ['bills', 'table', 'records'].includes(kind) ? 'list' : ['contract', 'object'].includes(kind) ? 'record' : ['textarea', 'longtext'].includes(kind) ? 'text' : kind
export const catalogLabel = (label: LocalizedText | undefined, locale = 'zh') => label?.[locale === 'en' ? 'en' : 'zhHans'] || label?.zhHant || label?.en || ''
export function baselineVariables(catalog: DraftCatalog): BaselineVariable[] {
  const result: BaselineVariable[] = []
  const append = (field: DraftField, parentKey: string, groupLabel: LocalizedText) => {
    if (field.hidden || field.key === 'contractTitle' || field.key === 'id' && field.hidden !== false) return
    const baselineKey = parentKey ? parentKey + '.' + field.key : field.key
    result.push({ ...field, baselineKey, baselineVersion: catalog.ruleVersion, parentKey, groupLabel })
    for (const child of field.columnFields ?? []) append(child, baselineKey, groupLabel)
  }
  for (const group of catalog.groups) for (const field of group.fields) append(field, '', group.label)
  return result
}
export const businessVariable = (node: ReviewObject) => node.status !== 'excluded' && node.definitionKind !== 'source_seed' && ['input','decision','content'].includes(node.role?.kind) && !(node.editorialReview?.confirmed === true && node.editorialReview?.kind === 'editorial') && !pendingEditorial(node)
export function baselineCompatibilityIssues(node: ReviewVariable, baseline: BaselineVariable, state: ReviewState, scope = node.catalogAlignment?.scope ?? ''): string[] {
  const issues: string[] = []
  if (node.role?.kind && !['input', 'decision', 'content'].includes(node.role.kind) || !businessVariable(node)) issues.push('role_mismatch')
  if (variableKind(baseline.kind) !== node.type.kind) issues.push('type_mismatch')
  if (!!baseline.optional !== !!node.optional) issues.push('optional_mismatch')
  const parent = state.variables.find(item => item.id === node.parentId)
  if (baseline.parentKey ? parent?.catalogAlignment?.baselineKey !== baseline.parentKey : !!node.parentId && !scope.trim()) issues.push(baseline.parentKey && !parent?.catalogAlignment?.baselineKey ? 'parent_mapping_required' : 'parent_mismatch')
  if (String(node.type.unit ?? node.unit ?? '').trim() !== (baseline.unit ?? '')) issues.push('unit_mismatch')
  const options = (baseline.options ?? []).map(option => String(option.value)).sort()
  if (['choice','multiselect'].includes(node.type.kind) && (options.length !== (node.options ?? []).length || !options.every((value, index) => value === (node.options ?? []).map(option => String(option.value)).sort()[index]))) issues.push('option_domain_mismatch')
  return issues
}
export function compatibleBaseline(node: ReviewVariable, baseline: BaselineVariable, state: ReviewState, scope?: string): boolean {
  return baselineCompatibilityIssues(node, baseline, state, scope).length === 0
}
export function baselineProposals(node: ReviewVariable, fields: BaselineVariable[], state: ReviewState, scope?: string): BaselineProposal[] {
  if (!businessVariable(node)) return []
  return (Array.isArray(node.catalogAlignment?.proposedMatches) ? node.catalogAlignment.proposedMatches : []).map((proposal: ReviewObject) => {
    const field = fields.find(value => value.baselineKey === proposal.baselineKey)
    const originalIssues: string[] = Array.isArray(proposal.issues) ? proposal.issues.filter((value: unknown) => typeof value === 'string') : []
    const recomputed = ['type_mismatch', 'unit_mismatch', 'option_domain_mismatch', 'optional_mismatch', 'parent_mapping_required', 'parent_mismatch', 'baseline_version_changed', 'child_mapping_required']
    const issues = [...originalIssues.filter(code => !recomputed.includes(code)), ...(field ? baselineCompatibilityIssues(node, field, state, scope) : ['baseline_unavailable'])]
    if (field && proposal.baselineVersion !== field.baselineVersion) issues.push('baseline_version_changed')
    if (field && originalIssues.includes('child_mapping_required')) {
      const projected = { ...state, variables: state.variables.map(value => value.id === node.id ? { ...value, catalogAlignment: { ...value.catalogAlignment, baselineKey: field.baselineKey } } : value) }
      if ((field.columnFields ?? []).filter(child => !child.optional && !child.hidden).some(child => {
        const key = field.baselineKey + '.' + child.key, definition = fields.find(value => value.baselineKey === key)
        return !definition || !state.variables.some(value => businessVariable(value) && value.parentId === node.id && compatibleBaseline(value, definition, projected) && (value.catalogAlignment?.confirmed && value.catalogAlignment?.baselineKey === key || value.catalogAlignment?.proposedMatches?.length === 1 && value.catalogAlignment.proposedMatches[0].baselineKey === key && value.catalogAlignment.proposedMatches[0].baselineVersion === field.baselineVersion))
      })) issues.push('child_mapping_required')
    }
    if (proposal.compatible !== true && !originalIssues.length) issues.push('definition_mismatch')
    return { ...proposal, baselineKey: String(proposal.baselineKey ?? ''), field, originalIssues, compatible: !issues.length, issues: [...new Set(issues)] }
  })
}
export function suggestedBaselines(node: ReviewVariable, fields: BaselineVariable[], state: ReviewState) {
  if (['unavailable', 'unmatched'].includes(node.catalogAlignment?.proposalStatus)) return []
  if (node.catalogAlignment?.proposalStatus || Array.isArray(node.catalogAlignment?.proposedMatches)) return baselineProposals(node, fields, state).filter(proposal => proposal.compatible).map(proposal => proposal.field!)
  const normalize = (value: string) => value.toLocaleLowerCase().replace(/[\s?？()（）]+/g, '')
  const names = [node.nameZh, node.nameEn, node.questionZh, node.questionEn].filter(Boolean).map(normalize)
  return fields.filter(field => compatibleBaseline(node, field, state) && [field.key, ...Object.values(field.label)].some(name => names.includes(normalize(name))))
}
export function alignmentGroup(node: ReviewVariable, fields: BaselineVariable[], state: ReviewState, version: string): string {
  const alignment = node.catalogAlignment
  if (['matched', 'new', 'ambiguous'].includes(alignment?.status)) return alignment.status
  if (alignment?.proposalStatus === 'unavailable') return 'unavailable'
  if (alignment?.proposalStatus === 'unmatched') return 'new'
  if (alignment?.proposalStatus === 'ambiguous') return 'ambiguous'
  if (alignment?.proposalStatus === 'matched') {
    const proposals = baselineProposals(node, fields, state)
    return proposals.length === 1 && proposals[0]!.compatible && proposals[0]!.baselineVersion === version ? 'matched' : 'ambiguous'
  }
  const count = suggestedBaselines(node, fields, state).length
  return count === 1 ? 'matched' : count > 1 ? 'ambiguous' : 'new'
}
export function baselineNode(field: BaselineVariable, version: string, reason: string, parentId: string | null): ReviewObject {
  return { nameZh: catalogLabel(field.label), nameEn: catalogLabel(field.label, 'en'), questionZh: catalogLabel(field.label), questionEn: catalogLabel(field.label, 'en'), type: { kind: variableKind(field.kind), ...(field.unit ? { unit: field.unit } : {}) }, role: { kind: 'input', zh: '变量', en: 'Variable' }, parentId, optional: !!field.optional, options: (field.options ?? []).map(option => ({ value: option.value, labelZh: catalogLabel(option.label), labelEn: catalogLabel(option.label, 'en') })), definitionOrigin: { kind: 'manual', reason }, catalogAlignment: { status: 'matched', baselineKey: field.baselineKey, baselineVersion: version, confirmed: true, reason } }
}
