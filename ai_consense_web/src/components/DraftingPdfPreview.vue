<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, shallowRef, useId, watch } from 'vue'
import { getDocument, GlobalWorkerOptions, renderTextLayer, type PDFDocumentProxy } from 'pdfjs-dist'
import type { TextContent } from 'pdfjs-dist/types/src/display/api'
import pdfWorkerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?url'
import { createPdfPreview, type PdfPreviewState } from '@/drafting/pdf-preview'
import { draftWord, type DraftWord } from '@/drafting/words'
import type { AppLocale } from '@/i18n'
import type { PdfBindingLocation } from '@/drafting/document-bindings'
import { findPdfTextMatches, selectPdfTextRange, type PdfTextMatch, type PdfTextRectangle } from '@/drafting/pdf-text-ranges'
import { wordsInBox, type SourcePageWord, type SourcePageSelection } from '@/drafting/source-page-selection'

const props = defineProps<{ source: string; title: string; locale: AppLocale; initialPage?: number; immersive?: boolean; locations?: PdfBindingLocation[]; selectedBindingId?: string; locationRequest?: number; selectWords?: boolean; focusText?: string; focusPage?: number; focusRequest?: number }>()
const emit = defineEmits<{ loaded: [pages: number]; binding: [id: string]; locationUnavailable: [id: string]; rangeUnavailable: [id: string]; rangeAvailable: [id: string]; selection: [value: SourcePageSelection]; focusUnavailable: [reason: 'not_found' | 'unreadable'] }>()
const w = (key: DraftWord) => draftWord(key, props.locale)
const canvas = ref<HTMLCanvasElement>()
const surface = ref<HTMLElement>()
const paper = ref<HTMLElement>()
const textLayerHost = ref<HTMLElement>()
const selectionMenuHost=ref<HTMLElement>()
type PdfTextLayer = { textDivs: HTMLElement[]; textContentItemsStr: string[]; cancel(): void }
const renderedText = shallowRef<{ layer: PdfTextLayer; page: number; scale: number }>()
const textRanges = ref<Record<string, PdfTextRectangle[]>>({})
const pageWords = shallowRef<SourcePageWord[]>([]), pickedWords = ref<number[]>([])
let wordSelection: SourcePageSelection | undefined
const dragStart = ref<{ x: number; y: number; word?: number }>(), dragBox = ref<PdfTextRectangle>()
const selectionMenu = computed(() => {
  const last = pageWords.value[pickedWords.value[pickedWords.value.length - 1] ?? -1]?.rectangle
  return last ? { left: `${Math.max(0,Math.min(last.x*renderedScale.value,paperSize.value.width-320))}px`, top: `${(last.y+last.height)*renderedScale.value+8}px` } : {}
})
function publishSelection(indices: number[]) {
  pickedWords.value = [...new Set(indices)].sort((a,b)=>a-b)
  wordSelection = {source:props.source,page:state.page,words:pickedWords.value.map(index=>pageWords.value[index]!)}
  emit('selection',wordSelection)
  void nextTick().then(()=>{if(pickedWords.value.length)selectionMenuHost.value?.scrollIntoView?.({block:'nearest',inline:'nearest'})})
}
function clearSelection() {
  dragStart.value=undefined;dragBox.value=undefined;publishSelection([])
}
defineExpose({ clearSelection })
function pointerPosition(event: PointerEvent) {
  const box=paper.value!.getBoundingClientRect();return {x:(event.clientX-box.left)/renderedScale.value,y:(event.clientY-box.top)/renderedScale.value}
}
function startWordSelection(event: PointerEvent) {
  if(!props.selectWords||event.button!==0||!pageWords.value.length||(event.target as Element).closest('button,[data-source-selection-menu]'))return
  event.preventDefault();const point=pointerPosition(event),raw=(event.target as HTMLElement).dataset.sourceWord
  dragStart.value={...point,word:raw===undefined?undefined:Number(raw)};dragBox.value=undefined;paper.value?.setPointerCapture?.(event.pointerId)
}
function moveWordSelection(event: PointerEvent) {
  if(!dragStart.value)return;const point=pointerPosition(event),start=dragStart.value
  dragBox.value={x:Math.min(point.x,start.x),y:Math.min(point.y,start.y),width:Math.abs(point.x-start.x),height:Math.abs(point.y-start.y)}
}
function finishWordSelection(event: PointerEvent) {
  if(!dragStart.value)return
  const start=dragStart.value,box=dragBox.value;dragStart.value=undefined;dragBox.value=undefined
  paper.value?.releasePointerCapture?.(event.pointerId)
  if(box&&box.width>3&&box.height>3)publishSelection(wordsInBox(pageWords.value,box))
  else if(start.word!==undefined)publishSelection(pickedWords.value.includes(start.word)?pickedWords.value.filter(id=>id!==start.word):[...pickedWords.value,start.word])
  else publishSelection([])
}
let textContentCache = new Map<number, Promise<TextContent>>()
function pageText(document: PDFDocumentProxy,pageNumber:number){
  let content=textContentCache.get(pageNumber)
  if(!content){content=document.getPage(pageNumber).then(page=>page.getTextContent());textContentCache.set(pageNumber,content);if(textContentCache.size>8)textContentCache.delete(textContentCache.keys().next().value!)}
  return content
}
let activeTextLayer: PdfTextLayer | undefined
let rangeVersion = 0
const state = reactive<PdfPreviewState>({ status: 'idle', page: 1, pages: 0, error: '' })
const requestedPage = ref<number | string>(1)
const pageInputId = useId()
const zoom = ref<number>()
let normalZoom: number | undefined
const renderedScale = ref(1)
const paperSize = ref({ width: 0, height: 0, pageWidth: 0, pageHeight: 0, page: 0, rotation: 0 })
const pageLocations = computed(() => state.status === 'ready' ? (props.locations ?? []).filter(location => {
  const point = location.geometry, paper = paperSize.value
  return point.pageNumber === state.page && paper.page === state.page && point.rotation === paper.rotation && Math.abs(point.pageWidth - paper.pageWidth) < 1 && Math.abs(point.pageHeight - paper.pageHeight) < 1
}) : [])
const paintedLocations = computed(() => pageLocations.value.filter(location => location.highlight !== false))
const pageHighlights = computed(() => paintedLocations.value.flatMap(location => (textRanges.value[location.bindingId] ?? []).map((rectangle, index) => ({ location, rectangle, index }))))
const pageAnchors = computed(() => paintedLocations.value.filter(location => !textRanges.value[location.bindingId]?.length))
let locatedRequest = ''
const minimumZoom = .25, maximumZoom = 3
let resizeObserver: ResizeObserver | undefined
let resizeFrame: number | undefined
let surfaceWidth = 0
let viewVersion = 0, disposed = false
let loadedPdf: PDFDocumentProxy | undefined, focusVersion=0,loadVersion=0
const focusRectangles = ref<PdfTextRectangle[]>([]), searching = ref(false)
const focusMatches = ref<{ page: number; count: number }[]>([])
const focusMatchCount = computed(() => focusMatches.value.reduce((total, match) => total + match.count, 0))
async function focusPassage(){
  const pdf=loadedPdf,quote=props.focusText,source=props.source,version=++focusVersion
  focusRectangles.value=[];focusMatches.value=[]
  if(!pdf||!quote){searching.value=false;return}
  searching.value=true
  try{
    const found: { page: number; count: number }[] = []
    if(props.focusPage!==undefined&&(!Number.isInteger(props.focusPage)||props.focusPage<1||props.focusPage>pdf.numPages)){emit('focusUnavailable','not_found');return}
    const first=props.focusPage??1,last=props.focusPage??pdf.numPages
    for(let page=first;page<=last;page++){
      const content=await pageText(pdf,page)
      if(disposed||version!==focusVersion||source!==props.source)return
      const matches=findPdfTextMatches(content.items.flatMap(item=>'str' in item?[item.str]:[]),quote)
      if(matches.length)found.push({page,count:matches.length})
    }
    if(!found.length){emit('focusUnavailable','not_found');return}
    focusMatches.value=found
    await navigate(found.some(match=>match.page===state.page)?state.page:found[0]!.page)
  }catch{if(!disposed&&version===focusVersion&&source===props.source)emit('focusUnavailable','unreadable')}
  finally{if(version===focusVersion)searching.value=false}
}
watch(()=>[props.focusText,props.focusPage,props.focusRequest],()=>{void focusPassage()})
GlobalWorkerOptions.workerSrc = pdfWorkerUrl

