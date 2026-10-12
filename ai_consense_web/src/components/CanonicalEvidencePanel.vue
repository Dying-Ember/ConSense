<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { CanonicalEvidenceError, evidenceCoverage, fetchCanonicalAsset, fetchCanonicalPointer, isNativeEvidence, orderedEvidenceAssets, orderedEvidenceParts } from '../api/canonicalEvidence'
import type { CanonicalEvidenceAddress, CanonicalEvidenceAsset, CanonicalEvidencePointer } from '../api/canonicalEvidence'

const props = withDefaults(defineProps<{ pointer: CanonicalEvidenceAddress | null; locale?: 'zh-Hans' | 'zh-Hant' | 'en' }>(), { locale: 'zh-Hans' })
const copy = {
  sourcePage: ['完整原页渲染', '完整原頁渲染', 'Full original page render'],
  title: ['原件证据', '原件證據', 'Original source evidence'], loading: ['正在读取绑定的原件证据…', '正在讀取綁定的原件證據…', 'Loading bound source evidence…'],
  empty: ['请选择明确的 canonical 原件位置。', '請選擇明確的 canonical 原件位置。', 'Select an explicit canonical source address.'],
  source: ['来源文件', '來源文件', 'Source file'], pages: ['原 PDF 物理页', '原 PDF 物理頁', 'Original PDF physical pages'],
  notHuman: ['未经过人工确认；unknown 属性继续保留。', '未經過人工確認；unknown 屬性繼續保留。', 'Not human-confirmed; unknown attributes remain unknown.'],
  noAssets: ['此位置没有绑定图像资产，当前仅展示文字与来源；不能据此宣称图表可视化完成。', '此位置沒有綁定圖像資產，目前僅展示文字與來源；不能據此宣稱圖表視覺化完成。', 'No image assets are bound. Only text and source metadata can be shown; visual evidence is unavailable.'],
  incomplete: ['跨页证据不完整，请核对缺页、缺失区域或资产读取失败；不能视为完整表组。', '跨頁證據不完整，請核對缺頁、缺失區域或資產讀取失敗；不能視為完整表組。', 'Cross-page evidence is incomplete. Check missing pages, regions or asset failures; this is not a complete table group.'],
  table: ['跨页表组', '跨頁表組', 'Cross-page table group'], position: ['原页 normalized xyxy 位置', '原頁 normalized xyxy 位置', 'Original-page normalized xyxy position'],
  nativeParts: ['原生 DOCX 部件', '原生 DOCX 部件', 'Native DOCX package parts'],
  nativePosition: ['原生节点位置', '原生節點位置', 'Native node position'],
  nativeChannel: ['文字通道（不代表最终有效正文）', '文字通道（不代表最終有效正文）', 'Observed text channel (effective contract text remains unverified)'],
  nativeTable: ['完整原生表组观察', '完整原生表組觀察', 'Complete native table-group observation'],
  nativeBlank: ['空白文字观察，原节点仍保留。', '空白文字觀察，原節點仍保留。', 'Blank text observation; the original node is retained.'],
  nativeBoundary: ['DOCX 原件没有固定物理页或原页坐标；派生图像映射尚未核验。', 'DOCX 原件沒有固定物理頁或原頁座標；衍生圖像映射尚未核驗。', 'Native DOCX has no fixed physical page or original-page coordinates. Derived visual mapping is not verified.'],
  text: ['原结构文字（保留原文）', '原結構文字（保留原文）', 'Source structure text (literal)'],
  attributes: ['来源属性与未确认项', '來源屬性與未確認項', 'Source attributes and unresolved items'],
  assetLoading: ['图像读取中…', '圖像讀取中…', 'Loading image…'], imageError: ['图像无法读取', '圖像無法讀取', 'Image unavailable'],
  shaVerified: ['图像字节 SHA-256 已在浏览器核对。', '圖像位元組 SHA-256 已在瀏覽器核對。', 'Image byte SHA-256 was verified in the browser.'],
  geometryUnknown: ['位置未知', '位置未知', 'Position unknown'], retry: ['重新读取', '重新讀取', 'Reload evidence'],
  CANONICAL_BINDING_UNAVAILABLE: ['原引用的证据绑定当前不可用。请核对来源登记；历史引用位置继续保留。', '原引用的證據綁定目前不可用。請核對來源登記；歷史引用位置繼續保留。', 'The original evidence binding is unavailable. Check source registration; the historical citation address is retained.'],
  CANONICAL_SOURCE_STALE: ['来源或解析修订已变化，历史引用当前无法核验。请按当前来源重新查询。', '來源或解析修訂已變化，歷史引用目前無法核驗。請按目前來源重新查詢。', 'The source or parsed revision changed. This historical citation cannot be verified against the current source; query the current source again.'],
  SOURCE_ROUTING_STALE: ['来源身份或适用范围已变化，历史引用当前不可用。请核对当前来源范围。', '來源身分或適用範圍已變化，歷史引用目前不可用。請核對目前來源範圍。', 'Source identity or eligibility changed. This historical citation is unavailable; check the current source scope.'],
  SOURCE_ORIGINAL_BYTES_CHANGED: ['原文件字节已变化，历史引用当前无法核验。请重新解析并查询当前原件。', '原檔案位元組已變化，歷史引用目前無法核驗。請重新解析並查詢目前原件。', 'The original file bytes changed. This historical citation cannot be verified; reparse and query the current original.']
} as const
const localeIndex = computed(() => props.locale === 'en' ? 2 : props.locale === 'zh-Hant' ? 1 : 0)
const label = (key: keyof typeof copy) => copy[key][localeIndex.value]
const resolved = ref<CanonicalEvidencePointer | null>(null)
const loading = ref(false)
const error = ref('')
type AssetView = { asset: CanonicalEvidenceAsset; url: string | null; status: 'loading' | 'ready' | 'error'; error: string; shaImplementation: 'webcrypto' | 'portable' | null }
const assets = ref<AssetView[]>([])
const parts = computed(() => resolved.value ? orderedEvidenceParts(resolved.value) : [])
const native = computed(() => resolved.value ? isNativeEvidence(resolved.value) : false)
const nativePartNames = computed(() => [...new Set(parts.value.map(part => part.sourcePosition?.partName).filter(Boolean))])
const coverage = computed(() => resolved.value ? evidenceCoverage(resolved.value) : null)
const incomplete = computed(() => coverage.value?.status === 'incomplete' || assets.value.some(view => view.status === 'error'))
let epoch = 0
let active: AbortController | null = null
function clearAssets() { for (const asset of assets.value) if (asset.url) URL.revokeObjectURL(asset.url); assets.value = [] }
function reason(value: unknown) { return value instanceof CanonicalEvidenceError && Object.prototype.hasOwnProperty.call(copy, value.reason) ? value.reason : value instanceof Error ? value.message : String(value) }
function displayReason(value: string) { return Object.prototype.hasOwnProperty.call(copy, value) ? label(value as keyof typeof copy) : value }
function imageFailed(assetId: string, url: string | null) {
  const view = assets.value.find(item => item.asset.assetId === assetId && item.url === url)
  if (!view || !url) return
  URL.revokeObjectURL(url)
  view.url = null; view.status = 'error'; view.error = 'IMAGE_DECODE_FAILED'
}

