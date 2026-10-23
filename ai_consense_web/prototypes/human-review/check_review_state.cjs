'use strict';

// Offline prototype self-check: synthetic review records, no original documents or browser.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const script = fs.readFileSync(path.join(__dirname, 'prototype.logic.js'), 'utf8');
const cut = script.indexOf("  const source = JSON.parse($('source-data').textContent);");
assert.ok(cut > 0, 'Pure review-state boundary must remain identifiable.');
const anchorsBody = script.slice(script.indexOf('  function anchorsFor(v)'), script.indexOf('  function notify('));
const validateBody = script.slice(script.indexOf('  function validateClauses('), script.indexOf('  function openCorrections('));
const targetBody = script.slice(script.indexOf('  function readTargetFields('), script.indexOf('  function required('));
const item = (id, docId = 'ntt', kind = 'boolean') => ({
  id, nameZh: id, nameEn: id, questionZh: id, questionEn: id,
  type: {kind, zh: kind, en: kind}, role: {kind: 'input', zh: '资料', en: 'Input'},
  docIds: [docId], parentId: null, sourceUnitIds: ['note-' + id],
  evidence: [{docId, quote: 'source-' + id}], targets: [{docId, labelZh: 'target-' + id}],
  relatedInputIds: [], relations: [{branches: [{whenZh: id, whenEn: id, resultZh: id, resultEn: id}]}]
});
const fixture = {variables: [item('a'), item('b'), item('c'), item('n', 'ntt', 'number'), item('pdf', 'scc-pdf')],
  sourceUnits: [{id: 'note-a', docId: 'ntt'}]};
const context = vm.createContext({structuredClone,
  document: {getElementById: id => {assert.equal(id, 'prototype-data'); return {textContent: JSON.stringify(fixture)};}}
});
vm.runInContext(script.slice(0, cut) + `
  const source = {anchors: {'note-a': {ranges: [{page: 1, start: 0, end: 99}]}}};
  const rangesForUnit = id => source.anchors[id]?.ranges || [];
  let validationState;
  const getName = id => validationState.variables.find(v => v.id === id) || {};
  ${anchorsBody}
  ${validateBody}
  ${targetBody}
  globalThis.review = {initial, reduce, anchorsFor, validateClauses, validateBranch, validateTargets, readTargetFields,
    use: value => {validationState = value;}};
})();`, context);
const api = context.review;
const find = (state, id) => state.variables.find(v => v.id === id);
const step = (state, action) => api.reduce(state, {...action, at: 'self-check'});
const plain = value => JSON.parse(JSON.stringify(value));
const rule = inputId => ({kind: 'rule', mode: 'all', clauses: [{inputId, operator: 'yes', value: ''}], effectZh: '修改', effectEn: 'Amend'});
const base = () => {
  const state = api.initial();
  find(state, 'c').correction = rule('a');
  find(state, 'c').relatedInputIds = ['a'];
  find(state, 'c').status = 'confirmed';
  state.sourceReviews['note-c'] = true;
  return state;
};

let state = base();
const before = plain(state);
const aAnchor = {docId: 'ntt', page: 2, start: 4, end: 6, text: 'combined contracts'};
const bAnchor = {docId: 'ntt', page: 3, start: 8, end: 10, text: 'foundation works'};
let result = step(state, {type: 'SPLIT', id: 'a', mode: 'all', items: [
  {nameZh: '组合合同', nameEn: 'Combined contract', sourceAnchors: [aAnchor]},
  {nameZh: '基础工程', nameEn: 'Foundation works', sourceAnchors: [bAnchor]}
]});
assert.deepEqual(plain(state), before, 'Reducer must not mutate the pre-edit state.');
assert.equal(find(result, 'a').status, 'excluded');
assert.deepEqual(plain(api.anchorsFor(find(result, 'review-1'))), [aAnchor], 'Split child focuses on the exact selected words.');
assert.deepEqual(plain(api.anchorsFor(find(result, 'review-2'))), [bAnchor]);
assert.equal(find(result, 'c').correction.needsRebind, true);
assert.deepEqual(plain(find(result, 'c').relatedInputIds), ['review-1', 'review-2']);
assert.equal(result.sourceReviews['note-c'], undefined);

