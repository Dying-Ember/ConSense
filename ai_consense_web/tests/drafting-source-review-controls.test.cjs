const assert = require('node:assert/strict')
const path = require('node:path')
const {createHash}=require('node:crypto')
const vue = require('vue')
const { loadVue } = require('./helpers/load-vue.cjs')
const { renderer, find, text, settle, deferred } = require('./helpers/render-controls.cjs')
const copy = value => JSON.parse(JSON.stringify(value))
const nativeWindow = new (require('jsdom').JSDOM)('').window
let nativeSelection
const nativeGlobals = { getSelection: () => nativeSelection ? { rangeCount: 1, getRangeAt: () => nativeSelection } : null }
const source = { id: '41', sourceDocumentId: 41, docId: 'ntt', editionId: 'NTT-docx-TEST', fileKey: 'NTT', fileName: 'TEST ONLY source.docx', format: 'docx', sourceSha256: 'a'.repeat(64), parseStatus: 'PARSED', canEdit: true, evidenceStatus: 'bound', analysisRevision: 'TEST-analysis-current' }
const paragraph = { id: 'p1', ordinal: 1, text: 'TEST ONLY exact source requirement', paragraphSha256: 'b'.repeat(64), sourceSha256: source.sourceSha256 }
const item = { id: 'question-1', nameZh: '测试要求', nameEn: 'TEST requirement', docIds: ['41'], type: { kind: 'boolean', zh: '是／否' }, role: { kind: 'input', zh: '项目事实' }, status: 'pending', options: [], sourceAnchors: [{ docId: '41', sourceSha256: source.sourceSha256, text: paragraph.text, native: { paragraphId: 'p1', paragraphSha256: paragraph.paragraphSha256, start: 0, end: paragraph.text.length, expectedText: paragraph.text } }], sourceUnitIds: [], editTargets: [], relatedInputIds: [], relations: [], targets: [], evidence: [] }
item.sourceAnchors.push({ docId: '41', sourceSha256: source.sourceSha256, text: paragraph.text, nativeBinding: { status: 'bound', spans: [copy(item.sourceAnchors[0].native)] } })
const graph = { kind: 'consense-template-graph-v1', graph: { variables: [item, { ...copy(item), id: 'unrelated-2', nameEn: 'UNRELATED graph question', status: 'confirmed' }], events: [], sourceReviews: {}, nextId: 1, blank: false, guidanceMode: 'minimum' }, sources: [source], selectedDocuments: { NTT: '41' } }
const revision = { id: 'draft-1', parentRevisionId: null, status: 'DRAFT', graphSha256: 'graph-test', graph, sourceStale: false }
const extraParagraphs = [{ ...paragraph, id: 'p2', ordinal: 2, text: 'Second exact source passage', paragraphSha256: 'c'.repeat(64) }, { ...paragraph, id: 'p3', ordinal: 3, text: 'Third exact source passage', paragraphSha256: 'd'.repeat(64) }, { ...paragraph, id: 'p4', ordinal: 4, text: 'Duration: ____ months.', paragraphSha256: 'e'.repeat(64) }]
const reading = { ...source, paragraphs: [paragraph, ...extraParagraphs], blocks: [], guidanceNotes: [], sourceMembers: [], markerCandidates: [{ id: 'marker:INTERNAL-TEST', disposition: 'unknown', symbol: '*', localSentenceObservation: { text: 'TEST source with an unresolved * marker.' } }, { id: 'marker:RESOLVED-TEST', disposition: 'conditional_source_target', symbol: '#' }], groups: [], conditions: [], issues: [{ code: 'GUIDANCE_SOURCE_SYNTAX_INCOMPLETE', ref: 'guidance:INTERNAL-TEST', status: 'open', message: 'The source instruction has no closing delimiter.' }] }
item.sourceAnchors[0].sourceObservationId = 'guidance:TEST'
item.sourceAnchors[0].analysisRevision = source.analysisRevision
paragraph.sourcePosition = { sourceSha256: source.sourceSha256, partName: 'word/document.xml', nodePath: '/TEST/p[1]' }
reading.sourceMembers = [{ id: 'member:TEST', sourcePosition: paragraph.sourcePosition, textObservation: { text: 'RELATED source paragraph' } }]
const blankParagraph={...copy(paragraph),id:'blank-p',text:'  ',sourcePosition:{...paragraph.sourcePosition,nodePath:'/TEST/p[blank]'}}
reading.paragraphs.push(blankParagraph)
reading.sourceMembers.push({id:'member:BLANK',sourcePosition:blankParagraph.sourcePosition,textObservation:{text:'  '}})
reading.groups = [
  { id: 'scope:TEST', guidanceNoteId: 'guidance:TEST', instructionReferenceText: 'SCT6(2)', scopeComplete: true, orderedMemberIds: ['member:TEST', 'member:MISSING','member:BLANK'], physicalContextMemberIds: ['member:CONTEXT'] },
  { id: 'scope:INLINE', guidanceNoteId: 'guidance:TEST', kind: 'inline_parenthetical_scope', scopeComplete: true, text: '#special payment words', wrapperCleanupStatus: 'independent_decision_required', orderedMemberIds: ['member:TEST'] },
  { id: 'scope:UNRELATED', guidanceNoteId: 'guidance:OTHER', instructionReferenceText: 'UNRELATED range', orderedMemberIds: ['member:TEST'] }
]
const writes = [], confirms = [], originals = [], historyReads = [], emitted = [], pending = new Map()
let saveBarrier
const api = {
  reviewHead: async project => pending.has(project) ? pending.get(project).promise : { currentRevisionId: 'current-1', draftRevisionId: revision.id, draft: copy(revision), current: null, sources: [source] },
  reviewReading: async () => copy(reading),
  reviewSave: async (...args) => { writes.push(copy(args)); if (saveBarrier) await saveBarrier.promise; return { ...copy(revision), id: 'draft-' + (writes.length + 1), graph: copy(args[1]) } },
  reviewConfirm: async (...args) => { confirms.push(args); return revision },
  reviewCandidates: async () => ({ variables: [] }),
  reviewRevisions: async () => [{ id: 'old-confirmed', status: 'CONFIRMED', sourceStale: false, createdAt: 'TEST time', graphSha256: 'old-sha' }],
  reviewRevision: async (...args) => { historyReads.push(args); return { ...copy(revision), id: 'old-confirmed', status: 'CONFIRMED', graph: { ...copy(graph), graph: { ...copy(graph.graph), variables: [{ ...copy(item), nameEn: 'HISTORICAL TEST requirement' }] } } } },
  reviewPreview: async (...args) => { originals.push(args); throw Error('TEST renderer unavailable') },
  reviewSource: async () => { throw Error('Unexpected PDF fallback') }
}
let wrongPreviewHash=false
const previewBytes='SYNTHETIC PDF boundary'
api.reviewSourceBindings=async (_project,id,sha)=>({fileKey:String(id),view:'source',sourceSha256:sha,docxSha256:'synthetic-working-docx',pdfSha256:wrongPreviewHash?'0'.repeat(64):createHash('sha256').update(previewBytes).digest('hex'),renderProfileHash:'SYNTHETIC-profile',geometryStatus:'ready',bindings:reading.paragraphs.filter(p=>p.text.trim()).map((p,index)=>({bindingId:'source-marker-'+p.id,sourceParagraphId:p.id,sourceText:p.text,locationStatus:'exact',geometryStatus:'ready',geometry:{kind:'point',pageNumber:index===0?3:4,x:40,y:100,pageWidth:600,pageHeight:800,unit:'pt',origin:'top-left',pageBox:'crop',rotation:0}}))})

