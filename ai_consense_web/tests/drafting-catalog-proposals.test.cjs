// Synthetic post-atomic proposals with real catalog/source-review controls. No model or live writes.
const assert = require('node:assert/strict'), path = require('node:path'), vue = require('vue')
const { loadVue } = require('./helpers/load-vue.cjs')
const { renderer, find, text, settle } = require('./helpers/render-controls.cjs')
const copy = value => JSON.parse(JSON.stringify(value)), label = (zh, en = zh) => ({ zhHans: zh, zhHant: zh, en })
const catalog = { ruleVersion: 'proposal-baseline-1', groups: [{ id: 'scope', label: label('工程范围'), fields: [
  { key: 'foundationIncluded', kind: 'boolean', label: label('是否包含基础工程', 'Includes foundation works'), description: label('判断本合约范围是否包含基础工程。'), affects: [{ document: 'NTT', clause: '1(e)' }], condition: { all: [{ field: 'electronicIssue', operator: 'eq', value: false }] } },
  { key: 'electronicIssue', kind: 'boolean', label: label('电子方式发出招标文件', 'Electronic tender issue') },
  { key: 'durationMonths', kind: 'number', unit: 'months', optional: true, label: label('合约工期（月）', 'Contract duration in months') }
] }] }
const source = { id: '41', fileKey: 'NTT', docId: 'ntt', editionId: 'SYNTHETIC', fileName: 'SYNTHETIC.docx', format: 'docx', sourceSha256: 'a'.repeat(64), evidenceStatus: 'bound', canEdit: true, parseStatus: 'PARSED' }
const quote = 'Delete this clause if the contract does not include foundation works.'
const node = { id: 'atomic-groundworks', nameZh: '地下基础施工范围确认', nameEn: 'Groundworks applicability', role: { kind: 'input' }, type: { kind: 'boolean' }, status: 'pending', parentId: null, options: [], docIds: [source.id], sourceAnchors: [{ docId: source.id, sourceSha256: source.sourceSha256, text: quote }], sourceUnitIds: [], relatedInputIds: [], relations: [], evidence: [], targets: [], correction: { kind: 'rule', branches: [{ id: 'self-route', mode: 'all', clauses: [{ inputId: 'atomic-groundworks', operator: 'no' }] }] }, editTargets: [{ docId: source.id, text: 'The foundation clause', action: 'check', branchId: 'self-route', needsRebind: true }], catalogAlignment: { status: 'unreviewed', confirmed: false, proposalStatus: 'matched', proposalMethod: 'post_atomic_semantic', proposedMatches: [{ baselineKey: 'foundationIncluded', baselineVersion: catalog.ruleVersion, reason: 'Both ask whether foundation works are included in this contract.', sourceQuote: quote, confidence: 0.91, compatible: true, issues: [] }] } }
const state = variables => ({ variables, events: [], sourceReviews: {}, nextId: 1, blank: false, guidanceMode: 'recognized' })
const loaded = loadVue(path.join(__dirname, '../src/components/DraftingVariableCatalog.vue'), { globals: { structuredClone } })
const model = loaded.loadLocal(path.join(__dirname, '../src/drafting/variable-catalog.ts'))
const fields = model.baselineVariables(catalog), failures = []
let checks = 0
async function check(name, run) { checks++; try { await run(); console.log('PASS: ' + name) } catch (error) { failures.push(name + ': ' + error.message); console.error('FAIL: ' + name + ': ' + error.message) } }
const draft = () => vue.reactive({ tab: 'matched', nodeId: '', baselineKey: '', missingKey: '', query: '', baselineQuery: '', missingQuery: '', reason: '', scope: '' })
function mountCatalog(variable, tab = 'matched') {
  const host = renderer(), value = draft(), events = [], original = copy(variable), graph = vue.reactive(state([variable])); value.tab = tab
  const app = host.createApp({ render: () => vue.h(loaded.default, { catalog, state: graph, draft: value, locale: 'zh', onAlign: (...args) => events.push(args) }) }); app.mount(host.root)
  const get = (attr, key) => find(host.root, n => key === undefined ? n.props?.[attr] !== undefined : n.props?.[attr] === key)
  const click = async (attr, key) => { const n = get(attr, key); assert.ok(n, attr + ':' + key); assert.notEqual(n.props.disabled, true); await n.props.onClick(); await settle() }
  return { host, value, events, original, state: graph, get, click, app }
}
;(async () => {
  await check('semantic post-atomic proposals reach suggestions despite different labels', () => {
    assert.deepEqual(Array.from(model.suggestedBaselines(node, fields, state([node])), field => field.baselineKey), ['foundationIncluded'])
    assert.equal(node.catalogAlignment.confirmed, false); assert.equal(node.catalogAlignment.baselineKey, undefined)
  })
  await check('proposal classifications keep unavailable, unmatched and ambiguous distinct', () => {
    for (const [proposalStatus, expected] of [['matched', 'matched'], ['ambiguous', 'ambiguous'], ['unmatched', 'new'], ['unavailable', 'unavailable']]) {
      const n = copy(node); n.nameZh = '是否包含基础工程'; n.catalogAlignment.proposalStatus = proposalStatus
      if (['unmatched', 'unavailable'].includes(proposalStatus)) n.catalogAlignment.proposedMatches = []
      assert.equal(model.alignmentGroup(n, fields, state([n]), catalog.ruleVersion), expected)
    }
    const old = copy(node); delete old.catalogAlignment; old.nameZh = '是否包含基础工程'
    assert.equal(model.alignmentGroup(old, fields, state([old]), catalog.ruleVersion), 'matched', 'Historical graphs retain explicit exact-name fallback')
  })
  await check('semantic match shows original evidence, fixed definitions and relationship hints before human confirmation', async () => {
    const h = mountCatalog(copy(node)); await settle(); await h.click('data-picker-option', node.id)
    assert.equal(h.value.baselineKey, 'foundationIncluded'); assert.equal(h.events.length, 0)
    const contents = text(h.host.root); assert.match(contents, /地下基础施工范围确认/); assert.ok(contents.includes(quote)); assert.match(contents, /是否包含基础工程/); assert.match(contents, /Both ask whether foundation/)
    assert.match(text(h.get('data-catalog-definition')), /判断本合约范围/); assert.match(text(h.get('data-catalog-relation-hints')), /电子方式发出招标文件/); assert.match(text(h.get('data-catalog-relation-hints')), /NTT.*1\(e\)/)
    assert.equal(h.get('data-catalog-confirm-match').props.disabled, true, 'A proposal alone is not a confirmation')
    h.get('data-catalog-reason').props['onUpdate:modelValue']('人工对照原文确认此变量对应基础工程范围'); await settle(); await h.click('data-catalog-confirm-match')
    assert.equal(h.events.length, 1); assert.equal(h.events[0][1].confirmed, true); assert.equal(h.events[0][1].baselineKey, 'foundationIncluded'); assert.deepEqual(node, h.original)
    h.app.unmount()
  })
  await check('incompatible or stale proposals remain visible but cannot be directly confirmed', async () => {
    for (const [patch, issue] of [[{ baselineKey: 'durationMonths', compatible: false, issues: ['type_mismatch', 'unit_mismatch'] }, /类型|单位/], [{ baselineVersion: 'old-baseline-version' }, /版次|版本/]]) {
      const n = copy(node); n.catalogAlignment.proposedMatches[0] = { ...n.catalogAlignment.proposedMatches[0], ...patch }; n.catalogAlignment.proposalStatus = 'ambiguous'
      const h = mountCatalog(n, 'ambiguous'); await settle(); await h.click('data-picker-option', n.id)
      assert.equal(h.value.baselineKey, ''); assert.equal(h.get('data-catalog-confirm-match').props.disabled, true); assert.match(text(h.get('data-catalog-proposal')), issue); assert.equal(h.events.length, 0); h.app.unmount()
    }
  })
  await check('unavailable semantic work does not become an unmatched or exact-name success', async () => {
    const n = copy(node); n.nameZh = '是否包含基础工程'; n.catalogAlignment.proposalStatus = 'unavailable'; n.catalogAlignment.proposedMatches = []
    const h = mountCatalog(n, 'unavailable'); await settle(); await h.click('data-picker-option', n.id)
    assert.match(text(h.get('data-catalog-proposal-state')), /未取得|未.*建议/); assert.equal(h.value.baselineKey, ''); assert.equal(h.events.length, 0); h.app.unmount()
  })
  await check('correcting current units and optionality unlocks the retained proposal without rerunning matching', async () => {
    const n = copy(node); n.type = { kind: 'number' }; n.catalogAlignment.proposalStatus = 'ambiguous'; n.catalogAlignment.proposedMatches[0] = { ...n.catalogAlignment.proposedMatches[0], baselineKey: 'durationMonths', compatible: false, issues: ['unit_mismatch', 'optional_mismatch'] }
    const h = mountCatalog(n, 'ambiguous'); await settle(); await h.click('data-picker-option', n.id); await h.click('data-catalog-view-proposal', 'durationMonths')
    h.get('data-catalog-reason').props['onUpdate:modelValue']('人工核对后补齐月单位及选填性'); await settle(); assert.equal(h.get('data-catalog-confirm-match').props.disabled, true)
    const beforeProposal = copy(h.state.variables[0].catalogAlignment.proposedMatches); h.state.variables[0].type.unit = 'months'; h.state.variables[0].optional = true; await settle()
    const current = model.baselineProposals(h.state.variables[0], fields, h.state)[0]; assert.equal(current.compatible, true); assert.deepEqual(Array.from(current.issues), []); assert.deepEqual(Array.from(current.originalIssues), ['unit_mismatch', 'optional_mismatch'])
    assert.equal(h.value.baselineKey, 'durationMonths'); assert.equal(h.get('data-catalog-confirm-match').props.disabled, false); await h.click('data-catalog-confirm-match'); assert.equal(h.events.length, 1); assert.deepEqual(copy(h.state.variables[0].catalogAlignment.proposedMatches), beforeProposal); h.app.unmount()
  })
  await check('a top-level fixed definition under an atomic parent requires an explicit reviewed scope', async () => {
    const n = copy(node); n.parentId = 'atomic-record'; n.catalogAlignment.proposalStatus = 'ambiguous'; n.catalogAlignment.proposedMatches[0].compatible = false; n.catalogAlignment.proposedMatches[0].issues = ['parent_mismatch']
    assert.equal(model.compatibleBaseline(n, fields[0], state([n]), ''), false); assert.equal(model.compatibleBaseline(n, fields[0], state([n]), 'Distinct package'), true)
    const h = mountCatalog(n, 'ambiguous'); await settle(); await h.click('data-picker-option', n.id); await h.click('data-catalog-view-proposal', 'foundationIncluded')
    h.get('data-catalog-reason').props['onUpdate:modelValue']('人工核对为独立分包适用范围'); await settle(); assert.equal(h.get('data-catalog-confirm-match').props.disabled, true)
    h.get('data-catalog-scope').props['onUpdate:modelValue']('Distinct package'); await settle(); assert.equal(h.get('data-catalog-confirm-match').props.disabled, false); await h.click('data-catalog-confirm-match'); assert.equal(h.events[0][1].scope, 'Distinct package'); assert.equal(h.state.variables[0].parentId, 'atomic-record'); h.app.unmount()
  })
  await check('alignment decisions preserve post-atomic proposals without changing the atom or its executable graph', async () => {
    const graph = { kind: 'consense-template-graph-v1', graph: state([copy(node)]), sources: [source], selectedDocuments: { NTT: source.id } }, revision = { id: 'CATALOG-PROPOSAL', graph, status: 'DRAFT', sourceStale: false }, writes = []
    const native = new (require('jsdom').JSDOM)('').window
    const api = { baselineCatalog: async () => copy(catalog), reviewHead: async () => ({ draft: copy(revision), draftRevisionId: revision.id, sources: [source] }), reviewReading: async () => ({ ...source, paragraphs: [], blocks: [], guidanceNotes: [], sourceMembers: [], markerCandidates: [], groups: [], conditions: [], issues: [] }), reviewPreview: async () => { throw Error('SYNTHETIC no PDF') }, reviewSave: async (_project, next) => { writes.push(copy(next)); return { ...copy(revision), graph: copy(next) } } }
    const parent = loadVue(path.join(__dirname, '../src/components/DraftingSourceReview.vue'), { boundaries: { '@/api': { draftingApi: api }, '@/api/source-locations': { reviewSourceBindings: async () => ({ bindings: [] }) }, './AppModal.vue': { default: { render: () => null } }, './DraftingPdfPreview.vue': { default: { render: () => null } } }, globals: { structuredClone, window: native, document: native.document, Element: native.Element, URL } })
    const host = renderer(), editor = vue.ref(), app = host.createApp({ render: () => vue.h(parent.default, { projectId: 'CATALOG-PROPOSAL-CONTROLS', locale: 'zh', ref: editor }) }); app.mount(host.root); await settle()
    const get = (attr, key) => find(host.root, n => key === undefined ? n.props?.[attr] !== undefined : n.props?.[attr] === key)
    const click = async (attr, key) => { const n = get(attr, key); assert.ok(n, attr); assert.notEqual(n.props.disabled, true); await n.props.onClick(); await settle() }
    await click('data-review-catalog'); await click('data-picker-option', node.id); get('data-catalog-reason').props['onUpdate:modelValue']('人工确认与固定基础工程变量相同'); await settle(); await click('data-catalog-confirm-match'); await editor.value.save()
    assert.equal(writes.length, 1); const aligned = writes[0].graph.variables[0]
    assert.equal(aligned.catalogAlignment.status, 'matched'); assert.equal(aligned.catalogAlignment.proposalMethod, 'post_atomic_semantic'); assert.deepEqual(aligned.catalogAlignment.proposedMatches, node.catalogAlignment.proposedMatches)
    for (const key of ['id', 'nameZh', 'nameEn', 'type', 'options', 'sourceAnchors', 'correction', 'editTargets', 'relatedInputIds', 'relations']) assert.deepEqual(aligned[key], node[key], key + ' remains the atomic source graph')
    assert.equal(aligned.value, undefined); app.unmount()
  })
  console.log(`${checks - failures.length}/${checks} post-atomic baseline proposal checks passed`); if (failures.length) process.exitCode = 1
})().catch(error => { console.error(error); process.exitCode = 1 })
