const assert = require('node:assert/strict')
const path = require('node:path')
const { test } = require('node:test')
const { loadTypeScript } = require('./helpers/load-typescript.cjs')

function harness() {
  const calls = []
  const axios = { isAxiosError: () => false, create: () => ({ get(url, config) {
    return new Promise((resolve, reject) => calls.push({ url, config, resolve: data => resolve({ data, headers: {} }), reject }))
  } }) }
  const client = loadTypeScript(path.join(__dirname, '../src/api/client.ts'), { dependencies: { axios }, globals: { Blob }, compilerOptions: { esModuleInterop: true } })
  const sourceLocations = loadTypeScript(path.join(__dirname, '../src/api/source-locations.ts'), { dependencies: { './client': client } })
  const { draftingApi } = loadTypeScript(path.join(__dirname, '../src/api/index.ts'), { dependencies: { './client': client, './source-locations': sourceLocations, './types': './types.ts' } })
  return { client, draftingApi, calls }
}

test('concurrent consumers of one project, file, revision and profile share only the in-flight request', async () => {
  const h = harness(), blob = new Blob(['saved DOCX'])
  h.client.setLlmProfileResolver(() => 'qwen-27b')
  const one = h.draftingApi.documentSource('project', 'NTT', 'rev/1')
  const two = h.draftingApi.documentSource('project', 'NTT', 'rev/1')
  assert.equal(one, two); assert.equal(h.calls.length, 1)
  assert.equal(h.calls[0].url, '/drafting/project/documents/NTT/source?revisionId=rev%2F1')
  assert.equal(h.calls[0].config.headers['X-ConSense-Llm-Profile'], 'qwen-27b')
  h.calls[0].resolve(blob)
  assert.equal(await one, blob); assert.equal(await two, blob)
  const next = h.draftingApi.documentSource('project', 'NTT', 'rev/1')
  assert.equal(h.calls.length, 2, 'completed bytes must not be cached')
  h.calls[1].resolve(blob); assert.equal(await next, blob)
})

test('different project, file, revision or selected profile do not share a request', async () => {
  const h = harness(), blob = new Blob(['saved DOCX'])
  let profile = 'qwen-27b'; h.client.setLlmProfileResolver(() => profile)
  const pending = [h.draftingApi.documentSource('project', 'NTT', 'rev'), h.draftingApi.documentSource('other', 'NTT', 'rev'),
    h.draftingApi.documentSource('project', 'SCT', 'rev'), h.draftingApi.documentSource('project', 'NTT', 'other-rev')]
  profile = 'qwen-flash'; pending.push(h.draftingApi.documentSource('project', 'NTT', 'rev'))
  assert.equal(h.calls.length, 5)
  assert.equal(h.calls[0].config.headers['X-ConSense-Llm-Profile'], 'qwen-27b')
  assert.equal(h.calls[4].config.headers['X-ConSense-Llm-Profile'], 'qwen-flash')
  h.calls.forEach(call => call.resolve(blob))
  assert.ok((await Promise.all(pending)).every(value => value === blob))
})

test('both consumers see the failure and a later attempt sends a fresh request', async () => {
  const h = harness(), failure = new Error('source failed')
  const one = h.draftingApi.documentSource('project', 'NTT', 'rev')
  const two = h.draftingApi.documentSource('project', 'NTT', 'rev')
  assert.equal(h.calls.length, 1)
  const observed = Promise.allSettled([one, two])
  h.calls[0].reject(failure)
  const results = await observed
  assert.ok(results.every(result => result.status === 'rejected' && result.reason === failure))
  const retry = h.draftingApi.documentSource('project', 'NTT', 'rev')
  assert.equal(h.calls.length, 2)
  const blob = new Blob(['retry']); h.calls[1].resolve(blob); assert.equal(await retry, blob)
})
