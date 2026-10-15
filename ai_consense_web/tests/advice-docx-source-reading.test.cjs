const assert = require('node:assert/strict')
const path = require('node:path')
const { test } = require('node:test')
const { webcrypto } = require('node:crypto')
const { JSDOM } = require('jsdom')
const JSZip = require('jszip')
const { loadVue } = require('./helpers/load-vue.cjs')
const window = new JSDOM('').window
const globals = { crypto: webcrypto, document: window.document, DOMParser: window.DOMParser, XMLSerializer: window.XMLSerializer, NodeFilter: window.NodeFilter, TextDecoder }
const { readAdviceDocx, docxPositionKey, resolveDocxNode } = loadVue(path.join(__dirname, '../src/advice/docxSourceReading.ts'), {
  globals, boundaries: { jszip: { __esModule: true, default: JSZip }, 'mammoth/mammoth.browser.min.js': { __esModule: true, default: require('mammoth/mammoth.browser.min.js') },
    dompurify: { __esModule: true, default: require('dompurify')(window) }, '@noble/hashes/sha256': require('@noble/hashes/sha256') }
})
const WORD = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'
const c = name => `{${WORD}}${name}`
const root = `/${c('document')}[1]/${c('body')}[1]`
const hash = async bytes => Buffer.from(await webcrypto.subtle.digest('SHA-256', bytes)).toString('hex')
async function docx(body, extras = {}) {
  const zip = new JSZip()
  zip.file('[Content_Types].xml', '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>')
  zip.file('_rels/.rels', '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>')
  zip.file('word/document.xml', `<w:document xmlns:w="${WORD}" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><w:body>${body}</w:body></w:document>`)
  for (const [name, value] of Object.entries(extras)) zip.file(name, value)
  const bytes = await zip.generateAsync({ type: 'arraybuffer' }), sha = await hash(bytes)
  const position = async (partName, nodePath) => ({ kind: 'docx_native', sourceSha256: sha, partName, partSha256: await hash(await zip.file(partName).async('arraybuffer')), nodePath })
  return { bytes, sha, position }
}
test('repeated source text selects the unique actual table cell; entire table and neighbours remain readable', async () => {
  const source = await docx('<w:p><w:r><w:t>Repeated clause</w:t></w:r></w:p><w:tbl><w:tr><w:tc><w:p><w:r><w:t>Repeated clause</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>Opposing condition</w:t></w:r></w:p></w:tc></w:tr></w:tbl><w:p><w:r><w:t>Next parent clause</w:t></w:r></w:p>')
  const cell = await source.position('word/document.xml', `${root}/${c('tbl')}[1]/${c('tr')}[1]/${c('tc')}[1]`)
  const reading = await readAdviceDocx(source.bytes, source.sha, [cell]), flow = new JSDOM(reading.html).window.document
  const matches = [...flow.querySelectorAll('[data-advice-target]')].filter(node => node.dataset.adviceTarget === docxPositionKey(cell))
  assert.equal(matches.length, 1); assert.equal(matches[0].closest('td').tagName, 'TD'); assert.match(matches[0].closest('td').textContent, /Repeated clause/)
  assert.equal(flow.querySelectorAll('table tr td').length, 2)
  assert.match(flow.body.textContent, /Opposing condition/); assert.match(flow.body.textContent, /Next parent clause/)
  assert.equal(reading.missingTargets.length, 0)
})

