// Regression for the step-082 representation mismatch; no business API or real-source acceptance.
const assert = require('node:assert/strict'), path = require('node:path'), vue = require('vue')
const { loadVue } = require('./helpers/load-vue.cjs')
const { renderer, find, text, settle } = require('./helpers/render-controls.cjs')
const Component = loadVue(path.join(__dirname, '../src/components/DraftingQuestionBatch.vue'), { globals: { structuredClone } }).default
const source = { id: '41', fileKey: 'SCC', fileName: 'SYNTHETIC SCC.docx', format: 'docx' }
const makeNode = (id, values) => ({ id, nameZh: '工程是否涉及需要获取挖掘许可的工作', nameEn: 'Works requiring excavation permits', type: { kind: 'boolean' }, role: { kind: 'input' }, parentId: null, status: 'pending', docIds: ['41'], options: values.map((value, index) => ({ value, labelZh: index ? '否' : '是', labelEn: index ? 'No' : 'Yes' })), sourceAnchors: [{ docId: '41', text: 'Do not adopt this SCC Clause in contracts that do not involve works requiring excavation permits to be obtained.' }], editTargets: [], sourceUnitIds: [], relatedInputIds: [], evidence: [], targets: [], relations: [] })
async function run(reverse = false, implicit = false) {
  const state = { variables: [makeNode('yes-no', ['yes', 'no']), makeNode('true-false', ['true', 'false'])], events: [], sourceReviews: {} }
  if (implicit) state.variables[0].options = []
  if (reverse) state.variables[0].options.reverse().forEach((option, index) => { option.value = index ? 'no' : 'yes' })
  const before = structuredClone(state), emitted = [], host = renderer()
  const app = host.createApp({ render: () => vue.h(Component, { state, ids: ['yes-no', 'true-false'], sources: [source], locale: 'en', onMerge: (...args) => emitted.push(args) }) })
  app.mount(host.root); await settle()
  const get = (attribute, value) => find(host.root, node => value === undefined ? node.props?.[attribute] !== undefined : node.props?.[attribute] === value)
  get('data-batch-mode', 'duplicates').props.onClick(); await settle()
  if (reverse) {
    assert.equal(get('data-batch-confirm-merge').props.disabled, true, 'Reversed value/label polarity must keep the real merge button disabled')
    assert.match(text(get('data-batch-merge-problem')), /Value options differ/)
  } else {
    assert.equal(Boolean(get('data-batch-merge-problem')), false, 'Step 082: the actual comparison must stop reporting equivalent affirmative booleans as different value options')
    assert.equal(get('data-batch-confirm-merge').props.disabled, true, 'Semantic equivalence never replaces explicit human acknowledgement')
    get('data-batch-ack').props['onUpdate:modelValue'](true); await settle()
    assert.equal(get('data-batch-confirm-merge').props.disabled, false)
    get('data-batch-confirm-merge').props.onClick(); await settle()
    assert.deepEqual(JSON.parse(JSON.stringify(emitted)), [[['yes-no', 'true-false'], 'yes-no']], 'Use the existing merge event and selected retained identity')
  }
  assert.deepEqual(state, before, 'Comparing/acknowledging emits only the existing local transaction; it does not mutate or save the graph')
  app.unmount()
}
;(async () => { await run(); await run(true); await run(false, true); console.log('PASS: standard explicit and default booleans pass the real comparison, still require acknowledgement, and reversed polarity stays blocked') })().catch(error => { console.error(error); process.exitCode = 1 })
