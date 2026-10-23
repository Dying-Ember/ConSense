// Synthetic source/graph fixtures; this tests controls and reducer transactions, not source quality.
const assert = require('node:assert/strict'), path = require('node:path'), vue = require('vue')
const { loadVue } = require('./helpers/load-vue.cjs')
const { renderer, find, text, settle } = require('./helpers/render-controls.cjs')
const { loadTypeScript } = require('./helpers/load-typescript.cjs')
const { createReviewReducer } = loadTypeScript(path.join(__dirname, '../src/drafting/source-review/reducer.js'), { globals: { structuredClone } })
const copy = value => JSON.parse(JSON.stringify(value))
const sources = ['NTT','SCT'].map((fileKey,index) => ({ id: String(41+index), docId: fileKey.toLowerCase(), editionId: fileKey+'-TEST', fileKey, fileName: 'SYNTHETIC '+fileKey+'.docx', format: 'docx', sourceSha256: String(index+1).repeat(64), evidenceStatus: 'bound', canEdit: true, parseStatus: 'PARSED' }))
const target = (docId, quote, action='keep') => ({ docId, sourceSha256:sources.find(source=>source.id===docId).sourceSha256, text:quote, action, native:{ paragraphId:'p-'+quote,paragraphSha256:'f'.repeat(64),start:0,end:quote.length,expectedText:quote } })
const node = (id,name,docId='41') => ({ id,nameZh:name,nameEn:name,docIds:[docId],type:{kind:'boolean'},role:{kind:'input'},status:'pending',parentId:null,options:[],sourceAnchors:[{docId,text:'SOURCE '+name,sourceSha256:sources.find(source=>source.id===docId).sourceSha256}],editTargets:[],sourceUnitIds:[],relatedInputIds:[],relations:[],targets:[],evidence:[] })
function fixture() {
  const main=node('PRIVATE-MAIN','Issue tender documents electronically?'), duplicate=node('PRIVATE-DUP','Duplicate electronic question'), third=node('PRIVATE-THIRD','Third duplicate'), envelope=node('PRIVATE-ENVELOPE','Two-envelope tendering')
  const donor=node('PRIVATE-DONOR','Source treatment check','42'); donor.type.kind='text';donor.role.kind='verification';donor.definitionKind='source_seed';donor.manual=false
  donor.correction={kind:'rule',branches:[{id:'PRIVATE-OLD-BRANCH',mode:'all',clauses:[{inputId:envelope.id,operator:'yes',value:''}],effectZh:'旧条件',effectEn:'Existing prerequisite'}]}
  donor.editTargets=[{...target('41','NTT2 Alternative (a)','keep'),branchId:'PRIVATE-OLD-BRANCH'},{...target('42','SCT5 Alternative (b)','delete'),branchId:'PRIVATE-OLD-BRANCH'}, {...target('42','Unassigned original passage','check'),needsRebind:true}]
  return { variables:[main,duplicate,third,envelope,donor],events:[],sourceReviews:{},nextId:1,blank:false,guidanceMode:'recognized' }
}
function controls(root) {
  const get=(attr,value)=>find(root,node=>value===undefined?node.props?.[attr]!==undefined:node.props?.[attr]===value)
  return {get,async click(attr,value){const n=get(attr,value);assert.ok(n,attr);assert.notEqual(n.props.disabled,true,'Enabled '+attr);await n.props.onClick();await settle()},async model(attr,value,key){const n=get(attr,key);assert.ok(n,attr);n.props['onUpdate:modelValue'](value);await settle()}}
}
const Component=loadVue(path.join(__dirname,'../src/components/DraftingQuestionBatch.vue'),{globals:{structuredClone}}).default
async function prerequisiteSearchTest() {
  const state=fixture(),duration=node('PRIVATE-DURATION','Contract duration in months');duration.nameZh='合约工期（月）';duration.type.kind='number';state.variables.push(duration)
  state.variables.find(item=>item.id==='PRIVATE-ENVELOPE').nameZh='双信封'
  state.variables.find(item=>item.id==='PRIVATE-THIRD').status='excluded'
  const props=vue.reactive({state,ids:['PRIVATE-MAIN','PRIVATE-DONOR'],sources,locale:'en'}),drafts=[],events=[],original=copy(state),key='PRIVATE-DONOR:0:0'
  const host=renderer(),app=host.createApp({render:()=>vue.h(Component,{...props,onDraft:value=>drafts.push(copy(value)),onBranches:(...args)=>events.push(args)})});app.mount(host.root);await settle()
  const c=controls(host.root),picker=()=>c.get('data-branch-prerequisite',key)
  const option=id=>find(picker(),item=>item.props?.['data-picker-option']===id)
  const search=async value=>{c.get('data-branch-prerequisite-search',key).props.onInput({target:{value}});await settle()}
  await c.model('data-branch-route','keep','PRIVATE-DONOR:0:yes');await c.model('data-branch-route','delete','PRIVATE-DONOR:0:no')
  await c.click('data-branch-add-prerequisite','PRIVATE-DONOR:0')
  assert.equal(option('PRIVATE-MAIN'),undefined);assert.equal(option('PRIVATE-DONOR'),undefined);assert.equal(option('PRIVATE-THIRD'),undefined)
  await search('双信封');assert(option('PRIVATE-ENVELOPE'));assert.equal(option('PRIVATE-DURATION'),undefined)
  await option('PRIVATE-ENVELOPE').props.onClick();await settle()
  assert.equal(drafts.at(-1).assignments['PRIVATE-DONOR:0'].extra[0].operator,'yes','Selecting a Boolean retains the existing operator reset')
  await search('合同期限');assert(option('PRIVATE-DURATION'));assert.equal(picker().props['data-selected-id'],'PRIVATE-ENVELOPE')
  assert.match(text(find(picker(),item=>item.props?.['data-picker-current']!==undefined)),/Two-envelope/)
  await search('no matching variable');assert.match(text(find(picker(),item=>item.props?.['data-picker-count']!==undefined)),/^0 /)
  assert.deepEqual(copy(state),original);assert.deepEqual(events,[],'Searching and selecting prerequisites does not apply a graph transaction')
  props.state.variables.find(item=>item.id==='PRIVATE-ENVELOPE').status='excluded';await settle()
  assert.notEqual(option('PRIVATE-ENVELOPE').props.disabled,undefined,'An excluded current reference stays visible and disabled')
  assert.match(text(c.get('data-branch-problem')),/retained prerequisite is no longer selectable/)
  const draft=copy(drafts.at(-1));app.unmount()
  const restored=renderer(),restoredApp=restored.createApp({render:()=>vue.h(Component,{...props,draft})});restoredApp.mount(restored.root);await settle()
  const restoredControls=controls(restored.root),retained=restoredControls.get('data-branch-prerequisite',key)
  assert.equal(restoredControls.get('data-branch-prerequisite-search',key).props.value,'no matching variable','Search survives the existing batch draft restore')
  assert.equal(retained.props['data-selected-id'],'PRIVATE-ENVELOPE');assert.equal(restoredControls.get('data-branch-commit').props.disabled,true)
  restoredApp.unmount();console.log('PASS: prerequisite search preserves selections and drafts, filters primary/source/excluded variables and blocks invalid retained references without applying changes')
}
async function childTest() {
  const state=fixture(), original=copy(state), events=[], drafts=[]
  const props=vue.reactive({state,ids:['PRIVATE-MAIN','PRIVATE-DONOR'],sources,locale:'en'})
  const host=renderer(),app=host.createApp({render:()=>vue.h(Component,{...props,onBranches:(...args)=>events.push(args),onDraft:value=>drafts.push(copy(value))})});app.mount(host.root);await settle()
  const c=controls(host.root)
  const actionOptions=c.get('data-branch-route','PRIVATE-DONOR:0:yes').options.map(text)
  assert.equal(actionOptions.length,8,'One pending option and seven business actions; aliases cannot create duplicate labels')
  assert.equal(new Set(actionOptions).size,actionOptions.length,'Retain and Not used appear only once')
  await c.click('data-batch-mode','duplicates')
  assert.match(text(c.get('data-batch-merge-problem')),/Value shapes differ/,'The real boolean business / text source check mismatch has a concrete reason')
  assert.equal(c.get('data-batch-confirm-merge').props.disabled,true)
  assert.doesNotMatch(text(host.root),/PRIVATE-|p-NTT|[a-f0-9]{64}/,'No internal identities appear in review text')
  await c.click('data-batch-mode','branches')
  assert.match(text(c.get('data-batch-comparison')),/0 amendment targets/,'The primary question honestly starts with no targets')
  assert.equal(c.get('data-branch-commit').props.disabled,true)
  await c.model('data-route-name-en','Electronic issue','yes');await c.model('data-route-name-zh','电子发行','yes')
  await c.model('data-route-name-en','Paper issue','no');await c.model('data-route-name-zh','纸质发行','no')
  await c.model('data-branch-route','keep','PRIVATE-DONOR:0:yes');await c.model('data-branch-route','delete','PRIVATE-DONOR:0:no')
  await c.model('data-branch-route','delete','PRIVATE-DONOR:1:yes');await c.model('data-branch-route','keep','PRIVATE-DONOR:1:no')
  assert.match(text(c.get('data-branch-preview')),/Electronic issue.*NTT.*Alternative \(a\).*Retain.*Two-envelope tendering.*SCT.*Alternative \(b\).*Delete.*Paper issue.*NTT.*Alternative \(a\).*Delete.*SCT.*Alternative \(b\).*Retain/s,'Both paths group source quotes, reverse actions and retained prerequisites by file')
  await c.model('data-branch-ack',true);await c.click('data-branch-commit')
  assert.equal(events.length,1);assert.equal(events[0][1],'PRIVATE-MAIN')
  let next=state;const reduce=createReviewReducer({sources}).reduce
  for(const action of events[0][0]) { const result=reduce(next,{...action,at:'SYNTHETIC'});assert.notEqual(result,next);next=result }
  const main=next.variables.find(item=>item.id==='PRIVATE-MAIN'),donor=next.variables.find(item=>item.id==='PRIVATE-DONOR')
  assert.equal(main.editTargets.length,4);assert.equal(donor.editTargets.length,1,'Moved targets no longer execute from the previous owner')
  assert.equal(donor.editTargets[0].text,'Unassigned original passage','Unselected targets remain exactly where they were')
  assert.equal(main.correction.needsRebind,true);assert.equal(donor.correction.needsRebind,true)
  assert.deepEqual(main.correction.branches.map(branch=>branch.clauses[0].operator),['yes','no','yes','no'],'One business answer selects mutually exclusive routes for each exact position')
  assert.deepEqual(Array.from(main.editTargets,item=>item.action),['keep','delete','delete','keep'],'The same NTT/SCT positions have opposite actions under the two answers')
  for(const branch of main.correction.branches)assert.deepEqual(branch.clauses[1],{inputId:'PRIVATE-ENVELOPE',operator:'yes',value:''},'Two-envelope prerequisite survives each target move')
  for(const item of main.editTargets) { assert.equal(item.needsRebind,true);assert.ok(main.correction.branches.some(branch=>branch.id===item.branchId));assert.deepEqual(copy(item.native),original.variables[4].editTargets.find(old=>old.text===item.text).native) }
  assert.deepEqual(state,original,'Reviewing and emitting a proposal cannot mutate the existing graph')
  const lastDraft=drafts.at(-1);app.unmount()
  const restored=renderer(),restoredApp=restored.createApp({render:()=>vue.h(Component,{...props,draft:lastDraft})});restoredApp.mount(restored.root);await settle()
  assert.match(text(controls(restored.root).get('data-branch-preview')),/Electronic issue.*NTT.*Paper issue.*SCT/s,'An uncommitted branch proposal survives remount')
  restoredApp.unmount()
  const orState=fixture();orState.variables[4].correction.branches[0].mode='any';orState.variables[4].correction.branches[0].clauses.push({inputId:'PRIVATE-THIRD',operator:'yes'})
  const orHost=renderer(),orApp=orHost.createApp({render:()=>vue.h(Component,{state:orState,ids:props.ids,sources,locale:'en'})});orApp.mount(orHost.root);await settle();const orControls=controls(orHost.root);await orControls.click('data-batch-mode','branches')
  assert.equal(orControls.get('data-branch-route','PRIVATE-DONOR:0:yes').props.disabled,true,'An OR expression cannot be silently flattened while moving it')
  orApp.unmount()
}

