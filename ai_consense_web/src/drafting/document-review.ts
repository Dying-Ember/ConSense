import DOMPurify from 'dompurify'
import type { DraftCandidate, DraftDocument, DraftDocumentBinding, DraftDocumentBindings, DraftField, DraftPlanAction, ExtractContext, ExtractTrace } from '@/api/types'
import type { DocumentReading } from './document-reading'
import { documentParagraphText, walkParagraphText } from './document-reading-text'
import type { AppLocale } from '@/i18n'
import { localized } from './words'

export type DocumentReviewMode = 'original' | 'review' | 'saved'
export interface DocumentReviewLocation { id: string; fileKey: string; bindingId?: string; actionId?: string; clause: string; paragraph?: number; status?: string }
export interface PreparedDocumentReview { html: string; notices: string[]; bindingIds: string[]; savedBindingIds: string[] }
export interface ReviewEvidenceContext { partId: string; attemptIndex?: number; sourceText: string; context?: ExtractContext }
const normalized = (text: string | null | undefined) => (text ?? '').replace(/\s+/gu, ' ').trim()

/** Resolve recorded context through the extraction receipt, never by searching
 * another file for similar wording. Multiple accepted identities are ambiguous. */
export function reviewEvidenceContext(trace: ExtractTrace | null | undefined, fieldKey: string, candidate: DraftCandidate): ReviewEvidenceContext | undefined {
  const quote = candidate.sourceQuote
  if (!quote || !candidate.sourceHash || !candidate.fileName || candidate.sourceDocumentId === undefined || candidate.sourceDocumentId === null) return
  const parts = trace?.parts?.filter(part => part.sourceHash === candidate.sourceHash && part.fileName === candidate.fileName && part.sourceDocumentId !== null && String(part.sourceDocumentId) === String(candidate.sourceDocumentId)) ?? []
  const decisions = trace?.decisions?.filter(decision => decision.status === 'accepted' && decision.key === fieldKey && decision.sourceQuote === quote && decision.normalizedValue === candidate.value) ?? []
  if (trace?.decisions?.length && !decisions.length) return
  const contexts: ReviewEvidenceContext[] = decisions.length ? decisions.flatMap(decision => parts.filter(part => part.partId === decision.partId).flatMap(part => {
    if (!part.attempts?.length) return [{ partId: part.partId, sourceText: part.sourceText }]
    return part.attempts.filter(attempt => attempt.attemptIndex === decision.attemptIndex && ['completed', 'succeeded', 'success'].includes(attempt.status)).flatMap(attempt => {
      if (attempt.context) return attempt.context.sourceText ? [{ partId: part.partId, attemptIndex: attempt.attemptIndex, sourceText: attempt.context.sourceText, context: attempt.context }] : []
      return attempt.kind === 'recall' ? [] : [{ partId: part.partId, attemptIndex: attempt.attemptIndex, sourceText: part.sourceText }]
    })
  })) : parts.map(part => ({ partId: part.partId, sourceText: part.sourceText }))
  const matching = new Map(contexts.filter(context => context.sourceText.includes(quote)).map(context => [JSON.stringify([context.partId, context.attemptIndex, context.context?.sourceStart, context.context?.sourceEnd, context.sourceText]), context]))
  return matching.size === 1 ? matching.values().next().value : undefined
}

export function associatedReviewFields(binding: DraftDocumentBinding, fields: DraftField[], actions: DraftPlanAction[]): string[] {
  const keys = new Set(binding.fieldKeys)
  for (const action of actions.filter(item => binding.actionIds.includes(item.id))) for (const key of action.inputKeys ?? action.fieldKeys ?? []) keys.add(key)
  return fields.filter(field => keys.has(field.key)).map(field => field.key)
}
function changeAttribution(binding: DraftDocumentBinding, actions: DraftPlanAction[], fileKey: string, selectedKey: string) {
  if (binding.bodyOperationIds?.length || ['body_edited', 'body_added'].includes(binding.applicationStatus)) return 'manualSharedChange'
  const matches = (operation: string, actionId: string) => operation === actionId || operation.startsWith(`${actionId}-`)
  const applied = actions.filter(action => action.document === fileKey && binding.actionIds.includes(action.id) && binding.appliedActionIds?.includes(action.id) && binding.operationIds.some(operation => matches(operation, action.id)))
  if (!applied.length || binding.operationIds.some(operation => !applied.some(action => matches(operation, action.id)))) return 'diffUnverified'
  const keys = new Set(applied.flatMap(action => action.inputKeys ?? action.fieldKeys ?? []))
  if (!keys.has(selectedKey)) return 'unrelatedSharedChange'
  // The paragraph ledger has operation membership, not per-action character
  // ranges. Diffing a jointly amended paragraph would misattribute its tokens.
  return keys.size === 1 ? 'selected' : 'jointRangeAttribution'
}