result = step(base(), {type: 'EDIT', id: 'a', values: {type: {kind: 'number', zh: '数字', en: 'Number'}}});
assert.equal(find(result, 'c').status, 'help');
assert.equal(find(result, 'c').correction.needsRebind, true);
api.use(result);
assert.throws(() => api.validateClauses(find(result, 'c').correction.clauses, find(result, 'c')), /Yes \/ No/);
result = step(base(), {type: 'EXCLUDE', id: 'a', reason: 'unnecessary'});
assert.equal(find(result, 'c').correction.needsRebind, true);
api.use(result);
assert.throws(() => api.validateClauses(find(result, 'c').correction.clauses, find(result, 'c')), /active question/);
result = step(base(), {type: 'EDIT', id: 'a', values: {type: {kind: 'boolean', zh: '是／否', en: 'Yes / No'}, role: {kind: 'fixed', zh: '固定', en: 'Fixed'}, fixedValue: 'No'}});
assert.equal(find(result, 'c').correction.needsRebind, true, 'Changing an input to fixed content requires dependent conditions to be reviewed.');

state = base();
find(state, 'a').correction = {kind: 'rule', mode: 'all', clauses: [{inputId: 'n', operator: 'ge', value: '12'}], effectZh: '保留十二个月', effectEn: 'Keep twelve months'};
find(state, 'a').relatedInputIds = ['n'];
result = step(state, {type: 'MERGE', id: 'a', targetId: 'b', reason: 'same question'});
assert.deepEqual(plain(find(result, 'b').correction), plain(find(state, 'a').correction), 'A sole manual rule must survive merge.');
assert.ok(find(result, 'b').relatedInputIds.includes('n'));
assert.equal(find(result, 'b').relations.length, 2);
assert.equal(find(result, 'b').evidence.length, 2);
assert.equal(find(result, 'c').correction.clauses[0].inputId, 'b');
assert.equal(find(result, 'a').status, 'excluded');
find(state, 'b').correction = rule('b');
assert.equal(step(state, {type: 'MERGE', id: 'a', targetId: 'b'}), state, 'Two manual rule sets require explicit review.');
state = base(); find(state, 'a').docIds = ['scc-word'];
assert.equal(step(state, {type: 'MERGE', id: 'a', targetId: 'pdf'}), state, 'Word/PDF SCC families cannot merge.');

for (const fixedId of ['a', 'b']) {
  state = base();
  Object.assign(find(state, fixedId), {role: {kind: 'fixed', zh: '固定', en: 'Fixed'}, fixedValue: 'Use the standard wording'});
  result = step(state, {type: 'MERGE', id: 'a', targetId: 'b', reason: 'same question'});
  assert.equal(find(result, 'b').role.kind, 'fixed', 'A unique fixed answer survives merge in either direction.');
  assert.equal(find(result, 'b').fixedValue, 'Use the standard wording');
}
state = base();
for (const id of ['a', 'b']) Object.assign(find(state, id), {role: {kind: 'fixed', zh: '固定', en: 'Fixed'}, fixedValue: 'Same wording'});
result = step(state, {type: 'MERGE', id: 'a', targetId: 'b', reason: 'same fixed answer'});
assert.equal(find(result, 'b').fixedValue, 'Same wording');
find(state, 'b').fixedValue = 'Different wording';
assert.equal(step(state, {type: 'MERGE', id: 'a', targetId: 'b'}), state, 'Conflicting fixed answers cannot be silently merged.');