async function load() {
  const current = ++epoch
  active?.abort(); active = null; clearAssets()
  resolved.value = null; error.value = ''; loading.value = false
  if (!props.pointer) return
  // Capture primitives so a parent mutation cannot change request identity mid-flight.
  const address = Object.freeze({ ...props.pointer })
  active = new AbortController()
  const signal = active.signal
  loading.value = true
  try {
    const pointer = await fetchCanonicalPointer(address, signal)
    if (current !== epoch) return
    resolved.value = pointer
    assets.value = orderedEvidenceAssets(pointer).map(asset => ({ asset, url: null, status: 'loading', error: '', shaImplementation: null }))
    await Promise.all(assets.value.map(async (view, index) => {
      try {
        const result = await fetchCanonicalAsset(address, view.asset, signal)
        if (current !== epoch) return
        const url = URL.createObjectURL(result.blob)
        assets.value[index] = { ...view, url, status: 'ready', shaImplementation: result.browserShaImplementation }
      } catch (caught) {
        if (current === epoch) assets.value[index] = { ...view, status: 'error', error: reason(caught) }
      }
    }))
  } catch (caught) { if (current === epoch) error.value = reason(caught) }
  finally { if (current === epoch) loading.value = false }
}
watch(() => props.pointer, load, { immediate: true, deep: true })
onBeforeUnmount(() => { ++epoch; active?.abort(); clearAssets() })
</script>

