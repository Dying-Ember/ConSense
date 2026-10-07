export interface PdfTextSpan { itemIndex: number; start: number; end: number }
export interface PdfTextMatch { spans: PdfTextSpan[] }
export interface PdfTextRectangle { x: number; y: number; width: number; height: number }
export interface PdfTextRange { match: PdfTextMatch; rectangles: PdfTextRectangle[] }

const printLigatures: Record<string, string> = { 'ﬀ': 'ff', 'ﬁ': 'fi', 'ﬂ': 'fl', 'ﬃ': 'ffi', 'ﬄ': 'ffl', 'ﬅ': 'st', 'ﬆ': 'st' }
function printedCharacter(character: string): string {
  return /\s/u.test(character) || character === '\u00ad' ? '' : printLigatures[character] ?? character
}

/** Match full paragraph text across PDF runs, retaining original UTF-16 Range offsets.
 * Only print whitespace, discretionary soft hyphens and ligatures are normalized;
 * case, punctuation and visible hyphens must still match the saved paragraph. */
export function findPdfTextMatches(items: readonly string[], targetText: string): PdfTextMatch[] {
  const target = Array.from(targetText, printedCharacter).join('')
  if (!target) return []
  let printed = ''
  const positions: Array<{ itemIndex: number; start: number; end: number }> = []
  items.forEach((text, itemIndex) => {
    let offset = 0
    for (const character of text) {
      const normalized = printedCharacter(character)
      printed += normalized
      for (let index = 0; index < normalized.length; index++) positions.push({ itemIndex, start: offset, end: offset + character.length })
      offset += character.length
    }
  })
  const matches: PdfTextMatch[] = []
  for (let from = 0; from <= printed.length - target.length;) {
    const start = printed.indexOf(target, from)
    if (start < 0) break
    const spans: PdfTextSpan[] = []
    for (let index = start; index < start + target.length; index++) {
      const position = positions[index], previous = spans[spans.length - 1]
      if (previous?.itemIndex === position.itemIndex) previous.end = position.end
      else spans.push({ ...position })
    }
    // A substring cannot start/end halfway through a printed ligature.
    const before = positions[start - 1], first = positions[start], last = positions[start + target.length - 1], after = positions[start + target.length]
    if (!(before?.itemIndex === first.itemIndex && before.start === first.start) && !(after?.itemIndex === last.itemIndex && after.start === last.start)) matches.push({ spans })
    from = start + 1
  }
  return matches
}

function validatedRectangles(rectangles: readonly PdfTextRectangle[], page: { width: number; height: number }): PdfTextRectangle[] | undefined {
  const valid: PdfTextRectangle[] = []
  for (const rectangle of rectangles) {
    const { x, y, width, height } = rectangle
    if (![x, y, width, height].every(Number.isFinite) || width < 0 || height < 0) return
    if (!width || !height) continue
    if (x < -.5 || y < -.5 || x + width > page.width + .5 || y + height > page.height + .5) return
    const left = Math.max(0, x), top = Math.max(0, y)
    valid.push({ x: left, y: top, width: Math.min(page.width, x + width) - left, height: Math.min(page.height, y + height) - top })
  }
  let lineLeft = valid[0]?.x ?? 0, lineRight = lineLeft + (valid[0]?.width ?? 0)
  for (let index = 1; index < valid.length; index++) {
    const previous = valid[index - 1], next = valid[index]
    const height = Math.max(previous.height, next.height)
    const overlap = Math.min(previous.y + previous.height, next.y + next.height) - Math.max(previous.y, next.y)
    if (overlap >= Math.min(previous.height, next.height) / 2) {
      const gap = Math.max(next.x - previous.x - previous.width, previous.x - next.x - next.width, 0)
      if (gap > Math.max(72, height * 6)) return
      lineLeft = Math.min(lineLeft, next.x); lineRight = Math.max(lineRight, next.x + next.width)
    } else {
      const gap = Math.max(next.x - lineRight, lineLeft - next.x - next.width, 0)
      if (next.y < previous.y || next.y - previous.y > Math.max(48, height * 4) || gap > Math.max(72, height * 6)) return
      lineLeft = next.x; lineRight = next.x + next.width
    }
  }
  return valid.length ? valid : undefined
}

/** Choose only a measured complete match near its verified paragraph bookmark.
 * Rectangles are measured DOM text ranges in unscaled crop-box PDF points; this
 * helper never derives substring widths from a PDF text item's total width.
 * Distant runs or column jumps cannot supply a paragraph's missing remainder. */
export function selectPdfTextRange(candidates: readonly PdfTextRange[], anchor: { x: number; y: number }, page: { width: number; height: number }): PdfTextRange | undefined {
  if (![anchor.x, anchor.y, page.width, page.height].every(Number.isFinite) || page.width <= 0 || page.height <= 0 || anchor.x < 0 || anchor.y < 0 || anchor.x > page.width || anchor.y > page.height) return
  const located = candidates.flatMap(candidate => {
    const rectangles = validatedRectangles(candidate.rectangles, page)
    if (!rectangles || !candidate.match.spans.length) return []
    const first = rectangles[0]
    const dx = Math.max(first.x - anchor.x, 0, anchor.x - first.x - first.width)
    const dy = Math.max(first.y - anchor.y, 0, anchor.y - first.y - first.height)
    const distance = Math.hypot(dx, dy)
    // Destinations normally sit at the first line. Larger differences remain an anchor.
    return distance <= 36 ? [{ match: candidate.match, rectangles, distance }] : []
  }).sort((left, right) => left.distance - right.distance)
  const best = located[0]
  if (!best || located[1] && located[1].distance - best.distance < 4) return
  return { match: best.match, rectangles: best.rectangles }
}
