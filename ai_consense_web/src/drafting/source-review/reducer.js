import { isSourceCheck, pendingEditorial, valueDependencies } from './value-dependencies.js';

// Empty boolean options use the input control's standard Yes/No default;
// explicit options still need matching polarity in both languages.
function standardBooleanOptions(options) {
  if (options == null || Array.isArray(options) && options.length === 0) return true;
  if (!Array.isArray(options) || options.length !== 2) return false;
  const seen = new Set();
  for (const option of options) {
    if (!option || Object.keys(option).length !== 3 || Object.keys(option).some(key => !['value', 'labelZh', 'labelEn'].includes(key)) || typeof option.labelZh !== 'string' || typeof option.labelEn !== 'string') return false;
    const positive = option.value === true || option.value === 'true' || option.value === 'yes';
    const negative = option.value === false || option.value === 'false' || option.value === 'no';
    if ((!positive && !negative) || option.labelZh?.trim() !== (positive ? '是' : '否') || option.labelEn?.trim().toLowerCase() !== (positive ? 'yes' : 'no')) return false;
    seen.add(positive);
  }
  return seen.size === 2;
}

function sameAnswerOptions(a, b) {
  return sameCoreValue(a.options || [], b.options || []) ||
    a.type.kind === 'boolean' && b.type.kind === 'boolean' && standardBooleanOptions(a.options) && standardBooleanOptions(b.options);
}

function sameCoreValue(a, b) {
  if(a===b)return true;
  if(a==null||b==null||typeof a!=='object'||typeof b!=='object')return false;
  if(Array.isArray(a)||Array.isArray(b))return Array.isArray(a)&&Array.isArray(b)&&a.length===b.length&&a.every((value,index)=>sameCoreValue(value,b[index]));
  if(Object.prototype.toString.call(a)!=='[object Object]'||Object.prototype.toString.call(b)!=='[object Object]')return false;
  const keys=Object.keys(a);return keys.length===Object.keys(b).length&&keys.every(key=>Object.prototype.hasOwnProperty.call(b,key)&&sameCoreValue(a[key],b[key]));
}
const catalogCoreKeys = ['parentId','nameZh','nameEn','questionZh','questionEn','type','role','optional','options','unit','fixedValue','definitionScope'];
const catalogCore = node => [catalogCoreKeys.map(key=>node[key]), ['status','baselineKey','baselineVersion','scope'].map(key=>node.catalogAlignment?.[key]), node.correction??null, node.applicability??null];

