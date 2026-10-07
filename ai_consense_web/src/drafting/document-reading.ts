import DOMPurify from 'dompurify'
import JSZip from 'jszip'
import mammoth from 'mammoth/mammoth.browser.min.js'
import { documentParagraphText as htmlParagraphText } from './document-reading-text'

export interface DocumentReadingParagraph {
  id: string
  path: string
  ordinal: number
  text: string
  htmlText?: string
}

export interface DocumentReadingCoverage {
  complete: boolean
  mainBodyParagraphCount: number
  mappedParagraphCount: number
  exactTextMatchCount: number
  normalizedTextMatchCount: number
  missingParagraphs: { id: string; ordinal: number }[]
  textMismatches: { id: string; ordinal: number; nativeText: string; htmlText: string }[]
  hiddenMergedCellParagraphs: { id: string; ordinal: number }[]
  nonemptyParagraphOrderVerified: boolean
  officeMathCount: number
  renderedOfficeMathCount: number
  omittedNativeGraphics: { id: string; kind: 'drawing' | 'pict' }[]
  numberedParagraphCount: number
  numberingLabelsVerified: boolean
  tables: number
  headings: number
  lists: number
}

export interface DocumentReading {
  html: string
  paragraphs: DocumentReadingParagraph[]
  docxSha256: string
  warnings?: string[]
  coverage?: DocumentReadingCoverage
}

const WORD = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'
const MATH = 'http://schemas.openxmlformats.org/officeDocument/2006/math'
const normalize = (text: string) => text.replace(/\s+/gu, ' ').trim()

function nativePath(element: Element): string {
  const parts: string[] = []
  for (let node: Element | null = element; node; node = node.parentElement) {
    let index = 1
    for (let sibling = node.previousElementSibling; sibling; sibling = sibling.previousElementSibling) {
      if (sibling.namespaceURI === node.namespaceURI && sibling.localName === node.localName) index++
    }
    const name = node.namespaceURI === WORD ? `w:${node.localName}` : node.nodeName
    parts.unshift(`${name}[${index}]`)
  }
  return `word/document.xml#/${parts.join('/')}`
}

function nativeParagraphText(paragraph: Element): string {
  let text = ''
  function visit(node: Element) {
    // Nested textbox paragraphs have separate identities. Property tabs are layout, not text.
    if (node !== paragraph && node.namespaceURI === WORD && node.localName === 'p') return
    if (node.namespaceURI === WORD) {
      if (node.localName === 'pPr' || node.localName === 'rPr') return
      if (node.localName === 't') { text += node.textContent ?? ''; return }
      if (node.localName === 'tab') { text += '\t'; return }
      if (node.localName === 'br' || node.localName === 'cr') { text += '\n'; return }
      if (node.localName === 'noBreakHyphen') { text += '\u2011'; return }
      if (node.localName === 'softHyphen') { text += '\u00ad'; return }
    }
    for (const child of Array.from(node.children)) visit(child)
  }
  visit(paragraph)
  return text
}

function nativeAncestor(element: Element, localName: string): Element | undefined {
  for (let node: Element | null = element; node; node = node.parentElement) {
    if (node.namespaceURI === WORD && node.localName === localName) return node
  }
}

function nativeChildren(element: Element, localName: string): Element[] {
  return Array.from(element.children).filter(child => child.namespaceURI === WORD && child.localName === localName)
}

function nativeCellPositions(row: Element) {
  const rowProperties = nativeChildren(row, 'trPr')[0]
  let column = Number(rowProperties && nativeChildren(rowProperties, 'gridBefore')[0]?.getAttributeNS(WORD, 'val') || 0)
  return nativeChildren(row, 'tc').map(cell => {
    const properties = nativeChildren(cell, 'tcPr')[0]
    const span = Number(properties && nativeChildren(properties, 'gridSpan')[0]?.getAttributeNS(WORD, 'val') || 1)
    const position = { cell, column, span }
    column += span
    return position
  })
}