const preview = createPdfPreview<PDFDocumentProxy>({
  load: source => {
    const task=getDocument({url:source});const version=++loadVersion;focusVersion++;loadedPdf=undefined
    void task.promise.then(pdf=>{if(!disposed&&version===loadVersion){loadedPdf=pdf;emit('loaded',pdf.numPages);if(props.focusText)void focusPassage()}}).catch(()=>{})
    return task
  },
  async render(document, pageNumber, current) {
    const page = await document.getPage(pageNumber)
    await nextTick()
    if (!current()) return
    const target = canvas.value
    const context = target?.getContext('2d')
    if (!target || !context) throw new Error('Canvas rendering is unavailable.')
    const base = page.getViewport({ scale: 1 })
    const width = Math.max(160, (surface.value?.clientWidth || 800) - 48)
    const scale = zoom.value ?? width / base.width
    const viewport = page.getViewport({ scale })
    const pixelRatio = Math.min(window.devicePixelRatio || 1, 2)
    target.width = Math.ceil(viewport.width * pixelRatio); target.height = Math.ceil(viewport.height * pixelRatio)
    target.style.width = `${viewport.width}px`; target.style.height = `${viewport.height}px`
    paperSize.value = { width: viewport.width, height: viewport.height, pageWidth: base.width, pageHeight: base.height, page: pageNumber, rotation: page.rotate ?? 0 }
    renderedScale.value = scale
    renderedText.value = undefined; textRanges.value = {}; pageWords.value=[]; pickedWords.value=[];dragStart.value=undefined;dragBox.value=undefined; rangeVersion++
    if (wordSelection?.source !== props.source || wordSelection?.page !== pageNumber) {
      wordSelection = undefined
      if(props.selectWords)emit('selection',{source:props.source,page:pageNumber,words:[]})
    }
    activeTextLayer?.cancel(); activeTextLayer = undefined
    const host = textLayerHost.value
    host?.replaceChildren()
    const rendering = page.render({ canvasContext: context, viewport, transform: pixelRatio === 1 ? undefined : [pixelRatio, 0, 0, pixelRatio, 0, 0], background: '#ffffff' })
    let cancelled = false, layer: PdfTextLayer | undefined
    async function prepareTextLayer() {
      if (!host || typeof renderTextLayer !== 'function' || typeof page.getTextContent !== 'function') return
      const cache = textContentCache
      let content = cache.get(pageNumber)
      if (!content) {
        content = page.getTextContent()
        cache.set(pageNumber, content)
        if (cache.size > 8) cache.delete(cache.keys().next().value!)
      }
      let text: TextContent
      try { text = await content } catch { if (cache.get(pageNumber) === content) cache.delete(pageNumber); return }
      if (cancelled || !current()) return
      try {
        const textDivs: HTMLElement[] = [], textContentItemsStr: string[] = []
        const task = renderTextLayer({ textContentSource: text, container: host, viewport, textDivs, textContentItemsStr })
        layer = { textDivs, textContentItemsStr, cancel: () => task.cancel() }
        activeTextLayer = layer
        await task.promise
      } catch { return }
      if (!cancelled && current()) renderedText.value = { layer, page: pageNumber, scale }
    }
    // Text is an optional display layer: a slow or failed extraction must not
    // hold up the already rendered PDF. Late valid measurements publish ranges.
    void prepareTextLayer().catch(() => {})
    return {
      promise: rendering.promise,
      cancel() { cancelled = true; rendering.cancel(); layer?.cancel() }
    }
  }
}, state)