export function createReviewReducer(data) {
  const copy = value => structuredClone(value);
  const edition = id => {
    const source = (data.sources || []).find(item => [String(item.id), item.docId, item.editionId].includes(String(id)));
    return source?.fileKey === 'SCC' ? String(source.id) : id === 'scc-word' || id === 'scc-pdf' ? id : null;
  };
  const compatible = (a,b) => new Set([...a.docIds,...b.docIds].map(edition).filter(Boolean)).size < 2;
  const ruleBranches = c => c?.kind==='rule' ? (c.branches || [{...c,id:'case-1'}]) : [];
  const correctionClauses = c => ruleBranches(c).flatMap(b=>b.clauses||[]);
  const correctionInputs = c => [...(c?.inputIds||[]),...correctionClauses(c).map(x=>x.inputId),...ruleBranches(c).map(b=>b.scopeId).filter(Boolean)];
  const targetInputs = t => [t.valueSourceId,t.valueMode==='join'?t.listId:null].filter(Boolean);
  function isolatedSources(state){
    const byId=new Map(state.variables.map(v=>[v.id,v]));
    const context=v=>{const docs=new Set(v.docIds),seen=new Set();while(v?.parentId&&!seen.has(v.id)){seen.add(v.id);v=byId.get(v.parentId);for(const doc of v?.docIds||[])docs.add(doc);}return {docIds:[...docs]};};
    for(const v of state.variables){
      const own=context(v);if(!compatible(own,own))return false;if(v.status==='excluded')continue;
      const refs=[...(v.relatedInputIds||[]),...correctionInputs(v.correction?.needsRebind?null:v.correction),...(v.editTargets||[]).filter(t=>!t.needsRebind).flatMap(targetInputs)];
      for(const id of refs){const input=byId.get(id);if(input&&input.status!=='excluded'&&!compatible(own,context(input)))return false;}
    }
    return true;
  }
  const canShareSources=(state,v,docs)=>isolatedSources({...state,variables:state.variables.map(x=>x.id===v.id?{...x,docIds:[...new Set([...x.docIds,...docs])]}:x)});
  function affected(state, id) {
    const v = state.variables.find(x => x.id === id);
    const refs = x => [...(x.relatedInputIds||[]),...correctionInputs(x.correction),...(x.applicability?.clauses||[]).map(clause=>clause.inputId),...(x.editTargets||[]).flatMap(targetInputs)];
    return state.variables.filter(x => x.id !== id && compatible(x,v) && x.status !== 'excluded' &&
      (refs(x).includes(id) || refs(v).includes(x.id)));
  }
  function mergeInto(s, id, targetId) {
    const v=s.variables.find(x=>x.id===id),other=s.variables.find(x=>x.id===targetId);
    const retainedAlignment=!['record','list'].includes(other.type.kind)&&other.catalogAlignment?.status==='matched'&&other.catalogAlignment?.confirmed===true&&other.catalogAlignment.baselineKey&&other.catalogAlignment.baselineVersion?copy(other.catalogAlignment):null,retainedCore=retainedAlignment?copy(catalogCore(other)):null;
    v.status='excluded';v.replacedBy=[other.id];v.reason='人工合并 / Merged during review';
    other.docIds=[...new Set([...other.docIds,...v.docIds])];
    other.sourceUnitIds=[...new Set([...(other.sourceUnitIds||[]),...(v.sourceUnitIds||[])])];
    other.evidence=[...other.evidence,...v.evidence].filter((x,i,a)=>a.findIndex(y=>y.docId===x.docId&&y.quote===x.quote)===i);
    other.targets=[...other.targets,...v.targets].filter((x,i,a)=>a.findIndex(y=>y.docId===x.docId&&y.labelZh===x.labelZh)===i);
    other.sourceAnchors=[...(other.sourceAnchors||[]),...(v.sourceAnchors||[])];
    other.editTargets=[...(other.editTargets||[]),...copy(v.editTargets||[])];
    other.relatedInputIds=[...new Set([...(other.relatedInputIds||[]),...(v.relatedInputIds||[])])];
    other.relations=[...other.relations,...v.relations].filter((x,i,a)=>a.findIndex(y=>JSON.stringify(x)===JSON.stringify(y))===i);
    if(!other.correction&&v.correction)other.correction=copy(v.correction);
    if(other.catalogAlignment||v.catalogAlignment)other.catalogAlignment={...copy(other.catalogAlignment||v.catalogAlignment),confirmed:false};
    delete other.definitionReview;
    if(v.role.kind==='fixed'&&other.role.kind!=='fixed'){other.role=copy(v.role);other.fixedValue=v.fixedValue;}
    other.status='changed';other.manual=true;
    for (const x of s.variables) {
      if(x.status==='excluded')continue;
      if(x.parentId===v.id){x.parentId=other.id;if(x.status==='confirmed')x.status='pending';}
      x.relatedInputIds=[...new Set((x.relatedInputIds||[]).map(id=>id===v.id?other.id:id))].filter(id=>id!==x.id);
      if(x.id!==other.id&&(x.applicability?.clauses||[]).some(clause=>clause.inputId===v.id)){
        x.applicability.clauses=x.applicability.clauses.map(clause=>clause.inputId===v.id?{...clause,inputId:other.id}:clause);
        x.applicability.confirmed=false;delete x.applicability.bindingFingerprint;delete x.definitionReview;if(x.status==='confirmed')x.status='pending';
      }
      if(x.correction?.inputIds) x.correction.inputIds=[...new Set(x.correction.inputIds.map(id=>id===v.id?other.id:id))];
      if(x.correction?.branches)for(const branch of x.correction.branches){branch.clauses=branch.clauses.map(c=>({...c,inputId:c.inputId===v.id?other.id:c.inputId}));if(branch.scopeId===v.id)branch.scopeId=other.id;}
      else if(x.correction?.clauses)x.correction.clauses=x.correction.clauses.map(c=>({...c,inputId:c.inputId===v.id?other.id:c.inputId}));
      for(const target of x.editTargets||[]){if(target.valueSourceId===v.id)target.valueSourceId=other.id;if(target.listId===v.id)target.listId=other.id;if(target.itemKey===v.id){target.itemKey=other.id;target.needsRebind=true;x.status='help';}}
    }
    for(const requirement of s.requirements||[])requirement.nodeIds=[...new Set((requirement.nodeIds||[]).map(id=>id===v.id?other.id:id))];
    // Scalar identity can survive added evidence; record/list membership and the expanded cohort still need review.
    if(retainedAlignment&&sameCoreValue(retainedCore,catalogCore(other)))other.catalogAlignment=retainedAlignment;
  }
  function mergeProblem(state, id, targetId) {
    const v=state.variables.find(x=>x.id===id),other=state.variables.find(x=>x.id===targetId);
    const problem=code=>({code,id,targetId});
    if(!v||!other)return problem('MERGE_NODE_MISSING');
    if(id===targetId)return problem('MERGE_SAME_NODE');
    if(v.status==='excluded'||other.status==='excluded')return problem('MERGE_EXCLUDED');
    if([v,other].some(node=>pendingEditorial(node)&&node.editorialReview?.confirmed!==true))return problem('MERGE_EDITORIAL_REVIEW_REQUIRED');
    if(v.type.kind!==other.type.kind)return problem('MERGE_TYPE_MISMATCH');
    if((v.type.unit||'')!==(other.type.unit||''))return problem('MERGE_UNIT_MISMATCH');
    if(v.catalogAlignment?.confirmed&&other.catalogAlignment?.confirmed&&['status','baselineKey','baselineVersion','scope'].some(key=>(v.catalogAlignment[key]||'')!==(other.catalogAlignment[key]||'')))return problem('MERGE_ALIGNMENT_REVIEW_REQUIRED');
    if(v.role.kind!==other.role.kind)return problem('MERGE_ROLE_MISMATCH');
    if(isSourceCheck(v)!==isSourceCheck(other))return problem('MERGE_ROLE_MISMATCH');
    if(v.parentId!==other.parentId)return problem('MERGE_PARENT_MISMATCH');
    if(Boolean(v.optional)!==Boolean(other.optional))return problem('MERGE_OPTIONAL_MISMATCH');
    if(!sameAnswerOptions(v,other))return problem('MERGE_OPTIONS_MISMATCH');
    if(v.role.kind==='fixed'&&v.fixedValue!==other.fixedValue)return problem('MERGE_FIXED_VALUE_MISMATCH');
    if(!sameCoreValue(v.applicability??null,other.applicability??null))return problem('MERGE_APPLICABILITY_CONFLICT');
    const emptyUnresolved=c=>c?.kind==='rule'&&c.needsRebind===true&&Array.isArray(c.branches)&&!c.branches.length&&Array.isArray(c.inputIds)&&!c.inputIds.length&&Object.keys(c).every(key=>['kind','needsRebind','branches','inputIds'].includes(key));
    if(v.correction&&other.correction&&!(emptyUnresolved(v.correction)&&emptyUnresolved(other.correction)))return problem('MERGE_RULE_CONFLICT');
    if(!compatible(other,v))return problem('MERGE_SOURCE_EDITION_CONFLICT');
    if(!canShareSources(state,v,other.docIds)||!canShareSources(state,other,v.docIds))return problem('MERGE_SOURCE_SCOPE_CONFLICT');
    const projected=copy(state);mergeInto(projected,id,targetId);
    if(valueDependencies(projected).some(edge=>edge.cyclic))return problem('MERGE_DEPENDENCY_CYCLE');
    return isolatedSources(projected)?null:problem('MERGE_SOURCE_SCOPE_CONFLICT');
  }
  const spans = anchor => anchor.native ? anchor.native.spans || [anchor.native] : anchor.nativeBinding?.status==='bound' ? anchor.nativeBinding.spans || [] : [];
  const sourceHash = anchor => anchor.sourceSha256 || anchor.nativeBinding?.sourceSha256;
  const contains = (anchor,target) => {
    if(anchor.docId!==target.docId||!sourceHash(anchor)||sourceHash(anchor)!==sourceHash(target))return false;
    const selected=spans(anchor),original=spans(target);
    if(original.length)return original.every(part=>selected.some(span=>span.paragraphId===part.paragraphId&&span.paragraphSha256===part.paragraphSha256&&span.start<=part.start&&span.end>=part.end));
    if(target.observation&&anchor.observation)return target.observation.blockId===anchor.observation.blockId&&anchor.observation.start<=target.observation.start&&anchor.observation.end>=target.observation.end;
    return Boolean(target.text)&&anchor.text===target.text;
  };
  function splitProblem(state,id,items) {
    const v=state.variables.find(node=>node.id===id);
    if(!v||v.status==='excluded'||!Array.isArray(items)||items.length!==2||items.some(item=>!item.sourceAnchors?.length))return 'SPLIT_SELECTION_REQUIRED';
    if((v.editTargets||[]).some(target=>items.filter(item=>item.sourceAnchors.some(anchor=>contains(anchor,target))).length!==1))return 'SPLIT_TARGET_OWNERSHIP_REQUIRED';
    return null;
  }
  function mergeMany(state, ids, targetId, at, reason) {
    const failed=(code,id=targetId)=>({state,problem:{code,id,targetId}});
    if(!Array.isArray(ids)||ids.length<2)return failed('MERGE_SELECTION_REQUIRED');
    const duplicate=ids.find((id,index)=>ids.indexOf(id)!==index);
    if(duplicate!==undefined)return failed('MERGE_DUPLICATE_ID',duplicate);
    if(!ids.includes(targetId))return failed('MERGE_TARGET_REQUIRED');
    for(const id of ids)if(typeof id!=='string'||!id||!state.variables.some(v=>v.id===id))return failed('MERGE_NODE_MISSING',id);
    let next=state;
    for(const id of ids)if(id!==targetId){
      const merged=reduce(next,{type:'MERGE',id,targetId,at,reason});
      if(merged===next)return {state,problem:mergeProblem(next,id,targetId)||{code:'MERGE_SOURCE_SCOPE_CONFLICT',id,targetId}};
      next=merged;
    }
    return {state:next,problem:null};
  }
  const classificationBasis = node => JSON.stringify(Object.fromEntries(['nameZh','nameEn','questionZh','questionEn','type','options','parentId','optional','definitionScope','fixedValue','docIds','sourceUnitIds','sourceAnchors','semanticSourceQuote','guidance'].map(key=>[key,node[key]])));
  // The preview records explicit human decisions; it does not reinterpret source wording.
  function reduce(old, action) {
    if(action.type==='MERGE_MANY')return mergeMany(old,action.ids,action.targetId,action.at,action.reason).state;
    const s = copy(old), find = id => s.variables.find(v => v.id === id);
    const v = find(action.id);
    const event = (zh,en,details=[]) => s.events.unshift({zh,en,details,time:action.at});
    const revisit = id => { for (const x of [find(id),...affected(s,id)]) {if(x.id!==id&&x.status==='confirmed')x.status='pending';for(const unitId of x.sourceUnitIds||[])delete s.sourceReviews[unitId];} };
    if (action.type === 'REVIEW_RELATIONS') {
      const statuses={implemented:'confirmed',rejected:'rejected',not_applicable:'excluded'},indices=action.indices;
      if(!Object.prototype.hasOwnProperty.call(statuses,action.decision)||typeof action.reason!=='string'||!action.reason.trim()||!Array.isArray(indices)||!indices.length||new Set(indices).size!==indices.length||indices.some(index=>!Number.isInteger(index)||index<0||!s.relationProposals?.[index]))return old;
      for(const index of indices){const relation=s.relationProposals[index];relation.status=statuses[action.decision];relation.executable=false;relation.review={decision:action.decision,reason:action.reason.trim(),basis:null};}
      event('已记录关系建议的人工处理，保存后生效','Recorded human relationship review; save to retain it',[action.decision,String(indices.length),action.reason.trim()]);
    } else if (action.type === 'CLEAR') {
      s.variables=[];s.sourceReviews={};s.events=[];s.nextId=1;s.blank=true;s.guidanceMode=action.guidanceMode||'empty';
      event('已从空白开始核对','Started an empty review');
    } else if (action.type === 'EDIT') {
      const changedValues=Object.fromEntries(Object.entries(action.values).filter(([key,value])=>!sameCoreValue(value,v[key])));
      const classificationOriginal=action.values.role?.kind==='verification'&&v.role?.kind!=='verification'?copy(v):null;
      const definitionChanged=catalogCoreKeys.some(key=>action.values[key]!==undefined&&!sameCoreValue(action.values[key],v[key]));
      revisit(v.id);
      if(definitionChanged||['editorialReview','catalogAlignment','definitionOrigin'].some(key=>action.values[key]!==undefined&&!sameCoreValue(action.values[key],v[key])))delete v.definitionReview;
      const before = [v.nameZh,v.nameEn,v.type.zh,v.role.zh].join(' · ');
      const ownAnswerChanged=(action.values.type&&(action.values.type.kind!==v.type.kind||(action.values.type.unit||'')!==(v.type.unit||'')))||(action.values.options&&!sameCoreValue(action.values.options,v.options||[]));
      if(!action.values.catalogAlignment&&v.catalogAlignment?.confirmed&&definitionChanged)v.catalogAlignment={...v.catalogAlignment,confirmed:false};
      if(action.values.parentId!==undefined&&action.values.parentId!==v.parentId){
        const moved=new Set([v.id]);let changed=true;
        while(changed){changed=false;for(const x of s.variables)if(moved.has(x.parentId)&&!moved.has(x.id)){moved.add(x.id);changed=true;}}
        for(const x of s.variables)if(x.status!=='excluded'){
          if(x.correction&&correctionInputs(x.correction).some(id=>moved.has(id))){x.correction.needsRebind=true;x.status='help';}
          for(const target of x.editTargets||[])if(targetInputs(target).some(id=>moved.has(id))){target.needsRebind=true;x.status='help';}
        }
      }
      const noLongerValue=action.values.role?.kind==='verification'&&v.role?.kind!=='verification';
      if(noLongerValue && s.variables.some(x=>x.status!=='excluded' && x.parentId===v.id))return old;
      const activeHistory=(v.editorialClassificationHistory||[]).findLast(item=>!item.restored);
      if(v.role?.kind==='verification'&&activeHistory){
        if(Object.prototype.hasOwnProperty.call(action.values,'definitionOrigin'))activeHistory.originTouched=true;
        if(Object.prototype.hasOwnProperty.call(action.values,'catalogAlignment'))activeHistory.alignmentTouched=true;
      }
      let classification;
      if(noLongerValue){
        classification={at:action.at,original:{role:copy(classificationOriginal.role),definitionOrigin:copy(classificationOriginal.definitionOrigin??null),catalogAlignment:copy(classificationOriginal.catalogAlignment??null),factProjectionReview:copy(classificationOriginal.factProjectionReview??null),correction:copy(classificationOriginal.correction??null)},basis:classificationBasis(classificationOriginal),affectedOwners:[],restored:false};
        v.editorialClassificationHistory=[...(v.editorialClassificationHistory||[]),classification];
        for(const x of s.variables)if(x.status!=='excluded'){
          const prior=old.variables.find(item=>item.id===x.id);
          const correction=prior?.correction&&correctionInputs(prior.correction).includes(v.id)?copy(prior.correction):null;
          const targets=(prior?.editTargets||[]).flatMap((target,index)=>[...targetInputs(target),target.itemKey].includes(v.id)?[{index,id:target.id??null,original:copy(target)}]:[]);
          const relatedInputIds=(prior?.relatedInputIds||[]).includes(v.id)?copy(prior.relatedInputIds):[];
          if(correction||targets.length||relatedInputIds.length){
            x.editorialReferenceHistory=[...(x.editorialReferenceHistory||[]),{variableId:v.id,at:action.at,correction,targets,relatedInputIds}];classification.affectedOwners.push(x.id);
          }
        }

        if(v.factProjectionReview){v.supersededFactProjectionReview=copy(v.factProjectionReview);delete v.factProjectionReview;if(v.correction)v.correction.needsRebind=true;for(const target of v.editTargets||[])target.needsRebind=true;}
        if(v.definitionOrigin){v.supersededDefinitionOrigin=copy(v.definitionOrigin);delete v.definitionOrigin;}
        if(v.catalogAlignment?.confirmed)v.catalogAlignment={...v.catalogAlignment,confirmed:false};
        const parent=find(v.parentId);if(parent?.catalogAlignment?.confirmed)parent.catalogAlignment={...parent.catalogAlignment,confirmed:false};
      }
      if(ownAnswerChanged||action.values.role?.kind==='fixed'||noLongerValue)for(const x of s.variables){if(x.status!=='excluded'&&x.correction&&correctionInputs(x.correction).includes(v.id)){x.correction.needsRebind=true;x.status='help';}}
      Object.assign(v,changedValues,{status:'changed',manual:true});
      if(classification){classification.classifiedRole=copy(v.role);classification.classifiedAlignment=copy(v.catalogAlignment??null);}
      if(noLongerValue)for(const x of s.variables)if(x.status!=='excluded'){
        if(x.correction&&correctionInputs(x.correction).includes(v.id)){x.correction={kind:'rule',needsRebind:true,branches:[],inputIds:[],supersededCorrection:copy(x.correction)};x.status='help';}
        for(const target of x.editTargets||[])if([...targetInputs(target),target.itemKey].includes(v.id)){
          target.supersededValueReference={valueSourceId:target.valueSourceId,listId:target.listId,itemKey:target.itemKey,itemValue:target.itemValue};
          if(target.valueSourceId===v.id)delete target.valueSourceId;
          if(target.listId===v.id)delete target.listId;
          if(target.itemKey===v.id){delete target.itemKey;delete target.itemValue;}
          target.needsRebind=true;x.status='help';
        }
      }
      if(ownAnswerChanged&&v.correction){v.correction.needsRebind=true;v.status='help';}
      if(ownAnswerChanged||noLongerValue)for(const x of s.variables)if(x.status!=='excluded')for(const target of x.editTargets||[])if(targetInputs(target).includes(v.id)){target.needsRebind=true;x.status='help';}
      event('调整了问题的内容或用途','Changed the question or its purpose',[before,'→ '+[v.nameZh,v.nameEn,v.type.zh,v.role.zh].join(' · ')]);
    } else if (action.type === 'RESTORE_BUSINESS_CLASSIFICATION') {
      const history=(v?.editorialClassificationHistory||[]).findLast(item=>!item.restored);
      if(!v||v.status==='excluded'||v.role?.kind!=='verification'||v.definitionKind==='source_seed'||!history?.original?.role||history.original.role.kind==='verification'||history.original.role.kind!=='input'&&v.definitionOrigin!=null||JSON.stringify(v.role)!==JSON.stringify(history.classifiedRole))return old;
      const unchanged=classificationBasis(v)===history.basis;
      v.role=copy(history.original.role);
      if(unchanged&&!history.originTouched&&v.role.kind==='input'&&v.definitionOrigin==null&&history.original.definitionOrigin)v.definitionOrigin=copy(history.original.definitionOrigin);
      if(unchanged&&!history.alignmentTouched&&JSON.stringify(v.catalogAlignment??null)===JSON.stringify(history.classifiedAlignment??null)&&history.original.catalogAlignment)v.catalogAlignment={...copy(history.original.catalogAlignment),confirmed:false};
      history.restored=true;history.restoredAt=action.at;history.referencesRequireReview=true;
      v.editorialReview={kind:'business_value',confirmed:true};delete v.definitionReview;v.status='pending';v.manual=true;revisit(v.id);
      event('恢复业务变量，历史引用待核对','Restored business variable; prior references require review',[v.nameZh]);
    } else if (action.type === 'SPLIT') {
      if(splitProblem(old,v.id,action.items))return old;
      if(!compatible({docIds:action.items.flatMap(item=>(item.sourceAnchors||[]).map(a=>a.docId))},v))return old;
      revisit(v.id);
      const children = action.items.map(item => ({...copy(v),id:`review-${s.nextId++}`,parentId:v.parentId||null,definitionKind:'added',...item,
        docIds:[...new Set((item.sourceAnchors||[]).map(a=>a.docId))],sourceUnitIds:[],missingSource:false,
        evidence:(item.sourceAnchors||[]).map(a=>({docId:a.docId,locationZh:`原文段落`,locationEn:`Source passage`,quote:a.text})),
        questionZh:item.nameZh,questionEn:item.nameEn,type:item.type||{kind:'boolean',zh:'是／否',en:'Yes / No'},role:{kind:'input',zh:'项目资料',en:'Project information'},
        options:[],status:'changed',manual:true,originId:v.id,relatedInputIds:copy(v.relatedInputIds||[]),relations:copy(v.relations||[]),correction:v.correction?{...copy(v.correction),needsRebind:true,supersededCorrection:copy(v.correction)}:null,catalogAlignment:v.catalogAlignment?{...v.catalogAlignment,confirmed:false,status:'ambiguous'}:undefined,
        editTargets:(v.editTargets||[]).filter(target=>(item.sourceAnchors||[]).some(a=>contains(a,target))).map(target=>({...target,needsRebind:true})),
        targets:(v.targets||[]).filter(target=>(item.sourceAnchors||[]).some(a=>a.docId===target.docId))}));
      for(const child of children){
        if(child.correction?.inputIds)child.correction.inputIds=child.correction.inputIds.map(id=>id===v.id?child.id:id);
        for(const branch of ruleBranches(child.correction))for(const clause of branch.clauses||[])if(clause.inputId===v.id)clause.inputId=child.id;
        for(const target of child.editTargets)if(target.valueSourceId===v.id)target.valueSourceId=child.id;
      }
      v.status='excluded'; v.replacedBy=children.map(x=>x.id); v.reason='人工拆分 / Split during review';
      s.variables.push(...children);
      const splitReview={mode:action.mode,inputIds:children.map(x=>x.id),originalZh:v.nameZh,originalEn:v.nameEn};
      children[0].splitReview=splitReview;
      if(!isolatedSources(s))return old;
      for (const x of s.variables) if (x.id !== v.id && x.status!=='excluded' && !children.some(c=>c.id===x.id)) {
        for(const target of x.editTargets||[])if(targetInputs(target).includes(v.id)){target.needsRebind=true;x.status='help';}
        x.relatedInputIds=[...new Set((x.relatedInputIds||[]).flatMap(id=>id===v.id?children.map(c=>c.id):[id]))];
        if(x.correction&&correctionInputs(x.correction).includes(v.id)) {
          x.correction.needsRebind=true;x.status='help';x.reason='引用的问题已拆分，请重新确认条件。 / A referenced question was split. Review the condition.';
        }
      }
      for(const requirement of s.requirements||[])requirement.nodeIds=[...new Set((requirement.nodeIds||[]).flatMap(id=>id===v.id?children.map(child=>child.id):[id]))];
      event('将一个问题拆成了两个独立问题','Split one question into two',[v.nameZh,...children.map(x=>x.nameZh),action.mode==='all'?'两个问题必须同时成立 / Both must be true':action.mode==='any'?'任一问题成立即可 / Either may be true':'条件仍待确认 / Conditions still need review']);
    } else if (action.type === 'MERGE') {
      const other=find(action.targetId);
      if(mergeProblem(old,action.id,action.targetId))return old;
      revisit(v.id); revisit(other.id);
      const oldName=v.nameZh;
      mergeInto(s,v.id,other.id);
      if(!isolatedSources(s))return old;
      event('确认两处是在问同一件事','Merged two references to the same question',[oldName,'→ '+other.nameZh,action.reason]);
    } else if (action.type === 'EXCLUDE') {
      revisit(v.id);v.status='excluded';v.reason=action.reason;v.manual=true;
      for(const x of s.variables)if(x.status!=='excluded'&&x.correction&&correctionInputs(x.correction).includes(v.id)){x.correction.needsRebind=true;x.status='help';}
      for(const x of s.variables)if(x.status!=='excluded')for(const target of x.editTargets||[])if(targetInputs(target).includes(v.id)){target.needsRebind=true;x.status='help';}
      event('将问题标记为不再提问','Marked the question as no longer needed',[v.nameZh,action.reason]);
    } else if (action.type === 'RESTORE') {
      revisit(v.id);
      v.status='pending';v.reason='';delete v.replacedBy;
      event('恢复了一个问题','Restored a question',[v.nameZh]);
    } else if (action.type === 'ADD') {
      const unit=(data.sourceUnits || []).find(x=>x.id===action.unitId), base=action.base;
      const id=`review-${s.nextId++}`;
      const units=unit?[unit.id]:(base?.sourceUnitIds||[]);
      for(const unitId of units)delete s.sourceReviews[unitId];
      for(const x of s.variables)if(x.status==='confirmed'&&(x.sourceUnitIds||[]).some(id=>units.includes(id)))x.status='pending';
      s.variables.push({id,parentId:null,definitionKind:'added',...action.values,docIds:action.docId?[action.docId]:[],options:action.values.options||[],optional:!!action.values.optional,
        guidance:!action.anchor&&!unit&&action.values.definitionOrigin?.kind==='manual'?{zh:'人工新增变量定义：'+action.values.definitionOrigin.reason,en:'Manually added variable definition: '+action.values.definitionOrigin.reason}:{zh:'人工根据原文补充。',en:'Added during source review.'},evidence:action.anchor?[{docId:action.anchor.docId,locationZh:`原文段落`,locationEn:`Source passage`,quote:action.anchor.text}]:unit?[{docId:unit.docId,locationZh:unit.locationZh,locationEn:unit.locationEn,quote:unit.quote}]:(base?.evidence||[]),
        relations:[],targets:[],relatedInputIds:[],sourceUnitIds:unit?[unit.id]:(base?.sourceUnitIds||[]),missingSource:!action.anchor&&!unit&&!base?.evidence?.length,
        status:'changed',reason:action.reason,manual:true,editTargets:[],sourceAnchors:action.anchor?[action.anchor]:[]});
      event('补充了一个遗漏的问题','Added a missing question',[action.values.nameZh,action.reason]);
    } else if (action.type === 'LINK') {
      if(!compatible(v,{docIds:[action.anchor.docId]})||!canShareSources(old,v,[action.anchor.docId]))return old;
      revisit(v.id);delete v.definitionReview;v.sourceAnchors=[...(v.sourceAnchors||[]),action.anchor];v.status='changed';v.manual=true;v.missingSource=false;
      v.docIds=[...new Set([...v.docIds,action.anchor.docId])];
      v.evidence.push({docId:action.anchor.docId,locationZh:`原文段落`,locationEn:`Source passage`,quote:action.anchor.text});
      event('重新关联了原文','Linked a source passage',[v.nameZh,action.anchor.text]);
    } else if (action.type === 'UNLINK') {
      const removed=v.sourceAnchors?.[action.index];if(!removed)return old;
      revisit(v.id);delete v.definitionReview;v.sourceAnchors.splice(action.index,1);v.manual=true;v.status='changed';
      v.evidence=v.evidence.filter(e=>e.docId!==removed.docId||e.quote!==removed.text||v.sourceAnchors.some(a=>a.docId===e.docId&&a.text===e.quote));
      v.missingSource=!v.sourceAnchors.length&&!v.sourceUnitIds?.length;
      event('移除了关联错误的原文，仍保留在调整记录中','Removed an incorrect source link; retained it in the change history',[v.nameZh,removed.docId,removed.text]);
    } else if (action.type === 'TARGETS') {
      const docs=[...new Set([...v.docIds,...action.targets.map(t=>t.docId)])];
      if(!canShareSources(old,v,docs))return old;if(!sameCoreValue(v.docIds,docs))delete v.definitionReview;v.docIds=docs;
      revisit(v.id);v.editTargets=action.targets;v.status='changed';v.manual=true;
      if(!isolatedSources(s))return old;
      event('调整了需要修改的原文位置','Changed the text to be amended',[v.nameZh,`${action.targets.length} 处 / passages`]);
    } else if (action.type === 'RULE') {
      const legacy=v.correction?.supersededCorrection;
      if(legacy!=null && JSON.stringify(legacy)!==JSON.stringify(action.correction?.supersededCorrection)) {
        if(v.editorialReferenceHistory!=null&&!Array.isArray(v.editorialReferenceHistory))return old;
        const history=v.editorialReferenceHistory||[];
        if(!history.some(item=>JSON.stringify(item?.correction)===JSON.stringify(legacy)))v.editorialReferenceHistory=[...history,{variableId:null,at:action.at,correction:copy(legacy),targets:[],relatedInputIds:[],reason:'legacy_superseded_correction'}];
      }
      if(v.factProjectionReview && JSON.stringify(action.correction)!==JSON.stringify(v.correction)){v.factProjectionReview.previousRuleResolution={status:'pending'};for(const target of v.editTargets||[])target.needsRebind=true;}
      revisit(v.id);v.correction=action.correction;v.status='changed';v.manual=true;
      if(!isolatedSources(s))return old;
      event('调整了条件与联动','Changed conditions and linked effects',[v.nameZh,...ruleBranches(action.correction).map(b=>b.effectZh)]);
    } else if (action.type === 'SET_PDF_REFERENCE_ALIGNMENT') {
      const alignment=action.alignment, source=(data.sources||[]).find(item=>String(item.id)===String(alignment?.sourceId));
      if(!v||v.status==='excluded'||isSourceCheck(v)||v.pdfReferenceAlignment?.confirmed||v.catalogAlignment?.confirmed||!source||source.fileKey!=='SCC'||source.format!=='pdf'||source.sourceSha256!==alignment?.sourceSha256||v.docIds?.length!==1||String(v.docIds[0])!==String(source.id)||alignment.status!=='confirmed'||alignment.confirmed!==true||typeof alignment.inputRef!=='string'||!alignment.inputRef||typeof alignment.sourceQuote!=='string'||!alignment.sourceQuote.trim()||v.semanticSourceQuote&&!String(v.semanticSourceQuote).includes(alignment.sourceQuote)||typeof alignment.reason!=='string'||!alignment.reason.trim()||typeof alignment.requestId!=='string'||!alignment.requestId||alignment.basis!==null||!(v.sourceAnchors||[]).some(anchor=>String(anchor.docId)===String(source.id)&&anchor.sourceSha256===source.sourceSha256&&anchor.sourceObservationBinding?.status==='bound'&&String(anchor.text||'').includes(alignment.sourceQuote)))return old;
      if(v.pdfReferenceAlignment){if(v.pdfReferenceAlignmentHistory!=null&&!Array.isArray(v.pdfReferenceAlignmentHistory))return old;const prior=v.pdfReferenceAlignment;v.pdfReferenceAlignmentHistory=[...(v.pdfReferenceAlignmentHistory||[]),Object.fromEntries(['sourceId','sourceSha256','inputRef','reason','requestId','status','basis'].map(key=>[key,prior[key]]))];}
      v.pdfReferenceAlignment=copy(alignment);v.pdfReferenceAlignment.reason=alignment.reason.trim();delete v.definitionReview;v.status='pending';v.manual=true;revisit(v.id);
      event('确认了 PDF 固定事实对应待保存','Confirmed PDF fact correspondence pending save',[v.nameZh,alignment.inputRef]);
    } else if (action.type === 'CLEAR_PDF_REFERENCE_ALIGNMENT') {
      if(!v||!v.pdfReferenceAlignment?.confirmed)return old;
      v.pdfReferenceAlignment={...v.pdfReferenceAlignment,status:'unreviewed',confirmed:false};delete v.definitionReview;v.status='pending';v.manual=true;revisit(v.id);
      event('撤销了 PDF 固定事实对应','Revoked PDF fact correspondence',[v.nameZh]);
    } else if (action.type === 'SET_FIXED_APPLICABILITY') {
      const rule=action.applicability;
      if(!v||v.status==='excluded'||isSourceCheck(v)||v.applicability||rule?.kind!=='fixed_catalog'||typeof rule.bindingFingerprint!=='string'||!rule.bindingFingerprint||rule.confirmed!==true||rule.mode!=='all'||typeof rule.reason!=='string'||!rule.reason.trim()||!Array.isArray(rule.clauses)||!rule.clauses.length||v.catalogAlignment?.confirmed!==true||v.catalogAlignment?.baselineKey!==rule.targetBaselineKey||v.catalogAlignment?.baselineVersion!==rule.ruleVersion)return old;
      for(const clause of rule.clauses){const input=find(clause.inputId);if(!input||input.id===v.id||input.status==='excluded'||isSourceCheck(input)||input.catalogAlignment?.confirmed!==true||input.catalogAlignment?.baselineKey!==clause.baselineKey||input.catalogAlignment?.baselineVersion!==rule.ruleVersion||!compatible(v,input))return old;}
      v.applicability=copy(rule);v.applicability.reason=rule.reason.trim();delete v.definitionReview;v.status='pending';v.manual=true;revisit(v.id);
      if(valueDependencies(s).some(edge=>edge.cyclic))return old;
      event('确认变量适用条件待保存','Confirmed variable applicability pending save',[v.nameZh,rule.reason.trim()]);
    } else if (action.type === 'CLEAR_FIXED_APPLICABILITY') {
      if(!v||v.applicability?.kind!=='fixed_catalog')return old;
      delete v.applicability;delete v.definitionReview;v.status='pending';v.manual=true;revisit(v.id);
      event('移除了变量适用条件','Removed variable applicability',[v.nameZh]);
    } else if (action.type === 'CONVERT_FACT_PROJECTION') {
      const root=find(action.rootId), proposal=(v?.catalogAlignment?.proposedMatches||[]).find(p=>p.baselineKey===action.baselineKey && p.factProjection?.branchValue===action.branchValue && p.factProjection?.branchPolarity===action.branchPolarity);
      const manual=action.origin==='manual'&&typeof action.reason==='string'&&!!action.reason.trim();
      if(!v||!root||v.id===root.id||v.status==='excluded'||root.status==='excluded'||v.type.kind!=='boolean'||!['input','decision','content'].includes(v.role.kind)||v.factProjectionReview||v.parentId||root.parentId||v.definitionKind==='source_seed'||(v.editTargets||[]).some(t=>typeof t.id!=='string'||!t.id)||new Set((v.editTargets||[]).map(t=>t.id)).size!==(v.editTargets||[]).length||(action.origin==='manual'?!manual:!!action.origin||!proposal||proposal.semanticStatus==='conflict')||action.exhaustiveConfirmed!==true||root.type.kind!=='choice'||root.catalogAlignment?.confirmed!==true||root.catalogAlignment?.baselineKey!==action.baselineKey||!compatible(v,root))return old;
      const options=root.options||[], chosen=options.find(o=>o.value===action.branchValue), other=options.filter(o=>o.value!==action.branchValue);
      if(options.length!==2||!chosen||other.length!==1||typeof action.branchPolarity!=='boolean')return old;
      const branches=[{id:'projection-selected',mode:'all',clauses:[{inputId:root.id,operator:'eq',value:chosen.value}],resultValue:action.branchPolarity,effectZh:'所选方案',effectEn:'Selected option'}, {id:'projection-other',mode:'all',clauses:[{inputId:root.id,operator:'eq',value:other[0].value}],resultValue:!action.branchPolarity,effectZh:'另一方案',effectEn:'Other option'}];
      const correction={kind:'rule',branches,otherwise:'needs_review'};
      v.factProjectionReview={rootId:root.id,baselineKey:action.baselineKey,branchValue:action.branchValue,branchPolarity:action.branchPolarity,exhaustiveConfirmed:true,original:{role:copy(v.role),correction:copy(v.correction??null),catalogAlignment:copy(v.catalogAlignment),definitionOrigin:copy(v.definitionOrigin??null),definitionReview:copy(v.definitionReview??null),targetBindings:(v.editTargets||[]).map(target=>({id:target.id,branchId:target.branchId||null,needsRebind:!!target.needsRebind}))},convertedCorrection:copy(correction)};
      if(v.correction!=null)v.factProjectionReview.previousRuleResolution={status:'pending'};
      if(manual){v.factProjectionReview.origin='manual';v.factProjectionReview.reason=action.reason.trim();}
      v.role={kind:'derived',zh:'由其他变量值决定',en:'Derived value'};v.correction=correction;delete v.catalogAlignment;delete v.definitionOrigin;delete v.definitionReview;
      for(const target of v.editTargets||[]){delete target.branchId;target.needsRebind=true;}
      v.status='pending';v.manual=true;revisit(v.id);
      if(!isolatedSources(s)||valueDependencies(s).some(edge=>edge.cyclic))return old;
      event('将局部变量关联到共同选择','Linked local variable to a shared choice',[v.nameZh,root.nameZh]);
    } else if (action.type === 'REVERT_FACT_PROJECTION') {
      const review=v?.factProjectionReview;if(!review||v.role?.kind!=='derived')return old;
      const originalTargets=review.original?.targetBindings;
      if(originalTargets){const oldIds=originalTargets.map(target=>target.id),currentIds=(v.editTargets||[]).map(target=>target.id);if(new Set(oldIds).size!==oldIds.length||new Set(currentIds).size!==currentIds.length||oldIds.length!==currentIds.length||oldIds.some(id=>!id||!currentIds.includes(id)))return old;}
      v.role=copy(review.original.role);v.correction=copy(review.original.correction);if(review.original.catalogAlignment)v.catalogAlignment={...copy(review.original.catalogAlignment),confirmed:false};if(review.original.definitionOrigin)v.definitionOrigin=copy(review.original.definitionOrigin);
      for(const target of v.editTargets||[]){const oldTarget=originalTargets?.find(item=>item.id===target.id);if(oldTarget?.branchId)target.branchId=oldTarget.branchId;else delete target.branchId;target.needsRebind=true;}
      v.supersededFactProjectionReview=copy(review);delete v.factProjectionReview;delete v.definitionReview;v.status='pending';v.manual=true;revisit(v.id);
      event('撤销共同选择关联','Reverted shared choice projection',[v.nameZh]);
    } else if (action.type === 'RESOLVE_PREVIOUS_PROJECTION_RULE') {
      const review=v?.factProjectionReview, original=review?.original?.correction, decision=action.decision, reason=typeof action.reason==='string'?action.reason.trim():'';
      if(!review||!review.previousRuleResolution||!reason||action.checked!==true||!['replace','rebuilt'].includes(decision))return old;
      const oldRefs=correctionInputs(original).filter(id=>id!==v.id&&id!==review.rootId),currentRefs=correctionInputs(v.correction);
      if(decision==='replace'&&(original?.kind!=='rule'||oldRefs.length||JSON.stringify(v.correction)!==JSON.stringify(review.convertedCorrection)))return old;
      if(decision==='rebuilt'&&(JSON.stringify(v.correction)===JSON.stringify(review.convertedCorrection)||!currentRefs.includes(review.rootId)||oldRefs.some(id=>!currentRefs.includes(id))))return old;
      review.previousRuleResolution={status:'resolved',decision,reason};v.status='pending';v.manual=true;revisit(v.id);
      if(valueDependencies(s).some(edge=>edge.cyclic))return old;
      event('人工核对了共同根的旧条件','Reviewed previous shared-choice conditions',[v.nameZh,reason]);
    } else if (action.type === 'CONFIRM_DEFINITION') {
      if(!v || v.status==='excluded' || isSourceCheck(v))return old;
      v.definitionReview={confirmed:true,basis:null};
      event('确认变量定义待保存','Confirmed variable definition pending save',[v.nameZh]);
    } else if (action.type === 'REVIEW') {
      if(valueDependencies(s).some(edge=>edge.cyclic&&(edge.from===v.id||edge.to===v.id)))return old;
      v.status='confirmed';event('确认了一个问题','Reviewed a question',[v.nameZh]);
    } else if (action.type === 'HELP') {
      v.status='help';v.reason=action.reason;event('记录了需要补充的信息','Recorded a request for more context',[v.nameZh,action.reason]);
    } else if (action.type === 'CATALOG_EXCLUDE') {
      s.catalogReview??={exclusions:{}};
      if(action.reason===null)delete s.catalogReview.exclusions[action.key];
      else if(action.reason?.trim()&&action.baselineVersion)s.catalogReview.exclusions[action.key]={reason:action.reason.trim(),baselineVersion:action.baselineVersion};
      else return old;
      event('核对了基准变量适用性','Reviewed baseline applicability',[action.key,action.reason||'重新核对 / Review again']);
    } else if (action.type === 'SOURCE_REVIEW') {
      s.sourceReviews[action.unitId]=true;event('核对了一条原文说明','Reviewed a source note',[action.label]);
    }
    return s;
  }
  return { reduce, compatible, isolatedSources, mergeProblem, mergeMany, splitProblem };
}
