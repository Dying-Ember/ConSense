<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { draftingApi } from '@/api'
import type { DraftField, DraftPlanAction, DraftVariable, TemplateReading } from '@/api/types'
import type { DraftValue } from '@/drafting/state'
import { isCollectionKind, isObjectKind } from '@/drafting/field-kinds'
import { draftWord, localized, type DraftWord } from '@/drafting/words'
import { previewWord, type PreviewWord } from '@/drafting/preview-words'
import type { AppLocale } from '@/i18n'
import DraftingPdfPreview from './DraftingPdfPreview.vue'
import DraftingValueDisplay from './DraftingValueDisplay.vue'
import { valueSummary } from '@/drafting/value-presentation'
import { locateTemplateTargets, type TemplateLocation } from '@/drafting/template-navigation'
import type { PreparedTemplateReading } from '@/drafting/template-reading'

const props = withDefaults(defineProps<{ projectId: string; fileKey: string; locale: AppLocale; fields: DraftField[]; values: Record<string, DraftValue>; variables: DraftVariable[]; actions: DraftPlanAction[]; selectedKey: string; selectedActionId: string; fieldStates: Record<string, string>; dirtyKeys: string[]; disabled?: boolean; sourceAvailable?: boolean }>(), { sourceAvailable: undefined })
const emit = defineEmits<{ select: [key: string]; edit: [key: string]; input: [key: string]; target: [actionId: string]; file: [fileKey: string]; locate: [actionId: string]; upload: [fileKey: string] }>()
const w = (key: PreviewWord) => previewWord(key, props.locale)
const l = (value: Parameters<typeof localized>[0]) => localized(value, props.locale)
const reading = ref<TemplateReading>()
const status = ref<'loading' | 'ready' | 'missing-source' | 'unsupported' | 'error'>('loading')
const error = ref('')
const prepared = ref<PreparedTemplateReading>()
const pdfUrl = ref('')
const surface = ref<HTMLElement>()
const locationId = ref('')
let generation = 0
let alive = true
const selected = computed(() => props.fields.find(field => field.key === props.selectedKey))
const variable = computed(() => props.variables.find(item => item.key === props.selectedKey))
const related = computed(() => props.actions.filter(action => [...(action.inputKeys ?? []), ...(action.fieldKeys ?? [])].includes(props.selectedKey)))
const files = computed(() => [...new Set([props.fileKey, ...props.fields.flatMap(field => (field.affects ?? []).map(target => target.document)), ...props.actions.map(action => action.document)])].filter(file => !!file))
const selectedInactive = computed(() => !!selected.value && props.fieldStates[selected.value.key] === 'inactive')
const locations = computed(() => reading.value?.format === 'docx' ? locateTemplateTargets(reading.value, props.fields, props.actions) : [])
const selectedLocations = computed(() => {
  const matching = locations.value.filter(item => props.selectedActionId ? item.actionId === props.selectedActionId : item.fieldKeys.includes(props.selectedKey))
  // Several actions can refer to the same native paragraph. Enumerate physical locations once.
  return matching.filter((item, index) => matching.findIndex(other => other.paragraphId === item.paragraphId && (item.paragraphId !== null || other.id === item.id)) === index)
})
const located = computed(() => selectedLocations.value.filter(item => item.paragraphId !== null))
const locationIndex = computed(() => Math.max(0, located.value.findIndex(item => item.id === locationId.value)))
const currentLocation = computed(() => located.value[locationIndex.value])
const sourceParagraph = computed(() => reading.value?.paragraphs.find(paragraph => paragraph.id === currentLocation.value?.paragraphId))
const unmapped = computed(() => !!currentLocation.value?.paragraphId && !prepared.value?.mappedParagraphIds.includes(currentLocation.value.paragraphId))
const actionStatus = (action: string) => draftWord((['retain', 'amend', 'insert', 'delete', 'not_used', 'pending', 'not_adopted'].includes(action) ? action : 'pending') as DraftWord, props.locale)
const structured = (field: DraftField) => isCollectionKind(field.kind) || isObjectKind(field.kind)
function requestEdit(field: DraftField) { if (props.disabled || props.fieldStates[field.key] === 'inactive') return; if (structured(field)) emit('input', field.key); else emit('edit', field.key) }
function release() { if (pdfUrl.value) URL.revokeObjectURL(pdfUrl.value); pdfUrl.value = ''; prepared.value = undefined }
function markerClick(event: MouseEvent) {
  const target = event.target as HTMLElement
  const edit = target.closest<HTMLElement>('[data-preview-edit],[data-preview-input]')
  if (edit) { const field = props.fields.find(field => field.key === (edit.dataset.previewEdit ?? edit.dataset.previewInput)); if (field) requestEdit(field); return }
  const field = target.closest<HTMLElement>('[data-preview-field]')?.dataset.previewField
  const action = target.closest<HTMLElement>('[data-preview-action]')?.dataset.previewAction
  if (field) emit('select', field)
  else if (action && !props.disabled) emit('target', action)
}
async function showLocation(location: TemplateLocation | undefined) {
  if (!location) return
  locationId.value = location.id
  await nextTick()
  const container = surface.value
  const paragraph = [...(container?.querySelectorAll<HTMLElement>('[data-native-paragraph]') ?? [])].find(node => node.dataset.nativeParagraph === location.paragraphId)
  if (container && paragraph) container.scrollTo?.({ top: Math.max(0, container.scrollTop + paragraph.getBoundingClientRect().top - container.getBoundingClientRect().top - container.clientTop), behavior: 'smooth' })
}
function showTarget(document: string, clause: string, actionId?: string) {
  const action = actionId ? props.actions.find(action => action.id === actionId) : related.value.find(action => action.document === document && action.clause === clause)
  if (action) { emit('locate', action.id); if (document === props.fileKey) void showLocation(locations.value.find(location => location.actionId === action.id && location.paragraphId)); return }
  if (document !== props.fileKey) { emit('file', document); return }
  void showLocation(located.value.find(location => location.clause === clause))
}
const markedHtml = computed(() => {
  if (!prepared.value) return ''
  const flow = document.createElement('div'); flow.innerHTML = prepared.value.html
  for (const node of flow.querySelectorAll<HTMLElement>('[data-native-paragraph]')) {
    const anchors = locations.value.filter(location => location.paragraphId === node.dataset.nativeParagraph)
    if (!anchors.length) continue
    if (currentLocation.value?.paragraphId === node.dataset.nativeParagraph) { node.classList.add('current-location'); node.setAttribute('aria-current', 'location') }
    const markers = document.createElement('span'); markers.className = 'source-markers'
    for (const key of new Set(anchors.flatMap(anchor => anchor.fieldKeys))) {
      const field = props.fields.find(field => field.key === key)
      if (!field) continue
      const button = document.createElement('button'); button.type = 'button'; button.dataset.previewField = key
      button.title = `${w('select')}: ${l(field.label)}`
      button.textContent = `${l(field.label)}: ${valueSummary(field, props.values[key], props.locale)} · ${draftWord((props.fieldStates[key] || 'missing') as DraftWord, props.locale)}${props.dirtyKeys.includes(key) ? ` · ${w('unsaved')}` : ''}`
      markers.append(button)
      const edit = document.createElement('button'); edit.type = 'button'
      if (structured(field)) edit.dataset.previewInput = key; else edit.dataset.previewEdit = key
      edit.disabled = !!props.disabled || props.fieldStates[key] === 'inactive'
      edit.textContent = '✎'; edit.title = `${w(structured(field) ? 'input' : 'edit')}: ${l(field.label)}`; edit.setAttribute('aria-label', edit.title)
      markers.append(edit)
    }
    for (const id of new Set(anchors.map(anchor => anchor.actionId).filter((id): id is string => !!id))) {
      const action = props.actions.find(action => action.id === id)
      if (!action?.overrideable) continue
      const button = document.createElement('button'); button.type = 'button'; button.dataset.previewAction = id; button.disabled = !!props.disabled
      button.textContent = `${w('editTarget')}: ${action.clause}`; markers.append(button)
    }
    node.append(markers)
  }
  return flow.innerHTML
})
async function open() {
  const token = ++generation
  release(); locationId.value = ''
  reading.value = undefined; status.value = 'loading'; error.value = ''
  if (props.sourceAvailable === false) { status.value = 'missing-source'; return }
  try {
    const result = await (draftingApi as typeof draftingApi & { templateReading(projectId: string, fileKey: string): Promise<TemplateReading> }).templateReading(props.projectId, props.fileKey)
    if (!alive || token !== generation) return
    if (result.format === 'unsupported') { reading.value = result; status.value = 'unsupported'; return }
    const source = await (draftingApi as typeof draftingApi & { templateSource(projectId: string, fileKey: string): Promise<Blob> }).templateSource(props.projectId, props.fileKey)
    if (!alive || token !== generation) return
    const bytes = await source.arrayBuffer()
    const digest = [...new Uint8Array(await crypto.subtle.digest('SHA-256', bytes))].map(byte => byte.toString(16).padStart(2, '0')).join('')
    if (!alive || token !== generation) return
    if (digest !== result.sourceHash.toLowerCase()) throw new Error(w('changed'))
    if (result.format === 'pdf') pdfUrl.value = URL.createObjectURL(source)
    else {
      const [{ default: mammoth }, { prepareTemplateReading }] = await Promise.all([import('mammoth/mammoth.browser.min.js'), import('@/drafting/template-reading')])
      const converted = await mammoth.convertToHtml({ arrayBuffer: bytes }, { externalFileAccess: false, includeEmbeddedStyleMap: false, convertImage: mammoth.images.imgElement(async () => ({ src: '' })) })
      if (!alive || token !== generation) return
      const flow = prepareTemplateReading(converted.value, result)
      if (!flow.html.trim()) throw new Error(w('empty'))
      prepared.value = flow
    }
    if (!alive || token !== generation) return
    reading.value = result; status.value = 'ready'
    await showLocation(located.value[0])
  } catch (caught) {
    if (!alive || token !== generation) return
    const message = caught && typeof caught === 'object' && 'message' in caught ? String(caught.message) : String(caught)
    if (/^SOURCE_NOT_UPLOADED(?::|$)/.test(message)) status.value = 'missing-source'
    else { status.value = 'error'; error.value = message }
  }
}
watch(() => [props.projectId, props.fileKey, props.locale, props.sourceAvailable], () => { void open() }, { immediate: true })
watch(() => [props.selectedKey, props.selectedActionId], () => { locationId.value = ''; void showLocation(located.value[0]) })
onBeforeUnmount(() => { alive = false; generation++; release() })
</script>

