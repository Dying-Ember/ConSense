import JSZip from 'jszip'
import { convertDocumentReading, readableOfficeMath } from '@/drafting/document-reading'
import { sha256Bytes } from '@/api/sha256Bytes'
import type { NativeDocxPosition } from '@/api/canonicalEvidence'

const WORD = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'
const MATH = 'http://schemas.openxmlformats.org/officeDocument/2006/math'
export interface AdviceDocxReading { html: string; warnings: string[]; targetKeys: string[]; missingTargets: string[] }
export function docxPositionKey(position: Pick<NativeDocxPosition, 'sourceSha256' | 'partName' | 'nodePath'>): string {
  return JSON.stringify([position.sourceSha256, position.partName, position.nodePath])
}
function clarkPath(element: Element): string {
  const parts: string[] = []
  for (let node: Element | null = element; node; node = node.parentElement) {
    let index = 1
    for (let sibling = node.previousElementSibling; sibling; sibling = sibling.previousElementSibling)
      if (sibling.namespaceURI === node.namespaceURI && sibling.localName === node.localName) index++
    parts.unshift(`{${node.namespaceURI ?? ''}}${node.localName}[${index}]`)
  }
  return '/' + parts.join('/')
}
/** The native path names every namespace and sibling ordinal; text is never a locator. */
export function resolveDocxNode(xml: Document, path: string): Element | undefined {
  const matches = [...path.matchAll(/\/\{([^{}\s]+)\}([A-Za-z_][A-Za-z0-9_.-]*)\[([1-9][0-9]*)\]/g)]
  if (!matches.length || matches.map(match => match[0]).join('') !== path) return undefined
  let parent: Element | Document = xml
  for (const match of matches) {
    const node: Element | undefined = Array.from(parent.children).filter(node => node.namespaceURI === match[1] && node.localName === match[2])[Number(match[3]) - 1]
    if (!node) return undefined
    parent = node
  }
  return parent as Element
}
function legacyPath(element: Element): string {
  const parts: string[] = []
  for (let node: Element | null = element; node; node = node.parentElement) {
    let index = 1
    for (let sibling = node.previousElementSibling; sibling; sibling = sibling.previousElementSibling)
      if (sibling.namespaceURI === node.namespaceURI && sibling.localName === node.localName) index++
    parts.unshift(`${node.namespaceURI === WORD ? 'w:' + node.localName : node.nodeName}[${index}]`)
  }
  return 'word/document.xml#/' + parts.join('/')
}
function nativeView(element: Element, sha: string, partName: string, images: Map<string, string>, warnings: string[]): HTMLElement {
  const tag = element.namespaceURI === WORD ? ({ p: 'p', tbl: 'table', tr: 'tr', tc: 'td', del: 'del', ins: 'ins', moveFrom: 'del', moveTo: 'ins', b: 'strong', i: 'em' } as Record<string, string>)[element.localName] : undefined
  const result = document.createElement(tag ?? 'span')
  result.dataset.sourceNode = docxPositionKey({ sourceSha256: sha, partName, nodePath: clarkPath(element) })
  if (element.namespaceURI === WORD && ['del', 'ins', 'moveFrom', 'moveTo'].includes(element.localName)) {
    result.dataset.nativeRevision = element.localName
    result.dataset.nativeRevisionId = element.getAttributeNS(WORD, 'id') ?? ''
    result.title = element.localName
  }
  if (element.namespaceURI === WORD && ['t', 'delText', 'instrText'].includes(element.localName)) { result.textContent = element.textContent; return result }
  if (element.namespaceURI === WORD && element.localName === 'tab') { result.textContent = '\t'; return result }
  if (element.namespaceURI === WORD && ['br', 'cr'].includes(element.localName)) { result.append(document.createElement('br')); return result }
  if (element.namespaceURI === MATH && element.localName === 'oMath') {
    const math = readableOfficeMath(element)
    if (math.unsupported.length) {
      result.dataset.formulaStatus = 'unavailable'
      result.textContent = '[Formula could not be displayed. Download the original to review it.]'
      warnings.push('A formula uses unsupported notation and remains unavailable in this view; review the original document.')
    } else result.append(math.fragment)
    return result
  }
  if (element.namespaceURI === WORD && ['pPr', 'rPr', 'tblPr', 'tblGrid', 'trPr', 'tcPr', 'sectPr'].includes(element.localName)) return result
  if (element.namespaceURI === WORD && ['drawing', 'pict', 'object', 'altChunk'].includes(element.localName)) {
    const embeds = Array.from(element.getElementsByTagName('*')).flatMap(node => {
      const id = node.getAttributeNS('http://schemas.openxmlformats.org/officeDocument/2006/relationships', 'embed')
        ?? node.getAttributeNS('http://schemas.openxmlformats.org/officeDocument/2006/relationships', 'id')
      return id && images.has(id) ? [images.get(id)!] : []
    })
    for (const src of [...new Set(embeds)]) { const image = document.createElement('img'); image.src = src; image.alt = 'Embedded original image'; result.append(image) }
    for (const text of Array.from(element.getElementsByTagNameNS(WORD, 'txbxContent'))) result.append(nativeView(text, sha, partName, images, warnings))
    if (!embeds.length) { result.className = 'source-object-warning'; result.prepend(document.createTextNode(`[${element.localName}: original object retained in downloaded source; browser graphic unavailable]`)) }
    return result
  }
  if (element.namespaceURI === WORD && element.localName === 'sym') { result.textContent = `[symbol ${element.getAttributeNS(WORD, 'font') ?? ''} ${element.getAttributeNS(WORD, 'char') ?? ''}]`; return result }
  if (tag === 'td') {
    const properties = Array.from(element.children).find(node => node.namespaceURI === WORD && node.localName === 'tcPr')
    const property = (name: string) => Array.from(properties?.children ?? []).find(node => node.namespaceURI === WORD && node.localName === name)
    const span = property('gridSpan')?.getAttributeNS(WORD, 'val')
    if (span && /^[1-9][0-9]*$/.test(span)) result.setAttribute('colspan', span)
    const vertical = property('vMerge'), horizontal = property('hMerge')
    if (vertical || horizontal) {
      // shortcut: native XML fallback retains merge relations as unknown; use the original for Word layout.
      result.dataset.nativeMergeStatus = 'unknown'
      const relations: string[] = []
      if (vertical) { result.dataset.nativeVerticalMerge = vertical.getAttributeNS(WORD, 'val') || 'continue'; relations.push(`vertical=${result.dataset.nativeVerticalMerge}`) }
      if (horizontal) { result.dataset.nativeHorizontalMerge = horizontal.getAttributeNS(WORD, 'val') || 'continue'; relations.push(`horizontal=${result.dataset.nativeHorizontalMerge}`) }
      const notice = document.createElement('span')
      notice.className = 'source-object-warning'
      notice.textContent = `[Merge relation unknown: ${relations.join('; ')}. Download the original to review the merged layout.]`
      result.append(notice)
      warnings.push(`${partName}: native merged-cell layout remains unknown in this XML view; original cells, paths and merge values are retained. Download the original to review the layout.`)
    }
  }
  for (const child of Array.from(element.childNodes)) {
    if (child.nodeType === 1) result.append(nativeView(child as Element, sha, partName, images, warnings))
    else if (child.nodeType === 3) result.append(document.createTextNode(child.textContent ?? ''))
  }
  if (element.namespaceURI === WORD && element.localName === 'r') {
    const properties = Array.from(element.children).find(node => node.namespaceURI === WORD && node.localName === 'rPr')
    if (Array.from(properties?.children ?? []).some(node => ['strike', 'dstrike'].includes(node.localName) && !['0', 'false', 'off'].includes(node.getAttributeNS(WORD, 'val') ?? ''))) result.style.textDecoration = 'line-through'
  }
  return result
}
async function embeddedImages(zip: JSZip, partName: string): Promise<Map<string, string>> {
  const segments = partName.split('/'), name = segments.pop()!, folder = segments.join('/')
  const relationships = zip.file(`${folder}/_rels/${name}.rels`), images = new Map<string, string>()
  if (!relationships) return images
  const xml = new DOMParser().parseFromString(await relationships.async('string'), 'application/xml')
  for (const relation of Array.from(xml.getElementsByTagNameNS('*', 'Relationship'))) {
    const id = relation.getAttribute('Id'), target = relation.getAttribute('Target')
    if (!id || !target || relation.getAttribute('TargetMode') === 'External' || !relation.getAttribute('Type')?.endsWith('/image') || /[\\\u0000-\u001f]/.test(target)) continue
    const pieces: string[] = target.startsWith('/') ? [] : [...segments]
    let valid = true
    for (const part of target.split('/')) { if (part === '..') { if (!pieces.length) { valid = false; break }; pieces.pop() } else if (part && part !== '.') pieces.push(part) }
    if (!valid) continue
    const image = zip.file(pieces.join('/'))
    if (!image) continue
    const bytes = await image.async('uint8array')
    const mime = bytes.length >= 8 && [137,80,78,71,13,10,26,10].every((value,index) => bytes[index] === value) ? 'image/png'
      : bytes.length >= 3 && bytes[0] === 255 && bytes[1] === 216 && bytes[2] === 255 ? 'image/jpeg'
        : bytes.length >= 6 && String.fromCharCode(...bytes.slice(0,6)).match(/^GIF8[79]a$/) ? 'image/gif'
          : bytes.length >= 12 && String.fromCharCode(...bytes.slice(0,4)) === 'RIFF' && String.fromCharCode(...bytes.slice(8,12)) === 'WEBP' ? 'image/webp' : undefined
    if (mime) images.set(id, `data:${mime};base64,${await image.async('base64')}`)
  }
  return images
}

