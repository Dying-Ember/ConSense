const assert = require('node:assert/strict')
const { test } = require('node:test')
const path = require('node:path')
const { loadTypeScript } = require('./helpers/load-typescript.cjs')
const requestId = '0123456789abcdef0123456789abcdef'
const request = { question: '画出处理流程', scope: 'contract', sessionId: 'session-A', basisScope: 'project_contract' }
const result = { sessionId: request.sessionId, grounded: false, content: { en: 'Limited [1]', zhHans: '有限分析 [1]', zhHant: '有限分析 [1]' }, citations: ['[1]'], sources: [], basisScope: 'insufficient' }
const event = (name, value) => `event: ${name}\ndata: ${JSON.stringify({ requestId, ...value })}\n\n`
class ApiError extends Error { constructor(code, message) { super(message); this.code = code } }
function client(fetch) {
  return loadTypeScript(path.join(__dirname, '../src/advice/adviceStream.ts'), {
    globals: { fetch, TextDecoder, DOMException, JSON, Error },
    dependencies: { '@/api/client': { captureLlmSelection: () => ({ profileId: 'minimax-cn' }), ApiError } }
  })
}
const options = (overrides = {}) => ({ requestId, signal: new AbortController().signal, phase() {}, section() {}, ...overrides })

test('actual fragmented response delivers phase and validated unit before committed completion, preserving UTF8 and profile', async () => {
  let wire, network
  const phases = [], sections = []
  const api = client(async (url, init) => {
    network = { url, init }
    return new Response(new ReadableStream({ start(controller) { wire = controller } }), { headers: { 'Content-Type': 'text/event-stream' } })
  })
  const pending = api.streamAdvice('project/甲', request, options({ phase: phase => phases.push(phase), section: section => sections.push(section) }))
  await new Promise(resolve => setImmediate(resolve))
  const first = Buffer.from(event('phase', { phase: 'retrieving' }) + event('section', { content: '流程 😀 [1]', citations: ['[1]'], sources: [] }))
  for (let offset = 0; offset < first.length; offset += 3) wire.enqueue(new Uint8Array(first.subarray(offset, offset + 3)))
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(phases.join(), 'retrieving')
  assert.equal(sections[0].content, '流程 😀 [1]')
  assert.equal(network.init.headers['X-ConSense-Llm-Profile'], 'minimax-cn')
  assert.equal(network.init.headers['X-ConSense-Advice-Request'], requestId)
  assert.equal(network.url, '/api/advice/project%2F%E7%94%B2/ask/stream')
  assert.equal(JSON.parse(network.init.body).basisScope, 'project_contract')
  wire.enqueue(new TextEncoder().encode(event('complete', { response: result }))); wire.close()
  const completed = await pending
  assert.equal(completed.grounded, false)
  assert.equal(completed.content.zhHans, '有限分析 [1]')
})

test('EOF after a checked unit does not report completion', async () => {
  const api = client(async () => new Response(event('section', { content: 'Supported [1]', citations: ['[1]'], sources: [] }), { headers: { 'Content-Type': 'text/event-stream' } }))
  await assert.rejects(api.streamAdvice('project-A', request, options()), /ended before the answer was saved/)
})

test('request/session mismatch, invalid section and error event reject the stream', async () => {
  for (const wire of [
    event('phase', { requestId: 'another-request', phase: 'generating' }),
    event('complete', { response: { ...result, sessionId: 'other-session' } }),
    event('section', { content: 'Unbound', citations: [1], sources: [] }),
    event('error', { code: 4201, message: 'Source revision changed' })
  ]) {
    const api = client(async () => new Response(wire, { headers: { 'Content-Type': 'text/event-stream' } }))
    await assert.rejects(api.streamAdvice('project-A', request, options()), /MISMATCH|INVALID|Source revision changed/)
  }
})

test('unterminated frame and events after commit cannot silently become success', async () => {
  for (const wire of [event('complete', { response: result }).trimEnd(), event('complete', { response: result }) + event('section', { content: 'Too late', citations: [], sources: [] })]) {
    const api = client(async () => new Response(wire, { headers: { 'Content-Type': 'text/event-stream' } }))
    await assert.rejects(api.streamAdvice('project-A', request, options()), /INCOMPLETE_FRAME|AFTER_COMMIT/)
  }
})

test('stop binds project, request, session and captured model without restarting an ask', async () => {
  const calls = []
  const api = client(async (url, init) => { calls.push({ url, init }); return new Response(JSON.stringify({ code: 0 }), { headers: { 'Content-Type': 'application/json' } }) })
  await api.stopAdvice('project/A', requestId, 'session A', { profileId: 'local' })
  assert.equal(calls.length, 1)
  assert.equal(calls[0].url, `/api/advice/project%2FA/ask/stream/${requestId}/stop?sessionId=session%20A`)
  assert.equal(calls[0].init.headers['X-ConSense-Llm-Profile'], 'local')
})

test('aborted stream cancels its reader and never accepts completion', async () => {
  const cancellation = new AbortController()
  let readerCancelled = false
  const api = client(async () => new Response(new ReadableStream({ start(controller) { controller.enqueue(new TextEncoder().encode(event('phase', { phase: 'generating' }))) }, cancel() { readerCancelled = true } }), { headers: { 'Content-Type': 'text/event-stream' } }))
  await assert.rejects(api.streamAdvice('project-A', request, options({ signal: cancellation.signal, phase() { cancellation.abort() } })), /Consultation stopped/)
  assert.equal(readerCancelled, true)
})