<template>
  <section class="template-reading" :aria-label="w('title')">
    <header><h3>{{ w('title') }} — {{ fileKey }}</h3><p>{{ w('layoutNote') }}</p><p v-if="reading">{{ reading.fileName }}</p></header>
    <nav class="template-files" :aria-label="w('title')"><button v-for="file in files" :key="file" type="button" class="btn" :data-preview-file="file" :aria-current="file === fileKey ? 'page' : undefined" @click="emit('file', file)">{{ file }}</button></nav>
    <p v-if="status === 'loading'" role="status">{{ w('loading') }}</p>
    <div v-else-if="status === 'missing-source'" class="source-notice" role="status"><p>{{ w('notUploaded').replace('{fileKey}', fileKey) }}</p><p>{{ w('uploadNote') }}</p><button type="button" class="btn" :disabled="disabled" @click="emit('upload', fileKey)">{{ w('upload') }}</button></div>
    <p v-else-if="status === 'unsupported'" role="alert">{{ w('unsupported') }}</p>
    <div v-else-if="status === 'error'" role="alert"><p>{{ w('failed') }}</p><p>{{ error }}</p><button type="button" class="btn" @click="open">{{ w('retry') }}</button></div>
    <aside v-if="selected" class="selected-field" :data-preview-card="selected.key">
      <h4>{{ w('selected') }}: {{ l(selected.label) }}</h4>
      <p><strong>{{ w('currentValue') }}:</strong> <span v-if="Array.isArray(values[selected.key])">{{ valueSummary(selected, values[selected.key], locale) }}</span></p><DraftingValueDisplay :field="selected" :value="values[selected.key]" :locale="locale" />
      <p class="badges"><span>{{ draftWord((fieldStates[selected.key] || 'missing') as DraftWord, locale) }}</span><span v-if="dirtyKeys.includes(selected.key)">{{ w('unsaved') }}</span></p>
      <button v-if="!structured(selected)" type="button" class="btn" :data-preview-edit="selected.key" :disabled="disabled || selectedInactive" @click="requestEdit(selected)">{{ w('edit') }}</button>
      <button v-else type="button" class="btn" :data-preview-input="selected.key" :disabled="disabled || selectedInactive" @click="requestEdit(selected)">{{ w('input') }}</button>
      <p v-if="variable?.source || variable?.confirmedFrom"><strong>{{ w('source') }}:</strong> {{ variable.source || variable.confirmedFrom }}</p>
      <details v-if="variable?.candidates?.length"><summary>{{ w('candidates') }}</summary><article v-for="(candidate, index) in variable.candidates" :key="index"><DraftingValueDisplay :field="selected" :value="candidate.value" :locale="locale" /><p>{{ candidate.fileName }}</p><blockquote v-if="candidate.sourceQuote">{{ candidate.sourceQuote }}</blockquote><p v-if="candidate.reason">{{ candidate.reason }}</p></article></details>
      <details v-if="selected.affects?.length || related.length"><summary>{{ w('affected') }}</summary><ul><li v-for="(target, index) in selected.affects" :key="`field-${index}`"><button type="button" class="link-button" @click="showTarget(target.document, target.clause)">{{ target.document }} · {{ target.clause }} <span v-if="target.paragraphs">({{ target.paragraphs }})</span></button></li><li v-for="action in related" :key="action.id">{{ action.document }} · {{ action.clause }} · {{ actionStatus(action.action) }} <button type="button" class="link-button" :data-preview-locate="action.id" @click="showTarget(action.document, action.clause, action.id)">{{ w('locate') }}</button> <button v-if="action.overrideable" type="button" class="btn" :disabled="disabled" :data-preview-target="action.id" @click="emit('target', action.id)">{{ w('editTarget') }}</button><p v-if="action.detail">{{ l(action.detail) }}</p><p v-if="action.sourceWarning" class="source-warning">{{ l(action.sourceWarning) }}</p><pre v-if="action.sourceText" lang="en">{{ action.sourceText }}</pre></li></ul></details>
    </aside>
    <p v-else>{{ w('choose') }}</p>
    <template v-if="status === 'ready' && reading?.format === 'docx'">
      <p v-if="!reading.catalogueSourceVerified" class="source-warning">{{ w('edition') }}</p>
      <nav v-if="located.length" data-preview-navigation :aria-label="w('locations')">
        <button type="button" class="btn" data-preview-previous :disabled="locationIndex <= 0" @click="showLocation(located[locationIndex - 1])">{{ w('previous') }}</button>
        <span aria-live="polite">{{ w('location') }} {{ locationIndex + 1 }} {{ w('of') }} {{ located.length }}</span>
        <button type="button" class="btn" data-preview-next :disabled="locationIndex >= located.length - 1" @click="showLocation(located[locationIndex + 1])">{{ w('next') }}</button>
        <ol><li v-for="(location, index) in located" :key="location.id"><button type="button" class="link-button" :data-preview-location="location.id" :aria-current="index === locationIndex ? 'location' : undefined" @click="showLocation(location)">{{ location.document }} · {{ location.clause }} · {{ w('paragraph') }} {{ location.ordinal }} · {{ w(location.quality) }}</button></li></ol>
      </nav>
      <p v-if="selectedLocations.some(location => location.quality === 'missing')" role="status">{{ w('missing') }}: {{ selectedLocations.filter(location => location.quality === 'missing').map(location => location.clause).join('; ') }}</p>
      <p v-else-if="!selectedKey && !selectedActionId">{{ w('choose') }}</p>
      <div v-if="unmapped" class="native-source"><p>{{ w('unmapped') }}</p><pre lang="en">{{ sourceParagraph?.text }}</pre><button v-for="key in currentLocation?.fieldKeys" :key="key" type="button" class="btn" :data-preview-field="key" @click="emit('select', key)">{{ l(fields.find(field => field.key === key)?.label) }}</button></div>
      <div ref="surface" class="reading-surface" :aria-label="reading.fileName" lang="en" @click="markerClick" v-html="markedHtml" />
    </template>
    <template v-else-if="status === 'ready' && reading?.format === 'pdf'"><p role="status">{{ w('pdfLimit') }}</p><DraftingPdfPreview v-if="pdfUrl" :source="pdfUrl" :title="reading.fileName" :locale="locale" /></template>
  </section>
