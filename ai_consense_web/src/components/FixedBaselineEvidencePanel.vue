<script setup lang="ts">
import { computed, defineAsyncComponent, onBeforeUnmount, ref, watch } from 'vue'
import { fetchFixedEvidence, fetchFixedEvidenceGroup, fetchFixedOriginal, fetchFixedTable } from '@/api/fixedBaselineEvidence'
import type { FixedBaselineEvidenceAddress, FixedBaselineEvidence, FixedBaselineSourceUnit } from '@/api/fixedBaselineEvidence'
const PdfPreview = defineAsyncComponent(() => import('@/components/DraftingPdfPreview.vue'))
const props = withDefaults(defineProps<{ pointer: FixedBaselineEvidenceAddress; locale?: 'zh-Hans' | 'zh-Hant' | 'en' }>(), { locale: 'zh-Hans' })
const words = {
  title: ['固定标准原件', '固定標準原件', 'Fixed standard source'],
  note: ['标准依据；项目采用尚未确认。开发基准质量、限定条件及法律归属保持未确认。', '標準依據；項目採用尚未確認。開發基準品質、限定條件及法律歸屬保持未確認。', 'Standard reference; project adoption is unconfirmed. Development source quality, qualifiers and legal ownership remain unverified.'],
  original: ['读取完整原 PDF', '讀取完整原 PDF', 'Read the complete original PDF'],
  originalPage: ['打开引用原页', '開啟引用原頁', 'Open cited original page'],
  group: ['完整来源成员组', '完整來源成員組', 'Complete source member group'],
  required: ['必要成员', '必要成員', 'Required members'], present: ['已回取成员', '已回取成員', 'Retrieved members'], missing: ['缺失成员／资产', '缺失成員／資產', 'Missing members / assets'],
  groupUnknown: ['成员／限定关系未确认。缺失项不能当作完整依据；其余回取成员仍保留。', '成員／限定關係未確認。缺失項不能當作完整依據；其餘回取成員仍保留。', 'Member and qualifier relations remain unverified. Missing items are not complete evidence; other retrieved members remain available.'],
  noAssets: ['未绑定完整表格资产；请回查完整原页。', '未綁定完整表格資產；請回查完整原頁。', 'No complete table asset is bound; inspect the full original pages.'],
  structure: ['来源结构与未确认项', '來源結構與未確認項', 'Source structure and unresolved items'],
  table: ['完整表格资产', '完整表格資產', 'Complete table asset'],
  loading: ['读取中…', '讀取中…', 'Loading…'], retry: ['重新读取证据', '重新讀取證據', 'Reload evidence'],
  stale: ['固定基准或原件已变化；保留历史引用，请重新取证。', '固定基準或原件已變化；保留歷史引用，請重新取證。', 'The fixed baseline or original changed. Retain the historical citation and retrieve current evidence.'],
  unavailable: ['原引用不可用，未换绑其他来源。', '原引用不可用，未換綁其他來源。', 'The cited source is unavailable. It has not been rebound to another source.'],
  verified: ['原 PDF 字节 SHA-256 已核对。', '原 PDF 位元組 SHA-256 已核對。', 'Original PDF byte SHA-256 verified.']
} as const
const label = (key: keyof typeof words) => words[key][props.locale === 'en' ? 2 : props.locale === 'zh-Hant' ? 1 : 0]
const evidence = ref<FixedBaselineEvidence | null>(null), table = ref<unknown>(null), original = ref(''), loading = ref(false), originalLoading = ref(false), error = ref(''), assetError = ref('')
const sourceUnit = ref<FixedBaselineSourceUnit | null>(null), group = ref<Awaited<ReturnType<typeof fetchFixedEvidenceGroup>> | null>(null)
const matrix = computed(() => {
  const value = (table.value as { table?: { extractedMatrix?: unknown } } | null)?.table?.extractedMatrix
  return Array.isArray(value) && value.every(row => Array.isArray(row)) ? value as unknown[][] : []
})
const displayError = computed(() => error.value === 'FIXED_EVIDENCE_STALE' || error.value.includes('MISMATCH') ? label('stale') : error.value === 'FIXED_EVIDENCE_UNAVAILABLE' ? label('unavailable') : error.value)
let epoch = 0, controller: AbortController | null = null
function cleanup() { controller?.abort(); if (original.value) URL.revokeObjectURL(original.value); original.value = '' }
async function load() {
  const current = ++epoch; cleanup(); controller = new AbortController()
  evidence.value = null; table.value = null; sourceUnit.value = null; group.value = null; error.value = ''; assetError.value = ''; loading.value = true; originalLoading.value = false
  const address = Object.freeze(JSON.parse(JSON.stringify(props.pointer)) as FixedBaselineEvidenceAddress), signal = controller.signal
  try {
    const result = await fetchFixedEvidence(address, signal)
    if (current !== epoch) return
    evidence.value = result
    sourceUnit.value = address.sourceUnit ?? result.sourceUnit as FixedBaselineSourceUnit | null
    if (result.tableArtifact) {
      try { const value = await fetchFixedTable(address, result.payload.tableArtifact as Record<string, unknown>, signal); if (current === epoch) table.value = value }
      catch (caught) { if (current === epoch) assetError.value = caught instanceof Error ? caught.message : String(caught) }
    }
    if (sourceUnit.value) {
      try { const value = await fetchFixedEvidenceGroup(address, sourceUnit.value, signal); if (current === epoch) group.value = value }
      catch (caught) { if (current === epoch) assetError.value = caught instanceof Error ? caught.message : String(caught) }
    }
  } catch (caught) { if (current === epoch) error.value = caught instanceof Error ? caught.message : String(caught) }
  finally { if (current === epoch) loading.value = false }
}
async function openOriginal() {
  if (!evidence.value || originalLoading.value) return
  const current = epoch, address = Object.freeze({ ...props.pointer })
  originalLoading.value = true; assetError.value = ''
  try {
    const blob = await fetchFixedOriginal(address, controller?.signal)
    if (current !== epoch) return
    if (original.value) URL.revokeObjectURL(original.value)
    original.value = URL.createObjectURL(blob)
  } catch (caught) { if (current === epoch) assetError.value = caught instanceof Error ? caught.message : String(caught) }
  finally { if (current === epoch) originalLoading.value = false }
}
watch(() => props.pointer, load, { immediate: true, deep: true })
onBeforeUnmount(() => { ++epoch; cleanup() })
</script>
<template>
  <section class="fixed-evidence-panel canonical-evidence-panel" aria-live="polite">
    <h4>{{ label('title') }}</h4>
    <p class="warning-note">{{ label('note') }}</p>
    <p class="mono small">{{ pointer.sourceFamily }} · {{ pointer.baselineVersion }} · {{ pointer.manifestSha256 }}</p>
    <p class="mono small">SHA-256: {{ pointer.originalSourceSha256 }} · {{ pointer.originalPageNos?.join(', ') || pointer.pageNo }}</p>
    <p v-if="loading" role="status">{{ label('loading') }}</p>
    <p v-if="error" role="alert">{{ displayError }}</p>
    <button type="button" class="btn" :disabled="loading" @click="load">{{ label('retry') }}</button>
    <template v-if="evidence">
      <strong>{{ evidence.fileLabel }}</strong>
      <pre>{{ evidence.content }}</pre>
      <p v-if="!evidence.tableArtifact && !group?.members.some(member => member.table)" class="warning-note">{{ label('noAssets') }}</p>
      <div v-if="matrix.length" class="table-wrap"><table><tbody><tr v-for="(row, rowIndex) in matrix" :key="rowIndex"><td v-for="(cell, cellIndex) in row" :key="cellIndex">{{ cell === null ? 'null' : cell }}</td></tr></tbody></table></div>
      <details v-if="table !== null" open><summary>{{ label('table') }}</summary><pre>{{ JSON.stringify(table, null, 2) }}</pre></details>
      <div v-if="sourceUnit" class="source-member-group"><h5>{{ label('group') }}</h5>
        <p>{{ label('required') }}: {{ sourceUnit.requiredMemberIds?.join(', ') || 'unknown' }}</p>
        <p>{{ label('present') }}: {{ group?.members.map(member => member.evidence.id).join(', ') || 'unknown' }}</p>
        <p v-if="group?.missingMemberIds.length || group?.missingAssetIds.length" role="alert">{{ label('missing') }}: {{ [...(group?.missingMemberIds ?? []), ...(group?.missingAssetIds ?? [])].join(', ') }}</p>
        <p class="warning-note">{{ label('groupUnknown') }}</p>
        <article v-for="member in group?.members ?? []" :key="member.evidence.id"><strong>{{ member.evidence.fileLabel }} · {{ member.evidence.pageNo }} · {{ member.evidence.id }}</strong><pre>{{ member.evidence.content }}</pre><details v-if="member.table"><summary>{{ label('table') }}</summary><pre>{{ JSON.stringify(member.table, null, 2) }}</pre></details></article>
      </div>
      <details><summary>{{ label('structure') }}</summary><pre>{{ JSON.stringify({ sourceUnit, sourceQuality: evidence.payload.sourceQuality, unknowns: evidence.payload.unknowns, projectApplicability: pointer.projectApplicability, qualifiersComplete: pointer.qualifiersComplete }, null, 2) }}</pre></details>
      <button type="button" class="btn" :disabled="originalLoading" @click="openOriginal">{{ originalLoading ? label('loading') : label('original') }}</button>
      <p v-if="assetError" role="alert">{{ assetError }}</p>
      <template v-if="original"><p>{{ label('verified') }}</p><a class="btn" :href="`${original}#page=${pointer.originalPageNos?.[0] || pointer.pageNo}`" target="_blank" rel="noopener noreferrer">{{ label('originalPage') }}</a><PdfPreview :source="original" :initial-page="pointer.originalPageNos?.[0] || pointer.pageNo" :title="evidence.fileLabel" :locale="locale" /></template>
    </template>
  </section>
</template>
<style scoped>
.fixed-evidence-panel { padding: 1rem; margin-top: 1rem; border: 1px solid var(--line); border-radius: 8px; overflow-wrap: anywhere; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 28rem; overflow: auto; }
details, button { margin-top: .75rem; } [role=alert] { color: #9b2929; }
</style>
