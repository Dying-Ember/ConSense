import type { DraftCandidate, DraftCandidateIdentity, DraftCondition, DraftDocument, DraftField, DraftPlanAction, DraftTargetOverride, DraftUnresolved, DraftVariable, DraftVariablePatch } from '@/api/types'
import { isCollectionKind, isObjectKind } from './field-kinds'
import type { BodyDraft } from './body-edit'

export type DraftValue = string | number | boolean | null | DraftValue[] | { [key: string]: DraftValue }
export type Applicability = 'yes' | 'no' | 'unknown'
export type DraftViewState = { step: 'inputs' | 'variables' | 'preview'; activeFile: string; review?: { layout: 'list' | 'focus'; groupId: string }; reading?: { fileKey: string; selectedKey: string; selectedActionId: string } }
export const projectDrafts = new Map<string, { values: Record<string, DraftValue>; baseline: Record<string, string>; documents?: Record<string, { content: string; baseline: string; body?: BodyDraft }>; view?: DraftViewState }>()

/** Stale snapshots remain readable, but the server requires a current draft for edits and binary output. */
export function documentCapabilities(document: Pick<DraftDocument, 'generated' | 'stale'> | undefined, contentDirty: boolean) {
  const generated = !!document?.generated
  const current = generated && !document?.stale
  return { readable: generated, editable: current, previewable: current, exportable: current && !contentDirty }
}

/** Language remounts restore the same project's place, but a project without a generated draft cannot restore preview. */
export function restoreDraftView(view: DraftViewState | undefined, documents: Pick<DraftDocument, 'fileKey' | 'generated'>[]): DraftViewState {
  const activeFile = ['NTT', 'SCT', 'SCC'].includes(view?.activeFile ?? '') ? view!.activeFile : 'NTT'
  const step = view?.step === 'preview' && !documents.some(document => document.generated) ? 'variables' : view?.step ?? 'inputs'
  const reading = view?.reading && ['NTT', 'SCT', 'SCC'].includes(view.reading.fileKey) ? { ...view.reading } : undefined
  const review = view?.review && ['list', 'focus'].includes(view.review.layout) ? { ...view.review } : undefined
  return { step, activeFile, ...(reading ? { reading } : {}), ...(review ? { review } : {}) }
}

export function decode(field: Pick<DraftField, 'kind'>, raw?: string): DraftValue {
  if (raw === undefined || raw === null || raw === '') return null
  if (isCollectionKind(field.kind) || isObjectKind(field.kind)) {
    try { return JSON.parse(raw) as DraftValue } catch { return raw }
  }
  return raw
}

export function encode(field: Pick<DraftField, 'kind'>, value: DraftValue | undefined): string {
  if (value === undefined || value === null) return ''
  if (isCollectionKind(field.kind) || isObjectKind(field.kind)) return typeof value === 'string' ? value : JSON.stringify(value)
  return String(value)
}

export function clone<T>(value: T): T { return JSON.parse(JSON.stringify(value)) as T }

export function hasAnswer(value: unknown): boolean {
  if (value === null || value === undefined) return false
  if (typeof value === 'string') return value.trim() !== '' && !['unknown', '待确定', '待决定', '待补充'].includes(value.trim())
  if (typeof value === 'number') return Number.isFinite(value)
  if (Array.isArray(value)) return value.every(hasAnswer)
  if (typeof value === 'object') return Object.keys(value).length > 0 && Object.values(value).every(hasAnswer)
  return true
}

function canonical(value: unknown): unknown {
  return value === true || value === 'true' || value === '是' ? true : value === false || value === 'false' || value === '否' ? false : value
}

export function applicability(condition: DraftCondition | undefined, values: Record<string, DraftValue>): Applicability {
  if (!condition?.all?.length) return 'yes'
  const results = condition.all.map(term => {
    const actual = values[term.field]
    if (term.operator === 'unknown') return hasAnswer(actual) ? 'no' : 'yes'
    if (term.operator === 'in') return !hasAnswer(actual) ? 'unknown' : Array.isArray(term.value) && term.value.some(value => canonical(value) === canonical(actual)) ? 'yes' : 'no'
    if (term.operator === 'includesComponent') {
      if (!Array.isArray(actual)) return 'unknown'
      if (actual.some(row => row && typeof row === 'object' && !Array.isArray(row) && row.component === term.value)) return 'yes'
      return actual.some(row => !row || typeof row !== 'object' || Array.isArray(row) || !hasAnswer(row.component)) ? 'unknown' : 'no'
    }
    if (term.operator === 'countGt') {
      if (!Array.isArray(actual)) return 'unknown'
      const identifiers = actual.map(row => row && typeof row === 'object' && !Array.isArray(row) ? String(row.serial ?? row.number ?? '').trim().toUpperCase() : String(row ?? '').trim().toUpperCase())
      if (identifiers.some(id => !id) || new Set(identifiers).size !== identifiers.length) return 'unknown'
      return actual.length > Number(term.value) ? 'yes' : 'no'
    }
    if (!hasAnswer(actual)) return 'unknown'
    if (term.operator === 'includes') return Array.isArray(actual) ? (actual.some(value => canonical(value) === canonical(term.value)) ? 'yes' : 'no') : 'unknown'
    if (term.operator === 'gt') return Number.isFinite(Number(actual)) ? (Number(actual) > Number(term.value) ? 'yes' : 'no') : 'unknown'
    return canonical(actual) === canonical(term.value) ? 'yes' : 'no'
  })
  return results.includes('no') ? 'no' : results.includes('unknown') ? 'unknown' : 'yes'
}