state = base(); api.use(state);
api.validateClauses([{inputId: 'n', operator: 'ge', value: '39'}], find(state, 'c'));
api.validateClauses([{inputId: 'pdf', operator: 'yes', value: ''}], find(state, 'c'));
find(state, 'c').docIds=['scc-word'];
assert.throws(() => api.validateClauses([{inputId: 'pdf', operator: 'yes', value: ''}], find(state, 'c')), /active question/);
assert.throws(() => api.validateClauses([{inputId: 'n', operator: 'ge', value: 'many'}], find(state, 'c')), /valid number/);
result = step(base(), {type: 'SPLIT', id: 'a', mode: 'unknown', items: [
  {nameZh: '甲', nameEn: 'A', type: {kind: 'number', zh: '数字', en: 'Number'}, sourceAnchors: [aAnchor]},
  {nameZh: '乙', nameEn: 'B', type: {kind: 'text', zh: '文字', en: 'Text'}, sourceAnchors: [bAnchor]}
]});
assert.equal(find(result, 'review-1').type.kind, 'number');
assert.equal(find(result, 'review-2').type.kind, 'text');
assert.equal(find(result, 'review-1').correction.needsRebind, true);
assert.match(result.events[0].details.at(-1), /still need review/);
console.log('PASS: review-state self-check (split anchors, immutability, invalidation, merges, SCC isolation, condition guards).');
state=step(base(),{type:'CLEAR',guidanceMode:'minimum'});
assert.equal(state.variables.length,0);
assert.equal(state.guidanceMode,'minimum');
assert.deepEqual(plain(state.sourceReviews),{});
state=base();find(state,'b').docIds=['sct'];
result=step(state,{type:'MERGE',id:'a',targetId:'b',reason:'Same answer across documents'});
assert.deepEqual(plain(find(result,'b').docIds),['sct','ntt']);
state=base();
result=step(state,{type:'LINK',id:'a',anchor:{docId:'sct',page:2,start:0,end:1,wordIds:[0],text:'source'}});
assert.deepEqual(plain(find(result,'a').docIds),['ntt','sct']);
state=base();find(state,'a').docIds=['scc-word'];
assert.equal(step(state,{type:'LINK',id:'a',anchor:{docId:'scc-pdf',page:1,start:0,end:1}}),state);
console.log('PASS: minimum guidance start, cross-document sharing and two SCC source isolation.');
state=base();
find(state,'c').correction={kind:'rule',branches:[
  {id:'case-1',mode:'all',clauses:[{inputId:'a',operator:'yes',value:''}],effectZh:'保留',effectEn:'Keep'},
  {id:'case-2',mode:'all',clauses:[{inputId:'a',operator:'no',value:''}],effectZh:'删除',effectEn:'Delete'}
]};
find(state,'c').editTargets=[{...aAnchor,action:'fill',branchId:'case-1',valueSourceId:'a'}];
result=step(state,{type:'EDIT',id:'a',values:{type:{kind:'text',zh:'文字',en:'Text'}}});
assert.equal(find(result,'c').correction.needsRebind,true,'Every case must be invalidated when an input changes type.');
result=step(state,{type:'MERGE',id:'a',targetId:'b',reason:'Same input'});
assert.deepEqual(plain(find(result,'c').correction.branches.map(b=>b.clauses[0].inputId)),['b','b']);
assert.equal(find(result,'c').editTargets[0].valueSourceId,'b');
api.use(state);
api.validateTargets(find(state,'c'));
find(state,'c').editTargets[0].branchId='missing-case';
assert.throws(()=>api.validateTargets(find(state,'c')),/existing case/);
find(state,'c').editTargets[0]={...aAnchor,action:'replace'};
assert.throws(()=>api.validateTargets(find(state,'c')),/new wording/);
find(state,'c').editTargets[0].replacementEn='Not used';
api.validateTargets(find(state,'c'));
find(state,'c').editTargets[0].action='check';
assert.throws(()=>api.validateTargets(find(state,'c')),/Choose an action/);
console.log('PASS: multiple cases retain their conditions, merge references and text-action requirements.');
state=step(base(),{type:'ADD',docId:'ntt',anchor:aAnchor,reason:'Source correction',values:{nameZh:'补充',nameEn:'Added',type:{kind:'boolean'},role:{kind:'input'}}});
const addedId=state.variables.at(-1).id;
result=step(state,{type:'UNLINK',id:addedId,index:0});
assert.equal(find(result,addedId).missingSource,true);
assert.equal(find(result,addedId).sourceAnchors.length,0);
assert.equal(find(result,addedId).evidence.length,0);
assert.ok(result.events[0].details.includes(aAnchor.text),'Removed wording remains in the review history.');
result=step(result,{type:'LINK',id:addedId,anchor:bAnchor});
assert.equal(find(result,addedId).missingSource,false);
assert.equal(find(result,addedId).sourceAnchors[0].text,bAnchor.text);
console.log('PASS: incorrect source links can be removed and rebound without losing the history.');
state=base();
state.variables.push({...item('rows-a','ntt','list'),status:'pending'},{...item('rows-b','ntt','list'),status:'pending'});
find(state,'a').parentId='rows-a';find(state,'b').parentId='rows-b';api.use(state);
const scopedCase={mode:'all',effectZh:'保留',effectEn:'Keep'};
api.validateBranch({...scopedCase,scopeId:'rows-a',scopeMode:'any',clauses:[{inputId:'a',operator:'yes',value:''},{inputId:'n',operator:'ge',value:'39'}]},find(state,'c'));
assert.throws(()=>api.validateBranch({...scopedCase,scopeId:'rows-a',scopeMode:'any',clauses:[{inputId:'a',operator:'yes',value:''},{inputId:'b',operator:'yes',value:''}]},find(state,'c')),/same list item/);
assert.throws(()=>api.validateBranch({...scopedCase,scopeId:'',clauses:[{inputId:'a',operator:'yes',value:''}]},find(state,'c')),/same list item/);
console.log('PASS: conditions on repeating details bind to the same item, while project facts remain available.');

