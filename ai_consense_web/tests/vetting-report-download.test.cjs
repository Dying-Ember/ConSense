// Exercise the report call site and real download client; JSON reports are files,
// while HTTP 200 business envelopes must never become saved reports.
const assert = require('node:assert/strict')
const path = require('node:path')
const { loadTypeScript } = require('./helpers/load-typescript.cjs')
let responseBlob, downloadCount = 0, urlCount = 0, rejectedReports = 0
const requests = [], reported = [], downloaded = []
const axios = {
  create: () => ({ get: async (url, config) => {
    requests.push({ url, config })
    return { data: responseBlob, headers: {} }
  } }),
  isAxiosError: () => false
}
const globals = {
  Blob,
  URL: { createObjectURL: blob => { urlCount++; downloaded.push(blob); return 'blob:test-report' }, revokeObjectURL: () => {} },
  document: { createElement: () => ({ click: () => downloadCount++, remove: () => {} }), body: { appendChild: () => {} } },
  setTimeout: callback => callback()
}
const client = loadTypeScript(path.join(__dirname, '../src/api/client.ts'), {
  globals, dependencies: { axios }, compilerOptions: { esModuleInterop: true }
})
const sourceLocations = loadTypeScript(path.join(__dirname, '../src/api/source-locations.ts'), {
  dependencies: { './client': client }
})
const { vettingApi } = loadTypeScript(path.join(__dirname, '../src/api/index.ts'), {
  dependencies: { './client': client, './source-locations': sourceLocations, './types': './types.ts' }
})
client.setApiErrorReporter(message => reported.push(message))
client.setLlmProfileResolver(() => 'minimax-cn')
const report = () => ({ schemaVersion: '2.0', reportStructureVersion: 'competition-comments-and-checklist-v1',
  reportLanguage: 'en', language: 'en', project: { id: 'project-A' }, job: { id: 'job-1', status: 'COMPLETED' },
  presentationOnly: true, sourceRunId: 'job-1', sourceReportSnapshotId: 'a'.repeat(64), sourceSnapshotSha256: 'b'.repeat(64),
  diagnostics: { retainedServerSide: true, sourceRunId: 'job-1', sourceReportSnapshotId: 'a'.repeat(64) },
  findings: [], commentsTables: [], checklistTables: [], checklistResults: [], evidenceIndex: { rows: [] } })
function json(value, type = 'application/json; charset=utf-8') { return new Blob([JSON.stringify(value)], { type }) }
async function reject(value, pattern) {
  responseBlob = json(value)
  const before = downloadCount
  await assert.rejects(vettingApi.exportReport('project-A', 'report.json', 'json'), pattern)
  assert.equal(downloadCount, before)
  rejectedReports++
}
async function run() {
  responseBlob = json(report())
  await vettingApi.exportReport('project-A', 'report.json', 'json')
  assert.equal(downloadCount, 1)
  assert.equal(downloaded[0], responseBlob)
  assert.equal(requests[0].url, '/vetting/project-A/report.json?lang=en')
  assert.equal(requests[0].config.headers['X-ConSense-Llm-Profile'], 'minimax-cn')
  await reject({ code: 4091, message: 'Source set changed', data: null }, /Source set changed/)
  await reject({ code: 0, message: 'Unexpected envelope', data: report() }, /Unexpected envelope/)
  await reject({ ...report(), project: { id: 'project-B' } }, /report|报告/)
  await reject({ ...report(), reportStructureVersion: 'unrecognized-version' }, /report|报告/)
  for (const change of [
    { schemaVersion: '1.0' }, { presentationOnly: false }, { language: 'zh-Hans' }, { reportLanguage: 'zh-Hant' },
    { sourceRunId: 'another-job' }, { sourceReportSnapshotId: 'invalid-snapshot' }, { sourceSnapshotSha256: null },
    { job: { id: 'job-1', status: 'FAILED' } }, { findings: {} },
    { diagnostics: { ...report().diagnostics, sourceRunId: 'another-job' } },
    { diagnostics: { ...report().diagnostics, sourceReportSnapshotId: 'c'.repeat(64) } },
    { diagnostics: { ...report().diagnostics, retainedServerSide: false } }
  ]) await reject({ ...report(), ...change }, /report|报告/)
  responseBlob = new Blob(['{broken'], { type: 'application/json' })
  await assert.rejects(vettingApi.exportReport('project-A', 'report.json', 'json'))
  responseBlob = json(report())
  await assert.rejects(vettingApi.exportReport('project-A', 'report.pdf', 'pdf'))
  await assert.rejects(client.api.blob('/drafting/preview.pdf'))
  for (const [format, type, bytes] of [['pdf', 'application/pdf', '%PDF-test'], ['docx', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', 'PK-test']]) {
    responseBlob = new Blob([bytes], { type })
    await vettingApi.exportReport('project-A', `report.${format}`, format)
    assert.equal(requests.at(-1).url, `/vetting/project-A/report.${format}?lang=en`)
  }
  assert.equal(downloadCount, 3); assert.equal(urlCount, 3)
  assert.equal(reported.length, rejectedReports + 3)
  console.log('PASS: English v2 JSON/PDF/DOCX download; invalid schema, language, presentation flag, project/run/snapshot references, envelopes, malformed JSON and JSON on binary endpoints are rejected')
}
run().catch(error => { console.error(error); process.exitCode = 1 })
