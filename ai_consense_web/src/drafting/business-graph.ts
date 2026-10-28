import type { DraftCatalog, DraftCondition, DraftField, DraftGroup, DraftPlan, DraftPlanAction, DraftTarget } from '@/api/types'
import { applicability, type Applicability, type DraftValue } from './state'
import { isCollectionKind, isObjectKind } from './field-kinds'

export type BusinessGraphPlanStatus = 'current' | 'stale' | 'unavailable'
export type BusinessGraphFileKey = 'NTT' | 'SCT' | 'SCC'
export interface BusinessGraphSubfield { id: string; key: string; parentKey: string; field: DraftField }
export interface BusinessGraphInput {
  id: string
  key: string
  groupId: string
  field: DraftField
  value: DraftValue | null
  applicability: Applicability
  condition: DraftCondition | undefined
  prerequisiteKeys: string[]
  subfields: BusinessGraphSubfield[]
  actionIds: string[]
  potentialTargets: DraftTarget[]
}
export interface BusinessGraphGroup { id: string; label: DraftGroup['label']; inputKeys: string[]; actionIds: string[]; fileKeys: string[] }
export interface BusinessGraphAction extends DraftTarget { id: string; inputKeys: string[]; result: DraftPlanAction }
export interface BusinessGraphFile { id: string; key: BusinessGraphFileKey; actionIds: string[]; inputKeys: string[] }
export interface BusinessGraphEdge {
  id: string
  from: string
  to: string
  kind: 'membership' | 'structure' | 'prerequisite' | 'dependency' | 'destination' | 'potential'
  conjunction?: 'all'
}
export interface BusinessGraph {
  ruleVersion: string
  planStatus: BusinessGraphPlanStatus
  groups: BusinessGraphGroup[]
  inputs: BusinessGraphInput[]
  actions: BusinessGraphAction[]
  files: BusinessGraphFile[]
  edges: BusinessGraphEdge[]
}

/** Compare request meaning across typed API values and the existing encoded form controls. */
function identityValue(raw: unknown, field?: DraftField): unknown {
  if (raw === undefined || raw === null || raw === '') return null
  let value = raw
  if (field && (isCollectionKind(field.kind) || isObjectKind(field.kind)) && typeof value === 'string') {
    try { value = JSON.parse(value) } catch { /* Invalid entered text remains distinct and inspectable. */ }
  }
  if (field?.kind === 'boolean') {
    if ([true, 'true', 'Yes', 'yes', '是'].includes(value as string | boolean)) return true
    if ([false, 'false', 'No', 'no', '否'].includes(value as string | boolean)) return false
  }
  if (field?.kind === 'number' && (typeof value === 'number' || typeof value === 'string') && String(value).trim() !== '' && Number.isFinite(Number(value))) return Number(value)
  if (Array.isArray(value)) return value.map(item => identityValue(item, field?.columnFields ? { ...field, kind: 'object' } : undefined))
  if (value && typeof value === 'object') return Object.fromEntries(Object.keys(value).sort().map(key => [key, identityValue((value as Record<string, unknown>)[key], field?.columnFields?.find(column => column.key === key))]))
  return value
}

/** Readonly projection of the existing catalogue and plan; it does not decide clause actions. */
export function buildBusinessGraph({ catalog, plan, values, conditionValues = values }: { catalog: DraftCatalog; plan: DraftPlan | null; values: Record<string, DraftValue>; conditionValues?: Record<string, DraftValue> }): BusinessGraph {
  const inputs: BusinessGraphInput[] = catalog.groups.flatMap(group => group.fields.filter(field => !field.hidden).map(field => ({
    id: `input:${field.key}`, key: field.key, groupId: group.id, field,
    value: values[field.key] ?? null, applicability: applicability(field.condition, conditionValues), condition: field.condition,
    prerequisiteKeys: [...new Set((field.condition?.all ?? []).map(term => term.field))],
    subfields: (field.columnFields ?? []).map(column => ({ id: `subfield:${field.key}.${column.key}`, key: column.key, parentKey: field.key, field: column })),
    actionIds: [], potentialTargets: field.affects ?? []
  })))
  const identityFields = [...catalog.groups.flatMap(group => group.fields), ...(catalog.systemFields ?? [])]
  const planStatus: BusinessGraphPlanStatus = !plan ? 'unavailable' : plan.ruleVersion === catalog.ruleVersion && identityFields.every(field => JSON.stringify(identityValue(plan.inputValues[field.key], field)) === JSON.stringify(identityValue(values[field.key], field))) ? 'current' : 'stale'
  const actions: BusinessGraphAction[] = planStatus === 'current' ? (plan?.actions ?? []).map(result => ({
    id: result.id, document: result.document, clause: result.clause, paragraphs: result.paragraphs,
    inputKeys: [...new Set(result.inputKeys ?? result.fieldKeys ?? [])], result
  })) : []
  const edges: BusinessGraphEdge[] = []
  const inputKeys = new Set(inputs.map(input => input.key))
  const fileKeys: BusinessGraphFileKey[] = ['NTT', 'SCT', 'SCC']
  for (const input of inputs) {
    edges.push({ id: `membership:${input.groupId}:${input.key}`, from: `group:${input.groupId}`, to: input.id, kind: 'membership' })
    for (const subfield of input.subfields) edges.push({ id: `structure:${subfield.id}`, from: input.id, to: subfield.id, kind: 'structure' })
    for (const [index, term] of (input.condition?.all ?? []).entries()) if (inputKeys.has(term.field)) edges.push({ id: `prerequisite:${input.key}:${index}`, from: `input:${term.field}`, to: input.id, kind: 'prerequisite', conjunction: 'all' })
    for (const [index, target] of input.potentialTargets.entries()) if (fileKeys.includes(target.document as BusinessGraphFileKey)) edges.push({ id: `potential:${input.key}:${index}`, from: input.id, to: `file:${target.document}`, kind: 'potential' })
    input.actionIds = actions.filter(action => action.inputKeys.includes(input.key)).map(action => action.id)
    for (const actionId of input.actionIds) edges.push({ id: `dependency:${input.key}:${actionId}`, from: input.id, to: actionId, kind: 'dependency' })
  }
  const files: BusinessGraphFile[] = fileKeys.map(key => ({
    id: `file:${key}`, key, actionIds: actions.filter(action => action.document === key).map(action => action.id),
    inputKeys: inputs.filter(input => input.potentialTargets.some(target => target.document === key) || input.actionIds.some(id => actions.find(action => action.id === id)?.document === key)).map(input => input.key)
  }))
  for (const action of actions) if (fileKeys.includes(action.document as BusinessGraphFileKey)) edges.push({ id: `destination:${action.id}`, from: action.id, to: `file:${action.document}`, kind: 'destination' })
  const groups = catalog.groups.map(group => {
    const members = inputs.filter(input => input.groupId === group.id), actionIds = [...new Set(members.flatMap(input => input.actionIds))]
    return { id: group.id, label: group.label, inputKeys: members.map(input => input.key), actionIds, fileKeys: fileKeys.filter(key => members.some(input => input.potentialTargets.some(target => target.document === key)) || actions.some(action => action.document === key && actionIds.includes(action.id))) }
  })
  return { ruleVersion: catalog.ruleVersion, planStatus, groups, inputs, actions, files, edges }
}