test('repeated table rows bind the exact second native row and preserve the complete table', async () => {
  const source = await docx('<w:p><w:r><w:t>Parent context</w:t></w:r></w:p><w:tbl><w:tr><w:tc><w:p><w:r><w:t>Repeated clause</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>First condition</w:t></w:r></w:p></w:tc></w:tr><w:tr><w:tc><w:p><w:r><w:t>Repeated clause</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>Second condition</w:t></w:r></w:p></w:tc></w:tr></w:tbl><w:p><w:r><w:t>Next clause</w:t></w:r></w:p>')
  const row = await source.position('word/document.xml', `${root}/${c('tbl')}[1]/${c('tr')}[2]`)
  const reading = await readAdviceDocx(source.bytes, source.sha, [row, row]), flow = new JSDOM(reading.html).window.document
  const targets = [...flow.querySelectorAll('[data-advice-target]')].filter(node => node.dataset.adviceTarget === docxPositionKey(row))
  assert.equal(targets.length, 1, 'Repeated references create one exact native target')
  const selected = targets[0].closest('tr')
  assert.equal(selected, flow.querySelectorAll('table tr')[1]); assert.match(selected.textContent, /Second condition/); assert.doesNotMatch(selected.textContent, /First condition/)
  assert.equal(flow.querySelectorAll('table tr').length, 2); assert.match(flow.body.textContent, /Parent context/); assert.match(flow.body.textContent, /Next clause/)
  assert.equal(reading.missingTargets.length, 0)
  await assert.rejects(readAdviceDocx(source.bytes, source.sha, [{ ...row, partSha256: 'a'.repeat(64) }, row]), /DOCX_PART_VERSION_MISMATCH/)
  await assert.rejects(readAdviceDocx(source.bytes, source.sha, [row, { ...row, partSha256: 'a'.repeat(64) }]), /DOCX_PART_VERSION_MISMATCH/)
})
test('merged-cell continuation retains a separate original cell key without overwriting its restart cell', async () => {
  const source = await docx('<w:tbl><w:tr><w:tc><w:tcPr><w:vMerge w:val="restart"/></w:tcPr><w:p><w:r><w:t>Shared proviso</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>First condition</w:t></w:r></w:p></w:tc></w:tr><w:tr><w:tc><w:tcPr><w:vMerge/></w:tcPr><w:p/></w:tc><w:tc><w:p><w:r><w:t>Second condition</w:t></w:r></w:p></w:tc></w:tr></w:tbl>')
  const restart = await source.position('word/document.xml', `${root}/${c('tbl')}[1]/${c('tr')}[1]/${c('tc')}[1]`)
  const continuation = await source.position('word/document.xml', `${root}/${c('tbl')}[1]/${c('tr')}[2]/${c('tc')}[1]`)
  const secondRow = await source.position('word/document.xml', `${root}/${c('tbl')}[1]/${c('tr')}[2]`)
  const reading = await readAdviceDocx(source.bytes, source.sha, [restart, continuation, secondRow])
  assert.equal(reading.missingTargets.length, 0)
  assert.ok(reading.targetKeys.includes(docxPositionKey(restart))); assert.ok(reading.targetKeys.includes(docxPositionKey(continuation)))
  assert.notEqual(docxPositionKey(restart), docxPositionKey(continuation))
  const flow = new JSDOM(reading.html).window.document
  const selectedRow = [...flow.querySelectorAll('[data-advice-target]')].find(node => node.dataset.adviceTarget === docxPositionKey(secondRow))
  assert.ok(selectedRow === flow.querySelectorAll('table tr')[1], 'The second native row locates its own row even when its first cell continues a prior merge')
  assert.match(reading.warnings.join(' '), /merged-cell continuation/)
})

test('native fallback preserves move revisions and direct cell spans, and exposes unsupported merge relations', async () => {
  const source = await docx('<w:p><w:moveFrom w:id="7"><w:r><w:t>Moved from here</w:t></w:r></w:moveFrom><w:moveTo w:id="8"><w:r><w:t>Moved to here</w:t></w:r></w:moveTo></w:p><w:tbl><w:tr><w:tc><w:tcPr><w:vMerge w:val="restart"/><w:hMerge w:val="restart"/></w:tcPr><w:p><w:r><w:t>Shared condition</w:t></w:r></w:p><w:tbl><w:tr><w:tc><w:tcPr><w:gridSpan w:val="2"/></w:tcPr><w:p><w:r><w:t>Nested span</w:t></w:r></w:p></w:tc></w:tr></w:tbl></w:tc></w:tr><w:tr><w:tc><w:tcPr><w:vMerge/><w:hMerge/></w:tcPr><w:p/></w:tc></w:tr></w:tbl>')
  const continuation = await source.position('word/document.xml', `${root}/${c('tbl')}[1]/${c('tr')}[2]/${c('tc')}[1]`)
  const reading = await readAdviceDocx(source.bytes, source.sha, [continuation]), flow = new JSDOM(reading.html).window.document
  assert.equal(flow.querySelector('del[data-native-revision="moveFrom"]').textContent, 'Moved from here')
  assert.equal(flow.querySelector('ins[data-native-revision="moveTo"]').textContent, 'Moved to here')
  assert.equal(flow.querySelector('del').dataset.nativeRevisionId, '7')
  const outer = flow.querySelector('table > tbody > tr > td')
  assert.equal(outer.colSpan, 1, 'An outer cell cannot inherit a nested cell gridSpan')
  assert.equal(outer.querySelector('table td').colSpan, 2)
  assert.equal(outer.dataset.nativeVerticalMerge, 'restart')
  assert.equal(outer.dataset.nativeHorizontalMerge, 'restart')
  const target = flow.querySelector('[data-advice-target]')
  assert.equal(target.dataset.nativeVerticalMerge, 'continue')
  assert.equal(target.dataset.nativeHorizontalMerge, 'continue')
  assert.equal(target.dataset.nativeMergeStatus, 'unknown')
  assert.match(target.textContent, /[Mm]erge.*continue/)
  assert.match(reading.warnings.join(' '), /word\/document.xml.*merge.*unknown.*original/i)
  assert.equal(reading.missingTargets.length, 0)
  assert.match(flow.body.textContent, /Shared condition/)
  assert.match(flow.body.textContent, /Nested span/)
})

