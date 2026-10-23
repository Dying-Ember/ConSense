const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const requests = [], response = { id: 'TEST-task' }
const axios = { isAxiosError: () => false, create: () => ({
  get: async (url, config) => { requests.push({ method: 'GET', url, config }); return { data: { code: 0, data: response } } },
  post: async (url, body, config) => { requests.push({ method: 'POST', url, body, config }); return { data: { code: 0, data: response } } }
}) }
function load(file, resolver) {
  const compiled = ts.transpileModule(fs.readFileSync(path.join(__dirname, file), 'utf8'), { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, esModuleInterop: true } }).outputText
  const sandbox = { exports: {}, require: resolver, Blob, FormData }
  vm.runInNewContext(compiled, sandbox); return sandbox.exports
}
const client = load('../src/api/client.ts', name => { assert.equal(name, 'axios'); return axios })
const sourceLocations = load('../src/api/source-locations.ts', name => { assert.equal(name, './client'); return client })
const { draftingApi } = load('../src/api/index.ts', name => { if (name === './client') return client; if (name === './source-locations') return sourceLocations; assert.equal(name, './types'); return {} })
;(async () => {
  client.setLlmProfileResolver(() => 'qwen-27b')
  const captured = client.captureLlmSelection(), binding = { expectedCurrentRevisionId: 'confirmed-TEST', expectedDraftRevisionId: 'draft-TEST' }
  client.setLlmProfileResolver(() => 'qwen-flash')
  await draftingApi.automationStart('TEST-project', binding, captured)
  assert.equal(requests.at(-1).config.headers['X-ConSense-Llm-Profile'], 'qwen-27b', 'A click retains its captured profile even when the topbar changes before transport')
  assert.equal(requests.at(-1).body, binding)
  assert.equal(requests.at(-1).url, '/drafting/TEST-project/graph/automation')
  const controller = new AbortController()
  await draftingApi.automationLatest('TEST-project', controller.signal)
  assert.equal(requests.at(-1).config.signal, controller.signal)
  assert.equal(requests.at(-1).config.headers['X-ConSense-Llm-Profile'], undefined, 'Read-only recovery cannot attach the newly selected profile')
  await draftingApi.automationGet('TEST-project', 'TEST/task', controller.signal)
  assert.equal(requests.at(-1).url, '/drafting/TEST-project/graph/automation/TEST%2Ftask')
  assert.equal(requests.at(-1).config.headers['X-ConSense-Llm-Profile'], undefined)
  await draftingApi.automationResume('TEST-project', 'TEST/task', binding, captured)
  assert.equal(requests.at(-1).config.headers['X-ConSense-Llm-Profile'], 'qwen-27b')
  assert.equal(requests.at(-1).body, binding)
  await draftingApi.automationCancel('TEST-project', 'TEST/task')
  assert.equal(requests.at(-1).config.headers['X-ConSense-Llm-Profile'], undefined)
  assert.equal(requests.at(-1).body, undefined)
  assert.deepEqual(requests.map(request => request.method), ['POST', 'GET', 'GET', 'POST', 'POST'])
  for (const { config } of requests) { assert.equal(config.timeout, 30000); assert.equal(Object.hasOwn(config, 'reportError'), false); assert.equal(Object.hasOwn(config, 'llmSelection'), false) }
  console.log('PASS: automatic graph jobs retain mutation profiles and revision fences; recovery/poll/cancel do not capture a changed model and support abortable bounded reads')
})().catch(error => { console.error(error); process.exitCode = 1 })
