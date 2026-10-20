import DOMPurify from 'dompurify'
import type { TemplateReading } from '@/api/types'
import { hideUnverifiedListMarkers } from './reading-list-markers'

export interface PreparedTemplateReading { html: string; mappedParagraphIds: string[]; numberingUnverified: boolean }
const normalize = (text: string) => text.normalize('NFKC').replace(/\s+/g, ' ').trim()

/** Sanitized reading flow, with identities only when current native text mapping is unambiguous. */
export function prepareTemplateReading(html: string, reading: TemplateReading): PreparedTemplateReading {
  const safe = DOMPurify.sanitize(html, {
    ALLOWED_TAGS: ['p', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'strong', 'b', 'em', 'i', 'u', 's', 'sup', 'sub', 'br', 'table', 'thead', 'tbody', 'tfoot', 'tr', 'th', 'td', 'ol', 'ul', 'li', 'a', 'span'],
    ALLOWED_ATTR: ['colspan', 'rowspan', 'title'],
    ALLOW_DATA_ATTR: false, ALLOW_ARIA_ATTR: false
  })
  const flow = document.createElement('div'); flow.innerHTML = safe
  const numberingUnverified = hideUnverifiedListMarkers(flow) > 0
  const blocks = [...flow.querySelectorAll<HTMLElement>('p,h1,h2,h3,h4,h5,h6,li')].filter(node => node.tagName !== 'LI' || !node.querySelector('p,h1,h2,h3,h4,h5,h6,li'))
  const native = reading.paragraphs.filter(paragraph => normalize(paragraph.text))
  const ordered = blocks.length === native.length && blocks.every((node, index) => normalize(node.textContent ?? '') === normalize(native[index]!.text))
  const mappedParagraphIds: string[] = []
  blocks.forEach((node, index) => {
    const text = normalize(node.textContent ?? '')
    const candidates = native.filter(paragraph => normalize(paragraph.text) === text)
    const paragraph = ordered ? native[index] : candidates.length === 1 && blocks.filter(block => normalize(block.textContent ?? '') === text).length === 1 ? candidates[0] : undefined
    if (!paragraph) return
    node.dataset.nativeParagraph = paragraph.id
    mappedParagraphIds.push(paragraph.id)
  })
  return { html: flow.innerHTML, mappedParagraphIds, numberingUnverified }
}
