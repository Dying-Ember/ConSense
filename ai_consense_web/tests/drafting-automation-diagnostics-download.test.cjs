const assert = require('node:assert/strict')
const path = require('node:path')
const { loadTypeScript } = require('./helpers/load-typescript.cjs')
let responseBlob, downloads = 0, urls = 0
const requests = [], revoked = []
const axios = { isAxiosError: () => false, create: () => ({ get: async (url, config) => {
  requests.push({ url, config }); return { data: responseBlob, headers: {} }
} }) }
const globals = { Blob,
  URL: { createObjectURL: () => `blob:diagnostics-${++urls}`, revokeObjectURL: url => revoked.push(url) },
  document: { createElement: () => ({ click: () => downloads++, remove() {} }), body: { appendChild() {} } }, setTimeout: callback => callback()
}
const client = loadTypeScript(path.join(__dirname, '../src/api/client.ts'), { globals, dependencies: { axios }, compilerOptions: { esModuleInterop: true } })
const locations = loadTypeScript(path.join(__dirname, '../src/api/source-locations.ts'), { dependencies: { './client': client } })
const { draftingApi } = loadTypeScript(path.join(__dirname, '../src/api/index.ts'), { dependencies: { './client': client, './source-locations': locations, './types': './types.ts' } })
const report = () => ({ schema: 'consense-draft-automation-diagnostics-v1', projectId: 'project-A', runId: 'run/A', evidenceScope: 'persisted_only', replayed: false, items: [{ status: 'UNKNOWN', receipt: null }] })
const respond = value => { responseBlob = new Blob([JSON.stringify(value)], { type: 'application/json' }) }
;(async () => {
  respond(report()); let prepared
  await draftingApi.automationDiagnostics('project-A', 'run/A', value => { prepared = value; return true })
  assert.equal(requests.length, 1); assert.equal(requests[0].url, '/drafting/project-A/graph/automation/run%2FA/diagnostics.json')
  assert.equal(downloads, 0, 'Preparing diagnostics leaves a real download link for the user')
  assert.equal(prepared.url, 'blob:diagnostics-1'); assert.equal(revoked.length, 0)
  for (const patch of [{ projectId: 'other-project' }, { runId: 'other-run' }, { schema: 'other' }, { evidenceScope: 'recomputed' }, { replayed: true }, { items: {} }]) {
    respond({ ...report(), ...patch }); await assert.rejects(draftingApi.automationDiagnostics('project-A', 'run/A'), /JSON|报告/)
  }
  respond({ code: 4091, message: 'Run is still active', data: report() }); await assert.rejects(draftingApi.automationDiagnostics('project-A', 'run/A'), /Run is still active/)
  respond(report()); await draftingApi.automationDiagnostics('project-A', 'run/A', () => false)
  assert.deepEqual(revoked, ['blob:diagnostics-2']); assert.equal(downloads, 0, 'A stale project cannot trigger a download')
  assert.equal(urls, 2, 'Foreign reports and error envelopes never become files')
  console.log('PASS: diagnostic downloads bind project/run identity, preserve UNKNOWN, reject envelopes and support a visible click without extra requests')
})().catch(error => { console.error(error); process.exitCode = 1 })