interface TextPosition { node: Node; offset: number; endNode: Node; endOffset: number }
function textMap(element: HTMLElement) {
  const positions: TextPosition[] = []
  let text = '', space = false
  walkParagraphText(element, run => {
    const node = run.node
    for (let index = 0; index < run.text.length; index++) {
      const character = run.text[index]!, offset = run.isText ? index : run.startOffset, endOffset = run.isText ? index + 1 : run.endOffset
      if (/\s/u.test(character)) {
        if (!space) { text += ' '; positions.push({ node, offset, endNode: node, endOffset }) }
        else { const position = positions[positions.length - 1]!; position.endNode = node; position.endOffset = endOffset }
        space = true
      } else { text += character; positions.push({ node, offset, endNode: node, endOffset }); space = false }
    }
  })
  if (text.startsWith(' ')) { text = text.slice(1); positions.shift() }
  if (text.endsWith(' ')) { text = text.slice(0, -1); positions.pop() }
  return { text, positions }
}
function nativeRange(element: HTMLElement, from: number, to: number) {
  const { positions } = textMap(element), range = element.ownerDocument.createRange(), first = positions[from], last = positions[to - 1]
  if (from === to) {
    if (first) range.setStart(first.node, first.offset)
    else if (from === positions.length && positions.length) { const end = positions[positions.length - 1]!; range.setStart(end.endNode, end.endOffset) }
    else { range.selectNodeContents(element); range.collapse(true); return range }
    range.collapse(true)
  } else { if (!first || !last) return; range.setStart(first.node, first.offset); range.setEnd(last.endNode, last.endOffset) }
  return range
}
function extractFormattedRange(range: Range, paragraph: HTMLElement): DocumentFragment {
  // extractContents omits the common inline ancestor when both boundaries are
  // inside its text. Carry those saved inline wrappers into the inserted token.
  const wrappers: Element[] = []
  let ancestor = range.commonAncestorContainer.nodeType === 3 ? range.commonAncestorContainer.parentNode : range.commonAncestorContainer
  while (ancestor && ancestor !== paragraph) {
    if (ancestor.nodeType === 1) wrappers.push((ancestor as Element).cloneNode(false) as Element)
    ancestor = ancestor.parentNode
  }
  let fragment = range.extractContents()
  for (const wrapper of wrappers) { wrapper.append(fragment); fragment = paragraph.ownerDocument.createDocumentFragment(); fragment.append(wrapper) }
  return fragment
}
function markActualDiff(before: HTMLElement, after: HTMLElement): boolean {
  const a = textMap(before).text, b = textMap(after).text
  const tokenize = (text: string) => [...text.matchAll(/\p{L}[\p{L}\p{N}]*|\p{N}+|[^\p{L}\p{N}\s]|\s+/gu)].map(match => ({ text: match[0], start: match.index!, end: match.index! + match[0].length }))
  const old = tokenize(a), next = tokenize(b)
  if (old.length * next.length > 2000000) return false
  const lengths = Array.from({ length: old.length + 1 }, () => new Uint32Array(next.length + 1))
  for (let i = old.length - 1; i >= 0; i--) for (let j = next.length - 1; j >= 0; j--) lengths[i]![j] = old[i]!.text === next[j]!.text ? lengths[i + 1]![j + 1]! + 1 : Math.max(lengths[i + 1]![j]!, lengths[i]![j + 1]!)
  const matched: { old: number; next: number }[] = []
  let i = 0, j = 0
  while (i < old.length && j < next.length) {
    if (old[i]!.text === next[j]!.text) { matched.push({ old: i++, next: j++ }) }
    else if (lengths[i + 1]![j]! >= lengths[i]![j + 1]!) i++
    else j++
  }
  const hunks: { a1: number; a2: number; b1: number; b2: number }[] = []
  let a1 = 0, b1 = 0
  for (const match of [...matched, { old: old.length, next: next.length }]) {
    const a2 = old[match.old]?.start ?? a.length, b2 = next[match.next]?.start ?? b.length
    if (a2 > a1 || b2 > b1) hunks.push({ a1, a2, b1, b2 })
    a1 = old[match.old]?.end ?? a.length; b1 = next[match.next]?.end ?? b.length
  }
  for (const hunk of hunks.reverse()) {
    const oldRange = nativeRange(before, hunk.a1, hunk.a2), newRange = nativeRange(after, hunk.b1, hunk.b2)
    if (!oldRange || !newRange) return false
    const fragment = before.ownerDocument.createDocumentFragment()
    if (hunk.a2 > hunk.a1) { const del = before.ownerDocument.createElement('del'); del.className = 'review-deleted-text'; del.append(oldRange.extractContents()); fragment.append(del) }
    if (hunk.b2 > hunk.b1) { const ins = before.ownerDocument.createElement('ins'); ins.className = 'review-inserted-text'; ins.append(extractFormattedRange(newRange, after)); fragment.append(ins) }
    oldRange.insertNode(fragment)
  }
  return true
}