function measuredRectangles(match: PdfTextMatch, layer: PdfTextLayer, scale: number): PdfTextRectangle[] {
  const box = paper.value?.getBoundingClientRect()
  if (!box || !scale) return []
  const rectangles: PdfTextRectangle[] = []
  for (const span of match.spans) {
    const element = layer.textDivs[span.itemIndex], text = element?.firstChild
    if (!text || text.nodeType !== 3 || (text.textContent?.length ?? 0) < span.end) return []
    const range = element.ownerDocument.createRange()
    range.setStart(text, span.start); range.setEnd(text, span.end)
    if (typeof range.getClientRects !== 'function') return []
    const measured = Array.from(range.getClientRects()).filter(rectangle => rectangle.width > 0 && rectangle.height > 0)
    if (!measured.length) return []
    for (const rectangle of measured) rectangles.push({ x: (rectangle.left - box.left) / scale, y: (rectangle.top - box.top) / scale, width: rectangle.width / scale, height: rectangle.height / scale })
  }
  return rectangles
}

async function updateTextRanges() {
  const version = ++rangeVersion, text = renderedText.value, source = props.source
  await nextTick()
  if (disposed || version !== rangeVersion || source !== props.source || state.status !== 'ready' || text !== renderedText.value) return
  const ranges: Record<string, PdfTextRectangle[]> = {}
  const words: SourcePageWord[] = []
  if (text?.page === state.page) {
    if(props.selectWords)text.layer.textContentItemsStr.forEach((context,itemIndex)=>{
      for(const match of context.matchAll(/\S+/gu)){
        const start=match.index!,end=start+match[0].length
        const rectangles=measuredRectangles({spans:[{itemIndex,start,end}]},text.layer,text.scale)
        if(rectangles.length===1)words.push({itemIndex,start,end,text:match[0],context,rectangle:rectangles[0]!})
      }
    })
    for (const location of paintedLocations.value) {
      if (!location.text?.trim()) continue
      const matches = findPdfTextMatches(text.layer.textContentItemsStr, location.text)
      const candidates = matches.map(match => ({ match, rectangles: measuredRectangles(match, text.layer, text.scale) }))
      const selected = selectPdfTextRange(candidates, location.geometry, { width: location.geometry.pageWidth, height: location.geometry.pageHeight })
      if (selected) ranges[location.bindingId] = selected.rectangles
    }
  }
  textRanges.value = ranges
  pageWords.value=words
  if (wordSelection?.words.length && text?.page === state.page) {
    const indices = wordSelection.words.map(saved => words.findIndex(word => word.itemIndex === saved.itemIndex && word.start === saved.start && word.end === saved.end && word.context === saved.context))
    // Layout changes preserve exact word identities, never old screen coordinates.
    publishSelection(indices.every(index => index >= 0) ? indices : [])
  }
  focusRectangles.value=text?.page===state.page&&props.focusText?findPdfTextMatches(text.layer.textContentItemsStr,props.focusText).flatMap(match=>measuredRectangles(match,text.layer,text.scale)):[]
  for (const bindingId of Object.keys(ranges)) emit('rangeAvailable', bindingId)
}

