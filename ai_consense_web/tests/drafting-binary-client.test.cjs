/* Binary endpoints may return HTTP 200 JSON business errors: never save them as Office/PDF files. */
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
let responseBlob, downloads = 0, objectUrls = 0
const configurations = []
const axios = {
  create: () => ({ get: async (_url, config) => { configurations.push(config); return { data: responseBlob, headers: {} } } }),
  isAxiosError: () => false
}
const source = fs.readFileSync(path.join(__dirname, '../src/api/client.ts'), 'utf8')
const compiled = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, esModuleInterop: true } }).outputText
const sandbox = {
  exports: {}, require: name => { assert.equal(name, 'axios'); return axios }, Blob,
  URL: { createObjectURL: () => { objectUrls++; return 'blob:unit-test' }, revokeObjectURL: () => {} },
  document: { createElement: () => ({ click: () => downloads++, remove: () => {} }), body: { appendChild: () => {} } },
  setTimeout: callback => callback(), console
}
vm.runInNewContext(compiled, sandbox, { filename: 'client.ts' })
const { api, ApiError, setApiErrorReporter, setLlmProfileResolver } = sandbox.exports
const reported = []; setApiErrorReporter(message => reported.push(message))
async function run() {
  responseBlob = new Blob([JSON.stringify({ code: 4009, message: 'Draft is stale' })], { type: 'application/json' })
  await assert.rejects(api.blob('/stale/preview.pdf'), e => e instanceof ApiError && e.code === 4009)
  await assert.rejects(api.download('/stale/export.docx', 'draft.docx'), e => e instanceof ApiError && e.code === 4009)
  assert.equal(objectUrls, 0); assert.equal(downloads, 0); assert.deepEqual(reported, ['Draft is stale', 'Draft is stale'])
  assert.equal(configurations[0].headers['X-ConSense-Llm-Profile'], undefined)
  setLlmProfileResolver(() => 'minimax-cn')
  responseBlob = new Blob(['%PDF-1.4 test'], { type: 'application/pdf' })
  assert.equal(await api.blob('/current/preview.pdf'), responseBlob)
  await api.download('/current/export.pdf', 'draft.pdf')
  assert.equal(objectUrls, 1); assert.equal(downloads, 1)
  assert.equal(configurations[2].headers['X-ConSense-Llm-Profile'], 'minimax-cn')
  assert.equal(configurations[2].responseType, 'blob'); assert.equal(configurations[2].timeout, 300000)
  console.log('PASS: stale JSON preview rejected; stale JSON export rejected without download; PDF preview accepted; PDF export accepted')
}
run().catch(error => { console.error(error); process.exitCode = 1 })