test('a row beginning inside a nested table keeps its outer and inner native identities distinct', async () => {
  const source = await docx('<w:tbl><w:tr><w:tc><w:tbl><w:tr><w:tc><w:p><w:r><w:t>Nested condition</w:t></w:r></w:p></w:tc></w:tr></w:tbl><w:p/></w:tc></w:tr></w:tbl>')
  const table = await source.position('word/document.xml', `${root}/${c('tbl')}[1]`)
  const outer = await source.position('word/document.xml', `${table.nodePath}/${c('tr')}[1]`)
  const inner = await source.position('word/document.xml', `${outer.nodePath}/${c('tc')}[1]/${c('tbl')}[1]/${c('tr')}[1]`)
  const reading = await readAdviceDocx(source.bytes, source.sha, [table, outer, inner]), flow = new JSDOM(reading.html).window.document
  const target = position => [...flow.querySelectorAll('[data-advice-target]')].find(node => node.dataset.adviceTarget === docxPositionKey(position))
  assert.equal(target(table), flow.querySelector('table')); assert.equal(target(outer), flow.querySelector('table > tbody > tr'))
  assert.equal(target(inner), flow.querySelector('table table tr')); assert.notEqual(target(outer), target(inner)); assert.equal(reading.missingTargets.length, 0)
})
test('header, footer, comments and footnotes stay distinct package parts with exact digest-bound paths', async () => {
  const source = await docx('<w:p><w:r><w:t>Main same text</w:t></w:r></w:p>', {
    'word/header1.xml': `<x:hdr xmlns:x="${WORD}"><x:p><x:r><x:t>Main same text</x:t></x:r></x:p><x:p><x:r><x:t>Header neighbour</x:t></x:r></x:p></x:hdr>`,
    'word/footer1.xml': `<w:ftr xmlns:w="${WORD}"><w:p><w:r><w:t>Footer</w:t></w:r></w:p></w:ftr>`,
    'word/comments.xml': `<w:comments xmlns:w="${WORD}"><w:comment><w:p><w:r><w:t>Comment context</w:t></w:r></w:p></w:comment></w:comments>`,
    'word/footnotes.xml': `<w:footnotes xmlns:w="${WORD}"><w:footnote><w:p><w:r><w:t>Footnote condition</w:t></w:r></w:p></w:footnote></w:footnotes>`
  })
  const main = await source.position('word/document.xml', `${root}/${c('p')}[1]`)
  const header = await source.position('word/header1.xml', `/${c('hdr')}[1]/${c('p')}[1]`)
  const reading = await readAdviceDocx(source.bytes, source.sha, [main, header]), document = new JSDOM(reading.html).window.document
  const mainLocated = [...document.querySelectorAll('[data-advice-target]')].find(node => node.dataset.adviceTarget === docxPositionKey(main))
  const located = [...document.querySelectorAll('[data-advice-target]')].find(node => node.dataset.adviceTarget === docxPositionKey(header))
  assert.equal(mainLocated.closest('section').dataset.sourcePart, 'word/document.xml')
  assert.equal(located.closest('section').dataset.sourcePart, 'word/header1.xml')
  assert.equal(document.querySelectorAll('[data-advice-target]').length, 2); assert.equal(reading.missingTargets.length, 0)
  assert.match(document.body.textContent, /Header neighbour/); assert.match(document.body.textContent, /Footnote condition/)
  assert.match(document.body.textContent, /Comment context/); assert.match(document.body.textContent, /Footer/)
  await assert.rejects(readAdviceDocx(source.bytes, source.sha, [{ ...header, partSha256: 'a'.repeat(64) }]), /DOCX_PART_VERSION_MISMATCH/)
})
test('deleted clauses and embedded safe original images remain visible without loading external relationships', async () => {
  const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLbtAAAAABJRU5ErkJggg==', 'base64')
  const source = await docx('<w:p><w:del><w:r><w:delText>Deleted proviso</w:delText></w:r></w:del><w:ins><w:r><w:t>New proviso</w:t></w:r></w:ins></w:p><w:p><w:r><w:drawing><a:blip xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" r:embed="image1"/></w:drawing></w:r></w:p>', {
    'word/_rels/document.xml.rels': '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="image1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/source.png"/><Relationship Id="external" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="https://example.test/image" TargetMode="External"/></Relationships>',
    'word/media/source.png': png
  })
  const reading = await readAdviceDocx(source.bytes, source.sha, []), document = new JSDOM(reading.html).window.document
  assert.match(document.querySelector('del').textContent, /Deleted proviso/); assert.match(document.body.textContent, /New proviso/)
  assert.match(document.querySelector('img').src, /^data:image\/png;base64,/)
  assert.doesNotMatch(reading.html, /https:\/\/example.test/)
})
test('missing native positions do not match similar text and namespace paths reject wrong roots', async () => {
  const source = await docx('<w:p><w:r><w:t>Repeated</w:t></w:r></w:p>')
  const missing = await source.position('word/document.xml', `${root}/${c('p')}[2]`)
  const reading = await readAdviceDocx(source.bytes, source.sha, [missing])
  assert.equal(reading.targetKeys.length, 0); assert.equal(reading.missingTargets.length, 1)
  await assert.rejects(readAdviceDocx(source.bytes, 'a'.repeat(64), []), /DOCX_VERSION_MISMATCH/)
  const xml = new window.DOMParser().parseFromString(`<w:document xmlns:w="${WORD}"><w:body><w:p/></w:body></w:document>`, 'application/xml')
  assert.equal(resolveDocxNode(xml, '/{wrong}document[1]'), undefined)
  assert.equal(resolveDocxNode(xml, `${root}/${c('p')}[1]`).localName, 'p')
})

