// Agreed public seam: actual workspace, DOCX carrier and reading controls.
// Only HTTP/provider and optional PDF/browser-layout boundaries are simulated.
import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { resolve } from 'node:path'
import { JSDOM } from 'jsdom'
import { createRequire } from 'node:module'
import { webcrypto, createHash } from 'node:crypto'
const root = fileURLToPath(new URL('../', import.meta.url)), require = createRequire(import.meta.url)
const JSZip = require('jszip'), window = new JSDOM('<main id="app"></main>').window
for (const key of ['window','document','Element','HTMLElement','SVGElement','Node','Document','ShadowRoot']) globalThis[key] = key === 'window' ? window : window[key]
window.HTMLElement.prototype.scrollIntoView = function () {}
window.HTMLElement.prototype.getClientRects = () => [{ width: 100, height: 30 }]
const vue = require('vue'), { loadVue } = require('../tests/helpers/load-vue.cjs')
const calls = [], sha = bytes => createHash('sha256').update(Buffer.from(bytes)).digest('hex')
async function docx(body) {
  const zip = new JSZip()
  zip.file('[Content_Types].xml', '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>')
  zip.file('_rels/.rels', '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>')
  zip.file('word/document.xml', '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>'+body+'</w:body></w:document>')
  return zip.generateAsync({ type: 'arraybuffer' })
}
const originalBytes = await docx('<w:p><w:r><w:t>SYNTHETIC ORIGINAL opening</w:t></w:r></w:p><w:tbl><w:tr><w:tc><w:p><w:r><w:t>Original full table cell</w:t></w:r></w:p></w:tc></w:tr></w:tbl><w:p><w:r><w:t>End of original full document</w:t></w:r></w:p>')
const resultBytes = await docx('<w:p><w:r><w:t>SYNTHETIC SAVED opening</w:t></w:r></w:p><w:tbl><w:tr><w:tc><w:p><w:r><w:t>Saved full table cell</w:t></w:r></w:p></w:tc></w:tr></w:tbl><w:p><w:r><w:t>End of saved full document</w:t></w:r></w:p>')
const sourceHash = sha(originalBytes), docxHash = sha(resultBytes)
const bundle = (view, fileKey) => ({ view,fileKey,sourceSha256:sourceHash,docxSha256:view==='source'?sourceHash:docxHash,revisionId:view==='result'?'revision-1':null,bindings:[] })
const deferred = () => { let resolve; const promise = new Promise(done => { resolve = done }); return { promise,resolve } }
const sourceDelay = deferred()
let mismatch = false
const api = {
  templateBindings: async (project,file,hash) => { calls.push(['sourceBindings',project,file,hash]); return bundle('source',file) },
  templateSource: async (project,file) => { calls.push(['sourceBytes',project,file]); if(project==='slow-project')return sourceDelay.promise; return new Blob([originalBytes]) },
  documentBindings: async (...args) => { calls.push(['resultBindings',...args]); return { ...bundle('result',args[1]), docxSha256:mismatch?'0'.repeat(64):docxHash } },
  documentSource: async (...args) => { calls.push(['resultBytes',...args]); return new Blob([resultBytes]) },
  templatePreview: async () => { calls.push(['pdf']); throw Error('PDF must be on demand') },
  previewDocument: async () => { calls.push(['pdf']); throw Error('PDF must be on demand') }
}
const loaded = loadVue(resolve(root,'src/components/DraftingDocumentWorkspace.vue'), {
  globals:{ window,document,HTMLElement,Node,DOMParser:window.DOMParser,XMLSerializer:window.XMLSerializer,NodeFilter:window.NodeFilter,crypto:webcrypto,Blob,URL },
  boundaries:{ '@/api':{draftingApi:api},jszip:{default:JSZip},'mammoth/mammoth.browser.min.js':{default:require('mammoth/mammoth.browser.min.js')},dompurify:{default:require('dompurify')(window)},'pdfjs-dist/build/pdf.worker.min.mjs?url':{default:'external-worker'},'pdfjs-dist':{GlobalWorkerOptions:{},getDocument(){throw Error('PDF must be on demand')}} }
})
const props = vue.reactive({ projectId:'slow-project',fileKey:'NTT',document:{fileKey:'NTT',generated:true,sourceSha256:sourceHash,revisionId:'revision-1',docxSha256:docxHash},locale:'en',fields:[],values:{},variables:[],actions:[],fieldStates:{},dirtyKeys:[],editing:false,dirty:false,disabled:false })
const app = vue.createApp({render:()=>vue.h(loaded.default,props)}); app.mount('#app')
async function settleUntil(check) { for(let index=0;index<100;index++){await new Promise(resolve=>setTimeout(resolve,5));await vue.nextTick();if(check())return}throw Error('Rendered state did not settle: '+document.body.textContent) }
try {
  await settleUntil(()=>calls.some(call=>call[0]==='sourceBytes'))
  props.projectId='current-project'; props.fileKey='SCT'; props.document={...props.document,fileKey:'SCT'}
  await settleUntil(()=>document.querySelector('[data-native-paragraph]'))
  assert.match(document.body.textContent,/SYNTHETIC SAVED opening/,'Review opens the saved result without inventing change attribution')
  const original=document.querySelector('[data-review-mode="original"]');assert.ok(original,'Original source remains directly available');original.click()
  await settleUntil(()=>document.body.textContent.includes('SYNTHETIC ORIGINAL opening'))
  assert.match(document.body.textContent,/SYNTHETIC ORIGINAL opening/)
  assert.match(document.body.textContent,/End of original full document/)
  assert.equal(document.querySelectorAll('[data-native-paragraph]').length,3)
  assert.equal(document.querySelector('table td')?.textContent,'Original full table cell')
  const saved=document.querySelector('[data-review-mode="saved"]');assert.ok(saved,'Actual reading mode control exists');saved.click()
  await settleUntil(()=>document.body.textContent.includes('SYNTHETIC SAVED opening'))
  assert.match(document.body.textContent,/End of saved full document/)
  assert.equal(document.querySelector('table td')?.textContent,'Saved full table cell')
  sourceDelay.resolve(new Blob([originalBytes]));await new Promise(resolve=>setTimeout(resolve,30));await vue.nextTick()
  assert.match(document.body.textContent,/SYNTHETIC SAVED opening/)
  assert.equal(calls.some(call=>call[0]==='pdf'),false)
  assert.ok(calls.some(call=>call[0]==='resultBytes'&&call[3]==='revision-1'))
  assert.ok(calls.some(call=>call[0]==='resultBindings'&&call[3]==='revision-1'&&call[4]===docxHash))
  console.log('PASS: actual workspace converts real ZIP DOCX at correct SHA, renders complete native flow/table, rejects late project source and defers PDF')
  mismatch=true;props.projectId='mismatched-project'
  await settleUntil(()=>document.querySelector('[role="alert"]'))
  assert.match(document.querySelector('[role="alert"]').textContent,/revision|version|changed/i)
  assert.equal(document.querySelectorAll('[data-native-paragraph]').length,0,'Mismatched saved identity cannot publish content')
  console.log('PASS: actual workspace rejects mismatched binding revision/hash before showing the document')
} finally {app.unmount()}
