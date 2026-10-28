const assert = require('node:assert/strict')
const path = require('node:path')
const { webcrypto } = require('node:crypto')
const { JSDOM } = require('jsdom')
const window = new JSDOM('<main id="app"></main>', { url: 'http://localhost/' }).window
for (const key of ['window', 'document', 'Element', 'HTMLElement', 'SVGElement', 'Node', 'Document', 'ShadowRoot']) globalThis[key] = key === 'window' ? window : window[key]
const vue = require('vue')
const JSZip = require('jszip')
const { loadVue } = require('./helpers/load-vue.cjs')
const { settle } = require('./helpers/render-controls.cjs')
const copy = value => JSON.parse(JSON.stringify(value))
const ns = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'
const paragraphId = n => `word/document.xml#/w:document[1]/w:body[1]/w:p[${n}]`
const sourcePath = n => `/{${ns}}document[1]/{${ns}}body[1]/{${ns}}p[${n}]`

async function fixture() {
  const zip = new JSZip()
  zip.file('[Content_Types].xml', '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>')
  zip.file('_rels/.rels', '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>')
  zip.file('word/document.xml', `<w:document xmlns:w="${ns}"><w:body><w:p><w:r><w:t>First linked passage.</w:t></w:r></w:p><w:p><w:r><w:t xml:space="preserve">Second </w:t></w:r><w:r><w:rPr><w:b/></w:rPr><w:t>affected</w:t></w:r><w:r><w:t xml:space="preserve"> passage.</w:t></w:r></w:p><w:p><w:r><w:t>First linked passage.</w:t></w:r></w:p></w:body></w:document>`)
  return zip.generateAsync({ type: 'arraybuffer' })
}