watch(() => [props.locations, state.status, renderedText.value, props.selectWords], () => {
  if (state.status === 'ready') void updateTextRanges()
  else { rangeVersion++; textRanges.value = {} }
}, { flush: 'post' })

async function navigate(requested: number) {
  if (!Number.isFinite(requested) || !state.pages) { requestedPage.value = state.page; return }
  const version = ++viewVersion
  const rendering = preview.showPage(requested)
  const page = state.page
  await rendering
  if (version === viewVersion && state.status === 'ready' && state.page === page) surface.value?.scrollTo({ top: 0, left: 0 })
}

function jumpToPage() { void navigate(Number(requestedPage.value)) }

async function refresh() {
  if (!state.pages) return
  const version = ++viewVersion
  const reader = surface.value
  const previousScale = renderedScale.value
  const top = reader?.scrollTop ?? 0, left = reader?.scrollLeft ?? 0
  const rendering = preview.showPage(state.page)
  const page = state.page
  await rendering
  if (version !== viewVersion || state.status !== 'ready' || state.page !== page || !reader) return
  const ratio = renderedScale.value / previousScale
  reader.scrollTo({
    top: top > 0 ? Math.max(0, (top + reader.clientHeight / 2) * ratio - reader.clientHeight / 2) : 0,
    left: left > 0 ? Math.max(0, (left + reader.clientWidth / 2) * ratio - reader.clientWidth / 2) : 0
  })
}