/** Full main document plus all text-bearing package parts; only exact native identities may locate. */
export async function readAdviceDocx(bytes: ArrayBuffer, expectedSha: string, positions: NativeDocxPosition[]): Promise<AdviceDocxReading> {
  if ((await sha256Bytes(bytes)).hex !== expectedSha) throw new Error('DOCX_VERSION_MISMATCH')
  const zip = await JSZip.loadAsync(bytes), container = document.createElement('div'), warnings: string[] = []
  const partNames = Object.keys(zip.files).filter(name => /^word\/(?:document|header[0-9]+|footer[0-9]+|footnotes|endnotes|comments)\.xml$/.test(name))
  for (const position of positions) {
    if (position.kind !== 'docx_native' || position.sourceSha256 !== expectedSha || !/^[a-f0-9]{64}$/.test(position.partSha256)
      || typeof position.partName !== 'string' || typeof position.nodePath !== 'string'
      || /[\\\u0000-\u001f]/.test(position.partName) || !position.partName.startsWith('word/')
      || position.partName.split('/').some(part => !part || part === '.' || part === '..')) throw new Error('DOCX_POSITION_INVALID')
    if (!partNames.includes(position.partName)) partNames.push(position.partName)
  }
  if (!partNames.includes('word/document.xml')) throw new Error('DOCX_MAIN_PART_UNAVAILABLE')
  partNames.sort((a, b) => a === 'word/document.xml' ? -1 : b === 'word/document.xml' ? 1 : a.localeCompare(b))
  for (const partName of partNames) {
    const part = zip.file(partName)
    if (!part) throw new Error('DOCX_PART_UNAVAILABLE:' + partName)
    const partBytes = await part.async('arraybuffer'), partSha = (await sha256Bytes(partBytes)).hex
    if (positions.some(position => position.partName === partName && position.partSha256 !== partSha)) throw new Error('DOCX_PART_VERSION_MISMATCH')
    const xml = new DOMParser().parseFromString(new TextDecoder().decode(partBytes), 'application/xml')
    if (xml.getElementsByTagName('parsererror').length) throw new Error('DOCX_PART_INVALID:' + partName)
    const section = document.createElement('section'), heading = document.createElement('h3')
    section.dataset.sourcePart = partName; heading.textContent = partName; section.append(heading)
    let flow: HTMLElement | undefined
    if (partName === 'word/document.xml') {
      try {
        const reading = await convertDocumentReading(bytes, expectedSha)
        warnings.push(...(reading.warnings ?? []))
        if (reading.coverage?.complete && !['del', 'moveFrom', 'moveTo', 'sym', 'altChunk', 'object'].some(name => xml.getElementsByTagNameNS(WORD, name).length)) {
          flow = document.createElement('div'); flow.innerHTML = reading.html
          for (const rendered of Array.from(flow.querySelectorAll<HTMLElement>('[data-native-paragraph]'))) {
            const node = Array.from(xml.getElementsByTagNameNS(WORD, 'p')).find(node => legacyPath(node) === rendered.dataset.nativeParagraph)
            if (node) rendered.dataset.sourceNode = docxPositionKey({ sourceSha256: expectedSha, partName, nodePath: clarkPath(node) })
          }
          // Bind complete tables/rows/cells by their exact descendant paragraph identities, including duplicates.
          for (const name of ['tbl', 'tr', 'tc']) for (const native of Array.from(xml.getElementsByTagNameNS(WORD, name))) {
            const paragraphs = Array.from(native.getElementsByTagNameNS(WORD, 'p'))
            const nativeParagraphs = paragraphs.map(node => docxPositionKey({ sourceSha256: expectedSha, partName, nodePath: clarkPath(node) }))
            const blocks = Array.from(flow.querySelectorAll<HTMLElement>('[data-source-node]')).filter(block => nativeParagraphs.includes(block.dataset.sourceNode!))
            const selectionBlocks = name === 'tr' ? blocks.filter(block => block.dataset.nativeMergedContinuation !== 'true') : blocks
            const firstParagraph = paragraphs[nativeParagraphs.indexOf(selectionBlocks[0]?.dataset.sourceNode ?? '')]
            const selector = name === 'tbl' ? 'table' : name === 'tr' ? 'tr' : 'td,th'
            let target = selectionBlocks[0]?.closest<HTMLElement>(selector)
            for (let ancestor = firstParagraph?.parentElement; ancestor && ancestor !== native; ancestor = ancestor.parentElement)
              if (ancestor.namespaceURI === WORD && ancestor.localName === name) target = target?.parentElement?.closest<HTMLElement>(selector) ?? null
            if (target && blocks.length === nativeParagraphs.length && selectionBlocks.every(block => target!.contains(block))) {
              const marker = name === 'tc' ? document.createElement('span') : target
              marker.dataset.sourceNode = docxPositionKey({ sourceSha256: expectedSha, partName, nodePath: clarkPath(native) })
              if (marker !== target) target.prepend(marker)
              if (blocks.some(block => block.dataset.nativeMergedContinuation === 'true')) warnings.push('An empty native merged-cell continuation is anchored inside its verified spanning cell; the original XML cell identity is retained.')
            }
          }
        } else warnings.push('Incomplete visual conversion: displaying every original XML paragraph/table instead; Word layout is not reproduced.')
      } catch { warnings.push('Visual conversion unavailable: displaying original XML paragraphs/tables; Word layout is not reproduced.') }
    }
    if (!flow) {
      flow = nativeView(xml.documentElement, expectedSha, partName, await embeddedImages(zip, partName), warnings)
      if (partName !== 'word/document.xml') warnings.push(`${partName}: original text/structure shown without Word pagination or physical placement.`)
    }
    const partPositions = [...new Map(positions.filter(position => position.partName === partName).map(position => [docxPositionKey(position), position])).values()]
    for (const position of partPositions) {
      const native = resolveDocxNode(xml, position.nodePath)
      if (!native) continue
      const key = docxPositionKey(position)
      const targets = Array.from(flow.querySelectorAll<HTMLElement>('[data-source-node]')).filter(node => node.dataset.sourceNode === key)
      if (flow.dataset.sourceNode === key) targets.push(flow)
      if (targets.length === 1) { targets[0]!.dataset.adviceTarget = key; continue }
      // A run/text reference can locate its verified containing paragraph, never a similar passage.
      let paragraph: Element | null = native
      while (paragraph && !(paragraph.namespaceURI === WORD && paragraph.localName === 'p')) paragraph = paragraph.parentElement
      const paragraphKey = paragraph && docxPositionKey({ sourceSha256: expectedSha, partName, nodePath: clarkPath(paragraph) })
      const blocks = Array.from(flow.querySelectorAll<HTMLElement>('[data-source-node]')).filter(node => node.dataset.sourceNode === paragraphKey)
      if (blocks.length === 1) {
        const marker = document.createElement('span'); marker.dataset.adviceTarget = key; blocks[0]!.prepend(marker)
        warnings.push('A run/text citation locates its exact containing paragraph; character-level highlighting is unavailable.')
      }
    }
    section.append(flow); container.append(section)
  }
  const targetKeys = Array.from(container.querySelectorAll<HTMLElement>('[data-advice-target]')).map(node => node.dataset.adviceTarget!)
  return { html: container.innerHTML, warnings: [...new Set(warnings)], targetKeys,
    missingTargets: positions.map(docxPositionKey).filter(key => targetKeys.filter(value => value === key).length !== 1) }
}
