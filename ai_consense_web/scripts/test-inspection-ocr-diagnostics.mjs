// Render the actual Vue component against frozen producer observations.
// No inspection API, model, extraction, browser or source application is involved.
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { createSSRApp } from 'vue'
import { renderToString } from 'vue/server-renderer'
import { createServer } from 'vite'

const root = fileURLToPath(new URL('../', import.meta.url))
const fixture = JSON.parse(await readFile(new URL('../tests/fixtures/inspection-ocr-diagnostics.actual.json', import.meta.url), 'utf8'))
const server = await createServer({ root, configFile: false, plugins: [(await import('@vitejs/plugin-vue')).default()],
  resolve: { alias: { '@': fileURLToPath(new URL('../src', import.meta.url)) } },
  server: { middlewareMode: true, hmr: false }, appType: 'custom', optimizeDeps: { noDiscovery: true, include: [] } })
try {
  const { default: component } = await server.ssrLoadModule('/src/components/InspectionOcrDiagnostics.vue')
  const render = props => renderToString(createSSRApp(component, { locale: 'en', ...props }))
  const stageHtml = await render({ lineStageDiagnostics: fixture.lineStageDiagnostics })
  assert.match(stageHtml, /page-0:det-13/)
  assert.match(stageHtml, /0\.49389/)
  assert.match(stageHtml, /0\.9151526093482971/)
  assert.match(stageHtml, /Low-score candidate · excluded from final OCR · untrusted/)
  assert.match(stageHtml, /rotationPolicyTriggered/)
  assert.match(stageHtml, /Showing 1–20 \/ 51/)
  assert.match(stageHtml, /Retained in experimental OCR: 49/)
  assert.equal((stageHtml.match(/<tr class=/g) ?? []).length, 20, 'The first visible stage page is bounded, without losing the total')
  assert.doesNotMatch(stageHtml, /page-0:det-23/, 'The next stage page must not be silently displayed in the first page')

  // The actual revision header is rendered alone to inspect its fused source line.
  // Only table selection is narrowed here; cell/line/polygon data remain unchanged.
  const actualHeader = fixture.rasterTableCandidates.result.tables.find(table => table.id === 'table-1')
  const headerHtml = await render({ rasterTableCandidates: { ...fixture.rasterTableCandidates,
    result: { ...fixture.rasterTableCandidates.result, tables: [actualHeader] } } })
  assert.match(headerHtml, /NODESCRIPTION AND DATE/)
  assert.match(headerHtml, /actual-line-70/)
  assert.match(headerHtml, /0\.8201970443349754/)
  assert.match(headerHtml, /0\.10806650246305419/)
  assert.match(headerHtml, /No assigned OCR · blankness unconfirmed/)
  assert.match(headerHtml, /nativeCell: false/)
  assert.match(headerHtml, /False does not establish full polygon containment/)
  assert.match(headerHtml, /textMayCrossRasterCellBoundaries: true/)
  assert.match(headerHtml, /1 × 3/)

  const allTablesHtml = await render({ rasterTableCandidates: fixture.rasterTableCandidates })
  assert.match(allTablesHtml, /table-0 · 5 × 4 · 18 Candidate cells/)
  assert.match(allTablesHtml, /table-1 · 2 × 5 · 7 Candidate cells/)
  assert.match(allTablesHtml, /table-2 · 11 × 2 · 22 Candidate cells/)
  assert.match(allTablesHtml, /3 × 1/)

  const absent = await render({})
  assert.match(absent, /This configuration has no saved line-stage observation/)
  assert.match(absent, /This configuration has no saved raster table candidates/)
  assert.doesNotMatch(absent, /page-0:det-13|NODESCRIPTION AND DATE/)
  const literal = await render({ lineStageDiagnostics: { schema: 'rapidocr-line-flow-observation-v1',
    pages: [{ lines: [{ lineId: 'literal-source', recognition: { text: '<script>alert(1)</script>' } }] }] } })
  assert.match(literal, /&lt;script&gt;alert\(1\)&lt;\/script&gt;/)
  assert.doesNotMatch(literal, /<script>alert\(1\)<\/script>/)
  console.log(JSON.stringify({ status: 'passed', actualSLDetected: 51, actualSLRetained: 49, firstStagePageRows: 20,
    actualAPCTables: 3, actualAPCCells: 47, fusedNoDescriptionUnresolved: true, absentStageNotInferred: true,
    sourceTextEscaped: true, modelCalls: 0, apiCalls: 0, sourceApplied: false }))
} finally { await server.close() }
