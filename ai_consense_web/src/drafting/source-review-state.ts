import type { ReviewAnchor, ReviewGraph, ReviewHead, ReviewObject, ReviewReading, ReviewSpan, ReviewState, ReviewVariable } from './source-review-types'
import { isSourceCheck, valueDependencies } from './source-review/value-dependencies.js'

// Retain unsaved source work through project switches and locale remounts.
type ReviewDraft = { graph: ReviewGraph; head: ReviewHead; dirty: boolean; selectedId: string; sourceId: string; form: ReviewObject; formBase: string; formOpen: boolean; split?: { names: ReviewObject; first?: ReviewAnchor; second?: ReviewAnchor } }
const draftStorage = globalThis as typeof globalThis & { __consenseSourceDrafts?: Map<string, ReviewDraft>; __consenseCandidateDrafts?: Map<string, { form: ReviewObject; formBase: string; editing: string }> }
export const sourceReviewDrafts = draftStorage.__consenseSourceDrafts ??= new Map<string, ReviewDraft>()

export const candidateReviewDrafts = draftStorage.__consenseCandidateDrafts ??= new Map<string, { form: ReviewObject; formBase: string; editing: string }>()

export function emptyReviewState(): ReviewState { return { variables: [], sourceReviews: {}, events: [], nextId: 1, blank: true, guidanceMode: 'recognized' } }
export function nativeAnchor(reading: ReviewReading, paragraphId: string, start: number, end: number): ReviewAnchor {
  const paragraph = reading.paragraphs.find(item => item.id === paragraphId)
  if (!paragraph || paragraph.sourceSha256 !== reading.sourceSha256 || !paragraph.paragraphSha256 || !Number.isInteger(start) || !Number.isInteger(end) || start < 0 || end <= start || end > paragraph.text.length) throw Error('原文选区已变化，请重新选择。 / Source selection changed; select it again.')
  const expectedText = paragraph.text.slice(start, end)
  return { docId: String(reading.id), sourceSha256: reading.sourceSha256, text: expectedText, native: { paragraphId, paragraphSha256: paragraph.paragraphSha256, start, end, expectedText, sourceSha256: reading.sourceSha256, channel: 'editableText' } }
}
export function nativeRangeAnchor(reading: ReviewReading, paragraphs: HTMLElement[], range: Range): ReviewAnchor | undefined {
  const intersected = paragraphs.filter(paragraph => range.intersectsNode(paragraph))
  if (range.collapsed || !intersected.some(paragraph => paragraph.contains(range.startContainer)) || !intersected.some(paragraph => paragraph.contains(range.endContainer))) return
  const offset = (paragraph: HTMLElement, container: Node, at: number) => {
    const prefix = paragraph.ownerDocument.createRange()
    prefix.selectNodeContents(paragraph); prefix.setEnd(container, at)
    return prefix.toString().length
  }
  let result: ReviewAnchor | undefined
  for (const paragraph of intersected) {
    const id = paragraph.dataset.nativeId!, original = reading.paragraphs.find(item => item.id === id)
    if (!original || paragraph.textContent !== original.text) throw Error('原文选区已变化，请重新选择。 / Source selection changed; select it again.')
    const start = paragraph.contains(range.startContainer) ? offset(paragraph, range.startContainer, range.startOffset) : 0
    const end = paragraph.contains(range.endContainer) ? offset(paragraph, range.endContainer, range.endOffset) : original.text.length
    if (end <= start) continue // A DOM boundary or empty prefix is not selected source text.
    const anchor = nativeAnchor(reading, id, start, end)
    result = result ? appendNativeSelection(result, anchor) : anchor
  }
  return result
}
export function observedAnchor(reading: ReviewReading, blockId: string, start: number, end: number): ReviewAnchor {
  const block = reading.blocks.find(item => String(item.id ?? item.blockId) === blockId)
  const text = String(block?.originalText ?? block?.text ?? block?.effectiveText ?? '')
  if (!block || !reading.sourceSha256 || !Number.isInteger(start) || !Number.isInteger(end) || start < 0 || end <= start || end > text.length) throw Error('来源观察选区已变化。 / Source observation changed.')
  const expectedText = text.slice(start, end)
  return { docId: String(reading.id), sourceSha256: reading.sourceSha256, text: expectedText, observation: { blockId, channel: 'text', start, end, expectedText, sourceSha256: reading.sourceSha256 } }
}
export function appendNativeSelection(target: ReviewAnchor, selection: ReviewAnchor): ReviewAnchor {
  if (target.docId !== selection.docId || (target.sourceSha256 ?? target.nativeBinding?.sourceSha256) !== selection.sourceSha256 || !selection.native) throw Error('只能追加同一原件版本的准确Word选区。 / Append an exact Word selection from the same source edition.')
  const parts = (anchor: ReviewAnchor): ReviewSpan[] => anchor.native ? 'spans' in anchor.native ? anchor.native.spans : [anchor.native] : anchor.nativeBinding?.status === 'bound' ? anchor.nativeBinding.spans : []
  const spans = [...parts(target), ...parts(selection)]
  if (!parts(target).length || spans.some(span => span.operation === 'insert') || spans.some((span, index) => spans.slice(index + 1).some(other => span.paragraphId === other.paragraphId && span.start < other.end && other.start < span.end))) throw Error('选区重叠、包含插入点或原目标未绑定，请分别选择。 / Selections overlap, contain an insertion anchor or the original target is unbound.')
  const result: ReviewAnchor = { ...target, sourceSha256: selection.sourceSha256, native: { spans }, text: spans.map(span => span.expectedText).join('\n') }
  delete result.nativeBinding; delete result.needsRebind; delete result.replacementPlacement
  return result
}
export function reviewProblem(item: ReviewVariable, state: ReviewState): string | null {
  if (!item.nameZh?.trim() || !item.nameEn?.trim()) return '请补齐中英文名称。 / Supply both names.'
  const manualDefinition = item.definitionOrigin?.kind === 'manual' && item.definitionOrigin.reason?.trim() && item.role?.kind === 'input' && !item.correction && !item.editTargets?.length
  if (!manualDefinition && (item.missingSource || !item.sourceAnchors?.length && !item.sourceUnitIds?.length)) return '请先关联准确原文，或为独立人工变量填写新增理由。 / Link the exact source, or give a reason for an independent manual variable.'
  if (item.catalogAlignment && (!item.catalogAlignment.confirmed || item.catalogAlignment.status === 'ambiguous')) return '请先完成人工变量对齐审核。 / Complete the variable alignment review.'
  if (['choice','multiselect'].includes(item.type.kind) && (!item.options?.length || new Set(item.options.map(option=>String(option.value))).size!==item.options.length)) return '请填写不重复的选项。 / Supply unique options.'
  if (item.correction?.needsRebind || item.editTargets?.some(target => target.needsRebind || target.nativeBinding && target.nativeBinding.status !== 'bound')) return '关联或目标待重新核对。 / A binding or target needs review.'
  const byId = new Map(state.variables.map(value => [value.id, value]))
  for (const target of item.editTargets ?? []) {
    const spans = targetSpans(target)
    if (['fill', 'replace'].includes(target.action) && spans.length > 1 && (!Number.isInteger(target.replacementPlacement?.spanIndex) || target.replacementPlacement.spanIndex < 0 || target.replacementPlacement.spanIndex >= spans.length)) return '请明确新文字放在哪个选区；其他已选区将清空。 / Choose the replacement span; the other selected spans will be cleared.'
    if (target.action === 'insert' && (spans.length !== 1 || spans[0]!.operation !== 'insert' || spans[0]!.start !== spans[0]!.end || spans[0]!.expectedText !== '')) return '插入须绑定原件的准确插入点。 / Insertion needs an exact source insertion anchor.'
    if (['fill', 'replace', 'delete', 'unused', 'not_used'].includes(target.action) && spans.some(span => span.operation === 'insert' || span.start >= span.end)) return '插入点只能用于插入操作，不能替换或删除原文。 / An insertion anchor only permits insertion, not replacement or deletion.'
    if ([target.valueSourceId, target.listId, target.itemKey].some(id => id && isSourceCheck(byId.get(id)))) return '来源核对事项不能提供修改文字、清单或明细匹配值，请选择业务变量。 / A source check cannot supply amendment text, a list or an item match; choose a business variable.'
  }
  let parent = item.parentId ? byId.get(item.parentId) : undefined
  if (item.parentId && !parent) return '明细所属关系无效。 / Invalid detail membership.'
  const seen = new Set([item.id])
  while (parent) { if (seen.has(parent.id) || parent.status === 'excluded' || isSourceCheck(parent)) return '明细所属关系无效。 / Invalid detail membership.'; seen.add(parent.id); parent = parent.parentId ? byId.get(parent.parentId) : undefined }
  if (item.role?.kind === 'fixed' && !item.fixedValue?.trim()) return '请填写固定内容。 / Supply fixed wording.'
  const split = item.splitReview ?? (item.correction?.kind === 'split' ? item.correction : undefined)
  if (split && !['all', 'any'].includes(split.mode)) return '拆分后的关系仍待核对。 / Review the relationship between the split variables.'
  const branches = item.correction?.branches ?? (item.correction?.kind === 'rule' && item.correction.clauses?.length ? [item.correction] : [])
  for (const branch of branches) {
    if (!['all', 'any'].includes(branch.mode) || !branch.clauses?.length) return '请明确条件的共同判断方式。 / Complete the conditions.'
    for (const id of [...branch.clauses.map((clause: ReviewObject) => clause.inputId), ...(branch.scopeId ? [branch.scopeId] : [])]) {
      if (!byId.has(id) || byId.get(id)?.status === 'excluded') return '条件引用的变量不存在或已排除，请重新选择。 / A condition variable is missing or excluded; choose it again.'
      if (isSourceCheck(byId.get(id))) return '来源核对事项没有业务答案，不能用作条件；请明确建立独立变量。 / A source check has no business answer and cannot supply a condition; define an independent variable.'
    }
    if (item.role?.kind === 'derived' && branch.resultValue === undefined) return '请填写条件对应的结果。 / Supply each conditional result.'
  }
  const variables = state.variables.map(value => value.id === item.id ? item : value)
  if (valueDependencies({ ...state, variables }).some(edge => edge.cyclic && (edge.from === item.id || edge.to === item.id))) return '派生答案存在循环依赖，请核对取值规则及明细所属关系。 / Derived answers have a cyclic value dependency; review the value rules and record membership.'
  return null
}
export function targetSpans(target: ReviewObject): ReviewSpan[] { return target.native ? 'spans' in target.native ? target.native.spans : [target.native] : target.nativeBinding?.status === 'bound' ? target.nativeBinding.spans : [] }
