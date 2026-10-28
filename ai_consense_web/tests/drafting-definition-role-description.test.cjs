const assert = require('node:assert/strict')
const path = require('node:path')
const { test } = require('node:test')
const vue = require('vue')
const { loadVue } = require('./helpers/load-vue.cjs')
const { renderer, find, text, settle } = require('./helpers/render-controls.cjs')
const copy = value => JSON.parse(JSON.stringify(value))
const source = { id: '41', sourceDocumentId: 41, fileKey: 'NTT', fileName: 'TEST.docx', format: 'docx', sourceSha256: 'a'.repeat(64), canEdit: true, evidenceStatus: 'bound', analysisRevision: 'test' }
const node = (id, role = 'input') => ({ id, nameZh: id, nameEn: id, type: { kind: 'boolean' }, role: { kind: role }, status: 'pending', docIds: ['41'], definitionKind: 'discovered', options: [], sourceAnchors: [], sourceUnitIds: [], editTargets: [], relatedInputIds: [], relations: [], targets: [], evidence: [] })

test('a manually linked derived value has its own explanation without changing source-check classification or confirmation controls', async () => {
  for (const locale of ['zh', 'en']) {
    let saved, writes = 0
    const api = {
      reviewHead: async () => ({ currentRevisionId: null, draftRevisionId: saved.id, current: null, draft: copy(saved), sources: [copy(source)] }),
      reviewReading: async () => ({ ...copy(source), paragraphs: [], blocks: [], guidanceNotes: [], sourceMembers: [], markerCandidates: [], groups: [], conditions: [], issues: [] }),
      reviewPreview: async () => { throw Error('TEST preview unavailable') },
      reviewRevisions: async () => [], reviewCandidates: async () => ({ variables: [] }),
      reviewSave: async () => { writes++; throw Error('This display check must not save') }
    }
    const loaded = loadVue(path.join(__dirname, '../src/components/DraftingSourceReview.vue'), { boundaries: {
      '@/api': { draftingApi: api }, '@/api/source-locations': { reviewSourceBindings: async () => { throw Error('Unexpected bindings') } },
      './AppModal.vue': { default: { render: () => null } }, './DraftingPdfPreview.vue': { default: { render: () => null } }
    }, globals: { structuredClone, Blob, URL, window: { addEventListener() {}, removeEventListener() {} }, document: { documentElement: {}, getElementById() { return null } } } })
    const { createReviewReducer } = loaded.loadLocal(path.join(__dirname, '../src/drafting/source-review/reducer.js'))
    const root = { ...node('bq-format'), type: { kind: 'choice' }, options: [{ value: 'L10Pro' }, { value: 'Hardcopy' }], catalogAlignment: { status: 'matched', confirmed: true, baselineKey: 'electronicTendering', baselineVersion: 'test' } }
    const initial = { variables: [{ ...node('local-branch'), catalogAlignment: { status: 'unreviewed', confirmed: false } }, root], events: [], sourceReviews: {}, nextId: 1 }
    const converted = createReviewReducer({ sources: [source] }).reduce(initial, { type: 'CONVERT_FACT_PROJECTION', id: 'local-branch', rootId: root.id, baselineKey: 'electronicTendering', branchValue: 'L10Pro', branchPolarity: true, exhaustiveConfirmed: true, origin: 'manual', reason: 'Checked the shared BQ choice and local branch' })
    assert.equal(converted.variables[0].role.kind, 'derived')
    assert.equal(converted.variables[0].correction.branches.length, 2)
    converted.variables.push(node('check', 'verification'), { ...node('seed', 'derived'), definitionKind: 'source_seed' }, { ...node('fixed', 'fixed'), type: { kind: 'text' }, fixedValue: 'Fixed text' })
    saved = { id: 'draft-1', status: 'DRAFT', sourceStale: false, graph: { kind: 'consense-template-graph-v1', graph: converted, sources: [source], selectedDocuments: { NTT: '41' } } }
    const before = JSON.stringify(saved), editor = vue.ref(), host = renderer()
    const app = host.createApp({ setup: () => () => vue.h(loaded.default, { projectId: `ROLE-DESCRIPTION-${locale}`, locale, ref: editor }) })
    app.mount(host.root)
    try {
      await settle()
      for (const id of ['local-branch', 'bq-format', 'check', 'seed', 'fixed']) {
        await editor.value.locateQuestion(id, saved.id); await settle()
        const section = find(host.root, item => item.props?.['data-definition-review'] !== undefined)
        assert.ok(section, id)
        const description = text(section), sourceCheck = locale === 'en' ? 'This is a review check' : '该项是人工核查事项'
        assert.equal(description.includes(sourceCheck), id === 'check' || id === 'seed', `${id}: only actual source checks receive the source-check explanation`)
        assert.equal(!!find(section, item => item.props?.['data-review-confirm-definition'] !== undefined), id === 'bq-format', 'Existing input-definition confirmation eligibility remains unchanged')
        if (id === 'local-branch') assert.ok(description.includes(locale === 'en' ? 'calculated from other variable values and branch rules' : '由其他变量的取值和分支规则计算'))
      }
      assert.equal(writes, 0)
      assert.equal(JSON.stringify(saved), before, 'Reading role explanations must not rewrite graph definitions or branches')
    } finally { app.unmount() }
  }
})
