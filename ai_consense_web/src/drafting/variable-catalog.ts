import type { DraftCatalog, DraftField, LocalizedText } from '@/api/types'
import type { ReviewObject, ReviewState, ReviewVariable } from './source-review-types'

export type BaselineVariable = DraftField & { baselineKey: string; parentKey: string; groupLabel: LocalizedText }
export const variableKind = (kind: string) => ['bills', 'table', 'records'].includes(kind) ? 'list' : ['contract', 'object'].includes(kind) ? 'record' : ['textarea', 'longtext'].includes(kind) ? 'text' : kind
export const catalogLabel = (label: LocalizedText | undefined, locale = 'zh') => label?.[locale === 'en' ? 'en' : 'zhHans'] || label?.zhHant || label?.en || ''
export function baselineVariables(catalog: DraftCatalog): BaselineVariable[] {
  const result: BaselineVariable[] = []
  const append = (field: DraftField, parentKey: string, groupLabel: LocalizedText) => {
    if (field.hidden || field.key === 'contractTitle' || field.key === 'id' && field.hidden !== false) return
    const baselineKey = parentKey ? parentKey + '.' + field.key : field.key
    result.push({ ...field, baselineKey, parentKey, groupLabel })
    for (const child of field.columnFields ?? []) append(child, baselineKey, groupLabel)
  }
  for (const group of catalog.groups) for (const field of group.fields) append(field, '', group.label)
  return result
}
export const businessVariable = (node: ReviewObject) => node.status !== 'excluded' && node.definitionKind !== 'source_seed' && node.role?.kind !== 'verification'
export function compatibleBaseline(node: ReviewVariable, baseline: BaselineVariable, state: ReviewState): boolean {
  if (variableKind(baseline.kind) !== node.type.kind) return false
  if (!!baseline.optional !== !!node.optional) return false
  const parent = state.variables.find(item => item.id === node.parentId)
  if (baseline.parentKey ? parent?.catalogAlignment?.baselineKey !== baseline.parentKey : !!node.parentId) return false
  if (String(node.type.unit ?? '').trim() !== (baseline.unit ?? '')) return false
  if (!['choice','multiselect'].includes(node.type.kind)) return true
  const options = (baseline.options ?? []).map(option => String(option.value)).sort()
  return options.length === (node.options ?? []).length && options.every((value, index) => value === (node.options ?? []).map(option => String(option.value)).sort()[index])
}
export function suggestedBaselines(node: ReviewVariable, fields: BaselineVariable[], state: ReviewState) {
  const normalize = (value: string) => value.toLocaleLowerCase().replace(/[\s?？()（）]+/g, '')
  const names = [node.nameZh, node.nameEn, node.questionZh, node.questionEn].filter(Boolean).map(normalize)
  return fields.filter(field => compatibleBaseline(node, field, state) && [field.key, ...Object.values(field.label)].some(name => names.includes(normalize(name))))
}
export function baselineNode(field: BaselineVariable, version: string, reason: string, parentId: string | null): ReviewObject {
  return { nameZh: catalogLabel(field.label), nameEn: catalogLabel(field.label, 'en'), questionZh: catalogLabel(field.label), questionEn: catalogLabel(field.label, 'en'), type: { kind: variableKind(field.kind), ...(field.unit ? { unit: field.unit } : {}) }, role: { kind: 'input', zh: '变量', en: 'Variable' }, parentId, optional: !!field.optional, options: (field.options ?? []).map(option => ({ value: option.value, labelZh: catalogLabel(option.label), labelEn: catalogLabel(option.label, 'en') })), definitionOrigin: { kind: 'manual', reason }, catalogAlignment: { status: 'matched', baselineKey: field.baselineKey, baselineVersion: version, confirmed: true, reason } }
}
