<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { draftingApi } from '@/api'
import type { DraftCandidate, DraftDocument, DraftDocumentBinding, DraftDocumentBindings, DraftField, DraftVariable, ExtractContext, ExtractTrace } from '@/api/types'
import type { AppLocale } from '@/i18n'
import type { DraftValue } from '@/drafting/state'
import DraftingPdfPreview from './DraftingPdfPreview.vue'
import DraftingValueDisplay from './DraftingValueDisplay.vue'
import DraftingExtractionContext from './DraftingExtractionContext.vue'
import DraftingNativeLayoutStatus from './DraftingNativeLayoutStatus.vue'
import { bindingPoint, matchesBindingPdf, pdfSha256 } from '@/drafting/document-bindings'
import { draftWord, localized, type DraftWord } from '@/drafting/words'
import { previewWord } from '@/drafting/preview-words'

const props = defineProps<{ projectId: string; fileKey: string; document?: DraftDocument; resultSource: string; resultPdfHash: string; loading: boolean; dirty: boolean; editing: boolean; locale: AppLocale; immersive?: boolean; fields: DraftField[]; values: Record<string, DraftValue>; variables: DraftVariable[]; fieldStates: Record<string, string>; dirtyKeys: string[]; trace?: ExtractTrace | null; disabled?: boolean }>()
const emit = defineEmits<{ retry: []; input: [key: string]; file: [fileKey: string] }>()
const w = (key: DraftWord) => draftWord(key, props.locale)
const l = (field: DraftField) => localized(field.label, props.locale)
const view = ref<'source' | 'result'>('result')
const inspectorOpen = ref(!props.immersive)
const inspectorToggle = ref<HTMLButtonElement>()
const inspectorPanel = ref<HTMLElement>()
const inspectorOverlay = ref(false)
function updateInspectorLayout() { inspectorOverlay.value = window.innerWidth <= 1000 }
function closeInspector() { inspectorOpen.value = false; void nextTick(() => inspectorToggle.value?.focus({ preventScroll: true })) }
function inspectorKeydown(event: KeyboardEvent) {
  if (!props.immersive || !inspectorOpen.value || event.key !== 'Escape' || event.defaultPrevented) return
  event.preventDefault(); event.stopPropagation(); closeInspector()
}
const sourceUrl = ref(''), sourceBundle = ref<DraftDocumentBindings>(), resultBundle = ref<DraftDocumentBindings>()
const sourceLoading = ref(false), resultLoading = ref(false), sourceError = ref(''), resultError = ref('')
const shownError = computed(() => view.value === 'source' ? sourceError.value : resultError.value)
const activeBundle = computed(() => view.value === 'source' ? sourceBundle.value : resultBundle.value)
const selectedFieldKey = ref(''), selectedBindingId = ref(''), locationRequest = ref(0)
const explicitBinding = ref<{ bindingId: string; sourceSha256: string; hasSource: boolean; fieldKeys: string[]; origin: 'target' | 'saved-change'; documentIdentity: string }>()
const selectionIdentity = computed(() => JSON.stringify([props.projectId, props.fileKey, props.document?.sourceSha256, props.document?.revisionId, props.document?.docxSha256]))
const unavailablePoints = ref<string[]>([])
const unavailableRanges = ref<string[]>([])
const selectedField = computed(() => props.fields.find(field => field.key === selectedFieldKey.value))
const selectedVariable = computed(() => props.variables.find(variable => variable.key === selectedFieldKey.value))
const relatedBindings = computed(() => activeBundle.value?.bindings.filter(binding => binding.fieldKeys.includes(selectedFieldKey.value)) ?? [])
const recommendedBindings = computed(() => [...relatedBindings.value].sort((left, right) => recommendationRank(left) - recommendationRank(right)))
const compactBindings = computed(() => {
  const first = recommendedBindings.value.slice(0, 4)
  const selected = relatedBindings.value.find(binding => binding.bindingId === selectedBindingId.value)
  if (selected && !first.includes(selected)) first.push(selected)
  return first
})
const otherBindings = computed(() => relatedBindings.value.filter(binding => !compactBindings.value.some(first => first.bindingId === binding.bindingId)))
const selectedBinding = computed(() => activeBundle.value?.bindings.find(binding => binding.bindingId === selectedBindingId.value))
const originalLocationAbsent = computed(() => view.value === 'source' && !!activeBundle.value && !selectedBinding.value && explicitBinding.value?.bindingId === selectedBindingId.value && explicitBinding.value.sourceSha256 === activeBundle.value.sourceSha256 && !explicitBinding.value.hasSource && (!selectedFieldKey.value || explicitBinding.value.fieldKeys.includes(selectedFieldKey.value)))
const targetFields = computed(() => selectedBinding.value?.fieldKeys.flatMap(key => props.fields.filter(field => field.key === key)) ?? [])
const otherFiles = computed(() => [...new Set(selectedField.value?.affects?.map(target => target.document) ?? [])].filter(file => file !== props.fileKey))
const savedChangeBindings = computed(() => view.value === 'result' ? activeBundle.value?.bindings.filter(binding => binding.documentEditStatus === 'applied' || ['body_edited', 'body_added', 'generated_added'].includes(binding.applicationStatus)) ?? [] : [])
const markerLocations = computed(() => {
  const binding = selectedBinding.value
  if (activeBundle.value?.geometryStatus !== 'ready' || !binding) return []
  const fields = props.fields.filter(field => binding.fieldKeys.includes(field.key))
  const variableTarget = selectedField.value && binding.fieldKeys.includes(selectedField.value.key)
  const inspectedChange = !selectedFieldKey.value && !fields.length && explicitBinding.value?.origin === 'saved-change' && explicitBinding.value.bindingId === binding.bindingId && explicitBinding.value.documentIdentity === selectionIdentity.value
  // A registered paragraph or a broad action range is not itself a variable highlight.
  const highlight = !!(variableTarget || inspectedChange) && !templateFurniture(binding)
  const geometry = usablePoint(binding)
  return geometry ? [{ bindingId: binding.bindingId, geometry, highlight, text: targetText(binding) ?? undefined, label: `${w('paragraph')} ${ordinal(binding)} · ${fields.length ? fields.map(l).join(' / ') : applicationLabel(binding)}` }] : []
})
function templateFurniture(binding: DraftDocumentBinding) {
  const text = (targetText(binding) ?? '').replace(/\s+/g, ' ').trim()
  return /^(?:NOTES TO TENDERERS|SPECIAL CONDITIONS? OF TENDER|SPECIAL CONDITIONS? OF CONTRACT)(?:\s*\((?:Cont['’]d|continued)\))?\.?$/i.test(text)
    || /^(?:(?:HD\(QS\)\s*3C\/\d+\/\d+\/\d+|(?:January|February|March|April|May|June|July|August|September|October|November|December)\s+\d{4})\s*)?-\s*(?:NTT|SCT|SCC)\s*\/\s*\d+\s*-$/i.test(text)
    || /^(?:Preface:?|CONTENTS|TABLE OF CONTENTS)$/i.test(text)
}
function usablePoint(binding: DraftDocumentBinding) { return unavailablePoints.value.includes(binding.bindingId) ? undefined : bindingPoint(binding) }
function recommendationRank(binding: DraftDocumentBinding) {
  if (templateFurniture(binding)) return 4
  if (/^[*#]?\s*(?:\d+(?:\.\d+)*\.?|\([a-z0-9]+\))$/i.test((targetText(binding) ?? '').trim())) return 3
  if (activeBundle.value?.geometryStatus !== 'ready' || !usablePoint(binding) || !(view.value === 'source' ? binding.sourceParagraphId && binding.sourceText?.trim() : binding.resultParagraphId && binding.text?.trim())) return 3
  if (view.value === 'result' && ['generated_added', 'body_added', 'body_edited'].includes(binding.applicationStatus)) return 0
  if (view.value === 'result' && binding.applicationStatus === 'applied' && binding.appliedActionIds?.length) return 1
  return 2
}
function targetCaption(binding: DraftDocumentBinding) {
  const original = view.value === 'source' || !binding.resultParagraphId
  return `${props.fileKey} · ${w(view.value === 'result' && original ? 'sourceParagraph' : 'paragraph')} ${original ? binding.sourceParagraphOrdinal ?? '—' : binding.resultParagraphOrdinal ?? '—'}`
}
function targetText(binding: DraftDocumentBinding) { return view.value === 'source' || !binding.resultParagraphId ? binding.sourceText : binding.text }
function pointUnavailable(id: string) { if (!unavailablePoints.value.includes(id)) unavailablePoints.value = [...unavailablePoints.value, id] }
function rangeUnavailable(id: string) { if (!unavailableRanges.value.includes(id)) unavailableRanges.value = [...unavailableRanges.value, id] }
function rangeAvailable(id: string) { unavailableRanges.value = unavailableRanges.value.filter(item => item !== id) }
function ordinal(binding: DraftDocumentBinding) { return view.value === 'source' ? binding.sourceParagraphOrdinal : binding.resultParagraphOrdinal ?? binding.sourceParagraphOrdinal }
function applicationWord(binding: DraftDocumentBinding): DraftWord {
  if (binding.applicationStatus === 'applied') return binding.appliedActionIds?.length ? 'appliedTarget' : 'documentOperationApplied'
  return ({ unapplied: 'unappliedTarget', unchanged: 'unchangedTarget', body_edited: 'bodyEditedTarget', body_added: 'bodyAddedTarget', generated_added: 'generatedAddedTarget' } as const)[binding.applicationStatus]
}
function applicationLabel(binding: DraftDocumentBinding) {
  const label = w(applicationWord(binding))
  return binding.applicationStatus === 'unapplied' && binding.documentEditStatus === 'applied' ? `${label} · ${w('documentOperationApplied')}` : label
}
function locationWord(binding: DraftDocumentBinding): DraftWord { return ({ exact: 'exactBinding', removed: 'removedBinding', missing: 'missingBinding', approximate: 'approximateBinding', conflicted: 'conflictedBinding' } as const)[binding.locationStatus] ?? 'missingBinding' }
function selectBinding(binding: DraftDocumentBinding, origin: 'target' | 'saved-change' = 'target') {
  const bundle = activeBundle.value
  if (!bundle) return
  selectedBindingId.value = binding.bindingId
  explicitBinding.value = { bindingId: binding.bindingId, sourceSha256: bundle.sourceSha256, hasSource: !!binding.sourceParagraphId, fieldKeys: [...binding.fieldKeys], origin, documentIdentity: selectionIdentity.value }
  if (!binding.fieldKeys.includes(selectedFieldKey.value)) selectedFieldKey.value = props.fields.find(field => binding.fieldKeys.includes(field.key))?.key ?? ''
  locationRequest.value++
}
function markerSelected(id: string) {
  const binding = activeBundle.value?.bindings.find(item => item.bindingId === id)
  if (binding) {
    const origin = explicitBinding.value?.bindingId === id && explicitBinding.value.documentIdentity === selectionIdentity.value ? explicitBinding.value.origin : 'target'
    selectBinding(binding, origin); inspectorOpen.value = true
  }
}
interface QuotationContext { partId: string; attemptIndex?: number; sourceText: string; context?: ExtractContext }
function evidenceContext(candidate: DraftCandidate): QuotationContext | undefined {
  const quote = candidate.sourceQuote
  if (!quote || !candidate.sourceHash || candidate.sourceDocumentId === undefined || candidate.sourceDocumentId === null) return
  const parts = props.trace?.parts?.filter(part => part.sourceHash === candidate.sourceHash && part.sourceDocumentId !== null && String(part.sourceDocumentId) === String(candidate.sourceDocumentId)) ?? []
  const decisions = props.trace?.decisions?.filter(decision => decision.status === 'accepted' && decision.key === selectedFieldKey.value && decision.sourceQuote === candidate.sourceQuote && decision.normalizedValue === candidate.value) ?? []
  const contexts: QuotationContext[] = decisions.length ? decisions.flatMap(decision => parts.filter(part => part.partId === decision.partId).flatMap(part => {
    if (!part.attempts?.length) return [{ partId: part.partId, sourceText: part.sourceText }]
    return part.attempts.filter(attempt => attempt.attemptIndex === decision.attemptIndex && ['completed', 'succeeded', 'success'].includes(attempt.status)).flatMap(attempt => {
      if (attempt.context) return attempt.context.sourceText ? [{ partId: part.partId, attemptIndex: attempt.attemptIndex, sourceText: attempt.context.sourceText, context: attempt.context }] : []
      return attempt.kind === 'recall' ? [] : [{ partId: part.partId, attemptIndex: attempt.attemptIndex, sourceText: part.sourceText }]
    })
  })) : parts.map(part => ({ partId: part.partId, sourceText: part.sourceText }))
  const matching = new Map(contexts.filter(context => context.sourceText.includes(quote)).map(context => [JSON.stringify([context.partId, context.attemptIndex, context.context?.sourceStart, context.context?.sourceEnd, context.sourceText]), context]))
  return matching.size === 1 ? matching.values().next().value : undefined
}
let sourceGeneration = 0, resultGeneration = 0, alive = true
function clearSource() { sourceGeneration++; if (sourceUrl.value) URL.revokeObjectURL(sourceUrl.value); sourceUrl.value = ''; sourceBundle.value = undefined; sourceError.value = ''; sourceLoading.value = false }
async function openSource() {
  clearSource()
  const sequence = sourceGeneration, id = props.projectId, fileKey = props.fileKey
  const expectedSource = props.document?.stale ? undefined : props.document?.sourceSha256
  sourceLoading.value = true
  try {
    const initial = await draftingApi.templateBindings(id, fileKey, expectedSource)
    if (initial.fileKey !== fileKey || initial.view !== 'source' || !initial.sourceSha256 || expectedSource && initial.sourceSha256 !== expectedSource) throw new Error(w('bindingIdentityMismatch'))
    const blob = await draftingApi.templatePreview(id, fileKey, initial.sourceSha256)
    const hash = await pdfSha256(blob)
    const bundle = await draftingApi.templateBindings(id, fileKey, initial.sourceSha256)
    if (!alive || sequence !== sourceGeneration) return
    if (bundle.sourceSha256 !== initial.sourceSha256 || !matchesBindingPdf(bundle, fileKey, 'source', hash)) throw new Error(w('bindingIdentityMismatch'))
    sourceBundle.value = bundle; sourceUrl.value = URL.createObjectURL(blob)
  } catch (caught) { if (alive && sequence === sourceGeneration) sourceError.value = caught instanceof Error ? caught.message : String(caught) }
  finally { if (sequence === sourceGeneration) sourceLoading.value = false }
}
async function openResult() {
  const sequence = ++resultGeneration
  resultBundle.value = undefined; resultError.value = ''; resultLoading.value = false
  const document = props.document
  if (props.dirty || !props.resultSource || !document?.revisionId || !document.docxSha256) return
  resultLoading.value = true
  try {
    const bundle = await draftingApi.documentBindings(props.projectId, props.fileKey, document.revisionId, document.docxSha256)
    if (!alive || sequence !== resultGeneration) return
    if (!matchesBindingPdf(bundle, props.fileKey, 'result', props.resultPdfHash) || document.pdfSha256 !== props.resultPdfHash || bundle.revisionId !== document.revisionId || bundle.docxSha256 !== document.docxSha256 || bundle.renderProfileHash !== document.renderProfileHash || document.sourceSha256 && bundle.sourceSha256 !== document.sourceSha256) throw new Error(w('bindingIdentityMismatch'))
    resultBundle.value = bundle
  } catch (caught) { if (alive && sequence === resultGeneration) resultError.value = caught instanceof Error ? caught.message : String(caught) }
  finally { if (sequence === resultGeneration) resultLoading.value = false }
}
function chooseView(next: 'source' | 'result') { view.value = next; if (next === 'source' && !sourceUrl.value && !sourceLoading.value) void openSource() }
function retry() { if (view.value === 'source') void openSource(); else { emit('retry'); void openResult() } }
watch(() => [props.projectId, props.fileKey, props.document?.sourceSha256], () => { clearSource(); if (view.value === 'source') void openSource() })
watch(() => [props.projectId, props.fileKey, props.document?.sourceSha256, props.document?.revisionId, props.document?.docxSha256, props.document?.pdfSha256, props.document?.renderProfileHash, props.resultSource, props.resultPdfHash, props.dirty], openResult, { immediate: true })
watch([activeBundle, selectedFieldKey], () => {
  // A loading or rejected bundle cannot establish that the chosen paragraph is absent.
  if (!activeBundle.value) return
  if (originalLocationAbsent.value) return
  if (!selectedFieldKey.value && selectedBinding.value) return
  if (!explicitBinding.value) { selectedBindingId.value = recommendedBindings.value[0]?.bindingId ?? ''; return }
  if (!relatedBindings.value.some(binding => binding.bindingId === selectedBindingId.value)) { explicitBinding.value = undefined; selectedBindingId.value = recommendedBindings.value[0]?.bindingId ?? '' }
})
watch(activeBundle, () => { unavailablePoints.value = []; unavailableRanges.value = [] })
watch(() => props.projectId, () => { selectedFieldKey.value = ''; selectedBindingId.value = ''; explicitBinding.value = undefined })
watch(() => props.fileKey, () => { selectedBindingId.value = ''; explicitBinding.value = undefined })
watch(() => props.immersive, expanded => { inspectorOpen.value = !expanded })
watch(inspectorOpen, opened => { if (props.immersive && opened) void nextTick(() => inspectorPanel.value?.focus({ preventScroll: true })) })
onMounted(() => { updateInspectorLayout(); window.addEventListener('resize', updateInspectorLayout) })
onBeforeUnmount(() => { alive = false; clearSource(); resultGeneration++; window.removeEventListener('resize', updateInspectorLayout) })
</script>

<template>
  <section class="bound-document" :class="{ 'bound-document--immersive': immersive }" :data-document-view="view" @keydown="inspectorKeydown">
    <nav class="view-tabs" :aria-label="w('documentWorkspace')"><button type="button" class="btn" :aria-pressed="view === 'source'" @click="chooseView('source')">{{ w('originalTemplate') }}</button><button type="button" class="btn" :aria-pressed="view === 'result'" @click="chooseView('result')">{{ w('savedDraftView') }}</button><button v-if="immersive" ref="inspectorToggle" type="button" class="btn inspector-toggle" :aria-expanded="inspectorOpen" @click="inspectorOpen = !inspectorOpen">{{ w(inspectorOpen ? 'hideEvidence' : 'showEvidence') }}</button></nav>
    <DraftingNativeLayoutStatus :layout="activeBundle?.nativeLayout ?? (view === 'result' ? document?.nativeLayout : undefined)" :locale="locale" />
    <p v-if="view === 'result' && dirty" class="hint" role="status">{{ w('pendingLocations') }}</p>
    <p v-if="shownError" class="hint" role="status">{{ w('bindingUnavailable') }} · {{ shownError }} <button type="button" class="btn" @click="retry">{{ w('retry') }}</button></p>
    <p v-if="view === 'source' && sourceLoading || view === 'result' && (loading || resultLoading)" class="hint" role="status">{{ w('bindingLoading') }}</p>
    <p v-if="activeBundle && !activeBundle.bindings.length" class="hint" role="status">{{ w(view === 'result' ? 'noRegisteredResultLocations' : 'noRegisteredSourceLocations') }}</p>
    <div class="reading-layout" :class="{ 'reading-layout--document-only': immersive && !inspectorOpen }">
      <div class="reading-document" :inert="immersive && inspectorOpen && inspectorOverlay || undefined">
        <div v-if="editing" v-show="view === 'result'" class="body-frame"><slot /></div>
        <DraftingPdfPreview v-if="view === 'source' ? sourceUrl : resultSource && !dirty && !editing" :key="view" :source="view === 'source' ? sourceUrl : resultSource" :title="`${fileKey} · ${w(view === 'source' ? 'originalTemplate' : 'savedDraftView')}`" :locale="locale" :immersive="immersive" :locations="markerLocations" :selected-binding-id="selectedBindingId" :location-request="locationRequest" @binding="markerSelected" @location-unavailable="pointUnavailable" @range-unavailable="rangeUnavailable" @range-available="rangeAvailable" />
        <p v-else-if="view === 'source' ? !sourceLoading && !sourceError : !loading && !dirty && !editing" class="hint">{{ w('pdfUnavailable') }} <button type="button" class="btn" @click="retry">{{ w('retry') }}</button></p>
      </div>
      <aside v-show="!immersive || inspectorOpen" ref="inspectorPanel" class="binding-inspector" data-binding-inspector tabindex="-1" :aria-label="w('showEvidence')">
        <button v-if="immersive" type="button" class="btn inspector-close" @click="closeInspector">{{ w('hideEvidence') }}</button>
        <label>{{ w('draftingField') }}<select v-model="selectedFieldKey" :aria-label="w('draftingField')"><option value="">{{ w('chooseBoundField') }}</option><option v-for="field in fields" :key="field.key" :value="field.key">{{ l(field) }}</option></select></label>
        <p class="hint">{{ w('pointLocationNote') }}</p>
        <p v-if="originalLocationAbsent" class="hint" role="status">{{ w('noOriginalTemplateParagraph') }}</p>
        <section v-if="selectedBinding" class="selected-target">
          <strong>{{ targetCaption(selectedBinding) }}</strong>
          <div class="target-fields" data-binding-fields><button v-for="field in targetFields" :key="field.key" type="button" class="btn" :aria-pressed="selectedFieldKey === field.key" @click="selectedFieldKey = field.key">{{ l(field) }}</button></div>
          <p class="hint">{{ w(locationWord(selectedBinding)) }} · {{ applicationLabel(selectedBinding) }}<span v-if="!usablePoint(selectedBinding)"> · {{ w(selectedBinding.geometryStatus === 'pending' ? 'pendingGeometry' : 'unavailableGeometry') }}</span></p>
          <p v-if="usablePoint(selectedBinding) && unavailableRanges.includes(selectedBinding.bindingId)" class="hint" role="status" data-text-range-unavailable>{{ w('pdfTextRangeUnavailable') }}</p>
          <section class="target-text"><template v-if="selectedBinding.sourceParagraphId"><strong>{{ w('templateTargetText') }}</strong><p lang="en">{{ selectedBinding.sourceText }}</p></template><p v-else class="hint">{{ w('noOriginalTemplateParagraph') }}</p><template v-if="view === 'result'"><strong>{{ w('savedTargetText') }}</strong><p lang="en">{{ selectedBinding.text }}</p></template></section>
        </section>
        <details v-if="savedChangeBindings.length" data-document-changes class="binding-targets"><summary>{{ w('savedDocumentChanges') }} · {{ savedChangeBindings.length }}</summary><button v-for="binding in savedChangeBindings" :key="binding.bindingId" type="button" class="target-location" :data-binding-change="binding.bindingId" :aria-pressed="binding.bindingId === selectedBindingId" @click="selectBinding(binding, 'saved-change')">{{ targetCaption(binding) }}<span class="target-caption">{{ targetText(binding) }}</span><span>{{ w(locationWord(binding)) }} · {{ applicationLabel(binding) }}</span></button></details>
        <template v-if="selectedField">
          <strong>{{ l(selectedField) }}</strong><p class="field-state" data-current-field-state>{{ draftWord((fieldStates[selectedField.key] ?? 'missing') as DraftWord, locale) }}<span v-if="dirtyKeys.includes(selectedField.key)"> · {{ w('unsaved') }}</span></p>
          <div data-current-field-value><strong>{{ previewWord('currentValue', locale) }}</strong><DraftingValueDisplay :field="selectedField" :value="values[selectedField.key]" :locale="locale" /></div>
          <button type="button" class="btn" :disabled="disabled || dirty" @click="emit('input', selectedField.key)">{{ previewWord('edit', locale) }}</button>
          <section class="binding-targets" :aria-label="w('boundTargets')">
            <strong>{{ w('boundTargets') }} · {{ relatedBindings.length }}</strong>
            <button v-for="binding in compactBindings" :key="binding.bindingId" type="button" class="target-location" :data-binding-target="binding.bindingId" :aria-pressed="binding.bindingId === selectedBindingId" @click="selectBinding(binding)">{{ targetCaption(binding) }}<span class="target-caption">{{ targetText(binding) }}</span><span>{{ w(locationWord(binding)) }} · {{ applicationLabel(binding) }}</span><span v-if="!usablePoint(binding)">{{ w(binding.geometryStatus === 'pending' ? 'pendingGeometry' : 'unavailableGeometry') }}</span></button>
            <details v-if="otherBindings.length" data-other-binding-targets class="binding-targets"><summary>{{ w('otherLocations') }} · {{ otherBindings.length }}</summary><button v-for="binding in otherBindings" :key="binding.bindingId" type="button" class="target-location" :data-binding-target="binding.bindingId" :aria-pressed="binding.bindingId === selectedBindingId" @click="selectBinding(binding)">{{ targetCaption(binding) }}<span class="target-caption">{{ targetText(binding) }}</span><span>{{ w(locationWord(binding)) }} · {{ applicationLabel(binding) }}</span><span v-if="!usablePoint(binding)">{{ w(binding.geometryStatus === 'pending' ? 'pendingGeometry' : 'unavailableGeometry') }}</span></button></details>
            <button v-for="file in otherFiles" :key="file" type="button" class="btn" :disabled="disabled || dirty" @click="emit('file', file)">{{ file }} · {{ w('boundTargets') }}</button>
          </section>
          <section class="evidence-panel" data-evidence-panel :aria-label="w('correspondenceQuotation')"><strong>{{ w('correspondenceQuotation') }}</strong><p v-if="!selectedVariable?.candidates?.some(candidate => candidate.sourceQuote)" class="hint">{{ w('noCorrespondenceQuotation') }}</p><article v-for="(candidate, index) in selectedVariable?.candidates ?? []" :key="index"><p class="hint">{{ candidate.fileName }}</p><DraftingValueDisplay :field="selectedField" :value="candidate.value" :locale="locale" /><blockquote v-if="candidate.sourceQuote">{{ candidate.sourceQuote }}</blockquote><template v-for="context in [evidenceContext(candidate)]" :key="'quotation-context'"><details v-if="context"><summary>{{ w('quotedSourceContext') }}</summary><p v-if="trace?.stale" class="hint">{{ w('historicalEvidenceContext') }}</p><DraftingExtractionContext v-if="context.context" :context="context.context" :locale="locale" dispatched /><template v-else><p class="hint">{{ w('recordedContextText') }}</p><pre>{{ context.sourceText }}</pre></template></details><p v-else-if="candidate.sourceQuote" class="hint">{{ w('sourceContextUnavailable') }}</p></template><p v-if="candidate.reason" class="hint"><strong>{{ w('modelExplanation') }}</strong> · {{ candidate.reason }}</p></article></section>
        </template>
        <details v-if="activeBundle" class="binding-versions"><summary>{{ w('documentVersions') }}</summary><p>Source SHA-256: {{ activeBundle.sourceSha256 }}</p><p v-if="activeBundle.revisionId">Revision: {{ activeBundle.revisionId }}</p><p v-if="activeBundle.docxSha256">DOCX SHA-256: {{ activeBundle.docxSha256 }}</p><p>PDF SHA-256: {{ activeBundle.pdfSha256 }}</p><p>Render profile: {{ activeBundle.renderProfileHash }}</p></details>
      </aside>
    </div>
  </section>
</template>

<style scoped>
.bound-document { min-width: 0; display: flex; flex-direction: column; gap: 12px; }
.bound-document--immersive { height: 100%; min-height: 0; }
.bound-document--immersive { gap: 6px; }
.view-tabs { display: flex; gap: 8px; flex: none; flex-wrap: wrap; }
.inspector-toggle { margin-left: auto; }
.view-tabs [aria-pressed="true"] { color: var(--accent-dark); background: var(--accent-soft); }
.body-frame { min-height: 0; flex: 1; overflow: hidden; }
.reading-layout { display: grid; grid-template-columns: minmax(0, 1fr) minmax(260px, 340px); gap: 16px; min-height: 0; }
.bound-document--immersive .reading-layout { flex: 1; overflow: hidden; }
.reading-layout--document-only { grid-template-columns: minmax(0, 1fr); }
.inspector-close { position: sticky; top: 0; margin-left: auto; display: block; }
.reading-document { min-width: 0; min-height: 0; display: flex; flex-direction: column; }
.binding-inspector { padding: 14px; overflow: auto; min-height: 0; border: 1px solid var(--line); border-radius: 8px; background: var(--surface); font-size: 13px; line-height: 1.55; }
.binding-inspector > * { margin-bottom: 14px; }
select { display: block; width: 100%; padding: 8px; margin: 6px 0; border: 1px solid var(--line); border-radius: 6px; font: inherit; background: var(--surface); color: var(--ink); }
.binding-targets { display: flex; flex-direction: column; gap: 8px; }
.target-location { border: 1px solid var(--line); padding: 9px; border-radius: 6px; background: var(--surface-subtle); color: var(--ink); text-align: left; font: inherit; cursor: pointer; }
.target-location[aria-pressed="true"] { border-color: var(--accent); background: var(--accent-soft); }
.target-location span { display: block; font-size: 11px; margin-top: 4px; }
.target-location .target-caption { display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 2; overflow: hidden; font-size: 13px; }
.target-fields { min-width: 0; display: flex; flex-wrap: wrap; gap: 6px; }
.target-fields .btn { min-width: 0; max-width: 100%; white-space: normal; overflow-wrap: anywhere; text-align: left; }
.selected-target { border: 1px solid var(--line); padding: 12px; border-radius: 6px; display: flex; flex-direction: column; gap: 8px; }
.evidence-panel article { padding: 12px 0; border-bottom: 1px solid var(--line); }
blockquote, .target-text p, pre { white-space: pre-wrap; overflow-wrap: anywhere; }
blockquote { border-left: 3px solid var(--accent); padding-left: 10px; margin: 10px 0; }
pre { max-height: 320px; overflow: auto; font: inherit; background: var(--surface-subtle); padding: 10px; }
.target-text { border-top: 1px solid var(--line); padding-top: 12px; }
.binding-versions p { font-size: 11px; overflow-wrap: anywhere; }
summary { cursor: pointer; color: var(--accent-dark); }
.hint { font-size: 12px; line-height: 1.55; margin: 0; }
@media (max-width: 1000px) {
  .reading-layout { grid-template-columns: minmax(0, 1fr); }
  .bound-document--immersive .reading-layout { position: relative; overflow: hidden; }
  .bound-document--immersive .binding-inspector { position: absolute; inset: 0 0 0 auto; z-index: 5; width: min(340px, 100%); box-sizing: border-box; box-shadow: var(--shadow); }
}
</style>
