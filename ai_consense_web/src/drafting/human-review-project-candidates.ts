import type { ProjectExampleAnswers } from './human-review-integration'

export const PROJECT_SAMPLE_SOURCE_NAME = '虚构会议纪要（交互示例）'
export const PROJECT_SAMPLE_SOURCE_TEXT = '本次招标采用联合合约。工程范围包括基础工程。合约工期42个月。'
export type FactKey = 'combined' | 'foundations' | 'months'
export interface ProjectCandidate {
  id: FactKey; key: FactKey; label: string; type: 'boolean' | 'number'
  value: string; quote: string; sourceStart: number; sourceEnd: number
  issue: string; origin: 'extracted' | 'corrected'
}
export interface AdoptedProjectFact {
  value: string; quote: string; origin: 'candidate' | 'manual'; reason: string; reviewRequired: boolean
}
export interface CandidateReviewState {
  compoundSplit: boolean
  compoundCandidate: { label: string; quote: string }
  sourceChanged: boolean
  run: number
  candidates: Record<FactKey, ProjectCandidate>
  adopted: Partial<Record<FactKey, AdoptedProjectFact>>
}
const keys: FactKey[] = ['combined', 'foundations', 'months']
const labels: Record<FactKey, string> = { combined: '采用联合合约', foundations: '包括基础工程', months: '合约工期（月）' }
const quotes: Record<FactKey, string> = { combined: '本次招标采用联合合约。', foundations: '工程范围包括基础工程。', months: '合约工期42个月。' }

function normalized(key: FactKey, value: string | number): string {
  if (!keys.includes(key) || !['string', 'number'].includes(typeof value)) throw Error('不支持的项目值。 / Unsupported project value.')
  const text = String(value).trim()
  if (!text) return ''
  if (key !== 'months') {
    if (text !== 'yes' && text !== 'no') throw Error('请选择是、否或尚未知。 / Choose Yes, No or unknown.')
    return text
  }
  const number = Number(text)
  if (!Number.isFinite(number) || number < 0) throw Error('工期须为有限的非负数。 / Duration must be finite and non-negative.')
  return String(number)
}

// shortcut: these evidence checks cover one explicitly fictional record; real extraction remains in Drafting.
function candidate(key: FactKey, value: string, quote: string, origin: ProjectCandidate['origin']): ProjectCandidate {
  if (!quote.trim() || !PROJECT_SAMPLE_SOURCE_TEXT.includes(quote)) throw Error('引文必须来自这份虚构会议纪要。 / Quote must come from the fictional minutes.')
  const sourceStart = PROJECT_SAMPLE_SOURCE_TEXT.indexOf(quote)
  let issue = ''
  try {
    value = normalized(key, value)
    if (!value) issue = '尚未知，不能采用。 / Unknown; cannot adopt.'
    else if (key === 'months' ? value !== '42' || !quote.includes('42个月') : value !== 'yes' || !quote.includes(key === 'combined' ? '联合合约' : '基础工程')) {
      issue = '演示候选与本条原文或归属不符，请纠错；手工决定需另填理由。 / Demonstration candidate does not match this passage or fact; correct it or give a reason for a manual value.'
    }
  } catch { issue = '候选类型与归属不符，请纠错。 / Candidate type does not match its fact; correct it.' }
  return { id: key, key, label: labels[key], type: key === 'months' ? 'number' : 'boolean', value, quote, sourceStart, sourceEnd: sourceStart + quote.length, issue, origin }
}

function suggestions(split: boolean, months: string): Record<FactKey, ProjectCandidate> {
  return { combined: candidate('combined', split ? 'yes' : '', quotes.combined, 'extracted'),
    foundations: candidate('foundations', split ? 'yes' : '', quotes.foundations, 'extracted'),
    months: candidate('months', months, quotes.months, 'extracted') }
}

function replaceCandidates(state: CandidateReviewState, candidates: Record<FactKey, ProjectCandidate>): CandidateReviewState {
  const next = structuredClone(state)
  for (const key of keys) {
    const previous = state.candidates[key], current = candidates[key]
    if (next.adopted[key] && (previous.value !== current.value || previous.quote !== current.quote)) next.adopted[key]!.reviewRequired = true
  }
  next.candidates = structuredClone(candidates)
  return next
}