async function oldAnswerAndChoiceTest() {
  const state=fixture(),old=state.variables[4]
  old.type.kind='boolean';old.role.kind='input';old.manual=true;old.definitionKind='discovered';old.correction=null;old.editTargets=old.editTargets.slice(0,1);delete old.editTargets[0].branchId
  const ref=node('PRIVATE-REF','Treatment with legacy answer','42');ref.role.kind='verification';ref.type.kind='text';ref.correction={kind:'rule',branches:[{id:'legacy-predicate',mode:'all',clauses:[{inputId:old.id,operator:'no'},{inputId:'PRIVATE-ENVELOPE',operator:'yes'}]}]};ref.editTargets=[{...target('42','SCT with old answer'),branchId:'legacy-predicate'}];state.variables.push(ref)
  const events=[],host=renderer(),app=host.createApp({render:()=>vue.h(Component,{state,ids:['PRIVATE-MAIN',old.id,ref.id],sources,locale:'en',onBranches:(...args)=>events.push(args)})});app.mount(host.root);await settle();const c=controls(host.root)
  assert.equal(c.get('data-branch-answer-map',old.id).props.disabled,true,'Do not remove an independent answer while target routes remain incomplete')
  await c.model('data-branch-route','keep',old.id+':0:yes');await c.model('data-branch-route','delete',old.id+':0:no')
  await c.model('data-branch-route','keep',ref.id+':0:yes')
  await c.model('data-branch-answer-map','no',old.id);await c.model('data-branch-ack',true);await c.click('data-branch-commit')
  let next=state;for(const action of events[0][0])next=createReviewReducer({sources}).reduce(next,action)
  const mapped=next.variables.find(item=>item.id===old.id),main=next.variables[0]
  assert.equal(mapped.role.kind,'derived');assert.deepEqual(mapped.sourceAnchors,old.sourceAnchors,'The old answer identity and original evidence remain available to consumers')
  assert.deepEqual(mapped.correction.branches.map(branch=>branch.resultValue),[false,true],'Explicit reverse mapping derives the old boolean from the single primary answer')
  const migrated=main.correction.branches.find(branch=>main.editTargets.some(target=>target.text==='SCT with old answer'&&target.branchId===branch.id))
  assert.equal(migrated.clauses[1].inputId,'PRIVATE-MAIN');assert.equal(migrated.clauses[1].operator,'yes','The old boolean predicate is translated through the explicit inverse mapping')
  assert.equal(migrated.clauses[2].inputId,'PRIVATE-ENVELOPE','A genuinely separate two-envelope prerequisite remains separate')
  app.unmount()
  const blocked=fixture();blocked.variables[4].role.kind='input';blocked.variables[4].type.kind='boolean';blocked.variables[4].manual=true
  const blockedHost=renderer(),blockedApp=blockedHost.createApp({render:()=>vue.h(Component,{state:blocked,ids:['PRIVATE-MAIN','PRIVATE-DONOR'],sources,locale:'en'})});blockedApp.mount(blockedHost.root);await settle();assert.match(text(controls(blockedHost.root).get('data-branch-old-inputs')),/original treatment rules do not clearly separate/,'Rules without a clear self route remain explicit blockers instead of being overwritten');blockedApp.unmount()
  const legacy=fixture();legacy.variables[4].role.kind='input';legacy.variables[4].type.kind='boolean';legacy.variables[4].manual=true;legacy.variables[4].correction={kind:'rule',mode:'all',clauses:[{inputId:'PRIVATE-DONOR',operator:'no'}]}
  const legacyHost=renderer(),legacyApp=legacyHost.createApp({render:()=>vue.h(Component,{state:legacy,ids:['PRIVATE-MAIN','PRIVATE-DONOR'],sources,locale:'en'})});legacyApp.mount(legacyHost.root);await settle();assert.match(text(controls(legacyHost.root).get('data-branch-old-inputs')),/original treatment rules do not clearly separate/,'A legacy top-level rule stays reviewable without crashing or silently rewriting it');legacyApp.unmount()
  const paper=fixture(),paperOld=paper.variables[4]
  paperOld.role.kind='input';paperOld.type.kind='boolean';paperOld.manual=true
  paperOld.correction.branches[0].clauses=[{inputId:paperOld.id,operator:'no'}, {inputId:'PRIVATE-ENVELOPE',operator:'yes'}]
  paperOld.editTargets.forEach(target=>target.branchId='PRIVATE-OLD-BRANCH');delete paperOld.editTargets[2].native
  const originalPaperRule=copy(paperOld.correction),paperEvents=[],paperHost=renderer(),paperApp=paperHost.createApp({render:()=>vue.h(Component,{state:paper,ids:['PRIVATE-MAIN',paperOld.id],sources,locale:'en',onBranches:actions=>paperEvents.push(actions)})});paperApp.mount(paperHost.root);await settle();const paperControls=controls(paperHost.root)
  for(let index=0;index<3;index++){await paperControls.model('data-branch-route','delete',paperOld.id+':'+index+':yes');await paperControls.model('data-branch-route','keep',paperOld.id+':'+index+':no')}
  assert.match(text(paperControls.get('data-branch-problem')),/old variable’s routing condition/,'An old self route cannot masquerade as an extra independent prerequisite')
  await paperControls.model('data-branch-answer-map','no',paperOld.id);await paperControls.model('data-branch-ack',true);await paperControls.click('data-branch-commit')
  let paperResult=paper;for(const action of paperEvents[0])paperResult=createReviewReducer({sources}).reduce(paperResult,action)
  const paperPrimary=paperResult.variables[0],paperDerived=paperResult.variables[4]
  assert.equal(paperPrimary.editTargets.length,6);assert.equal(paperDerived.editTargets.length,0);assert.equal(paperDerived.role.kind,'derived')
  assert.deepEqual(copy(paperDerived.correction.supersededCorrection),originalPaperRule,'The complete replaced routing rule stays in inert revision metadata')
  for(const branch of paperPrimary.correction.branches){assert.equal(branch.clauses.length,2);assert.equal(branch.clauses[0].inputId,'PRIVATE-MAIN');assert.equal(branch.clauses[1].inputId,'PRIVATE-ENVELOPE','Only the old self route is replaced; the two-envelope prerequisite remains')}
  assert.equal(paperPrimary.editTargets.filter(target=>!target.native).length,2,'Unbound source targets remain unbound pending targets on both routes')
  assert.ok(paperPrimary.editTargets.every(target=>target.needsRebind))
  assert.deepEqual([...paperPrimary.docIds].sort(),['41','42'],'The existing TARGETS reducer declares both NTT and SCT documents on the primary node')
  for(const item of paperResult.variables.filter(item=>item.status!=='excluded'))for(const group of ['sourceAnchors','editTargets','evidence'])for(const entry of item[group]??[])if(entry.docId)assert.ok(item.docIds.includes(entry.docId),'Backend GRAPH_SOURCE_OUTSIDE_DECLARED_DOCUMENTS contract: '+item.id+' / '+group)
  if(process.env.DRAFTING_BRANCH_ARTIFACT) require('node:fs').writeFileSync(process.env.DRAFTING_BRANCH_ARTIFACT,JSON.stringify({fixture:'SYNTHETIC emitted commit actions passed through the real review reducer; no original-document or business acceptance',mainId:'PRIVATE-MAIN',envelopeId:'PRIVATE-ENVELOPE',oldId:paperOld.id,graph:{kind:'consense-template-graph-v1',graph:paperResult,sources,selectedDocuments:{NTT:'41',SCT:'42'}},expectations:paperOld.editTargets.map(target=>({docId:target.docId,text:target.text,electronic:'delete',paper:'keep',twoEnvelopeRequired:true,exactRange:!!target.native}))},null,2))
  paperApp.unmount()
  const choice=fixture();choice.variables[0].type.kind='choice';choice.variables[0].options=[{value:'L10Pro',labelZh:'电子发行',labelEn:'Electronic issue'},{value:'Hardcopy',labelZh:'纸质发行',labelEn:'Paper issue'}]
  const choiceEvents=[],choiceHost=renderer(),choiceApp=choiceHost.createApp({render:()=>vue.h(Component,{state:choice,ids:['PRIVATE-MAIN','PRIVATE-DONOR'],sources,locale:'en',onBranches:actions=>choiceEvents.push(actions)})});choiceApp.mount(choiceHost.root);await settle();const choiceControls=controls(choiceHost.root)
  await choiceControls.model('data-branch-route','keep','PRIVATE-DONOR:0:choice-0');await choiceControls.model('data-branch-route','delete','PRIVATE-DONOR:0:choice-1');await choiceControls.model('data-branch-ack',true);await choiceControls.click('data-branch-commit')
  const rule=choiceEvents[0].find(action=>action.type==='RULE'&&action.id==='PRIVATE-MAIN')
  assert.deepEqual(copy(rule.correction.branches.map(branch=>[branch.clauses[0].operator,branch.clauses[0].value])),[['eq','L10Pro'],['eq','Hardcopy']],'Existing choice values select branches without introducing another answer')
  choiceApp.unmount()
}