const scopedRule = inputId => ({kind:'rule',branches:[{id:'case-1',scopeId:'rows-a',scopeMode:'any',mode:'all',clauses:[{inputId,operator:'yes',value:''}],effectZh:'保留',effectEn:'Keep'}]});
const unchangedEdit = (value,parentId) => ({parentId,type:plain(value.type),role:plain(value.role),options:plain(value.options||[]),nameZh:value.nameZh,nameEn:value.nameEn});
state=base();
state.variables.push({...item('rows-a','ntt','list'),status:'pending'},{...item('rows-b','ntt','list'),status:'pending'});
find(state,'a').parentId='rows-a';find(state,'a').options=[];
find(state,'c').correction=scopedRule('a');find(state,'c').status='confirmed';
result=step(state,{type:'EDIT',id:'a',values:unchangedEdit(find(state,'a'),'rows-b')});
assert.equal(find(result,'c').status,'help');
assert.equal(find(result,'c').correction.needsRebind,true);
assert.equal(find(result,'a').parentId,'rows-b');

state=base();
state.variables.push({...item('rows-a','ntt','list'),status:'pending'},{...item('rows-b','ntt','list'),status:'pending'},
  {...item('detail-group','ntt','record'),parentId:'rows-a',options:[]});
find(state,'a').parentId='detail-group';find(state,'c').correction=scopedRule('a');find(state,'c').status='confirmed';
result=step(state,{type:'EDIT',id:'detail-group',values:unchangedEdit(find(state,'detail-group'),'rows-b')});
assert.equal(find(result,'c').status,'help');
assert.equal(find(result,'c').correction.needsRebind,true);
assert.equal(find(result,'a').parentId,'detail-group','Moving the group preserves the descendant relationship.');

state=base();
result=step(state,{type:'TARGETS',id:'a',targets:[{...aAnchor,docId:'sct',action:'keep'}]});
assert.deepEqual(plain(find(result,'a').docIds),['ntt','sct']);
assert.equal(find(result,'a').editTargets[0].docId,'sct');

state=base();
result=step(state,{type:'SPLIT',id:'a',mode:'all',items:[
  {nameZh:'第一问',nameEn:'First',sourceAnchors:[{...aAnchor,docId:'scc-word'}]},
  {nameZh:'第二问',nameEn:'Second',sourceAnchors:[{...bAnchor,docId:'scc-pdf'}]}
]});
assert.equal(result,state,'The reducer rejects a split that joins the two SCC editions.');

state=base();find(state,'a').missingSource=true;
result=step(state,{type:'SPLIT',id:'a',mode:'unknown',items:[
  {nameZh:'第一问',nameEn:'First',sourceAnchors:[aAnchor]},
  {nameZh:'第二问',nameEn:'Second',sourceAnchors:[bAnchor]}
]});
assert.ok(result.variables.filter(value=>value.originId==='a').every(value=>value.missingSource===false),
  'Freshly selected split sources clear the original missing-source flag.');
console.log('PASS: moving detail groups invalidates scoped conditions; cross-document targets save their scope; split sources preserve SCC isolation and recover missing anchors.');

