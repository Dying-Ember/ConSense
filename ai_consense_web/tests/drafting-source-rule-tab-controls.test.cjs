// Mounted controls only; synthetic transport. This file does not run real model or product requests.
const assert = require('node:assert/strict'), path = require('node:path'), vue = require('vue')
const { loadVue } = require('./helpers/load-vue.cjs')
const { renderer, find, text, settle } = require('./helpers/render-controls.cjs')
const copy = value => JSON.parse(JSON.stringify(value))
const source = { id: '41', sourceDocumentId: 41, docId: 'ntt', editionId: 'NTT-docx-TEST', fileKey: 'NTT', fileName: 'SYNTHETIC NTT.docx', format: 'docx', sourceSha256: 'a'.repeat(64), parseStatus: 'PARSED', evidenceStatus: 'bound' }
const item = (id, role, status = 'pending') => ({ id, definitionKind: 'discovered', nameZh: id, nameEn: id, questionZh: id, questionEn: id, type: { kind: 'boolean' }, role: { kind: role }, status, parentId: null, optional: false, options: [], sourceAnchors: [{ docId: '41', sourceSha256: source.sourceSha256, text: 'SYNTHETIC current instruction' }], docIds: ['41'], sourceUnitIds: ['note'], editTargets: [], relatedInputIds: [], targets: [], relations: [], evidence: [] })
const root = item('business-root', 'input', 'confirmed'), rule = item('scope-rule', 'derived'), check = item('source-check', 'verification')
rule.correction = { kind: 'rule', branches: [{ id: 'match', mode: 'all', clauses: [{ inputId: root.id, operator: 'yes' }], resultValue: true }], inputIds: [root.id], needsRebind: false }
rule.origin = 'scope_rules_compilation'
const fixture = { kind: 'consense-template-graph-v1', graph: { variables: [root, rule, check], events: [], sourceReviews: {}, nextId: 1, blank: false, guidanceMode: 'recognized' }, sources: [source], selectedDocuments: { NTT: '41' } }
const saves = []
const revision = graph => ({ id: 'rule-tab-draft', parentRevisionId: null, status: 'DRAFT', graphSha256: 'test-sha', sourceStale: false, graph: copy(graph), definitionGraphReview: { valid: true, confirmedFieldIds: [root.id], pendingFieldIds: [], fixedCandidateFieldIds: [], unresolvedRelationCount: 1 } })
const api = {
  reviewHead: async () => ({ currentRevisionId: null, draftRevisionId: 'rule-tab-draft', current: null, draft: revision(fixture), sources: [source] }),
  reviewReading: async () => ({ ...source, paragraphs: [], sourceMembers: [], guidanceNotes: [], markerCandidates: [], groups: [], conditions: [], issues: [], blocks: [] }),
  reviewPreview: async () => { throw Error('SYNTHETIC preview boundary') },
  reviewSave: async (_project, graph) => { saves.push(copy(graph)); return revision(graph) },
  baselineCatalog: async () => ({ ruleVersion: 'SYNTHETIC', groups: [], systemFields: [] })
}
const Modal = { props: ['open'], setup: (props, { slots }) => () => props.open ? vue.h('section', slots.default?.()) : null }
const loaded = loadVue(path.join(__dirname, '../src/components/DraftingSourceReview.vue'), {
  boundaries: { '@/api': { draftingApi: api }, '@/api/source-locations': { reviewSourceBindings: async () => ({ bindings: [] }) }, './AppModal.vue': { default: Modal }, './DraftingPdfPreview.vue': { default: { render: () => null } } },
  globals: { structuredClone, URL }
})
;(async () => {
  const host = renderer(), editor = vue.ref(), app = host.createApp({ setup: () => () => vue.h(loaded.default, { projectId: 'SYNTHETIC-RULE-TABS', locale: 'en', ref: editor }) })
  const attr = (key, value) => find(host.root, node => value === undefined ? node.props?.[key] !== undefined : node.props?.[key] === value)
  const click = async node => { assert.ok(node); await node.props.onClick(); await settle() }
  const button = label => find(host.root, node => node.tag === 'button' && text(node) === label)
  const back = async () => click(button('Back to variables'))
  app.mount(host.root); await settle()
  try {
    assert.ok(attr('data-review-question', root.id)); assert.equal(attr('data-review-question', rule.id), undefined); assert.equal(attr('data-review-question', check.id), undefined)
    assert.match(text(host.root), /1 business variables/); assert.match(text(host.root), /1 treatment rules \/ derived items/)
    await click(attr('data-review-tab-rules'))
    assert.ok(attr('data-review-question', rule.id)); assert.equal(attr('data-review-question', root.id), undefined); assert.equal(attr('data-review-question', check.id), undefined)
    await click(attr('data-review-question', rule.id)); assert.ok(attr('data-review-edit-question')); await back()
    assert.equal(attr('data-review-tab-rules').props['aria-pressed'], true, 'Returning from a rule retains the rule list')

    await editor.value.locateQuestion(root.id, 'rule-tab-draft'); await settle(); await click(attr('data-review-next-pending')); await back()
    assert.equal(attr('data-review-tab-rules').props['aria-pressed'], true, 'Next unfinished item selects the matching rule tab')
    await click(button('View relationships')); await click(attr('data-review-graph-node', rule.id)); await click(attr('data-review-graph-locate')); await back()
    assert.equal(attr('data-review-tab-rules').props['aria-pressed'], true, 'The graph locate path also selects the rule tab')

    await editor.value.locateQuestion(rule.id, 'rule-tab-draft'); await settle(); await click(attr('data-review-edit-question'))
    assert.match(text(host.root), /Which passage changes, and how/); assert.ok(attr('data-review-confirm-item'), 'Rules retain the manual review action')
    await click(attr('data-review-confirm-item')); await click(attr('data-review-save'))
    assert.equal(saves.at(-1).graph.variables.find(node => node.id === rule.id).status, 'confirmed', 'An explicit review and save confirms the rule')
    assert.equal(saves.at(-1).graph.variables.find(node => node.id === check.id).status, 'pending', 'Reviewing a rule does not confirm source checks')
    assert.equal(rule.status, 'pending', 'The fixture/known business confirmation did not automatically approve this rule')

    await editor.value.locateQuestion(check.id, 'rule-tab-draft'); await settle(); await back()
    assert.equal(attr('data-review-tab-checks').props['aria-pressed'], true); assert.ok(attr('data-review-question', check.id)); assert.equal(attr('data-review-question', rule.id), undefined)
    await editor.value.locateQuestion(root.id, 'rule-tab-draft'); await settle(); await back()
    assert.equal(attr('data-review-tab-business').props['aria-pressed'], true); assert.ok(attr('data-review-question', root.id)); assert.equal(attr('data-review-question', rule.id), undefined)
  } finally { app.unmount() }
})().catch(error => { console.error(error); process.exitCode = 1 })
