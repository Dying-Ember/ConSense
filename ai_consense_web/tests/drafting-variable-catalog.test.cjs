const assert = require('node:assert/strict')
const path = require('node:path')
const vue = require('vue')
const { loadVue } = require('./helpers/load-vue.cjs')
const { renderer, find, text, settle } = require('./helpers/render-controls.cjs')
const copy = value => JSON.parse(JSON.stringify(value))
const label = (zh, en = zh) => ({ zhHans: zh, zhHant: zh, en })
const designField = require(path.join(__dirname,'../../ai_consense_service/src/main/resources/drafting/input-catalog.json')).groups.flatMap(group=>group.fields).find(field=>field.key==='designResponsibilities')
const catalog = { ruleVersion: 'authored-baseline-2', groups: [{ id: 'contract', label: label('合约'), fields: [{ key: 'contractPeriodMonths', kind: 'number', optional: true, unit: 'months', label: label('合约工期（月）', 'Contract period (months)') }, { key: 'foundationIncluded', kind: 'boolean', options:[{value:true,label:label('是')},{value:false,label:label('否')}], label: label('包含基础工程', 'Includes foundation works') }, designField, { key: 'contractTitle', kind: 'text', label: label('系统合约名称') }] }] }
const source = { id: '41', docId: 'ntt', editionId: 'NTT-test', fileName: 'Authored NTT.docx', fileKey: 'NTT', format: 'docx', sourceSha256: 'a'.repeat(64), evidenceStatus: 'bound', analysisRevision: 'authored-analysis', canEdit: true }
const initial = { kind: 'consense-template-graph-v1', graph: { variables: [], events: [], sourceReviews: {}, nextId: 1, blank: false, guidanceMode: 'minimum' }, sources: [], selectedDocuments: {} }
let revision = { id: 'authored-draft', graph: initial, status: 'DRAFT', sourceStale: false }, currentId = null, failCatalog = true, failHead = false
const writes = [], confirmations = [], calls = []
const api = {
  reviewHead: async () => { if(failHead) throw Error('Transient HTTP 503'); return { currentRevisionId: currentId, draftRevisionId: revision.id, draft: copy(revision), sources: [] } },
  reviewReading: async () => ({ ...source, paragraphs: [], guidanceNotes: [], sourceMembers: [], markerCandidates: [], groups: [], conditions: [], issues: [], blocks: [] }),
  reviewPreview: async () => { throw Error('Authored fixture has no PDF') },
  baselineCatalog: async () => { calls.push('baseline'); if (failCatalog) throw Error('HTTP 404'); return copy(catalog) },
  reviewSave: async (project, graph, expectedCurrent, expectedDraft) => { assert.equal(expectedCurrent,currentId); assert.equal(expectedDraft,revision.id); writes.push(copy(graph)); revision = { ...revision, id: 'saved-' + writes.length, graph: copy(graph) }; return copy(revision) },
  reviewConfirm: async (...args) => { confirmations.push(args); currentId = args[1] },
  reviewCandidates: async () => ({ variables: [] })
}
const nativeWindow = new (require('jsdom').JSDOM)('').window
const loaded = loadVue(path.join(__dirname, '../src/components/DraftingSourceReview.vue'), { boundaries: { '@/api': { draftingApi: api }, '@/api/source-locations': { reviewSourceBindings: async () => ({ bindings: [] }) }, './AppModal.vue': { default: { setup: () => () => null } }, './DraftingPdfPreview.vue': { default: { setup: () => () => null } } }, globals: { structuredClone, window: nativeWindow, document: nativeWindow.document, Element: nativeWindow.Element, URL } })
const model = loaded.loadLocal(path.join(__dirname,'../src/drafting/variable-catalog.ts'))
const review = loaded.loadLocal(path.join(__dirname,'../src/drafting/source-review-state.ts'))
const reducer = loaded.loadLocal(path.join(__dirname,'../src/drafting/source-review/reducer.js')).createReviewReducer({ sources: [source] })
const host = renderer(), editor = vue.ref(), props = { projectId: 'CATALOG-AUTHORED', locale: 'zh' }
const mount = () => { const app = host.createApp({ setup: () => () => vue.h(loaded.default, { ...props, ref: editor }) }); app.mount(host.root); return app }
let app = mount()
const control = (key, value) => find(host.root, node => value === undefined ? node.props?.[key] !== undefined : node.props?.[key] === value)
const set = async (key, value) => { const node = control(key); assert.ok(node,key); if (node.props['onUpdate:modelValue']) node.props['onUpdate:modelValue'](value); else node.props.onInput({ target: { value } }); await settle() }
const click = async (key,value) => { const node=control(key,value); assert.ok(node,key); await node.props.onClick(); await settle() }
;(async () => {
  const fields = model.baselineVariables(catalog)
  assert.deepEqual(Array.from(fields, field=>field.baselineKey), ['contractPeriodMonths','foundationIncluded','designResponsibilities','designResponsibilities.component','designResponsibilities.design','designResponsibilities.execution','designResponsibilities.scope'])
  const node = { id:'n',optional:true,type:{kind:'number',unit:'days'},options:[],nameZh:'合约工期（月）',nameEn:'Contract period (months)' }
  assert.equal(model.compatibleBaseline(node,fields[0],initial.graph),false,'Different units cannot be matched by name')
  node.type.unit='months'; assert.equal(model.compatibleBaseline(node,fields[0],initial.graph),true)
  assert.equal(model.suggestedBaselines(node,fields,initial.graph).length,1)
  assert.equal(model.compatibleBaseline({type:{kind:'boolean'},options:[]},fields[1],initial.graph),true,'Baseline Boolean choices do not prevent matching an identified Boolean')
  assert.equal(model.compatibleBaseline({...node,optional:false},fields[0],initial.graph),false,'Optionality must match')
  const child=model.baselineNode(fields[3],catalog.ruleVersion,'人工确认','parent')
  assert.equal(model.compatibleBaseline(child,fields[3],{variables:[{id:'parent',catalogAlignment:{baselineKey:'designResponsibilities'}}]}),true)
  assert.equal(model.compatibleBaseline(child,fields[3],{variables:[]}),false)

  await settle(); assert.equal(calls.length,0,'Baseline is only loaded on request')
  await click('data-review-catalog'); assert.match(text(host.root),/基准目录暂时无法读取/)
  assert.equal(control('data-review-new-variable').props.disabled,false,'Unavailable baseline does not disable manual editing')
  await click('data-review-catalog'); failCatalog=false
  await click('data-review-new-variable')
  await set('data-condition-new-name-zh','实际合约工期')
  await set('data-condition-new-kind','number')
  await set('data-new-variable-unit','months'); await set('data-new-variable-optional',true)
  await set('data-new-variable-reason','用户补充遗漏的实际工期变量')
  await click('data-condition-create-input')
  assert.ok(control('data-source-question-drawer'),'Manual variable editor is visible without any template source')
  await click('data-review-confirm-item')
  await click('data-review-save')
  const manual = revision.graph.graph.variables[0]
  assert.equal(manual.status,'confirmed'); assert.equal(manual.type.unit,'months'); assert.equal(manual.missingSource,true)
  assert.equal(manual.docIds.length,0); assert.equal(manual.sourceAnchors.length,0); assert.equal(manual.editTargets.length,0)
  assert.equal(manual.guidance.zh,'人工新增变量定义：用户补充遗漏的实际工期变量','A source-free definition never claims it was added from original wording')
  assert.equal(review.reviewProblem(manual,revision.graph.graph),null,'A reasoned independent manual definition can be confirmed')
  const unsafe={...manual,editTargets:[{action:'fill'}]}; assert.match(review.reviewProblem(unsafe,revision.graph.graph),/准确原文/,'A manual reason cannot authorize source amendments')
  app.unmount(); app=mount(); await settle()
  await editor.value.locateQuestion(manual.id,revision.id); await settle()
  await click('data-review-catalog'); await click('data-catalog-tab','new')
  await click('data-picker-option',manual.id)
  await click('data-picker-option','contractPeriodMonths')
  await set('data-catalog-reason','实际工期，单位月，定义与基准一致')
  app.unmount(); failHead=true; app=mount(); await settle()
  assert.ok(control('data-source-question-drawer'),'A cached no-source graph remains visible during a backend failure')
  assert.match(text(host.root),/Transient HTTP 503/)
  app.unmount(); failHead=false; app=mount(); await settle()
  assert.equal(control('data-catalog-reason').value,'实际工期，单位月，定义与基准一致','An unfinished alignment review survives remount')
  await click('data-catalog-confirm-match')
  assert.equal(control('data-catalog-tab','matched').props['aria-pressed'],true,'Confirmed matching stays in its corresponding review tab')
  assert.equal(control('data-picker-retained'),undefined,'A valid mapping does not appear as a missing retained reference')
  await click('data-review-edit-question'); await click('data-review-confirm-item'); await click('data-review-save')
  const aligned = revision.graph.graph.variables[0]
  assert.equal(aligned.id,manual.id); assert.equal(aligned.catalogAlignment.baselineKey,'contractPeriodMonths'); assert.equal(aligned.catalogAlignment.confirmed,true)
  assert.equal(aligned.definitionOrigin.reason,manual.definitionOrigin.reason)
  assert.equal(aligned.value,undefined,'Aligning a definition never supplies or adopts a value')
  const changed=reducer.reduce(revision.graph.graph,{type:'EDIT',id:aligned.id,values:{type:{kind:'number',unit:'days'}}})
  assert.equal(changed.variables[0].catalogAlignment.confirmed,false,'Changing a definition invalidates its alignment')

  await click('data-catalog-tab','missing'); await click('data-picker-option','foundationIncluded')
  await set('data-catalog-missing-reason','本项目不含基础工程，暂不补入此定义'); await click('data-catalog-exclude')
  await click('data-picker-option','designResponsibilities.scope'); await set('data-catalog-missing-reason','人工明确此可选列不适用'); await click('data-catalog-exclude');
  await click('data-picker-option','designResponsibilities.design'); await set('data-catalog-missing-reason','人工确认需逐项清单'); await click('data-catalog-add-missing')
  await click('data-review-save')
  const nodes=revision.graph.graph.variables, list=nodes.find(item=>item.catalogAlignment?.baselineKey==='designResponsibilities'), column=nodes.find(item=>item.catalogAlignment?.baselineKey==='designResponsibilities.design')
  assert.ok(list&&column); assert.equal(nodes.some(item=>item.catalogAlignment?.baselineKey==='designResponsibilities.scope'),false,'An excluded optional sibling is not silently imported'); assert.equal(list.type.kind,'list'); assert.equal(column.parentId,list.id)
  assert.equal(revision.graph.graph.catalogReview.exclusions.foundationIncluded.reason,'本项目不含基础工程，暂不补入此定义')
  assert.equal(nodes[0].id,manual.id); assert.equal(nodes[0].catalogAlignment.baselineKey,'contractPeriodMonths')
  assert.ok(nodes.some(item=>item.catalogAlignment?.baselineKey==='designResponsibilities.component')); assert.ok(nodes.some(item=>item.catalogAlignment?.baselineKey==='designResponsibilities.execution'));
  for(const item of nodes.filter(item=>item.id!==manual.id)) { await editor.value.locateQuestion(item.id,revision.id); await settle(); await click('data-review-edit-question'); await click('data-review-confirm-item') }
  await click('data-review-save'); await click('data-review-confirm')
  assert.equal(confirmations.length,1,'The saved same graph revision reaches the existing confirmation path')
  assert.equal(confirmations[0][1],'saved-'+writes.length)
  const component=nodes.find(item=>item.catalogAlignment?.baselineKey==='designResponsibilities.component')
  await editor.value.locateQuestion(component.id,revision.id); await settle(); await click('data-review-edit-question')
  const stableOptions=control('data-review-options').value
  assert.ok(stableOptions.startsWith('piling |'),'Existing variable editor exposes stable option values')
  await set('data-review-options',stableOptions.replace('piling |','authored-piling |'))
  await click('data-review-save-form'); await click('data-review-save')
  const edited=revision.graph.graph.variables.find(item=>item.id===component.id)
  assert.equal(edited.options[0].value,'authored-piling'); assert.equal(edited.catalogAlignment.confirmed,false,'Editing option tokens retains the node but requires alignment review')
  app.unmount(); review.sourceReviewDrafts.delete(props.projectId)
  const secondGroup = copy(revision.graph.graph.variables.find(item=>item.id===list.id))
  secondGroup.id='second-scope'; secondGroup.catalogAlignment.scope='Separate contract'
  revision.graph.graph.variables.push(secondGroup)
  delete revision.graph.graph.catalogReview.exclusions['designResponsibilities.scope']
  app=mount(); await settle(); await click('data-review-catalog'); await click('data-catalog-tab','missing')
  await click('data-picker-option','designResponsibilities.scope'); await set('data-catalog-missing-reason','需要补充指定范围')
  const writeCount=writes.length
  await click('data-catalog-add-missing')
  assert.match(text(host.root),/已有多个适用范围/,'Multiple parent scopes require an explicit choice')
  assert.equal(writes.length,writeCount)
  app.unmount()
  assert.equal(review.sourceReviewDrafts.get(props.projectId).graph.graph.variables.some(item=>item.catalogAlignment?.baselineKey==='designResponsibilities.scope'),false,'Ambiguous scope import leaves the graph unchanged')
  console.log('PASS: manual definitions, units and provenance, baseline review, missing/extra variables, exclusions, nested definitions, revision save/confirm and unfinished-form recovery use the existing graph without adopting values')
})().catch(error=>{console.error(error);process.exitCode=1})