export function validationIssue(field: DraftField, value: DraftValue | undefined): 'missing' | 'incomplete' | 'duplicate' | 'negative' | 'invalid' | null {
  if (value === undefined || value === null || value === '') return field.optional ? null : 'missing'
  if (field.kind === 'number') return Number.isFinite(Number(value)) ? Number(value) < 0 ? 'negative' : null : 'invalid'
  if (field.kind === 'date') {
    const raw = String(value)
    if (!/^\d{4}-\d{2}-\d{2}$/.test(raw)) return 'invalid'
    const parsed = new Date(`${raw}T00:00:00Z`)
    return Number.isFinite(parsed.getTime()) && parsed.toISOString().slice(0, 10) === raw ? null : 'invalid'
  }
  if (field.options?.length && !Array.isArray(value) && !field.options.some(option => String(option.value) === String(value))) return 'invalid'
  if (isCollectionKind(field.kind)) {
    if (!Array.isArray(value)) return 'invalid'
    if (!value.length) return null // Explicit empty differs from unanswered.
    if (field.kind === 'multiselect') return value.every(item => !field.options?.length || field.options.some(option => String(option.value) === String(item))) ? null : 'invalid'
    if (!field.columnFields?.length) return value.every(hasAnswer) ? null : 'incomplete'
    const required = field.columnFields.filter(column => !column.optional && column.key !== 'id')
    if (value.some(row => !row || typeof row !== 'object' || Array.isArray(row) || required.some(column => applicability(column.condition, row as Record<string, DraftValue>) !== 'no' && validationIssue(column, row[column.key]) !== null))) return 'incomplete'
    const identity = field.columnFields.find(column => ['serial', 'number'].includes(column.key))
    if (identity) {
      const identities = value.map(row => {
        const record = row as Record<string, DraftValue>
        const number = String(record[identity.key] ?? '').trim().toUpperCase()
        return field.key === 'billNos' ? `${String(record.type ?? '').trim().toUpperCase()}:${number}` : number
      })
      if (new Set(identities).size !== identities.length) return 'duplicate'
    }
    return null
  }
  if (isObjectKind(field.kind)) {
    if (!value || typeof value !== 'object' || Array.isArray(value)) return 'invalid'
    return (field.columnFields ?? []).filter(column => !column.optional).some(column => validationIssue(column, value[column.key]) !== null) ? 'incomplete' : null
  }
  return hasAnswer(value) ? null : field.optional ? null : 'missing'
}

/** A refresh updates server metadata, while unrelated local edits survive. */
export function mergeServerValues(fields: DraftField[], incoming: DraftVariable[], local: Record<string, DraftValue>, baseline: Record<string, string>) {
  const nextLocal: Record<string, DraftValue> = {}
  const nextBaseline: Record<string, string> = {}
  const byKey = new Map(incoming.map(variable => [variable.key, variable]))
  for (const field of fields) {
    const serverRaw = byKey.get(field.key)?.value ?? ''
    const wasDirty = field.key in local && encode(field, local[field.key]) !== (baseline[field.key] ?? '')
    nextLocal[field.key] = wasDirty ? clone(local[field.key]!) : decode(field, serverRaw)
    nextBaseline[field.key] = serverRaw
  }
  return { values: nextLocal, baseline: nextBaseline }
}

/** Passing untouched suggestions as plan patches would silently adopt them. */
export function dirtyPatch(fields: DraftField[], local: Record<string, DraftValue>, baseline: Record<string, string>): Record<string, string> {
  return Object.fromEntries(fields.filter(field => encode(field, local[field.key]) !== (baseline[field.key] ?? '')).map(field => [field.key, encode(field, local[field.key])]))
}

export function reversedSiteDates(values: Record<string, DraftValue>): boolean {
  const start = values.siteInspectionStartDate, end = values.siteInspectionEndDate
  return typeof start === 'string' && typeof end === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(start) && /^\d{4}-\d{2}-\d{2}$/.test(end) && start > end
}

export function actionForUnresolved(issue: DraftUnresolved, actions: DraftPlanAction[]): DraftPlanAction | undefined {
  const actionId = issue.actionId ?? (issue.kind === 'SourceTarget' && issue.id.startsWith('application-') ? issue.id.slice('application-'.length) : issue.id)
  return actions.find(action => action.id === actionId)
}

function candidateIdentity(candidate: DraftCandidate): DraftCandidateIdentity {
  return { value: candidate.value, sourceDocumentId: candidate.sourceDocumentId ?? null, sourceHash: candidate.sourceHash ?? null, sourceQuote: candidate.sourceQuote ?? null }
}
export function adoptionPatch(raw: string, baseline: string, reviewRequired: boolean, candidateIndex?: number, displayed?: Pick<DraftVariable, 'source' | 'candidates'>): DraftVariablePatch {
  if (candidateIndex !== undefined) {
    const candidate = displayed?.candidates?.[candidateIndex]
    if (!candidate) throw new Error('Candidate no longer exists. Refresh the input before adopting it.')
    return { candidateIndex, candidateSnapshot: candidateIdentity(candidate) }
  }
  if (raw !== baseline) return { value: raw, reviewed: true }
  if (!displayed) throw new Error('Suggestion evidence is unavailable. Refresh the input before adopting it.')
  const suggestionSnapshot = { value: baseline, source: displayed.source ?? null, candidates: (displayed.candidates ?? []).map(candidateIdentity), reviewRequired }
  return reviewRequired ? { reviewed: true, confirmed: true, suggestionSnapshot } : { confirmed: true, suggestionSnapshot }
}

/** Re-adopting one target binds that target to the current source; other targets retain their source revisions. */
export function replaceTargetOverride(list: DraftTargetOverride[], actionId: string, replacement?: DraftTargetOverride): DraftTargetOverride[] {
  const retained = list.filter(item => item.actionId !== actionId)
  return replacement ? [...retained, replacement] : retained
}
