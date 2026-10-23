/* Template readers own their inline errors; other requests retain global reporting. */
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')

const requests = []
let response, rejection
const axios = {
  create: () => ({
    get: async (url, config) => {
      requests.push({ url, config })
      if (rejection) throw rejection
      return { data: response, headers: {} }
    }
  }),
  isAxiosError: error => error?.isAxiosError === true
}
function load(relativePath, requireModule) {
  const source = fs.readFileSync(path.join(__dirname, relativePath), 'utf8')
  const compiled = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, esModuleInterop: true } }).outputText
  const sandbox = { exports: {}, require: requireModule, Blob, FormData, console }
  vm.runInNewContext(compiled, sandbox, { filename: relativePath })
  return sandbox.exports
}
const client = load('../src/api/client.ts', name => { assert.equal(name, 'axios'); return axios })
const sourceLocations = load('../src/api/source-locations.ts', name => { assert.equal(name, './client'); return client })
const { draftingApi } = load('../src/api/index.ts', name => {
  if (name === './client') return client
  if (name === './source-locations') return sourceLocations
  assert.equal(name, './types'); return {}
})
const reported = []
client.setApiErrorReporter(message => reported.push(message))

async function run() {
  const missing = 'SOURCE_NOT_UPLOADED: Upload the NTT standard template first.'
  response = { code: 4011, message: missing, data: null }
  await assert.rejects(draftingApi.templateReading('project-without-template', 'NTT'), error => error instanceof client.ApiError && error.code === 4011 && error.message === missing)
  assert.deepEqual(reported, [], 'The reading panel must handle an absent source without a duplicate global toast')
  assert.equal(requests[0].url, '/drafting/project-without-template/templates/NTT/reading')
  assert.equal(Object.hasOwn(requests[0].config, 'reportError'), false, 'Application reporting settings must not leak into axios')
  response = new Blob([JSON.stringify({ code: 4011, message: missing, data: null })], { type: 'application/json' })
  await assert.rejects(draftingApi.templateSource('project-without-template', 'NTT'), error => error instanceof client.ApiError && error.code === 4011 && error.message === missing)
  assert.deepEqual(reported, [], 'A source removed after reading must also be handled inline without a duplicate toast')
  assert.equal(requests[1].url, '/drafting/project-without-template/templates/NTT/source')
  assert.equal(requests[1].config.responseType, 'blob')
  assert.equal(requests[1].config.timeout, 300000)
  assert.equal(Object.hasOwn(requests[1].config, 'reportError'), false)

  // The error code is shared with actual storage faults: callers receive the exact failure.
  const broken = 'SOURCE_FILE_MISSING: Uploaded template bytes are unavailable.'
  response = { code: 4011, message: broken, data: null }
  await assert.rejects(draftingApi.templateReading('broken-project', 'SCT'), error => error instanceof client.ApiError && error.code === 4011 && error.message === broken)
  assert.deepEqual(reported, [])
  await assert.rejects(draftingApi.getTemplateText('broken-project', 'SCT'), error => error instanceof client.ApiError && error.code === 4011 && error.message === broken)
  assert.deepEqual(reported, [broken], 'Existing non-reader JSON requests still report errors globally')
  reported.length = 0
  response = new Blob([JSON.stringify({ code: 4011, message: broken })], { type: 'application/json' })
  await assert.rejects(draftingApi.templatePreview('broken-project', 'SCT'), error => error instanceof client.ApiError && error.code === 4011 && error.message === broken)
  assert.deepEqual(reported, [broken], 'Existing PDF preview requests still report errors globally')
  reported.length = 0

  const network = Object.assign(new Error('Network Error'), { isAxiosError: true })
  rejection = network
  await assert.rejects(draftingApi.templateReading('offline-project', 'SCC'), error => error instanceof client.ApiError && error.code === -1 && error.message === '无法连接后端服务，请确认 service 已启动及 API 地址配置正确')
  await assert.rejects(draftingApi.templateSource('offline-project', 'SCC'), error => error === network)
  assert.deepEqual(reported, [], 'Reader transport failures must still reject for inline error and retry handling')
  await assert.rejects(client.api.get('/projects'), error => error instanceof client.ApiError && error.code === -1)
  assert.deepEqual(reported, ['无法连接后端服务，请确认 service 已启动及 API 地址配置正确'])
  reported.length = 0
  await assert.rejects(client.api.blob('/current/preview.pdf'), error => error === network)
  assert.deepEqual(reported, ['无法连接后端服务，请确认 service 已启动及 API 地址配置正确'])
  reported.length = 0

  rejection = null
  client.setLlmProfileResolver(() => 'minimax-cn')
  const reading = { fileKey: 'NTT', format: 'docx', paragraphs: [] }
  response = { code: 0, message: 'ok', data: reading }
  assert.equal(await draftingApi.templateReading('ready-project', 'NTT'), reading)
  response = new Blob(['actual source bytes'], { type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document' })
  assert.equal(await draftingApi.templateSource('ready-project', 'NTT'), response)
  assert.equal(requests.at(-1).config.headers['X-ConSense-Llm-Profile'], 'minimax-cn')
  response = { code: 4090, message: 'SYNTHETIC source/revision changed', data: null }
  await assert.rejects(draftingApi.templateBindings('bound-project', 'NTT', 'source hash/guard'), error => error instanceof client.ApiError && error.code === 4090)
  assert.equal(requests.at(-1).url, '/drafting/bound-project/templates/NTT/bindings?sourceSha256=source%20hash%2Fguard')
  await assert.rejects(draftingApi.documentBindings('bound-project', 'SCT', 'revision/2', 'docx hash'), error => error instanceof client.ApiError && error.code === 4090)
  assert.equal(requests.at(-1).url, '/drafting/bound-project/documents/SCT/bindings?revisionId=revision%2F2&docxSha256=docx%20hash')
  assert.deepEqual(reported, [], 'Binding identity failures belong to the document inspector without a duplicate global toast')
  response = new Blob(['SYNTHETIC guarded original PDF'], { type: 'application/pdf' })
  assert.equal(await draftingApi.templatePreview('bound-project', 'NTT', 'source hash/guard'), response)
  assert.equal(requests.at(-1).url, '/drafting/bound-project/templates/NTT/preview.pdf?sourceSha256=source%20hash%2Fguard')
  for (const { config } of requests) {
    assert.equal(Object.hasOwn(config, 'reportError'), false)
    assert.equal(Object.hasOwn(config, 'llmSelection'), false)
  }
  assert.deepEqual(reported, [])
  console.log('PASS: source reading and binary failures reject without duplicate global toasts; default requests still report; metadata, bytes, profile and transport configuration are preserved')
  console.log('PASS: source/revision/DOCX guards are encoded on actual binding/PDF routes and identity failures stay inline')
}
run().catch(error => { console.error(error); process.exitCode = 1 })
