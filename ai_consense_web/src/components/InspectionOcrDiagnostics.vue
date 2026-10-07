<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { inspectionJson, type InspectionRecord } from '@/api/inspection'
import { ocrDiagnosticWords } from '@/i18n/ocrDiagnostics'

const props = defineProps<{ locale: string; lineStageDiagnostics?: unknown; rasterTableCandidates?: unknown }>()
const words = computed(() => ocrDiagnosticWords(props.locale))
const pageSize = 20
const stagePageIndex = ref(0), stagePage = ref(1), lineFilter = ref('all')
const tableIndex = ref(0), cellPage = ref(1)
function record(value: unknown): InspectionRecord {
  return value !== null && typeof value === 'object' && !Array.isArray(value) ? value as InspectionRecord : {}
}
function records(value: unknown): InspectionRecord[] { return Array.isArray(value) ? value.filter(x => x !== null && typeof x === 'object' && !Array.isArray(x)) as InspectionRecord[] : [] }
function value(input: unknown): string { return input === undefined || input === null || input === '' ? words.value.unknown : String(input) }
function boolean(input: unknown): string { return input === true ? words.value.yes : input === false ? words.value.no : words.value.unknown }
const diagnostics = computed(() => record(props.lineStageDiagnostics))
const stagePages = computed(() => records(diagnostics.value.pages))
const savedPage = computed(() => stagePages.value[stagePageIndex.value] ?? {})
const stageLines = computed(() => records(savedPage.value.lines))
const retainedCount = computed(() => stageLines.value.filter(line => line.terminalStage === 'retained').length)
const filteredLines = computed(() => lineFilter.value === 'all' ? stageLines.value : stageLines.value.filter(line => line.terminalStage !== 'retained'))
const stagePageCount = computed(() => Math.max(1, Math.ceil(filteredLines.value.length / pageSize)))
const shownLines = computed(() => filteredLines.value.slice((stagePage.value - 1) * pageSize, stagePage.value * pageSize))
const tableEnvelope = computed(() => record(props.rasterTableCandidates))
const tableResult = computed(() => record(tableEnvelope.value.result))
const tables = computed(() => records(tableResult.value.tables))
const table = computed(() => tables.value[tableIndex.value] ?? {})
const cells = computed(() => records(table.value.cells))
const cellPageCount = computed(() => Math.max(1, Math.ceil(cells.value.length / pageSize)))
const shownCells = computed(() => cells.value.slice((cellPage.value - 1) * pageSize, cellPage.value * pageSize))
const hasSavedStages = computed(() => diagnostics.value.schema === 'rapidocr-line-flow-observation-v1' && stagePages.value.length > 0)
const hasSavedTables = computed(() => Array.isArray(tableResult.value.tables))
function tableDimension(boundaries: unknown): string { return Array.isArray(boundaries) && boundaries.length > 1 ? String(boundaries.length - 1) : words.value.unknown }
function range(total: number, page: number): string { return total === 0 ? '0' : `${(page-1)*pageSize+1}–${Math.min(page*pageSize, total)}` }
function terminal(line: InspectionRecord): string {
  return line.terminalStage === 'below_text_score' ? words.value.lowScore : line.terminalStage === 'retained' ? words.value.retainedStatus : value(line.terminalStage)
}
function originalGeometry(line: InspectionRecord): InspectionRecord {
  return { originalImagePolygonBeforeFiltering: line.originalImagePolygonBeforeFiltering, originalPageNormalizedPolygon: line.originalPageNormalizedPolygon }
}
watch([() => props.lineStageDiagnostics, () => props.rasterTableCandidates], () => { stagePageIndex.value = 0; stagePage.value = 1; lineFilter.value = 'all'; tableIndex.value = 0; cellPage.value = 1 })
watch([stagePageIndex, lineFilter], () => { stagePage.value = 1 })
watch(tableIndex, () => { cellPage.value = 1 })
</script>