function changeZoom(delta: number) {
  zoom.value = Math.max(minimumZoom, Math.min(maximumZoom, Math.round(((zoom.value ?? renderedScale.value) + delta) * 100) / 100))
  void refresh()
}

function fitWidth() { zoom.value = undefined; void refresh() }

function refreshWidth() {
  if (disposed) return
  if (resizeFrame !== undefined) window.cancelAnimationFrame(resizeFrame)
  resizeFrame = window.requestAnimationFrame(() => {
    resizeFrame = undefined
    const width = surface.value?.clientWidth ?? 0
    if (width <= 0 || Math.abs(width - surfaceWidth) < 1) return
    surfaceWidth = width
    if (zoom.value === undefined) void refresh()
  })
}

function retry() { void (state.pages ? preview.showPage(state.page) : preview.open(props.source, props.initialPage)) }
async function locateSelected(request: string, page: number) {
  if (page !== state.page) await navigate(page)
  await nextTick()
  await updateTextRanges()
  const location = pageLocations.value.find(item => item.bindingId === props.selectedBindingId), reader = surface.value
  if (disposed || request !== locatedRequest || state.status !== 'ready' || state.page !== page || !reader) return
  if (!location) { if (props.selectedBindingId) emit('locationUnavailable', props.selectedBindingId); return }
  const rectangle = textRanges.value[location.bindingId]?.[0]
  if (!rectangle && location.highlight !== false) emit('rangeUnavailable', location.bindingId)
  reader.scrollTo({ top: Math.max(0, (rectangle?.y ?? location.geometry.y) * renderedScale.value - reader.clientHeight / 2), left: Math.max(0, (rectangle?.x ?? location.geometry.x) * renderedScale.value - reader.clientWidth / 2) })
}
watch(() => state.page, page => { requestedPage.value = page })
watch(() => [props.selectedBindingId, props.locations, props.locationRequest, state.status], () => {
  if (state.status !== 'ready') return
  const location = props.locations?.find(item => item.bindingId === props.selectedBindingId)
  if (!location) return
  if (location.geometry.pageNumber > state.pages) { emit('locationUnavailable', location.bindingId); return }
  const request = `${props.source}:${location.bindingId}:${props.locationRequest ?? 0}`
  if (request === locatedRequest) return
  locatedRequest = request
  void locateSelected(request, location.geometry.pageNumber)
})
watch(() => props.source, source => {
  viewVersion++
  rangeVersion++; activeTextLayer?.cancel(); activeTextLayer = undefined
  renderedText.value = undefined; textRanges.value = {}; textContentCache = new Map()
  locatedRequest = ''
  focusMatches.value=[];focusRectangles.value=[];wordSelection=undefined
  zoom.value = undefined; requestedPage.value = 1
  normalZoom = undefined
  surface.value?.scrollTo({ top: 0, left: 0 })
  void preview.open(source, props.initialPage)
}, { immediate: true })
watch(() => props.initialPage, page => { if (page !== undefined) void preview.showPage(page) })
watch(() => props.immersive, async expanded => {
  if (expanded) { normalZoom = zoom.value; zoom.value = undefined }
  else zoom.value = normalZoom
  await nextTick()
  surfaceWidth = surface.value?.clientWidth ?? 0
  await refresh()
})
onMounted(() => {
  surfaceWidth = surface.value?.clientWidth ?? 0
  if (typeof ResizeObserver !== 'undefined' && surface.value) {
    resizeObserver = new ResizeObserver(refreshWidth)
    resizeObserver.observe(surface.value)
  }
  window.addEventListener('resize', refreshWidth)
})
onBeforeUnmount(() => {
  disposed = true; viewVersion++
  rangeVersion++; activeTextLayer?.cancel(); textContentCache.clear()
  resizeObserver?.disconnect()
  if (resizeFrame !== undefined) window.cancelAnimationFrame(resizeFrame)
  window.removeEventListener('resize', refreshWidth)
  preview.close()
})
</script>