state=base();
find(state,'a').docIds=['ntt'];
state.variables.push({...item('word-group','scc-word','record'),docIds:['ntt','scc-word']});
find(state,'c').docIds=['scc-pdf'];
find(state,'c').relatedInputIds=[];
find(state,'c').correction={kind:'rule',mode:'all',clauses:[{inputId:'a',operator:'yes',value:''}],effectZh:'保留',effectEn:'Keep'};
find(state,'c').editTargets=[{...aAnchor,docId:'scc-pdf',action:'fill',valueSourceId:'a'}];
result=step(state,{type:'EDIT',id:'a',values:unchangedEdit(find(state,'a'),'word-group')});
assert.equal(find(result,'c').correction.needsRebind,true);
assert.equal(find(result,'c').editTargets[0].needsRebind,true);
const linkedUnrelated=step(result,{type:'LINK',id:'b',anchor:{...bAnchor,docId:'sct'}});
assert.deepEqual(plain(find(linkedUnrelated,'b').docIds),['ntt','sct'],
  'A retained invalid relationship must not block unrelated source corrections.');
const targetUnrelated=step(result,{type:'TARGETS',id:'b',targets:[{...bAnchor,action:'keep'}]});
assert.equal(find(targetUnrelated,'b').editTargets.length,1);
assert.equal(step(result,{type:'LINK',id:'a',anchor:{...aAnchor,docId:'scc-pdf'}}),result,
  'Ignoring invalid historical edges must not weaken current source and ancestor isolation.');
console.log('PASS: unresolved historical references stay recoverable without blocking unrelated review work or weakening SCC isolation.');

const targetForm={get:key=>({'action-0':'fill','source-0':'a','prefix-0':'Section ','suffix-0':':-'}[key])};
let formattedTarget=plain(api.readTargetFields(targetForm,{targets:[{...aAnchor,prefix:'old',suffix:'old'}]}))[0];
assert.equal(formattedTarget.valueSourceId,'a');
assert.equal(formattedTarget.prefix,'Section ','The space before the answer is part of the wording.');
assert.equal(formattedTarget.suffix,':-','A shared answer can retain punctuation specific to this passage.');
formattedTarget=plain(api.readTargetFields({get:key=>({'action-0':'delete'}[key])},{targets:[formattedTarget]}))[0];
for(const key of ['valueSourceId','prefix','suffix'])assert.equal(formattedTarget[key],undefined,
  'Changing to deletion clears obsolete answer formatting.');
console.log('PASS: answer-sourced targets retain passage-specific prefixes and suffixes without changing the shared answer.');

const readBranchesBody=script.slice(script.indexOf('  function readBranchFields('),script.indexOf('  function readTargetFields('));
vm.runInContext(`(() => {let owner;const current=()=>owner;${readBranchesBody}
  globalThis.readTestBranches=(value,form,draft)=>{owner=value;return readBranchFields(form,draft);};})();`,context);
state=base();api.use(state);
const resultOwner=find(state,'c');resultOwner.role.kind='derived';
const resultCase={id:'case-1',mode:'all',clauses:[{inputId:'a',operator:'yes',value:''}],effectZh:'否',effectEn:'No'};
const resultForm=answer=>({get:key=>({'result-0':answer,'mode-0':'all','input-0-0':'a','op-0-0':'yes','effectZh-0':'否','effectEn-0':'No'}[key])});
let derivedCase=plain(context.readTestBranches(resultOwner,resultForm('false'),{branches:[resultCase]}))[0];
assert.equal(derivedCase.resultValue,false,'The No choice is a boolean false, not a truthy string.');
api.validateBranch(derivedCase,resultOwner);
assert.throws(()=>api.validateBranch({...derivedCase,resultValue:'false'},resultOwner),/explicit|answer for this case/);
derivedCase=plain(context.readTestBranches(resultOwner,resultForm(''),{branches:[resultCase]}))[0];
assert.equal(derivedCase.resultValue,undefined,'An unanswered result does not default to false.');
assert.throws(()=>api.validateBranch(derivedCase,resultOwner),/explicit|answer for this case/);
resultOwner.type.kind='choice';resultOwner.options=[{value:'bond-a',labelZh:'甲',labelEn:'A'}];
derivedCase=plain(context.readTestBranches(resultOwner,resultForm('bond-a'),{branches:[resultCase]}))[0];
api.validateBranch(derivedCase,resultOwner);
assert.throws(()=>api.validateBranch({...derivedCase,resultValue:'deleted-option'},resultOwner),/explicit|answer for this case/);
console.log('PASS: derived cases save typed boolean or choice results and reject missing or obsolete answers.');
for(const kind of ['text','multiselect','list']){
  find(state,'a').type.kind=kind;
  api.validateClauses([{inputId:'a',operator:'empty',value:''}],resultOwner);
  api.validateClauses([{inputId:'a',operator:'notempty',value:''}],resultOwner);
}
find(state,'a').type.kind='number';
assert.throws(()=>api.validateClauses([{inputId:'a',operator:'empty',value:''}],resultOwner),/Empty\/content/);
console.log('PASS: explicit empty/content predicates are available without treating an unanswered field or a numeric zero as empty.');