</template>

<style scoped>
.template-reading { min-width: 0; border: 1px solid var(--line); border-radius: var(--radius); background: var(--surface); padding: 16px; }
.template-reading header h3 { margin: 0 0 8px; font-size: 16px; }
.template-reading p, .template-reading li { font-size: 13px; line-height: 1.55; overflow-wrap: anywhere; }
.template-reading header p { color: var(--muted); }
.selected-field { border-top: 1px solid var(--line); margin-top: 16px; padding-top: 12px; }
.selected-field h4, .selected-field h5 { margin: 12px 0; }
.selected-field h4 { font-size: 14px; }
.selected-field h5 { font-size: 12px; }
.badges { display: flex; gap: 8px; flex-wrap: wrap; }
.badges span { border: 1px solid var(--line); background: var(--surface-subtle); color: var(--muted); border-radius: 999px; padding: 3px 9px; font-size: 11.5px; font-weight: 700; }
.selected-field article { padding: 12px 14px; border: 1px solid var(--line); border-radius: var(--radius); background: var(--surface-subtle); margin: 8px 0; }
.selected-field blockquote { margin: 8px 0; white-space: pre-wrap; }
.selected-field pre { white-space: pre-wrap; overflow-wrap: anywhere; font-family: inherit; font-size: 12px; max-height: 160px; overflow: auto; }
.link-button { border: 0; background: transparent; color: var(--accent-dark); padding: 0; cursor: pointer; text-align: left; font: inherit; }
button:disabled { opacity: .5; cursor: not-allowed; }
nav { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.template-files .btn { font-size: 12.5px; font-weight: 700; color: var(--ink-soft); }
.template-files .btn[aria-current="page"] { background: var(--accent-soft); border-color: var(--accent); color: var(--accent-dark); }
nav ol { width: 100%; max-height: 130px; overflow: auto; padding-left: 24px; }
.reading-surface { max-height: 65vh; overflow: auto; background: var(--surface); color: var(--ink); padding: 16px; border: 1px solid var(--line); border-radius: var(--radius-sm); font-family: Georgia, 'Times New Roman', serif; font-size: 14px; line-height: 1.65; }
.reading-surface :deep(table) { border-collapse: collapse; width: 100%; }
.reading-surface :deep(td), .reading-surface :deep(th) { border: 1px solid var(--line); padding: 7px; vertical-align: top; }
.reading-surface :deep(.current-location) { background: var(--amber-soft); outline: 2px solid var(--amber); outline-offset: 3px; }
.reading-surface :deep(.source-markers) { display: flex; gap: 6px; flex-wrap: wrap; padding: 6px 0; font-family: var(--font-ui); }
.reading-surface :deep(.source-markers button) { font-family: var(--font-ui); font-size: 11.5px; border: 1px solid var(--line); background: var(--blue-soft); color: var(--blue); padding: 5px 7px; border-radius: var(--radius-sm); cursor: pointer; text-align: left; }
.reading-surface :deep(.source-markers button:focus-visible) { outline: none; box-shadow: var(--focus); }
.reading-surface :deep(.source-markers button:disabled) { opacity: .5; cursor: not-allowed; }
.source-warning, .native-source { background: var(--amber-soft); color: var(--amber); padding: 10px; border-radius: var(--radius-sm); }
.native-source pre { white-space: pre-wrap; overflow-wrap: anywhere; }
</style>
