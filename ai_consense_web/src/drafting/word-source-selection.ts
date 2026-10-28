import type { DocumentReading } from './document-reading'
import type { ReviewAnchor, ReviewReading, ReviewSpan } from './source-review-types'
import { nativeRangeAnchor } from './source-review-state'

const WORD = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'
const MAIN = 'word/document.xml'

// The rendered markers use canonical w: names; backend source positions use expanded names.
function expandedPath(id: string): string | undefined {
  const prefix = `${MAIN}#/`
  if (!id.startsWith(prefix)) return
  const parts = id.slice(prefix.length).split('/')
  if (!parts.length || parts.some(part => !/^w:[A-Za-z_][A-Za-z0-9_.-]*\[[1-9][0-9]*\]$/.test(part))) return
  return parts.map(part => `/{${WORD}}${part.slice(2)}`).join('')
}

/** Bind only unique source-identified Word paragraphs whose editable text is exactly displayed. */
export function bindWordReview(root: HTMLElement, converted: DocumentReading, reading: ReviewReading): number {
  const blocks = Array.from(root.querySelectorAll<HTMLElement>('[data-native-paragraph]'))
  for (const block of blocks) delete block.dataset.nativeId
  if (reading.format !== 'docx' || !reading.canEdit || !/^[a-f0-9]{64}$/.test(reading.sourceSha256) || converted.docxSha256 !== reading.sourceSha256) return 0
  const byPath = new Map<string, typeof reading.paragraphs>()
  const sourceIds = new Map<string, number>()
  for (const paragraph of reading.paragraphs) {
    sourceIds.set(paragraph.id, (sourceIds.get(paragraph.id) ?? 0) + 1)
    const position = paragraph.sourcePosition
    if (paragraph.sourceSha256 !== reading.sourceSha256 || position?.sourceSha256 !== reading.sourceSha256 || position?.partName !== MAIN || typeof position?.nodePath !== 'string' || !paragraph.paragraphSha256) continue
    const group = byPath.get(position.nodePath) ?? []
    group.push(paragraph); byPath.set(position.nodePath, group)
  }
  const count = new Map<string, number>()
  for (const block of blocks) count.set(block.dataset.nativeParagraph!, (count.get(block.dataset.nativeParagraph!) ?? 0) + 1)
  const rendered = new Map<string, typeof converted.paragraphs>()
  for (const paragraph of converted.paragraphs) {
    const group = rendered.get(paragraph.id) ?? []
    group.push(paragraph); rendered.set(paragraph.id, group)
  }
  let bound = 0
  for (const block of blocks) {
    const id = block.dataset.nativeParagraph!, path = expandedPath(id), shown = rendered.get(id), matching = path ? byPath.get(path) : undefined
    if (count.get(id) !== 1 || shown?.length !== 1 || matching?.length !== 1 || block.querySelector('[data-native-paragraph]') || block.parentElement?.closest('[data-native-paragraph]')) continue
    const paragraph = matching[0]!
    if (sourceIds.get(paragraph.id) !== 1 || shown[0]!.text !== paragraph.text || block.textContent !== paragraph.text) continue
    block.dataset.nativeId = paragraph.id
    if (paragraph.text.trim() && !block.closest('[hidden],[aria-hidden="true"]')) bound++
  }
  return bound
}

/** Create visible ranges without changing source DOM, and retain all unresolved spans. */
export function wordReviewRanges(root: HTMLElement, reading: ReviewReading, spans: ReviewSpan[]): { ranges: Range[]; unresolved: number } {
  const blocks = Array.from(root.querySelectorAll<HTMLElement>('[data-native-id]'))
  const ranges: Range[] = []
  let unresolved = 0
  for (const span of spans) {
    const sources = reading.paragraphs.filter(paragraph => paragraph.id === span.paragraphId)
    const matches = blocks.filter(block => block.dataset.nativeId === span.paragraphId)
    const source = sources[0], block = matches[0]
    if (sources.length !== 1 || matches.length !== 1 || !source || !block || span.sourceSha256 !== reading.sourceSha256 || source.sourceSha256 !== reading.sourceSha256 || span.paragraphSha256 !== source.paragraphSha256 || (span.channel && span.channel !== 'editableText') || !Number.isInteger(span.start) || !Number.isInteger(span.end) || span.start < 0 || span.end <= span.start || span.end > source.text.length || source.text.slice(span.start, span.end) !== span.expectedText || block.textContent !== source.text || block.closest('[hidden],[aria-hidden="true"]')) { unresolved++; continue }
    const walker = root.ownerDocument.createTreeWalker(block, 4)
    const nodes: Text[] = []
    while (walker.nextNode()) nodes.push(walker.currentNode as Text)
    const point = (offset: number): [Text, number] | undefined => {
      for (const node of nodes) {
        if (offset <= node.length) return [node, offset]
        offset -= node.length
      }
    }
    const start = point(span.start), end = point(span.end)
    if (!start || !end) { unresolved++; continue }
    const range = root.ownerDocument.createRange()
    range.setStart(start[0], start[1]); range.setEnd(end[0], end[1])
    if (range.toString() !== span.expectedText) { unresolved++; continue }
    ranges.push(range)
  }
  return { ranges, unresolved }
}

/** Reject ranges that include displayed text outside verified native paragraphs. */
export function selectWordReviewRange(root: HTMLElement, reading: ReviewReading, range: Range): ReviewAnchor | undefined {
  if (!root.contains(range.startContainer) || !root.contains(range.endContainer)) return
  const paragraphs = Array.from(root.querySelectorAll<HTMLElement>('[data-native-id]'))
  const result = nativeRangeAnchor(reading, paragraphs, range)
  if (!result?.native) return result
  const spans = 'spans' in result.native ? result.native.spans : [result.native]
  if (spans.map(span => span.expectedText).join('') !== range.toString()) throw Error('选区包含未绑定的原文，请分开选择。 / The selection includes unbound source text; select passages separately.')
  return result
}
