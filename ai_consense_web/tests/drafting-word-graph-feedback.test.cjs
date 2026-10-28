const assert = require('node:assert/strict')
const path = require('node:path')
const vue = require('vue')
const { loadVue } = require('./helpers/load-vue.cjs')
const { renderer, find, text, settle } = require('./helpers/render-controls.cjs')

const copy = value => JSON.parse(JSON.stringify(value))
const source = { id: '41', sourceDocumentId: 41, docId: 'ntt', editionId: 'NTT-docx-TEST', fileKey: 'NTT', fileName: 'TEST source.docx', format: 'docx', sourceSha256: 'a'.repeat(64), parseStatus: 'PARSED', canEdit: true, evidenceStatus: 'bound', analysisRevision: 'TEST-analysis',analysisInputSha256:'input',analysisSha256:'analysis' }
const paragraph = { id: 'p1', ordinal: 1, text: 'First linked passage. Second affected passage.', paragraphSha256: 'b'.repeat(64), sourceSha256: source.sourceSha256, sourcePosition:{sourceSha256:source.sourceSha256,partName:'word/document.xml',nodePath:'p1'} }
const reading = { ...source, paragraphs: [paragraph], blocks: [], guidanceNotes: [], sourceMembers: [], markerCandidates: [], groups: [], conditions: [], issues: [] }
const existing = { id: 'existing', nameZh: '原变量', nameEn: 'Existing variable', type: { kind: 'boolean', zh: '是／否', en: 'Yes / No' }, role: { kind: 'input', zh: '项目事实', en: 'Project fact' }, status: 'pending', docIds: ['41'], options: [], sourceAnchors: [], sourceUnitIds: [], editTargets: [], relatedInputIds: [], relations: [], targets: [], evidence: [] }
const graph = { kind: 'consense-template-graph-v1', graph: { variables: [existing], events: [], sourceReviews: {}, nextId: 1, blank: false, guidanceMode: 'recognized' }, sources: [source], selectedDocuments: { NTT: '41' } }
let saved = { id: 'draft-1', parentRevisionId: null, status: 'DRAFT', graphSha256: 'test-graph', graph, sourceStale: false }
const writes = [], confirms = []
const api = {
  reviewHead: async () => ({ currentRevisionId: null, draftRevisionId: saved.id, draft: copy(saved), current: null, sources: [copy(source)] }),
  reviewReading: async () => copy(reading),
  reviewPreview: async () => { throw Error('TEST PDF unavailable') },
  reviewSave: async (...args) => { writes.push(copy(args)); saved = { ...copy(saved), id: 'draft-2', graph: copy(args[1]) }; return copy(saved) },
  reviewConfirm: async (...args) => { confirms.push(args); throw Error('Unexpected confirmation') },
  reviewCandidates: async () => ({ variables: [] })
}
let selection
const Word = {
  props: ['projectId', 'reading', 'locale', 'locations', 'locationRequest'],
  emits: ['selection', 'error'],
  setup(_props, { emit, expose }) {
    expose({ clear: () => emit('selection', undefined) })
    return () => vue.h('section', { 'data-test-word': true }, [vue.h('button', { 'data-test-word-pick': true, onClick: () => emit('selection', selection) }, 'Pick Word text')])
  }
}
const Modal = { props: ['open'], setup: (props, { slots }) => () => props.open ? vue.h('section', slots.default?.()) : null }
const nativeWindow = new (require('jsdom').JSDOM)('').window
const loaded = loadVue(path.join(__dirname, '../src/components/DraftingSourceReview.vue'), {
  boundaries: { '@/api': { draftingApi: api }, '@/api/source-locations': { reviewSourceBindings: async () => { throw Error('Unexpected PDF bindings') } }, './AppModal.vue': { default: Modal }, './DraftingWordSource.vue': Word, './DraftingPdfPreview.vue': { default: { render: () => null } } },
  globals: { structuredClone, window: nativeWindow, document: nativeWindow.document, Element: nativeWindow.Element, URL }
})
const props = vue.reactive({ projectId: 'WORD-GRAPH-TEST', locale: 'en' }), editor = vue.ref()
const host = renderer(), app = host.createApp({ setup: () => () => vue.h(loaded.default, { ...props, ref: editor }) })
app.mount(host.root)
const control = (attr, value) => find(host.root, node => value === undefined ? node.props?.[attr] !== undefined : node.props?.[attr] === value)
const click = async attr => { const node = control(attr); assert.ok(node, attr); assert.notEqual(node.props.disabled, true, attr + ' disabled'); await node.props.onClick(); await settle() }
const button = async label => { const node = find(host.root, item => item.tag === 'button' && text(item).trim() === label); assert.ok(node, label); await node.props.onClick(); await settle() }
const anchor = (start, end) => ({ docId: '41', sourceSha256: source.sourceSha256, text: paragraph.text.slice(start, end), native: { paragraphId: paragraph.id, paragraphSha256: paragraph.paragraphSha256, start, end, expectedText: paragraph.text.slice(start, end), sourceSha256: source.sourceSha256, channel: 'editableText' } })
const pick = async (start, end) => { selection = anchor(start, end); await click('data-test-word-pick') }

