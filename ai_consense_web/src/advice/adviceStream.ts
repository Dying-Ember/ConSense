import { captureLlmSelection, ApiError } from '@/api/client'
import type { AdviceBasisScope, AskResponse, Citation, LlmSelection } from '@/api/types'

export interface AdviceStreamRequest {
  question: string
  scope: string
  sessionId: string
  basisScope: AdviceBasisScope
  replyToMessageId?: number
}
export interface AdviceSection { content: string; citations: string[]; sources: Citation[] }
export interface AdviceStreamOptions {
  requestId: string
  signal: AbortSignal
  llmSelection?: LlmSelection
  phase: (phase: string) => void
  section: (section: AdviceSection) => void
}

function headers(selection: LlmSelection = captureLlmSelection()) {
  return { 'Content-Type': 'application/json', ...(selection.profileId ? { 'X-ConSense-Llm-Profile': selection.profileId } : {}) }
}

/** Only server-validated answer units are delivered; EOF without commit is a failure. */
export async function streamAdvice(projectId: string, request: AdviceStreamRequest, options: AdviceStreamOptions): Promise<AskResponse> {
  const response = await fetch(`/api/advice/${encodeURIComponent(projectId)}/ask/stream`, {
    method: 'POST', headers: { ...headers(options.llmSelection), Accept: 'text/event-stream', 'X-ConSense-Advice-Request': options.requestId },
    body: JSON.stringify(request), signal: options.signal
  })
  if (!response.ok || !response.headers.get('Content-Type')?.includes('text/event-stream') || !response.body) {
    const failure = await response.json().catch(() => null)
    throw new ApiError(failure?.code ?? response.status, failure?.message ?? 'Unable to start the consultation stream.')
  }
  const reader = response.body.getReader(), decoder = new TextDecoder('utf-8', { fatal: true })
  let pending = '', bytes = 0, completed: AskResponse | undefined
  const frame = (block: string) => {
    const lines = block.split(/\r?\n/)
    const event = lines.find(line => line.startsWith('event:'))?.slice(6).trim()
    const raw = lines.filter(line => line.startsWith('data:')).map(line => line.slice(5).trimStart()).join('\n')
    if (!raw) return
    const value = JSON.parse(raw)
    if (value.requestId !== options.requestId) throw new Error('ADVICE_STREAM_REQUEST_MISMATCH')
    if (completed) throw new Error('ADVICE_STREAM_EVENT_AFTER_COMMIT')
    if (event === 'phase') {
      if (typeof value.phase !== 'string') throw new Error('ADVICE_STREAM_INVALID_PHASE')
      options.phase(value.phase)
    } else if (event === 'section') {
      if (typeof value.content !== 'string' || !Array.isArray(value.citations) || !value.citations.every((id: unknown) => typeof id === 'string') || !Array.isArray(value.sources)) throw new Error('ADVICE_STREAM_INVALID_SECTION')
      options.section({ content: value.content, citations: value.citations, sources: value.sources })
    } else if (event === 'complete') {
      if (!value.response || value.response.sessionId !== request.sessionId || typeof value.response.grounded !== 'boolean' || !Array.isArray(value.response.citations) || !Array.isArray(value.response.sources)) throw new Error('ADVICE_STREAM_INVALID_COMMIT')
      completed = value.response
    } else if (event === 'error') {
      throw new ApiError(typeof value.code === 'number' ? value.code : -1, typeof value.message === 'string' ? value.message : 'Consultation failed before completion.')
    } else throw new Error('ADVICE_STREAM_UNKNOWN_EVENT')
  }
  try {
    while (true) {
      if (options.signal.aborted) throw new DOMException('Consultation stopped', 'AbortError')
      const next = await reader.read()
      if (next.done) { pending += decoder.decode(); break }
      bytes += next.value.byteLength
      if (bytes > 32 * 1024 * 1024) throw new Error('ADVICE_STREAM_TOO_LARGE')
      pending += decoder.decode(next.value, { stream: true })
      let boundary: RegExpExecArray | null
      while ((boundary = /\r?\n\r?\n/.exec(pending))) {
        frame(pending.slice(0, boundary.index)); pending = pending.slice(boundary.index + boundary[0].length)
      }
      if (pending.length > 16 * 1024 * 1024) throw new Error('ADVICE_STREAM_FRAME_TOO_LARGE')
    }
    if (pending.trim()) throw new Error('ADVICE_STREAM_INCOMPLETE_FRAME')
    if (!completed) throw new Error('The consultation connection ended before the answer was saved. Please retry.')
    return completed
  } finally { await reader.cancel().catch(() => undefined); reader.releaseLock() }
}

export async function stopAdvice(projectId: string, requestId: string, sessionId: string, selection?: LlmSelection): Promise<void> {
  const response = await fetch(`/api/advice/${encodeURIComponent(projectId)}/ask/stream/${encodeURIComponent(requestId)}/stop?sessionId=${encodeURIComponent(sessionId)}`, { method: 'POST', headers: headers(selection) })
  const body = await response.json()
  if (!response.ok || body.code !== 0) throw new ApiError(body.code ?? response.status, body.message ?? 'The stop request could not be confirmed.')
}