<template>
  <section class="ocr-diagnostics">
    <details open data-inspection="ocr-stages">
      <summary>{{ words.stages }}</summary>
      <p class="diagnostic-boundary">{{ words.stageBoundary }}</p>
      <p v-if="!hasSavedStages" class="muted">{{ words.unrecordedStages }}</p>
      <template v-else>
        <div class="diagnostic-controls">
          <label v-if="stagePages.length > 1">{{ words.page }}<select v-model.number="stagePageIndex"><option v-for="(page, index) in stagePages" :key="index" :value="index">{{ value(page.pageOrdinalZeroBased) }}</option></select></label>
          <label>{{ words.line }}<select v-model="lineFilter"><option value="all">{{ words.all }}</option><option value="unretained">{{ words.unresolved }}</option></select></label>
        </div>
        <p class="diagnostic-summary">{{ words.detected }}: {{ stageLines.length }} · {{ words.retained }}: {{ retainedCount }} · {{ words.unresolved }}: {{ stageLines.length - retainedCount }}<br />{{ words.complete }}: {{ boolean(savedPage.complete) }} · {{ words.mapping }}: {{ boolean(savedPage.mappingVerified) }}</p>
        <p v-if="records(diagnostics.metadataErrors).length || (Array.isArray(diagnostics.metadataErrors) && diagnostics.metadataErrors.length)" class="diagnostic-warning">{{ words.metadataErrors }}: {{ inspectionJson(diagnostics.metadataErrors) }}</p>
        <div v-if="shownLines.length" class="diagnostic-scroll"><table class="diagnostic-table stage-table">
          <thead><tr><th>{{ words.line }}</th><th>{{ words.detection }}</th><th>{{ words.orientation }}</th><th>{{ words.recognition }}</th><th>{{ words.filtering }}</th><th>{{ words.geometry }}</th><th>{{ words.raw }}</th></tr></thead>
          <tbody><tr v-for="(line, index) in shownLines" :key="String(line.lineId ?? index)" :class="{ 'diagnostic-rejected': line.terminalStage === 'below_text_score' }">
            <td><code>{{ value(line.lineId) }}</code><small>ordinal: {{ value(line.detectionOrdinalZeroBased) }}</small></td>
            <td>{{ words.confidence }}: {{ value(line.detectorConfidence) }}<small>{{ value(line.detectorConfidenceAssociation) }}</small></td>
            <td>{{ words.angle }}: {{ value(record(line.classification).label) }}<small>{{ words.confidence }}: {{ value(record(line.classification).confidence) }}</small><small>{{ words.rotation }}: {{ boolean(record(line.classification).rotationPolicyTriggered) }}</small></td>
            <td><div class="diagnostic-text">{{ value(record(line.recognition).text) }}</div><small>{{ words.confidence }}: {{ value(record(line.recognition).confidence) }}</small><strong v-if="line.terminalStage === 'below_text_score'" class="diagnostic-warning-label">{{ words.lowScore }}</strong></td>
            <td>{{ terminal(line) }}<small>{{ words.blankFilter }}: {{ boolean(record(line.blankTextFilter).kept) }}</small><small>{{ words.scoreFilter }}: {{ boolean(record(line.scoreFilter).kept) }}</small><small>{{ words.threshold }}: {{ value(record(line.scoreFilter).threshold) }}</small><small>finalOrdinalZeroBased: {{ value(line.finalOrdinalZeroBased) }}</small><code>{{ value(line.terminalStage) }}</code></td>
            <td><details><summary>{{ words.geometry }}</summary><pre>{{ inspectionJson(originalGeometry(line)) }}</pre></details></td>
            <td><details><summary>{{ words.raw }}</summary><pre>{{ inspectionJson(line) }}</pre></details></td>
          </tr></tbody>
        </table></div>
        <p v-else class="muted">{{ words.empty }}</p>
        <div class="diagnostic-pagination"><span>{{ words.shown }} {{ range(filteredLines.length, stagePage) }} / {{ filteredLines.length }} {{ words.records }} · {{ stagePage }}/{{ stagePageCount }}</span><button class="btn small" :disabled="stagePage <= 1" @click="stagePage--">{{ words.previous }}</button><button class="btn small" :disabled="stagePage >= stagePageCount" @click="stagePage++">{{ words.next }}</button></div>
        <details><summary>{{ words.parameters }}</summary><pre>{{ inspectionJson({ schema: diagnostics.schema, tag: diagnostics.tag, parametersChanged: diagnostics.parametersChanged, additionalNeuralCalls: diagnostics.additionalNeuralCalls, metadataErrors: diagnostics.metadataErrors, expectedTextOrRegionRead: diagnostics.expectedTextOrRegionRead, originalImageShape: savedPage.originalImageShape, preprocess: savedPage.preprocess, scoreThreshold: savedPage.scoreThreshold, complete: savedPage.complete, mappingVerified: savedPage.mappingVerified }) }}</pre></details>
      </template>
    </details>

    <details open data-inspection="raster-cells">
      <summary>{{ words.tables }} · {{ tables.length }}</summary>
      <p class="diagnostic-boundary">{{ words.tableBoundary }}</p>
      <p v-if="!hasSavedTables" class="muted">{{ words.unrecordedTables }}</p>
      <template v-else>
        <p class="diagnostic-summary">{{ words.frame }}: {{ value(tableEnvelope.coordinateFrame) }}<br />{{ words.inputRaster }}</p>
        <div v-if="tables.length" class="diagnostic-controls"><label>{{ words.table }}<select v-model.number="tableIndex"><option v-for="(candidate, index) in tables" :key="String(candidate.id ?? index)" :value="index">{{ value(candidate.id) }} · {{ tableDimension(candidate.rowBoundaries) }} × {{ tableDimension(candidate.columnBoundaries) }} · {{ records(candidate.cells).length }} {{ words.cells }}</option></select></label></div>
        <p class="diagnostic-summary">{{ words.status }}: {{ value(table.status ?? tableResult.status) }} · nativeTable: {{ value(table.nativeTable) }} · semanticStructureVerified: {{ value(table.semanticStructureVerified) }}</p>
        <p class="diagnostic-boundary">{{ words.crossingBoundary }}</p>
        <div v-if="shownCells.length" class="diagnostic-scroll"><table class="diagnostic-table cell-table">
          <thead><tr><th>ID</th><th>{{ words.row }} / {{ words.column }}</th><th>{{ words.span }}</th><th>{{ words.recognition }}</th><th>{{ words.status }}</th><th>{{ words.source }}</th></tr></thead>
          <tbody><tr v-for="(cell, index) in shownCells" :key="String(cell.id ?? index)" :class="{ 'diagnostic-rejected': cell.textMayCrossRasterCellBoundaries === true }">
            <td><code>{{ value(cell.id) }}</code><details><summary>{{ words.geometry }}</summary><pre>{{ inspectionJson(cell.bboxXYXY) }}</pre></details></td>
            <td>{{ value(cell.row) }} / {{ value(cell.column) }}</td><td>{{ value(cell.rowSpan) }} × {{ value(cell.columnSpan) }}</td>
            <td><div v-if="typeof cell.text === 'string' && cell.text.length" class="diagnostic-text">{{ cell.text }}</div><span v-else>{{ cell.textStatus === 'no_assigned_ocr_text_unknown' ? words.unknownCell : words.unknown }}</span></td>
            <td><code>{{ value(cell.textStatus) }}</code><small>headerStatus: {{ value(cell.headerStatus) }}</small><small>nativeCell: {{ value(cell.nativeCell) }}</small><small>textMayCrossRasterCellBoundaries: {{ value(cell.textMayCrossRasterCellBoundaries) }}</small><strong v-if="cell.textMayCrossRasterCellBoundaries === true" class="diagnostic-warning-label">{{ words.crossing }}</strong></td>
            <td><code>{{ Array.isArray(cell.sourceLineIds) && cell.sourceLineIds.length ? cell.sourceLineIds.join(', ') : words.unknown }}</code><details><summary>{{ words.source }}</summary><pre>{{ inspectionJson(cell.sourceLineAssignments) }}</pre></details><details><summary>{{ words.raw }}</summary><pre>{{ inspectionJson(cell) }}</pre></details></td>
          </tr></tbody>
        </table></div>
        <p v-else class="muted">{{ words.empty }}</p>
        <div class="diagnostic-pagination"><span>{{ words.shown }} {{ range(cells.length, cellPage) }} / {{ cells.length }} {{ words.cells }} · {{ cellPage }}/{{ cellPageCount }}</span><button class="btn small" :disabled="cellPage <= 1" @click="cellPage--">{{ words.previous }}</button><button class="btn small" :disabled="cellPage >= cellPageCount" @click="cellPage++">{{ words.next }}</button></div>
        <details><summary>{{ words.parameters }}</summary><pre>{{ inspectionJson({ coordinateFrame: tableEnvelope.coordinateFrame, originalPageTransform: tableEnvelope.originalPageTransform, rawResult: tableEnvelope.rawResult, inputImage: tableEnvelope.inputImage, algorithmModule: tableEnvelope.algorithmModule, status: tableResult.status, parameters: tableResult.parameters, limitations: tableResult.limitations, bboxXYXY: table.bboxXYXY, rowBoundaries: table.rowBoundaries, columnBoundaries: table.columnBoundaries, ambiguousSourceLineIds: table.ambiguousSourceLineIds, unassignedSourceLineIds: table.unassignedSourceLineIds }) }}</pre></details>
      </template>
    </details>
  </section>