async function main() {
  const bytes = await fixture(), sha = Buffer.from(await webcrypto.subtle.digest('SHA-256', bytes)).toString('hex')
  const source = { id: '41', sourceDocumentId: 41, docId: 'ntt', editionId: 'NTT-docx-TEST', fileKey: 'NTT', fileName: 'TEST source.docx', format: 'docx', sourceSha256: sha, parseStatus: 'PARSED', canEdit: true, evidenceStatus: 'bound', analysisRevision: 'TEST-analysis' }
  const paragraphs = ['First linked passage.', 'Second affected passage.', 'First linked passage.'].map((text, index) => ({ id: paragraphId(index + 1), ordinal: index + 1, text, paragraphSha256: String(index + 1).repeat(64), sourceSha256: sha, sourcePosition: { sourceSha256: sha, partName: 'word/document.xml', nodePath: sourcePath(index + 1) } }))
  const reading = { ...source, paragraphs, blocks: [], guidanceNotes: [], sourceMembers: [], markerCandidates: [], groups: [], conditions: [], issues: [] }
  const existing = { id: 'existing', nameZh: '原变量', nameEn: 'Existing variable', type: { kind: 'boolean', zh: '是／否', en: 'Yes / No' }, role: { kind: 'input', zh: '项目事实', en: 'Project fact' }, status: 'pending', docIds: ['41'], options: [], sourceAnchors: [], sourceUnitIds: [], editTargets: [], relatedInputIds: [], relations: [], targets: [], evidence: [] }
  const graph = { kind: 'consense-template-graph-v1', graph: { variables: [existing], events: [], sourceReviews: {}, nextId: 1, blank: false, guidanceMode: 'recognized' }, sources: [source], selectedDocuments: { NTT: '41' } }
  let saved = { id: 'draft-1', parentRevisionId: null, status: 'DRAFT', graphSha256: 'test', graph, sourceStale: false }
  const writes = [], confirms = [], sourceReads = []
  const api = {
    reviewHead: async () => ({ currentRevisionId: null, draftRevisionId: saved.id, draft: copy(saved), current: null, sources: [copy(source)] }),
    reviewReading: async () => copy(reading),
    reviewSource: async (...args) => { sourceReads.push(args); return new Blob([bytes]) },
    reviewPreview: async () => { throw Error('TEST PDF unavailable') },
    reviewSave: async (...args) => { writes.push(copy(args)); saved = { ...copy(saved), id: 'draft-2', graph: copy(args[1]) }; return copy(saved) },
    reviewConfirm: async (...args) => { confirms.push(args); throw Error('Unexpected confirmation') },
    reviewCandidates: async () => ({ variables: [] })
  }
  const Modal = { props: ['open'], setup: (props, { slots }) => () => props.open ? vue.h('section', slots.default?.()) : null }
  const loaded = loadVue(path.join(__dirname, '../src/components/DraftingSourceReview.vue'), {
    boundaries: {
      '@/api': { draftingApi: api }, '@/api/source-locations': { reviewSourceBindings: async () => { throw Error('Unexpected PDF bindings') } },
      './AppModal.vue': { default: Modal }, './DraftingPdfPreview.vue': { default: { render: () => null } },
      jszip: { default: JSZip }, 'mammoth/mammoth.browser.min.js': { default: require('mammoth/mammoth.browser.min.js') }, dompurify: { default: require('dompurify') }
    },
    globals: { structuredClone, window, document, Element: window.Element, HTMLElement: window.HTMLElement, DOMParser: window.DOMParser, XMLSerializer: window.XMLSerializer, NodeFilter: window.NodeFilter, crypto: webcrypto, Blob, URL }
  })
  const editor = vue.ref(), app = vue.createApp({ setup: () => () => vue.h(loaded.default, { projectId: 'WORD-DOM-TEST', locale: 'en', ref: editor }) })
  const wait = async predicate => { for (let i = 0; i < 100; i++) { await settle(); if (predicate()) return; await new Promise(resolve => setTimeout(resolve, 10)) } throw Error('Timed out waiting for Word UI: ' + document.body.textContent.slice(0, 1200)) }
  const click = async selector => { const node = document.querySelector(selector); assert.ok(node, selector); assert.equal(node.disabled, false, selector); node.click(); await settle() }
  const button = async label => { const node = [...document.querySelectorAll('button')].find(item => item.textContent.trim() === label); assert.ok(node, label); assert.equal(node.disabled, false, label); node.click(); await settle() }
  const select = async (paragraph, start, end) => {
    const block = [...document.querySelectorAll('[data-native-id]')].find(node => node.dataset.nativeId === paragraph.id)
    assert.ok(block, paragraph.id)
    const walker = document.createTreeWalker(block, 4), nodes = []
    while (walker.nextNode()) nodes.push(walker.currentNode)
    const point = offset => { for (const node of nodes) { if (offset <= node.length) return [node, offset]; offset -= node.length } throw Error('offset outside source') }
    const [startNode, startOffset] = point(start), [endNode, endOffset] = point(end)
    const range = document.createRange(); range.setStart(startNode, startOffset); range.setEnd(endNode, endOffset)
    const selection = document.getSelection(); selection.removeAllRanges(); selection.addRange(range)
    document.querySelector('[data-word-source-surface]').dispatchEvent(new window.MouseEvent('mouseup', { bubbles: true }))
    await settle()
    assert.match(document.querySelector('[data-word-selection]').textContent, new RegExp(paragraph.text.slice(start, end)))
  }
  app.mount('#app')
  try {
    await wait(() => document.querySelector('[data-source-view-word]'))
    await click('[data-source-view-word]')
    await wait(() => document.querySelectorAll('[data-native-id]').length === 3)
    assert.deepEqual(sourceReads[0], ['WORD-DOM-TEST', '41', sha], 'The actual Word reader requests the exact selected source')
    await select(paragraphs[0], 0, 5); await click('[data-word-add-variable]')
    await select(paragraphs[2], 6, 12); await click('[data-word-link-variable]')
    await select(paragraphs[1], 7, 15); await click('[data-word-add-target]')
    await click('[data-review-save-form]')
    await button('View relationships')
    assert.ok(document.querySelector('[data-review-graph-node="review-1"]'))
    assert.ok(document.querySelector('[data-review-graph-node="action:review-1:all"]'))
    assert.ok(document.querySelector('[data-review-graph-edge="action:review-1:all:file:41"]'))
    await button('Save review draft')
    assert.equal(writes.length, 1); assert.equal(confirms.length, 0)
    assert.deepEqual(writes[0].slice(2), [null, 'draft-1'])
    const variables = writes[0][1].graph.variables, added = variables.find(item => item.id === 'review-1')
    assert.deepEqual(variables.find(item => item.id === 'existing'), existing)
    assert.deepEqual(added.sourceAnchors.map(item => [item.text, item.native.paragraphId, item.native.start, item.native.end]), [['First', paragraphs[0].id, 0, 5], ['linked', paragraphs[2].id, 6, 12]], 'Repeated text retains distinct native source identities')
    const target = added.editTargets[0]
    assert.deepEqual([target.text, target.native.paragraphId, target.native.start, target.native.end], ['affected', paragraphs[1].id, 7, 15])
    assert.equal(target.native.paragraphSha256, paragraphs[1].paragraphSha256); assert.equal(target.native.sourceSha256, sha)
    await editor.value.refresh(false); await settle()
    await editor.value.locateQuestion('review-1', 'draft-2'); await settle()
    assert.match(document.body.textContent, /First/); assert.match(document.body.textContent, /linked/)
    await click('[data-review-edit-question]')
    assert.match(document.querySelector('[data-source-question-drawer]').textContent, /affected/, 'The recorded target survives the mocked readback')
    assert.equal(confirms.length, 0)
    console.log('PASS: synthetic DOCX through real Word conversion and DOM Range feeds ADD, LINK and TARGETS into the graph and mock save/readback')
  } finally { app.unmount() }
}
main().catch(error => { console.error(error); process.exitCode = 1 })