<template>
  <section class="pdf-pages" :class="{ 'pdf-pages--immersive': immersive }" :aria-label="title">
    <nav v-if="state.pages" class="pdf-toolbar" :aria-label="w('pdfPages')">
      <div class="pdf-page-controls">
        <button type="button" class="btn" :disabled="state.page <= 1" @click="navigate(state.page - 1)">{{ w('pdfPreviousPage') }}</button>
        <form class="pdf-page-jump" @submit.prevent="jumpToPage">
          <label :for="pageInputId">{{ w('pdfPage') }}</label>
          <input :id="pageInputId" v-model="requestedPage" type="number" min="1" :max="state.pages" step="1" required :aria-label="w('pdfJumpPage')" class="pdf-page-number">
          <span aria-live="polite">{{ w('pdfPageOf') }} {{ state.pages }}</span>
          <button type="submit" class="btn">{{ w('pdfJumpPage') }}</button>
        </form>
        <button type="button" class="btn" :disabled="state.page >= state.pages" @click="navigate(state.page + 1)">{{ w('pdfNextPage') }}</button>
      </div>
      <div class="pdf-zoom-controls" :aria-label="w('pdfZoom')" role="group">
        <button type="button" class="btn" :disabled="(zoom ?? renderedScale) <= minimumZoom" @click="changeZoom(-.25)">{{ w('pdfZoomOut') }}</button>
        <output class="pdf-zoom-value" :aria-label="w('pdfZoom')">{{ Math.round(renderedScale * 100) }}%</output>
        <button type="button" class="btn" :disabled="(zoom ?? renderedScale) >= maximumZoom" @click="changeZoom(.25)">{{ w('pdfZoomIn') }}</button>
        <button type="button" class="btn pdf-fit-width" :aria-pressed="zoom === undefined" @click="fitWidth">{{ w('pdfFitWidth') }}</button>
      </div>
    </nav>
    <div v-if="state.status === 'error'" class="pdf-error" role="alert"><p>{{ w('pdfRenderFailed') }}</p><p class="error-detail">{{ state.error }}</p><button type="button" class="btn" @click="retry">{{ w('retry') }}</button></div>
    <p v-if="searching" role="status">{{ locale==='en'?'Locating the linked passage…':'正在定位关联原文…' }}</p>
    <div v-if="focusMatches.length" class="pdf-match-controls" data-source-text-matches role="status"><span>{{ locale==='en'?`${focusMatchCount} text matches for review`:`找到 ${focusMatchCount} 处文字匹配，供对照核查` }}</span><label v-if="focusMatchCount>1">{{ locale==='en'?'Matching page':'匹配页' }}<select :value="focusMatches.some(match=>match.page===state.page)?state.page:''" :aria-label="locale==='en'?'Matching page':'匹配页'" @change="navigate(Number(($event.target as HTMLSelectElement).value))"><option disabled value="">{{ locale==='en'?'Choose a matching page':'选择匹配页' }}</option><option v-for="match in focusMatches" :key="match.page" :value="match.page">{{ locale==='en'?`Page ${match.page} · ${match.count} matches`:`第 ${match.page} 页 · ${match.count} 处` }}</option></select></label><small>{{ locale==='en'?'Text matches do not confirm amendment ranges.':'文字匹配不等于已确认修改范围。' }}</small></div>
    <div ref="surface" class="pdf-surface" role="region" :aria-label="title" tabindex="0" :aria-busy="['loading', 'rendering'].includes(state.status)">
      <p v-if="['loading', 'rendering'].includes(state.status)" class="pdf-status" role="status">{{ w('pdfLoading') }}</p>
      <div ref="paper" class="pdf-paper" :class="{'source-selectable':selectWords}" :style="{ width: `${paperSize.width}px`, height: `${paperSize.height}px`, '--scale-factor': renderedScale }" v-show="state.status === 'ready'" @pointerdown="startWordSelection" @pointermove="moveWordSelection" @pointerup="finishWordSelection" @pointercancel="dragStart=undefined;dragBox=undefined">
        <canvas ref="canvas" :aria-label="`${title} — ${w('pdfPage')} ${state.page}`" role="img" />
        <div ref="textLayerHost" class="pdf-text-layer" aria-hidden="true" />
        <div v-for="(rectangle,index) in focusRectangles" :key="`focus-${index}`" class="source-focus-range" :style="{left:rectangle.x*renderedScale+'px',top:rectangle.y*renderedScale+'px',width:rectangle.width*renderedScale+'px',height:rectangle.height*renderedScale+'px'}" />
        <span v-for="(word,index) in pageWords" :key="`${state.page}-${index}`" :data-source-word="index" class="source-page-word" :class="{picked:pickedWords.includes(index)}" role="checkbox" :aria-checked="pickedWords.includes(index)" :aria-label="word.text" tabindex="0" :style="{left:word.rectangle.x*renderedScale+'px',top:word.rectangle.y*renderedScale+'px',width:word.rectangle.width*renderedScale+'px',height:word.rectangle.height*renderedScale+'px'}" @keydown.space.prevent="publishSelection(pickedWords.includes(index)?pickedWords.filter(id=>id!==index):[...pickedWords,index])" @dblclick.prevent="publishSelection([index])">{{ word.text }}</span>
        <div v-if="dragBox" class="source-selection-box" :style="{left:dragBox.x*renderedScale+'px',top:dragBox.y*renderedScale+'px',width:dragBox.width*renderedScale+'px',height:dragBox.height*renderedScale+'px'}" />
        <div v-if="pickedWords.length" ref="selectionMenuHost" data-source-selection-menu class="source-selection-menu" :style="selectionMenu" @pointerdown.stop><slot name="selection" :clear="clearSelection" /></div>
        <button v-for="highlight in pageHighlights" :key="`${highlight.location.bindingId}:${highlight.index}`" type="button" class="pdf-binding-range" :class="{ selected: selectedBindingId === highlight.location.bindingId }" :data-pdf-binding="highlight.location.bindingId" :title="highlight.location.label" :aria-label="highlight.location.label" :aria-pressed="selectedBindingId === highlight.location.bindingId" :style="{ left: `${highlight.rectangle.x * renderedScale}px`, top: `${highlight.rectangle.y * renderedScale}px`, width: `${highlight.rectangle.width * renderedScale}px`, height: `${highlight.rectangle.height * renderedScale}px` }" @click="emit('binding', highlight.location.bindingId)" />
        <button v-for="location in pageAnchors" :key="location.bindingId" type="button" class="pdf-binding-point" :class="{ selected: selectedBindingId === location.bindingId }" :data-pdf-binding="location.bindingId" :title="`${location.label} · ${w('pdfParagraphAnchor')}`" :aria-label="`${location.label} · ${w('pdfParagraphAnchor')}`" :aria-pressed="selectedBindingId === location.bindingId" :style="{ left: `${location.geometry.x / location.geometry.pageWidth * 100}%`, top: `${location.geometry.y / location.geometry.pageHeight * 100}%` }" @click="emit('binding', location.bindingId)">⌖</button>
      </div>
    </div>
  </section>
