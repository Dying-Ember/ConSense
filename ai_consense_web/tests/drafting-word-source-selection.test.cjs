const assert = require('node:assert/strict')
const path = require('node:path')
const { JSDOM } = require('jsdom')
const { loadTypeScript } = require('./helpers/load-typescript.cjs')
const { webcrypto } = require('node:crypto')
const review = loadTypeScript(path.join(__dirname, '../src/drafting/source-review-state.ts'), { dependencies: { './source-review/value-dependencies.js': './source-review/value-dependencies.js' } })
const { bindWordReview, selectWordReviewRange, wordReviewRanges } = loadTypeScript(path.join(__dirname, '../src/drafting/word-source-selection.ts'), { dependencies: { './source-review-state': review } })

const sha = 'a'.repeat(64), paragraphSha = 'b'.repeat(64)
const ns = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'
const wordPath = index => `word/document.xml#/w:document[1]/w:body[1]/w:p[${index}]`
const expanded = index => `/{${ns}}document[1]/{${ns}}body[1]/{${ns}}p[${index}]`
const paragraph = (index, text) => ({ id: `word/document.xml#/x:document[1]/x:body[1]/x:p[${index}]`, ordinal: index, text, sourceSha256: sha, paragraphSha256: paragraphSha, sourcePosition: { partName: 'word/document.xml', sourceSha256: sha, nodePath: expanded(index) } })
const reading = { id: '41', format: 'docx', canEdit: true, sourceSha256: sha, paragraphs: [paragraph(1, 'First 😀 line'), paragraph(2, 'Second line')] }
const converted = { docxSha256: sha, paragraphs: [{ id: wordPath(1), text: 'First 😀 line' }, { id: wordPath(2), text: 'Second line' }] }
const document = new JSDOM(`<main><p data-native-paragraph="${wordPath(1)}">First 😀 line</p><p data-native-paragraph="${wordPath(2)}">Second line</p></main>`).window.document
const root = document.querySelector('main'), first = root.children[0], second = root.children[1]
assert.equal(bindWordReview(root, converted, reading), 2, 'Different XML prefixes bind using the same expanded native path')
assert.equal(first.dataset.nativeId, reading.paragraphs[0].id)
const single = document.createRange(); single.setStart(first.firstChild, 6); single.setEnd(first.firstChild, 8)
assert.equal(selectWordReviewRange(root, reading, single).text, '😀', 'Selection uses UTF-16 source offsets')
const both = document.createRange(); both.setStart(first.firstChild, 6); both.setEnd(second.firstChild, 6)
const selected = selectWordReviewRange(root, reading, both)
assert.equal(selected.native.spans.length, 2)
assert.deepEqual(Array.from(selected.native.spans, span => span.expectedText), ['😀 line', 'Second'])
first.innerHTML = 'First <b>😀</b> line'
assert.equal(bindWordReview(root, converted, reading), 2)
const beforeHighlight = root.innerHTML
const highlighted = wordReviewRanges(root, reading, selected.native.spans)
assert.equal(highlighted.unresolved, 0)
assert.deepEqual(Array.from(highlighted.ranges, range => range.toString()), ['😀 line', 'Second'], 'Highlight spans preserve formatting-run and cross-paragraph offsets')
assert.equal(root.innerHTML, beforeHighlight, 'Calculating highlights never changes source DOM or editable text')
assert.equal(wordReviewRanges(root, reading, [{ ...selected.native.spans[0], sourceSha256: 'c'.repeat(64) }]).unresolved, 1, 'Changed source bytes cannot be highlighted')
assert.equal(wordReviewRanges(root, reading, [{ ...selected.native.spans[0], paragraphSha256: 'd'.repeat(64) }]).unresolved, 1, 'Changed source paragraph cannot be highlighted')
assert.equal(wordReviewRanges(root, reading, [{ ...selected.native.spans[0], expectedText: 'not source text' }]).unresolved, 1, 'Changed expected text cannot be highlighted')
first.textContent = 'First 😀 line'
bindWordReview(root, converted, reading)
both.setStart(first.firstChild, 6); both.setEnd(second.firstChild, 6)

const omitted = document.createElement('p'); omitted.textContent = 'Unmapped text'; root.insertBefore(omitted, second)
assert.throws(() => selectWordReviewRange(root, reading, both), /unbound source text/, 'A cross-paragraph selection never silently drops an unmapped passage')
omitted.remove()
first.textContent = 'First X line'
const changed = document.createRange(); changed.setStart(first.firstChild, 0); changed.setEnd(first.firstChild, 5)
assert.throws(() => selectWordReviewRange(root, reading, changed), /Source selection changed|source text/, 'Changed rendered text is never an edit authority')
first.textContent = 'First 😀 line'
assert.equal(bindWordReview(root, { ...converted, docxSha256: 'c'.repeat(64) }, reading), 0)
assert.equal(first.dataset.nativeId, undefined, 'Source changes clear all previously verified bindings')
assert.equal(bindWordReview(root, converted, { ...reading, canEdit: false }), 0)
assert.equal(bindWordReview(root, converted, { ...reading, paragraphs: [...reading.paragraphs, { ...reading.paragraphs[0], id: 'duplicate' }] }), 1, 'Duplicate backend identities cannot be guessed')
assert.equal(bindWordReview(root, converted, { ...reading, paragraphs: [reading.paragraphs[0], { ...reading.paragraphs[1], id: reading.paragraphs[0].id }] }), 0, 'A reused backend paragraph ID cannot authorize either paragraph')
const duplicate = first.cloneNode(true); root.append(duplicate)
assert.equal(bindWordReview(root, converted, reading), 1, 'Duplicate rendered identity cannot be selected')
duplicate.remove()
first.innerHTML = 'First<br>😀 line'
assert.equal(bindWordReview(root, converted, reading), 1, 'DOM line-break mismatch cannot be used as a Word offset')
const emptyBlock = document.createElement('p'); emptyBlock.dataset.nativeParagraph = wordPath(3); root.append(emptyBlock)
const hiddenBlock = document.createElement('p'); hiddenBlock.dataset.nativeParagraph = wordPath(4); hiddenBlock.hidden = true; hiddenBlock.textContent = 'Hidden'; root.append(hiddenBlock)
const withEmpty = { ...converted, paragraphs: [...converted.paragraphs, { id: wordPath(3), text: '' }, { id: wordPath(4), text: 'Hidden' }] }
const withEmptyReading = { ...reading, paragraphs: [...reading.paragraphs, paragraph(3, ''), paragraph(4, 'Hidden')] }
assert.equal(bindWordReview(root, withEmpty, withEmptyReading), 1, 'Empty and hidden paragraphs do not count as selectable text')
console.log('PASS: Word selections require source identity, unique native paths, exact text and do not skip unmapped content')

