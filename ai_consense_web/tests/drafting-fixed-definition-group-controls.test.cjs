const assert = require('node:assert/strict')
const path = require('node:path')
const vue = require('vue')
const { loadVue } = require('./helpers/load-vue.cjs')
const { renderer, find, text, settle } = require('./helpers/render-controls.cjs')

const copy = value => JSON.parse(JSON.stringify(value))
const source = { id: '41', sourceDocumentId: 41, docId: '41', editionId: 'NTT-TEST', fileKey: 'NTT', fileName: 'TEST template.docx', format: 'docx', sourceSha256: 'a'.repeat(64), evidenceStatus: 'bound', parseStatus: 'PARSED', canEdit: true }
const variable = id => ({ id, nameZh: id, nameEn: id, docIds: ['41'], type: { kind: 'boolean' }, role: { kind: 'input' }, status: 'pending', options: [], sourceAnchors: [], sourceUnitIds: [], editTargets: [], relatedInputIds: [], relations: [], targets: [], evidence: [] })
const fixed = { ...variable('fixed-bq'), definitionKind: 'fixed_catalog', definitionReview: { confirmed: false }, catalogAlignment: { status: 'matched', confirmed: false, baselineKey: 'bqIssueFormat', baselineVersion: 'test' } }
const custom = { ...variable('custom'), definitionReview: { confirmed: true } }
const guidance = { ...variable('guidance'), definitionKind: 'source_seed', role: { kind: 'verification' } }
const target = { ...variable('edit-object'), role: { kind: 'system' } }
const child = { ...variable('fixed-detail'), parentId: fixed.id }
const graph = { kind: 'consense-template-graph-v1', graph: { variables: [fixed, custom, guidance, target, child, variable('unreviewed-custom')], events: [], sourceReviews: {}, nextId: 1, blank: false, guidanceMode: 'minimum' }, sources: [source], selectedDocuments: { NTT: '41' } }
let saved = { id: 'draft-1', status: 'DRAFT', sourceStale: false, graph: copy(graph), definitionGraphReview: { confirmed: false, valid: false, confirmedFieldIds: [], pendingFieldIds: [fixed.id, custom.id], fixedCandidateFieldIds: [], currentDefinitionReviewFieldIds: [custom.id], definitionGroupReadyFieldIds: [custom.id], unresolvedRelationCount: 0, baselineReuseSupported: true, completeBaselineReuseSupported: true } }
const confirmations = [], writes = [], catalogReads = []
const api = {
  reviewHead: async () => ({ currentRevisionId: null, draftRevisionId: saved.id, current: null, draft: copy(saved), sources: copy(saved.graph.sources) }),
  reviewReading: async () => ({ ...source, paragraphs: [], blocks: [], guidanceNotes: [], sourceMembers: [], markerCandidates: [], groups: [], conditions: [], issues: [] }),
  reviewPreview: async () => { throw Error('TEST preview unavailable') },
  reviewCandidates: async () => ({ variables: [] }),
  baselineCatalog: async (...args) => { catalogReads.push(args); return { ruleVersion: 'test', groups: [] } },
  reviewSave: async (...args) => { writes.push(args); throw Error('Group confirmation must not manufacture a local review save') },
  reviewConfirmDefinitions: async (...args) => {
    confirmations.push(copy(args)); saved = { ...copy(saved), id: 'draft-' + (confirmations.length + 1), definitionGraphReview: { ...saved.definitionGraphReview, valid: true, confirmedFieldIds: copy(args[5]), pendingFieldIds: [] } }
    return copy(saved)
  }
}
const Modal = { props: ['open'], setup: (props, { slots }) => () => props.open ? vue.h('section', slots.default?.()) : null }
const nativeWindow = new (require('jsdom').JSDOM)('').window
const loaded = loadVue(path.join(__dirname, '../src/components/DraftingSourceReview.vue'), { boundaries: {
  '@/api': { draftingApi: api }, '@/api/source-locations': { reviewSourceBindings: async () => { throw Error('TEST locations unavailable') } }, './AppModal.vue': { default: Modal }, './DraftingPdfPreview.vue': { default: { render: () => null } }
}, globals: { structuredClone, Blob, URL, crypto: require('node:crypto').webcrypto, window: nativeWindow, document: nativeWindow.document, Element: nativeWindow.Element } })
const editor = vue.ref(), host = renderer(), app = host.createApp({ setup: () => () => vue.h(loaded.default, { ref: editor, projectId: 'TEST-FIXED-DEFINITION-GROUP', locale: 'en' }) })
const control = (attribute, value) => find(host.root, node => value === undefined ? node.props?.[attribute] !== undefined : node.props?.[attribute] === value)
const update = async (attribute, value) => { const node = control(attribute); assert.ok(node, attribute); node.props['onUpdate:modelValue'](value); await settle() }
const choose = async ids => { const node = control('data-definition-group-choice', ids[0]); assert.ok(node, ids[0]); node.props['onUpdate:modelValue'](ids); await settle() }
const confirm = async () => { const node = control('data-definition-graph-confirm'); assert.equal(node.props.disabled, false); await node.props.onClick(); await settle() }

