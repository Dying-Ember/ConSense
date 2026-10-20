const assert = require('node:assert/strict')
const path = require('node:path')
const fs = require('node:fs')
const { JSDOM } = require('jsdom')
const window = new JSDOM('').window
const { loadTypeScript } = require('./helpers/load-typescript.cjs')
const { prepareTemplateReading } = loadTypeScript(path.join(__dirname, '../src/drafting/template-reading.ts'), { globals: { document: window.document }, dependencies: { dompurify: { default: require('dompurify')(window) }, './reading-list-markers': loadTypeScript(path.join(__dirname, '../src/drafting/reading-list-markers.ts')) } })
const metadata = { fileKey: 'NTT', sourceHash: 'current', format: 'docx', catalogueSourceVerified: true, paragraphs: [{ id: 'native-1', ordinal: 1, text: 'Invitation' }, { id: 'native-2', ordinal: 2, text: 'Repeat' }, { id: 'native-3', ordinal: 3, text: '' }, { id: 'native-4', ordinal: 4, text: 'Repeat' }] }
const prepared = prepareTemplateReading('<h1>Invitation</h1><table><tr><td><p>Repeat</p></td></tr></table><p>Repeat</p><img src="https://tracker.invalid/a"><script>alert(1)</script><a href="javascript:alert(1)" onclick="alert(2)">Bad link</a><a href="https://tracker.invalid">External</a>', metadata)
const doc = new JSDOM(prepared.html).window.document
assert.equal(doc.querySelector('script,img,[onclick],[href]'), null)
assert.equal(doc.querySelector('h1').dataset.nativeParagraph, 'native-1')
assert.deepEqual([...doc.querySelectorAll('p')].map(node => node.dataset.nativeParagraph), ['native-2', 'native-4'])
assert.ok(doc.querySelector('table td p'))
assert.equal(prepared.numberingUnverified, false)
console.log('PASS: untrusted HTML removes script/image/unsafe and external URL surfaces, retains table content, and matches repeated paragraphs using current native order')

const restructured = prepareTemplateReading('<p>Repeat</p><p>Unrelated conversion content</p>', metadata)
assert.equal(restructured.mappedParagraphIds.length, 0)
console.log('PASS: reordered/merged conversion cannot assign a repeated native paragraph identity by a guessed ordinal')

const numberedMetadata = { ...metadata, paragraphs: ['First source item', 'Second source item', 'Third source item', '(d) Literal fourth item'].map((text, index) => ({ id: `list-${index + 1}`, ordinal: index + 1, text })) }
const numbered = prepareTemplateReading('<ol><li>First source item</li></ol><p></p><ol><li>Second source item</li></ol><p></p><ol><li>Third source item</li></ol><p>(d) Literal fourth item</p>', numberedMetadata)
const numberedFlow = window.document.createElement('div'); numberedFlow.innerHTML = numbered.html
const markerStyle = window.document.createElement('style'); markerStyle.textContent = fs.readFileSync(path.join(__dirname, '../src/styles/main.css'), 'utf8')
window.document.head.append(markerStyle); window.document.body.append(numberedFlow)
assert.ok([...numberedFlow.querySelectorAll('ol')].every(list => window.getComputedStyle(list).listStyleType === 'none'), 'Interrupted Word lists must not silently acquire repeated browser 1. markers')
assert.equal(numbered.numberingUnverified, true)
assert.deepEqual([...numberedFlow.querySelectorAll('[data-native-paragraph]')].map(node => node.textContent), numberedMetadata.paragraphs.map(paragraph => paragraph.text))
assert.match(numberedFlow.textContent, /\(d\) Literal fourth item/)
assert.equal(numberedFlow.querySelectorAll('ol').length, 3, 'List interruption and native structure remain intact')
const nested = prepareTemplateReading('<ul class="hostile" style="list-style-type:disc"><li>(a) Literal<ul><li>Nested item</li></ul></li></ul>', { ...metadata, paragraphs: [] })
numberedFlow.innerHTML = nested.html
assert.equal(nested.numberingUnverified, true)
assert.equal(numberedFlow.querySelector('[style],.hostile'), null, 'Source styles and classes remain excluded before trusted markers are added')
assert.ok([...numberedFlow.querySelectorAll('ul,li')].every(node => window.getComputedStyle(node).listStyleType === 'none'))
assert.equal(numberedFlow.textContent, '(a) LiteralNested item', 'No marker text or paragraph offset pollution')
console.log('PASS: unverified converted markers are hidden without changing literal labels, paragraph text or native identities')