/** Readable semantic formulas, independently checked because Mammoth omits Office Math. */
function readableOfficeMath(element: Element): { fragment: DocumentFragment; unsupported: string[] } {
  const fragment = document.createDocumentFragment()
  const unsupported = new Set<string>()
  function content(node: Element | undefined, host: Node) {
    if (!node || node.namespaceURI !== MATH || node.localName.endsWith('Pr')) return
    if (node.localName === 't') { host.appendChild(document.createTextNode(node.textContent ?? '')); return }
    const child = (name: string) => Array.from(node.children).find(element => element.namespaceURI === MATH && element.localName === name)
    if (node.localName === 'f') {
      host.appendChild(document.createTextNode('('))
      content(child('num'), host)
      host.appendChild(document.createTextNode(') / ('))
      content(child('den'), host)
      host.appendChild(document.createTextNode(')'))
      return
    }
    if (node.localName === 'sSub' || node.localName === 'sSup') {
      content(child('e'), host)
      const script = document.createElement(node.localName === 'sSub' ? 'sub' : 'sup')
      content(child(node.localName === 'sSub' ? 'sub' : 'sup'), script)
      host.appendChild(script)
      return
    }
    if (!['oMath', 'r', 'e', 'num', 'den', 'sub', 'sup'].includes(node.localName)) {
      // Keep literal formula atoms visible, but do not claim their unsupported notation is complete.
      unsupported.add(node.localName)
    }
    for (const element of Array.from(node.children)) content(element, host)
  }
  content(element, fragment)
  return { fragment, unsupported: Array.from(unsupported) }
}