;(async () => {
  await settle()
  await click('data-source-view-word')
  await settle()
  assert.ok(control('data-test-word'), 'Word reader is mounted on demand')
  assert.equal(control('data-source-view-word').props['aria-pressed'], true)
  await click('data-source-view-pdf')
  assert.equal(control('data-source-view-pdf').props['aria-pressed'], true)
  await click('data-source-view-word')
  await pick(0, 5)
  await click('data-word-add-variable')
  assert.equal(writes.length, 0, 'Selecting and adding a variable only changes the draft in memory')
  await pick(6, 12)
  await click('data-word-link-variable')
  await pick(21, 27)
  await click('data-word-add-target')
  await click('data-review-save-form')
  await click('data-review-confirm-definition')
  await button('View relationships')
  assert.ok(control('data-review-graph-node', 'review-1'), 'New variable is represented in the actual relationship view')
  const action = control('data-review-graph-node', 'action:review-1:all')
  assert.ok(action, 'The selected passage appears as an amendment action')
  assert.ok(control('data-review-graph-node', 'file:41'), 'The action retains its source document')
  assert.ok(control('data-review-graph-edge', 'action:review-1:all:file:41'), 'The action remains linked to the original file')
  await action.props.onClick(); await settle()
  assert.match(text(host.root), new RegExp(paragraph.text.slice(21, 27)), 'The action inspector shows the selected source text')
  await button('Save review draft')
  assert.equal(writes.length, 1)
  assert.equal(confirms.length, 0, 'Saving a selection never confirms the template')
  assert.deepEqual(writes[0].slice(2), [null, 'draft-1'], 'The existing optimistic revision checks are retained')
  const result = writes[0][1].graph.variables
  assert.deepEqual(result.find(item => item.id === 'existing'), existing, 'The unrelated existing variable is unchanged')
  const added = result.find(item => item.id === 'review-1')
  assert.ok(added)
  assert.equal(added.definitionReview.confirmed, true)
  assert.equal(added.definitionReview.basis, null, 'The backend stamps the definition at save time')
  assert.deepEqual(added.sourceAnchors.map(item => [item.text, item.native.start, item.native.end]), [['First', 0, 5], ['linked', 6, 12]])
  assert.equal(added.editTargets.length, 1)
  assert.deepEqual([added.editTargets[0].text, added.editTargets[0].native.start, added.editTargets[0].native.end], [paragraph.text.slice(21, 27), 21, 27])
  assert.equal(added.editTargets[0].native.paragraphSha256, paragraph.paragraphSha256)
  assert.equal(added.editTargets[0].native.sourceSha256, source.sourceSha256)
  await editor.value.refresh(false)
  await settle()
  await editor.value.locateQuestion('review-1', 'draft-2')
  await settle()
  assert.match(text(host.root), /First/)
  assert.match(text(host.root), /linked/)
  await click('data-review-edit-question')
  assert.match(text(host.root), new RegExp(paragraph.text.slice(21, 27)), 'The recorded affected passage remains in the reloaded question')
  assert.equal(confirms.length, 0)
  const span = { paragraphId: paragraph.id, paragraphSha256: paragraph.paragraphSha256, sourceSha256: source.sourceSha256, channel: 'editableText', start: 0, end: 12, expectedText: paragraph.text.slice(0, 12) }
  saved.graph.graph.variables.find(item => item.id === 'existing').editTargets = [{ id:'refine', docId:'41', sourceSha256:source.sourceSha256, text:span.expectedText, native:{sourceSha256:source.sourceSha256,spans:[span]}, action:'check', proposedAction:'delete', needsRebind:true, refinementCandidate:{status:'pending',text:'linked',editableAuthority:false,native:{sourceSha256:source.sourceSha256,spans:[{...span,start:6,end:12,expectedText:'linked'}]}} }]
  await editor.value.refresh(false); await settle(); await editor.value.locateQuestion('existing', 'draft-2'); await settle(); await click('data-review-edit-question')
  await button('Use this subrange for review')
  await click('data-review-save-form'); await button('Save review draft')
  const refined = writes.at(-1)[1].graph.variables.find(item => item.id === 'existing').editTargets[0]
  assert.equal(refined.text, 'linked')
  assert.equal(refined.observedScopeOriginal.text, span.expectedText)
  assert.equal(refined.action, 'check', 'Accepting a suggested span does not choose an action')
  assert.equal(refined.refinementCandidate.status, 'accepted_for_review')
  saved.graph.graph.variables.find(item => item.id === 'existing').editTargets = [{ id:'invalid', docId:'41', sourceSha256:source.sourceSha256, text:span.expectedText, native:{sourceSha256:source.sourceSha256,spans:[span]}, action:'check', needsRebind:true, refinementCandidate:{status:'pending',text:paragraph.text.slice(21,27),editableAuthority:false,native:{sourceSha256:source.sourceSha256,spans:[{...span,start:21,end:27,expectedText:paragraph.text.slice(21,27)}]}} }]
  await editor.value.refresh(false); await settle(); await editor.value.locateQuestion('existing', 'draft-2'); await settle(); await click('data-review-edit-question'); await button('Use this subrange for review')
  assert.match(text(host.root), /cannot be matched exactly/, 'A real source passage outside the observed scope is refused')
  const old = saved.graph.graph.variables.find(item => item.id === 'existing')
  old.editTargets=[];old.catalogAlignment={status:'unreviewed',confirmed:false,proposedMatches:[{baselineKey:'format',baselineVersion:'v',factProjection:{branchValue:'electronic',branchPolarity:true}}]}
  saved.graph.graph.variables.push({...copy(existing),id:'root',nameEn:'Format',nameZh:'格式',type:{kind:'choice',zh:'选择',en:'Choice'},catalogAlignment:{status:'matched',confirmed:true,baselineKey:'format',baselineVersion:'v'},options:[{value:'electronic',labelZh:'电子',labelEn:'Electronic'},{value:'paper',labelZh:'纸',labelEn:'Paper'}]})
  await editor.value.refresh(false);await settle();await editor.value.locateQuestion('existing','draft-2');await settle()
  control('data-projection-key').props['onUpdate:modelValue']('format');await settle()
  control('data-projection-root').props['onUpdate:modelValue']('root');await settle()
  assert.equal(control('data-projection-confirm').props.disabled,true,'Explicit confirmation of the two choices is required')
  control('data-projection-exhaustive').props['onUpdate:modelValue'](true);await settle();await click('data-projection-confirm');await button('Save review draft')
  assert.equal(writes.at(-1)[1].graph.variables.find(item=>item.id==='existing').role.kind,'derived')
  api.plan = async () => ({graphRevisionId:'draft-2',unresolved:[{kind:'HistoricalProjectionAnswerConflict',inputKeys:['existing'],rootId:'root',rootValue:'electronic',oldValue:false,derivedValue:true,evidenceFingerprint:'history-hash'}]})
  api.projectionHistorySources=async (_project,revisionId,nodeId)=>({graphRevisionId:revisionId,nodeId,evidenceFingerprint:'history-hash',sourceStatus:'manual_unbound',unverifiedSourceRef:'Manual note',sources:[]})
  await editor.value.refresh(false); await settle(); await editor.value.locateQuestion('existing','draft-2'); await settle(); await click('data-projection-read-history')
  assert.equal(control('data-projection-resolve-history').props.disabled,true, 'A reason is required before confirming the derived result')
  control('data-projection-history-reason').props['onUpdate:modelValue']('Compared against the adopted selection'); await settle()
  await click('data-projection-resolve-history'); await button('Save review draft')
  const resolution = writes.at(-1)[1].graph.variables.find(item => item.id==='existing').factProjectionReview.historicalAnswerResolution
  assert.equal(resolution.rootValue,'electronic'); assert.equal(resolution.oldValue,false); assert.equal(resolution.decision,'use_derived'); assert.equal(resolution.evidenceFingerprint,'history-hash')
  saved.graph.graph.variables.push({...copy(existing),id:'manual-local',nameEn:'Local option',catalogAlignment:{status:'unreviewed',confirmed:false,proposedMatches:[]},editTargets:[{id:'manual-target',docId:'41',text:'Alternative B',action:'check'}]})
  await editor.value.refresh(false);await settle();await editor.value.locateQuestion('manual-local','draft-2');await settle()
  assert.ok(control('data-manual-projection'),'Manual linking remains available without a model proposal')
  control('data-manual-projection-root').props['onUpdate:modelValue']('root');await settle()
  control('data-manual-projection-branch').props['onUpdate:modelValue']('paper');await settle()
  control('data-manual-projection-polarity').props['onUpdate:modelValue']('false');await settle()
  assert.equal(control('data-manual-projection-confirm').props.disabled,true,'Human reason and exhaustive confirmation are required')
  control('data-manual-projection-reason').props['onUpdate:modelValue']('Checked Alternative B in the source');await settle()
  control('data-manual-projection-exhaustive').props['onUpdate:modelValue'](true);await settle();await click('data-manual-projection-confirm');await button('Save review draft')
  const manualLinked=writes.at(-1)[1].graph.variables.find(item=>item.id==='manual-local')
  assert.equal(manualLinked.factProjectionReview.origin,'manual');assert.equal(manualLinked.factProjectionReview.reason,'Checked Alternative B in the source')
  assert.equal(manualLinked.factProjectionReview.branchValue,'paper');assert.equal(manualLinked.factProjectionReview.branchPolarity,false)
  assert.equal(manualLinked.editTargets[0].text,'Alternative B');assert.equal(manualLinked.editTargets[0].needsRebind,true,'Manual association retains the separate target for review')
  saved.graph.graph.variables.push({...copy(existing),id:'conditional',nameEn:'Conditional input',catalogAlignment:{status:'matched',confirmed:true,baselineKey:'dependent',baselineVersion:'v'}})
  saved.fixedRuleSuggestions=[{kind:'field_condition',actionId:'field:dependent',ruleVersion:'v',bindingFingerprint:'source-basis-hash',document:'',historicalClause:'',detail:{en:'Fixed condition'},targetBaselineKey:'dependent',targetNodeIds:['conditional'],inputs:[{baselineKey:'format',nodeIds:['root']}],conditions:[{field:'format',operator:'eq',value:'paper'}],targetBinding:'unbound',status:'pending',executable:false,coverage:'catalog_condition'}]
  await editor.value.refresh(false);await settle()
  control('data-fixed-applicability-reason').props['onUpdate:modelValue']('Compared the condition to the template');await settle()
  control('data-fixed-applicability-confirmed').props['onUpdate:modelValue'](true);await settle();await click('data-fixed-applicability-adopt');await button('Save review draft')
  const applied=writes.at(-1)[1].graph.variables.find(item=>item.id==='conditional')
  assert.equal(applied.applicability.clauses[0].inputId,'root');assert.equal(applied.applicability.clauses[0].value,'paper');assert.equal(applied.applicability.bindingFingerprint,'source-basis-hash')
  assert.equal(applied.status,'pending');assert.equal(confirms.length,0,'Confirming applicability never confirms the template')
  await editor.value.refresh(false);await settle();await editor.value.locateQuestion('conditional','draft-2');await settle();await click('data-fixed-applicability-remove');await button('Save review draft')
  assert.equal(writes.at(-1)[1].graph.variables.find(item=>item.id==='conditional').applicability,undefined,'The reviewer can remove a confirmed condition')
  const priorRule={kind:'rule',branches:[{id:'old-case',mode:'all',clauses:[{inputId:'existing',operator:'yes'}]}]}
  saved.graph.graph.variables.push({...copy(existing),id:'prior-rule',nameEn:'Previously ruled variable',correction:copy(priorRule),catalogAlignment:{status:'unreviewed',confirmed:false,proposedMatches:[]},editTargets:[{id:'old-target',docId:'41',text:'Existing affected passage',action:'delete',branchId:'old-case'}]})
  await editor.value.refresh(false);await settle();await editor.value.locateQuestion('prior-rule','draft-2');await settle()
  control('data-manual-projection-root').props['onUpdate:modelValue']('root');await settle()
  control('data-manual-projection-branch').props['onUpdate:modelValue']('paper');await settle()
  control('data-manual-projection-polarity').props['onUpdate:modelValue']('true');await settle()
  control('data-manual-projection-reason').props['onUpdate:modelValue']('Compared source choices');await settle()
  control('data-manual-projection-exhaustive').props['onUpdate:modelValue'](true);await settle();await click('data-manual-projection-confirm');await button('Save review draft')
  let convertedOld=writes.at(-1)[1].graph.variables.find(item=>item.id==='prior-rule')
  assert.equal(convertedOld.factProjectionReview.previousRuleResolution.status,'pending');assert.deepEqual(convertedOld.factProjectionReview.original.correction,priorRule)
  assert.equal(convertedOld.factProjectionReview.original.targetBindings[0].branchId,'old-case');assert.equal(convertedOld.editTargets[0].needsRebind,true)
  await editor.value.refresh(false);await settle();await editor.value.locateQuestion('prior-rule','draft-2');await settle()
  assert.ok(control('data-projection-previous-rule'));assert.match(text(host.root),/old-case/,'The saved old rule remains reviewable after reload')
  await click('data-projection-revert');await button('Save review draft')
  convertedOld=writes.at(-1)[1].graph.variables.find(item=>item.id==='prior-rule')
  assert.deepEqual(convertedOld.correction,priorRule);assert.equal(convertedOld.editTargets[0].branchId,'old-case');assert.equal(convertedOld.editTargets[0].needsRebind,true)
  const graphConfirmations=[]
  saved.definitionGraphReview={confirmed:false,valid:false,unresolvedRelationCount:1}
  api.reviewConfirmDefinitions=async(...args)=>{graphConfirmations.push(copy(args));saved={...copy(saved),id:'definitions-1',definitionGraphReview:{confirmed:true,valid:true,reason:args[3],unresolvedRelationCount:1,confirmedFieldIds:args[5],pendingFieldIds:['existing'],currentDefinitionReviewFieldIds:copy(args[5]),definitionGroupReadyFieldIds:copy(args[5])}};return copy(saved)}
  await editor.value.refresh(false);await settle()
  assert.equal(control('data-definition-graph-confirm').props.disabled,true,'Graph confirmation requires an explicit reason and acknowledgement')
  await click('data-definition-select-all')
  control('data-definition-graph-reason').props['onUpdate:modelValue']('Checked the usable variables and relations');await settle()
  control('data-definition-graph-acknowledged').props['onUpdate:modelValue'](true);await settle()
  assert.equal(control('data-definition-graph-confirm').props.disabled,true,'An older backend cannot accidentally interpret a group as whole-graph consent')
  saved.definitionGraphReview={confirmed:false,valid:false,unresolvedRelationCount:1,confirmedFieldIds:[],pendingFieldIds:['review-1','root','conditional']};await editor.value.refresh(false);await settle()
  assert.equal(control('data-definition-group-choice','review-1'),undefined,'An older service without current-basis eligibility cannot promote a saved manual confirmation')
  assert.ok(control('data-definition-group-choice','root'));assert.ok(control('data-definition-group-choice','conditional'),'Confirmed matched definitions retain explicit group confirmation on an older service')
  assert.equal(graphConfirmations.length,0,'Checking old-service eligibility does not submit a partial or whole-graph confirmation')
  saved.definitionGraphReview.currentDefinitionReviewFieldIds=['review-1'];saved.definitionGraphReview.definitionGroupReadyFieldIds=['review-1','root','conditional']
  await editor.value.refresh(false);await settle();await click('data-definition-select-all');await click('data-definition-graph-confirm')
  assert.deepEqual(graphConfirmations,[['WORD-GRAPH-TEST','draft-2',null,'Checked the usable variables and relations',true,['review-1','root','conditional']]])
  assert.match(text(host.root),/confirmed group is available for project candidate extraction/i);assert.equal(confirms.length,0,'Confirming variable graph does not confirm amendment ranges')
  saved.id='draft-2';saved.definitionGraphReview={confirmed:false,valid:false,unresolvedRelationCount:0,confirmedFieldIds:[],pendingFieldIds:['review-1','root','conditional'],currentDefinitionReviewFieldIds:['review-1'],definitionGroupReadyFieldIds:['review-1','root','conditional'],baselineReuseSupported:true,completeBaselineReuseSupported:true};await editor.value.refresh(false);await settle();await click('data-definition-select-all')
  control('data-definition-graph-reason').props['onUpdate:modelValue']('Reviewed this complete edition');control('data-definition-graph-acknowledged').props['onUpdate:modelValue'](true);control('data-definition-reuse-edition').props['onUpdate:modelValue'](true);await settle()
  control('data-definition-complete-edition').props['onUpdate:modelValue'](true);await settle();await click('data-definition-graph-confirm')
  assert.deepEqual(graphConfirmations.at(-1),['WORD-GRAPH-TEST','draft-2',null,'Reviewed this complete edition',true,['review-1','root','conditional'],true,true])
  saved.id='draft-2';delete saved.definitionGraphReview
  const current = {...copy(saved),id:'current-1',status:'CONFIRMED',parentRevisionId:'draft-2',scopeObservationIntegrity:{ref:'valid',bad:'invalid'}}
  current.graph.graph.variables.find(item=>item.id==='existing').nameEn='Confirmed version'
  current.graph.graph.requirements=[{id:'unassigned',sourceId:'41',sourceSha256:source.sourceSha256,sourceQuote:'First',nodeIds:[],status:'pending',kind:'amendment',sourceQuoteEvidence:{kind:'scope',sourceObservationId:'scope-1',sourceScopeText:'First linked',sourceScopeObservation:{memberRanges:[]}}}]
  current.graph.graph.sourceScopeObservations={ref:{sourceId:'41',sourceSha256:source.sourceSha256,analysisRevision:'test-rev',analysisInputSha256:'test-input',analysisSha256:'test-analysis',sourceObservationId:'scope-2',sourceScopeText:'Shared complete scope',sourceScopeObservation:{id:'scope-2',memberRanges:[]}}}
  current.graph.graph.requirements.push({id:'deduped',sourceId:'41',sourceSha256:source.sourceSha256,analysisRevision:'test-rev',analysisInputSha256:'test-input',analysisSha256:'test-analysis',sourceQuote:'Shared',nodeIds:[],status:'pending',kind:'amendment',sourceQuoteEvidence:{kind:'scope',sourceObservationId:'scope-2',sourceScopeObservationRef:'ref'}})
  current.graph.graph.sourceScopeObservations.bad={...current.graph.graph.sourceScopeObservations.ref}
  current.graph.graph.requirements.push({...current.graph.graph.requirements[1],id:'damaged',sourceQuoteEvidence:{kind:'scope',sourceObservationId:'scope-2',sourceScopeObservationRef:'bad'}})
  reading.sourceMembers=[{id:'member-1',sourcePosition:copy(paragraph.sourcePosition)}]
  reading.groups=[{id:'inline-1',memberRanges:[{memberId:'member-1',start:0,end:5}],status:'observed'}]
  current.graph.graph.requirements.push({id:'inline',sourceId:'41',sourceSha256:source.sourceSha256,analysisRevision:source.analysisRevision,analysisInputSha256:source.analysisInputSha256,analysisSha256:source.analysisSha256,sourceQuote:'First',nodeIds:[],status:'pending',kind:'amendment',sourceQuoteEvidence:{kind:'scope',sourceObservationId:'inline-1',sourceScopeText:'First',sourceScopeObservation:{status:'observed',memberRanges:[{end:5,start:0,memberId:'member-1'}],id:'inline-1'}}})
  api.reviewHead=async()=>({currentRevisionId:'current-1',draftRevisionId:'draft-2',workingRevisionId:'current-1',current:copy(current),draft:copy(saved),sources:[copy(source)]})
  api.plan=async()=>({graphRevisionId:'current-1',unresolved:[{kind:'HistoricalProjectionAnswerConflict',inputKeys:['existing'],rootId:'root',rootValue:'electronic',oldValue:false,derivedValue:true,evidenceFingerprint:'history-hash'}]})
  await editor.value.refresh(false);await settle();await editor.value.locateQuestion('existing','current-1');await settle()
  assert.match(text(host.root),/Confirmed version/,'The active confirmed revision, not its older draft, is displayed')
  assert.ok(control('data-unassigned-requirements'),'Source ranges without a variable association remain visible for human review')
  assert.match(text(host.root),/Shared complete scope/,'The graph revision resolves a deduplicated source observation')
  assert.match(text(host.root),/Historical scope evidence is missing or inconsistent/,'Invalid observation hashes are visible and cannot authorize location')
  await button('Locate and review source')
  assert.match(text(host.root),/missing or changed analysis identity/,'Historical scope without analysis identity is not relocated by a reused member ID')
  await click('data-requirement-locate','inline')
  assert.doesNotMatch(text(host.root),/cannot uniquely verify this historical scope/,'Equivalent source observations remain valid when object keys are reordered')
  await click('data-projection-read-history')
  assert.ok(control('data-projection-resolve-history'),'History read uses the actual active revision')
  const historic=copy(current);historic.id='older-1';historic.scopeObservationIntegrity.ref='invalid';historic.graph.graph.sourceScopeObservations.ref.sourceScopeText='Tampered Shared scope'
  api.reviewRevisions=async()=>[{id:'older-1',createdAt:'test',status:'DRAFT',sourceStale:false}]
  api.reviewRevision=async()=>copy(historic)
  await button('Graph versions');await button('Review as a new draft')
  assert.doesNotMatch(text(host.root),/Tampered Shared scope/,'Restoring a historical graph cannot reuse the current revision integrity status')
  assert.match(text(host.root),/Historical scope evidence is missing or inconsistent/)
  api.reviewHead=async project => { if(project==='OTHER-PROJECT')throw Error('Other project unavailable');return {currentRevisionId:'current-1',draftRevisionId:'draft-2',workingRevisionId:'current-1',current:copy(current),draft:copy(saved),sources:[copy(source)]} }
  props.projectId='OTHER-PROJECT';await settle()
  assert.doesNotMatch(text(host.root),/Confirmed version|Shared complete scope/,'A failed project switch never displays the previous project graph')
  assert.match(text(host.root),/Other project unavailable/)
  app.unmount()
  console.log('PASS: Word selection feeds ADD, LINK and TARGETS into the saved and reloaded review graph without confirming or modifying unrelated nodes')
})().catch(error => { console.error(error); process.exitCode = 1; app.unmount() })