<template>
  <section class="canonical-evidence-panel" aria-live="polite">
    <h3>{{ label('title') }}</h3>
    <p v-if="!pointer">{{ label('empty') }}</p>
    <p v-if="loading" role="status">{{ label('loading') }}</p>
    <p v-if="error" role="alert">{{ displayReason(error) }}</p>
    <button v-if="pointer && !loading" type="button" @click="load">{{ label('retry') }}</button>
    <template v-if="resolved">
      <dl class="source-identity">
        <dt>{{ label('source') }}</dt><dd>{{ resolved.sourceFilename }}</dd>
        <template v-if="!native"><dt>{{ label('pages') }}</dt><dd>{{ [...resolved.physicalPages].sort((a, b) => a - b).join(', ') }}</dd></template>
        <template v-else><dt>{{ label('nativeParts') }}</dt><dd>{{ nativePartNames.join(', ') }}</dd></template>
        <dt>SHA-256</dt><dd>{{ resolved.originalSourceSha256 }}</dd>
      </dl>
      <p class="evidence-status">{{ label('notHuman') }}</p>
      <p v-if="native" role="status">{{ label('nativeBoundary') }}</p>
      <p v-if="resolved.tableGroup">{{ native ? label('nativeTable') : label('table') }}: {{ resolved.tableGroup.tableGroupId }}</p>
      <p v-if="coverage?.status === 'no_assets'" role="status">{{ label('noAssets') }}</p>
      <p v-if="incomplete" role="alert">{{ label('incomplete') }} {{ coverage?.missingPages.join(', ') }}</p>
      <div class="source-images">
        <figure v-for="view in assets" :key="view.asset.assetId" :data-asset-id="view.asset.assetId">
          <figcaption>{{ label('pages') }} {{ view.asset.physicalPages.join(', ') }} — {{ view.asset.kind === 'source_page_render' ? label('sourcePage') : (view.asset.artifactName || view.asset.assetId) }}</figcaption>
          <p v-if="view.status === 'loading'" role="status">{{ label('assetLoading') }}</p>
          <p v-if="view.status === 'error'" role="alert">{{ label('imageError') }}: {{ displayReason(view.error) }}</p>
          <img v-if="view.url" :src="view.url" :alt="`${resolved.sourceFilename} — ${label('pages')} ${view.asset.physicalPages.join(', ')}`" @error="imageFailed(view.asset.assetId, view.url)" />
          <p v-if="view.shaImplementation" class="evidence-status" :data-sha-implementation="view.shaImplementation">{{ label('shaVerified') }}</p>
          <p v-for="region in view.asset.sourceRegions" :key="region.blockId" class="source-position">{{ label('position') }}: {{ region.bbox?.join(', ') || label('geometryUnknown') }}</p>
          <p v-if="view.asset.sourceRegions.length === 0" class="source-position">{{ label('position') }}: {{ label('geometryUnknown') }}</p>
        </figure>
      </div>
      <details>
        <summary>{{ label('text') }}</summary>
        <article v-for="part in parts" :key="part.blockId" :data-block-id="part.blockId">
          <template v-if="native">
            <p>{{ label('nativePosition') }}: {{ part.sourcePosition?.partName }}</p>
            <pre class="native-position">{{ part.sourcePosition?.nodePath }}</pre>
            <p>{{ label('nativeChannel') }}: {{ part.textTransform?.textChannel }} · UTF-16 [{{ part.textTransform?.sourceStartUtf16 }}, {{ part.textTransform?.sourceEndUtf16 }})</p>
            <p v-if="part.text === ''" class="evidence-status">{{ label('nativeBlank') }}</p>
          </template>
          <p v-else>{{ label('pages') }} {{ part.physicalPageNo }} · {{ label('position') }}: {{ part.bbox?.join(', ') || label('geometryUnknown') }}</p>
          <pre>{{ part.text }}</pre>
        </article>
      </details>
      <details v-if="native && resolved.tableGroup">
        <summary>{{ label('nativeTable') }}</summary>
        <pre>{{ JSON.stringify(resolved.tableGroup, null, 2) }}</pre>
      </details>
      <details>
        <summary>{{ label('attributes') }}</summary>
        <pre>{{ JSON.stringify({ sourceQuality: resolved.sourceQuality, unknowns: resolved.unknowns, relations: resolved.relations, sourceRenderCaptureCompleteness: resolved.sourceRenderCaptureCompleteness, sourceRenderLimitations: resolved.sourceRenderLimitations, humanConfirmed: resolved.humanConfirmed, ...(native ? { visualReturnStatus: resolved.visualReturnStatus, editingStates: parts.map(part => part.nativeEditingState) } : {}) }, null, 2) }}</pre>
      </details>
    </template>
  </section>
</template>

<style scoped>
.canonical-evidence-panel { padding: 1rem; border: 1px solid #ccd3df; border-radius: .5rem; background: #fff; color: #263245; }
h3 { margin: 0 0 .7rem; }
.source-identity { display: grid; grid-template-columns: minmax(7rem, auto) minmax(0, 1fr); gap: .35rem .75rem; }
.source-identity dt { font-weight: 600; }.source-identity dd { margin: 0; overflow-wrap: anywhere; }
.evidence-status, .source-position { font-size: .85rem; color: #536078; }
.source-images { display: grid; gap: 1rem; }.source-images figure { margin: 0; }.source-images img { display: block; max-width: 100%; height: auto; margin-top: .5rem; border: 1px solid #ccd3df; }
details { margin-top: 1rem; } summary { cursor: pointer; } pre { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 28rem; overflow: auto; }
[role=alert] { color: #9b2929; } button { padding: .4rem .65rem; }
</style>