/** Convert the exact source/revision bytes into a read-only main-document flow. */
export async function convertDocumentReading(bytes: ArrayBuffer, expectedSha256: string): Promise<DocumentReading> {
  const digest = await crypto.subtle.digest('SHA-256', bytes)
  const docxSha256 = Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, '0')).join('')
  if (!/^[a-f0-9]{64}$/i.test(expectedSha256) || docxSha256 !== expectedSha256.toLowerCase()) {
    throw new Error('DOCX_VERSION_MISMATCH: document bytes do not match the expected source/revision SHA-256.')
  }
  const zip = await JSZip.loadAsync(bytes)
  const documentPart = zip.file('word/document.xml')
  if (!documentPart) throw new Error('DOCX_READING_INVALID: missing word/document.xml.')
  const sourceXml = await documentPart.async('string')
  const native = new DOMParser().parseFromString(sourceXml, 'application/xml')
  if (native.getElementsByTagName('parsererror').length) throw new Error('DOCX_READING_INVALID: invalid main-document XML.')
  const body = native.getElementsByTagNameNS(WORD, 'body')[0]
  if (!body) throw new Error('DOCX_READING_INVALID: no main-document body.')
  // The safe reading carrier currently omits native drawings and VML pictures.
  // Account for their exact source identities independently of paragraph text.
  const omittedNativeGraphics = (['drawing', 'pict'] as const).flatMap(kind => Array.from(body.getElementsByTagNameNS(WORD, kind)).map(element => ({ id: nativePath(element), kind })))
  const elements = Array.from(body.getElementsByTagNameNS(WORD, 'p'))
  const stylesPart = zip.file('word/styles.xml')
  const stylesXml = stylesPart ? new DOMParser().parseFromString(await stylesPart.async('string'), 'application/xml') : undefined
  const paragraphStyles = Array.from(stylesXml?.getElementsByTagNameNS(WORD, 'style') ?? []).filter(style => style.getAttributeNS(WORD, 'type') === 'paragraph')
  const stylesById = new Map(paragraphStyles.map(style => [style.getAttributeNS(WORD, 'styleId'), style]))
  const defaultStyles = paragraphStyles.filter(style => ['1', 'true', 'on'].includes(style.getAttributeNS(WORD, 'default') ?? ''))
  function numbering(properties: Element | undefined): boolean | undefined {
    const number = properties && nativeChildren(properties, 'numPr')[0]
    return number ? nativeChildren(number, 'numId')[0]?.getAttributeNS(WORD, 'val') !== '0' : undefined
  }
  function styleNumbering(style: Element | undefined): boolean {
    const visited = new Set<Element>()
    while (style && !visited.has(style)) {
      visited.add(style)
      const numbered = numbering(nativeChildren(style, 'pPr')[0])
      if (numbered !== undefined) return numbered
      const ancestorId = nativeChildren(style, 'basedOn')[0]?.getAttributeNS(WORD, 'val')
      style = ancestorId ? stylesById.get(ancestorId) : undefined
    }
    return false
  }
  const numberedParagraphCount = elements.filter(paragraph => {
    const properties = nativeChildren(paragraph, 'pPr')[0], direct = numbering(properties)
    if (direct !== undefined) return direct
    const styleId = properties && nativeChildren(properties, 'pStyle')[0]?.getAttributeNS(WORD, 'val')
    // Only the referenced/default paragraph styles can supply numbering. A
    // basedOn chain is exact and bounded; an explicit numId=0 cancels it.
    return styleId ? styleNumbering(stylesById.get(styleId)) : defaultStyles.some(styleNumbering)
  }).length
  const paragraphs: DocumentReadingParagraph[] = elements.map((element, index) => {
    const path = nativePath(element)
    return { id: path, path, ordinal: index + 1, text: nativeParagraphText(element) }
  })
  // Choose deterministic names that cannot collide with source bookmarks or literal text.
  let prefix = `CSReading${docxSha256.slice(0, 12)}`
  while (sourceXml.includes(prefix)) prefix += 'X'
  const officeMath = Array.from(body.getElementsByTagNameNS(MATH, 'oMath')).map((element, index) => {
    const paragraph = nativeAncestor(element, 'p')
    if (!paragraph) throw new Error('DOCX_READING_UNSUPPORTED: Office Math outside a main-document paragraph.')
    const holder = document.createElement('span')
    const readable = readableOfficeMath(element)
    holder.append(readable.fragment)
    return { token: `${prefix}Math${String(index + 1).padStart(6, '0')}`, path: nativePath(element), paragraphId: nativePath(paragraph), text: holder.textContent ?? '', html: holder.innerHTML, unsupported: readable.unsupported, element }
  })
  for (const formula of officeMath) {
    const run = native.createElementNS(WORD, 'w:r')
    const text = native.createElementNS(WORD, 'w:t')
    text.textContent = formula.token
    run.append(text)
    formula.element.replaceWith(run)
  }
  // Display formula wrappers are omitted by Mammoth too; their replacement runs belong to w:p.
  for (const display of Array.from(body.getElementsByTagNameNS(MATH, 'oMathPara'))) {
    const runs = Array.from(display.children).filter(node => node.namespaceURI === WORD && node.localName === 'r')
    display.replaceWith(...runs)
  }
  const existingIds = Array.from(native.getElementsByTagNameNS(WORD, 'bookmarkStart'))
    .map(element => Number(element.getAttributeNS(WORD, 'id'))).filter(Number.isFinite)
  const firstId = Math.max(0, ...existingIds) + 1
  const bookmarkParagraphs = new Map<string, DocumentReadingParagraph>()
  elements.forEach((element, index) => {
    const name = `${prefix}P${String(index + 1).padStart(6, '0')}`
    bookmarkParagraphs.set(name, paragraphs[index]!)
    const start = native.createElementNS(WORD, 'w:bookmarkStart')
    start.setAttributeNS(WORD, 'w:id', String(firstId + index))
    start.setAttributeNS(WORD, 'w:name', name)
    const end = native.createElementNS(WORD, 'w:bookmarkEnd')
    end.setAttributeNS(WORD, 'w:id', String(firstId + index))
    const properties = Array.from(element.children).find(node => node.namespaceURI === WORD && node.localName === 'pPr')
    const before = properties ? properties.nextSibling : element.firstChild
    element.insertBefore(start, before)
    element.insertBefore(end, before)
  })
  zip.file('word/document.xml', new XMLSerializer().serializeToString(native))
  const conversionBytes = await zip.generateAsync({ type: 'arraybuffer', compression: 'DEFLATE' })
  const converted = await mammoth.convertToHtml({ arrayBuffer: conversionBytes }, {
    externalFileAccess: false,
    includeEmbeddedStyleMap: false,
    styleMap: ['u => u']
  })
  const safe = DOMPurify.sanitize(converted.value, {
    ALLOWED_TAGS: ['p', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'strong', 'b', 'em', 'i', 'u', 's', 'sup', 'sub', 'br', 'table', 'thead', 'tbody', 'tfoot', 'tr', 'th', 'td', 'ol', 'ul', 'li', 'a', 'span'],
    ALLOWED_ATTR: ['colspan', 'rowspan', 'title', 'id'],
    ALLOW_DATA_ATTR: false,
    ALLOW_ARIA_ATTR: false
  })
  const flow = document.createElement('div')
  flow.innerHTML = safe
  const formulasByToken = new Map(officeMath.map(formula => [formula.token, formula]))
  const walker = document.createTreeWalker(flow, NodeFilter.SHOW_TEXT)
  const textNodes: Text[] = []
  while (walker.nextNode()) textNodes.push(walker.currentNode as Text)
  const formulaPattern = new RegExp(`${prefix}Math\\d{6}`, 'g')
  for (const node of textNodes) {
    const text = node.textContent ?? ''
    const matches = Array.from(text.matchAll(formulaPattern))
    if (!matches.length) continue
    const fragment = document.createDocumentFragment()
    let offset = 0
    for (const match of matches) {
      const formula = formulasByToken.get(match[0])
      if (!formula) throw new Error('DOCX_READING_INVALID: unknown temporary Office Math marker.')
      fragment.append(document.createTextNode(text.slice(offset, match.index)))
      const span = document.createElement('span')
      span.dataset.nativeMath = formula.path
      span.title = formula.text
      // This fragment contains only elements and text created by readableOfficeMath.
      span.innerHTML = formula.html
      fragment.append(span)
      offset = match.index! + match[0].length
    }
    fragment.append(document.createTextNode(text.slice(offset)))
    node.replaceWith(fragment)
  }
  const mapped = new Map<string, HTMLElement>()
  const warnings = converted.messages.map(message => message.message)
  if (omittedNativeGraphics.length) warnings.push(`Native drawings/images omitted from this reading: ${omittedNativeGraphics.length}. Check the PDF layout for these source objects.`)
  if (numberedParagraphCount) warnings.push(`Native numbering labels and continuation sequence are not verified in this reading (${numberedParagraphCount} numbered paragraph(s)). Check the PDF layout for the original list markers.`)
  for (const formula of officeMath.filter(formula => formula.unsupported.length)) {
    warnings.push(`Office Math at ${formula.path} retains literal atoms only for unsupported structure(s): ${formula.unsupported.join(', ')}.`)
  }
  let duplicateBlock = false
  for (const anchor of Array.from(flow.querySelectorAll<HTMLElement>('[id]'))) {
    const paragraph = bookmarkParagraphs.get(anchor.id)
    if (!paragraph) { anchor.removeAttribute('id'); continue }
    const block = anchor.closest<HTMLElement>('p,h1,h2,h3,h4,h5,h6,li')
    if (!block || (block.dataset.nativeParagraph && block.dataset.nativeParagraph !== paragraph.id)) {
      duplicateBlock = true
      warnings.push(`Paragraph ${paragraph.ordinal} cannot be assigned a unique rendered block.`)
      anchor.remove()
      continue
    }
    block.dataset.nativeParagraph = paragraph.id
    block.dataset.nativeOrdinal = String(paragraph.ordinal)
    mapped.set(paragraph.id, block)
    anchor.remove()
  }
  const hiddenMergedCellParagraphs: DocumentReadingCoverage['hiddenMergedCellParagraphs'] = []
  const nativeTables = Array.from(body.getElementsByTagNameNS(WORD, 'tbl'))
  const renderedTables = Array.from(flow.querySelectorAll('table'))
  for (const paragraph of paragraphs.filter(paragraph => !mapped.has(paragraph.id))) {
    const element = elements[paragraph.ordinal - 1]!
    const cell = nativeAncestor(element, 'tc')
    const properties = cell && nativeChildren(cell, 'tcPr')[0]
    const merge = properties && nativeChildren(properties, 'vMerge')[0]
    if (paragraph.text || officeMath.some(formula => formula.paragraphId === paragraph.id) || !cell || !merge || !['', 'continue'].includes(merge.getAttributeNS(WORD, 'val') ?? '')) continue
    if (nativeTables.length !== renderedTables.length) continue
    const table = nativeAncestor(cell, 'tbl'), row = nativeAncestor(cell, 'tr')
    if (!table || !row) continue
    const tableIndex = nativeTables.indexOf(table)
    const nativeRows = nativeChildren(table, 'tr')
    const rowIndex = nativeRows.indexOf(row)
    const renderedTable = renderedTables[tableIndex]
    if (!renderedTable || rowIndex < 0) continue
    const rows = Array.from(renderedTable.querySelectorAll('tr')).filter(row => row.closest('table') === renderedTable)
    if (rows.length !== nativeRows.length) continue
    const position = nativeCellPositions(row).find(position => position.cell === cell)
    if (!position) continue
    let restart: Element | undefined, restartRow = -1
    for (let previous = rowIndex - 1; previous >= 0; previous--) {
      const candidate = nativeCellPositions(nativeRows[previous]!).find(candidate => candidate.column === position.column && candidate.span === position.span)
      const candidateProperties = candidate && nativeChildren(candidate.cell, 'tcPr')[0]
      const candidateMerge = candidateProperties && nativeChildren(candidateProperties, 'vMerge')[0]
      if (!candidate || !candidateMerge) break
      const mergeValue = candidateMerge.getAttributeNS(WORD, 'val') ?? ''
      if (mergeValue === 'restart') { restart = candidate.cell; restartRow = previous; break }
      if (!['', 'continue'].includes(mergeValue)) break
    }
    if (!restart) continue
    // Locate the native restart through its already verified paragraph marker. Mammoth omits
    // gridBefore columns, so a rendered column number alone cannot establish this identity.
    const restartParagraph = Array.from(restart.getElementsByTagNameNS(WORD, 'p')).find(paragraph => nativeAncestor(paragraph, 'tc') === restart && mapped.has(nativePath(paragraph)))
    const spanningCell = restartParagraph && mapped.get(nativePath(restartParagraph))?.closest<HTMLTableCellElement>('td,th')
    if (!spanningCell || spanningCell.parentElement !== rows[restartRow] || spanningCell.rowSpan <= rowIndex - restartRow || spanningCell.colSpan !== position.span) continue
    const marker = document.createElement('span')
    marker.hidden = true
    marker.setAttribute('aria-hidden', 'true')
    marker.dataset.nativeParagraph = paragraph.id
    marker.dataset.nativeOrdinal = String(paragraph.ordinal)
    marker.dataset.nativeMergedContinuation = 'true'
    spanningCell.append(marker)
    mapped.set(paragraph.id, marker)
    hiddenMergedCellParagraphs.push({ id: paragraph.id, ordinal: paragraph.ordinal })
  }
  const textMismatches: DocumentReadingCoverage['textMismatches'] = []
  let exactTextMatchCount = 0, normalizedTextMatchCount = 0
  for (const paragraph of paragraphs) {
    const block = mapped.get(paragraph.id)
    if (!block) continue
    const htmlText = htmlParagraphText(block, true)
    paragraph.htmlText = htmlParagraphText(block)
    if (htmlText === paragraph.text) exactTextMatchCount++
    if (normalize(htmlText) === normalize(paragraph.text)) normalizedTextMatchCount++
    else textMismatches.push({ ordinal: paragraph.ordinal, id: paragraph.id, nativeText: paragraph.text, htmlText })
  }
  const missingParagraphs = paragraphs.filter(paragraph => !mapped.has(paragraph.id)).map(({ id, ordinal }) => ({ id, ordinal }))
  const mathParagraphs = new Set(officeMath.map(formula => formula.paragraphId))
  const hasContent = (paragraph: DocumentReadingParagraph) => !!normalize(paragraph.text) || mathParagraphs.has(paragraph.id)
  const expectedOrder = paragraphs.filter(hasContent).map(paragraph => paragraph.id)
  const nativeById = new Map(paragraphs.map(paragraph => [paragraph.id, paragraph]))
  const renderedOrder = Array.from(flow.querySelectorAll<HTMLElement>('[data-native-paragraph]'))
    .map(node => nativeById.get(node.dataset.nativeParagraph!)).filter(paragraph => paragraph && hasContent(paragraph)).map(paragraph => paragraph!.id)
  const nonemptyParagraphOrderVerified = expectedOrder.length === renderedOrder.length && expectedOrder.every((id, index) => id === renderedOrder[index])
  const coverage: DocumentReadingCoverage = {
    complete: !missingParagraphs.length && !textMismatches.length && !omittedNativeGraphics.length && !duplicateBlock && nonemptyParagraphOrderVerified && officeMath.every(formula => {
      const rendered = Array.from(flow.querySelectorAll<HTMLElement>('[data-native-math]')).filter(node => node.dataset.nativeMath === formula.path)
      return !formula.unsupported.length && rendered.length === 1 && rendered[0]!.textContent === formula.text
    }),
    mainBodyParagraphCount: paragraphs.length,
    mappedParagraphCount: mapped.size,
    exactTextMatchCount, normalizedTextMatchCount,
    missingParagraphs, textMismatches,
    hiddenMergedCellParagraphs,
    nonemptyParagraphOrderVerified,
    officeMathCount: officeMath.length, renderedOfficeMathCount: flow.querySelectorAll('[data-native-math]').length,
    omittedNativeGraphics,
    numberedParagraphCount, numberingLabelsVerified: numberedParagraphCount === 0,
    tables: flow.querySelectorAll('table').length,
    headings: flow.querySelectorAll('h1,h2,h3,h4,h5,h6').length,
    lists: flow.querySelectorAll('ol,ul').length
  }
  if (!coverage.complete) warnings.push(`Incomplete main-document coverage: ${missingParagraphs.length} missing paragraph(s), ${textMismatches.length} text mismatch(es).`)
  return { html: flow.innerHTML, paragraphs, docxSha256, warnings, coverage }
}
