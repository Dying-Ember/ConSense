import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { parse } from 'vue/compiler-sfc'
import ts from 'typescript'
import * as vue from 'vue'

const source = await readFile(new URL('../src/views/AdviceView.vue', import.meta.url), 'utf8')
const script = parse(source).descriptor.scriptSetup.content.replace(/^import .*\r?\n/gm, '')
const compiled = ts.transpileModule(script, { compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.None } }).outputText
const setup = new Function('deps', `
  const { computed, nextTick, ref, watch, onMounted, onBeforeUnmount, useI18n, useRoute, useRouter, useAppStore, useLocalized, adviceApi } = deps;
  ${compiled}
  return { messages, scope, scopeDocuments, ask, askQuick, askPrecedence, activeCitation, openMessageSources, asking, clarificationReply, replyToClarification, clearChat };
`)
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r }); return { promise, resolve } }
const settle = async () => { for (let i=0; i<15; i++) await Promise.resolve(); await vue.nextTick() }
function harness(overrides = {}) {
  const mounted = [], scopeCalls = [], asks = []
  const store = vue.reactive({ activeProjectId: 'A', notify() {}, setBusy() {}, clearBusy() {}, captureLlmSelection: () => ({ profileId: 'minimax-cn' }) })
  const api = {
    sessions: async () => [],
    createSession: async (projectId, scope, basisScope) => ({ id: `session-${projectId}-${scope}`, projectId, scope, basisScope }),
    messages: async () => [], quickQuestions: async () => [], indexStatus: async () => ({ chunks: 1 }),
    sources: async (id, scope) => { scopeCalls.push([id, scope]); return [{ documentId: id, fileName: scope, included: true }] },
    ask: async (...args) => { asks.push(args); return { messageId: 101, sessionId: args[5], basisScope: 'insufficient', grounded: false, unknownScope: args[2], citations: [], sources: [], clarifications: ['Are quantities firm?'] } },
    ...overrides
  }
  const effects = vue.effectScope()
  const view = effects.run(() => setup({ computed: vue.computed, nextTick: vue.nextTick, ref: vue.ref, watch: vue.watch,
    onMounted: fn => mounted.push(fn), onBeforeUnmount() {}, useI18n: () => ({ t: key => key }),
    useRoute: () => ({ query: {} }), useRouter: () => ({ replace: async () => undefined }), useAppStore: () => store,
    useLocalized: () => ({ pick: value => value }), adviceApi: api }))
  return { view, store, scopeCalls, asks, mount: async () => { mounted.forEach(fn => fn()); await settle() }, dispose: () => effects.stop() }
}
{
  const h = harness(); await h.mount()
  assert.deepEqual(h.scopeCalls[0], ['A', 'fullset'])
  h.view.scope.value = 'contract'; await settle()
  assert.deepEqual(h.scopeCalls.at(-1), ['A', 'contract'])
  await h.view.ask('Explain contract quantities')
  assert.equal(h.asks[0][2], 'contract')
  assert.deepEqual(h.view.messages.value.at(-1).clarifications, ['Are quantities firm?'])
  assert.equal(h.view.messages.value.at(-1).sourceScope, 'contract')
  assert.equal(h.view.messages.value.at(-1).id, 101)
  assert.equal(h.asks[0][4], undefined, 'new questions do not implicitly reply to previous clarifications')
  h.view.replyToClarification(h.view.messages.value.at(-1))
  await h.view.ask('Firm quantities, already signed')
  assert.equal(h.asks[1][4], 101, 'explicit reply binds the original clarification message')
  assert.equal(h.view.clarificationReply.value, null)
  const clarification = h.view.messages.value.at(-1)
  h.view.replyToClarification(clarification)
  h.view.scope.value = 'tender'; await settle()
  assert.equal(h.view.clarificationReply.value, null, 'scope changes cancel clarification binding')
  assert.equal(h.view.messages.value.length, 0, 'scope changes clear the visible history')
  h.view.scope.value = 'contract'; await settle()
  h.view.replyToClarification(clarification)
  await h.view.askQuick({ text: 'Independent quick question' })
  assert.equal(h.asks.at(-1)[4], undefined, 'quick questions start a new question, even when a reply was selected')
  h.view.replyToClarification(h.view.messages.value.at(-1))
  await h.view.askPrecedence()
  assert.equal(h.asks.at(-1)[4], undefined, 'precedence question does not become background to an earlier question')
  h.view.replyToClarification({ id: 201, basisScope: 'project_contract', sourceScope: 'contract', clarifications: ['Optional question from a legacy answer'] })
  assert.equal(h.view.clarificationReply.value, null, 'a grounded legacy answer cannot be bound as a missing-background clarification')
  h.dispose()
}
{
  const pending = deferred(), h = harness({ ask: () => pending.promise }); await h.mount()
  const asking = h.view.ask('A contract question')
  await settle(); h.store.activeProjectId = 'B'; await settle()
  pending.resolve({ grounded: true, title: {}, content: { en: 'Answer for A' }, sources: [], citations: [] })
  await asking; await settle()
  assert.equal(h.view.messages.value.length, 0, 'A response must not appear in project B')
  assert.equal(h.view.asking.value, false)
  h.dispose()
}
{
  const pending = deferred(), h = harness({ sources: async (_id, scope) => scope === 'tender' ? pending.promise : [{ documentId: scope, included: true }] }); await h.mount()
  h.view.scope.value = 'tender'; await settle(); h.view.scope.value = 'contract'; await settle()
  pending.resolve([{ documentId: 'stale-tender', included: true }]); await settle()
  assert.equal(h.view.scopeDocuments.value[0].documentId, 'contract', 'stale scope catalog must not replace current catalog')
  const sources = [{ fileLabel: 'same.docx', evidenceId: '[1]', content: 'first' }, { fileLabel: 'same.docx', evidenceId: '[2]', content: 'second' }]
  h.view.openMessageSources({ sources }, '[2]')
  assert.equal(h.view.activeCitation.value.content, 'second', 'citation ID resolves exact supplied excerpt')
  h.dispose()
}
console.log('Advice scope, clarifications, citation IDs and project isolation: passed')
