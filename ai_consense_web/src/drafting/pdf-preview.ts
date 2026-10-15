export type PdfPreviewStatus = 'idle' | 'loading' | 'rendering' | 'ready' | 'error'
export interface PdfPreviewState { status: PdfPreviewStatus; page: number; pages: number; error: string }
export interface PdfPreviewDocument { numPages: number; destroy(): Promise<void> }
export interface PdfPreviewLoad<Document extends PdfPreviewDocument> { promise: Promise<Document>; destroy(): Promise<void> }
export interface PdfPreviewRender { promise: Promise<void>; cancel(): void }
export interface PdfPreviewAdapter<Document extends PdfPreviewDocument> {
  load(source: string): PdfPreviewLoad<Document>
  render(document: Document, page: number, current: () => boolean): Promise<PdfPreviewRender | undefined>
}

/** One document and one rendered page; obsolete jobs cannot publish state after a source/page switch. */
export function createPdfPreview<Document extends PdfPreviewDocument>(adapter: PdfPreviewAdapter<Document>, state: PdfPreviewState) {
  let sourceVersion = 0, renderVersion = 0, closed = false
  let loading: PdfPreviewLoad<Document> | undefined
  let document: Document | undefined
  let rendering: PdfPreviewRender | undefined
  let canvasReleased: Promise<void> = Promise.resolve()

  function release() {
    if (rendering) { canvasReleased = rendering.promise.catch(() => {}); rendering.cancel() }
    rendering = undefined
    if (loading) void loading.destroy().catch(() => {})
    loading = undefined; document = undefined
  }

  async function showPage(requested: number) {
    const pdf = document
    if (!pdf || closed) return
    const source = sourceVersion, version = ++renderVersion
    const current = () => !closed && source === sourceVersion && version === renderVersion
    const previous = rendering; rendering = undefined; previous?.cancel()
    const page = Number.isFinite(requested) ? Math.max(1, Math.min(pdf.numPages, Math.trunc(requested))) : state.page
    state.page = page; state.status = 'rendering'; state.error = ''
    try {
      if (previous) canvasReleased = previous.promise.catch(() => {})
      await canvasReleased
      if (!current()) return
      const task = await adapter.render(pdf, page, current)
      if (!current()) { task?.cancel(); return }
      if (!task) throw new Error('The PDF page could not be rendered.')
      rendering = task
      await task.promise
      if (current()) state.status = 'ready'
    } catch (caught) {
      if (current()) { state.status = 'error'; state.error = caught instanceof Error ? caught.message : String(caught) }
    } finally { if (current()) rendering = undefined }
  }

  async function open(source: string, initialPage = 1) {
    const version = ++sourceVersion; renderVersion++; release()
    state.page = 1; state.pages = 0; state.error = ''; state.status = source ? 'loading' : 'idle'
    if (!source || closed) return
    try {
      const task = adapter.load(source); loading = task
      const pdf = await task.promise
      if (closed || version !== sourceVersion) { void pdf.destroy().catch(() => {}); return }
      document = pdf; state.pages = pdf.numPages
      await showPage(initialPage)
    } catch (caught) {
      if (!closed && version === sourceVersion) { state.status = 'error'; state.error = caught instanceof Error ? caught.message : String(caught) }
    }
  }

  function close() { closed = true; sourceVersion++; renderVersion++; release(); state.status = 'idle' }
  return { open, showPage, close }
}
