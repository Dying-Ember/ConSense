/** This prototype evaluates only the recorded three-input bond example. */
export type ExampleAnswer = '' | 'yes' | 'no'
export interface ProjectExampleAnswers { combined: ExampleAnswer; foundations: ExampleAnswer; months: string | number }
type JsonObject = Record<string, unknown>
export interface ReviewSnapshot {
  pack: { kind: 'consense-review-v1'; sourceKey: string; state: JsonObject & { variables: JsonObject[] } }
  summary: { records: number; active: number; unconfirmed: number }
  templates: JsonObject[]
}
const object = (value: unknown): value is JsonObject => value !== null && typeof value === 'object' && !Array.isArray(value)
export function readReviewSnapshot(value: unknown): ReviewSnapshot | null {
  if (!object(value) || !object(value.pack) || value.pack.kind !== 'consense-review-v1' || typeof value.pack.sourceKey !== 'string' || !value.pack.sourceKey || !object(value.pack.state)) return null
  const state = value.pack.state
  if (!Array.isArray(state.variables) || !state.variables.every(item => object(item) && typeof item.id === 'string' && typeof item.status === 'string') ||
      !object(state.sourceReviews) || !Array.isArray(state.events) || !Number.isSafeInteger(state.nextId) || typeof state.blank !== 'boolean' || typeof state.guidanceMode !== 'string') return null
  if (!object(value.summary) || !Array.isArray(value.templates) || value.templates.length !== 4) return null
  const documents = ['ntt', 'sct', 'scc-word', 'scc-pdf']
  const templates = value.templates
  if (!documents.every(id => templates.filter(item => object(item) && item.id === id).length === 1) ||
      !templates.every(item => object(item) && ['originalSha256', 'visualSha256', 'layoutSha256'].every(key => typeof item[key] === 'string' && /^[a-f0-9]{64}$/.test(item[key] as string)))) return null
  const records = state.variables.length
  const active = state.variables.filter(item => item.status !== 'excluded').length
  const unconfirmed = state.variables.filter(item => item.status !== 'excluded' && item.status !== 'confirmed').length
  if (value.summary.records !== records || value.summary.active !== active || value.summary.unconfirmed !== unconfirmed) return null
  return value as unknown as ReviewSnapshot
}

type Truth = 'yes' | 'no' | 'unknown'
export interface BondExample {
  status: 'resolved' | 'pending' | 'unavailable' | 'conflict'
  nameZh: string; nameEn: string; resultZh: string; resultEn: string; effectZh: string; effectEn: string
}
export function evaluateBondExample(snapshot: ReviewSnapshot | null, answers: ProjectExampleAnswers): BondExample {
  const empty: BondExample = { status: 'unavailable', nameZh: '担保书格式', nameEn: 'Bond appendix form', resultZh: '', resultEn: '', effectZh: '', effectEn: '' }
  if (!snapshot) return empty
  const variables = snapshot.pack.state.variables
  const owner = variables.find(item => item.id === 'review-8' && item.status !== 'excluded')
  const definitions = ['review-5', 'review-6', 'review-7'].map(id => variables.find(item => item.id === id && item.status !== 'excluded'))
  if (!owner || !definitions.every(Boolean) || !object(owner.role) || owner.role.kind !== 'derived' || !object(owner.type) || owner.type.kind !== 'choice' || !object(owner.correction) || owner.correction.needsRebind || !Array.isArray(owner.correction.branches) || !Array.isArray(owner.options)) return empty
  if (!definitions.every((item, index) => object(item?.type) && item.type.kind === (index === 2 ? 'number' : 'boolean'))) return empty
  const months = typeof answers.months === 'string' && answers.months.trim() === '' ? null : Number(answers.months)
  if (months !== null && (!Number.isFinite(months) || months < 0)) return empty
  const values: Record<string, string | number | null> = { 'review-5': answers.combined || null, 'review-6': answers.foundations || null, 'review-7': months }
  const truth = (clause: unknown): Truth | null => {
    if (!object(clause) || typeof clause.inputId !== 'string' || !(clause.inputId in values)) return null
    const actual = values[clause.inputId]
    if (!['yes', 'no', 'ge', 'lt'].includes(String(clause.operator))) return null
    if ((clause.operator === 'yes' || clause.operator === 'no') && clause.inputId === 'review-7' ||
        (clause.operator === 'ge' || clause.operator === 'lt') && clause.inputId !== 'review-7') return null
    if (actual === null) return 'unknown'
    if (clause.operator === 'yes' || clause.operator === 'no') return actual === clause.operator ? 'yes' : 'no'
    if (String(clause.value).trim() === '' || !Number.isFinite(Number(clause.value))) return null
    return (clause.operator === 'ge' ? Number(actual) >= Number(clause.value) : Number(actual) < Number(clause.value)) ? 'yes' : 'no'
  }
  const matches: JsonObject[] = []
  let hasUnknown = false
  for (const branch of owner.correction.branches) {
    // shortcut: only this unscoped scalar example is evaluated; add a real evaluator before executing other graph rules.
    if (!object(branch) || branch.scopeId || branch.scopeMode || !['all', 'any'].includes(String(branch.mode)) || !Array.isArray(branch.clauses) || !branch.clauses.length) return empty
    const terms = branch.clauses.map(truth)
    if (terms.includes(null)) return empty
    const result: Truth = branch.mode === 'all' ? terms.includes('no') ? 'no' : terms.includes('unknown') ? 'unknown' : 'yes' : terms.includes('yes') ? 'yes' : terms.includes('unknown') ? 'unknown' : 'no'
    if (result === 'yes') matches.push(branch)
    if (result === 'unknown') hasUnknown = true
  }
  if (matches.length > 1) return { ...empty, status: 'conflict' }
  if (!matches.length || hasUnknown) return { ...empty, status: 'pending' }
  const branch = matches[0]!
  const option = owner.options.find(item => object(item) && item.value === branch.resultValue)
  if (!object(option) || typeof option.labelZh !== 'string' || typeof option.labelEn !== 'string') return empty
  return { status: 'resolved', nameZh: String(owner.nameZh || empty.nameZh), nameEn: String(owner.nameEn || empty.nameEn), resultZh: option.labelZh, resultEn: option.labelEn, effectZh: String(branch.effectZh || ''), effectEn: String(branch.effectEn || '') }
}