// Joined targets are declarations over one scalar field in its actual repeating list.
state=api.initial();
state.variables.push({...item('rows-a','ntt','list'),status:'pending'},{...item('rows-b','ntt','list'),status:'pending'});
find(state,'a').type.kind='text';find(state,'a').parentId='rows-a';api.use(state);
const joinedForm=values=>({get:key=>({'action-0':'fill','source-0':'a','valueMode-0':'join','prefix-0':'Sections ','suffix-0':'.',...values}[key])});
const joinedTarget=plain(api.readTargetFields(joinedForm({}),{targets:[aAnchor]}))[0];
assert.deepEqual({mode:joinedTarget.valueMode,list:joinedTarget.listId,separator:joinedTarget.separator},{mode:'join',list:'rows-a',separator:', '});
assert.equal(joinedTarget.prefix,'Sections ');assert.equal(joinedTarget.suffix,'.');
find(state,'c').editTargets=[joinedTarget];api.validateTargets(find(state,'c'));
const exactSeparator=plain(api.readTargetFields(joinedForm({'separator-0':'  /  '}),{targets:[joinedTarget]}))[0];
assert.equal(exactSeparator.separator,'  /  ','Separator whitespace is source wording.');
assert.equal(plain(api.readTargetFields(joinedForm({'separator-0':''}),{targets:[joinedTarget]}))[0].separator,'','An explicit empty separator stays empty.');
for(const values of [{'valueMode-0':'single'},{'source-0':'','replacementEn-0':'Fixed wording'},{'action-0':'keep'},{'action-0':'delete'}]){
  const cleared=plain(api.readTargetFields(joinedForm(values),{targets:[joinedTarget]}))[0];
  for(const key of ['valueMode','listId','separator'])assert.equal(cleared[key],undefined,'Single, fixed and non-fill actions clear join metadata.');
}
for(const change of [{listId:'rows-b'},{separator:7},{valueSourceId:'rows-a'},{valueSourceId:'n'}]){
  find(state,'c').editTargets=[{...joinedTarget,...change}];
  assert.throws(()=>api.validateTargets(find(state,'c')),/scalar detail/);
}
find(state,'a').type.kind='multiselect';find(state,'c').editTargets=[joinedTarget];
assert.throws(()=>api.validateTargets(find(state,'c')),/scalar detail/);find(state,'a').type.kind='text';
find(state,'c').editTargets=[joinedTarget];
let movedJoin=step(state,{type:'EDIT',id:'a',values:unchangedEdit(find(state,'a'),'rows-b')});
assert.equal(find(movedJoin,'c').editTargets[0].needsRebind,true);
assert.equal(find(movedJoin,'c').status,'help');api.use(movedJoin);
assert.throws(()=>api.validateTargets(find(movedJoin,'c')),/Review the affected text/);
let changedList=step(state,{type:'EDIT',id:'rows-a',values:{type:{kind:'record',zh:'组',en:'Record'}}});
assert.equal(find(changedList,'c').editTargets[0].needsRebind,true,'Changing list to record preserves the historical join but requires rebinding.');
assert.equal(find(changedList,'c').status,'help');
const renamedList=step(state,{type:'EDIT',id:'rows-a',values:{nameZh:'改名',nameEn:'Renamed'}});
assert.equal(find(renamedList,'c').editTargets[0].needsRebind,undefined,'Renaming a list does not change the join binding.');
for(const type of ['EXCLUDE','SPLIT']){
  const invalidated=step(state,{type,id:'rows-a',reason:'Synthetic',mode:'unknown',items:[{nameZh:'甲',nameEn:'A',sourceAnchors:[aAnchor]},{nameZh:'乙',nameEn:'B',sourceAnchors:[bAnchor]}]});
  assert.equal(find(invalidated,'c').editTargets[0].needsRebind,true,'Removing or splitting the list invalidates dependent joins.');
}
find(state,'rows-a').docIds=['scc-word'];find(state,'c').docIds=['scc-pdf'];find(state,'c').correction=null;find(state,'c').relatedInputIds=[];find(state,'c').editTargets=[];
api.use(state);assert.throws(()=>api.validateTargets({...find(state,'c'),editTargets:[joinedTarget]}),/scalar detail/);
assert.equal(step(state,{type:'TARGETS',id:'c',targets:[{...joinedTarget,docId:'scc-pdf'}]}),state,'A new join cannot bypass SCC isolation through the input’s parent list.');
console.log('PASS: list-answer joins retain exact separators, clear obsolete modes, validate actual scalar/list bindings, invalidate structural changes and preserve SCC isolation.');