export function newCandidateReview(): CandidateReviewState {
  return { compoundSplit: false, compoundCandidate: { label: '联合合约且包括基础工程（合取项，待人工拆分）', quote: quotes.combined + quotes.foundations },
    sourceChanged: false, run: 1, candidates: suggestions(false, '39'), adopted: {} }
}

export function splitCombinedCandidate(state: CandidateReviewState): CandidateReviewState {
  if (state.sourceChanged) throw Error('来源已变化，请先重新识别。 / Source changed; identify again first.')
  if (state.compoundSplit) return state
  const split = suggestions(true, state.candidates.months.value)
  const next = replaceCandidates(state, { ...split, months: state.candidates.months })
  next.compoundSplit = true
  return next
}

export function correctCandidate(state: CandidateReviewState, key: FactKey, value: string | number, quote: string): CandidateReviewState {
  const corrected = candidate(key, normalized(key, value), quote, 'corrected')
  return replaceCandidates(state, { ...state.candidates, [key]: corrected })
}

export function retargetCandidate(state: CandidateReviewState, fromKey: FactKey, toKey: FactKey): CandidateReviewState {
  if (!keys.includes(fromKey) || !keys.includes(toKey)) throw Error('未知归属。 / Unknown fact.')
  if (fromKey === toKey) return state
  const from = state.candidates[fromKey], to = state.candidates[toKey]
  return replaceCandidates(state, { ...state.candidates,
    [toKey]: candidate(toKey, from.value, from.quote, 'corrected'),
    [fromKey]: candidate(fromKey, to.value, to.quote, 'corrected') })
}

function requireAdoptable(state: CandidateReviewState, key: FactKey): void {
  if (state.sourceChanged) throw Error('来源已变化，请重新识别并复核。 / Source changed; identify and review again.')
  if (key !== 'months' && !state.compoundSplit) throw Error('请先把合取项拆成两个独立事实。 / Split the compound item into two facts first.')
}

export function adoptCandidate(state: CandidateReviewState, key: FactKey): CandidateReviewState {
  requireAdoptable(state, key)
  const selected = state.candidates[key], checked = candidate(key, selected.value, selected.quote, selected.origin)
  if (checked.issue) throw Error(checked.issue)
  const next = structuredClone(state)
  next.adopted[key] = { value: checked.value, quote: checked.quote, origin: 'candidate', reason: '', reviewRequired: false }
  return next
}

export function adoptManual(state: CandidateReviewState, key: FactKey, value: string | number, reason: string): CandidateReviewState {
  requireAdoptable(state, key)
  const accepted = normalized(key, value)
  if (!accepted || !reason.trim()) throw Error('手工采用须有有效值和理由。 / A manual adoption needs a valid value and a reason.')
  const next = structuredClone(state)
  next.adopted[key] = { value: accepted, quote: '', origin: 'manual', reason: reason.trim(), reviewRequired: false }
  return next
}

export function withdrawAdoption(state: CandidateReviewState, key: FactKey): CandidateReviewState {
  const next = structuredClone(state)
  delete next.adopted[key]
  return next
}

export function rerunCandidates(state: CandidateReviewState): CandidateReviewState {
  const next = replaceCandidates(state, suggestions(state.compoundSplit, '42'))
  next.sourceChanged = false
  next.run++
  return next
}

export function markSourceChanged(state: CandidateReviewState): CandidateReviewState {
  const next = structuredClone(state)
  next.sourceChanged = true
  for (const key of keys) if (next.adopted[key]) next.adopted[key]!.reviewRequired = true
  return next
}

export function acceptedProjectAnswers(state: CandidateReviewState): ProjectExampleAnswers {
  const value = (key: FactKey) => {
    const accepted = state.adopted[key]
    return !state.sourceChanged && accepted && !accepted.reviewRequired && (key === 'months' || state.compoundSplit) ? accepted.value : ''
  }
  const binary = (key: 'combined' | 'foundations') => { const answer = value(key); return answer === 'yes' || answer === 'no' ? answer : '' }
  return { combined: binary('combined'), foundations: binary('foundations'), months: value('months') }
}