let receipt,previewFailure=false
api.reviewPreview=async (...args)=>{originals.push(args);if(previewFailure)throw Error('TEST renderer unavailable');return new Blob(['SYNTHETIC PDF boundary'])}
const Modal={props:['open'],setup:(props,{slots})=>()=>props.open?vue.h('section',slots.default?.()):null}
const Pdf={props:['source','focusText','focusRequest','selectWords','immersive','locations','selectedBindingId','locationRequest'],emits:['selection'],setup:(props,{emit,slots})=>()=>vue.h('section',{'data-test-pdf':true,'data-focus-text':props.focusText,'data-source-url':props.source,'data-immersive':props.immersive,'data-selected-binding':props.selectedBindingId,'data-locations':props.locations},[
  vue.h('button',{'data-test-pick':true,onClick:()=>emit('selection',{...receipt,source:props.source})},'Synthetic page gesture'),slots.selection?.({clear:()=>emit('selection',{source:props.source,page:1,words:[]})})])}
const loaded=loadVue(path.join(__dirname,'../src/components/DraftingSourceReview.vue'),{boundaries:{'@/api':{draftingApi:api},'@/api/source-locations':{reviewSourceBindings:(...args)=>api.reviewSourceBindings(...args)},'./AppModal.vue':{default:Modal},'./DraftingPdfPreview.vue':{default:Pdf}},globals:{structuredClone,window:nativeGlobals,document:nativeWindow.document,Element:nativeWindow.Element,URL,crypto:{subtle:{digest:async (_algorithm,bytes)=>Uint8Array.from(createHash('sha256').update(Buffer.from(bytes)).digest()).buffer}}}})
const props=vue.reactive({projectId:'SOURCE-A',locale:'en'}),editor=vue.ref()
const host=renderer(),app=host.createApp({setup:()=>()=>vue.h(loaded.default,{...props,ref:editor,onDirty:value=>emitted.push(value)})})
app.mount(host.root)
const control=(attr,value)=>find(host.root,node=>value===undefined?node.props?.[attr]!==undefined:node.props?.[attr]===value)
const click=async attr=>{const node=control(attr);assert.ok(node,attr);await node.props.onClick();await settle()}
const title=async label=>{const node=find(host.root,node=>node.tag==='button'&&text(node).trim()===label);assert.ok(node,label);await node.props.onClick();await settle()}
const choose=async()=>{await editor.value.locateQuestion(item.id,revision.id);await settle();if(control('data-review-edit-question'))await click('data-review-edit-question')}
const pick=async parts=>{receipt={page:2,words:parts.map(([paragraph,start,end],itemIndex)=>({itemIndex,start,end,text:paragraph.text.slice(start,end),context:paragraph.text,rectangle:{x:0,y:0,width:30,height:10}}))};await click('data-test-pick')}
;(async()=>{
  await settle()
  assert.ok(control('data-source-page-review'));assert.ok(control('data-test-pdf'),'The original layout is opened inline without a modal or separate parsed view')
  assert.equal(find(host.root,node=>node.props?.class==='native-source'),undefined)
  assert.equal(writes.length,0);assert.equal(confirms.length,0)
  await editor.value.locateQuestion(item.id,'obsolete');await settle();assert.match(text(host.root),/revision changed/)
  await editor.value.locateQuestion(item.id,'draft-1');await settle()
  assert.equal(control('data-test-pdf').props['data-selected-binding'],'source-marker-p1','Question navigation must use the source-bound PDF marker, including when the same quote exists elsewhere')
  await click('data-review-edit-question')
  assert.match(text(control('data-review-related-scopes')),/SCT6\(2\).*RELATED source paragraph/s)
  assert.doesNotMatch(text(control('data-review-related-scopes')),/UNRELATED range|member:|scope:/)
  const blankLocate=control('data-review-locate-scope','member:BLANK')
  assert.ok(blankLocate,'The fixture includes an actual empty native source paragraph')
  assert.equal(blankLocate.props.disabled,true,'An empty paragraph must not offer a locator that can only produce a misleading error')
  control('data-review-name-en').props['onUpdate:modelValue']('Updated source question');await settle()
  await pick([[paragraph,5,paragraph.text.length],[extraParagraphs[0],0,4]])
  const workspace=control('data-source-workspace'), pdf=control('data-test-pdf'), originalReads=originals.length
  workspace.getBoundingClientRect=()=>({height:800})
  nativeWindow.document.body.style.overflow='auto'
  control('data-source-workspace').getBoundingClientRect=()=>({height:800});await click('data-source-immersive')
  assert.equal(control('data-source-workspace').props.role,'dialog')
  assert.equal(nativeWindow.document.body.style.overflow,'hidden')
  assert.equal(control('data-test-pdf'),pdf,'Entering immersive mode keeps the original reader mounted')
  assert.equal(pdf.props['data-immersive'],true)
  let focused=0,tabContained=false
  workspace.querySelectorAll=()=>[];workspace.closest=()=>null;workspace.focus=()=>focused++
  control('data-source-review').props.onKeydown({key:'Tab',preventDefault(){tabContained=true}})
  assert.equal(tabContained,true);assert.equal(focused,1,'Keyboard navigation remains inside the immersive workspace')
  assert.equal(originals.length,originalReads,'Expanding does not request the original again')
  assert.equal(control('data-review-name-en').value,'Updated source question','The unsaved form survives expansion')
  const escape=()=>control('data-source-review').props.onKeydown({key:'Escape',preventDefault(){},stopPropagation(){}})
  await title('View relationships');escape();await settle()
  assert.equal(control('data-source-workspace').props.role,'dialog','Escape closes the nested graph before the workspace')
  escape();await settle()
  assert.equal(control('data-source-workspace').props.role,undefined)
  assert.equal(nativeWindow.document.body.style.overflow,'auto','Exiting restores the prior scroll setting')
  assert.equal(control('data-test-pdf'),pdf)
  assert.equal(control('data-review-name-en').value,'Updated source question')
  await title('Use as affected passage')
  await pick([[extraParagraphs[1],3,8]]);await click('data-target-append')
  await pick([[extraParagraphs[3-1],0,8]]);await title('Use as affected passage')
  const placement=control('data-target-placement',0);placement.props.onChange({target:{value:'1'}});await settle()
  await click('data-review-save-form');saveBarrier=deferred();const saving=click('data-review-save-bottom');await settle()
  assert.equal(control('data-review-controls').props.disabled,true,'Save freezes source switching and question edits')
  assert.equal(control('data-review-save').props.disabled,true)
  saveBarrier.resolve();await saving;saveBarrier=undefined
  assert.equal(writes.length,1);assert.equal(confirms.length,0,'Saving ranges never confirms rules or adopts project facts')
  const saved=writes[0][1].graph.variables[0]
  assert.equal(saved.nameEn,'Updated source question');assert.deepEqual(saved.type,item.type);assert.deepEqual(saved.role,item.role)
  assert.deepEqual(saved.editTargets[0].native.spans.map(span=>[span.paragraphId,span.start,span.end]),[['p1',5,paragraph.text.length],['p2',0,4],['p3',3,8]])
  assert.equal(saved.editTargets[0].replacementPlacement.spanIndex,1)
  assert.equal(saved.editTargets[1].native.spans[0].expectedText,'Duration')
  assert.deepEqual(writes[0].slice(2),['current-1','draft-1'],'Optimistic revision guards remain on save')
  await editor.value.selectSource('41');await settle();const previousWrites=writes.length
  assert.equal(writes.length,previousWrites,'Viewing another source does not select a drafting edition or save')
  control('data-source-workspace').getBoundingClientRect=()=>({height:800});await click('data-source-immersive')
  const slow=deferred();pending.set('SOURCE-SLOW',slow);props.projectId='SOURCE-SLOW';await settle();props.projectId='SOURCE-B';await settle()
  assert.equal(nativeWindow.document.body.style.overflow,'auto')
  slow.resolve({currentRevisionId:null,draftRevisionId:null,current:null,draft:{...revision,graph:{...graph,graph:{...graph.graph,variables:[{...item,nameEn:'OLD PROJECT SECRET'}]}}},sources:[source]});await settle()
  assert.doesNotMatch(text(host.root),/OLD PROJECT SECRET/)
  previewFailure=true;await editor.value.selectSource('41');await settle();assert.match(text(host.root),/TEST renderer unavailable/)
  assert.ok(control('data-review-original'),'A failed original layout has an explicit retry, not a silent parsed-text substitution')
  previewFailure=false
  const multi=copy(item)
  multi.sourceAnchors=[{docId:'41',sourceSha256:source.sourceSha256,text:paragraph.text+'\n'+extraParagraphs[0].text,native:{spans:[copy(item.sourceAnchors[0].native),{paragraphId:'p2',paragraphSha256:extraParagraphs[0].paragraphSha256,start:0,end:extraParagraphs[0].text.length,expectedText:extraParagraphs[0].text}]}}]
  pending.set('SOURCE-MULTI',{promise:Promise.resolve({currentRevisionId:null,draftRevisionId:revision.id,draft:{...copy(revision),graph:{...copy(graph),graph:{...copy(graph.graph),variables:[multi]}}},current:null,sources:[source]})})
  props.projectId='SOURCE-MULTI';await settle();await choose()
  assert.deepEqual(Array.from(control('data-test-pdf').props['data-locations'],value=>value.geometry.pageNumber),[3,4],'All native spans retain their own physical source pages')
  await title('Next passage');assert.equal(control('data-test-pdf').props['data-selected-binding'],'source-marker-p2')
  assert.equal(control('data-test-pdf').props['data-focus-text'],'','DOCX location does not restart a full-PDF quotation search')
  const beforeWrongPdfWrites=writes.length
  wrongPreviewHash=true;props.projectId='SOURCE-WRONG-PDF';await settle()
  assert.ok(control('data-test-pdf'),'An identity mismatch does not hide the readable original')
  assert.equal(control('data-test-pdf').props['data-selected-binding'],'')
  assert.equal(control('data-test-pdf').props['data-locations'].length,0,'Coordinates from another PDF never reach the viewer')
  assert.match(text(control('data-review-location-notice')),/Locations do not match/)
  assert.equal(writes.length,beforeWrongPdfWrites);wrongPreviewHash=false
  const core = { ...copy(item), discoveryCoreRetained: true, correction: { kind: 'rule', branches: [], inputIds: [], needsRebind: true } }
  const coreGraph = { ...copy(graph), graph: { ...copy(graph.graph), variables: [core] } }
  pending.set('SOURCE-ISOLATED', { promise: Promise.resolve({ currentRevisionId: 'current-core', draftRevisionId: revision.id, draft: { ...copy(revision), graph: coreGraph }, current: null, sources: [source] }) })
  props.projectId = 'SOURCE-ISOLATED'; await settle(); await choose()
  await click('data-review-confirm-item')
  assert.match(text(host.root), /binding or target needs review/)
  assert.equal(control('data-review-save-form').props.disabled, true)
  assert.ok(control('data-review-remove-isolated-rule'), 'A retained sourced question exposes an explicit way to discard only its isolated empty rule')
  control('data-review-name-en').props['onUpdate:modelValue']('Renamed retained question'); await settle()
  await click('data-review-save-form')
  assert.ok(control('data-review-remove-isolated-rule'), 'Renaming a question must not silently discard its unresolved rule')
  await click('data-review-confirm-item')
  assert.match(text(host.root), /binding or target needs review/, 'An unrelated form edit must keep the rule review guard')
  await title('Undo')
  control('data-review-name-en').props['onUpdate:modelValue']('Unsaved core edit'); await settle()
  assert.equal(control('data-review-remove-isolated-rule').props.disabled, true)
  await click('data-review-remove-isolated-rule')
  assert.match(text(host.root), /Save or cancel the current question edit first/)
  await title('Cancel edit'); await click('data-review-remove-isolated-rule')
  assert.ok(!control('data-review-remove-isolated-rule'))
  assert.equal(control('data-review-confirm').props.disabled, true, 'Removing an isolated rule does not review the question or confirm its template')
  await title('Undo'); assert.ok(control('data-review-remove-isolated-rule'), 'The existing undo restores the isolated rule')
  assert.equal(control('data-review-remove-isolated-rule').props.disabled, false, 'Undo leaves a clean question form')
  await click('data-review-remove-isolated-rule')
  assert.ok(!find(host.root, node => node.props?.class === 'review-error'), text(find(host.root, node => node.props?.class === 'review-error') ?? {}))
  assert.ok(!control('data-review-remove-isolated-rule'), 'The empty isolated rule was removed again after undo')
  assert.equal(control('data-review-save').props.disabled, false, 'The removal is an unsaved graph edit')
  await click('data-review-save'); assert.equal(writes.length, 2, 'Save writes the isolated question graph')
  const retained = writes.at(-1)[1].graph.variables[0]
  assert.equal(retained.correction, null); assert.equal(retained.status, 'changed')
  for (const key of ['sourceAnchors', 'type', 'role', 'editTargets']) assert.deepEqual(retained[key], core[key], key + ' is retained exactly')
  assert.equal(confirms.length, 0)
  await click('data-review-confirm-item'); await click('data-review-save')
  assert.equal(writes.at(-1)[1].graph.variables[0].status, 'confirmed')
  assert.equal(control('data-review-confirm').props.disabled, false)
  await click('data-review-confirm'); assert.equal(confirms.length, 1, 'Template confirmation remains a separate explicit operation')
  for (const [suffix, changed] of [['branch', { correction: { ...core.correction, branches: [{ id: 'human-rule' }] } }], ['input', { correction: { ...core.correction, inputIds: ['another-question'] } }], ['target', { editTargets: [{ id: 'human-target' }] }], ['ordinary', { discoveryCoreRetained: false }]]) {
    const project = 'SOURCE-ISOLATED-' + suffix
    pending.set(project, { promise: Promise.resolve({ currentRevisionId: null, draftRevisionId: revision.id, draft: { ...copy(revision), graph: { ...copy(coreGraph), graph: { ...copy(coreGraph.graph), variables: [{ ...copy(core), ...changed }] } } }, current: null, sources: [source] }) })
    props.projectId = project; await settle(); await choose()
    assert.ok(!control('data-review-remove-isolated-rule'), 'Do not discard existing ' + suffix + ' content')
  }
  const definition = { ...copy(item), questionZh: '原来的准确问题？', questionEn: 'The original precise question?', type: { ...item.type, format: 'preserved' }, options: [{ value: 'stored', labelZh: '旧选项', labelEn: 'Old option', extra: 'preserved' }] }
  pending.set('SOURCE-CONDITION-ONLY', { promise: Promise.resolve({ currentRevisionId: null, draftRevisionId: revision.id, draft: { ...copy(revision), graph: { ...copy(graph), graph: { ...copy(graph.graph), variables: [definition] } } }, current: null, sources: [source] }) })
  props.projectId = 'SOURCE-CONDITION-ONLY'; await settle(); await choose()
  await title('Add a case'); await title('Add condition')
  const conditionInput = find(host.root, node => node.props?.class === 'condition-row').children.find(node => node.tag === 'select')
  conditionInput.props['onUpdate:modelValue'](item.id); await settle()
  await click('data-review-save-form'); await click('data-review-save')
  const conditionSaved = writes.at(-1)[1].graph.variables[0]
  for (const key of ['type', 'role', 'options', 'questionZh', 'questionEn', 'fixedValue', 'parentId']) assert.deepEqual(conditionSaved[key], definition[key], 'Condition-only save preserves definition field ' + key)
  assert.equal(conditionSaved.correction.branches[0].clauses[0].inputId, item.id)
  const markerItem = copy(item)
  markerItem.sourceAnchors = [{ ...copy(item.sourceAnchors[0]), sourceObservationId: 'marker:RESOLVED-TEST' }]
  reading.markerCandidates[1].targetGroupIds = ['scope:TEST']
  pending.set('SOURCE-MARKER-SCOPE', { promise: Promise.resolve({ currentRevisionId: null, draftRevisionId: revision.id, draft: { ...copy(revision), graph: { ...copy(graph), graph: { ...copy(graph.graph), variables: [markerItem] } } }, current: null, sources: [source] }) })
  const markerWrites = writes.length
  props.projectId = 'SOURCE-MARKER-SCOPE'; await settle(); await choose()
  assert.match(text(control('data-review-related-scopes')), /SCT6\(2\).*RELATED source paragraph/s, 'Marker questions expose their explicit OER target group')
  assert.doesNotMatch(text(control('data-review-related-scopes')), /UNRELATED range|Marked words only/, 'Marker links do not infer unrelated or neighbouring groups')
  assert.equal(writes.length, markerWrites, 'Showing marker scopes does not save or create amendment targets')
  const observedItem = copy(item)
  observedItem.sourceAnchors = [{ docId: '41', sourceSha256: source.sourceSha256, analysisRevision: source.analysisRevision, text: paragraph.text, sourceObservationBinding: { status: 'bound', observedRanges: [{memberId:'member:BLANK'},{ memberId: 'member:TEST' }] } }]
  pending.set('SOURCE-OBSERVATION-LOCATE', { promise: Promise.resolve({ currentRevisionId: null, draftRevisionId: revision.id, draft: { ...copy(revision), graph: { ...copy(graph), graph: { ...copy(graph.graph), variables: [observedItem] } } }, current: null, sources: [source] }) })
  props.projectId = 'SOURCE-OBSERVATION-LOCATE'; await settle(); await choose()
  await click('data-review-locate-anchor')
  assert.equal(control('data-test-pdf').props['data-selected-binding'],'source-marker-p1','An observation beginning with an empty source member still locates its actual text member')
  assert.equal(control('data-review-save-form').props.disabled, true)
  assert.equal(control('data-review-save').props.disabled, true)
  assert.equal(writes.length, markerWrites, 'Observation navigation never creates edits')
  observedItem.sourceAnchors[0].analysisRevision = 'old-analysis'
  pending.set('SOURCE-OBSERVATION-OLD', { promise: Promise.resolve({ currentRevisionId: null, draftRevisionId: revision.id, draft: { ...copy(revision), graph: { ...copy(graph), graph: { ...copy(graph.graph), variables: [observedItem] } } }, current: null, sources: [source] }) })
  props.projectId = 'SOURCE-OBSERVATION-OLD'; await settle(); await choose()
  assert.match(text(control('data-review-location-notice')), /could not be located automatically/, 'Old observation identity cannot locate against current evidence')
  assert.equal(control('data-review-location-notice').props.role,'status','Failed automatic location is a reading notice, not a blocking error')
  assert.equal(control('data-test-pdf').props['data-focus-text'],'','Old evidence cannot publish a page highlight')
  assert.equal(control('data-test-pdf').props['data-selected-binding'],'','Old evidence cannot borrow a current PDF marker')
  assert.ok(control('data-test-pdf'),'The original remains readable when automatic location is unavailable')
  observedItem.sourceAnchors[0].analysisRevision = source.analysisRevision
  reading.paragraphs.push({ ...copy(paragraph), id: 'duplicate-position' })
  pending.set('SOURCE-OBSERVATION-AMBIGUOUS', { promise: Promise.resolve({ currentRevisionId: null, draftRevisionId: revision.id, draft: { ...copy(revision), graph: { ...copy(graph), graph: { ...copy(graph.graph), variables: [observedItem] } } }, current: null, sources: [source] }) })
  props.projectId = 'SOURCE-OBSERVATION-AMBIGUOUS'; await settle(); await choose()
  assert.match(text(control('data-review-location-notice')), /could not be located automatically/, 'Ambiguous native identities cannot be guessed for observation navigation')
  props.projectId = 'SOURCE-SAVE-FAILURE'; await settle(); await choose()
  control('data-review-name-en').props['onUpdate:modelValue']('Keep this unsaved question'); await settle()
  await click('data-review-save-form'); saveBarrier = deferred()
  const failedSave = click('data-review-save-bottom'); await settle()
  assert.match(text(host.root), /waiting for server confirmation/)
  saveBarrier.resolve(Promise.reject(new Error('TEST save failed'))); await failedSave; saveBarrier = undefined
  assert.match(text(host.root), /TEST save failed/)
  assert.equal(control('data-review-save-bottom').props.disabled, false, 'Failed save keeps the draft available for retry')
  assert.equal(text(control('data-review-save-bottom')), 'Save review draft')
  assert.equal(control('data-review-name-en').value, 'Keep this unsaved question')
  saveBarrier = deferred(); const obsoleteSave = click('data-review-save-bottom'); await settle()
  props.projectId = 'SOURCE-AFTER-SAVE'; await settle()
  saveBarrier.resolve(Promise.reject(new Error('OBSOLETE save failure'))); await obsoleteSave; saveBarrier = undefined
  assert.doesNotMatch(text(host.root), /OBSOLETE save failure|waiting for server confirmation/, 'An old save cannot overwrite another project status')
  const missingRange = { ...copy(item), editTargets: [{ id: 'missing-range', docId: '41', text: '____', action: 'check', proposedAction: 'fill', needsRebind: true, scopeComplete: false, valueSourceId: item.id, replacementEn: 'Retained proposed wording', nativeBinding: { status: 'missing', reason: 'NATIVE_SPANS_REQUIRED', spans: [] } }] }
  const missingBefore = JSON.stringify(missingRange), writeCount = writes.length
  pending.set('SOURCE-MISSING-RANGE', { promise: Promise.resolve({ currentRevisionId: null, draftRevisionId: revision.id, draft: { ...copy(revision), graph: { ...copy(graph), graph: { ...copy(graph.graph), variables: [missingRange] } } }, current: null, sources: [source] }) })
  props.projectId = 'SOURCE-MISSING-RANGE'; await settle(); await choose()
  assert.match(text(host.root), /create separate targets when the same answer must fill several places/)
  assert.doesNotMatch(text(host.root), /NATIVE_SPANS_REQUIRED/)
  assert.equal(JSON.stringify(missingRange), missingBefore, 'Readable guidance does not bind or mutate the target')
  assert.equal(writes.length, writeCount)
  control('data-review-name-en').props['onUpdate:modelValue']('Clarified question'); await settle()
  await click('data-review-save-form'); await click('data-review-save-bottom')
  const retainedTarget = writes.at(-1)[1].graph.variables[0].editTargets[0]
  assert.equal(retainedTarget.valueSourceId, item.id, 'Saving another adjustment preserves the proposed answer link of an unresolved fill')
  assert.equal(retainedTarget.replacementEn, 'Retained proposed wording')
  assert.equal(retainedTarget.action, 'check')
  assert.equal(retainedTarget.needsRebind, true, 'Preserving the proposal does not authorize an edit')
  const manualFill = { ...copy(item), editTargets: [{ ...copy(item.sourceAnchors[0]), id: 'manual-fill', action: 'fill', valueSourceId: item.id, replacementEn: 'Keep manual wording' }] }
  const laterSpan = { paragraphId: extraParagraphs[0].id, paragraphSha256: extraParagraphs[0].paragraphSha256, sourceSha256: source.sourceSha256, start: 0, end: extraParagraphs[0].text.length, expectedText: extraParagraphs[0].text, channel: 'editableText' }
  manualFill.editTargets[0].native = { spans: [copy(item.sourceAnchors[0].native), laterSpan] }
  manualFill.editTargets[0].text = paragraph.text + '\n' + laterSpan.expectedText
  manualFill.editTargets[0].replacementPlacement = { spanIndex: 1 }
  pending.set('SOURCE-MANUAL-CHECK', { promise: Promise.resolve({ currentRevisionId: null, draftRevisionId: revision.id, draft: { ...copy(revision), graph: { ...copy(graph), graph: { ...copy(graph.graph), variables: [manualFill] } } }, current: null, sources: [source] }) })
  props.projectId = 'SOURCE-MANUAL-CHECK'; await settle(); await choose()
  control('data-target-action').props.onChange({ target: { value: 'check' } }); await settle()
  await click('data-review-save-form'); await click('data-review-save-bottom')
  let manualTarget = writes.at(-1)[1].graph.variables[0].editTargets[0]
  assert.equal(manualTarget.action, 'check')
  assert.equal(manualTarget.proposedAction, 'fill')
  assert.equal(manualTarget.replacementPlacement.spanIndex, 1, 'Pausing an edit preserves the explicitly chosen replacement span')
  assert.equal(manualTarget.valueSourceId, item.id)
  assert.equal(manualTarget.replacementEn, 'Keep manual wording')
  control('data-target-action').props.onChange({ target: { value: 'delete' } }); await settle()
  await click('data-review-save-form'); await click('data-review-save-bottom')
  manualTarget = writes.at(-1)[1].graph.variables[0].editTargets[0]
  assert.equal(manualTarget.action, 'delete')
  assert.equal(manualTarget.proposedAction, undefined)
  assert.equal(manualTarget.valueSourceId, undefined, 'Explicit deletion still clears unused answer configuration')
  control('data-source-workspace').getBoundingClientRect=()=>({height:800});await click('data-source-immersive')
  assert.equal(nativeWindow.document.body.style.overflow,'hidden')
  receipt={source:control('data-test-pdf').props['data-source-url'],page:3,words:[{itemIndex:0,start:0,end:1,text:'a',context:'a',rectangle:{x:40,y:110,width:10,height:12}}]}
  await click('data-test-pick')
  assert.ok(control('data-selection-pending'),'An unbound selection remains available for manual operations')
  await title('Use as affected passage')
  await click('data-review-save-form');await click('data-review-save-bottom')
  assert.equal(writes.at(-1)[1].graph.variables[0].editTargets.at(-1).action,'check','An unbound manual target remains pending instead of becoming executable')
  control('data-manual-source-paragraph').props['onUpdate:modelValue']('p1');await settle()
  assert.ok(control('data-use-whole-source-paragraph'))
  await click('data-use-whole-source-paragraph')
  assert.equal(control('data-selection-pending'),undefined,'Explicitly choosing a verified source paragraph supplies exact native authority')
  api.reviewReading=async()=>{throw Error('TEST source reading failure')}
  await editor.value.selectSource('41');await settle()
  assert.equal(control('data-source-workspace').props.role,'dialog')
  assert.ok(control('data-source-immersive'),'A source-load failure keeps the exit control available')
  assert.match(text(control('data-source-workspace')),/TEST source reading failure/)
  const snapshots=loaded.loadLocal(path.join(__dirname,'../src/drafting/source-review-state.ts')).sourceReviewDrafts
  const interrupted=deferred();pending.set('SOURCE-LOADING-PRESERVE',interrupted)
  snapshots.set('SOURCE-LOADING-PRESERVE',{retained:'unsaved previous instance'})
  props.projectId='SOURCE-LOADING-PRESERVE';await settle()
  app.unmount();assert.equal(snapshots.get('SOURCE-LOADING-PRESERVE').retained,'unsaved previous instance','A loading instance must not delete the prior dirty snapshot during consecutive hot updates')
  assert.equal(nativeWindow.document.body.style.overflow,'auto','Unmount releases immersive scrolling')
  console.log('PASS: immersive original reader preserves selections and unsaved edits, exact multi-span targets, protected revisions, no adoption and stale-project guards')
})().catch(error=>{console.error(error);process.exitCode=1})