function verifiedIndex(host: HTMLElement, reading: DocumentReading) {
  const native = new Map(reading.paragraphs.map(paragraph => [paragraph.path, paragraph])), elements = new Map<string, HTMLElement>()
  for (const element of host.querySelectorAll<HTMLElement>('[data-native-paragraph]')) {
    const path = element.dataset.nativeParagraph ?? '', paragraph = native.get(path)
    if (!paragraph || elements.has(path) || normalized(documentParagraphText(element)) !== normalized(paragraph.htmlText ?? paragraph.text)) continue
    elements.set(path, element)
  }
  return { native, elements }
}
function scopes(text: string) {
  const ordinals = new Set<number>()
  for (const match of text.matchAll(/P(\d+)(?:\s*[–—-]\s*P?(\d+))?/g)) {
    const from = Number(match[1]), to = Number(match[2] ?? match[1])
    if (to >= from && to - from < 10000) for (let ordinal = from; ordinal <= to; ordinal++) ordinals.add(ordinal)
  }
  return ordinals
}
function matchedElement(index: ReturnType<typeof verifiedIndex>, path: string | null | undefined, text: string | null | undefined) {
  const paragraph = path && index.native.get(path), element = path && index.elements.get(path)
  return paragraph && element && normalized(paragraph.text) === normalized(text) ? element : undefined
}

/** Prepare only actual source/result changes. Current input values intentionally
 * have no place in this API: they cannot rewrite a revision-bound document. */
