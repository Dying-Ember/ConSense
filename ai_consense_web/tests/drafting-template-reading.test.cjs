const assert = require('node:assert/strict')
const path = require('node:path')
const { JSDOM } = require('jsdom')
const window = new JSDOM('').window
const { loadTypeScript } = require('./helpers/load-typescript.cjs')
const { prepareTemplateReading } = loadTypeScript(path.join(__dirname, '../src/drafting/template-reading.ts'), { globals: { document: window.document }, dependencies: { dompurify: { default: require('dompurify')(window) } } })
const metadata = { fileKey: 'NTT', sourceHash: 'current', format: 'docx', catalogueSourceVerified: true, paragraphs: [{ id: 'native-1', ordinal: 1, text: 'Invitation' }, { id: 'native-2', ordinal: 2, text: 'Repeat' }, { id: 'native-3', ordinal: 3, text: '' }, { id: 'native-4', ordinal: 4, text: 'Repeat' }] }
const prepared = prepareTemplateReading('<h1>Invitation</h1><table><tr><td><p>Repeat</p></td></tr></table><p>Repeat</p><img src="https://tracker.invalid/a"><script>alert(1)</script><a href="javascript:alert(1)" onclick="alert(2)">Bad link</a><a href="https://tracker.invalid">External</a>', metadata)
const doc = new JSDOM(prepared.html).window.document
assert.equal(doc.querySelector('script,img,[onclick],[href]'), null)
assert.equal(doc.querySelector('h1').dataset.nativeParagraph, 'native-1')
assert.deepEqual([...doc.querySelectorAll('p')].map(node => node.dataset.nativeParagraph), ['native-2', 'native-4'])
assert.ok(doc.querySelector('table td p'))
console.log('PASS: untrusted HTML removes script/image/unsafe and external URL surfaces, retains table content, and matches repeated paragraphs using current native order')

const restructured = prepareTemplateReading('<p>Repeat</p><p>Unrelated conversion content</p>', metadata)
assert.equal(restructured.mappedParagraphIds.length, 0)
console.log('PASS: reordered/merged conversion cannot assign a repeated native paragraph identity by a guessed ordinal')
