import { findPdfTextMatches, type PdfTextRectangle } from './pdf-text-ranges'
import { nativeAnchor } from './source-review-state'
import type { ReviewAnchor, ReviewReading, ReviewSpan } from './source-review-types'

export interface SourcePageWord { itemIndex: number; start: number; end: number; text: string; context: string; rectangle: PdfTextRectangle }
export interface SourcePageSelection { source: string; page: number; words: SourcePageWord[] }

/** Keep page selections reviewable; only exact native contexts authorize Word offsets. */
export function bindPageSelection(reading: ReviewReading, selected: SourcePageSelection): ReviewAnchor {
  if (!selected.words.length || !Number.isInteger(selected.page) || selected.page < 1) throw Error('请先在原件上选词。 / Select words on the original page.')
  const text = selected.words.map(word => word.text).join(' ')
  const items = new Map<number, SourcePageWord[]>()
  for (const word of selected.words) {
    if (!Number.isInteger(word.start) || !Number.isInteger(word.end) || word.start < 0 || word.end <= word.start || word.context.slice(word.start, word.end) !== word.text) throw Error('原件选词已变化，请重新选择。 / The source selection changed; select it again.')
    const group = items.get(word.itemIndex) ?? []; group.push(word); items.set(word.itemIndex, group)
  }
  const pending: ReviewAnchor = { docId: String(reading.id), sourceSha256: reading.sourceSha256, text, page: selected.page }
  if (reading.format !== 'docx' || !reading.canEdit) return pending
  if (reading.paragraphs.some(paragraph => paragraph.sourceSha256 !== reading.sourceSha256)) throw Error('Source selection changed; reload the current original.')
  if(items.size>1){
    const matches=findPdfTextMatches(reading.paragraphs.map(paragraph=>paragraph.text),text)
    if(matches.length===1){
      const spans=matches[0]!.spans.map(span=>nativeAnchor(reading,reading.paragraphs[span.itemIndex]!.id,span.start,span.end).native as ReviewSpan)
      return {docId:String(reading.id),sourceSha256:reading.sourceSha256,text:spans.map(span=>span.expectedText).join('\n'),page:selected.page,native:{spans}}
    }
  }
  const spans: ReviewSpan[] = []
  for (const words of items.values()) {
    const context = words[0]!.context
    if (words.some(word => word.context !== context)) throw Error('原件选词已变化。 / Source selection changed.')
    const contexts = findPdfTextMatches(reading.paragraphs.map(paragraph => paragraph.text), context)
    if (contexts.length !== 1) return pending
    const runs: SourcePageWord[] = []
    for (const word of [...words].sort((a,b) => a.start-b.start)) {
      const prior = runs[runs.length - 1]
      if (prior && word.start >= prior.end && /^\s*$/.test(context.slice(prior.end,word.start))) { prior.end=word.end; prior.text=context.slice(prior.start,word.end) }
      else runs.push({ ...word })
    }
    for (const word of runs) {
      const matches = findPdfTextMatches(reading.paragraphs.map(paragraph => paragraph.text), word.text).filter(match => match.spans.every(span => contexts[0]!.spans.some(parent => parent.itemIndex === span.itemIndex && parent.start <= span.start && parent.end >= span.end)))
      if (matches.length !== 1) return pending
      for (const span of matches[0]!.spans) {
        const anchor = nativeAnchor(reading, reading.paragraphs[span.itemIndex]!.id, span.start, span.end)
        spans.push(anchor.native as ReviewSpan)
      }
    }
  }
  const merged: ReviewSpan[] = []
  for (const span of spans) {
    const prior = merged[merged.length - 1], paragraph = reading.paragraphs.find(item => item.id === span.paragraphId)!
    if (prior?.paragraphId === span.paragraphId && span.start >= prior.end && /^\s*$/.test(paragraph.text.slice(prior.end, span.start))) {
      prior.end = span.end; prior.expectedText = paragraph.text.slice(prior.start, span.end)
    } else if (!merged.some(existing => existing.paragraphId === span.paragraphId && existing.start === span.start && existing.end === span.end)) merged.push({ ...span })
  }
  return { docId: String(reading.id), sourceSha256: reading.sourceSha256, text: merged.map(span => span.expectedText).join('\n'), page: selected.page, native: { spans: merged } }
}

export function wordsInBox(words: SourcePageWord[], box: PdfTextRectangle): number[] {
  return words.flatMap((word, index) => {
    const r = word.rectangle, x = r.x + r.width / 2, y = r.y + r.height / 2
    return x >= box.x && x <= box.x + box.width && y >= box.y && y <= box.y + box.height ? [index] : []
  })
}