export function prepareDocumentReview(options: { fileKey: string; document?: DraftDocument; original?: DocumentReading; result?: DocumentReading; sourceBindings?: DraftDocumentBindings; resultBindings?: DraftDocumentBindings; fields: DraftField[]; actions: DraftPlanAction[]; selectedKey: string; mode: DocumentReviewMode; planCurrent?: boolean; locale?: AppLocale }): PreparedDocumentReview {
  const { fileKey, original, result, sourceBindings, resultBindings, document: revision } = options
  const notices = new Set<string>(), sourceHost = document.createElement('div'), resultHost = document.createElement('div')
  const sanitize = (html: string) => DOMPurify.sanitize(html, { ADD_TAGS: ['math', 'mrow', 'mi', 'mn', 'mo', 'mfrac', 'msup', 'msub', 'msubsup', 'msqrt', 'mroot', 'mtable', 'mtr', 'mtd', 'mtext'] })
  sourceHost.innerHTML = sanitize(original?.html ?? ''); resultHost.innerHTML = sanitize(result?.html ?? '')
  for (const root of [sourceHost, resultHost]) for (const element of root.querySelectorAll<HTMLElement>('*')) {
    const importedAnnotation = [...element.attributes].some(attribute => attribute.name.startsWith('data-review-'))
    for (const attribute of [...element.attributes]) if (attribute.name.startsWith('data-review-')) element.removeAttribute(attribute.name)
    for (const name of [...element.classList]) if (name.startsWith('review-')) element.classList.remove(name)
    if (importedAnnotation) { element.removeAttribute('tabindex'); element.removeAttribute('role') }
  }
  const host = options.mode === 'saved' ? resultHost : sourceHost
  if (!original && options.mode !== 'saved' || !result && options.mode === 'saved') return { html: '', notices: ['unavailable'], bindingIds: [], savedBindingIds: [] }
  // A stale saved revision can refer to an older template. The current source
  // remains readable against its own verified source receipt, independently.
  const sourceIdentity = !!original && !!sourceBindings && sourceBindings.fileKey === fileKey && original.docxSha256 === sourceBindings.sourceSha256 && (revision?.stale || !revision?.sourceSha256 || original.docxSha256 === revision.sourceSha256)
  const resultIdentity = !!result && !!resultBindings && resultBindings.fileKey === fileKey && result.docxSha256 === resultBindings.docxSha256 && (!revision?.docxSha256 || result.docxSha256 === revision.docxSha256) && (!revision?.revisionId || revision.revisionId === resultBindings.revisionId) && (!original || resultBindings.sourceSha256 === original.docxSha256)
  if ((options.mode !== 'saved' && sourceBindings && !sourceIdentity) || (options.mode === 'saved' && resultBindings && !resultIdentity)) return { html: '', notices: ['identityMismatch'], bindingIds: [], savedBindingIds: [] }
  if (options.mode === 'review' && (!resultIdentity || !resultBindings?.bindings.length)) notices.add('reviewUnavailable')
  const sourceIndex = original ? verifiedIndex(sourceHost, original) : undefined, resultIndex = result ? verifiedIndex(resultHost, result) : undefined
  const sources = sourceIdentity ? sourceBindings!.bindings : [], results = resultIdentity ? resultBindings!.bindings : []
  const sourceById = new Map(sources.map(binding => [binding.bindingId, binding]))
  const savedBindingIds = results.filter(binding => {
    const source = sourceById.get(binding.bindingId)
    if (binding.sourceParagraphId && (!sourceIndex || !source || binding.sourceParagraphId !== source.sourceParagraphId || normalized(binding.sourceText) !== normalized(source.sourceText) || !matchedElement(sourceIndex, source.sourceParagraphId, source.sourceText))) return false
    if (binding.locationStatus === 'removed') return !!binding.sourceParagraphId && !!binding.operationIds.length
    return binding.locationStatus === 'exact' && !!resultIndex && !!matchedElement(resultIndex, binding.resultParagraphId, binding.text)
  }).map(binding => binding.bindingId)
  if (options.mode === 'review' && resultIdentity && results.length && sources.some(binding => associatedReviewFields(binding, options.fields, options.actions).includes(options.selectedKey) && !savedBindingIds.includes(binding.bindingId))) notices.add('selectedMappingUnavailable')
  const bindingIds = new Set<string>()
  function interactive(element: HTMLElement, binding: DraftDocumentBinding) {
    const fieldKeys = associatedReviewFields(binding, options.fields, options.actions)
    if (!fieldKeys.length) return
    element.dataset.reviewBinding = binding.bindingId; element.dataset.reviewFields = fieldKeys.join(' ')
    element.setAttribute('tabindex', '0'); element.setAttribute('role', 'button')
    element.setAttribute('aria-label', fieldKeys.map(key => localized(options.fields.find(field => field.key === key)?.label, options.locale ?? 'en') || key).join(' / '))
    if (fieldKeys.includes(options.selectedKey)) element.classList.add('review-associated')
    bindingIds.add(binding.bindingId)
  }
  if (options.mode === 'saved' && resultIndex) for (const binding of results) {
    if (binding.locationStatus !== 'exact') continue
    const element = matchedElement(resultIndex, binding.resultParagraphId, binding.text)
    if (element) interactive(element, binding)
  }
  if (options.mode !== 'saved' && sourceIndex) for (const binding of sources) {
    if (binding.locationStatus !== 'exact') continue
    const element = matchedElement(sourceIndex, binding.sourceParagraphId, binding.sourceText)
    if (element) interactive(element, binding)
    else if (associatedReviewFields(binding, options.fields, options.actions).includes(options.selectedKey)) notices.add('mappingUnavailable')
  }
  if (options.mode !== 'review' || !sourceIndex || !resultIndex || !resultIdentity) return { html: host.innerHTML, notices: [...notices], bindingIds: [...bindingIds], savedBindingIds }
  for (const binding of results) {
    if (!associatedReviewFields(binding, options.fields, options.actions).includes(options.selectedKey)) continue
    const source = sourceById.get(binding.bindingId)
    if (!source || binding.sourceParagraphId !== source.sourceParagraphId) continue
    const element = matchedElement(sourceIndex, source.sourceParagraphId, source.sourceText)
    if (!element || normalized(binding.sourceText) !== normalized(source.sourceText)) continue
    const actions = options.actions.filter(action => action.document === fileKey && binding.actionIds.includes(action.id))
    const guidance = actions.some(action => /guidance/i.test(action.id) || scopes((action.paragraphs ?? '').split(/;\s*guidance\s*/i)[1] ?? '').has(source.sourceParagraphOrdinal ?? -1))
    if (binding.locationStatus === 'removed' && binding.operationIds.length) {
      const attribution = changeAttribution(binding, options.actions, fileKey, options.selectedKey)
      if (attribution !== 'selected') { notices.add(attribution); continue }
      element.classList.add(guidance ? 'review-guidance' : 'review-removed')
      if (!guidance && actions.some(action => /alternative/i.test(action.clause) && binding.appliedActionIds?.includes(action.id))) element.classList.add('review-alternative-removed')
      continue
    }
    if (binding.locationStatus !== 'exact') continue
    const after = matchedElement(resultIndex, binding.resultParagraphId, binding.text)
    if (!after) { notices.add('mappingUnavailable'); continue }
    if (normalized(documentParagraphText(element)) !== normalized(documentParagraphText(after))) {
      const attribution = changeAttribution(binding, options.actions, fileKey, options.selectedKey)
      if (attribution === 'selected') {
        if (!markActualDiff(element, after.cloneNode(true) as HTMLElement)) notices.add('diffUnavailable')
      } else notices.add(attribution)
    }
    if (options.planCurrent && actions.some(action => action.action === 'retain' && scopes((action.paragraphs ?? '').split(';')[0] ?? '').has(source.sourceParagraphOrdinal ?? -1))) element.classList.add('review-retained')
  }
  const tails = new Map<string, HTMLElement>()
  for (const binding of results) {
    if (binding.sourceParagraphId || !binding.parentBindingId || binding.locationStatus !== 'exact' || !['generated_added', 'body_added'].includes(binding.applicationStatus) || !associatedReviewFields(binding, options.fields, options.actions).includes(options.selectedKey)) continue
    const attribution = changeAttribution(binding, options.actions, fileKey, options.selectedKey)
    if (attribution !== 'selected') { notices.add(attribution); continue }
    const parent = sourceById.get(binding.parentBindingId), anchor = parent && matchedElement(sourceIndex, parent.sourceParagraphId, parent.sourceText), resultElement = matchedElement(resultIndex, binding.resultParagraphId, binding.text)
    if (!anchor || !resultElement) { notices.add('additionUnmapped'); continue }
    const clone = resultElement.cloneNode(true) as HTMLElement
    clone.removeAttribute('data-native-paragraph'); clone.removeAttribute('data-native-ordinal')
    clone.dataset.reviewAdded = 'true'; clone.classList.add('review-added')
    const additionLabel = options.locale === 'zh-Hans' ? '文稿行登记为新增，尚无可靠的一对一原文映射。' : options.locale === 'zh-Hant' ? '文稿行登記為新增，尚無可靠的一對一原文映射。' : 'Saved row registered as added; no verified one-to-one original mapping.'
    clone.dataset.reviewAdditionLabel = additionLabel; clone.setAttribute('aria-description', additionLabel)
    interactive(clone, binding)
    const tail = tails.get(binding.parentBindingId) ?? anchor; tail.after(clone); tails.set(binding.parentBindingId, clone)
  }
  return { html: host.innerHTML, notices: [...notices], bindingIds: [...bindingIds], savedBindingIds }
}
