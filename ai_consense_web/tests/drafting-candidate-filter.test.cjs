const assert = require('node:assert/strict')
const path = require('node:path')
const vue = require('vue')
const { loadVue } = require('./helpers/load-vue.cjs')
const { renderer, find, text, settle } = require('./helpers/render-controls.cjs')
const calls = []
const response = { runId: 'saved', variables: [{ key: 'question', label: { en: 'Project requirement' }, kind: 'text', candidates: [
  { value: 'First', sourceQuote: 'First source quotation', fileName: 'a.docx' },
  { value: 'Second', sourceQuote: 'Second source quotation', fileName: 'b.docx' }
] }], catalog: { groups: [{ fields: [{ key: 'question', label: { en: 'Project requirement' }, kind: 'text' }] }] } }
const loaded = loadVue(path.join(__dirname, '../src/components/DraftingCandidateReview.vue'), { boundaries: { '@/api': { draftingApi: {
  reviewCandidates: async () => response,
  reviewCorrectCandidate: async (...args) => { calls.push(args); return response }
} } } })
const host = renderer(), app = host.createApp({ setup: () => () => vue.h(loaded.default, { projectId: 'P', revisionId: 'R', locale: 'en', reloadKey: 0 }) })
app.mount(host.root)
;(async () => {
  await settle()
  const search = find(host.root, node => node.props?.['data-candidate-search'] !== undefined)
  search.props['onUpdate:modelValue']('b.docx'); await settle()
  assert.match(text(host.root), /1 \/ 2/)
  const edit = find(host.root, node => node.tag === 'button' && text(node) === 'Correct candidate or assignment')
  await edit.props.onClick(); await settle()
  const input = find(host.root, node => node.props?.id === 'candidate-question')
  assert.equal(input.props.value, 'Second')
  input.props.onInput({ target: { value: 'Corrected second' } }); await settle()
  assert.equal(search.props.disabled, true)
  await find(host.root, node => node.tag === 'form').props.onSubmit({ preventDefault() {} }); await settle()
  assert.equal(calls.length, 1)
  assert.equal(calls[0][3].candidateIndex, 1, 'Filtered position zero still corrects original candidate one')
  assert.equal(calls[0][3].value, 'Corrected second')
  assert.equal(calls[0][3].sourceQuote, 'Second source quotation')
  assert.equal(response.variables[0].candidates[0].value, 'First')
  app.unmount()
  console.log('PASS: filtered correction retains original candidate index, evidence and the other candidate')
})().catch(error => { console.error(error); process.exitCode = 1 })