;(async () => {
  app.mount(host.root); await settle(); await editor.value.ready()
  assert.ok(control('data-definition-group-choice', 'custom'))
  assert.equal(control('data-definition-group-choice', fixed.id), undefined, 'The frontend cannot infer eligibility from an unconfirmed matched key')
  await choose(['custom']); await update('data-definition-reuse-edition', true); await update('data-definition-complete-edition', true)

  saved.graph.graph.fixedRuleBaseline = { kind: 'fixed_catalog', coverage: 'fixed_rules_with_unresolved_scopes' }
  saved.graph.graph.baselineRestoration = { kind: 'fixed_catalog_candidates', status: 'partial_requires_review', projectAnswersImported: false }
  saved.definitionGraphReview.fixedCandidateFieldIds = [fixed.id, guidance.id, target.id, child.id]
  saved.definitionGraphReview.definitionGroupReadyFieldIds = [fixed.id, custom.id, guidance.id, target.id, child.id]
  await editor.value.refresh(false); await settle()
  assert.ok(control('data-definition-group-choice', fixed.id), 'Server-verified pending fixed candidates are available without individually confirming them first')
  for (const id of [guidance.id, target.id, child.id, 'unreviewed-custom']) assert.equal(control('data-definition-group-choice', id), undefined, id + ' must not become a root variable choice')
  assert.ok(control('data-restored-fixed-candidates')); assert.ok(control('data-definition-fixed-candidate', fixed.id))
  assert.equal(control('data-definition-reuse-edition').props.disabled, true, 'A descriptor blocks reuse even when only a custom root was selected')
  assert.equal(control('data-definition-reuse-edition').checked, false, 'An earlier reuse choice is cleared when a fixed-rule draft loads')
  assert.equal(control('data-definition-complete-edition'), undefined)
  assert.match(text(control('data-definition-fixed-reuse-blocked')), /not registered as a fully reviewed baseline/)
  await choose([fixed.id]); assert.equal(control('data-definition-graph-confirm').props.disabled, true, 'Selection alone does not confirm anything')
  await update('data-definition-graph-reason', 'Reviewed the fixed meaning and prerequisite'); assert.equal(control('data-definition-graph-confirm').props.disabled, true)
  await update('data-definition-graph-acknowledged', true)
  assert.equal(saved.graph.graph.variables.find(item => item.id === fixed.id).catalogAlignment.confirmed, false)
  assert.equal(writes.length, 0); assert.equal(confirmations.length, 0)
  // Even a stale control update cannot smuggle reuse flags into the confirmation request.
  await update('data-definition-reuse-edition', true); await update('data-definition-complete-edition', true); await confirm()
  assert.deepEqual(confirmations[0], ['TEST-FIXED-DEFINITION-GROUP', 'draft-1', null, 'Reviewed the fixed meaning and prerequisite', true, [fixed.id]])
  assert.equal(writes.length, 0, 'No per-node confirmed flags or empty-range baseline are written by the frontend')

  delete saved.graph.graph.fixedRuleBaseline; delete saved.graph.graph.baselineRestoration
  saved.definitionGraphReview = { ...saved.definitionGraphReview, valid: false, confirmedFieldIds: [fixed.id], pendingFieldIds: ['custom'], fixedCandidateFieldIds: [] }
  await editor.value.refresh(false); await settle(); await choose(['custom'])
  assert.equal(control('data-definition-reuse-edition').props.disabled, false)
  await update('data-definition-graph-reason', 'Reviewed the custom edition graph'); await update('data-definition-graph-acknowledged', true)
  await update('data-definition-reuse-edition', true); await update('data-definition-complete-edition', true); await confirm()
  assert.deepEqual(confirmations[1], ['TEST-FIXED-DEFINITION-GROUP', 'draft-2', null, 'Reviewed the custom edition graph', true, ['custom'], true, true], 'Ordinary custom graph reuse keeps its existing API contract')

  const manualMatched = { ...variable('manual-matched'), manual: true, status: 'changed', catalogAlignment: { status: 'matched', confirmed: true, baselineKey: 'electronicTendering', baselineVersion: 'test' } }
  const manualNew = { ...variable('manual-new'), manual: true, definitionReview: { confirmed: true, basis: 'new-reviewed' }, catalogAlignment: { status: 'new', confirmed: true, reason: 'Independent local fact' } }
  const staleMatches = ['unreviewed', 'matched'].map(status => ({ ...variable('stale-' + status), manual: true, status: 'changed', definitionReview: { confirmed: true, basis: 'old-before-merge' }, catalogAlignment: { status, confirmed: false, ...(status === 'matched' ? { baselineKey: 'electronicTendering', baselineVersion: 'test' } : {}) } }))
  const pdfSource = { ...source, id: '43', sourceDocumentId: 43, docId: '43', editionId: 'SCC-PDF-TEST', fileKey: 'SCC', format: 'pdf', sourceSha256: 'b'.repeat(64) }
  const pdfReviewed = { ...variable('pdf-reviewed'), docIds: ['43'], manual: true, definitionReview: { confirmed: true, basis: 'pdf-reviewed' }, catalogAlignment: { status: 'unreviewed', confirmed: false }, pdfReferenceAlignment: { confirmed: true, sourceSha256: pdfSource.sourceSha256 } }
  const reducer = loaded.loadLocal(path.join(__dirname, '../src/drafting/source-review/reducer.js')).createReviewReducer({ sources: [source] })
  const beforeLink = { ...copy(graph.graph), variables: [{ ...variable('linked-manual'), manual: true, definitionReview: { confirmed: true, basis: 'before-link' } }, { ...copy(manualMatched), id: 'linked-matched', definitionReview: { confirmed: true, basis: 'before-link' } }] }
  const afterLink = beforeLink.variables.reduce((state, item) => reducer.reduce(state, { type: 'LINK', id: item.id, anchor: { docId: '41', text: 'Additional current proof' } }), beforeLink)
  const beforeOriginEdit = { ...copy(graph.graph), variables: [variable('origin-manual'), { ...copy(manualNew), id: 'origin-new' }, { ...copy(manualMatched), id: 'origin-matched' }].map(item => ({ ...item, manual: true, definitionOrigin: { kind: 'manual', reason: 'Original definition evidence' }, definitionReview: { confirmed: true, basis: 'before-origin-edit' } })) }
  const afterOriginEdit = beforeOriginEdit.variables.reduce((state, item) => reducer.reduce(state, { type: 'EDIT', id: item.id, values: { definitionOrigin: { kind: 'manual', reason: 'Corrected definition evidence' } } }), beforeOriginEdit)
  saved.graph.graph.variables.push(manualMatched, manualNew, ...staleMatches, pdfReviewed, ...afterLink.variables, ...afterOriginEdit.variables); saved.graph.sources.push(pdfSource); saved.graph.selectedDocuments.SCC = '43'
  saved.definitionGraphReview = { ...saved.definitionGraphReview, confirmedFieldIds: [fixed.id, custom.id], pendingFieldIds: [manualMatched.id, manualNew.id, ...staleMatches.map(item => item.id), pdfReviewed.id, ...afterLink.variables.map(item => item.id), ...afterOriginEdit.variables.map(item => item.id)], fixedCandidateFieldIds: [], currentDefinitionReviewFieldIds: [manualNew.id, pdfReviewed.id], definitionGroupReadyFieldIds: [manualMatched.id, manualNew.id, pdfReviewed.id, 'linked-matched', 'origin-matched'] }
  await editor.value.refresh(false); await settle()
  assert.ok(control('data-definition-group-choice', manualMatched.id), 'A manually merged primary with its retained catalog identity can be reviewed as a group')
  assert.ok(control('data-definition-group-choice', manualNew.id), 'Reviewed new manual facts remain available')
  assert.ok(control('data-definition-group-choice', pdfReviewed.id), 'The separate PDF correspondence satisfies the existing alignment prerequisite without a Word catalog match')
  assert.equal(control('data-definition-group-choice', 'linked-manual'), undefined, 'A new source requires a fresh definition review for an unaligned manual fact')
  assert.ok(control('data-definition-group-choice', 'linked-matched'), 'A retained fixed identity permits explicit group confirmation of its expanded sources')
  for (const id of ['origin-manual', 'origin-new']) assert.equal(control('data-definition-group-choice', id), undefined, 'Changed manual definition evidence cannot reuse its old review basis: ' + id)
  assert.ok(control('data-definition-group-choice', 'origin-matched'), 'An unchanged matched identity still permits explicit group confirmation after the definition reason changes')
  for (const item of staleMatches) assert.equal(control('data-definition-group-choice', item.id), undefined, 'A stale definition flag cannot bypass alignment review: ' + item.id)
  assert.match(text(control('data-definition-alignment-pending')), /variables need alignment review/)
  const openAlignment = control('data-definition-open-alignment'); assert.equal(openAlignment.props.disabled, false); await openAlignment.props.onClick(); await settle()
  assert.ok(control('data-catalog-review'), 'The blocked cohort provides the existing alignment UI'); assert.equal(catalogReads.length, 1); assert.equal(writes.length, 0)
  await choose([manualMatched.id, 'origin-matched']); await update('data-definition-graph-reason', 'Reviewed the merged sources and corrected definition evidence without changing the fixed meaning'); await update('data-definition-graph-acknowledged', true); await confirm()
  assert.deepEqual(confirmations[2][5], [manualMatched.id, 'origin-matched'], 'Manual retained matches submit through the normal group confirmation API after source or origin changes')
  await choose([manualNew.id]); await update('data-definition-graph-reason', 'Review current local fact'); await update('data-definition-graph-acknowledged', true)
  saved.graph.graph.variables.find(item => item.id === manualNew.id).catalogAlignment.confirmed = false
  await editor.value.refresh(false); await settle()
  assert.equal(control('data-definition-group-choice', manualNew.id), undefined)
  assert.equal(control('data-definition-graph-confirm').props.disabled, true, 'Refreshing a no-longer-aligned selection removes its stale checkbox selection')
  assert.equal(writes.length, 0, 'Cohort qualification never manufactures a local definition or alignment confirmation')

  const reviewed = item => ({ ...item, definitionReview: { confirmed: true, basis: 'historical-basis' } })
  const historicalNew = reviewed({ ...copy(manualNew), id: 'historical-new' }), historicalManual = reviewed(variable('historical-manual'))
  const matchedCurrentChild = reviewed({ ...copy(manualMatched), id: 'matched-current-child' }), matchedStaleChild = reviewed({ ...copy(manualMatched), id: 'matched-stale-child' })
  const fixedCurrentChild = { ...copy(fixed), id: 'fixed-current-child' }, fixedStaleChild = { ...copy(fixed), id: 'fixed-stale-child' }, fixedLeaf = { ...copy(fixed), id: 'fixed-leaf' }
  const matchedTree = { ...copy(manualMatched), id: 'all-matched-tree' }
  const treeNodes = [historicalNew, historicalManual, matchedCurrentChild, matchedStaleChild, fixedCurrentChild, fixedStaleChild, fixedLeaf, matchedTree,
    reviewed({ ...copy(manualNew), id: 'current-new-child', parentId: matchedCurrentChild.id }),
    reviewed({ ...copy(manualNew), id: 'stale-new-child', parentId: matchedStaleChild.id }),
    { ...copy(fixed), id: 'fixed-current-detail', parentId: fixedCurrentChild.id },
    reviewed({ ...variable('fixed-stale-detail'), parentId: fixedStaleChild.id }),
    { ...copy(manualMatched), id: 'all-matched-detail', parentId: matchedTree.id }]
  saved.graph.graph.variables.push(...treeNodes)
  saved.definitionGraphReview.fixedCandidateFieldIds = [fixedCurrentChild.id, fixedStaleChild.id, fixedLeaf.id]
  saved.definitionGraphReview.definitionGroupReadyFieldIds.push(matchedCurrentChild.id, fixedCurrentChild.id, fixedLeaf.id, matchedTree.id)
  await editor.value.refresh(false); await settle()
  for (const id of [historicalNew.id, historicalManual.id, matchedStaleChild.id, fixedStaleChild.id]) assert.equal(control('data-definition-group-choice', id), undefined, 'A historical confirmed flag or matched/fixed parent cannot bypass stale child review: ' + id)
  for (const id of [matchedCurrentChild.id, fixedCurrentChild.id, matchedTree.id]) assert.ok(control('data-definition-group-choice', id), 'The server can distinguish a preparable parent with eligible children: ' + id)
  assert.equal(saved.definitionGraphReview.currentDefinitionReviewFieldIds.includes(matchedCurrentChild.id), false, 'A stale matched parent is not mislabeled as already current')
  await choose([matchedCurrentChild.id]); await update('data-definition-graph-reason', 'Review current child and explicitly restamp the matched parent'); await update('data-definition-graph-acknowledged', true)
  saved.definitionGraphReview.definitionGroupReadyFieldIds = saved.definitionGraphReview.definitionGroupReadyFieldIds.filter(id => id !== matchedCurrentChild.id)
  await editor.value.refresh(false); await settle()
  assert.equal(control('data-definition-group-choice', matchedCurrentChild.id), undefined)
  assert.equal(control('data-definition-graph-confirm').props.disabled, true, 'A changed server eligibility projection prunes the old group selection')

  delete saved.definitionGraphReview.currentDefinitionReviewFieldIds; delete saved.definitionGraphReview.definitionGroupReadyFieldIds
  await editor.value.refresh(false); await settle()
  for (const id of [historicalNew.id, historicalManual.id, matchedCurrentChild.id, matchedStaleChild.id, fixedCurrentChild.id, fixedStaleChild.id]) assert.equal(control('data-definition-group-choice', id), undefined, 'An older service cannot establish the current basis for a manual definition or mixed subtree: ' + id)
  for (const id of ['linked-matched', matchedTree.id, fixedLeaf.id]) assert.ok(control('data-definition-group-choice', id), 'An older service retains explicit confirmation for an entirely matched tree or fixed leaf: ' + id)
  saved.sourceStale = true
  await editor.value.refresh(false); await settle()
  assert.equal(control('data-definition-group-choice'), undefined, 'A stale source revision exposes no group-confirmable definitions')
  assert.equal(writes.length, 0, 'Eligibility projection and fallback remain read-only')
  app.unmount()
  console.log('PASS: pending fixed definitions use explicit group confirmation; unknown ranges cannot enter edition reuse; ordinary custom reuse is preserved')
})().catch(error => { console.error(error); process.exitCode = 1 })