</template>

<style scoped>
.pdf-pages { display: flex; flex-direction: column; height: min(78vh, 960px); min-width: 0; min-height: 280px; border: 1px solid var(--line); border-radius: var(--radius); overflow: hidden; background: var(--bg); }
.pdf-pages--immersive { flex: 1; height: 100%; min-height: 0; border-radius: 0; }
.pdf-pages--immersive .pdf-toolbar { padding: 6px 12px; gap: 6px 12px; }
.pdf-match-controls{display:flex;flex-wrap:wrap;align-items:center;gap:8px 16px;padding:8px 12px;font-size:12px;background:var(--accent-soft)}.pdf-match-controls label{display:flex;align-items:center;gap:6px}.pdf-match-controls select{font:inherit;padding:4px}.pdf-match-controls small{color:var(--muted)}
.pdf-toolbar { display: flex; flex: none; justify-content: space-between; align-items: center; gap: 12px 20px; flex-wrap: wrap; padding: 12px 16px; background: var(--surface); border-bottom: 1px solid var(--line); }
.pdf-page-controls, .pdf-page-jump, .pdf-zoom-controls { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.pdf-page-jump { font-size: 13px; }
.pdf-page-number { width: 4.5em; padding: 7px 8px; border: 1px solid var(--line); border-radius: 6px; background: var(--surface); color: var(--ink); font: inherit; }
.pdf-zoom-value { min-width: 48px; text-align: center; font-size: 13px; font-variant-numeric: tabular-nums; }
.pdf-toolbar .btn { padding: 7px 10px; font-size: 13px; white-space: nowrap; }
.pdf-fit-width[aria-pressed="true"] { background: var(--accent-soft); color: var(--accent-dark); }
.pdf-surface { flex: 1; min-height: 0; overflow: auto; overflow-y: scroll; overscroll-behavior: contain; padding: 24px; background: var(--bg); }
.pdf-paper { position: relative; margin: 0 auto; }
.source-selectable { touch-action:none;user-select:none;cursor:crosshair; }
.source-page-word { position:absolute;color:transparent;white-space:pre;overflow:hidden;cursor:text; }
.source-page-word.picked { background:#79b9aa66;outline:1px solid #24736766; }
.source-focus-range { position:absolute;pointer-events:none;background:#ebce6a66; }
.source-page-word:focus-visible { outline:2px solid var(--accent); }
.source-selection-box { position:absolute;border:1px solid var(--accent);background:#79b9aa33;pointer-events:none; }
.source-selection-menu { position:absolute;z-index:6;min-width:250px;max-width:340px;padding:10px;background:var(--surface);box-shadow:0 4px 20px #1234;border:1px solid var(--line);border-radius:8px;color:var(--ink);cursor:default; }
.source-selection-menu :deep(blockquote){max-height:90px;overflow:auto;margin:0 0 10px;white-space:pre-wrap}
canvas { display: block; max-width: none; background: #fff; box-shadow: var(--shadow); }
.pdf-text-layer { position: absolute; inset: 0; overflow: clip; line-height: 1; text-align: initial; text-size-adjust: none; forced-color-adjust: none; transform-origin: 0 0; pointer-events: none; }
.pdf-text-layer :deep(span), .pdf-text-layer :deep(br) { position: absolute; color: transparent; white-space: pre; transform-origin: 0 0; }
.pdf-binding-range { position: absolute; padding: 0; border: 1px solid var(--accent); border-radius: 1px; background: color-mix(in srgb, var(--accent) 14%, transparent); cursor: pointer; }
.pdf-binding-range.selected { border-color: var(--amber); background: color-mix(in srgb, var(--amber) 22%, transparent); box-shadow: 0 0 0 1px var(--amber); }
.pdf-binding-range:focus-visible { outline: 2px solid var(--accent-dark); outline-offset: 2px; }
.pdf-binding-point { position: absolute; transform: translate(-50%, -50%); width: 22px; height: 22px; padding: 0; border: 1px solid var(--accent); border-radius: 3px; background: var(--surface); color: var(--accent-dark); font-size: 19px; line-height: 19px; cursor: pointer; }
.pdf-binding-point.selected { outline: 3px solid var(--amber); }
.pdf-status { width: fit-content; margin: 0 auto; padding: 14px; background: var(--surface); border-radius: 8px; font-size: 13px; }
.pdf-error { flex: none; padding: 14px; font-size: 13px; line-height: 1.55; background: var(--red-soft); color: var(--red); }
.pdf-error p { margin: 0 0 8px; }
.error-detail { white-space: pre-wrap; overflow-wrap: anywhere; }
button:disabled { opacity: .5; cursor: not-allowed; }
@media (max-width: 700px) {
  .pdf-toolbar { padding: 10px; gap: 10px; }
  .pdf-page-controls, .pdf-zoom-controls { justify-content: center; width: 100%; }
  .pdf-page-jump { gap: 6px; }
}
</style>