test('native fallback preserves fraction and subscript notation when revisions require the original XML view', async () => {
  const source = await docx('<w:p><w:del><w:r><w:delText>Old revision</w:delText></w:r></w:del></w:p><w:p><w:r><w:t>Ratio: </w:t></w:r><m:oMath xmlns:m="http://schemas.openxmlformats.org/officeDocument/2006/math"><m:f><m:num><m:r><m:t>1</m:t></m:r></m:num><m:den><m:sSub><m:e><m:r><m:t>B</m:t></m:r></m:e><m:sub><m:r><m:t>0</m:t></m:r></m:sub></m:sSub></m:den></m:f></m:oMath></w:p>')
  const reading = await readAdviceDocx(source.bytes, source.sha, []), flow = new JSDOM(reading.html).window.document
  assert.match(flow.body.textContent, /Ratio: \(1\) \/ \(B0\)/)
  assert.equal(flow.querySelector('sub').textContent, '0')
  assert.match(flow.querySelector('del').textContent, /Old revision/)
})
test('unsupported formula notation stays unavailable in all source parts rather than becoming literal numbers', async () => {
  const source = await docx('<w:p><w:r><w:t>Body</w:t></w:r></w:p>', {
    'word/header1.xml': `<w:hdr xmlns:w="${WORD}" xmlns:m="http://schemas.openxmlformats.org/officeDocument/2006/math"><w:p><m:oMath><m:rad><m:e><m:r><m:t>12</m:t></m:r></m:e></m:rad></m:oMath></w:p></w:hdr>`
  })
  const reading = await readAdviceDocx(source.bytes, source.sha, []), flow = new JSDOM(reading.html).window.document
  assert.equal(flow.querySelectorAll('[data-formula-status="unavailable"]').length, 1)
  assert.doesNotMatch(flow.querySelector('[data-source-part="word/header1.xml"]').textContent, /12/)
  assert.match(reading.warnings.join(' '), /formula.*unsupported notation/i)
  assert.match(flow.body.textContent, /[Dd]ownload the original/)
})