const displayContext=vm.createContext({variables:state.variables,joinTarget:{...joinedTarget,separator:'<script> & '}});
const displayHelpers=['  const escape =','  const bi =','  const ruleBranches =','  const quote='].map(prefix=>script.split('\n').find(line=>line.startsWith(prefix))).join('\n');
const targetMeaningBody=script.slice(script.indexOf('  function targetMeaning('),script.indexOf('  function startDraft('));
vm.runInContext(`${displayHelpers}\nconst getName=id=>variables.find(v=>v.id===id)||{};${targetMeaningBody}\nglobalThis.targetHtml=targetMeaning(joinTarget,variables.find(v=>v.id==='c'));`,displayContext);
assert.match(displayContext.targetHtml,/Join the/);assert.match(displayContext.targetHtml,/answers from all items/);
assert.match(displayContext.targetHtml,/separator: &quot;&lt;script&gt; &amp; &quot;/);
assert.match(displayContext.targetHtml,/Sections \[合并后的答案 \/ Joined answers\]\./);
assert.equal(displayContext.targetHtml.includes('<script>'),false,'Readable target notes escape separator wording.');
assert.ok(script.slice(script.indexOf('  function download('),script.indexOf('  function act(')).includes('targetMeaning(a,v)'),
  'Readable HTML export uses the same joined-target explanation.');
console.log('PASS: cards and readable HTML describe joined answers, list names, exact separators and surrounding wording safely.');

state=api.initial();
state.variables.push(
  {...item('word-group','ntt','record'),docIds:['ntt','scc-word'],status:'pending'},
  {...item('pdf-group','ntt','record'),docIds:['ntt','scc-pdf'],status:'pending'},
  {...item('word-list','ntt','list'),parentId:'word-group',status:'pending'}
);
find(state,'a').parentId='word-group';find(state,'c').parentId='pdf-group';api.use(state);
const ancestorCase={id:'case-1',mode:'all',clauses:[{inputId:'a',operator:'yes',value:''}],effectZh:'保留',effectEn:'Keep'};
assert.throws(()=>api.validateBranch(ancestorCase,find(state,'c')),/active question/,
  'A condition cannot hide the other SCC edition in its input or owner ancestry.');
assert.equal(step(state,{type:'RULE',id:'c',correction:{kind:'rule',branches:[ancestorCase]}}),state,
  'The reducer rejects incompatible ancestry even when called without form validation.');
const ancestorScope={...ancestorCase,scopeId:'word-list',scopeMode:'any',clauses:[{inputId:'c',operator:'yes',value:''}]};
assert.throws(()=>api.validateBranch(ancestorScope,find(state,'c')),/active list/);
assert.equal(step(state,{type:'RULE',id:'c',correction:{kind:'rule',branches:[ancestorScope]}}),state);

const branchEditorBody=script.slice(script.indexOf('  function branchEditor('),script.indexOf('  function readBranchFields('));
const ancestorRender=vm.createContext({state:plain(state),ownerId:'c',caseValue:ancestorCase});
vm.runInContext(`${displayHelpers}
  const compatible=(a,b)=>!([...a.docIds,...b.docIds].includes('scc-word')&&[...a.docIds,...b.docIds].includes('scc-pdf'));
  const getName=id=>state.variables.find(v=>v.id===id)||{};
  const choose=(name,zh,en,options)=>name+':'+options,opts=()=>'',field=()=>'',button=()=>'',note=()=>'',operators={};
  const draft={branches:[caseValue]};${validateBody}${branchEditorBody}
  globalThis.editorHtml=branchEditor(caseValue,0,getName(ownerId),{});`,ancestorRender);
