const assert = require('node:assert/strict')
const path = require('node:path')
const { test } = require('node:test')
const { loadTypeScript } = require('./helpers/load-typescript.cjs')

test('browser plan and preview opt in without changing the project or patch payload', async () => {
  const calls = [], response = { snapshotId: 'unchanged-service-snapshot' }
  const api = {
    get: async url => { calls.push({ method: 'GET', url }); return response },
    post: async (url, body) => { calls.push({ method: 'POST', url, body }); return response }
  }
  const { draftingApi } = loadTypeScript(path.join(__dirname, '../src/api/index.ts'), { dependencies: {
    './client': { api }, './types': {}, './source-locations': { reviewSourceBindings() { throw Error('Plan requests do not load source bindings') } }
  } })
  assert.equal(await draftingApi.plan('project-A'), response)
  assert.equal(calls[0].method, 'GET'); assert.equal(calls[0].url, '/drafting/project-A/plan?view=browser')
  for (const values of [{}, { choice: 'Hardcopy', contractTitle: '{"number":"TEST-1","title":"Example"}' }]) {
    assert.equal(await draftingApi.plan('project-A', values), response)
    const call = calls.at(-1)
    assert.equal(call.method, 'POST'); assert.equal(call.url, '/drafting/project-A/plan?view=browser')
    assert.deepEqual(Object.keys(call.body), ['values']); assert.equal(call.body.values, values)
  }
  assert.equal(calls.length, 3)
})