async function parentTest() {
  const state=fixture(),graph={kind:'consense-template-graph-v1',graph:state,sources,selectedDocuments:{NTT:'41',SCT:'42'}},revision={id:'draft-TEST',graph,status:'DRAFT',sourceStale:false},writes=[]
  const superseded={kind:'rule',branches:[{id:'archived-route',mode:'all',clauses:[{inputId:'PRIVATE-MAIN',operator:'no'}],effectEn:'Historical source treatment'}]}
  state.variables[0].correction={kind:'rule',branches:[{id:'existing-route',mode:'all',clauses:[{inputId:'PRIVATE-ENVELOPE',operator:'yes'}],effectZh:'原已有路线',effectEn:'Existing route before review'}],supersededCorrection:superseded,needsRebind:true}
  const api={reviewHead:async()=>({currentRevisionId:null,draftRevisionId:revision.id,current:null,draft:copy(revision),sources}),reviewReading:async(_project,id)=>({...sources.find(source=>source.id===id),paragraphs:[],blocks:[],sourceMembers:[],guidanceNotes:[],markerCandidates:[],groups:[],conditions:[],issues:[]}),reviewPreview:async()=>{throw Error('SYNTHETIC preview unavailable')},reviewSave:async(...args)=>{writes.push(copy(args));return {...copy(revision),graph:copy(args[1])}}}
  const Modal={props:['open'],setup:(props,{slots})=>()=>props.open?vue.h('section',slots.default?.()):null}
  const native=new(require('jsdom').JSDOM)('').window
  const loaded=loadVue(path.join(__dirname,'../src/components/DraftingSourceReview.vue'),{boundaries:{'@/api':{draftingApi:api},'@/api/source-locations':{reviewSourceBindings:async()=>{throw Error('SYNTHETIC geometry unavailable')}},'./AppModal.vue':{default:Modal},'./DraftingPdfPreview.vue':{default:{render:()=>null}}},globals:{structuredClone,window:native,document:native.document,Element:native.Element,URL}})
  const editor=vue.ref(),props=vue.reactive({projectId:'BATCH-PARENT',locale:'en'}),host=renderer()
  const makeApp=()=>host.createApp({render:()=>vue.h(loaded.default,{...props,ref:editor})})
  let app=makeApp();app.mount(host.root);await settle();const c=controls(host.root)
  for(const id of ['PRIVATE-MAIN','PRIVATE-DUP','PRIVATE-THIRD']){c.get('data-review-check',id).props.onChange();await settle()}
  await c.click('data-review-open-batch');assert.match(text(c.get('data-batch-comparison')),/Third duplicate/);await c.click('data-batch-mode','duplicates')
  await c.model('data-batch-ack',true);await c.click('data-batch-confirm-merge');assert.equal(writes.length,0,'Confirming a batch records one local change without saving, adopting or generating')
  await editor.value.save();assert.equal(writes.length,1)
  assert.deepEqual(writes[0][1].graph.variables.slice(0,3).map(item=>item.status),['changed','excluded','excluded'])
  const undo=find(host.root,node=>node.tag==='button'&&text(node).trim()==='Undo');await undo.props.onClick();await settle()
  await editor.value.save();assert.deepEqual(writes[1][1].graph.variables.slice(0,3).map(item=>item.status),['pending','pending','pending'],'One undo restores the entire batch')
  // A source change and a parent draft update must not reset route edits or recursive-render.
  await editor.value.locateQuestion('PRIVATE-MAIN','draft-TEST');await settle();await c.click('data-review-edit-question');await c.model('data-review-name-en','RECORDED LOCAL GRAPH');await c.click('data-review-save-form');await c.click('data-review-check-current')
  await editor.value.locateQuestion('PRIVATE-DONOR','draft-TEST');await settle();await c.click('data-review-check-current');await c.click('data-review-open-batch')
  await c.model('data-route-name-en','Electronic route kept through remount','yes');await c.model('data-branch-route','keep','PRIVATE-DONOR:0:yes');await c.model('data-branch-route','delete','PRIVATE-DONOR:0:no')
  assert.equal(c.get('data-route-name-en','yes').value,'Electronic route kept through remount','Parent draft emissions and new selections cannot reset an edited path')
  const beforeBranchWrites=writes.length;app.unmount();app=makeApp();app.mount(host.root);await settle()
  assert.equal(c.get('data-route-name-en','yes').value,'Electronic route kept through remount','The open batch proposal survives component replacement')
  assert.match(text(c.get('data-batch-comparison')),/RECORDED LOCAL GRAPH/,'A graph edit that was never saved to the server survives the same remount')
  assert.equal(writes.length,beforeBranchWrites)
  await c.model('data-branch-ack',true);await c.click('data-branch-commit');assert.equal(writes.length,beforeBranchWrites,'The branch transaction records local changes only')
  await editor.value.save();const branched=writes.at(-1)[1].graph
  assert.equal(branched.variables[0].editTargets.length,2);assert.equal(branched.variables[4].editTargets.length,2,'The parent applies ownership changes and mutually exclusive routes together')
  const branchUndo=find(host.root,node=>node.tag==='button'&&text(node).trim()==='Undo');await branchUndo.props.onClick();await settle();await editor.value.save()
  assert.equal(writes.at(-1)[1].graph.variables[0].editTargets.length,0);assert.equal(writes.at(-1)[1].graph.variables[4].editTargets.length,3,'One undo restores both the primary and original owners')
  // Keep a real unsaved question form and a cross-list selection through component remount.
  await editor.value.locateQuestion('PRIVATE-MAIN','draft-TEST');await settle();await c.click('data-review-edit-question');await c.model('data-review-name-en','UNSAVED HUMAN QUESTION')
  await c.click('data-review-check-current');const beforeWrites=writes.length;app.unmount();app=makeApp();app.mount(host.root);await settle()
  assert.equal(c.get('data-review-name-en').value,'UNSAVED HUMAN QUESTION','Existing unsaved form survives the new batch component and a remount')
  assert.match(text(c.get('data-review-selection')),/1 items selected/)
  assert.equal(writes.length,beforeWrites,'Restoring drafts cannot write the server')
  await c.click('data-review-save-form')
  const effect=find(host.root,node=>node.tag==='textarea'&&node.value==='Existing route before review');assert.ok(effect,'The existing branch is editable after restoring the unsaved form');effect.props['onUpdate:modelValue']('Human revised branch treatment');await settle();await c.click('data-review-save-form');await editor.value.save()
  assert.deepEqual(writes.at(-1)[1].graph.variables[0].correction.supersededCorrection,superseded,'A later ordinary branch edit retains the archived original correction without retaining its unresolved guard')
  app.unmount()
}
;(async()=>{await prerequisiteSearchTest();await childTest();await oldAnswerAndChoiceTest();await parentTest();console.log('PASS: multi-selection comparison, concrete merge guards, atomic batch/undo, one-answer branch target moves, reverse actions/mappings, choice routes, retained additional prerequisites, pending scope and graph/form/batch remount preservation')})().catch(error=>{console.error(error);process.exitCode=1})