async function actualDocxConversion() {
  const window = new JSDOM('').window
  const JSZip = require('jszip')
  const portableSha256 = loadTypeScript(path.join(__dirname, '../src/api/sha256Bytes.ts'), { globals: { crypto: {}, setTimeout }, dependencies: { '@noble/hashes/sha256': require('@noble/hashes/sha256') } })
  const converter = loadTypeScript(path.join(__dirname, '../src/drafting/document-reading.ts'), {
    globals: { crypto: webcrypto, document: window.document, DOMParser: window.DOMParser, XMLSerializer: window.XMLSerializer, NodeFilter: window.NodeFilter },
    dependencies: { '@/api/sha256Bytes': portableSha256, jszip: { default: JSZip }, 'mammoth/mammoth.browser.min.js': { default: require('mammoth/mammoth.browser.min.js') }, dompurify: { default: require('dompurify')(window) }, './document-reading-text': loadTypeScript(path.join(__dirname, '../src/drafting/document-reading-text.ts')), './reading-list-markers': loadTypeScript(path.join(__dirname, '../src/drafting/reading-list-markers.ts')) }
  })
  const zip = new JSZip()
  zip.file('[Content_Types].xml', '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>')
  zip.file('_rels/.rels', '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>')
  zip.file('word/document.xml', `<x:document xmlns:x="${ns}"><x:body><x:p><x:r><x:t>Repeated text</x:t></x:r></x:p><x:p><x:r><x:t>Repeated text</x:t></x:r></x:p><x:p><x:r><x:t>Choose Section _____*</x:t></x:r><x:r><x:br x:type="page"/></x:r></x:p><x:p><x:r><x:t>Ordinary break</x:t><x:br/></x:r></x:p><x:p><x:r><x:t>Before</x:t><x:br x:type="page"/><x:t>After</x:t></x:r></x:p></x:body></x:document>`)
  const bytes = await zip.generateAsync({ type: 'arraybuffer' })
  const actualSha = Buffer.from(await webcrypto.subtle.digest('SHA-256', bytes)).toString('hex')
  const rendered = await converter.convertDocumentReading(bytes, actualSha)
  const texts = ['Repeated text', 'Repeated text', 'Choose Section _____*\n', 'Ordinary break\n', 'Before\nAfter']
  const reviewReading = { ...reading, sourceSha256: actualSha, paragraphs: texts.map((value, i) => ({ ...paragraph(i + 1, value), sourceSha256: actualSha, sourcePosition: { partName: 'word/document.xml', sourceSha256: actualSha, nodePath: expanded(i + 1) } })) }
  const surface = window.document.createElement('main'); surface.innerHTML = rendered.html
  assert.equal(bindWordReview(surface, rendered, reviewReading), 3, 'A trailing page break retains its native offset; ordinary and embedded breaks remain unbound')
  const blocks = surface.querySelectorAll('[data-native-id]'), text = blocks[1].firstChild
  const range = window.document.createRange(); range.setStart(text, 0); range.setEnd(text, 8)
  const picked = selectWordReviewRange(surface, reviewReading, range)
  assert.equal(picked.native.paragraphId, reviewReading.paragraphs[1].id)
  assert.equal(picked.text, 'Repeated')
  const pageBlock = blocks[2], pageText = pageBlock.firstChild, pageRange = window.document.createRange()
  pageRange.setStart(pageText, 7); pageRange.setEnd(pageText, pageText.textContent.length - 1)
  const pageSelection = selectWordReviewRange(surface, reviewReading, pageRange)
  assert.equal(pageSelection.text, 'Section _____*')
  assert.equal(pageSelection.native.end, texts[2].length - 1, 'The selected editable text ends before the page break')
  assert.equal(pageSelection.native.sourceSha256, actualSha)
  const highlights = wordReviewRanges(surface, reviewReading, [pageSelection.native])
  assert.equal(highlights.unresolved, 0); assert.equal(highlights.ranges[0].toString(), pageSelection.text)
  assert.equal(wordReviewRanges(surface, reviewReading, [{ ...pageSelection.native, expectedText: 'altered' }]).unresolved, 1)
  assert.equal(bindWordReview(surface, { ...rendered, docxSha256: '0'.repeat(64) }, reviewReading), 0, 'Source identity remains required')
  console.log('PASS: converted Word binds repeated clauses by exact native identity and selects the intended one')
}
actualDocxConversion().catch(error => { console.error(error); process.exitCode = 1 })
