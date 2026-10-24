/* Transport contracts only; automatic translation quality is a separate backend acceptance. */
const assert = require('node:assert/strict')
const path = require('node:path')
const { test } = require('node:test')
const { loadTypeScript } = require('./helpers/load-typescript.cjs')
const requests = []
const original = new Blob(['AUTHORED unchanged original bytes'])
const translated = new Blob(['AUTHORED translated delivery bytes differ'])
const downloads = []
const axios = { isAxiosError: () => false, create: () => ({
  post: async (url, body, config) => { requests.push({ method: 'POST', url, body, config }); return { data: { code: 0, data: [] } } },
  get: async (url, config) => { requests.push({ method: 'GET', url, config }); return { data: url.includes('/export.') ? translated : original, headers: {} } }
}) }
const globals = { Blob, URL: { createObjectURL: blob => { downloads.push(blob); return 'blob:authored-download' }, revokeObjectURL() {} },
  document: { createElement: () => ({ click() {}, remove() {} }), body: { appendChild() {} } }, setTimeout: callback => callback() }
const client = loadTypeScript(path.join(__dirname, '../src/api/client.ts'), { dependencies: { axios }, globals, compilerOptions: { esModuleInterop: true } })
const sourceLocations = loadTypeScript(path.join(__dirname, '../src/api/source-locations.ts'), { dependencies: { './client': client } })
const { draftingApi, vettingApi } = loadTypeScript(path.join(__dirname, '../src/api/index.ts'), {
  dependencies: { './client': client, './source-locations': sourceLocations, './types': './types.ts' }
})

test('file generation requests English while vetting runs keep their interface language and selected model', async () => {
  client.setLlmProfileResolver(() => 'qwen-flash')
  const selection = { profileId: 'qwen-27b' }
  await draftingApi.generate('AUTHORED-project', selection)
  await vettingApi.startRun('AUTHORED-project', 'zh-Hans', selection)
  await vettingApi.run('AUTHORED-project', 'zh-Hant')
  assert.deepEqual(requests.map(request => request.url), [
    '/drafting/AUTHORED-project/generate?lang=en',
    '/vetting/AUTHORED-project/runs?lang=zh-Hans',
    '/vetting/AUTHORED-project/run?lang=zh-Hant'
  ])
  assert.deepEqual(requests.map(request => request.config.headers['X-ConSense-Llm-Profile']), ['qwen-27b', 'qwen-27b', 'qwen-flash'])
  assert.ok(requests.every(request => request.method === 'POST' && request.body === undefined))
})

test('original downloads retain their bound source identity and bytes without requesting translation', async () => {
  requests.length = 0
  assert.equal(await draftingApi.reviewSource('AUTHORED-project', 'source/1', 'a'.repeat(64)), original)
  assert.equal(await draftingApi.templateSource('AUTHORED-project', 'SCT'), original)
  assert.deepEqual(requests.map(request => request.url), [
    `/drafting/AUTHORED-project/graph/sources/source%2F1/original?sourceSha256=${'a'.repeat(64)}`,
    '/drafting/AUTHORED-project/templates/SCT/source'
  ])
  assert.equal(await original.text(), 'AUTHORED unchanged original bytes')
})

test('native saved-revision reading uses original bytes while explicit Word and PDF exports use delivery copies', async () => {
  requests.length = 0; downloads.length = 0
  const revision = 'AUTHORED revision/1?bound=yes'
  assert.equal(await draftingApi.documentSource('AUTHORED-project', 'SCT', revision), original)
  await draftingApi.exportDocument('AUTHORED-project', 'SCT', 'docx', revision)
  await draftingApi.exportDocument('AUTHORED-project', 'SCT', 'pdf', revision)
  const query = `revisionId=${encodeURIComponent(revision)}`
  assert.deepEqual(requests.map(request => request.url), [
    `/drafting/AUTHORED-project/documents/SCT/source?${query}`,
    `/drafting/AUTHORED-project/documents/SCT/export.docx?${query}`,
    `/drafting/AUTHORED-project/documents/SCT/export.pdf?${query}`
  ])
  assert.equal(downloads.length, 2); assert.ok(downloads.every(blob => blob === translated))
  assert.notEqual(await original.text(), await translated.text(), 'A translated delivery must not masquerade as the saved native revision')
})
