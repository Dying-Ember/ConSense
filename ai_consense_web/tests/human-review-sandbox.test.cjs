const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const { pathToFileURL } = require('node:url')
const ts = require('typescript')
const file = path.join(__dirname, '../vite.review-prototype.config.ts')
const source = fs.readFileSync(file, 'utf8').replaceAll('import.meta.url', JSON.stringify(pathToFileURL(file).href))
const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
const loaded = { exports: {} }
const reads = []
vm.runInNewContext(compiled, { module: loaded, exports: loaded.exports, URL, require(name) {
  if (name === 'vite') return { defineConfig: value => value }
  if (name === '@vitejs/plugin-vue') return { default: () => ({ name: 'vue' }) }
  if (name === 'node:fs/promises') return { readFile: async file => { reads.push(file); return Buffer.from('{"kind":"allowed-local-graph"}') } }
  if (name === 'node:url') return require(name)
  throw Error(`Unexpected dependency ${name}`)
} }, { filename: file })
const config = loaded.exports.default
assert.equal(config.server.host, '127.0.0.1')
assert.equal(config.server.port, 5192)
assert.equal(config.server.proxy, undefined)
assert.equal(config.define.__CONSENSE_REVIEW_PROTOTYPE__, 'true')
const routerFile = path.join(__dirname, '../src/router/index.ts')
function draftingRoute(dev, sandbox, query) {
  const source = fs.readFileSync(routerFile, 'utf8').replaceAll('import.meta.env.DEV', String(dev))
  const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
  const module = { exports: {} }
  const context = { module, exports: module.exports, require(name) {
    if (name === 'vue') return { defineComponent: value => value, defineAsyncComponent: value => value, h: value => value }
    if (name === 'vue-router') return { createRouter: value => value, createWebHashHistory: () => ({}), useRoute: () => ({ query }) }
    throw Error(`Unexpected router dependency ${name}`)
  } }
  if (sandbox !== undefined) context.__CONSENSE_REVIEW_PROTOTYPE__ = sandbox
  vm.runInNewContext(compiled, context, { filename: routerFile })
  const component = module.exports.default.routes.find(route => route.path === '/drafting').component
  return typeof component === 'function' ? component : component.setup()()
}
for (const [dev, sandbox] of [[false, undefined], [false, true], [true, undefined], [true, false]]) assert.doesNotMatch(String(draftingRoute(dev, sandbox, { reviewPrototype: '1' })), /HumanReviewIntegrationPrototype/)
assert.match(String(draftingRoute(true, true, { reviewPrototype: '1' })), /HumanReviewIntegrationPrototype/)
assert.doesNotMatch(String(draftingRoute(true, true, {})), /HumanReviewIntegrationPrototype/)
let handler
config.plugins.find(item => item.name === 'consense-review-prototype-sandbox').configureServer({ middlewares: { use: value => { handler = value } } })
async function request(url, method = 'GET') {
  let next = false, body = '', status = 200
  const response = { setHeader() {}, end(value) { body = String(value) }, set statusCode(value) { status = value } }
  await handler({ url, method }, response, () => { next = true })
  return { next, status, body }
}
;(async () => {
  for (const endpoint of ['/api/projects', '/api/system/health', '/api/system/llm-profiles', '/api/skills']) {
    const result = await request(endpoint)
    assert.equal(result.status, 200)
    assert.equal(JSON.parse(result.body).code, 0)
  }
  assert.match((await request('/api/projects')).body, /review-prototype-example/)
  for (const method of ['POST', 'PUT', 'DELETE', 'PATCH']) for (const endpoint of ['/api/projects', '/api/drafting/example/generate', '/__review-prototype/graph.json']) assert.equal((await request(endpoint, method)).status, 405)
  for (const endpoint of ['/api/drafting/example/catalog', '/api/system/private', '/__review-prototype/private.json', '/__review-prototype/other/../private']) assert.equal((await request(endpoint)).status, 404)
  assert.equal((await request('/__review-prototype/graph.json')).status, 200)
  assert.deepEqual(reads, ['D:/R&D/ConSense/.local/drafting-human-review-prototype-20261016/template-graph-20261017/graph-v2-original.json'])
  assert.equal((await request('/src/main.ts')).next, true)
  console.log('PASS sandbox middleware: four bootstrap fixtures, exact graph allowlist, unknown API rejection, all mutation methods rejected; no server or backend started')
  console.log('PASS router gate: ordinary dev and production retain existing Drafting; dedicated sandbox + explicit query enables the prototype')
})().catch(error => { console.error(error); process.exitCode = 1 })