</template>

<style scoped>
.ocr-diagnostics { border-top:1px solid var(--line);margin-top:14px;padding-top:4px;min-width:0; }
details { margin:12px 0; }summary { cursor:pointer;font-size:13px;font-weight:600; }
.diagnostic-boundary { font-size:12px;line-height:1.6;background:var(--blue-soft);border-left:3px solid var(--blue);padding:8px 10px; }
.diagnostic-summary { font-size:12px;line-height:1.7;overflow-wrap:anywhere;color:var(--muted); }
.diagnostic-controls { display:flex;flex-wrap:wrap;gap:10px; }.diagnostic-controls label { display:flex;flex-direction:column;gap:5px;font-size:12px; }.diagnostic-controls select { max-width:100%;padding:7px;border:1px solid var(--line);border-radius:5px;background:white; }
.diagnostic-scroll { max-height:480px;overflow:auto;border:1px solid var(--line);border-radius:5px; }
.diagnostic-table { border-collapse:collapse;width:100%;font-size:12px; }.stage-table { min-width:1080px; }.cell-table { min-width:900px; }.diagnostic-table th,.diagnostic-table td { text-align:left;vertical-align:top;padding:9px;border-bottom:1px solid var(--line);max-width:250px;overflow-wrap:anywhere; }.diagnostic-table th { position:sticky;top:0;background:var(--surface-subtle);z-index:1; }
.diagnostic-table small { display:block;color:var(--muted);margin-top:4px; }.diagnostic-text { white-space:pre-wrap;line-height:1.6; }.diagnostic-rejected { background:var(--amber-soft); }.diagnostic-warning-label { display:block;color:var(--amber);font-size:11px;margin-top:6px; }.diagnostic-warning { font-size:12px;color:var(--amber);overflow-wrap:anywhere; }
.diagnostic-table details { margin:6px 0; }.diagnostic-table summary { font-size:11px; }.diagnostic-pagination { display:flex;flex-wrap:wrap;align-items:center;gap:8px;font-size:12px;margin-top:10px; }.diagnostic-pagination span { margin-right:auto; }
pre { white-space:pre-wrap;overflow-wrap:anywhere;word-break:break-word;background:var(--surface-subtle);border:1px solid var(--line);border-radius:5px;padding:10px;font:11px/1.6 var(--font-mono);max-height:320px;overflow:auto; }code { font-family:var(--font-mono);font-size:11px; }
</style>