assert.equal(ancestorRender.editorHtml.includes('a / a'),false,'An incompatible condition input is not offered.');
assert.equal(ancestorRender.editorHtml.includes('word-list / word-list'),false,'An incompatible list scope is not offered.');
assert.ok(ancestorRender.editorHtml.includes('Question changed; choose again'),'The old input remains visible as unresolved wording.');

find(state,'c').parentId='word-group';api.use(state);
api.validateBranch(ancestorCase,find(state,'c'));
const compatibleRule=step(state,{type:'RULE',id:'c',correction:{kind:'rule',branches:[ancestorCase]}});
assert.equal(find(compatibleRule,'c').status,'changed');
assert.deepEqual(plain(find(compatibleRule,'c').correction.branches),[ancestorCase]);
console.log('PASS: condition candidates, form validation and reducer agree on SCC ancestor isolation while same-edition rules remain usable.');

const helpContext=vm.createContext({structuredClone,reviewState:plain(api.initial()),reduce:api.reduce});
const helpHelpers=['  const escape =','  const bi =','  const button =','  const field='].map(prefix=>script.split('\n').find(line=>line.startsWith(prefix))).join('\n');
const helpFunctions=script.slice(script.indexOf('  function startDraft('),script.indexOf('  function form('))+
  script.slice(script.indexOf('  function renderDraft('),script.indexOf('  function targetEditor('))+
  script.slice(script.indexOf('  function required('),script.indexOf('  function validateClauses('))+
  script.slice(script.indexOf('  function act('),script.indexOf("  document.addEventListener('click'"));
vm.runInContext(`${helpHelpers}
  let draft=null,capture=null,selection=null,overviewRange=null,formHtml='',docId='ntt',selectedId='a';
  const current=()=>reviewState.variables.find(v=>v.id===selectedId),clearSelection=()=>{},renderPage=()=>{},showQuestion=()=>{},showCatalog=()=>{},openPanel=()=>{},integrationChanged=()=>{},form=html=>{formHtml=html;};
  const formValues={},error={textContent:''},$=id=>id==='form-error'?error:{values:formValues};
  class FormData{constructor(form){this.values=form.values;}get(key){return this.values[key];}}
  const prompt=()=>{throw Error('prompt() is not supported');};
  const apply=action=>{reviewState=reduce(reviewState,{...action,at:'self-check'});draft=null;};
  ${helpFunctions}
  globalThis.helpTest={act,saveDraft,values:formValues,error,html:()=>formHtml,state:()=>reviewState,draft:()=>draft};`,helpContext);
const helpTest=helpContext.helpTest;
assert.doesNotThrow(()=>helpTest.act('help'),'Need more information must use the drawer when browser prompt is unsupported.');
assert.equal(helpTest.draft().kind,'help');
assert.match(helpTest.html(),/<textarea name="reason">/);assert.match(helpTest.html(),/data-action="save"/);assert.match(helpTest.html(),/data-action="cancel"/);
helpTest.values.reason='   ';helpTest.saveDraft();
assert.equal(find(helpTest.state(),'a').status,'pending');assert.ok(helpTest.error.textContent);assert.equal(helpTest.draft().kind,'help');
helpTest.values.reason='Need the project scope';helpTest.act('cancel');
assert.equal(find(helpTest.state(),'a').status,'pending','Cancel must not record the unfinished request.');
helpTest.act('help');helpTest.values.reason='  Need the project scope  ';helpTest.saveDraft();
assert.equal(find(helpTest.state(),'a').status,'help');assert.equal(find(helpTest.state(),'a').reason,'Need the project scope');
assert.equal(helpTest.state().events[0].en,'Recorded a request for more context');
helpTest.act('help');assert.match(helpTest.html(),/Need the project scope/);helpTest.act('cancel');
assert.equal(find(helpTest.state(),'a').reason,'Need the project scope','Cancel preserves the previously saved request.');
console.log('PASS: help opens the existing drawer without prompt, rejects blank text, cancels safely and records the request through the HELP reducer.');
